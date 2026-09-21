package slideshow

import org.openrndr.Fullscreen
import org.openrndr.KEY_ARROW_DOWN
import org.openrndr.KEY_ARROW_LEFT
import org.openrndr.KEY_ARROW_RIGHT
import org.openrndr.KEY_ARROW_UP
import org.openrndr.KEY_ESCAPE
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
import java.io.File
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

    configure {
        width = (settings.width * settings.windowScale).toInt()
        height = (settings.height * settings.windowScale).toInt()
        title = settings.title
        hideWindowDecorations = settings.undecorated
        settings.windowX?.let { position = IntVector2(it, settings.windowY ?: 0) }
        //if (settings.fullscreen) fullscreen = Fullscreen.CURRENT_DISPLAY_MODE
    }

    program {
        // The show's own contents, so the structure declared in Slideshow.kt can be read
        // back without clicking through it.
        println(show.runningOrder())

        // Everything a slide needs is loaded before the first frame: a show must not
        // stall on a click. Panel cards are slides too, and load the same way.
        (if (organizing) catalogue.slides else slides).forEach { it.load(this) }
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
                open = settings.organizerOpen
            ).takeIf { it.start() }?.also { onClose { it.stop() } }
        else null

        // The cues, decoded before the first frame for the same reason the slides are —
        // a show must not stall on a click. Silent under `stills`, which jumps through
        // every slide of the deck on a timer and would fire every cue in the show at it.
        val speakers = Speakers().apply { muted = settings.muted }

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

        /** What the deck should sound at [step] of the slide at [index] — the sheet's, or the slide's own. */
        fun cueAt(index: Int, step: Int) =
            slides.getOrNull(index)?.let { soundAt(it, idAt(index), step, cueSheet) }

        if (settings.sound && !settings.stills) {
            // Every cue the deck can reach: a slide's own, the marks its clicks make, the
            // chapter cards' and the sheet's. The step cues have to be asked for by name — they
            // hang off `stepSound(step)` rather than a property, so a `mapNotNull` over the
            // slides misses them and they arrive at `play` with no buffer to their name.
            //
            // The whole sheet is decoded rather than only the part the running order reaches,
            // for the reason it is bound against the catalogue: an order applied while the
            // window is up may call for any declared slide, and a cue read late is a cue that
            // lands on the click after the one it was for.
            // Paired with its own ids: the catalogue is in declaration order and the deck in
            // running order, so indexing one by the other's names would ask the wrong slide.
            val loading = if (organizing) catalogue.slides to catalogue.slideIds else slides to ids
            val declared = loading.first.flatMapIndexed { i: Int, slide: Slide ->
                cuesOf(slide, loading.second.getOrElse(i) { "" }, cueSheet)
            }
            speakers.load(
                declared + cueSheet?.all.orEmpty() +
                        show.panels.mapNotNull { it.sound } + listOfNotNull(settings.slideBed)
            )
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
            speakers.play(show.panels.getOrNull(panel)?.sound)
        }

        /** A slide's own cue, where it has one. Cards are announced separately, above. */
        var soundedSlide = -1

        /** The click its cue was last fired on, so a build marks each one exactly once. */
        var soundedStep = -1

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

        // The concrete over the whole frame: the show as if projected onto a concrete wall.
        // Laid on only where the canvas meets the window, so no slide, still or preview carries
        // it.
        //
        // **It only ever darkens, but for the floor.** Light on a wall is the picture times the stone, and stone is
        // never brighter than white — dividing the texture by its *average* instead pushed every
        // lighter-than-average pixel past white, so a white piece clipped to flat white with a
        // few specks and read as overexposed. So the grain is measured against the texture's
        // bright end (its 98th percentile of brightness, read off the file once), and `mix`
        // exaggerates it: this photograph is mid grey within ±9%, far too flat to show at 1.
        // Brightness only, so the stone's own faint hue does not tint the house colours.
        val concreteFile = settings.concrete?.let { File(it) }
        val concrete = concreteFile?.let { file ->
            if (!file.isFile) { println("concrete: no texture at ${file.path}"); null }
            else runCatching { loadImage(file) }.getOrElse { println("concrete: could not read ${file.path}"); null }
        }?.apply {
            wrapU = WrapMode.REPEAT; wrapV = WrapMode.REPEAT
            filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
            generateMipmaps()
        }
        // Measured off the loaded texture's own bytes, which are the file's sRGB values.
        val concreteBright = concrete?.let { brightEnd(it) } ?: 1.0
        if (concrete != null) println("concrete: ${concreteFile?.path}, bright end %.3f, grain x%.1f".format(concreteBright, settings.concreteMix))
        val concreteStyle = concrete?.let { stone ->
            shadeStyle {
                fragmentTransform = """
                    vec2 uv = c_boundsPosition.xy * p_canvas / p_tile;
                    // The photograph is an sRGB texture, so the sampler hands it back decoded to
                    // linear light — far darker than its file, which is what the bright end was
                    // measured on. Taken back to the file's values first, or the grain goes below
                    // zero everywhere and the whole frame comes out black.
                    float stone = pow(dot(texture(p_stone, uv).rgb, vec3(0.299, 0.587, 0.114)), 1.0 / 2.2);
                    float grain = min(stone / p_bright, 1.0);
                    // Black is lifted to the floor first, so a black slide is dark stone rather
                    // than a hole in the wall. Only what is near black: the lift fades out as the
                    // brightest channel rises, so a navy or a blue keeps its colour — lifting every
                    // dark channel alike added grey to them and washed the chapter card's blues out.
                    float nearBlack = 1.0 - smoothstep(0.0, p_floorReach, max(x_fill.r, max(x_fill.g, x_fill.b)));
                    x_fill.rgb = (x_fill.rgb + p_floor * nearBlack) * clamp(1.0 - p_mix * (1.0 - grain), 0.0, 1.0);
                """.trimIndent()
                parameter("stone", stone)
                parameter("canvas", Vector2(canvasBounds.width, canvasBounds.height))
                parameter("tile", Vector2(stone.width * settings.concreteScale, stone.height * settings.concreteScale))
                parameter("mix", settings.concreteMix)
                parameter("bright", concreteBright)
                parameter("floor", settings.concreteFloor)
                // Linear light: 0.06 is about 70 of 255, under the navy's blue channel.
                parameter("floorReach", 0.06)
            }
        }
        var concreteOn = settings.concreteOn && concrete != null

        var debug = settings.debug
        val overlay = DebugOverlay(runCatching { loadFont("data/fonts/default.otf", 13.0) }.getOrNull())

        // The ruled grid over the whole wall: the opening scene's own grid as a debug mode, drawn on
        // the window like the overlay. `g` and the organizer switch it. See GridOverlay.
        var gridOn = false
        val grid = GridOverlay(runCatching { loadFont("data/fonts/default.otf", 12.0) }.getOrNull())

        // Named into the canvas rather than onto the window, so a filmed run carries it. See
        // Nameplate — it is the one overlay here that is meant to be in the picture.
        val nameplate = if (settings.nameplate)
            Nameplate(runCatching { loadFont("data/fonts/default.otf", NAMEPLATE_SIZE) }.getOrNull()) else null

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

        /** True when the slide deck is on the very first click of its section. A backdrop has none. */
        fun atSectionStart(): Boolean = !deck.slide.wide && deck.step == 0 && (deck.index == 0 ||
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
        keyboard.keyDown.listen { event ->
            // The slide on screen asks first, for controls of its own.
            if (event.key != KEY_ESCAPE && deck.slide.key(event.name)) return@listen
            when {
                event.key == KEY_ARROW_RIGHT -> forward()
                event.key == KEY_ARROW_LEFT -> backward()
                event.key == KEY_ARROW_DOWN -> deck.nextSlide()
                event.key == KEY_ARROW_UP -> deck.previousSlide()
                event.key == KEY_ESCAPE -> { speakers.close(); application.exit() }

                event.name == "0" -> { deck.home(); openCard() }
                event.name == "r" -> deck.replay()
                event.name == "d" -> debug = !debug
                event.name == "g" -> gridOn = !gridOn

                // The clock controls are part of the debug view, not of the show, so they
                // do nothing while it is down.
                debug && event.name == "p" -> clock.paused = !clock.paused
                debug && (event.name == "." || event.name == "period") -> clock.step(1)
                debug && (event.name == "," || event.name == "comma") -> clock.step(-1)
            }
        }

        if (settings.record) {
            extend(ScreenRecorder().apply {
                outputFile = settings.video
                frameRate = settings.fps
                // so the clip comes out at the canvas size, not the window's
                contentScale = 1.0 / settings.windowScale
                settings.duration?.let { maximumDuration = it }
            })
            // The soundtrack is rendered from the log once the film is on disk, which is
            // after the program ends — so it is handed out rather than done here. The
            // frame count is the clock's last, which is the film's length in deck frames.
            leave { Soundtrack.export(speakers.log.toList(), clock.frame, File(settings.video), settings.mix) }
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
        val holds = if (settings.cuesAuto) autoCues(slides, ids, startSlide, untilSlide, settings)
        else settings.cues.map { frames(it) }
        val cueFrames = if (settings.cuesAuto) holds.dropLast(1) else holds
        val finalHold = holds.lastOrNull() ?: 0
        var cue = 0
        var cueAt = 0
        var ended = false
        var fps = 0.0
        var lastSeconds = 0.0

        extend {
            // The one place a clock is read, and it is read inside the draw loop, so it is
            // video time while recording and wall time otherwise. Everything downstream
            // sees frame numbers.
            val frame = clock.advance(seconds)
            deck.tick(frame)
            panelDeck?.tick(frame)
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
                if (leaving != null) {
                    val arrivingFiles = cuesOf(deck.slide, idAt(deck.index), cueSheet)
                        .mapTo(mutableSetOf()) { it.file }
                    cuesOf(leaving, idAt(soundedFrom), cueSheet)
                        .filter { it.sustained && it.file !in arrivingFiles }
                        .forEach { speakers.release(it) }
                }

                speakers.play(cueAt(deck.index, 0))
                // The bed under the talk: up on any slide of a chapter, let go while a wall
                // or a scene is up. Asking for it again on the next slide does not restart it —
                // a held loop only picks its fade up from where it stands — so it runs on
                // unbroken from one slide to the next.
                settings.slideBed?.let { if (deck.slide.wide) speakers.release(it) else speakers.play(it) }
                soundedStep = deck.step
                if (first && startPanel >= 0 && !deck.slide.wide) announce(startPanel)

            } else if (deck.step != soundedStep) {
                // A built slide marks its clicks: a band landing on the stack, and so on.
                // Forward only — clicking back through a build is a correction, and re-firing
                // the marks would say it is being built when it is being taken apart.
                if (deck.step > soundedStep) speakers.play(cueAt(deck.index, deck.step))
                soundedStep = deck.step
            }

            fps = mix(fps, 1.0 / (seconds - lastSeconds).coerceAtLeast(1e-4), 0.1)
            lastSeconds = seconds

            if (settings.stills) {
                val (slide, step) = plan[planned]
                if (deck.index != slide || deck.step != step) {
                    deck.goTo(slide, step, cut = true)
                    heldSince = frame
                }
            } else if (holds.isNotEmpty()) {
                if (cue < cueFrames.size) {
                    if (frame - cueAt >= cueFrames[cue]) {
                        cueAt = frame
                        cue++
                        forward()
                    }
                } else if (settings.record && !ended && frame - cueAt >= finalHold) {
                    // A filmed run ends one hold after its last click; a watched one stands.
                    ended = true
                    speakers.close()
                    application.exit()
                }
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

            // The card is rendered whenever it can be seen: beside a slide, or in a wall a
            // slide is leaving or arriving as. Under a backdrop standing alone it is not
            // asked for — a mosaic card repaints its plate every frame, for nobody.
            val panelStage = if (panelDeck != null && (crossing || !arriving.wide))
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

            /** The whole wall for one shot: a backdrop edge to edge, or a slide beside its card. */
            fun wall(target: RenderTarget, shot: Deck.Shot) {
                if (shot.slide.wide) paint(target, shot)
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
                val shot = Remote.previewShots(slide)[k]
                val stage = stageAt(slide, boundsOf(slide), shot.step, shot.frame)
                if (slide.wide) paint(target, Deck.Shot(slide, stage))
                else {
                    val panel = of.panelOf.getOrElse(index) { -1 }
                    if (panelCanvas != null && panel >= 0) {
                        val card = of.panels[panel]
                        paint(panelCanvas, Deck.Shot(card, stageAt(card, panelBounds, closed(panel), 600)))
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
                val w = if (slide.wide) settings.width else slideWidth
                slide.layOut(w, settings.height)
                val clicks = midiClicks(slide, settings.hold)
                val tracks = midiTracksOf(slide, clicks = clicks)
                val mid = remote!!.midiFileOf(id)
                writeMidi(mid, tracks)
                val last = tracks.flatMap { it.notes }.maxOfOrNull { frames(it.at + it.length) } ?: 0
                j.clicks = clicks
                j.nextClick = 0
                j.frame = 0
                j.frames = clipFrames(slide, settings.hold, clicks, last)
                j.deck = Deck(listOf(slide), 0, Outline.EMPTY)
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

            val slideStage: Stage = when {
                crossing -> {
                    val from = deck.leavingShot(boundsOf(leaving!!))!!
                    val to = deck.shot(boundsOf(arriving))
                    wall(wallLeaving!!, from)
                    wall(wallArriving!!, to)
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
                    paint(canvas, to)
                    to.stage
                }

                else -> {
                    val stage = renderPane(slideCanvas, slideLeaving, slideArriving, deck, slideBounds)
                    composePanes(canvas, cardOpened)
                    stage
                }
            }

            // The plate goes on last and into the canvas, over whatever the frame turned out to
            // be — a slide, a wall, or two of them handing over. Named off the deck rather than
            // off the shot, so a frame mid-handover carries the slide it is arriving at.
            nameplate?.let { plate ->
                drawer.isolatedWithTarget(canvas) {
                    drawer.ortho(canvas)
                    plate.draw(drawer, canvasBounds, ids.getOrElse(deck.index) { deck.slide.name }, deck.step)
                }
            }

            // The canvas is fitted into the window rather than stretched to it, so a
            // projector of another shape letterboxes instead of distorting the slides.
            val fit = min(width / canvasBounds.width, height / canvasBounds.height)
            val shown = Rectangle.fromCenter(
                Rectangle(0.0, 0.0, width.toDouble(), height.toDouble()).center,
                canvasBounds.width * fit, canvasBounds.height * fit
            )
            canvas.colorBuffer(0).generateMipmaps()
            drawer.clear(ColorRGBa.BLACK)
            drawer.isolated {
                if (concreteOn) drawer.shadeStyle = concreteStyle
                drawer.image(canvas.colorBuffer(0), shown.corner.x, shown.corner.y, shown.width, shown.height)
            }

            if (gridOn && !settings.stills) grid.draw(drawer, shown, canvasBounds, settings.panelWidth)

            if (debug && !settings.stills) {
                overlay.draw(drawer, deck, slideStage, width, height, fps, clock.paused)
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
                    val nextIds = next.slideIds
                    val at = ((deck.index until ids.size) + (deck.index - 1 downTo 0))
                        .firstNotNullOfOrNull { i -> nextIds.indexOf(ids[i]).takeIf { it >= 0 } } ?: 0
                    show = next
                    slides = next.slides
                    ids = nextIds
                    deck.rearrange(slides, next.outline, at)
                    val wanted = show.panelOf.getOrElse(deck.index) { -1 }
                    if (panelDeck != null && wanted >= 0 && panelDeck.index != wanted) {
                        panelDeck.goTo(wanted, closed(wanted), cut = true)
                    }
                    if (wanted >= 0) shownPanel = wanted
                    shownSlide = deck.index
                    soundedSlide = deck.index
                    soundedStep = deck.step
                    remote.arranged(next)
                    println("organizer: playing the saved order, ${slides.size} slides, on #${ids[deck.index]}")
                }

                while (true) {
                    when (val command = remote.commands.poll() ?: break) {
                        is Remote.Go -> ids.indexOf(command.id).takeIf { it >= 0 }?.let { deck.goTo(it, command.step, cut = true) }
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
                                val slide = catalogue.slides[i]
                                if (id !in wanted) return@forEachIndexed
                                val file = remote.midiFileOf(id)
                                // The pane the slide composes for, since a slide that deals its
                                // elements against the frame has no schedule until it has one.
                                slide.layOut(if (slide.wide) settings.width else slideWidth, settings.height)
                                // At the pace a filmed run really gives it, so the file and a
                                // clip of the same slide agree at every click and not just the
                                // first. A wall that builds on its own clock ignores it.
                                val clicks = midiClicks(slide, settings.hold)
                                val tracks = midiTracksOf(slide, clicks = clicks)
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
                            fresh.forEach { it.load(this) }
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
                    muted = speakers.muted, hasSound = speakers.ready
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
        }
    }
}

/**
 * The cue list the deck writes for itself: one hold per state from [start] to the end, each
 * long enough for the state to finish moving — its click, or its own opening — plus a reading
 * time, longer for a wall that is one picture. Printed, so it can be copied into
 * `SLIDES_CUES` and tuned by hand where a state wants more or less than the rule gives it.
 */
private fun autoCues(slides: List<Slide>, ids: List<String>, start: Int, until: Int, settings: Settings): List<Int> {
    val holds = mutableListOf<Int>()
    for (s in start..until) {
        val slide = slides[s]
        val read = if (slide.wide && slide.steps == 1) settings.holdWide else settings.hold
        for (step in 0 until slide.steps) {
            val settle = if (step == 0) slide.settle else slide.stepLength(step)
            holds += settle + frames(read)
        }
    }
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

/**
 * Resolves `SLIDES_UNTIL`, the last slide a hands-off run plays: a number from 1 or a slide's
 * name, as [startIndex] reads them, and the end of the deck where it is not set.
 *
 * Never before [start], because a run that ends before it begins is a film of one state with no
 * sign of why. A name that matches nothing runs to the end rather than failing, the same way an
 * unknown `SLIDES_START` opens at the first slide.
 */
private fun untilIndex(slides: List<Slide>, until: String?, start: Int): Int {
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
private fun startIndex(slides: List<Slide>, start: String?): Int {
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

/**
 * The bright end of a texture: the 98th percentile of its brightness, sampled on a coarse grid
 * off the texture itself. What the concrete over the frame measures its grain against, so the
 * stone's lightest patches leave the picture as it is and everything else darkens it.
 */
private fun brightEnd(texture: org.openrndr.draw.ColorBuffer): Double = runCatching {
    texture.shadow.download()
    val step = maxOf(1, minOf(texture.width, texture.height) / 256)
    val values = ArrayList<Double>()
    for (y in 0 until texture.height step step) for (x in 0 until texture.width step step) {
        val c = texture.shadow[x, y]
        values += 0.299 * c.r + 0.587 * c.g + 0.114 * c.b
    }
    texture.shadow.destroy()
    values.sort()
    values[(values.size * 0.98).toInt().coerceAtMost(values.size - 1)].coerceIn(0.05, 1.0)
}.getOrElse { println("concrete: could not measure the texture (${it.message})"); 1.0 }
