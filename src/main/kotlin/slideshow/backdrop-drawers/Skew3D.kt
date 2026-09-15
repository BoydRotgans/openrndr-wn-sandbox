package slideshow.backdrops

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.ColorType
import org.openrndr.draw.Drawer
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Vector2
import slideshow.Backdrop
import slideshow.Stage
import slideshow.frames
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A tryout: a grid of folded panels in black and white, with the titles standing out of it and
 * read in the long shadows they throw as a low sun passes along the wall.
 *
 * **A panel is a rectangle folded down its middle, and it never moves.** The left half stands where
 * it is and the right half is dropped, the two joined by a diagonal top and bottom: a cell of the
 * reference, and a precast face turned at a crease. Each row is folded to its own depth, [ripple] of
 * a wave down the wall, so the grid is never one angle. A panel is solved per pixel against its own
 * outline, so it is exact at any size.
 *
 * **The letters are the same panels, standing proud of the wall.** Seen straight on a letter's panel
 * is indistinguishable from the ground's; only a light from the side gives it away, by the shadow it
 * throws across the panels behind it. An earlier version folded the panels themselves to set the type
 * and it read as the objects turning; now the objects hold still and only the light moves.
 *
 * **The shadows are long, they swing, and they sweep along the wall.** Each point of the wall has its
 * own sunrise and sunset in turn: the shadows there lengthen to [shadowLength] cells, swing from the
 * light standing at [sunFrom] to [sunTo], and shorten to nothing again. The point's moment in the
 * turn is set back by how far along the text it is, by [spread], so the reveal travels across the
 * words from left to right rather than landing everywhere at once. Along the *text*, not the wall:
 * the titles take the left part of the wall, and a sweep measured across all of it spent most of
 * each turn passing over panels with nothing to throw a shadow. At the seam of the loop every point
 * is between sunset and sunrise, nothing throws a shadow, and letter and ground are identical to the
 * pixel — which is where the text is swapped, one a turn.
 *
 * **A shadow is traced, not blurred.** From each point the line toward the sun is walked cell by cell
 * through the grid for as far as the shadow reaches, and the point is in shadow if a letter's cell
 * stands anywhere along it. So a shadow is exactly the letter swept along the light, with edges that
 * are straight lines from the cells' corners, and it stays two tones.
 *
 * **[alignLight] lays the light along the fold.** The sun then stands exactly on the line of the
 * panels' own diagonal, so every shadow runs parallel to the creases it falls across and never swings:
 * one shape, one angle, and the only thing that moves is how far the shadows reach. The angle is read
 * off the cell in force, so it stays on the diagonal as `Q`/`A` and `W`/`S` reshape the grid. It is the
 * simple variant's rule, where [sunFrom] and [sunTo] are set aside.
 *
 * [masks] circle one a turn; `M` skips one; `I` inverts; `Q`/`A` and `W`/`S` change the grid.
 */
