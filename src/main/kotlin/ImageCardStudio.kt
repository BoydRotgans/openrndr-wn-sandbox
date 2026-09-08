import org.openrndr.application
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.renderTarget
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.color.ColorRGBa
import org.openrndr.shape.Rectangle
import slideshow.Section
import slideshow.Stage
import slideshow.drawers.Type
import slideshow.FPS
import slideshow.frames
import slideshow.seconds
import java.io.File
import kotlin.math.min

/**
 * [ObjectImageChapterPanel] on its own, at 1920x1080 — the picture-backed card to work on,
 * with no show around it.
 *
 * ```
 * ./gradlew run -Popenrndr.application=ImageCardStudioKt
 * ```
 *
 * `j` and `k` step the mark size, `b` switches between the field and the plate it reads, `s`
 * writes a still, `r` replays the arrival, `esc` quits. `CARD_STILLS=true` writes one png and
 * quits, which is how to judge a change without watching it; `CARD_WINDOW` scales the window.
 *
 * It is `CardStudio` cut down to one card and no deck: the show has to load its slides before
 * it can show a panel, and this card is a tenth of a second.
 *
 * **What the card is made of is stated here, not read off `SLIDES_CARD_*`** — see the note in
 * the program. `CARD_SHEET`, `_COARSE`, `_FINEST`, `_SHAPE`, `_FILL`, `_UNIFORM`, `_SHRINK` and
 * `_THRESHOLD` are the studio's own and open on the recipe that matches the reference sketch;
 * `SLIDES_CARD_IMAGE` still names the picture, and `CARD_TITLE`/`CARD_SUB` the section it
 * stands for, which is only what the seed and the foot line are taken from.
 */
