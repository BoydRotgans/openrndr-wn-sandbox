// ============================================================================ //
//  No `package` declaration, deliberately: loadMarkTemplates, mosaicField,
//  fieldBuffer, MOSAIC_FIELD and Env all live in the default package, which Kotlin
//  cannot import into a named one.
// ============================================================================ //

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.parameter
import org.openrndr.draw.renderTarget
import org.openrndr.draw.shadeStyle
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.FPS
import slideshow.frames
import slideshow.seconds
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/**
 * The mosaic field with **nothing behind it but a growing circle**.
 *
 * ```
 * ./gradlew run -Popenrndr.application=CircleMosaicKt
 * ```
 *
 * `ObjectImageChapterPanel` stands a field of catalogue elements on a plate and lets the plate
 * decide, per cell, how big the mark is and what colour it takes. This is the same field
 * reading the same kind of plate, except that what is painted onto it is one white circle that
 * grows — so the whole demo is the one line in [paint] and everything else is the card's.
 *
 * **That is the point of doing it through a plate at all: the field never knew it was reading
 * letters.** A picture off disk, type set to the frame, or a circle drawn a frame at a time are
 * all the same thing to it — a black and white mask with a mip chain on it.
 *
 * What comes out is a ring rather than a disc, and that is the packing rather than anything
 * asked for: cells wholly inside the circle stand a big mark, cells wholly outside stand a
 * small one, and only the cells the edge crosses subdivide. So the travelling edge is a band of
 * fine marks moving outward through a field of coarse ones.
 *
 * **The plate is repainted every frame and its mip chain rebuilt with it**, which is the
 * typeset card's arrangement rather than the picture card's — a picture off disk cannot move,
 * a circle does nothing else.
 *
 * **The ground is packed as well as the disc, and it has to be.** A cell outside the circle is
 * as settled as one inside it, so it stands its element too — smaller, by `CIRCLE_SHRINK`, and
 * in `CIRCLE_GROUND`. Left dark it reads as a disc on empty paper and the piece is a shape; at
 * a grey that shows, the circle is passing *through* a standing field and swelling the marks it
 * crosses, which is the thing worth looking at. `SHRINK=1` hands the whole job to colour.
 *
 * `j`/`k` step the mark size, `b` switches between the field and the plate it reads, `p` holds
 * the clock and `.` `,` step it, `r` restarts, `s` writes a still, `esc` quits.
 *
 * **The keys are the studios', and so is everything about how it is composed**: 1920x1080 into
 * its own target at twice the size, fitted into the window rather than stretched to it, so a
 * still, a filmed frame and what is on screen are the same pixels. `CIRCLE_STILLS` writes one
 * png and quits, `CIRCLE_AT=1,2,3` writes the frames at those seconds, `CIRCLE_RECORD` films.
 */
