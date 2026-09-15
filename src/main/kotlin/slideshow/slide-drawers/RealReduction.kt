package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.draw.loadImage
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import kotlin.math.min
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.frames
import slideshow.smoothstep
import java.io.File

/** One measure: what it is drawn as, its caption, and which column it stands in — left, middle or right. */
class Measure(val kind: Kind, val caption: String, val column: Int) {
    /** [file] is the illustration's name in the slide's folder, without `.svg`. */
    enum class Kind(val file: String) { CRANE("crane"), TRUCK("truc"), VAN("van"), CAR("car"), PANEL("green-power"), BATTERY("battery") }
}

/**
 * Not compensation but reduction: a certificate alone, then the measures either side of it,
 * then the certificate gone and the measures closing up into its place. Three states.
 *
 *     0  the certificate, standing in the middle of the pane
 *     1  the measures arrive in the outer columns, growing from their own middles
 *     2  the certificate shrinks to nothing; the outer columns travel inward and the middle
 *        column's measures grow where it stood
 *
 * **The machines are illustrations off [illustrations]**, one svg a kind, white on transparent.
 * Two of them are pictures wrapped in an svg rather than vectors, which OPENRNDR's svg loader
 * cannot draw, so every one is rasterised once by `rsvg-convert` into `build/illustrations` and
 * drawn as an image — one path for both kinds, and a tint for the fade. Each is fitted into the
 * same box and stood on its foot, so a column reads as a set whatever their proportions. A kind
 * with no file, or no converter on the machine, falls back to a drawn pictogram in rectangles,
 * lines and circles. The certificate is type on a
 * white sheet; the mark at its foot is set as type until the WN mark arrives as an svg.
 *
 * Columns are a pure function of `on(2)`: the outer columns' x travels from the wide stance to
 * the closed one on the deck's ease, the middle column grows on the same number.
 */
