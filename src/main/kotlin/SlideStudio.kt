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
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.depthBuffer
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadFont
import org.openrndr.draw.renderTarget
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.math.IntVector2
import org.openrndr.shape.Rectangle
import slideshow.Backdrop
import slideshow.Clock
import slideshow.Deck
import slideshow.DebugOverlay
import slideshow.FPS
import slideshow.Outline
import slideshow.Slide
import slideshow.drawers.Type
import slideshow.frames
import slideshow.mix
import slideshow.seconds
import java.io.File
import kotlin.math.min

/**
 * One slide, on its own, at the size of the pane it composes for — the drawer to work on
 * rather than the show to click through.
 *
 *     ./gradlew run -Popenrndr.application=SlideStudioKt
 *
 *     ->  <-      the next click, the one before — the deck's own arrows
 *     down  up    the next slide in the show, the one before, loaded when first asked for
 *     r  0        replay this slide from its first click
 *     p           hold the clock,  . and , step it a frame at a time
 *     d           the debug overlay, up by default
 *     s           write this frame to screenshots/
 *     esc         quit
 *
 * **The slides are the deck's own** — `show.slides`, the very objects the talk is made of —
 * so what is on screen here is what is on screen in the show and not a second arrangement
 * of it that has to be kept in step. This is [CardStudio]'s argument applied to the other
 * half of the canvas, and the same three pieces make it: the show's own objects, a clock,
 * and a window.
 *
 * **Only the slide you asked for loads.** [slideshow.present] loads every slide in the deck
 * before the first frame, and has to — a show must not hitch on a click — so a run pays for
 * all thirteen and the four mosaic cards whichever drawer is actually being detailed. A
 * studio has no show to hitch, so it loads one and pays that slide's own cost: measured
 * here, about 3s against 0.1–0.3s. Worth having, but the seconds are the smaller half of
 * it. What the studio really saves is the rest of the loop — no clicking to reach the slide,
 * a window the size of the pane rather than a 3840 canvas beside a card you are not working
 * on, and `s` / `SLIDE_AT` / `SLIDE_STILLS` scoped to the one drawer instead of the deck.
 *
 * `up`/`down` then load a neighbour the first time it is asked for, so looking at the slide
 * next door costs a keypress rather than another JVM.
 *
 * **It is a real [Deck] of one slide**, not a hand-rolled stage. So `position` is eased over
 * the slide's own `stepFrames`, `stepName` reads as it does in the talk, and the debug
 * overlay is the show's own — a click here is the click the audience sees, rather than a
 * second approximation of one that can drift from it.
 *
 * **The canvas is the slide pane's own size**, read off the show's settings rather than
 * stated — 1920x1080 of the committed 3840 canvas, once the chapter card's half is taken
 * off. A drawer lays out against `stage.bounds`, so that is the shape it has to be handed
 * or it is being detailed at a size it will never be seen at. The card beside it is not
 * drawn at all: that is [CardStudio]'s job, and loading four mosaics to look at a slide is
 * exactly the overhead this exists to avoid.
 *
 * `SLIDE` picks the slide — a number counting from 1, or any of the three things a slide can
 * be called: its `name`, its class, or its title in the running order. So `SLIDE=3`,
 * `SLIDE=Cut`, `SLIDE=HardCutSlide` and `SLIDE=catalogue` all reach one. The class matters
 * because every drawer here overrides `name`, and detailing one means navigating by file.
 * Unset, it falls back to `SLIDES_START` and then to the first slide.
 *
 * `SLIDE_STILLS=true` writes one png per click of that slide and quits, which is how to
 * judge a change without clicking through it; `SLIDE_AT=1.2,3.0` writes the frames at those
 * seconds instead, which is how to judge one that moves. `SLIDE_RECORD=true` films it to
 * `video/`, with `SLIDE_AUTOSTEP` clicking it hands-off. Timing is read only inside the draw
 * loop — see the `ScreenRecorder` note under demo01 in CLAUDE.md.
 *
 * **It goes on the wall the way the show does**, and lands where the *slide* does. The
 * projector arrangement is the show's own — `SLIDES_UNDECORATED`, `SLIDES_WINDOW_X/_Y`,
 * `SLIDES_WINDOW_SCALE` — inherited rather than restated, so hanging the talk hangs the
 * studio with it. The one thing it adds is the offset: the show's window starts at the
 * chapter card and the slide pane is the half beyond it, so the studio is placed a card's
 * width along and stands exactly on the drawer's own projector. `SLIDE_UNDECORATED`,
 * `SLIDE_WINDOW_X/_Y` and `SLIDE_WINDOW` override any of it for the studio alone, which is
 * what to reach for on one projector rather than two.
 */