class Skew3D(
    override val name: String = "Skew 3D",
    /** The texts, dark ink on a light ground, one a turn. */
    private val masks: List<File> = emptyList(),
    /** Read the texts as light ink on a dark ground instead. */
    private val invert: Boolean = false,
    /** Rows of panels up the wall. */
    private val rows: Int = 56,
    /** A cell's width over its height — the reference's panels are a little over half as wide as their pitch. */
    private val aspect: Double = 0.58,
    /** How much of a cell's height a panel takes; the rest is the room its right half drops into. */
    private val fill: Double = 0.62,
    /** How far the right half is dropped, as a share of that room. */
    private val fold: Double = 1.0,
    /** How much each row's fold follows a wave down the wall, 0 for every row alike. */
    private val ripple: Double = 0.35,
    /** Rows a wave spans. */
    private val wavelength: Double = 18.0,
    /** Seconds a text takes. */
    period: Double = 12.0,
    /** The longest a shadow gets, in cell heights. */
    private val shadowLength: Double = 6.0,
    /** How far behind the left edge the right edge's sunrise comes, in turns of its own passage. */
    private val spread: Double = 1.0,
    /** Where the light stands as each point's sunrise begins and its sunset ends, degrees anticlockwise from the right. */
    private val sunFrom: Double = 165.0,
    private val sunTo: Double = 115.0,
    /** How the shadows grow and shrink over a point's passage: the power of the arc, higher lingering shorter. */
    private val hold: Double = 1.0,
    /** Lay the light along the panels' fold diagonal, fixed, instead of swinging it from [sunFrom] to [sunTo]. */
    private val alignLight: Boolean = false,
    private val ink: ColorRGBa = ColorRGBa.fromHex("FFFFFF"),
    private val ground: ColorRGBa = ColorRGBa.fromHex("000000")
) : Backdrop() {

    override val loop = frames(period)
    override val background: ColorRGBa get() = ground

    private lateinit var none: ColorBuffer
    private var pictures = emptyList<BufferedImage>()
    private var shownMask = 0
    private var grid: ColorBuffer? = null
    private var gridFor = emptyList<Any>()

    /** Where the text in force starts and ends across the wall, in pixels, found with the grid. */
    private var span = Vector2(0.0, 1.0)

    private var rowCount = rows
    private var columnCount = 0
    private var invertNow = false

    override fun load(program: Program) {
        none = colorBuffer(1, 1, type = ColorType.UINT8)
        pictures = masks.mapNotNull { file ->
            runCatching { ImageIO.read(file) }.getOrNull()
                .also { if (it == null) println("$name: no text at $file, skipping it") }
        }
    }

    override fun key(name: String): Boolean {
        when (name) {
            "q" -> columnCount = (columnCount + 1).coerceAtMost(MAX_COLUMNS)
            "a" -> columnCount = (columnCount - 1).coerceAtLeast(1)
            "w" -> rowCount = (rowCount + 1).coerceAtMost(MAX_ROWS)
            "s" -> rowCount = (rowCount - 1).coerceAtLeast(1)
            "m" -> {
                if (pictures.size < 2) return false
                shownMask = (shownMask + 1) % pictures.size
                println("${this.name}: text moved on by $shownMask of ${pictures.size}")
                return true
            }
            "i" -> {
                invertNow = !invertNow
                println("${this.name}: ${if (invertNow) "inverted" else "upright"}")
                return true
            }
            else -> return false
        }
        println("${this.name}: $columnCount columns x $rowCount rows")
        return true
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val cell = stage.height / rowCount
        if (columnCount == 0) columnCount = max(1, (stage.width / (cell * aspect)).roundToInt())
        val cellWidth = stage.width / columnCount

        val text = if (pictures.size > 1) (stage.cycle + shownMask) % pictures.size else 0
        val cells = pictures.getOrNull(text)?.let { cellsOf(it, text, stage.width, stage.height, rowCount, columnCount) }

        drawer.stroke = null
        drawer.shadeStyle = shadeStyle {
            fragmentPreamble = PREAMBLE
            fragmentTransform = TRANSFORM
            parameter("size", Vector2(stage.width, stage.height))
            parameter("cell", Vector2(cellWidth, cell))
            parameter("fill", fill.coerceIn(0.05, 1.0))
            parameter("fold", fold)
            parameter("ripple", ripple.coerceIn(0.0, 1.0))
            parameter("wavelength", wavelength)
            parameter("phase", stage.loop)
            parameter("spread", max(0.0, spread))
            parameter("span", span)
            parameter("reach", shadowLength)
            // Along the fold: the direction up the panels' diagonal toward the upper left, from the
            // drop across half a cell's width — the same line the creases are drawn on.
            val alongFold = PI - atan(cell * (1.0 - fill.coerceIn(0.05, 1.0)) * fold / (cellWidth / 2.0))
            parameter("sunFrom", if (alignLight) alongFold else Math.toRadians(sunFrom))
            parameter("sunTo", if (alignLight) alongFold else Math.toRadians(sunTo))
            parameter("hold", hold)
            parameter("masked", if (cells != null) 1.0 else 0.0)
            parameter("mask", cells ?: none)
            parameter("grid", Vector2((cells ?: none).width.toDouble(), (cells ?: none).height.toDouble()))
            parameter("ink", ink)
            parameter("ground", ground)
            parameter("inverted", if (invertNow) 1.0 else 0.0)
        }
        drawer.rectangle(stage.bounds)
        drawer.shadeStyle = null
    }

    /** One texel a cell, 1 where the cell is mostly ink. Row 0 is the **bottom** row, as the shader counts. */
    private fun cellsOf(image: BufferedImage, which: Int, width: Double, height: Double, rows: Int, columns: Int): ColorBuffer {
        val key = listOf(which, width, height, rows, columns)
        grid?.let { if (gridFor == key) return it }
        grid?.destroy()

        val cell = height / rows
        val cellWidth = width / columns
        val iw = image.width
        val ih = image.height
        val pixels = image.getRGB(0, 0, iw, ih, null, 0, iw)
        val scale = min(width / iw, height / ih)
        val ox = (width - iw * scale) / 2.0
        val oy = (height - ih * scale) / 2.0

        val buffer = colorBuffer(columns, rows, type = ColorType.FLOAT32).apply {
            filter(MinifyingFilter.NEAREST, MagnifyingFilter.NEAREST)
        }
        val shadow = buffer.shadow
        var first = columns
        var last = -1
        for (r in 0 until rows) {
            val top = height - (r + 1) * cell
            val y0 = ((top - oy) / scale).toInt()
            val y1 = ((top + cell - oy) / scale).toInt()
            for (c in 0 until columns) {
                val left = c * cellWidth
                val x0 = ((left - ox) / scale).toInt()
                val x1 = ((left + cellWidth - ox) / scale).toInt()
                var inked = 0
                var seen = 0
                for (y in maxOf(y0, 0) until minOf(y1, ih)) for (x in maxOf(x0, 0) until minOf(x1, iw)) {
                    val p = pixels[y * iw + x]
                    val lum = (((p shr 16) and 0xff) + ((p shr 8) and 0xff) + (p and 0xff)) / 765.0
                    if ((lum < 0.5) != invert) inked++
                    seen++
                }
                val v = if (seen > 0 && inked * 2 > seen) 1.0 else 0.0
                if (v > 0.0) { first = minOf(first, c); last = maxOf(last, c) }
                // The shadow buffer counts rows from the top and texelFetch from the bottom.
                shadow[c, rows - 1 - r] = ColorRGBa(v, v, v, 1.0)
            }
        }
        shadow.upload()
        span = if (last >= first) Vector2(first * cellWidth, (last + 1) * cellWidth) else Vector2(0.0, width)

        grid = buffer
        gridFor = key
        return buffer
    }

    private companion object {
        const val MAX_ROWS = 300
        const val MAX_COLUMNS = 1000

        val PREAMBLE = """
            const int WALK = 128;

            bool inked(vec2 cell) {
                if (p_masked < 0.5) return false;
                if (cell.x < 0.0 || cell.y < 0.0 || cell.x >= p_grid.x || cell.y >= p_grid.y) return false;
                return texelFetch(p_mask, ivec2(cell), 0).r > 0.5;
            }

            // How much of a panel covers wall pixel px (y up): x is coverage, y whether it is a letter's.
            vec2 panel(vec2 px, float pixel) {
                vec2 id = floor(px / p_cell);
                vec2 u = px - id * p_cell;
                // y down the cell from its top, in pixels.
                float y = p_cell.y - u.y;

                float height = p_cell.y * p_fill;
                float room = p_cell.y - height;
                // Each row is folded to its own depth on a wave down the wall; the wave holds still.
                float wave = 0.5 + 0.5 * sin(6.2831853 * id.y / p_wavelength);
                float drop = room * p_fold * mix(1.0, wave, p_ripple);

                // Across the right half the band shears down: its top and bottom both fall by `drop`.
                float crease = 0.5 * p_cell.x;
                float t = clamp((u.x - crease) / crease, 0.0, 1.0);
                float top = t * drop;
                float slope = u.x > crease ? drop / crease : 0.0;
                float across = 1.0 / sqrt(1.0 + slope * slope);
                float inside = min(y - top, top + height - y) * across;
                return vec2(clamp(0.5 + inside / pixel, 0.0, 1.0), inked(id) ? 1.0 : 0.0);
            }

            // 1 where a letter stands between wall pixel px and the sun: the line toward it is walked
            // cell by cell for `len` pixels, and any letter's cell along it puts px in its shadow.
            float shadowAt(vec2 px, vec2 toward, float len) {
                if (len <= 0.0 || p_masked < 0.5) return 0.0;
                vec2 g = px / p_cell;
                vec2 d = toward * len / p_cell;
                vec2 cell = floor(g);
                vec2 stride = sign(d);
                vec2 inv = vec2(abs(d.x) > 1e-6 ? 1.0 / abs(d.x) : 1e9, abs(d.y) > 1e-6 ? 1.0 / abs(d.y) : 1e9);
                vec2 into = g - cell;
                vec2 next = vec2(d.x > 0.0 ? (1.0 - into.x) * inv.x : into.x * inv.x,
                                 d.y > 0.0 ? (1.0 - into.y) * inv.y : into.y * inv.y);
                for (int i = 0; i < WALK; i++) {
                    float t;
                    if (next.x < next.y) { t = next.x; next.x += inv.x; cell.x += stride.x; }
                    else { t = next.y; next.y += inv.y; cell.y += stride.y; }
                    if (t > 1.0) break;
                    if (inked(cell)) return 1.0;
                }
                return 0.0;
            }

            vec3 paint(vec2 px, float pixel) {
                vec2 p = panel(px, pixel);
                float shade = 0.0;
                if (p.y < 0.5 && p.x > 0.0) {
                    // This point's own passage of the sun, set back by how far along the wall it is:
                    // 0 before its sunrise and 1 after its sunset, so the seam has no shadow anywhere.
                    float along = clamp((px.x - p_span.x) / max(p_span.y - p_span.x, 1.0), 0.0, 1.0);
                    float passage = clamp(p_phase * (1.0 + p_spread) - p_spread * along, 0.0, 1.0);
                    float len = p_reach * p_cell.y * pow(sin(3.14159265 * passage), p_hold);
                    float sun = mix(p_sunFrom, p_sunTo, passage);
                    shade = shadowAt(px, vec2(cos(sun), sin(sun)), len);
                }
                vec3 face = mix(p_ink.rgb, p_ground.rgb, shade);
                return mix(p_ground.rgb, face, p.x);
            }
        """.trimIndent()

        val TRANSFORM = """
            vec2 px = vec2(c_boundsPosition.x, 1.0 - c_boundsPosition.y) * p_size;
            // Four samples on a rotated grid; each edge is antialiased over half a pixel inside them.
            vec3 tone = 0.25 * (paint(px + vec2(0.125, 0.375), 0.5)
                              + paint(px + vec2(-0.375, 0.125), 0.5)
                              + paint(px + vec2(0.375, -0.125), 0.5)
                              + paint(px + vec2(-0.125, -0.375), 0.5));
            if (p_inverted > 0.5) tone = 1.0 - tone;
            x_fill = vec4(tone, 1.0);
        """.trimIndent()
    }
}