class RealReduction(
    private val title: String,
    private val certificate: List<String> = listOf("CERTIFICAAT", "Compensatie CO₂ emissies"),
    private val issuer: String = "WILLY NAESSENS",
    private val measures: List<Measure>,
    private val illustrations: File? = File("data/illustrations"),
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    private val accent: ColorRGBa = ColorRGBa.fromHex("FF0000"),
    private val blue: ColorRGBa = ColorRGBa.fromHex("3D5AE0"),
    private val paper: ColorRGBa = ColorRGBa.fromHex("EDEDED"),
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val stepFrames: Int = frames(0.9),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "Reduction, not compensation"
    override val steps get() = 3
    override fun stepName(step: Int): String? = when (step) { 1 -> "the measures"; 2 -> "no certificate"; else -> null }

    private lateinit var bold: FontImageMap
    private lateinit var text: FontImageMap

    override fun load(program: Program) {
        bold = program.loadFont(boldPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        text = program.loadFont(textPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        pictures = measures.map { it.kind }.distinct().mapNotNull { kind -> raster(kind)?.let { kind to it } }.toMap()
    }

    private var pictures: Map<Measure.Kind, ColorBuffer> = emptyMap()

    /** The white illustration taken to [ink] and faded with its measure. */
    private val tintStyle = shadeStyle { fragmentTransform = "x_fill *= p_tint;" }

    /** The kind's svg as a png, converted once and again only when the svg is newer. */
    private fun raster(kind: Measure.Kind): ColorBuffer? {
        val svg = illustrations?.let { File(it, kind.file + ".svg") }?.takeIf { it.isFile } ?: run {
            println("RealReduction: no ${kind.file}.svg in ${illustrations?.path} — drawn as a pictogram")
            return null
        }
        val png = File("build/illustrations/${kind.file}.png")
        if (!png.isFile || svg.lastModified() > png.lastModified()) {
            png.parentFile.mkdirs()
            val code = listOf("rsvg-convert", "/opt/homebrew/bin/rsvg-convert", "/usr/local/bin/rsvg-convert").firstNotNullOfOrNull { tool ->
                runCatching { ProcessBuilder(tool, "-w", RASTER.toString(), "-a", svg.path, "-o", png.path).inheritIO().start().waitFor() }.getOrNull()
            }
            if (code != 0) {
                println("RealReduction: could not convert ${svg.path} (is rsvg-convert installed?) — drawn as a pictogram")
                return null
            }
        }
        return runCatching { loadImage(png) }.getOrNull()?.apply {
            generateMipmaps()
            filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        }
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val w = stage.width
        val h = stage.height
        drawer.stroke = null
        drawer.fill = ink
        drawer.setLine(title, bold, Vector2(w / 2.0, h * TITLE_Y), h * TITLE, SIZE, align = 0.5)

        val opening = stage.step == 0 && stage.position < 1e-6
        val arrived = if (opening) smoothstep(stage.since(0, stepFrames)) else 1.0
        val measured = stage.on(1)
        val closed = stage.on(2)

        // The certificate: a sheet with its lines, shrinking to nothing as the click closes.
        val sheetScale = arrived * (1.0 - smoothstep(closed))
        if (sheetScale > 0.0) {
            val sheet = Rectangle(w / 2.0 - w * SHEET_W / 2.0 * sheetScale, h * SHEET_Y + h * SHEET_H / 2.0 * (1.0 - sheetScale), w * SHEET_W * sheetScale, h * SHEET_H * sheetScale)
            drawer.fill = paper
            drawer.rectangle(sheet)
            drawer.isolated {
                drawer.translate(sheet.center)
                drawer.scale(sheetScale)
                drawer.translate(-Vector2(w / 2.0, h * (SHEET_Y + SHEET_H / 2.0)))
                drawer.fill = accent
                drawer.setLine(certificate.getOrElse(0) { "" }, bold, Vector2(w / 2.0, h * (SHEET_Y + 0.085)), h * HEAD, SIZE, align = 0.5)
                drawer.fill = blue
                val lines = bold.wrapped(certificate.getOrElse(1) { "" }, (w * SHEET_W * 0.86) * SIZE / (h * BODY))
                val top = h * (SHEET_Y + SHEET_H * 0.5) - (lines.size - 1) * h * BODY_LEAD / 2.0
                lines.forEachIndexed { i, l -> drawer.setLine(l, bold, Vector2(w / 2.0, top + i * h * BODY_LEAD), h * BODY, SIZE, align = 0.5) }
                // PLACEHOLDER for the WN mark: its name in the red until the svg arrives.
                drawer.fill = accent
                drawer.setLine(issuer, bold, Vector2(w / 2.0, h * (SHEET_Y + SHEET_H - 0.05)), h * ISSUER, SIZE, align = 0.5)
            }
        }

        // The measures, by column: the outer two travel inward as the middle grows.
        val outerX = doubleArrayOf(w * (OUTER + (CLOSED - OUTER) * closed), w / 2.0, w * (1.0 - OUTER - (CLOSED - OUTER) * closed))
        val unit = h * UNIT
        val byColumn = measures.groupBy { it.column }
        byColumn.forEach { (column, list) ->
            val x = outerX.getOrElse(column) { w / 2.0 }
            val shown = if (column == 1) smoothstep(closed) else smoothstep(measured)
            if (shown <= 0.0) return@forEach
            list.forEachIndexed { i, m ->
                val grown = staggered(shown, i, list.size, 0.25)
                if (grown <= 0.0) return@forEachIndexed
                val y = h * (ROW_TOP + (ROW_BOTTOM - ROW_TOP) * (if (list.size == 1) 0.5 else i.toDouble() / (list.size - 1)))
                drawer.isolated {
                    drawer.translate(x, y)
                    drawer.scale(grown)
                    val picture = pictures[m.kind]
                    if (picture != null) {
                        // fitted into the box and stood on its foot, just above the caption
                        val fit = min(unit * PICTURE_W / picture.width, unit * PICTURE_H / picture.height)
                        val pw = picture.width * fit
                        val ph = picture.height * fit
                        drawer.shadeStyle = tintStyle.apply { parameter("tint", ink.opacify(grown)) }
                        drawer.image(picture, -pw / 2.0, unit * 0.5 - ph, pw, ph)
                    } else pictogram(drawer, m.kind, unit, ink.opacify(grown))
                }
                drawer.fill = ink.opacify(grown)
                drawer.setLine(m.caption, text, Vector2(x, y + unit * 0.62 + h * CAPTION_GAP), h * CAPTION, SIZE, align = 0.5)
            }
        }
    }

    /** One measure in a box [unit] wide, centred on the origin, outlined in [colour]. */
    private fun pictogram(drawer: Drawer, kind: Measure.Kind, unit: Double, colour: ColorRGBa) {
        val u = unit / 2.0
        drawer.stroke = colour
        drawer.strokeWeight = LINE
        drawer.fill = null
        fun box(x: Double, y: Double, wd: Double, ht: Double) = drawer.rectangle(Rectangle(x * u, y * u, wd * u, ht * u))
        fun wheel(x: Double, y: Double, r: Double) = drawer.circle(x * u, y * u, r * u)
        fun seg(a: Double, b: Double, c: Double, d: Double) = drawer.lineSegment(Vector2(a * u, b * u), Vector2(c * u, d * u))
        when (kind) {
            Measure.Kind.CRANE -> {
                box(-0.9, 0.3, 0.7, 0.35)                   // the carrier
                wheel(-0.7, 0.72, 0.1); wheel(-0.4, 0.72, 0.1)
                seg(-0.55, 0.3, 0.45, -0.85)                // the boom
                seg(-0.65, 0.3, 0.35, -0.85)
                seg(-0.55, 0.3, -0.65, 0.3)
                seg(0.45, -0.85, 0.35, -0.85)
                seg(0.4, -0.85, 0.75, -0.3)                 // the line and the hook
                seg(0.75, -0.3, 0.75, 0.05)
                seg(0.68, 0.05, 0.82, 0.05)
            }
            Measure.Kind.TRUCK -> {
                box(-0.95, -0.35, 1.3, 0.7)                 // the trailer
                box(0.42, -0.15, 0.5, 0.5)                  // the cab
                seg(0.62, -0.15, 0.62, 0.35)
                wheel(-0.65, 0.5, 0.13); wheel(-0.35, 0.5, 0.13); wheel(0.6, 0.5, 0.13)
            }
            Measure.Kind.VAN -> {
                box(-0.8, -0.3, 1.6, 0.6)
                seg(-0.8, -0.05, 0.8, -0.05)
                wheel(-0.45, 0.42, 0.14); wheel(0.45, 0.42, 0.14)
            }
            Measure.Kind.CAR -> {
                box(-0.8, 0.0, 1.6, 0.3)
                seg(-0.5, 0.0, -0.3, -0.3); seg(-0.3, -0.3, 0.3, -0.3); seg(0.3, -0.3, 0.55, 0.0)
                wheel(-0.45, 0.4, 0.14); wheel(0.45, 0.4, 0.14)
            }
            Measure.Kind.PANEL -> {
                box(-0.9, -0.45, 1.8, 0.9)
                for (c in 0 until 6) for (r in 0 until 3) box(-0.85 + c * 0.3, -0.4 + r * 0.3, 0.26, 0.26)
            }
            Measure.Kind.BATTERY -> {
                box(-0.55, -0.5, 1.1, 0.9)
                box(-0.4, -0.65, 0.2, 0.15); box(0.2, -0.65, 0.2, 0.15)
                seg(-0.75, 0.25, -0.55, 0.25); box(-0.85, 0.18, 0.1, 0.14)
                drawer.contour(org.openrndr.shape.ShapeContour.fromPoints(listOf(
                    Vector2(0.08 * u, -0.35 * u), Vector2(-0.14 * u, 0.02 * u), Vector2(0.02 * u, 0.02 * u),
                    Vector2(-0.08 * u, 0.3 * u), Vector2(0.16 * u, -0.08 * u), Vector2(0.0, -0.08 * u)
                ), closed = true))
            }
        }
        drawer.stroke = null
    }

    private companion object {
        const val SIZE = 200.0
        const val TITLE = 0.036
        const val TITLE_Y = 0.06
        const val SHEET_W = 0.28
        const val SHEET_H = 0.68
        const val SHEET_Y = 0.16
        const val HEAD = 0.04
        const val BODY = 0.036
        const val BODY_LEAD = 0.046
        const val ISSUER = 0.03
        const val UNIT = 0.28
        const val OUTER = 0.16
        const val CLOSED = 0.2
        const val ROW_TOP = 0.31
        const val ROW_BOTTOM = 0.71
        const val CAPTION = 0.024
        const val CAPTION_GAP = 0.02
        const val LINE = 2.0
        /** The box an illustration is fitted into, against [UNIT]. */
        const val PICTURE_W = 1.1
        const val PICTURE_H = 0.7
        /** Pixels across an illustration is rasterised at: twice the most it is drawn at. */
        const val RASTER = 800
    }
}
