package slideshow

import org.openrndr.Fullscreen
import org.openrndr.KEY_ARROW_DOWN
import org.openrndr.KEY_ARROW_LEFT
import org.openrndr.KEY_ARROW_RIGHT
import org.openrndr.KEY_ARROW_UP
import org.openrndr.KEY_ENTER
import org.openrndr.KEY_ESCAPE
import org.openrndr.KEY_F5
import org.openrndr.KeyEvent
import org.openrndr.KeyModifier
import org.openrndr.KEY_PAGE_DOWN
import org.openrndr.KEY_PAGE_UP
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorFormat
import org.openrndr.draw.ColorType
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.WrapMode
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadImage
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Vector2
import org.openrndr.draw.loadFont
import org.openrndr.draw.renderTarget
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.math.IntVector2
import org.openrndr.shape.Rectangle
import slideshow.drawers.PlaceholderSlide
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.Type
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Runs a show.
 *
 * This is the only part that touches OPENRNDR's application, and all it does is turn
 * frames into pictures: pump the clock into a [Deck], ask it for the one or two slides
 * that are on screen, draw each into its own buffer and let the transition put them
 * together. It holds no state about the show itself — that is all in the deck.
 *
 * With [Show.panels] set, there are **two** decks side by side rather than one — the
 * slides on the right and, on the left, a card per chapter/subchapter standing beside
 * them. See "two panes" below.
 *
 * A [Backdrop] takes the whole canvas instead — no card, no pane — and the deck steps into
 * and out of one like any other slide. See "the wall" in the draw loop for how a handover
 * between the two shapes is composed.
 *
 *     ->                            next click, running on into the next slide
 *     <-                            back
 *     up / down                     whole slides
 *     0                             back to the first slide
 *     r                             replay this slide from its first click
 *     d                             debug overlay
 *     esc                           quit
 *
 * and with the overlay up, `p` holds the clock and `.` / `,` step it a frame at a time.
 * Those belong to the debug view rather than to the show, so they are dead while it is
 * down — the show itself is four keys and nothing else can be hit by accident.
 *
 * There is no mouse binding and no jump-to-any-slide key: `SLIDES_START` opens on a slide,
 * and the arrows are the whole of it.
 */
fun present(show: Show) {
    // What a filmed run leaves to do once the window has closed and the film is on disk:
    // the soundtrack, rendered from the cue log, and the mix. Set inside the program, run
    // after it — `ScreenRecorder` only finishes its file as the program ends.
    var afterwards: (() -> Unit)? = null
    // What has to be shut however the window closes — the organizer's server, whose thread
    // would otherwise keep the JVM up after the red button.
    var cleanup: (() -> Unit)? = null
    // Read once, lazily, the first time a shape is filled — so it has to be set before the program is.
    // Off unless asked for, watched or filmed alike: the show is native GL, not the ANGLE it works round.
    val settings = show.settings
    if (!(settings.waitForFinish ?: false))
        System.setProperty("org.openrndr.draw.wait_for_finish", "false")
    // The other wait, on every render-target switch — see UnbindFinish. Before the first window,
    // since it has to be in place before OPENRNDR loads the class.
    if (!settings.finishOnUnbind) UnbindFinish.remove()
    application {
        present(show, { afterwards = it }, { cleanup = it })
    }
    cleanup?.invoke()
    afterwards?.invoke()
}