fun main() = application {
    val scale = Env["CARD_WINDOW"]?.toDoubleOrNull() ?: 1.0

    configure {
        width = (WIDE * scale).toInt()
        height = (HIGH * scale).toInt()
        title = "image chapter card"
    }

    program {
        Type.file = textFont

        // **The studio states the recipe rather than taking the show's.** `SLIDES_CARD_*` is
        // the *typeset* card's settings and the committed `.env` is tuned for it — a 1.85:1
        // cell off `subset.svg`, one mark size, a ground that barely shows. A picture wants
        // none of those: the marks have to be square or the letterforms smear, and the ground
        // has to be visible or there is no field for the words to be cut out of. Reading the
        // show's keys here would come up wrong every time and look like a fault in the card.
        //
        // So these are the studio's own, under its own `CARD_*` prefix, and they default to
        // the recipe that matches the reference sketch: square marks off a folder of drawn
        // shapes, each fitted to its own cell, one size, with the ground standing at just
        // under half.
        val card = ObjectImageChapterPanel(
            Section(
                Env["CARD_NUMBER"] ?: "1",
                Env["CARD_TITLE"] ?: "De wereld van bouwen",
                Env["CARD_SUB"] ?: ""
            ),
            sheet = Env["CARD_SHEET"] ?: "data/svg/subset.svg",
            objects = Env["CARD_OBJECTS"]?.split(",")?.mapNotNull { it.trim().toIntOrNull() },
            coarse = Env["CARD_COARSE"]?.toDoubleOrNull() ?: 32.0,
            finest = Env["CARD_FINEST"]?.toDoubleOrNull() ?: 8.0,
            shape = Env["CARD_SHAPE"]?.toDoubleOrNull(),
            fill = Env["CARD_FILL"]?.toDoubleOrNull() ?: 1.0,
            gap = Env["CARD_GAP"]?.toDoubleOrNull() ?: 2.0,
            levels = (Env["CARD_LEVELS"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
                ?.takeIf { it.size == 2 }?.let { it[0] to it[1] }) ?: (0.06 to 0.38),
            uniform = Env.boolean("CARD_UNIFORM", false),
            shrink = Env["CARD_SHRINK"]?.toDoubleOrNull() ?: 1.0,
            threshold = Env["CARD_THRESHOLD"]?.toDoubleOrNull() ?: 0.32,
            solid = Env["CARD_SOLID"]?.toDoubleOrNull() ?: 0.62,
            // Longer than the typeset card's 1.2s: there are two passes and a pause in this
            // one, so the same figure would run them into each other.
            reveal = frames(Env["CARD_REVEAL"]?.toDoubleOrNull() ?: 2.8),
            sweep = Env["CARD_SWEEP"]?.toDoubleOrNull() ?: 1.0,
            stage = Env["CARD_STAGE"]?.toDoubleOrNull() ?: 0.45,
            delay = Env["CARD_DELAY"]?.toDoubleOrNull() ?: 0.12,
            ink = ColorRGBa.fromHex(Env["CARD_INK"] ?: "#FFFFFF"),
            ground = ColorRGBa.fromHex(Env["CARD_GROUND"] ?: "#2E2E2E")
        )
        card.load(this)

        val stills = Env.boolean("CARD_STILLS")

        // **The recorder takes `contentScale = 1.0 / scale`**, so a clip comes out at the
        // canvas's own size and not the window's — the same trick the deck and demo02 use. The
        // card is already composed at twice 1920x1080 into its own target, so what is filmed is
        // the composition rather than a picture of the window.
        if (Env.boolean("CARD_RECORD")) {
            val file = "video/image-card.mp4"
            println("filming to $file")
            extend(ScreenRecorder().apply {
                outputFile = file
                frameRate = Env["CARD_FPS"]?.toIntOrNull() ?: FPS
                contentScale = 1.0 / scale
                maximumDuration = Env["CARD_DURATION"]?.toDoubleOrNull() ?: 5.0
            })
        }

        // Frames to write, in seconds into the card's own clock, then quit — the same
        // affordance as `CardStudio`'s `CARD_AT`. It is the only way to judge a *reveal*: a
        // single still says nothing about the order things arrived in.
        val at = Env["CARD_AT"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.map { frames(it) }?.sorted().orEmpty()
        var taken = 0
        var start = 0
        var frame = 0
        var saveNext = false

        // **Composed at twice the size.** `contentScale` doubles the real buffer behind the
        // same 1920x1080 coordinates, so the card lays out exactly as before and nothing it
        // draws has to know — but the edges are resolved at 3840x2160 and a still comes out at
        // that size. A field of this grain is all edge, so it is the one thing worth the memory.
        val canvas = renderTarget(WIDE.toInt(), HIGH.toInt(), contentScale = DETAIL) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }
        // Mipmapped for the windows smaller than the canvas: a mosaic of this grain aliases
        // badly under a plain minify.
        canvas.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        canvas.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR
        val bounds = Rectangle(0.0, 0.0, WIDE, HIGH)

        keyboard.keyDown.listen { event ->
            when {
                event.name == "escape" -> application.exit()
                event.name == "r" -> start = frame
                event.name == "b" -> card.plainly = !card.plainly
                event.name == "j" -> card.rescale(1.0 / STEP)
                event.name == "k" -> card.rescale(STEP)
                event.name == "s" -> saveNext = true
            }
        }

        extend {
            // Frames, counted here and nowhere else — the note under demo01.
            frame++
            val since = frame - start

            drawer.isolatedWithTarget(canvas) {
                drawer.ortho(canvas)
                drawer.clear(card.background)
                drawer.stroke = null
                drawer.shadeStyle = null
                // A card at rest in its section: one step, holding, its own frame count
                // running, which is all its arrival is built on.
                card.draw(drawer, Stage(bounds, since, 1, 0, 0.0, 1.0, 0.0, 0.0, 0))
            }

            // Fitted into the window rather than stretched to it, so a window of another shape
            // letterboxes instead of distorting the card.
            val window = Rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
            val fit = min(window.width / WIDE, window.height / HIGH)
            val shown = Rectangle.fromCenter(window.center, WIDE * fit, HIGH * fit)
            if (fit < 1.0) canvas.colorBuffer(0).generateMipmaps()
            drawer.clear(ColorRGBa.BLACK)
            drawer.image(canvas.colorBuffer(0), shown.corner.x, shown.corner.y, shown.width, shown.height)

            val timed = at.isNotEmpty() && taken < at.size && since >= at[taken]
            if (saveNext || timed || (stills && at.isEmpty() && since >= HOLD)) {
                val file = if (timed) File("screenshots/image-card-%.1fs.png".format(seconds(at[taken])))
                else File("screenshots/image-card.png")
                file.parentFile.mkdirs()
                canvas.colorBuffer(0).saveToFile(file)
                println("saved ${file.path}")
                saveNext = false
                if (timed) {
                    taken++
                    if (taken >= at.size) application.exit()
                } else if (stills) application.exit()
            }
        }
    }
}

/** Frames a still waits before it is taken, so the field has finished arriving. */
private val HOLD = frames(4.5)

/** What one press of j or k is worth. */
private const val STEP = 1.18

/** Real pixels to one canvas pixel: the card is composed at 1920x1080 and rendered at twice it. */
private const val DETAIL = 2.0

private const val WIDE = 1920.0
private const val HIGH = 1080.0