fun main() = application {
    // `withEnv()` and not `show.settings`: the canvas size and the whole projector
    // arrangement live in `.env`, and the raw show carries only what Slideshow.kt declares.
    // Read raw, the studio silently ignored SLIDES_WINDOW_SCALE and every placement key and
    // opened a full-size window in the middle of the desk.
    val settings = show.withEnv().settings

    // The pane a slide actually gets in the show: the canvas less the chapter card's half
    // and the gutter between them. Stated here it would be a second copy of the geometry
    // declared in Slideshow.kt, and would go quietly wrong the day the card changes width.
    val paneWidth = settings.width - (settings.panelWidth?.let { it + settings.panelGap } ?: 0)
    val paneHeight = settings.height

    // Which slide the studio opens on, resolved before the window exists because the
    // window's size depends on it: a backdrop composes for the whole wall rather than the
    // pane beside the card, so the studio opens at the size of whatever it opens on.
    val opening = slideIndex(show.slides, Env["SLIDE"] ?: Env["SLIDES_START"]) {
        show.outline[it]?.title ?: show.slides[it].name
    }
    val wide = show.slides[opening] is Backdrop
    val openWidth = if (wide) settings.width else paneWidth

    // How much of the screen the window takes, inherited from the show so that one dial —
    // SLIDES_WINDOW_SCALE — sets the studio and the talk together and a drawer is detailed
    // at the size it is projected. SLIDE_WINDOW overrides it for the studio alone.
    val scale = Env["SLIDE_WINDOW"]?.toDoubleOrNull() ?: settings.windowScale

    // --- onto the wall ------------------------------------------------------------- //
    //
    // The projector arrangement is the show's, inherited whole: an *undecorated* window
    // placed by hand, because `fullscreen` takes one display and the show spans two (see
    // Settings.undecorated). What the studio adds is the offset — the show's window starts
    // at the chapter card, and the slide pane is the half beyond it, so a studio that
    // wants to stand where its drawer really stands has to skip the card's width.
    //
    // That offset is measured at the *show's* scale rather than the studio's: it is the
    // projector's own edge, a fact about where the show is hung, so shrinking the studio
    // window with SLIDE_WINDOW moves nothing. SLIDE_WINDOW_X/_Y place it outright — which
    // is what to reach for on one projector rather than two.
    //
    // `esc` quits, and with the decorations gone it is the only way out.
    val undecorated = Env["SLIDE_UNDECORATED"]?.let { Env.boolean("SLIDE_UNDECORATED") }
        ?: settings.undecorated
    // A backdrop stands on both projectors, so it takes no offset at all.
    val paneOffset = if (wide) 0 else ((settings.width - paneWidth) * settings.windowScale).toInt()
    val placeX = Env["SLIDE_WINDOW_X"]?.toIntOrNull() ?: settings.windowX?.plus(paneOffset)
    val placeY = Env["SLIDE_WINDOW_Y"]?.toIntOrNull() ?: settings.windowY

    configure {
        width = (openWidth * scale).toInt()
        height = (paneHeight * scale).toInt()
        title = "slide studio"
        hideWindowDecorations = undecorated
        placeX?.let { position = IntVector2(it, placeY ?: 0) }
    }

    program {
        val host = this

        // The furniture picks up the family here, as it does in the show — the slides are
        // the show's own objects and load exactly as they do there.
        Type.file = textFont

        val slides = show.slides
        require(slides.isNotEmpty()) { "this show has no slides to draw" }

        val record = Env.boolean("SLIDE_RECORD")
        val stills = Env.boolean("SLIDE_STILLS")

        // The frames to write, in seconds into the slide's own clock — the same affordance
        // as CARD_AT and HANDOVER_SAVE. A still says nothing about the order things arrived
        // in; a handful of pngs across the move does, and losslessly.
        val at = Env["SLIDE_AT"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.map { frames(it) }?.sorted().orEmpty()

        /** What to call a slide: its title in the running order, else the class name. */
        fun label(i: Int) = show.outline[i]?.title ?: slides[i].name

        val loaded = BooleanArray(slides.size)

        /**
         * Loads a slide the first time it is asked for, and says how long it took.
         *
         * Lazy on purpose, and it is the only lazy load anywhere here: the show cannot
         * afford one because a click must never wait, but in a studio there is nothing to
         * hitch — and paying the city's ten seconds only when you actually step onto the
         * city is the whole point of the file.
         */
        fun open(i: Int): Int {
            val target = (i + slides.size) % slides.size
            if (!loaded[target]) {
                val began = System.currentTimeMillis()
                slides[target].load(host)
                loaded[target] = true
                println("loaded %s in %.1fs".format(label(target), (System.currentTimeMillis() - began) / 1000.0))
            }
            return target
        }

        // Where the window went, because an undecorated one on a second display gives no
        // other sign of it — and a placement that missed is otherwise a black projector.
        println("window %dx%d at %s%s".format(
            (openWidth * scale).toInt(), (paneHeight * scale).toInt(),
            placeX?.let { "$it,${placeY ?: 0}" } ?: "wherever the window manager puts it",
            if (undecorated) ", undecorated — esc quits" else ""))

        var index = open(opening)
        println("slide %d/%d — %s — %d click%s".format(
            index + 1, slides.size, label(index),
            slides[index].steps, if (slides[index].steps == 1) "" else "s"))

        // A deck of one slide, so the clicks ease over the slide's own stepFrames and the
        // overlay reads exactly as it does in the talk. The outline is sliced to the one
        // placement, so the overlay still names the chapter this slide sits in.
        fun deckFor(i: Int) = Deck(listOf(slides[i]), 0, Outline(listOfNotNull(show.outline[i])))
        var deck = deckFor(index)

        val clock = Clock()
        var saveNext = false
        var taken = 0
        var debug = !record && !stills
        var fps = 0.0
        var lastSeconds = 0.0

        fun buffer(w: Int, h: Int) = renderTarget(w, h) {
            colorBuffer()
            // stencil as well as depth, so shapes fill and a 3D slide sorts
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }.also {
            it.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
            it.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR
        }

        // A slide is drawn at the pane's size and a backdrop at the wall's, each into a
        // canvas of its own, and whichever is up is fitted into the window. So up/down from
        // one kind onto the other letterboxes rather than resizing the window, and a still
        // is always at the composition's own size.
        val paneCanvas = buffer(paneWidth, paneHeight)
        val wallCanvas = if (slides.any { it is Backdrop }) buffer(settings.width, settings.height) else null
        fun canvasOf(slide: Slide) = if (slide is Backdrop) wallCanvas!! else paneCanvas
        fun boundsOf(slide: Slide) =
            canvasOf(slide).let { Rectangle(0.0, 0.0, it.width.toDouble(), it.height.toDouble()) }

        val overlay = DebugOverlay(runCatching { loadFont("data/fonts/default.otf", 13.0) }.getOrNull())

        // Key handlers move the deck and take no timestamp. Under ScreenRecorder the draw
        // loop runs on video time while a handler sees wall time, so a timestamp taken here
        // would sit in the draw loop's future — the note under demo01 in CLAUDE.md. The deck
        // animates against the frame it is ticked to, so there is nothing to drift.
        fun goToSlide(i: Int) {
            index = open(i)
            deck = deckFor(index)
            taken = 0
            println("slide %d/%d — %s — %d click%s".format(
                index + 1, slides.size, label(index),
                slides[index].steps, if (slides[index].steps == 1) "" else "s"))
        }

        keyboard.keyDown.listen { event ->
            when {
                event.key == KEY_ARROW_RIGHT -> deck.next()
                event.key == KEY_ARROW_LEFT -> deck.back()
                event.key == KEY_ARROW_DOWN -> goToSlide(index + 1)
                event.key == KEY_ARROW_UP -> goToSlide(index - 1)
                event.key == KEY_ESCAPE -> application.exit()

                event.name == "r" || event.name == "0" -> deck.replay()
                event.name == "s" -> saveNext = true
                event.name == "d" -> debug = !debug

                // The clock controls belong to the debug view rather than to the drawing,
                // exactly as in the show, so they are dead while it is down.
                debug && event.name == "p" -> clock.paused = !clock.paused
                debug && (event.name == "." || event.name == "period") -> clock.step(1)
                debug && (event.name == "," || event.name == "comma") -> clock.step(-1)
            }
        }

        if (record) {
            val file = "video/slide-%02d-%s.mp4".format(index + 1, slug(label(index)))
            println("filming $file")
            extend(ScreenRecorder().apply {
                outputFile = file
                frameRate = Env["SLIDE_FPS"]?.toIntOrNull() ?: FPS
                // so the clip comes out at the canvas size, not the window's
                contentScale = 1.0 / scale
                maximumDuration = Env["SLIDE_DURATION"]?.toDoubleOrNull() ?: 8.0
            })
        }

        // One png per click of this slide, then quit.
        val plan = if (stills && at.isEmpty()) (0 until slides[index].steps).toList() else emptyList()
        var planned = 0
        var heldSince = 0

        val autoStepFrames = frames(Env["SLIDE_AUTOSTEP"]?.toDoubleOrNull() ?: 0.0)
        var lastAutoStep = 0

        extend {
            // The one place a clock is read, and it is read inside the draw loop — so it is
            // video time while recording and wall time otherwise, and everything downstream
            // sees frame numbers.
            val frame = clock.advance(seconds)
            deck.tick(frame)

            fps = mix(fps, 1.0 / (seconds - lastSeconds).coerceAtLeast(1e-4), 0.1)
            lastSeconds = seconds

            if (plan.isNotEmpty()) {
                val step = plan[planned]
                if (deck.step != step) {
                    deck.goTo(0, step, cut = true)
                    heldSince = frame
                }
            } else if (autoStepFrames > 0 && frame - lastAutoStep >= autoStepFrames) {
                lastAutoStep = frame
                deck.next()
            }

            val canvas = canvasOf(slides[index])
            val shot = deck.shot(boundsOf(slides[index]))
            drawer.isolatedWithTarget(canvas) {
                drawer.ortho(canvas)
                drawer.clear(shot.slide.background)
                drawer.fill = ColorRGBa.BLACK
                drawer.stroke = null
                drawer.strokeWeight = 1.0
                drawer.shadeStyle = null
                shot.slide.draw(drawer, shot.stage)
            }

            // The canvas is *fitted* into the window rather than stretched to it, the same
            // arrangement `present` uses to put the deck's canvas in its window: a window of
            // another shape letterboxes instead of distorting the slide.
            val window = Rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
            val fit = min(window.width / canvas.width, window.height / canvas.height)
            val shown = Rectangle.fromCenter(window.center, canvas.width * fit, canvas.height * fit)
            if (fit < 1.0) canvas.colorBuffer(0).generateMipmaps()
            drawer.clear(ColorRGBa.BLACK)
            drawer.image(canvas.colorBuffer(0), shown.corner.x, shown.corner.y, shown.width, shown.height)

            // A png is of the canvas, never of the window, so it is the composition at its
            // own size whatever the window is doing.
            val timed = at.isNotEmpty() && taken < at.size && shot.stage.frame >= at[taken]
            val held = plan.isNotEmpty() && frame - heldSince >= STILL_HOLD
            if (saveNext || timed || held) {
                val name = slug(label(index))
                val file = when {
                    timed -> File("screenshots/slide-%02d-%s-%.1fs.png".format(index + 1, name, seconds(at[taken])))
                    held -> File("screenshots/slide-%02d-%s-%d.png".format(index + 1, name, plan[planned]))
                    else -> File("screenshots/slide-%02d-%s.png".format(index + 1, name))
                }
                file.parentFile.mkdirs()
                canvas.colorBuffer(0).saveToFile(file)
                println("saved ${file.path}")
                saveNext = false
                if (timed) {
                    taken++
                    if (taken >= at.size) application.exit()
                } else if (held) {
                    planned++
                    if (planned >= plan.size) application.exit()
                }
            }

            // Drawn on the *window*, on top of the finished frame, so it scales with the
            // screen rather than with the composition and never lands in a still or a clip.
            if (debug && plan.isEmpty()) {
                overlay.draw(drawer, deck, shot.stage, width, height, fps, clock.paused)
            }
        }
    }
}

/**
 * `SLIDE` as an index: a number counting from 1, or any of the three things a slide can be
 * called — so "12", "Cut", "HardCutSlide" and "catalogue" all reach one, and something that
 * matches nothing opens the first slide rather than failing.
 *
 * Three, and it has to be three, which is what `SLIDES_START` gets wrong by matching only
 * the first:
 *
 *  - **`Slide.name`**, which is what the overlay shows — "Cut", "Stack up", "City".
 *  - **the class**, which is what the *file* is called. Every drawer in this deck overrides
 *    `name`, so the class name is otherwise unreachable: you would be editing
 *    `HardCutSlide.kt` and have no way to ask for it but by knowing it answers to "Cut".
 *    Detailing a drawer means navigating by file, so the file has to be the address.
 *  - **the title in the running order**, since the show calls most slides something other
 *    than either — `SLIDE=catalogue` finding "The catalogue city" is the difference between
 *    reading the running order and counting it.
 *
 * Exact first, then a prefix, then anywhere in the string, so a short unambiguous ask is not
 * beaten to it by a longer name that merely contains it.
 */
private fun slideIndex(slides: List<Slide>, wanted: String?, label: (Int) -> String): Int {
    val asked = wanted?.trim().orEmpty()
    if (asked.isEmpty()) return 0

    asked.toIntOrNull()?.let { return (it - 1).coerceIn(slides.indices) }

    fun names(i: Int) = listOf(slides[i].name, label(i), slides[i]::class.simpleName.orEmpty())

    fun find(match: (String) -> Boolean) =
        slides.indices.firstOrNull { i -> names(i).any(match) } ?: -1

    val exact = find { it.equals(asked, ignoreCase = true) }
    if (exact >= 0) return exact
    val prefix = find { it.startsWith(asked, ignoreCase = true) }
    if (prefix >= 0) return prefix
    val loose = find { it.contains(asked, ignoreCase = true) }
    if (loose >= 0) return loose

    println("no slide called \"$asked\"; opening on ${slides.first().name}")
    return 0
}

private fun slug(name: String) =
    name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48)

/** Frames a still waits before it is taken, so the click has finished playing. */
private const val STILL_HOLD = 95
