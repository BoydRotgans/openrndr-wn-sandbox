package slideshow

import org.openrndr.Fullscreen
import org.openrndr.KEY_ARROW_DOWN
import org.openrndr.KEY_ARROW_LEFT
import org.openrndr.KEY_ARROW_RIGHT
import org.openrndr.KEY_ARROW_UP
import org.openrndr.KEY_ESCAPE
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadFont
import org.openrndr.draw.renderTarget
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.math.IntVector2
import org.openrndr.shape.Rectangle
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
fun present(show: Show) = application {
    val settings = show.settings
    val slides = show.slides
    val hasPanels = show.panels.isNotEmpty() && settings.panelWidth != null

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
        slides.forEach { it.load(this) }
        show.panels.forEach { it.load(this) }

        val startSlide = startIndex(slides, settings.start)
        val deck = Deck(slides, startSlide, show.outline)
        val clock = Clock()

        // The cues, decoded before the first frame for the same reason the slides are —
        // a show must not stall on a click. Silent under `stills`, which jumps through
        // every slide of the deck on a timer and would fire every cue in the show at it.
        val speakers = Speakers()
        if (settings.sound && !settings.stills) {
            // Every cue the deck can reach: a slide's own, the marks its clicks make, and the
            // chapter cards'. The step cues have to be asked for by name — they hang off
            // `stepSound(step)` rather than a property, so a `mapNotNull` over the slides
            // misses them and they arrive at `play` with no buffer to their name.
            speakers.load(slides.flatMap { cuesOf(it) } + show.panels.mapNotNull { it.sound })
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
        fun announce(panel: Int) = speakers.play(show.panels.getOrNull(panel)?.sound)

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
        val hasWide = slides.any { it.wide }
        val wallLeaving = if (hasWide) buffer(settings.width, settings.height) else null
        val wallArriving = if (hasWide) buffer(settings.width, settings.height) else null

        // Only the outer canvas is ever minified — into the window — so it is the only
        // one that needs mipmaps; the panes are blitted into it at their native size.
        canvas.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        canvas.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR

        var debug = settings.debug
        val overlay = DebugOverlay(runCatching { loadFont("data/fonts/default.otf", 13.0) }.getOrNull())

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
            when {
                event.key == KEY_ARROW_RIGHT -> forward()
                event.key == KEY_ARROW_LEFT -> backward()
                event.key == KEY_ARROW_DOWN -> deck.nextSlide()
                event.key == KEY_ARROW_UP -> deck.previousSlide()
                event.key == KEY_ESCAPE -> { speakers.close(); application.exit() }

                event.name == "0" -> { deck.home(); openCard() }
                event.name == "r" -> deck.replay()
                event.name == "d" -> debug = !debug

                // The clock controls are part of the debug view, not of the show, so they
                // do nothing while it is down.
                debug && event.name == "p" -> clock.paused = !clock.paused
                debug && (event.name == "." || event.name == "period") -> clock.step(1)
                debug && (event.name == "," || event.name == "comma") -> clock.step(-1)
            }
        }

        if (settings.record) {
            extend(ScreenRecorder().apply {
                frameRate = settings.fps
                // so the clip comes out at the canvas size, not the window's
                contentScale = 1.0 / settings.windowScale
                settings.duration?.let { maximumDuration = it }
            })
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
        // filmed run and a watched one are the same run.
        val cueFrames = settings.cues.map { frames(it) }
        var cue = 0
        var cueAt = 0
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
                soundedSlide = deck.index

                // Every cue that belongs to the slide being left goes out — its arrival cue and
                // any of its click marks, since either may be sustained. Held back only where
                // the slide arriving stands on the same file, so a bed spanning two slides
                // keeps playing rather than dipping between them. A sting declares no fade, so
                // it is not sustained and is left to ring out.
                if (leaving != null) {
                    val arrivingFiles = cuesOf(deck.slide).mapTo(mutableSetOf()) { it.file }
                    cuesOf(leaving)
                        .filter { it.sustained && it.file !in arrivingFiles }
                        .forEach { speakers.release(it) }
                }

                speakers.play(deck.slide.sound)
                soundedStep = deck.step
                if (first && startPanel >= 0 && !deck.slide.wide) announce(startPanel)

            } else if (deck.step != soundedStep) {
                // A built slide marks its clicks: a band landing on the stack, and so on.
                // Forward only — clicking back through a build is a correction, and re-firing
                // the marks would say it is being built when it is being taken apart.
                if (deck.step > soundedStep) speakers.play(deck.slide.stepSound(deck.step))
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
            } else if (cueFrames.isNotEmpty()) {
                if (cue < cueFrames.size && frame - cueAt >= cueFrames[cue]) {
                    cueAt = frame
                    cue++
                    forward()
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

            /** The two panes composed into [target]: the wall as the presentation shows it. */
            fun composePanes(target: RenderTarget) = drawer.isolatedWithTarget(target) {
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
                    // 1920 wide and the move crosses 3840. `on(1)` is the card's own opening
                    // click, eased by the deck like any other, so the slide is uncovered at
                    // exactly the rate the title crosses. A one-step card never leaves home.
                    val opened = if (panelDeck!!.slide.steps > 1) panelStage!!.on(1) else 1.0
                    drawer.image(
                        panelCanvas.colorBuffer(0), slideOffsetX * (1.0 - opened), 0.0,
                        panelWidth.toDouble(), settings.height.toDouble()
                    )
                }
            }

            /** The whole wall for one shot: a backdrop edge to edge, or a slide beside its card. */
            fun wall(target: RenderTarget, shot: Deck.Shot) {
                if (shot.slide.wide) paint(target, shot)
                else {
                    paint(slideCanvas, shot)
                    composePanes(target)
                }
            }

            fun boundsOf(slide: Slide) = if (slide.wide) canvasBounds else slideBounds

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
                    composePanes(canvas)
                    stage
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
            drawer.image(canvas.colorBuffer(0), shown.corner.x, shown.corner.y, shown.width, shown.height)

            if (debug && !settings.stills) {
                overlay.draw(drawer, deck, slideStage, width, height, fps, clock.paused)
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
 * Frames a still waits before it is taken, so an opening ramp has finished moving.
 *
 * Long enough for the slowest of them: the chapter card sets its title out an element at a
 * time over 1.4s, and at the 40 frames this was it caught every card half built.
 */
private const val STILL_HOLD = 95

/**
 * Every cue a slide can reach: the one it arrives on and the mark each of its clicks makes.
 *
 * One definition rather than two, because the two callers must agree — `load` decodes this set
 * and the driver releases out of it. They disagreed once, and silently: `load` gathered only
 * `slide.sound`, so the stack's five click marks reached `play` with no buffer to their name
 * and did nothing at all. A step cue hangs off a *method*, so a `mapNotNull` over the slides
 * cannot see it; it has to be asked for by step.
 */
private fun cuesOf(slide: Slide): List<Sound> =
    listOfNotNull(slide.sound) + (0 until slide.steps).mapNotNull { slide.stepSound(it) }

/**
 * Resolves `SLIDES_START`: a number counting from 1, or a slide's name — "3" and
 * "Reveal" both work, and an unknown one opens at the first slide rather than failing.
 */
private fun startIndex(slides: List<Slide>, start: String?): Int {
    val wanted = start?.trim().orEmpty()
    if (wanted.isEmpty()) return 0

    wanted.toIntOrNull()?.let { return (it - 1).coerceIn(slides.indices) }

    val exact = slides.indexOfFirst { it.name.equals(wanted, ignoreCase = true) }
    if (exact >= 0) return exact
    val prefix = slides.indexOfFirst { it.name.startsWith(wanted, ignoreCase = true) }
    if (prefix >= 0) return prefix

    println("no slide called \"$wanted\"; starting at ${slides.first().name}")
    return 0
}