fun main() = application {
    val scale = Env["CIRCLE_WINDOW"]?.toDoubleOrNull() ?: 1.0

    configure {
        width = (WIDE * scale).toInt()
        height = (HIGH * scale).toInt()
        title = "circle mosaic"
    }

    program {
        // ---- what the field is made of ------------------------------------------------ //
        //
        // Its own `CIRCLE_*` keys rather than the show's `SLIDES_CARD_*`, for the reason
        // `ImageCardStudio` states its own: those are the typeset card's, tuned for a 1.85:1
        // cell off subset.svg and a ground that barely shows. A circle wants a square cell and
        // a visible ground, and reading the show's keys here would come up wrong every time
        // and read as a fault in the field.
        val sheetFile = File(Env["CIRCLE_SHEET"] ?: "data/svg/subset.svg")
        val all = loadMarkTemplates(sheetFile)
        val picks = Env["CIRCLE_OBJECTS"]?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }
        val templates = picks?.mapNotNull { all.getOrNull(it) }?.takeIf { it.isNotEmpty() } ?: all
        if (templates.isEmpty()) {
            println("no marks at ${sheetFile.path} — nothing to stand the circle in")
            application.exit()
            return@program
        }

        val coarse = Env["CIRCLE_COARSE"]?.toDoubleOrNull() ?: 64.0
        val finest = (Env["CIRCLE_FINEST"]?.toDoubleOrNull() ?: 16.0).coerceAtMost(coarse)
        // Unset takes the sheet's own median, the same move as the cards: an element fitted
        // into a cell of another proportion letterboxes, so naming a sheet without matching the
        // cell to it quietly halves the ink.
        val median = templates.map { it.aspect }.sorted()[templates.size / 2]
        val cell = Env["CIRCLE_SHAPE"]?.toDoubleOrNull() ?: median
        val fill = Env["CIRCLE_FILL"]?.toDoubleOrNull() ?: 0.88
        val gap = Env["CIRCLE_GAP"]?.toDoubleOrNull() ?: 2.0
        val uniform = Env.boolean("CIRCLE_UNIFORM", false)
        val seed = Env["CIRCLE_SEED"]?.toIntOrNull() ?: 0

        val ink = ColorRGBa.fromHex(Env["CIRCLE_INK"] ?: "#FFFFFF")
        val ground = ColorRGBa.fromHex(Env["CIRCLE_GROUND"] ?: "#454545")
        val paper = ColorRGBa.fromHex(Env["CIRCLE_PAPER"] ?: "#000000")
        val shrink = Env["CIRCLE_SHRINK"]?.toDoubleOrNull() ?: 0.45
        val threshold = Env["CIRCLE_THRESHOLD"]?.toDoubleOrNull() ?: 0.32
        val solid = Env["CIRCLE_SOLID"]?.toDoubleOrNull() ?: 0.88

        // ---- the circle --------------------------------------------------------------- //
        val period = frames(Env["CIRCLE_PERIOD"]?.toDoubleOrNull() ?: 6.0).coerceAtLeast(1)
        // Grow and come back rather than grow and cut. At full size every cell is inside the
        // ink, so restarting from nothing throws the whole field down a size in one frame —
        // a flash, and the one thing a loop of this cannot afford. A cosine turns at either
        // end instead of snapping back, and it closes the loop exactly. `false` is the plain
        // sawtooth for a clip that is not meant to loop.
        val returns = Env.boolean("CIRCLE_RETURN", true)
        // How far past the corner the circle grows: 1 is exactly covered, more holds the full
        // field for a moment at the top of the swing.
        val over = Env["CIRCLE_OVER"]?.toDoubleOrNull() ?: 1.0
        val centre = (Env["CIRCLE_CENTRE"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.takeIf { it.size == 2 }?.let { Vector2(it[0], it[1]) } ?: Vector2(0.5, 0.5))
            .let { Vector2(it.x * WIDE, it.y * HIGH) }
        // The corner furthest from where the circle stands, so it covers the frame wherever
        // it is centred rather than only when it is in the middle.
        val reach = listOf(
            Vector2(0.0, 0.0), Vector2(WIDE, 0.0), Vector2(0.0, HIGH), Vector2(WIDE, HIGH)
        ).maxOf { hypot(it.x - centre.x, it.y - centre.y) } * over

        // ---- the field ---------------------------------------------------------------- //
        var markSize = coarse
        var markFinest = finest
        var cells = fieldBuffer(
            mosaicField(WIDE.toInt(), HIGH.toInt(), templates, markSize, markFinest,
                cell, fill, gap, seed, uniform), templates)
        var cellCount = 0

        fun stand() {
            cells.destroy()
            val field = mosaicField(WIDE.toInt(), HIGH.toInt(), templates, markSize, markFinest,
                cell, fill, gap, seed, uniform)
            cellCount = field.size
            cells = fieldBuffer(field, templates)
            println(("%s, %d of %d marks   %d cells   coarse %.0f → finest %.0f   cell %.2f%s" +
                    "   fill %.2f%s gap %.0fpx").format(
                sheetFile.name, templates.size, all.size, cellCount, markSize, markFinest,
                cell, if (Env["CIRCLE_SHAPE"] == null) " (off the sheet)" else "",
                fill, if (uniform) " one size" else " fitted", gap))
        }
        stand()

        // ---- the plate ---------------------------------------------------------------- //
        //
        // **On the cell's aspect, not the pane's**: a cell is then square in texels, so a
        // square mip average is exactly the cell's own box and coverage costs one `textureLod`.
        val across = (WIDE / TEXEL).toInt()
        val down = (HIGH * cell / TEXEL).toInt()

        // **Asked for with a mip chain, and it has to be asked for**: a colour buffer is one
        // level by default and `textureLod` on a one-level texture does not fail — it hands
        // back level 0, so every cell reads a point sample at its own centre, which is always
        // 0 or 1. Nothing ever straddles the circle's edge, nothing subdivides, and what comes
        // out is blocks of the coarsest cell. It renders, and it is not the picture.
        val mask = colorBuffer(
            across, down, contentScale = DETAIL,
            levels = mipLevels((across * DETAIL).toInt(), (down * DETAIL).toInt())
        )
        mask.filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        mask.filterMag = MagnifyingFilter.LINEAR
        val plate = renderTarget(across, down, contentScale = DETAIL) { colorBuffer(mask) }

        /** The circle onto the plate, in the pane's own coordinates. */
        fun paint(drawer: Drawer, radius: Double) {
            drawer.isolatedWithTarget(plate) {
                drawer.ortho(plate)
                // Black is "no ink" — the ground the field stands in where the circle is not.
                drawer.clear(ColorRGBa.BLACK)
                drawer.fill = ColorRGBa.WHITE
                drawer.stroke = null
                drawer.shadeStyle = null
                // The plate is taller than the pane in proportion, so one scale here is the
                // whole of it and nothing else has to know: the circle comes out an ellipse in
                // the plate's own pixels and a true circle to the field that reads it back.
                drawer.scale(plate.width / WIDE, plate.height / HIGH)
                drawer.circle(centre, radius)
            }
            mask.generateMipmaps()
        }

        // ---- the arrival -------------------------------------------------------------- //
        val reveal = frames(Env["CIRCLE_REVEAL"]?.toDoubleOrNull() ?: 1.2)
        val pop = frames(Env["CIRCLE_POP"]?.toDoubleOrNull() ?: 0.3)

        fun standing(arrived: Double) = shadeStyle {
            vertexTransform = MOSAIC_FIELD
            fragmentTransform = MOSAIC_FIELD_COLOUR
            parameter("mask", mask)
            parameter("pane", Vector2(WIDE, HIGH))
            parameter("shape", cell)
            parameter("texel", TEXEL)
            // **markFinest, not finest**: j and k rebuild the field at another size, and a
            // shader still holding the size it was built with decides every cell is too big to
            // be the finest level — so nothing straddling the circle's edge may stand, and the
            // circle comes out as a hole in an otherwise intact ground.
            parameter("finest", markFinest)
            parameter("solid", solid)
            parameter("empty", EMPTY)
            parameter("threshold", threshold)
            parameter("shrink", shrink)
            parameter("ink", ink)
            parameter("ground", ground)
            // **No sweep.** The staged arrival sorts cells by whether they stand in ink, and
            // here that answer moves every frame — so a staged field would restage itself
            // under the circle for as long as the reveal lasted. The field arrives in its own
            // baked random order and the circle starts from there.
            parameter("sweep", 0.0)
            parameter("stage", 0.45)
            parameter("delay", 0.12)
            parameter("jitter", 0.0)
            parameter("arrived", arrived)
            parameter("ramp", (pop.toDouble() / (reveal + pop).coerceAtLeast(1)).coerceIn(0.01, 1.0))
        }

        // ---- composing ---------------------------------------------------------------- //
        //
        // Composed at twice the size: `contentScale` doubles the real buffer behind the same
        // 1920x1080 coordinates, so the field lays out exactly as before and nothing it draws
        // has to know, while the edges resolve at 3840x2160 and a still comes out at that size.
        // A field of this grain is all edge, so it is the one thing worth the memory.
        val canvas = renderTarget(WIDE.toInt(), HIGH.toInt(), contentScale = DETAIL) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }
        canvas.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        canvas.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR

        if (Env.boolean("CIRCLE_RECORD")) {
            val file = "video/circle-mosaic.mp4"
            println("filming to $file")
            // `contentScale = 1.0 / scale` so a clip comes out at the canvas's own size and not
            // the window's — the deck's own trick.
            extend(ScreenRecorder().apply {
                outputFile = file
                frameRate = Env["CIRCLE_FPS"]?.toIntOrNull() ?: FPS
                contentScale = 1.0 / scale
                maximumDuration = Env["CIRCLE_DURATION"]?.toDoubleOrNull() ?: seconds(period)
            })
        }

        val stills = Env.boolean("CIRCLE_STILLS")
        val at = Env["CIRCLE_AT"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.map { frames(it) }?.sorted().orEmpty()
        var taken = 0

        var plainly = Env.boolean("CIRCLE_DEBUG")
        var frame = 0
        var start = 0
        var held = false
        var nudge = 0
        var saveNext = false

        keyboard.keyDown.listen { event ->
            when (event.name) {
                "escape" -> application.exit()
                "r" -> start = frame
                "b" -> plainly = !plainly
                "p" -> held = !held
                "." -> nudge++
                "," -> nudge--
                "s" -> saveNext = true
                // A rebuild rather than a scale: the cells *are* the field, so a bigger mark is
                // a different packing of the same picture rather than the same picture drawn
                // larger.
                "j" -> {
                    val oneSize = markFinest >= markSize
                    markSize = (markSize / STEP).coerceIn(8.0, 256.0)
                    markFinest = if (oneSize) markSize else (markFinest / STEP).coerceIn(4.0, markSize)
                    stand()
                }
                "k" -> {
                    val oneSize = markFinest >= markSize
                    markSize = (markSize * STEP).coerceIn(8.0, 256.0)
                    markFinest = if (oneSize) markSize else (markFinest * STEP).coerceIn(4.0, markSize)
                    stand()
                }
            }
        }

        extend {
            // Frames, counted here and nowhere else — the note under demo01 in CLAUDE.md.
            // A key handler moves `start` and `nudge` and takes no timestamp, so there is
            // nothing to drift when the recorder swaps the clock out from under the draw loop.
            if (!held) frame++
            frame += nudge
            nudge = 0
            val since = (frame - start).coerceAtLeast(0)

            val t = since.mod(period).toDouble() / period
            // Up and back over the period, so the loop closes with nothing to cut; a sawtooth
            // is the same ramp not brought home.
            val u = if (returns) 0.5 - 0.5 * cos(2.0 * PI * t) else t
            paint(drawer, u * reach)

            val arrived = ((since).toDouble() / (reveal + pop).coerceAtLeast(1)).coerceIn(0.0, 1.0)

            drawer.isolatedWithTarget(canvas) {
                drawer.ortho(canvas)
                drawer.clear(paper)
                drawer.stroke = null
                drawer.shadeStyle = null
                if (plainly) {
                    // The plate itself, put back to the pane's proportion — it is rendered on
                    // the cell's aspect and would otherwise be stretched by exactly that factor.
                    drawer.fill = ColorRGBa.WHITE
                    drawer.image(
                        mask,
                        Rectangle(0.0, 0.0, plate.width.toDouble(), plate.height.toDouble()),
                        Rectangle(0.0, 0.0, WIDE, HIGH)
                    )
                } else {
                    drawer.fill = ink
                    drawer.shadeStyle = standing(arrived)
                    drawer.vertexBuffer(cells, DrawPrimitive.TRIANGLES)
                    drawer.shadeStyle = null
                }
            }

            // Fitted into the window rather than stretched to it, so a window of another shape
            // letterboxes instead of distorting the field.
            val window = Rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
            val fit = min(window.width / WIDE, window.height / HIGH)
            val shown = Rectangle.fromCenter(window.center, WIDE * fit, HIGH * fit)
            if (fit < 1.0) canvas.colorBuffer(0).generateMipmaps()
            drawer.clear(ColorRGBa.BLACK)
            drawer.image(canvas.colorBuffer(0), shown.corner.x, shown.corner.y, shown.width, shown.height)

            val timed = at.isNotEmpty() && taken < at.size && since >= at[taken]
            if (saveNext || timed || (stills && at.isEmpty() && since >= period / 2)) {
                val file = if (timed) File("screenshots/circle-mosaic-%.1fs.png".format(seconds(at[taken])))
                else File("screenshots/circle-mosaic.png")
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

/** What one press of j or k is worth. */
private const val STEP = 1.18

/**
 * Pane pixels to one texel of the plate. The plate only has to resolve a *cell*, since that is
 * the only question asked of it.
 */
private const val TEXEL = 4.0

/** Real pixels to one canvas pixel: composed at 1920x1080 and rendered at twice it. */
private const val DETAIL = 2.0

private const val WIDE = 1920.0
private const val HIGH = 1080.0

/** Levels in a full mip chain — down to a single texel, or a coarse cell reads a blur. */
private fun mipLevels(width: Int, height: Int): Int =
    floor(log2(max(width, height).toDouble())).toInt() + 1