private fun org.openrndr.ApplicationBuilder.present(
    initial: Show, leave: (() -> Unit) -> Unit, onClose: (() -> Unit) -> Unit
) {
    // The show as it plays. A `var`, because the organizer can hand over another arrangement
    // of the same slides while the window is up — the `Apply` command below — and every
    // closure here reads whichever is current. The settings and the cards never change.
    var show = initial
    var slides = show.slides
    val settings = show.settings
    val hasPanels = show.panels.isNotEmpty() && settings.panelWidth != null
    // Every slide the show declares, on or off: what the organizer lists, and what is loaded
    // while it is on, since an order applied live can call for any of them. A `var` because
    // saving the modules file stands a new set of placeholders in it — see Modules.
    var catalogue = initial.source ?: initial
    val organizing = settings.organizer && !settings.stills && !settings.record
    // The client's frames, for the placeholders a saved modules file conjures while the show runs.
    val references = if (organizing) References.read(File(settings.references ?: "export/references"), quiet = true) else References.NONE
    // The two projectors the window spans and where the halves stand on them — see Projectors. Read
    // once: their sizes are the window's. A film, a still or a bench is the canvas and nothing else.
    val projectors = if (settings.record || settings.stills || settings.bench) null
        else Projectors.read(Projectors.plain(settings.width, settings.height)).also { Projectors.started = it }
    val spanned = projectors?.takeUnless { it.plain(settings.width / 2, settings.height) }
    spanned?.let { println("projection: " + Projectors.describe(it)) }

    configure {
        width = ((spanned?.width ?: settings.width) * settings.windowScale).toInt()
        height = ((spanned?.height ?: settings.height) * settings.windowScale).toInt()
        title = settings.title
        hideWindowDecorations = settings.undecorated
        settings.windowX?.let { position = IntVector2(it, settings.windowY ?: 0) }
        //if (settings.fullscreen) fullscreen = Fullscreen.CURRENT_DISPLAY_MODE
        // A filmed run is on the frame clock and a bench times the draws: neither is watched at
        // the display's rate, and waiting for its refresh after every frame is time spent idle.
        if (settings.record || settings.bench) vsync = false
    }

    program {
        // The show's own contents, so the structure declared in Slideshow.kt can be read
        // back without clicking through it.
        println(show.runningOrder())

        // Everything a slide needs is loaded before the first frame: a show must not
        // stall on a click. Panel cards are slides too, and load the same way.
        //
        // **The running order, and only the running order**, organizer or not. The organizer used to
        // load the whole catalogue — the shelf of seventy-odd archived slides and sketch variants —
        // in case an order applied while the window was up called for one: 44 course walls built
        // for the 4 that play, a 4.4M-triangle building nobody saw, some 12 GB. The rest now loads
        // the first time something asks for it — see ensureLoaded below.
        val loaded: MutableSet<Slide> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        slides.forEach { it.load(this); loaded += it }
        show.panels.forEach { it.load(this) }

        val startSlide = startIndex(slides, settings.start)
        val deck = Deck(slides, startSlide, show.outline)
        val clock = Clock()
        var ids = show.slideIds

        // The organizer: a web page that arranges the running order and steers this window.
        // It lives on threads of its own and reaches the deck only through the queue drained
        // at the foot of the draw loop — see Remote.
        val remote = if (organizing)
            Remote(
                show, File(settings.order ?: "show-order.json"), settings.organizerPort, File("build/previews"),
                File(settings.modules ?: "show-modules.json"), references,
                File(settings.intents ?: "show-intents.json"),
                File(settings.feedback ?: "show-feedback.json"),
                File(settings.midi ?: "show-midi.json"),
                subtitlesFile = File(settings.subtitles ?: "show-subtitles.json"),
                subtitlesExtendedFile = File(settings.subtitlesExtended ?: "show-subtitles-extended.json"),
                meetingFile = File(settings.meeting ?: "show-meeting-tasks.json"),
                draaiboekFile = settings.draaiboek?.let { File(it) },
                draaiboekTab = settings.draaiboekTab,
                subtitleFeedbackFile = File(settings.subtitleFeedback ?: "show-subtitle-feedback.json"),
                voiceDir = settings.voice?.let { File(it) },
                open = settings.organizerOpen
            ).takeIf { it.start() }?.also { onClose { it.stop() } }
        else null

        // The cues, decoded before the first frame for the same reason the slides are —
        // a show must not stall on a click. Silent under `stills`, which jumps through
        // every slide of the deck on a timer and would fire every cue in the show at it.
        val speakers = Speakers().apply {
            muted = settings.muted
            settings.levels.forEach { (layer, gain) -> setMix(layer, gain) }
        }

        // The subtitle tracks as speech, where they have been rendered — see VoiceTrack. Read
        // here, ahead of the speakers loading, so the files are decoded with the cues.
        val voices = SubtitleTrack.entries.mapNotNull { track ->
            VoiceTrack.read(settings.voice, track, settings.voiceGain)?.let { track to it }
        }.toMap().toMutableMap()
        // The renderer writes into the folder while the show is up, so it is read again every few
        // seconds and what is new is decoded — a handful of short files, never the whole set. Only
        // under the organizer, which is where lines are rendered and listened to: a show on its own
        // reads the folder once. Not on a filmed run, which must be the same run however long it takes.
        var voicesReadAt = 0

        // The sound design delivered as a folder, re-keyed from the names in that folder to the
        // show's own slide ids — see [CueSheet]. Bound against the whole catalogue rather than
        // the running order, so a slide the order has archived still carries its cues when it is
        // dragged back in. Once, here, because the binding prints what it matched and a report
        // repeated every time the order changed would say nothing new.
        val cueSheet = settings.cueSheet?.boundTo(
            (catalogue.slideIds.zip(catalogue.slides) + show.slideIds.zip(show.slides))
                .associate { (id, slide) -> id to slide.steps }
        )

        /** The id the sheet knows a slide by, which is the id the order file names it by. */
        fun idAt(index: Int) = ids.getOrElse(index) { "" }

        /**
         * What the deck should sound at [step] of the slide at [index] — the sheet's, or the slide's
         * own — at the level the audio timeline review gave that state, where it gave one.
         */
        fun cueAt(index: Int, step: Int) =
            slides.getOrNull(index)?.let { soundAt(it, idAt(index), step, cueSheet) }
                ?.let { settings.gains?.at(it, "${idAt(index)}-${letter(step)}") ?: it }

        /** The playlist of the moment the slide at [index] stands in, where the moment has one and the slide takes it. */
        fun momentBed(index: Int): Sound? = slides.getOrNull(index)?.takeIf { it.momentMusic }
            ?.let { settings.momentMusic[show.outline[index]?.moment.orEmpty()] }

        /** A bed: a loop on the music track — what a moment's playlist takes the place of on its walls. */
        fun isBed(sound: Sound?) = sound != null && sound.loop && sound.layer == Layer.MUSIC

        /**
         * Everything the slide at [index] can be sounding while it stands: its cues, its lead-in, and its
         * moment's playlist in place of any bed of its own. What is let go as it is left, and what is kept
         * playing when the slide arriving sounds it too.
         */
        fun heardAt(index: Int): List<Sound> {
            val slide = slides.getOrNull(index) ?: return emptyList()
            val bed = momentBed(index)
            return cuesOf(slide, idAt(index), cueSheet).filter { bed == null || !isBed(it) } + listOfNotNull(slide.leadIn, bed) +
                    slide.clockCues
        }

        if (settings.sound && !settings.stills) {
            // Every cue the deck can reach: a slide's own, the marks its clicks make, the
            // chapter cards' and the sheet's. The step cues have to be asked for by name — they
            // hang off `stepSound(step)` rather than a property, so a `mapNotNull` over the
            // slides misses them and they arrive at `play` with no buffer to their name.
            //
            // The whole sheet is decoded rather than only the part the running order reaches,
            // for the reason it is bound against the catalogue: an order applied while the
            // window is up may call for any declared slide, and a cue read late is a cue that
            // lands on the click after the one it was for. A slide's own cues are decoded with the
            // slide, so a shelved one brings its cues in when it is loaded — see ensureLoaded.
            val loading = slides to ids
            val declared = loading.first.flatMapIndexed { i: Int, slide: Slide ->
                cuesOf(slide, loading.second.getOrElse(i) { "" }, cueSheet)
            }
            speakers.load(
                declared + loading.first.mapNotNull { it.leadIn } + loading.first.flatMap { it.clockCues } + settings.momentMusic.values + cueSheet?.all.orEmpty() +
                        show.panels.mapNotNull { it.sound } + listOfNotNull(settings.slideBed) +
                        voices.values.flatMap { it.all }
            )
            // Which moment plays which playlist, and where one has nothing to play under: the
            // moments are named in the order file and the music in the show, so a rename in the
            // one without the other is said here rather than heard as a silent course.
            val moments = slides.indices.mapNotNull { show.outline[it]?.moment?.takeIf(String::isNotBlank) }.distinct()
            if (settings.momentMusic.isNotEmpty()) println("music: " + moments.joinToString(", ") { m ->
                "$m ${settings.momentMusic[m]?.file?.name ?: "(none)"}"
            } + (settings.momentMusic.keys - moments.toSet()).takeIf { it.isNotEmpty() }
                ?.let { "; no wall to play under: ${it.joinToString(", ")}" }.orEmpty())
        }

        /**
         * Loads whichever of [wanted] is not loaded yet, and decodes its cues with it.
         *
         * The running order and the cards load before the first frame. Everything else in the
         * catalogue — the shelf, a slide switched off, a sketch shown on the side — loads here, the
         * first time something asks for it: an order that plays it, a preview or an export of it,
         * the overview's side. That is a stall on the frame it happens, a second at most, and it
         * falls on arranging the show rather than on a click of the talk: the order as it stands
         * never waits on it.
         */
        fun ensureLoaded(wanted: List<Slide>) {
            val fresh = wanted.filter { loaded.add(it) }
            if (fresh.isEmpty()) return
            val started = System.nanoTime()
            fresh.forEach { it.load(this) }
            if (settings.sound && !settings.stills) speakers.load(fresh.flatMap { slide ->
                val id = catalogue.slideIds.getOrElse(catalogue.slides.indexOfFirst { it === slide }) { "" }
                cuesOf(slide, id, cueSheet) + listOfNotNull(slide.leadIn) + slide.clockCues
            })
            println("show: loaded %s off the shelf in %.1fs".format(
                fresh.joinToString(", ") { it.name }, (System.nanoTime() - started) / 1e9))
        }

        // --- two panes -------------------------------------------------------------- //
        //
        // Without panels the slide has the whole canvas, exactly as before. With them,
        // the canvas splits into a left pane (panelWidth wide) carrying the chapter card
        // and a right pane (whatever is left, after the gutter) carrying the slide — two
        // independent [Deck]s, each with its own handover, composited side by side into
        // one outer canvas every frame.
        //
        // The panel deck is never driven by a key: it is driven from the slide deck's own
        // position, one step below. It only moves when the *section* changes, which is
        // what keeps the card standing while the slides beside it are clicked through.
        val panelWidth = settings.panelWidth ?: 0
        val gap = if (hasPanels) settings.panelGap else 0
        val slideWidth = settings.width - (if (hasPanels) panelWidth + gap else 0)
        val slideBounds = Rectangle(0.0, 0.0, slideWidth.toDouble(), settings.height.toDouble())
        val panelBounds = Rectangle(0.0, 0.0, panelWidth.toDouble(), settings.height.toDouble())
        val canvasBounds = Rectangle(0.0, 0.0, settings.width.toDouble(), settings.height.toDouble())
        val slideOffsetX = if (hasPanels) (panelWidth + gap).toDouble() else 0.0

        // -1 when the show opens on a backdrop: no card is wanted yet, and the panel deck
        // stands at the first one, unseen, until a slide asks for it.
        val startPanel = show.panelOf.getOrElse(startSlide) { 0 }
        val panelDeck = if (hasPanels) Deck(show.panels, startPanel.coerceAtLeast(0)) else null
        var shownPanel = startPanel
        var shownSlide = startSlide
        // The frame the slide on screen came up on, as last seen: a replay changes it without
        // changing the slide, and a slide that carries its card has to take the card with it.
        var shownStart = 0

        /** The card's resting step: on the left, out of the slide's way. */
        fun closed(panel: Int) = show.panels.getOrNull(panel)?.let { it.steps - 1 } ?: 0

        /**
         * The sting a chapter opens on, fired as its card is announced.
         *
         * Only where the card is genuinely *arriving* — a new section entered forward, or one
         * replayed. Stepping **back** into an earlier chapter is a retrace and lands the card
         * already across, mid-chapter, so it is silent: a cue there would announce a chapter
         * the talk is leaving rather than one it is opening.
         */
        fun announce(panel: Int) {
            // Two openings at one instant is one too many. Where the sheet gives the slide the
            // show is arriving at a cue of its own, that cue *is* the chapter opening — the
            // first sheet's `P1-01-hoe-bouw-je-een-wereld-A` is exactly the sting `1-01` was,
            // redelivered against the slide rather than the card — so the card holds its tongue
            // and the slide speaks. A chapter whose sheet has not arrived still announces.
            if (cueAt(deck.index, 0) != null && cueSheet?.owns(idAt(deck.index)) == true) return
            // it sounds on the arriving slide's opening state, and the review hears it there
            val sting = show.panels.getOrNull(panel)?.sound
            speakers.play(settings.gains?.at(sting, "${idAt(deck.index)}-${letter(0)}") ?: sting)
        }

        /** A slide's own cue, where it has one. Cards are announced separately, above. */
        var soundedSlide = -1

        /** The click its cue was last fired on, so a build marks each one exactly once. */
        var soundedStep = -1

        /** Which slide's own clock was last asked for its cues, from which start, and up to which frame. */
        var clockSlide = -1
        var clockStart = -1
        var clockAsked = 0

        // A contact sheet is a record of the *slides*, and the card standing open is a move
        // rather than a state of one — left open it would cover the first slide of every
        // chapter. So stills start with it already across.
        if (settings.stills) panelDeck?.let { it.goTo(it.index, closed(it.index), cut = true) }

        fun buffer(w: Int, h: Int) = renderTarget(w, h) {
            colorBuffer()
            // stencil as well as depth, so shapes fill and a 3D slide sorts
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }

        // The composed frame, plus a leaving/arriving pair per pane for the handover —
        // compositing finished pictures is what lets two slides with different grounds
        // cross over cleanly, with no moment where one ground is painted over the other.
        val canvas = buffer(settings.width, settings.height)
        val slideCanvas = buffer(slideWidth, settings.height)
        val slideLeaving = buffer(slideWidth, settings.height)
        val slideArriving = buffer(slideWidth, settings.height)
        val panelCanvas = if (hasPanels) buffer(panelWidth, settings.height) else null
        val panelLeaving = if (hasPanels) buffer(panelWidth, settings.height) else null
        val panelArriving = if (hasPanels) buffer(panelWidth, settings.height) else null

        // A backdrop composes for the whole canvas, and a handover with one on either side
        // is composed there too (see "the wall" below), so that needs a leaving and
        // arriving pair at canvas size. Only a show that has a backdrop pays for them.
        // Always under the organizer, which can conjure a wide placeholder while the show runs.
        val hasWide = organizing || slides.any { it.wide }
        val wallLeaving = if (hasWide) buffer(settings.width, settings.height) else null
        val wallArriving = if (hasWide) buffer(settings.width, settings.height) else null

        // How a backdrop takes the stage (see WallBuild): the arriving wall drawn here first, still
        // moving, and then through the build into the frame. Only a show with a build and a backdrop
        // pays for the buffer; stills are all taken on cuts and show the wall itself.
        val wallBuild = settings.wallBuild?.takeIf { hasWide && settings.backdropBuild > 0.0 && !settings.stills }
        val wallBuilding = if (wallBuild != null) buffer(settings.width, settings.height) else null
        wallBuild?.load(this, settings.width, settings.height)

        // A preview is the wall as the show composes it, drawn once more at a fraction of the
        // size: the full canvas first, so a pane and its card compose exactly as they do on
        // the wall, then that minified through its mip chain into a small target and saved.
        val previewCanvas = if (remote != null) buffer(settings.width, settings.height).also {
            it.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
            it.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR
        } else null
        val previewSmall = if (remote != null) renderTarget(
            Remote.PREVIEW_WIDTH, Remote.PREVIEW_WIDTH * settings.height / settings.width
        ) { colorBuffer() } else null
        // The previews still to render — a slide, by id since the order can change under them,
        // and one of its three shots each — one a frame. Every declared slide, on or off.
        val previewJobs = ArrayDeque<Pair<String, Int>>()

        /**
         * An export under way: which slides are left, and how far into the one being written.
         *
         * It is declared **here and not in `draw`**, which is the whole of why the first version
         * did nothing: a `var` inside the draw block is new every frame, so the job was begun,
         * forgotten and begun again, and the page saw an export that was never running. The
         * preview queue beside it has the same reason for standing here.
         *
         * **Its list is `queue` and not `ids`**, which is not fussiness: the draw loop already
         * has an `ids` — the running order — and while this was a local class taking a `val ids`,
         * the getter below read *that* one. The export wrote the right clips under the right
         * names and reported the show's first two slides as the ones it was writing, which is the
         * worst shape a bug can take: correct work, wrong account of it. A name of its own cannot
         * be captured by accident.
         */
        class ExportJob(val queue: List<String>) {
            var at = 0
            var frame = 0
            var frames = 0
            var clip: Clip? = null
            var deck: Deck? = null
            var clicks: List<Int> = emptyList()
            var nextClick = 0
            var pixels: java.nio.ByteBuffer? = null
            var target: RenderTarget? = null
            val written = mutableListOf<String>()
            val id: String get() = queue.getOrElse(at) { "" }
        }

        var job: ExportJob? = null
        if (remote != null) {
            catalogue.slideIds.forEach { id ->
                repeat(3) { k -> if (!File(remote.previewDir, "$id-$k.png").isFile) previewJobs.addLast(id to k) }
            }
        }

        // Only the outer canvas is ever minified — into the window — so it is the only
        // one that needs mipmaps; the panes are blitted into it at their native size.
        canvas.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        canvas.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR

        // The concrete over the whole frame: the show as if projected onto a concrete wall, laid
        // on only where the canvas meets the window, so no slide, still or preview carries it. The
        // wall itself is [ConcreteWall], shared with the sandbox's course studio.
        val concreteWall = ConcreteWall.load(settings.concrete, settings.concreteMix, settings.concreteScale, settings.concreteFloor)
        val concrete = concreteWall?.texture
        val concreteStyle = concreteWall?.style(Vector2(canvasBounds.width, canvasBounds.height))
        var concreteOn = settings.concreteOn && concrete != null

        // The organizer's colours on the wall while the slides are still in the ones this run was
        // built in: one pass as the canvas goes to the window, off until a colour is moved. See
        // BrandPreview, and Brand for why the slides themselves cannot change under a running show.
        val brandPreview = BrandPreview(settings.width, settings.height)

        var debug = settings.debug
        val overlay = DebugOverlay(runCatching { loadFont("data/fonts/default.otf", 13.0) }.getOrNull())

        // The ruled grid over the whole wall: the opening scene's own grid as a debug mode, drawn on
        // the window like the overlay. `g` and the organizer switch it. See GridOverlay.
        var gridOn = false
        val grid = GridOverlay(runCatching { loadFont("data/fonts/default.otf", 12.0) }.getOrNull())

        // The presenter clicker's two extra buttons. Screen held fills a ring and then takes the show
        // back to its start (HoldRestart); play pressed twice opens every slide as thumbnails on the right
        // projector (SlideOverview) — twice rather than held, because the R400 sends play as a whole tap
        // however long it is held, where screen sends a real press and release; a lone press does nothing. The key handlers only
        // note each press and release, in order, and the draw loop times them in frames like everything
        // else — a press and its release landing between two draws still reach it, as a hold of no length.
        fun face(size: Double) = runCatching { loadFont(slideshow.drawers.Type.file, size, contentScale = 1.0) }.getOrNull()
        val holdRing = HoldRestart(face(HoldRestart.LABEL_SIZE))
        val overview = SlideOverview(File("build/previews"), face(SlideOverview.HEADER_SIZE), face(SlideOverview.SMALL_SIZE))
        overview.refresh(ids)
        val screenEdges = ArrayDeque<Boolean>()
        val playEdges = ArrayDeque<Boolean>()
        var screenSince = -1
        var screenSpent = false
        var ringProgress = 0.0
        var ringShown = 0.0
        var overviewOpen = false
        var playTapAt = -1
        /** Practice mode: whether the screen and play buttons do anything. See [Settings.practice]. */
        var practice = settings.practice
        // The overview's hidden section, at its foot: the sketches off the shelf that fill both
        // projectors. One chosen there is shown on the side, in a deck of its own, and never enters the
        // running order; forward or back puts the show back as it was. Read again each time the overview
        // opens, so a sketch placed in the order since stands there instead.
        var shelf: List<Pair<String, Slide>> = emptyList()
        //
        // Every wall off the shelf that can stand on its own across both projectors: the course sketches
        // that fill the wall, and the shelved backdrops and scenes — the block cities, the assemble walls,
        // the yard. Not a wall that only makes sense where the order puts it: a chapter opening, which
        // carries its chapter's card, or a course transition, which goes on from the wall before it. Not a
        // pink placeholder either. One not loaded yet loads as it is picked (ensureLoaded), which may take
        // a second — it is the hidden section, not the talk.
        fun shelfNow() = catalogue.slideIds.zip(catalogue.slides).filter { (id, s) ->
            id !in ids && s.wide && !s.carriesCard && !s.carriesOn && s !is PlaceholderSlide &&
                    (s !is SketchWall || Sketches.fillsTheWall(s.sketch))
        }
        var aside: Deck? = null
        var asideId = ""
        var overviewShown = 0.0
        var overviewGo = false
        var pick = 0
        var uiFrame = 0
        /** The play button sends F5 and Esc by turns, so Esc is the clicker's; shift-Esc still quits. */
        fun isPlay(event: KeyEvent) = event.key == KEY_F5 || (event.key == KEY_ESCAPE && KeyModifier.SHIFT !in event.modifiers)
        /** The screen button sends `b` or `.` by model; `.` stays the debug view's frame step while that is up. */
        fun isScreen(event: KeyEvent) = event.name == "b" || ((event.name == "." || event.name == "period") && !debug)

        // Named into the canvas rather than onto the window, so a filmed run carries it. See
        // Nameplate — it is the one overlay here that is meant to be in the picture.
        val nameplate = if (settings.nameplate)
            Nameplate(runCatching { loadFont("data/fonts/default.otf", NAMEPLATE_SIZE) }.getOrNull()) else null

        // What is said over each state, a card at a time — see Subtitles. Read whether or not the
        // mode is on, so the organizer's button can switch it on mid-show; the face is loaded
        // here rather than on the first card, so switching it on never stalls a frame.
        // Both tracks are read, so the organizer's selector can switch between them mid-show;
        // `subtitles` is whichever is in force. See SubtitleTrack.
        voices.forEach { (track, v) -> println("voice: ${track.key} — ${v.size} states rendered under ${v.dir.path}") }
        val tracks = mutableMapOf(
            SubtitleTrack.DEFAULT to Subtitles.readOrEmpty(settings.subtitles ?: "show-subtitles.json"),
            SubtitleTrack.EXTENDED to Subtitles.readOrEmpty(settings.subtitlesExtended ?: "show-subtitles-extended.json")
        )
        var subtitleTrack = settings.subtitleTrack
        fun subtitles(): Subtitles = tracks[subtitleTrack] ?: Subtitles.EMPTY
        // Spoken or not, apart from whether the words are on the wall — see Settings.voiceOn.
        var voiceOn = settings.voiceOn
        // Whether the presented run clicks on by itself, or says each state's line and waits — see Settings.autoplay.
        var autoplay = settings.autoplay
        fun voice(): VoiceTrack? = voices[subtitleTrack]?.takeIf { voiceOn }
        /** Frames the voice for this state runs, or null where none is rendered or it is off. */
        fun voiceFrames(index: Int, step: Int): Int? = voice()?.frames(ids.getOrElse(index) { "" }, step)
        // The voice being said, and when the one for the state just reached is due — a breath
        // after the click, the same lead the first card takes.
        var voicePlaying: Sound? = null
        var voiceDue = -1
        var voiceCheckAt = -1
        tracks.forEach { (track, it) ->
            if (it.size > 0) println(
                "subtitles: ${track.key} — ${it.size} slides with a line" +
                        (if (track == subtitleTrack && settings.subtitleMode) ", subtitle mode on" else "") +
                        (if (track == SubtitleTrack.EXTENDED) " (presents: the deck runs itself on this track)" else "")
            )
        }
        var subtitleMode = settings.subtitleMode
        val pace = Pace(settings.subtitleCps)
        val subtitleOverlay = SubtitleOverlay(
            runCatching { loadFont(Type.file, SubtitleOverlay.EM, TYPE_CHARACTERS, contentScale = 1.0) }.getOrNull(),
            SubtitleOverlay.EM
        )
        // The state the line is being said over, and the frame it was reached on: a card's time is
        // counted from there, so a click back or a replay says the line again from its start.
        var subtitleState = -1 to -1
        var subtitleSince = 0
        var subtitleStageFrame = 0

        /** Draws one slide into its own buffer, from a known drawer state. */
        fun paint(target: RenderTarget, shot: Deck.Shot) {
            drawer.isolatedWithTarget(target) {
                drawer.ortho(target)
                drawer.clear(shot.slide.background)
                drawer.fill = ColorRGBa.BLACK
                drawer.stroke = null
                drawer.strokeWeight = 1.0
                drawer.shadeStyle = null
                shot.slide.draw(drawer, shot.stage)
            }
        }

        /**
         * Renders one pane's deck for this frame — its current slide, and while a
         * handover runs, the one it is leaving — into [target]. Returns the arriving
         * slide's [Stage], which is all the debug overlay needs.
         */
        fun renderPane(
            target: RenderTarget, leavingBuf: RenderTarget, arrivingBuf: RenderTarget,
            deck: Deck, bounds: Rectangle
        ): Stage {
            val leaving = deck.leavingShot(bounds)
            val arriving = deck.shot(bounds)
            if (leaving == null) {
                paint(target, arriving)
            } else {
                paint(leavingBuf, leaving)
                paint(arrivingBuf, arriving)
                drawer.isolatedWithTarget(target) {
                    drawer.ortho(target)
                    // the ground a push slides over; a fade never shows it
                    drawer.clear(arriving.slide.background)
                    deck.composite(drawer, leavingBuf.colorBuffer(0), arrivingBuf.colorBuffer(0))
                }
            }
            return arriving.stage
        }

        /**
         * Whether the chapter card is still holding the frame — a card that declares two
         * steps and has not been opened yet. While it is, the arrows belong to *it*: the
         * card is over the slide pane and the click that carries it across is its own beat,
         * not a click of the slide underneath. Never under a backdrop: there is no card on
         * the wall to hold it.
         */
        fun cardHoldsTheFrame(): Boolean = !deck.slide.wide &&
                panelDeck != null && panelDeck.slide.steps > 1 && panelDeck.step == 0

        /**
         * True when the slide deck is on the very first click of its section. A backdrop has none;
         * a wide slide that carries its card is the card, and opens its section like any slide.
         */
        fun atSectionStart(): Boolean = (!deck.slide.wide || deck.slide.carriesCard) && deck.step == 0 && (deck.index == 0 ||
                show.panelOf.getOrElse(deck.index) { -1 } != show.panelOf.getOrElse(deck.index - 1) { -2 })

        fun forward() = if (cardHoldsTheFrame()) panelDeck!!.next() else deck.next()

        /** Back over the opening click re-covers the slide, so going back undoes it exactly. */
        fun backward() {
            if (panelDeck != null && panelDeck.slide.steps > 1 &&
                panelDeck.step > 0 && atSectionStart()) panelDeck.back() else deck.back()
        }

        /**
         * `0` puts the card back over the slide, fresh, so the show is exactly as it boots.
         * On a backdrop there is no card to put back; it arrives with the first slide.
         */
        fun openCard() {
            val panel = panelDeck ?: return
            val target = show.panelOf.getOrElse(deck.index) { 0 }
            if (target < 0) return
            if (panel.index == target) panel.replay() else panel.goTo(target, 0, cut = true)
            shownPanel = target
            // `0` puts the deck back exactly as it boots, the card's own cue included
            announce(target)
        }

        // Key handlers move the slide deck and nothing else — they never read a clock,
        // and they never touch the panel deck directly (see above). Under ScreenRecorder
        // the draw loop runs on video time while a handler sees wall time, so a timestamp
        // taken here would sit in the draw loop's future (the note under demo01 in
        // CLAUDE.md). Here there is no timestamp to take: the deck animates against the
        // frame it is ticked to.
        // Whether the next draw has to paint the wall afresh whatever the frame — see "the repaint"
        // in the draw loop. A key, a clicker edge or the organizer can move the deck, or change what a
        // slide that listens to keys draws, between two draws.
        var repaint = true

        keyboard.keyDown.listen { event ->
            repaint = true
            // Every key the show is handed goes to the output, so a clicker or keyboard that does
            // something unexpected on site can be read back from the terminal.
            println("key: ${event.name} (${event.key})" + if (event.modifiers.isEmpty()) "" else " with ${event.modifiers.joinToString("+")}")
            // The clicker's held buttons, and the overview while it is open, before anything else. Out of
            // practice mode they are taken and dropped, so a wrong press in front of the room does nothing.
            if (isPlay(event)) { if (practice) playEdges.addLast(true); return@listen }
            if (isScreen(event)) { if (practice) screenEdges.addLast(true); return@listen }
            if (overviewOpen) {
                val last = ids.size + shelf.size - 1
                when (event.key) {
                    KEY_ARROW_RIGHT, KEY_PAGE_DOWN -> pick = (pick + 1).coerceAtMost(last)
                    KEY_ARROW_LEFT, KEY_PAGE_UP -> pick = (pick - 1).coerceAtLeast(0)
                    KEY_ARROW_DOWN -> pick = overview.neighbour(pick, 1).coerceIn(0, last)
                    KEY_ARROW_UP -> pick = overview.neighbour(pick, -1).coerceIn(0, last)
                    KEY_ENTER -> overviewGo = true
                }
                return@listen
            }
            // Forward or back off a sketch shown on the side puts the show back where it was, unclicked.
            if (aside != null && (event.key == KEY_ARROW_RIGHT || event.key == KEY_ARROW_LEFT ||
                    event.key == KEY_PAGE_DOWN || event.key == KEY_PAGE_UP)) {
                aside = null
                println("show: back to the show from #$asideId")
                return@listen
            }
            // The slide on screen asks first, for controls of its own.
            if (event.key != KEY_ESCAPE && deck.slide.key(event.name)) return@listen
            when {
                // A presenter clicker is a keyboard to the machine: the Logitech R400's forward and
                // back buttons send Page Down and Page Up, so those click exactly as the arrows do.
                event.key == KEY_ARROW_RIGHT || event.key == KEY_PAGE_DOWN -> forward()
                event.key == KEY_ARROW_LEFT || event.key == KEY_PAGE_UP -> backward()
                event.key == KEY_ARROW_DOWN -> deck.nextSlide()
                event.key == KEY_ARROW_UP -> deck.previousSlide()
                event.key == KEY_ESCAPE -> { println("show: shift-escape — closing"); speakers.close(); application.exit() }

                event.name == "0" -> { deck.home(); openCard() }
                event.name == "r" -> deck.replay()
                event.name == "d" -> debug = !debug
                event.name == "g" -> gridOn = !gridOn
                event.name == "s" -> subtitleMode = !subtitleMode
                event.name == "v" -> voiceOn = !voiceOn

                // The clock controls are part of the debug view, not of the show, so they
                // do nothing while it is down.
                debug && event.name == "p" -> clock.paused = !clock.paused
                debug && (event.name == "." || event.name == "period") -> clock.step(1)
                debug && (event.name == "," || event.name == "comma") -> clock.step(-1)
            }
        }
        keyboard.keyUp.listen { event ->
            repaint = true
            when {
                event.key == KEY_F5 || event.key == KEY_ESCAPE -> { println("key up: ${event.name}"); playEdges.addLast(false) }
                event.name == "b" || event.name == "." || event.name == "period" -> { println("key up: ${event.name}"); screenEdges.addLast(false) }
            }
        }

        // The state log's marks, kept for the whole run and written beside the film at the end.
        val stateMarks = mutableListOf<StateMark>()
        var markedSlide = -1
        var markedStep = -1

        // Where the draws went, slide by slide, on a filmed run — see DrawTimes.
        val drawTimes = DrawTimes()

        if (settings.record) {
            // so the clip comes out at the canvas size, not the window's
            if (settings.recorder == "screen") extend(ScreenRecorder().apply {
                outputFile = settings.video
                frameRate = settings.fps
                contentScale = 1.0 / settings.windowScale
                settings.duration?.let { maximumDuration = it }
            }) else extend(
                WallRecorder(File(settings.video), settings.fps, 1.0 / settings.windowScale,
                    settings.duration ?: Double.POSITIVE_INFINITY)
            )
            // The soundtrack is rendered from the log once the film is on disk, which is
            // after the program ends — so it is handed out rather than done here. The
            // frame count is the clock's last, which is the film's length in deck frames.
            leave {
                Soundtrack.export(speakers.log.toList(), clock.frame, File(settings.video), settings.mix,
                    Layer.entries.associateWith { speakers.mixOf(it) })
                // What was on screen when, for cutting the film into its states — see StatesLog.
                StatesLog.write(stateMarks, clock.frame, show, StatesLog.file(File(settings.video)))
                drawTimes.report(DrawTimes.file(File(settings.video)))
            }
        }

        // One png per click of every slide, then quit: the whole deck as a contact sheet,
        // which is how to check a change without clicking through it.
        val plan = if (settings.stills)
            slides.indices.flatMap { s -> (0 until slides[s].steps).map { s to it } } else emptyList()
        var planned = 0
        var heldSince = 0

        val autoStepFrames = frames(settings.autoStep)
        var lastAutoStep = 0

        // A written run: hold this many frames, click, hold the next many. Nothing here reads
        // a clock — the cue is measured against the frame the last one was taken on, so a
        // filmed run and a watched one are the same run. Written by hand, or by the deck
        // itself off what each state needs; either way the last hold is how long the final
        // state stands before a filmed run ends.
        // Where a hands-off run stops. It bounds the cue list rather than the deck, so the whole
        // show is still there to click into — see [Settings.until].
        val untilSlide = untilIndex(slides, settings.until, startSlide)
        val holds = if (settings.cuesAuto)
            autoCues(slides, ids, startSlide, untilSlide, settings, subtitles().takeIf { subtitleMode || voiceOn }, pace, voice())
        else settings.cues.map { frames(it) }
        val cueFrames = if (settings.cuesAuto) holds.dropLast(1) else holds
        val finalHold = holds.lastOrNull() ?: 0
        var cue = 0
        var cueAt = 0
        var ended = false
        var fps = 0.0
        var lastSeconds = 0.0
        // The same for the draws that painted, which on a screen faster than the deck's clock is fewer.
        var paintFps = 0.0
        var lastPainted = 0.0
        // What the wall was last painted at, and the slide's stage then — see "the repaint".
        var paintedAt: List<Any?> = emptyList()
        var paintedStage: Stage? = null

        // A bench: every state of the running order from the start to the until, timed. The clock
        // is held and stepped by hand, a sample at a time across each state's hold, so the draws
        // it times are the frames a filmed run would draw there. See Settings.bench.
        val benchPlan = if (settings.bench) (startSlide..untilSlide).flatMap { s -> (0 until slides[s].steps).map { s to it } } else emptyList()
        val benchHolds = if (settings.bench) (if (settings.cuesAuto) holds else
            autoCues(slides, ids, startSlide, untilSlide, settings, subtitles().takeIf { subtitleMode || voiceOn }, pace, voice())) else emptyList()
        val benchMillis = DoubleArray(benchPlan.size)
        var benchAt = 0
        var benchDrawn = 0
        if (settings.bench) {
            clock.paused = true
            speakers.muted = true
            println("bench: ${benchPlan.size} states, ${settings.benchSamples} draws each across its hold")
        }

        // Ahead of the draw below, so its frame can be laid onto the projectors after it — see ProjectorSplit.
        projectors?.let { extend(ProjectorSplit(it, settings.width, settings.height)) }

        extend {
            val drawStarted = System.nanoTime()
            drawTimes.lap(ids.getOrElse(deck.index) { deck.slide.name })
            if (settings.bench && benchDrawn > 0) {
                val hold = benchHolds.getOrElse(benchAt) { frames(settings.hold) }
                clock.step((hold / settings.benchSamples).coerceAtLeast(1))
            }
            // The one place a clock is read, and it is read inside the draw loop, so it is
            // video time while recording and wall time otherwise. Everything downstream
            // sees frame numbers.
            val frame = clock.advance(seconds)

            // The clicker's held buttons, timed here — see their declaration.
            val dt = (frame - uiFrame).coerceIn(0, 10)
            uiFrame = frame
            // Practice mode switched off with either up: both go, and nothing half done is left to finish.
            if (!practice) {
                screenEdges.clear(); playEdges.clear()
                screenSince = -1; playTapAt = -1
                if (overviewOpen) { overviewOpen = false; println("show: overview closed") }
                if (aside != null) { aside = null; println("show: back to the show from #$asideId") }
            }
            while (screenEdges.isNotEmpty()) {
                if (screenEdges.removeFirst()) {
                    // A press of screen closes the overview, and is spent doing it.
                    if (overviewOpen) { overviewOpen = false; println("show: overview closed") }
                    else { screenSince = frame; screenSpent = false }
                } else screenSince = -1
            }
            // The ring shows only once the button has been held a moment, so a tap shows nothing.
            val ringDelay = frames(0.2)
            val ringLength = frames(settings.holdRestart).coerceAtLeast(ringDelay + 1)
            val ringHeld = screenSince >= 0 && !screenSpent && frame - screenSince >= ringDelay
            if (screenSince >= 0 && !screenSpent) {
                val held = frame - screenSince
                ringProgress = ((held - ringDelay).toDouble() / (ringLength - ringDelay)).coerceIn(0.0, 1.0)
                if (held >= ringLength) {
                    screenSpent = true
                    println("show: screen button held — back to the start")
                    aside = null
                    deck.home()
                    openCard()
                }
            }
            ringShown = if (ringHeld) min(1.0, ringShown + dt / 6.0) else max(0.0, ringShown - dt / 10.0)

            while (playEdges.isNotEmpty()) {
                // Two presses of play close together open the overview; in it, one starts the show at the pick.
                if (playEdges.removeFirst()) {
                    when {
                        overviewOpen -> { overviewGo = true; playTapAt = -1 }
                        playTapAt >= 0 && frame - playTapAt <= frames(settings.overviewDouble) -> {
                            overviewOpen = true
                            playTapAt = -1
                            shelf = shelfNow()
                            pick = if (aside != null) ids.size + shelf.indexOfFirst { it.first == asideId }.coerceAtLeast(0) else deck.index
                            overview.refresh(ids + shelf.map { it.first })
                            println("show: overview open")
                        }
                        else -> playTapAt = frame
                    }
                }
            }
            if (overviewGo) {
                overviewGo = false
                if (overviewOpen) {
                    overviewOpen = false
                    if (pick >= ids.size) {
                        // A sketch from the foot: shown on the side, from its own beginning.
                        shelf.getOrNull(pick - ids.size)?.let { (id, slide) ->
                            ensureLoaded(listOf(slide))
                            aside = Deck(listOf(slide), 0, Outline.EMPTY).also { it.tick(frame); it.replay() }
                            asideId = id
                            println("show: #$id on the side — forward or back returns to the show")
                        }
                    } else if (pick == deck.index && aside == null) {
                        // The pick left on the slide already up closes the overview and leaves it standing,
                        // rather than cutting it back to its first state.
                        println("show: overview closed")
                    } else {
                        aside = null
                        println("show: overview — starting at #${ids.getOrElse(pick) { "?" }}")
                        if (pick != deck.index) deck.goTo(pick.coerceIn(0, ids.size - 1), 0, cut = true)
                    }
                }
            }
            overviewShown = if (overviewOpen) min(1.0, overviewShown + dt / 5.0) else max(0.0, overviewShown - dt / 8.0)

            deck.tick(frame)
            panelDeck?.tick(frame)
            aside?.tick(frame)
            // The state log: one mark per state the deck reaches, on the frame the click starts.
            // Frame-accurate by construction, because it is read off the deck the film is
            // drawn from rather than worked out again afterwards.
            if (settings.record && (deck.index != markedSlide || deck.step != markedStep)) {
                stateMarks += StateMark(frame, deck.index, deck.step)
                markedSlide = deck.index
                markedStep = deck.step
            }
            // fades run off the same frame count as everything else, so a bed comes up over
            // the same six seconds whether the show is watched, filmed or stepped
            speakers.tick(frame)

            // Drive the panel from the slide deck's own position: it only moves when the
            // chapter or subchapter changes, never on an ordinary click — a soft handover
            // of its own, played in reverse when the show steps back into an earlier one.
            //
            // *Which step* it arrives on is the other half. A card comes up open, over the
            // slide, when the show steps forward into its section; stepping back into a
            // section lands mid-chapter, where the card belongs on the left and was never
            // opened again, so it arrives already across.
            if (panelDeck != null) {
                val wanted = show.panelOf.getOrElse(deck.index) { shownPanel }
                val opening = atSectionStart() && !settings.stills

                // Forward out of a backdrop into a section. There was no card on the wall,
                // so the one wanted now *arrives* — from its first frame, with its own
                // entrance — rather than standing there already built: it has been ticking
                // unseen since the show booted or last left it, and an opening scene may
                // have stood for an hour.
                val fromWide = deck.index > shownSlide && slides[shownSlide].wide

                when {
                    // A backdrop wants no card. The panel deck holds where it is, unseen,
                    // so stepping back into the section finds the card as it was left.
                    wanted < 0 -> {}

                    // A wide slide that carries its card: the card is on the wall, drawn by the
                    // slide, so it starts with the slide — on the very frame the slide came up,
                    // which may have been between two draws, so the two are one picture and the
                    // slide after it finds the card exactly where the wall left it. Forward it is
                    // the chapter opening, announced like any. Stepped back into, it lands built,
                    // the deck's rule for going back: its clock set to where its reveal has come
                    // to rest, and the card's with it. A replay runs both again from the start.
                    deck.slide.carriesCard && (deck.index != shownSlide || deck.startedAt != shownStart) -> {
                        if (deck.index < shownSlide) deck.restart(deck.index, deck.step, since = deck.frame - deck.slide.settle)
                        panelDeck.restart(wanted, closed(wanted), since = deck.startedAt)
                        if (deck.index > shownSlide) announce(wanted)
                    }

                    fromWide && opening -> {
                        if (wanted != panelDeck.index) panelDeck.goTo(wanted, 0, cut = true)
                        else panelDeck.replay()
                        announce(wanted)
                    }

                    wanted != shownPanel -> {
                        panelDeck.goTo(wanted, if (opening) 0 else closed(wanted), cut = false)
                        // forward into a new chapter announces; stepping back retraces, silent
                        if (opening) announce(wanted)
                    }

                    // up/down cross whole slides without ever offering the card its click,
                    // so it would be left standing over a slide it does not belong to.
                    deck.index != shownSlide && !opening && panelDeck.step != closed(wanted) ->
                        panelDeck.goTo(wanted, closed(wanted), cut = false)
                }
                if (wanted >= 0) shownPanel = wanted
                shownSlide = deck.index
                shownStart = deck.startedAt
            }

            // A slide's own cue, where it declares one, as it comes up. Also the card the
            // show *boots* on: opening straight into a chapter passes through none of the
            // branches above, because nothing changed — the card was simply already there.
            if (deck.index != soundedSlide) {
                val first = soundedSlide < 0
                val leaving = slides.getOrNull(soundedSlide)
                val soundedFrom = soundedSlide
                soundedSlide = deck.index

                // Every cue that belongs to the slide being left goes out — its arrival cue and
                // any of its click marks, since either may be sustained. Held back only where
                // the slide arriving stands on the same file, so a bed spanning two slides
                // keeps playing rather than dipping between them. A sting declares no fade, so
                // it is not sustained and is left to ring out.
                // A moment's playlist runs on from one of its walls to the next the same way, and
                // the slide arriving may ask for what it leaves to be let go more slowly than its
                // own fade — the course transition quietens the dinner over seconds, not a beat.
                if (leaving != null) {
                    val arrivingFiles = heardAt(deck.index).mapTo(mutableSetOf()) { it.file }
                    val over = deck.slide.outgoingFade
                    heardAt(soundedFrom)
                        .filter { it.sustained && it.file !in arrivingFiles }
                        .forEach { speakers.release(if (over != null && over > it.fadeOut) it.copy(fadeOut = over) else it) }
                }

                // The slide's own cue, unless it is a bed and the moment has a playlist of its own
                // to play instead; then the playlist, which asked for again does not restart.
                val bed = momentBed(deck.index)
                val own = cueAt(deck.index, 0)
                speakers.play(if (bed != null && isBed(own)) null else own)
                speakers.play(bed)
                // The way in, beside the state's cue: coming forward, or opening on it.
                if (first || deck.index > soundedFrom) speakers.play(deck.slide.leadIn)
                // The bed under the talk: up on any slide of a chapter, let go while a wall
                // or a scene is up. Asking for it again on the next slide does not restart it —
                // a held loop only picks its fade up from where it stands — so it runs on
                // unbroken from one slide to the next.
                val walled = deck.slide.wide && !deck.slide.carriesCard
                settings.slideBed?.let { if (walled) speakers.release(it) else speakers.play(it) }
                soundedStep = deck.step
                if (first && startPanel >= 0 && !walled) announce(startPanel)

            } else if (deck.step != soundedStep) {
                // A built slide marks its clicks: a band landing on the stack, and so on.
                // Forward only — clicking back through a build is a correction, and re-firing
                // the marks would say it is being built when it is being taken apart.
                if (deck.step > soundedStep) speakers.play(cueAt(deck.index, deck.step))
                soundedStep = deck.step
            }

            // A slide that turns over on its own clock marks each turn off its own frame count (see
            // Slide.cuesBetween): whatever fell due since the frame last asked, so a draw that skips
            // frames still sounds every turn once. Counted afresh when the slide comes up or replays.
            if (deck.index != clockSlide || deck.startedAt != clockStart) {
                clockSlide = deck.index; clockStart = deck.startedAt; clockAsked = 0
            }
            val clockFrame = frame - deck.startedAt
            if (clockFrame > clockAsked) {
                deck.slide.cuesBetween(clockAsked, clockFrame).forEach { speakers.play(it) }
                clockAsked = clockFrame
            }

            fps = mix(fps, 1.0 / (seconds - lastSeconds).coerceAtLeast(1e-4), 0.1)
            lastSeconds = seconds

            if (settings.stills) {
                val (slide, step) = plan[planned]
                if (deck.index != slide || deck.step != step) {
                    deck.goTo(slide, step, cut = true)
                    heldSince = frame
                }
            } else if (settings.bench) {
                if (benchAt < benchPlan.size) {
                    val (slide, step) = benchPlan[benchAt]
                    if (deck.index != slide || deck.step != step) {
                        deck.goTo(slide, step, cut = true)
                        benchDrawn = 0
                    }
                }
            } else if (holds.isNotEmpty()) {
                if (cue < cueFrames.size) {
                    if (frame - cueAt >= cueFrames[cue]) {
                        // Counted from when the click was due rather than when it landed: at a
                        // recording rate under the deck's the clock moves several frames a draw,
                        // and taken from the landing each click would start up to a draw late and
                        // the lateness would add up across the run.
                        cueAt += cueFrames[cue]
                        cue++
                        forward()
                    }
                } else if (settings.record && !ended && frame - cueAt >= finalHold) {
                    // A filmed run ends one hold after its last click; a watched one stands.
                    ended = true
                    speakers.close()
                    application.exit()
                }
            } else if (autoplay && (subtitleMode || voiceOn) && subtitleTrack == SubtitleTrack.EXTENDED && !settings.stills) {
                // The presented run: on the extended track the deck runs itself, each state held
                // for what a hands-off run would hold it — its line said, and a beat after — and
                // then clicked on. The arrows still work: a click lands on a new state, whose
                // clock starts again from there. It stands where the deck runs out. See SubtitleTrack.
                val stand = standFrames(deck.slide, deck.step, subtitles()[ids.getOrElse(deck.index) { "" }, deck.step], settings, pace,
                    voiceFrames(deck.index, deck.step), voice()?.speech(ids.getOrElse(deck.index) { "" }, deck.step))
                val more = deck.step < deck.slide.steps - 1 || deck.index < slides.lastIndex
                if (more && frame - subtitleSince >= stand) forward()
            } else if (autoStepFrames > 0 && frame - lastAutoStep >= autoStepFrames) {
                lastAutoStep = frame
                forward()
            }

            // --- the wall ------------------------------------------------------------ //
            //
            // A slide composes for its pane and a backdrop for the whole canvas, so a
            // handover with a backdrop on either side cannot be composed pane by pane: the
            // two sides are different shapes. It is composed on the wall instead — the
            // leaving picture is whatever the whole frame showed, a slide beside its card
            // or a backdrop edge to edge, the arriving picture likewise, and the transition
            // mixes those two. Between two slides nothing changes: each pane still hands
            // over on its own.
            val arriving = deck.slide
            val leaving = deck.leavingSlide
            val crossing = leaving != null && (arriving.wide || leaving.wide)

            // --- the repaint ----------------------------------------------------------- //
            //
            // Every slide is a function of its frame and the deck's moves, so a frame already
            // painted at this count would paint the same pixels again. The deck's clock runs at 60
            // and the MacBook's screen at 120, so half the draws there were exactly that; they now
            // show the canvas painted last, and only the window's own layers — the concrete, the
            // overlays — are drawn again. The sound, the organizer and the voice still run every
            // draw. Never on a filmed run, a bench or stills, which step their own clocks.
            val paintKey = listOf<Any?>(
                frame, deck.moves, panelDeck?.moves, aside?.let { System.identityHashCode(it) }, aside?.moves,
                subtitleMode, subtitleTrack, voiceOn
            )
            val fresh = repaint || paintedStage == null || paintKey != paintedAt ||
                    settings.record || settings.bench || settings.stills
            repaint = false
            paintedAt = paintKey
            if (fresh) {
                paintFps = mix(paintFps, 1.0 / (seconds - lastPainted).coerceAtLeast(1e-4), 0.1)
                lastPainted = seconds
            }

            // The card is rendered whenever it can be seen: beside a slide, or in a wall a
            // slide is leaving or arriving as. Under a backdrop standing alone it is not
            // asked for — a mosaic card repaints its plate every frame, for nobody.
            val panelStage = if (fresh && panelDeck != null && (crossing || !arriving.wide))
                renderPane(panelCanvas!!, panelLeaving!!, panelArriving!!, panelDeck, panelBounds) else null

            /**
             * The two panes composed into [target]: the wall as the presentation shows it.
             * [opened] is how far a two-step card has crossed to its own pane; 1 at rest.
             */
            fun composePanes(target: RenderTarget, opened: Double) = drawer.isolatedWithTarget(target) {
                drawer.ortho(target)
                // shows in the gutter, and behind a pane that does not fill the canvas
                drawer.clear(settings.gutter)

                // The slide first and the card *over* it. The card opens on top of the
                // slide pane and is carried across to its own, so for half a click it is in
                // front of the slide — which is only possible if it is composited second.
                // At rest the two do not overlap and the order costs nothing.
                drawer.image(slideCanvas.colorBuffer(0), slideOffsetX, 0.0, slideWidth.toDouble(), settings.height.toDouble())
                if (panelCanvas != null) {
                    // Where the card *is*, which no card can draw for itself: its pane is
                    // 1920 wide and the move crosses 3840. `opened` is the card's own opening
                    // click, eased by the deck like any other, so the slide is uncovered at
                    // exactly the rate the title crosses. A one-step card never leaves home.
                    drawer.image(
                        panelCanvas.colorBuffer(0), slideOffsetX * (1.0 - opened), 0.0,
                        panelWidth.toDouble(), settings.height.toDouble()
                    )
                }
            }

            // How far the card has crossed this frame: its opening click, or home.
            val cardOpened = if (panelDeck != null && panelDeck.slide.steps > 1 && panelStage != null) panelStage.on(1) else 1.0

            /**
             * A wide shot into [target] — through the show's [WallBuild] while a backdrop the deck came
             * to going forward is still coming up, and otherwise the wall itself. [arriving] is false for
             * the wall a handover is leaving, which is never built again.
             */
            fun paintWide(target: RenderTarget, shot: Deck.Shot, arriving: Boolean) {
                val build = wallBuild
                val buf = wallBuilding
                val length = frames(settings.backdropBuild)
                if (build == null || buf == null || !arriving || !shot.slide.buildsIn || deck.arrivedBack ||
                    shot.stage.frame >= length) {
                    paint(target, shot)
                    return
                }
                paint(buf, shot)
                drawer.isolatedWithTarget(target) {
                    drawer.ortho(target)
                    drawer.clear(build.ground)
                    drawer.fill = ColorRGBa.WHITE
                    drawer.stroke = null
                    drawer.shadeStyle = null
                    build.draw(drawer, buf.colorBuffer(0), target.width.toDouble(), target.height.toDouble(),
                        shot.stage.frame.toDouble() / length)
                }
            }

            /** The whole wall for one shot: a backdrop edge to edge, or a slide beside its card. */
            fun wall(target: RenderTarget, shot: Deck.Shot, arriving: Boolean) {
                if (shot.slide.wide) paintWide(target, shot, arriving)
                else {
                    paint(slideCanvas, shot)
                    composePanes(target, cardOpened)
                }
            }

            fun boundsOf(slide: Slide) = if (slide.wide) canvasBounds else slideBounds

            /** A slide standing at [step], [frame] frames in, with nothing arriving or leaving. */
            fun stageAt(slide: Slide, bounds: Rectangle, step: Int, frame: Int) = Stage(
                bounds = bounds, frame = frame, steps = slide.steps, step = step, position = step.toDouble(),
                enter = 1.0, exit = 0.0,
                loop = if (slide.loop > 0) (frame % slide.loop).toDouble() / slide.loop else 0.0,
                cycle = if (slide.loop > 0) frame / slide.loop else 0
            )

            /**
             * One preview: slide [index] at its shot [k], composed as the wall would show it —
             * a slide beside its own chapter's card, closed and settled — and saved small. It
             * paints into the live pane buffers, which the next frame repaints anyway.
             */
            fun renderPreview(id: String, k: Int) {
                val target = previewCanvas ?: return
                val small = previewSmall ?: return
                // In the running show first, so a slide moved into another chapter previews
                // beside its new card; in the catalogue for one that is off or on the shelf.
                val (of, index) = ids.indexOf(id).takeIf { it >= 0 }?.let { show to it }
                    ?: catalogue.slideIds.indexOf(id).takeIf { it >= 0 }?.let { catalogue to it }
                    ?: return
                val slide = of.slides[index]
                ensureLoaded(listOf(slide))
                val shot = Remote.previewShots(slide)[k]
                val stage = stageAt(slide, boundsOf(slide), shot.step, shot.frame)
                if (slide.wide) paint(target, Deck.Shot(slide, stage))
                else {
                    val panel = of.panelOf.getOrElse(index) { -1 }
                    if (panelCanvas != null && panel >= 0) {
                        val card = of.panels[panel]
                        // Ten seconds in, or later where the card's own reveal takes longer to rest.
                        paint(panelCanvas, Deck.Shot(card, stageAt(card, panelBounds, closed(panel), maxOf(600, card.settle))))
                    }
                    paint(slideCanvas, Deck.Shot(slide, stage))
                    composePanes(target, 1.0)
                }
                target.colorBuffer(0).generateMipmaps()
                drawer.isolatedWithTarget(small) {
                    drawer.ortho(small)
                    drawer.clear(ColorRGBa.BLACK)
                    drawer.image(target.colorBuffer(0), 0.0, 0.0, small.width.toDouble(), small.height.toDouble())
                }
                remote!!.previewDir.mkdirs()
                small.colorBuffer(0).saveToFile(File(remote.previewDir, "$id-$k.png"))
            }

            // ---------------------------------------------------------------------------- //
            //  Exporting the ticked slides, a clip and a score each
            //
            //  **It is rendered, not filmed.** `ScreenRecorder` writes one file per program, so
            //  several slides in one session cannot be filmed that way at all (the note under
            //  CardStudio); and nothing here has to run at the rate it plays at, since every
            //  drawer is a pure function of its frame. So a throwaway `Deck` of one slide is
            //  ticked frame by frame, painted into the pane buffers the way a preview is, and
            //  read straight into ffmpeg. The clicks land where `midiClicks` puts them — the
            //  same list the score is written against — so the two cannot disagree.
            //
            //  It is done a chunk of frames at a time rather than all at once, so the window
            //  stays alive and the page can show how far it has got.

            /** Opens the next slide of [j]: its score first, then the pipe it will be drawn into. */
            fun beginExport(j: ExportJob): Boolean {
                val id = j.queue.getOrNull(j.at) ?: return false
                val index = catalogue.slideIds.indexOf(id).takeIf { it >= 0 } ?: return false
                val slide = catalogue.slides[index]
                // A slide that runs on into the next is filmed and scored across the click into it.
                val slides = listOfNotNull(slide, slide.runsInto)
                ensureLoaded(slides)
                val w = if (slide.wide) settings.width else slideWidth
                slides.forEach { it.layOut(w, settings.height) }
                val run = exportRun(slide, settings.hold)
                val tracks = run.tracks
                val mid = remote!!.midiFileOf(id)
                writeMidi(mid, tracks)
                j.clicks = run.clicks
                j.nextClick = 0
                j.frame = 0
                j.frames = run.frames
                j.deck = Deck(run.slides, 0, Outline.EMPTY)
                j.pixels = java.nio.ByteBuffer.allocateDirect(w * settings.height * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                // A target of its own rather than the show's pane buffers. A preview may scribble
                // on those and be repainted next frame without anyone seeing it; an export paints
                // hundreds of frames into them per tick, and on a wide slide that buffer is the
                // one the window is showing — so the wall would flicker with the clip being made.
                j.target = buffer(w, settings.height)
                j.clip = Clip(remote.clipFileOf(id), w, settings.height, settings.fps)
                j.written += mid.path
                println(
                    "export: %s — %d notes on %d tracks → %s, %.1fs of %dx%d → %s"
                        .format(id, tracks.sumOf { it.notes.size }, tracks.size, mid.path,
                            seconds(j.frames), w, settings.height, remote.clipFileOf(id).path)
                )
                return true
            }

            /** As many of this slide's frames as the tick can spare. */
            fun stepExport(j: ExportJob) {
                val deck = j.deck ?: return
                val clip = j.clip ?: return
                val pixels = j.pixels ?: return
                val slide = deck.slides[0]
                val bounds = boundsOf(slide)
                val target = j.target ?: return
                val until = System.currentTimeMillis() + EXPORT_BUDGET
                do {
                    // The studio's own order: tick, then take the click that falls on this frame,
                    // then draw — so a filmed run and this one are the same run.
                    deck.tick(j.frame)
                    while (j.nextClick < j.clicks.size && j.frame >= j.clicks[j.nextClick]) {
                        deck.next(); j.nextClick++
                    }
                    paint(target, deck.shot(bounds))
                    target.colorBuffer(0).read(pixels, ColorFormat.RGBa, ColorType.UINT8)
                    clip.frame(pixels)
                    j.frame++
                } while (j.frame < j.frames && System.currentTimeMillis() < until)
            }

            /** Finishes this slide's clip and moves to the next, or ends the job. */
            fun endExport(j: ExportJob) {
                val count = j.clip?.close() ?: 0
                if (j.clip?.ok == true) j.written += remote!!.clipFileOf(j.id).path
                println("export: ${j.id} — $count frames written")
                j.target?.let { it.colorBuffer(0).destroy(); it.detachColorAttachments(); it.destroy() }
                j.clip = null; j.deck = null; j.pixels = null; j.target = null
                j.at++
            }

            val sideDeck = aside
            val slideStage: Stage = if (!fresh) paintedStage!! else when {
                // A sketch shown on the side takes the whole wall; the show goes on under it, unseen.
                sideDeck != null -> {
                    val shot = sideDeck.shot(canvasBounds)
                    paint(canvas, shot)
                    shot.stage
                }

                crossing -> {
                    val from = deck.leavingShot(boundsOf(leaving!!))!!
                    val to = deck.shot(boundsOf(arriving))
                    wall(wallLeaving!!, from, arriving = false)
                    wall(wallArriving!!, to, arriving = true)
                    drawer.isolatedWithTarget(canvas) {
                        drawer.ortho(canvas)
                        // the ground a push slides over; a fade never shows it
                        drawer.clear(to.slide.background)
                        deck.composite(drawer, wallLeaving.colorBuffer(0), wallArriving.colorBuffer(0))
                    }
                    to.stage
                }

                arriving.wide -> {
                    val to = deck.shot(canvasBounds)
                    paintWide(canvas, to, arriving = true)
                    to.stage
                }

                else -> {
                    val stage = renderPane(slideCanvas, slideLeaving, slideArriving, deck, slideBounds)
                    composePanes(canvas, cardOpened)
                    stage
                }
            }
            paintedStage = slideStage

            // The plate goes on last and into the canvas, over whatever the frame turned out to
            // be — a slide, a wall, or two of them handing over. Named off the deck rather than
            // off the shot, so a frame mid-handover carries the slide it is arriving at.
            if (fresh) nameplate?.let { plate ->
                drawer.isolatedWithTarget(canvas) {
                    drawer.ortho(canvas)
                    plate.draw(drawer, canvasBounds, ids.getOrElse(deck.index) { deck.slide.name }, deck.step)
                }
            }

            // The line said over this state, in subtitle mode: into the canvas like the plate, so a
            // filmed run carries it, and always inside the slide's own projector — see Subtitles.
            val reached = deck.index to deck.step
            if (reached != subtitleState || slideStage.frame < subtitleStageFrame) {
                subtitleState = reached
                subtitleSince = frame
                // The voice of the state left is cut, over its short fade, and the new state's is
                // due a breath from now — whether or not one is rendered, which is asked then.
                voicePlaying?.let { speakers.release(it) }
                voicePlaying = null
                voiceDue = frame + frames(Pace.LEAD_SECONDS)
            }
            subtitleStageFrame = slideStage.frame
            if (!voiceOn && voicePlaying != null) {
                speakers.release(voicePlaying)
                voicePlaying = null
            }
            if (organizing && settings.voice != null && settings.sound && !settings.record && !settings.stills &&
                frame - voicesReadAt >= frames(VOICE_RESCAN)
            ) {
                voicesReadAt = frame
                val track = subtitleTrack
                VoiceTrack.read(settings.voice, track, settings.voiceGain)?.let { fresh ->
                    val was = voices[track]?.size ?: 0
                    speakers.load(fresh.all)
                    voices[track] = fresh
                    if (fresh.size != was) println("voice: ${track.key} — ${fresh.size} states rendered")
                }
            }
            if (voiceOn && voiceDue in 0..frame) {
                voiceDue = -1
                val key = "${ids.getOrElse(deck.index) { "" }}-${letter(deck.step)}"
                val line = voice()?.sound(ids.getOrElse(deck.index) { "" }, deck.step)
                if (line != null) {
                    speakers.play(line, restart = true)
                    voicePlaying = line
                    voiceCheckAt = frame + frames(0.2)
                    if (settings.soundTrace) println("trace: frame $frame  voice $key asked")
                } else if (settings.soundTrace) println("trace: frame $frame  voice $key — no file on ${subtitleTrack.key}")
            }
            if (settings.soundTrace && voiceCheckAt in 0..frame) {
                voiceCheckAt = -1
                voicePlaying?.let { v ->
                    println("trace: frame $frame  voice ${v.file.nameWithoutExtension} " +
                            if (speakers.isPlaying(v)) "playing" else "NOT PLAYING")
                }
            }
            if (subtitleMode && fresh) {
                val said = subtitles()[ids.getOrElse(deck.index) { "" }, deck.step]
                val fit = voiceFrames(deck.index, deck.step)?.let { pace.spoken(it) }
                val heard = voice()?.speech(ids.getOrElse(deck.index) { "" }, deck.step)
                pace.at(said, frame - subtitleSince, fit, heard)?.let { card ->
                    drawer.isolatedWithTarget(canvas) {
                        drawer.ortho(canvas)
                        val pane = if (hasPanels) Rectangle(slideOffsetX, 0.0, slideWidth.toDouble(), settings.height.toDouble())
                        else canvasBounds
                        subtitleOverlay.draw(drawer, pane, card, frame - subtitleSince - card.at, pace)
                    }
                }
            }

            // The canvas is fitted into the window rather than stretched to it, so a
            // projector of another shape letterboxes instead of distorting the slides.
            val fit = min(width / canvasBounds.width, height / canvasBounds.height)
            val shown = Rectangle.fromCenter(
                Rectangle(0.0, 0.0, width.toDouble(), height.toDouble()).center,
                canvasBounds.width * fit, canvasBounds.height * fit
            )
            if (fresh) canvas.colorBuffer(0).generateMipmaps()
            val picture = brandPreview.apply(drawer, canvas.colorBuffer(0))
            drawer.clear(ColorRGBa.BLACK)
            drawer.isolated {
                if (concreteOn) drawer.shadeStyle = concreteStyle
                drawer.image(picture, shown.corner.x, shown.corner.y, shown.width, shown.height)
            }

            if (gridOn && !settings.stills) grid.draw(drawer, shown, canvasBounds, settings.panelWidth)

            // The clicker's overview and hold ring, on the window like the grid: never in a film or a still.
            if (overviewShown > 0.0) {
                // The face has no subscript two, and a missing glyph drops out — "CO -prestatieladder".
                val titles = ids.indices.map { i -> (show.outline[i]?.title ?: show.slides.getOrNull(i)?.name.orEmpty()).replace('₂', '2') }
                // A chapter by its number and title; a moment of the evening by its name, and marked as walls.
                val groups = ids.indices.map { i ->
                    show.outline[i]?.let { p -> p.moment.ifBlank { if (p.chapterTitle.isBlank()) "" else "${p.chapter}  ${p.chapterTitle}" } }.orEmpty()
                }
                val walls = ids.indices.map { i -> show.outline[i]?.let { p -> p.moment.isNotBlank() || p.chapterTitle.isBlank() } ?: true }
                // The sketches at the foot, as a block of their own after the evening.
                val shelfIds = shelf.map { it.first }
                val shelfTitles = shelf.map { (id, slide) ->
                    (catalogue.outline[catalogue.slideIds.indexOf(id)]?.title ?: slide.name).removePrefix("Sketch: ")
                }
                val current = if (aside != null) ids.size + shelfIds.indexOf(asideId) else deck.index
                overview.draw(drawer, shown, canvasBounds, settings.panelWidth, ids + shelfIds, titles + shelfTitles,
                    groups + List(shelf.size) { SlideOverview.SHELF }, walls + List(shelf.size) { false },
                    pick, current, overviewShown, hiddenFrom = ids.size)
            }
            holdRing.draw(drawer, shown, canvasBounds, settings.panelWidth, ringProgress, ringShown)

            if (debug && !settings.stills) {
                overlay.draw(drawer, deck, slideStage, width, height, fps, clock.paused, paintFps)
            }

            // The organizer's commands, drained here and nowhere else: the deck is moved from
            // the draw loop only, whichever thread asked. Then one preview, if any are owed,
            // and the state the page polls.
            if (remote != null) {
                /**
                 * The saved order, played from this frame on. The deck is rearranged in place:
                 * the slide on screen stays, at its new index, with its frame count; taken out
                 * of the order, the show moves to the nearest slide after it that survived. The
                 * card beside it follows without an announcement — this is the order changing,
                 * not a step in the show.
                 */
                fun play(order: Order) {
                    val next = runCatching { catalogue.arranged(order) }
                        .getOrElse { println("organizer: could not play that order (${it.message})"); null } ?: return
                    // a slide dragged in off the shelf is loaded before it can come up
                    ensureLoaded(next.slides)
                    val nextIds = next.slideIds
                    val at = ((deck.index until ids.size) + (deck.index - 1 downTo 0))
                        .firstNotNullOfOrNull { i -> nextIds.indexOf(ids[i]).takeIf { it >= 0 } } ?: 0
                    show = next
                    slides = next.slides
                    ids = nextIds
                    deck.rearrange(slides, next.outline, at)
                    val wanted = show.panelOf.getOrElse(deck.index) { -1 }
                    if (panelDeck != null && wanted >= 0 && panelDeck.index != wanted) {
                        // Under a slide that carries it, the card keeps the slide's time.
                        if (deck.slide.carriesCard) panelDeck.restart(wanted, closed(wanted), since = deck.startedAt)
                        else panelDeck.goTo(wanted, closed(wanted), cut = true)
                    }
                    if (wanted >= 0) shownPanel = wanted
                    shownSlide = deck.index
                    soundedSlide = deck.index
                    soundedStep = deck.step
                    remote.arranged(next)
                    println("organizer: playing the saved order, ${slides.size} slides, on #${ids[deck.index]}")
                }

                while (true) {
                    val command = remote.commands.poll() ?: break
                    // anything the page asks for may move the deck or change what is drawn
                    repaint = true
                    when (command) {
                        is Remote.Go -> ids.indexOf(command.id).takeIf { it >= 0 }?.let {
                            // Asked for the state already on the wall: the deck has nowhere to go,
                            // so say its line again from the start — choosing a state on the page
                            // is how its voice is heard a second time.
                            if (it == deck.index && command.step == deck.step) subtitleState = -1 to -1
                            deck.goTo(it, command.step, cut = true)
                        }
                        Remote.Forward -> forward()
                        Remote.Backward -> backward()
                        Remote.Replay -> deck.replay()
                        is Remote.Previews -> {
                            previewJobs.clear()
                            (command.ids ?: catalogue.slideIds).forEach { id -> repeat(3) { k -> previewJobs.addLast(id to k) } }
                        }
                        is Remote.Apply -> play(command.order)
                        is Remote.Concrete -> {
                            concreteOn = concrete != null && (command.on ?: !concreteOn)
                            println("organizer: concrete ${if (concreteOn) "on" else "off"}")
                        }
                        is Remote.Mute -> {
                            speakers.muted = command.on ?: !speakers.muted
                            println("organizer: sound ${if (speakers.muted) "muted" else "on"}")
                        }
                        is Remote.Practice -> {
                            practice = command.on ?: !practice
                            println("organizer: practice mode ${if (practice) "on" else "off"}")
                        }
                        is Remote.Subtitle -> {
                            subtitleMode = command.on ?: !subtitleMode
                            println("organizer: subtitles ${if (subtitleMode) "on" else "off"}")
                        }
                        is Remote.ApplySubtitles -> {
                            tracks[command.track] = command.subtitles
                            // said again from its start, so an edited line is seen whole
                            if (command.track == subtitleTrack) subtitleState = -1 to -1
                        }
                        is Remote.Mix -> {
                            speakers.setMix(command.layer, command.gain)
                            println("organizer: mix ${command.layer.key} %.2f".format(command.gain))
                        }
                        is Remote.Voice -> {
                            voiceOn = command.on ?: !voiceOn
                            // switched on mid-state, the line is said from its start
                            if (voiceOn) subtitleState = -1 to -1
                            println("organizer: voice ${if (voiceOn) "on" else "off"}")
                        }
                        is Remote.Autoplay -> {
                            // switched back on, a state whose line has been said moves on at once
                            autoplay = command.on ?: !autoplay
                            println("organizer: autoplay ${if (autoplay) "on" else "off"}")
                        }
                        is Remote.Track -> {
                            subtitleTrack = command.track
                            // the new track's line for this state, from its start
                            subtitleState = -1 to -1
                            voicePlaying?.let { speakers.release(it) }
                            voicePlaying = null
                            println("organizer: voice-over ${subtitleTrack.key}")
                        }
                        is Remote.Grid -> {
                            gridOn = command.on ?: !gridOn
                            println("organizer: grid ${if (gridOn) "on" else "off"}")
                        }
                        is Remote.Export -> {
                            // Only what is ticked and can actually be written down, in the
                            // order the show declares them, so the run is the talk's own order.
                            val wanted = command.ids.toSet()
                            val ids = catalogue.slideIds.filter { it in wanted }
                            if (ids.isEmpty()) println("export: nothing ticked that can be written down")
                            else {
                                previewJobs.clear()
                                job = ExportJob(ids)
                                println("export: ${ids.size} slide${if (ids.size == 1) "" else "s"} — ${ids.joinToString(", ")}")
                            }
                        }
                        is Remote.ExportMidi -> {
                            // The slides as *declared*, not as played: a wall can be wanted as
                            // MIDI while it is archived or skipped, since the file is the
                            // timing rather than a record of the run.
                            val wanted = command.ids.toSet()
                            catalogue.slideIds.forEachIndexed { i, id ->
                                if (id !in wanted) return@forEachIndexed
                                val slide = catalogue.slides[i]
                                val slides = listOfNotNull(slide, slide.runsInto).also { ensureLoaded(it) }
                                val file = remote.midiFileOf(id)
                                // The pane the slide composes for, since a slide that deals its
                                // elements against the frame has no schedule until it has one.
                                slides.forEach { it.layOut(if (slide.wide) settings.width else slideWidth, settings.height) }
                                // At the pace a filmed run really gives it, so the file and a
                                // clip of the same slide agree at every click and not just the
                                // first. A wall that builds on its own clock ignores it. The export
                                // with its clip goes the same way, so the two cannot disagree.
                                val tracks = exportRun(slide, settings.hold).tracks
                                writeMidi(file, tracks)
                                val notes = tracks.sumOf { it.notes.size }
                                val last = tracks.flatMap { it.notes }.maxOfOrNull { it.at + it.length } ?: 0.0
                                println("organizer: %s — %d notes on %d tracks, 0 to %.2fs → %s"
                                    .format(id, notes, tracks.size, last, file.path))
                            }
                            // A slide un-ticked leaves its file behind rather than having it
                            // deleted under it: a MIDI file is somebody's working copy by then.
                        }
                        is Remote.ApplyModules -> {
                            // The saved modules, stood in the catalogue afresh. A placeholder
                            // whose frames are unchanged is the same object and needs nothing;
                            // a new or changed one is loaded here — a picture or two, on the
                            // frame the save lands on — and gets its previews rendered. Then
                            // the order is played again over the new catalogue.
                            val next = catalogue.withModules(command.modules, references)
                            val fresh = next.slides.filterIsInstance<PlaceholderSlide>().filter { !it.loaded }
                            ensureLoaded(fresh)
                            catalogue = next
                            remote.catalogued(next)
                            next.slideIds.forEachIndexed { i, id ->
                                if (next.slides[i] in fresh) repeat(3) { k -> previewJobs.addLast(id to k) }
                            }
                            println("organizer: ${command.modules.modules.size} modules to build, ${fresh.size} stood in afresh")
                            play(remote.currentOrder())
                        }
                    }
                }
                previewJobs.removeFirstOrNull()?.let { (id, k) ->
                    renderPreview(id, k)
                    remote.previewStamp = System.currentTimeMillis()
                    // a preview draws its slide at another frame; the next draw paints the wall again
                    repaint = true
                }

                // The export, a chunk of frames a tick. Previews are held off while it runs:
                // both paint into the same pane buffers, and a preview landing between two
                // exported frames would be one frame of another slide in the middle of a clip.
                job?.let { j ->
                    if (j.at >= j.queue.size) {
                        println("export: done — ${j.written.size} files")
                        job = null
                    } else if (j.clip == null) {
                        if (!beginExport(j)) j.at++
                    } else {
                        stepExport(j)
                        if (j.frame >= j.frames) endExport(j)
                    }
                }
                val j = job
                remote.exporting = if (j == null) "" else j.id
                remote.exportDone = if (j == null) 0 else j.at
                remote.exportTotal = j?.queue?.size ?: 0
                remote.exportFrame = j?.frame ?: 0
                remote.exportFrames = j?.frames ?: 0
                remote.snapshot = Remote.Snapshot(
                    deck.index, ids[deck.index], deck.step, deck.slide.steps, frame, previewJobs.size, remote.previewStamp,
                    concreteOn, concrete != null, gridOn,
                    muted = speakers.muted, hasSound = speakers.ready, practice = practice,
                    subtitles = subtitleMode, subtitleTrack = subtitleTrack, voice = voiceOn,
                    mix = Layer.entries.associateWith { speakers.mixOf(it) }, autoplay = autoplay
                )
            }

            if (settings.stills && frame - heldSince >= STILL_HOLD) {
                val (slide, step) = plan[planned]
                val file = File("screenshots/slide-%02d-%d-%s.png".format(slide + 1, step, slides[slide].name))
                file.parentFile.mkdirs()
                canvas.colorBuffer(0).saveToFile(file)
                println("saved ${file.path}")
                planned++
                if (planned >= plan.size) application.exit()
            }

            // The bench's timing: the GPU finished, so a draw costs what it really costs rather than
            // what it took to queue. The first draw of a state is left out — it jumped there, and a
            // slide's first draw can compile a shader the film would have compiled long before.
            if (settings.bench && benchAt < benchPlan.size) {
                org.lwjgl.opengl.GL11C.glFinish()
                if (benchDrawn > 0) benchMillis[benchAt] += (System.nanoTime() - drawStarted) / 1e6
                benchDrawn++
                if (benchDrawn > settings.benchSamples) {
                    benchAt++
                    if (benchAt >= benchPlan.size) {
                        benchReport(benchPlan, benchHolds, benchMillis.map { it / settings.benchSamples }, ids, slides, settings.fps)
                        application.exit()
                    }
                }
            }
        }
    }
}

/**
 * The cue list the deck writes for itself: one hold per state from [start] to the end, each
 * long enough for the state to finish moving — its click, or its own opening — plus a reading
 * time, longer for a wall that is one picture. Printed, so it can be copied into
 * `SLIDES_CUES` and tuned by hand where a state wants more or less than the rule gives it.
 */
internal fun autoCues(
    slides: List<Slide>, ids: List<String>, start: Int, until: Int, settings: Settings,
    subtitles: Subtitles? = null, pace: Pace = Pace(), voice: VoiceTrack? = null
): List<Int> {
    val holds = mutableListOf<Int>()
    var lengthened = 0
    for (s in start..until) {
        val slide = slides[s]
        val read = if (slide.wide && !slide.carriesCard && slide.steps == 1) settings.holdWide else settings.hold
        for (step in 0 until slide.steps) {
            // In subtitle mode a state stands until its line has been said and a beat after it,
            // where that is longer than the rule would hold it anyway.
            val said = subtitles?.get(ids.getOrElse(s) { "" }, step).orEmpty()
            val stand = standFrames(slide, step, said, settings, pace, voice?.frames(ids.getOrElse(s) { "" }, step),
                voice?.speech(ids.getOrElse(s) { "" }, step))
            if (stand > standFrames(slide, step, "", settings, pace)) lengthened++
            holds += stand
        }
    }
    if (subtitles != null) println("cues: subtitle mode — $lengthened states held longer to say their line" +
            if (voice != null) ", timed by the voice where one is rendered" else "")
    println(
        "cues: auto — " + holds.joinToString(", ") { "%.1f".format(seconds(it)) } +
                "  (%d states, %.0fs in all)".format(holds.size, seconds(holds.sum()))
    )
    // By id rather than by `name`, which is the class's and says nothing here — both ends of
    // chapter 1 are a QuoteSlide, so the line read "Quote to Quote". An id is what the order
    // file, the organizer and the nameplate call a slide, so it is the one name that is worth
    // reading back.
    if (start > 0 || until < slides.lastIndex) println(
        "cues: %s to %s — slides %d to %d of %d".format(
            ids.getOrElse(start) { slides[start].name }, ids.getOrElse(until) { slides[until].name },
            start + 1, until + 1, slides.size
        )
    )
    return holds
}

/** Seconds a state stands after its last subtitle card has gone, on a hands-off run. */
private const val SUBTITLE_TAIL = 1.0

/** Seconds between reads of the voice folder while the show is up — see [VoiceTrack]. */
private const val VOICE_RESCAN = 4.0

/**
 * Frames a state stands on a run that clicks for itself: long enough for it to finish moving —
 * its click, or the slide's own opening — plus a reading time, longer for a wall that is one
 * picture; and where [said] is spoken over it, at least until the last card has gone and a beat
 * after. One rule, read by the cue list a filmed run writes and by the presented run on the
 * extended track, so the two cannot disagree about how long a state is held. With [voiced], the
 * frames a rendered voice for the state runs, the speech is timed by the voice rather than
 * estimated from the text — see [VoiceTrack].
 */
internal fun standFrames(slide: Slide, step: Int, said: String, settings: Settings, pace: Pace, voiced: Int? = null,
                         heard: Speech? = null): Int {
    // A backdrop coming up through the show's build has not settled until the build is done.
    val building = step == 0 && slide.wide && slide.buildsIn && settings.wallBuild != null && settings.backdropBuild > 0.0
    val settle = if (step == 0) (if (building) maxOf(slide.settle, frames(settings.backdropBuild)) else slide.settle)
        else slide.stepLength(step)
    val read = slide.holdAfterSettle ?: if (slide.wide && !slide.carriesCard && slide.steps == 1) settings.holdWide else settings.hold
    val spoken = when {
        // The voice and the cards, whichever runs longer. With the voice's own word times the
        // cards end with the speech and this is the voice; without them the cards are fitted to
        // the voice and never squeezed under reading pace, so a quick line leaves them standing.
        voiced != null -> maxOf(pace.spoken(voiced), pace.length(said, pace.spoken(voiced), heard)) + frames(SUBTITLE_TAIL)
        said.isBlank() -> 0
        else -> pace.length(said) + frames(SUBTITLE_TAIL)
    }
    val stand = maxOf(settle + frames(read), spoken)
    // A backdrop is one picture and is never hurried: see Settings.holdBackdrop.
    return if (slide.kind == "backdrop") maxOf(stand, frames(settings.holdBackdrop)) else stand
}

/**
 * Resolves `SLIDES_UNTIL`, the last slide a hands-off run plays: a number from 1 or a slide's
 * name, as [startIndex] reads them, and the end of the deck where it is not set.
 *
 * Never before [start], because a run that ends before it begins is a film of one state with no
 * sign of why. A name that matches nothing runs to the end rather than failing, the same way an
 * unknown `SLIDES_START` opens at the first slide.
 */
internal fun untilIndex(slides: List<Slide>, until: String?, start: Int): Int {
    val wanted = until?.trim().orEmpty()
    if (wanted.isEmpty()) return slides.lastIndex
    val at = slideAt(slides, wanted)
        ?: run { println("no slide called \"$wanted\"; running to the end"); return slides.lastIndex }
    return at.coerceIn(start, slides.lastIndex)
}

/**
 * Frames a still waits before it is taken, so an opening ramp has finished moving.
 *
 * Long enough for the slowest of them: the chapter card sets its title out an element at a
 * time over 1.4s, and at the 40 frames this was it caught every card half built.
 */
private const val STILL_HOLD = 95

/**
 * Milliseconds a tick may spend rendering export frames.
 *
 * The export is a job on the draw loop rather than a thread, for the reason everything else the
 * page asks for is — the slides and the buffers belong to it. So it takes a slice of each frame
 * and the window stays alive under it: at a third of a 60 Hz frame the show still answers, and a
 * 25 second clip comes out in well under its own length because nothing here has to be drawn at
 * the rate it plays at.
 */
private const val EXPORT_BUDGET = 20L

/**
 * Every cue a slide can reach: the one it arrives on and the mark each of its clicks makes.
 *
 * One definition rather than two, because the two callers must agree — `load` decodes this set
 * and the driver releases out of it. They disagreed once, and silently: `load` gathered only
 * `slide.sound`, so the stack's five click marks reached `play` with no buffer to their name
 * and did nothing at all. A step cue hangs off a *method*, so a `mapNotNull` over the slides
 * cannot see it; it has to be asked for by step.
 */
private fun cuesOf(slide: Slide, id: String, sheet: CueSheet?): List<Sound> =
    (0 until slide.steps.coerceAtLeast(1)).mapNotNull { soundAt(slide, id, it, sheet) }

/**
 * What one state of one slide sounds like: the sheet's cue where a sheet speaks for the slide,
 * and otherwise whatever `Slideshow.kt` declared for it.
 *
 * **A sheet owns a slide outright rather than filling in around it.** Where it names a slide at
 * all, a state it leaves bare is silent — the designer chose not to mark it. Falling back state
 * by state instead would put the old sheet's sound under the new one on exactly the states the
 * new design left clear, which is the one arrangement nobody asked for. See [CueSheet].
 *
 * Step 0 is the slide arriving and is [Slide.sound]; every step above it is a click, and is
 * [Slide.stepSound]. That is the same numbering the [Nameplate] letters and the sheet's file
 * names use, which is why there is one function here rather than two.
 */
private fun soundAt(slide: Slide, id: String, step: Int, sheet: CueSheet?): Sound? =
    if (sheet != null && sheet.owns(id)) sheet[id, step]
    else if (step == 0) slide.sound else slide.stepSound(step)

/**
 * Resolves `SLIDES_START`: a number counting from 1, or a slide's name — "3" and
 * "Reveal" both work, and an unknown one opens at the first slide rather than failing.
 */
internal fun startIndex(slides: List<Slide>, start: String?): Int {
    val wanted = start?.trim().orEmpty()
    if (wanted.isEmpty()) return 0
    return slideAt(slides, wanted)
        ?: run { println("no slide called \"$wanted\"; starting at ${slides.first().name}"); 0 }
}

/** A number counting from 1, or a slide's name, exactly then by prefix. Null for neither. */
private fun slideAt(slides: List<Slide>, wanted: String): Int? {
    wanted.toIntOrNull()?.let { return (it - 1).coerceIn(slides.indices) }
    val exact = slides.indexOfFirst { it.name.equals(wanted, ignoreCase = true) }
    if (exact >= 0) return exact
    val prefix = slides.indexOfFirst { it.name.startsWith(wanted, ignoreCase = true) }
    return prefix.takeIf { it >= 0 }
}

