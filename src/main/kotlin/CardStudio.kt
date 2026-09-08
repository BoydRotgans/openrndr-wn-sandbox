import org.openrndr.KEY_ARROW_LEFT
import org.openrndr.KEY_ARROW_RIGHT
import org.openrndr.KEY_ESCAPE
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.depthBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.loadFont
import org.openrndr.draw.renderTarget
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.shape.Rectangle
import slideshow.Clock
import slideshow.FPS
import slideshow.Slide
import slideshow.Stage
import slideshow.drawers.Type
import slideshow.frames
import slideshow.seconds
import slideshow.mix
import java.io.File
import kotlin.math.min

/**
 * One chapter card, on its own, at 1920x1080 — the card to work on rather than the show to
 * click through.
 *
 *     ./gradlew run -Popenrndr.application=CardStudioKt
 *
 *     ->  <-      the next card, the one before, each replayed from its first frame
 *     j  k        the marks a step smaller, a step larger
 *     r           replay this one
 *     p           hold the clock,  . and , step it a frame at a time
 *     b           the plate the field reads, with its boxes drawn — the debug view
 *     s           write this frame to screenshots/
 *     esc         quit
 *
 * **The cards are the deck's own** — `show.panels`, the very objects the slideshow stands
 * beside its slides — so what is on screen here is what is on screen in the talk and not a
 * second arrangement of it that has to be kept in step. All this program adds is a clock and
 * a window: the card is handed a [Stage] whose `frame` runs from zero, which is exactly what
 * it gets when the show enters its chapter.
 *
 * **Only the cards load, never the slides.** The deck spends some ten seconds in `load`
 * collecting and packing the city; a card is a tenth of a second, and iterating on one should
 * cost the second kind of startup rather than the first. The four cards are all loaded up
 * front so stepping between them is instant.
 *
 * What the card is made of is steered from `.env` — `SLIDES_CARD_COARSE`, `_FINEST`,
 * `_SHAPE`, `_FILL`, `_GROUND`, `_INK`, `_PAPER`, `_REVEAL`, `_POP`, `_SHEET`. Those are
 * `ObjectChapterPanel`'s own constructor defaults, so a value tried here is the value the
 * show runs with and the two cannot drift apart.
 *
 * `CARD_RECORD=true` films it instead. **One chapter to a run**: `ScreenRecorder` writes a
 * file per program and a program is one `application {}`, so filming four cards is four runs
 * — `CARD=1` … `4`. Clumsier to type than a loop, and much simpler than restarting GLFW
 * between takes. `CARD_STILLS=true` writes a png of every card and quits, which is how to
 * judge a change to the grain without watching any of them.
 *
 * **The window is the card's own size and the card is composed into a target, not into the
 * window.** So a still, a filmed frame and what is on screen are the same pixels: at the
 * default `CARD_WINDOW=1.0` they are the same size as well. The canvas is *fitted* into the
 * window rather than stretched to it — a window of another shape letterboxes — and the
 * recorder's `contentScale` puts the resolution back whatever the window is doing. Both are
 * the deck's own arrangement, for the deck's own reasons.
 */
fun main() = application {
    // 1.0 is one canvas pixel to one screen pixel: the card is 1920x1080 and the window is
    // the same, so what is on screen is the composition itself rather than a picture of it.
    // A smaller screen wants CARD_WINDOW=0.5 and loses nothing but size — the canvas is
    // fitted into whatever window it gets, never stretched to it.
    val scale = Env["CARD_WINDOW"]?.toDoubleOrNull() ?: 1.0

    configure {
        width = (WIDTH * scale).toInt()
        height = (HEIGHT * scale).toInt()
        title = "chapter card"
    }

    program {
        // The furniture picks up the family here, as it does in the show — the cards are the
        // show's own objects and load exactly as they do there.
        Type.file = textFont

        val cards = show.panels
        require(cards.isNotEmpty()) { "this show has no chapter cards to draw" }

        val record = Env.boolean("CARD_RECORD")
        val stills = Env.boolean("CARD_STILLS")

        // The frames to write, in seconds into the card's own clock — the same affordance as
        // HANDOVER_SAVE. Two of them a period apart is how a loop is checked: the pngs are
        // lossless, where a clip's own compression noise swamps the difference being looked for.
        val at = Env["CARD_AT"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.map { frames(it) }?.sorted().orEmpty()

        // Everything up front, exactly as the deck does it: nothing may load on a keypress.
        val loading = System.currentTimeMillis()
        cards.forEach { it.load(this) }
        println("%d cards in %.1fs".format(cards.size, (System.currentTimeMillis() - loading) / 1000.0))

        var index = cardIndex(cards, Env["CARD"])
        val one = Env["CARD"] != null
        val clock = Clock()
        var start = 0
        var saveNext = false
        var taken = 0
        var rescale = 1.0
        var overlay = !record && !stills
        var fps = 0.0
        var lastSeconds = 0.0

        val canvas = renderTarget(WIDTH.toInt(), HEIGHT.toInt()) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }
        // Mipmapped for the windows that are smaller than the canvas: a mosaic of this grain
        // aliases badly under a plain minify. At 1:1 none are generated — see the draw loop.
        canvas.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        canvas.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR
        val bounds = Rectangle(0.0, 0.0, WIDTH, HEIGHT)
        val small = runCatching { loadFont("data/fonts/default.otf", 13.0) }.getOrNull()

        /**
         * The little readout at the foot of the window, drawn on the **window** and on top of
         * the finished frame — so it scales with the screen rather than with the composition,
         * and never lands in a still or a clip.
         *
         * `width` and `height` are read here rather than inside `isolated`, where they would
         * resolve to the *drawer's* own — the trap recorded under the chapter card in
         * CLAUDE.md.
         */
        fun hud(lines: List<String>) {
            val face = small ?: return
            val w = width.toDouble()
            val h = height.toDouble()
            drawer.isolated {
                drawer.stroke = null
                drawer.shadeStyle = null
                drawer.fontMap = face
                val top = h - PADDING - lines.size * LINE
                drawer.fill = ColorRGBa.BLACK.opacify(0.55)
                drawer.rectangle(0.0, top - LINE, w, lines.size * LINE + LINE * 1.5)
                drawer.fill = ColorRGBa.WHITE.opacify(0.85)
                lines.forEachIndexed { i, line -> drawer.text(line, PADDING, top + i * LINE) }
            }
        }

        // Key handlers move the card and take no timestamp. Under ScreenRecorder the draw loop
        // runs on video time while a handler sees wall time, so a timestamp taken here would
        // sit in the draw loop's future — the note under demo01 in CLAUDE.md. `clock.frame` is
        // a frame count the draw loop owns, not a clock, so replaying off it is safe.
        fun show(card: Int) {
            index = (card + cards.size) % cards.size
            start = clock.frame
        }

        keyboard.keyDown.listen { event ->
            when {
                event.key == KEY_ARROW_RIGHT -> show(index + 1)
                event.key == KEY_ARROW_LEFT -> show(index - 1)
                event.key == KEY_ESCAPE -> application.exit()

                event.name == "r" -> start = clock.frame
                // The marks a step smaller or larger. Taken as a pending factor rather than
                // acted on here: rebuilding the field touches the GPU, and a key handler is
                // not the draw loop.
                event.name == "j" -> rescale /= STEP
                event.name == "k" -> rescale *= STEP
                event.name == "s" -> saveNext = true
                event.name == "d" -> overlay = !overlay
                // The plate instead of the field: the same card at the stage before the
                // elements read it, with every box the layout stands on drawn over it.
                event.name == "b" -> cards.forEach {
                    (it as? MosaicCard)?.let { card -> card.plainly = !card.plainly }
                }
                event.name == "p" -> clock.paused = !clock.paused
                event.name == "." || event.name == "period" -> clock.step(1)
                event.name == "," || event.name == "comma" -> clock.step(-1)
            }
        }

        if (record) {
            val file = "video/chapter-%d-%s.mp4".format(index + 1, slug(cards[index].name))
            println("filming card ${index + 1} of ${cards.size} — \"${cards[index].name}\" — to $file")
            extend(ScreenRecorder().apply {
                outputFile = file
                frameRate = Env["CARD_FPS"]?.toIntOrNull() ?: FPS
                contentScale = 1.0 / scale
                maximumDuration = Env["CARD_DURATION"]?.toDoubleOrNull() ?: 4.0
            })
        }

        extend {
            // The one place a clock is read, and it is read inside the draw loop — so it is
            // video time while recording and wall time otherwise, and everything downstream
            // sees frame numbers.
            val now = clock.advance(seconds)
            val frame = now - start
            val card = cards[index]

            val card2 = card as? MosaicCard

            if (rescale != 1.0) {
                card2?.rescale(rescale)
                rescale = 1.0
            }

            fps = mix(fps, 1.0 / (seconds - lastSeconds).coerceAtLeast(1e-4), 0.1)
            lastSeconds = seconds

            drawer.isolatedWithTarget(canvas) {
                drawer.ortho(canvas)
                drawer.clear(card.background)
                drawer.fill = ColorRGBa.BLACK
                drawer.stroke = null
                drawer.strokeWeight = 1.0
                drawer.shadeStyle = null
                // A card at rest in its section: one step, holding, its own frame count
                // running — which is all its arrival is built on.
                card.draw(drawer, Stage(bounds, frame, 1, 0, 0.0, 1.0, 0.0, 0.0, 0))
            }

            // The canvas is *fitted* into the window rather than stretched to it, the same
            // arrangement `present` uses to put the deck's canvas in its window: a window of
            // another shape letterboxes instead of distorting the card.
            val window = Rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
            val fit = min(window.width / WIDTH, window.height / HEIGHT)
            val shown = Rectangle.fromCenter(window.center, WIDTH * fit, HEIGHT * fit)
            if (fit < 1.0) canvas.colorBuffer(0).generateMipmaps()
            drawer.clear(ColorRGBa.BLACK)
            drawer.image(canvas.colorBuffer(0), shown.corner.x, shown.corner.y, shown.width, shown.height)

            // A png is of the canvas, never of the window, so it is the composition at its own
            // size whatever the window is doing.
            val timed = at.isNotEmpty() && taken < at.size && frame >= at[taken]
            if (saveNext || timed || (stills && at.isEmpty() && frame >= HOLD)) {
                val file = if (timed)
                    File("screenshots/card-%d-%s-%.1fs.png".format(
                        index + 1, slug(card.name), seconds(at[taken])))
                else File("screenshots/card-%d-%s.png".format(index + 1, slug(card.name)))
                file.parentFile.mkdirs()
                canvas.colorBuffer(0).saveToFile(file)
                println("saved ${file.path}")
                saveNext = false
                if (timed) {
                    taken++
                    if (taken >= at.size) application.exit()
                } else if (stills) {
                    // `CARD` names one card, so stills writes that one and quits; unset, it
                    // walks the lot. Iterating on a chapter should not cost the other three.
                    if (one || index + 1 >= cards.size) application.exit() else show(index + 1)
                }
            }

            // Drawn on the *window*, on top of the finished frame, so it scales with the
            // screen rather than with the composition and never lands in a still or a clip.
            if (overlay) {
                val recipe = card2?.recipe ?: ""
                hud(
                    listOf(
                        "%d/%d  %s".format(index + 1, cards.size, card.name),
                        "frame %d   %.2fs   %.0f fps%s".format(
                            frame, frame.toDouble() / FPS, fps, if (clock.paused) "   held" else ""
                        ),
                        recipe
                    ).filter { it.isNotBlank() }
                )
            }
        }
    }
}

/**
 * `CARD` as an index: a number counting from 1, or the start of a chapter's name — "2" and
 * "Beton" both work, and something that matches nothing opens the first card rather than
 * failing. The same rule as `SLIDES_START`.
 */
private fun cardIndex(cards: List<Slide>, wanted: String?): Int {
    val asked = wanted?.trim().orEmpty()
    if (asked.isEmpty()) return 0

    asked.toIntOrNull()?.let { return (it - 1).coerceIn(cards.indices) }

    val match = cards.indexOfFirst { it.name.startsWith(asked, ignoreCase = true) }
    if (match >= 0) return match

    println("no chapter called \"$asked\"; opening on ${cards.first().name}")
    return 0
}

private fun slug(name: String) =
    name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48)

/** Frames a still waits before it is taken, so the card has finished arriving. */
private val HOLD = frames(2.0)

/** What one press of j or k is worth. A sixth, so a few presses is a real change. */
private const val STEP = 1.18

private const val PADDING = 14.0
private const val LINE = 18.0
private const val WIDTH = 1920.0
private const val HEIGHT = 1080.0
