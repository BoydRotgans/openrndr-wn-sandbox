// No `package` declaration, for `CityMapSlide`'s reason: the photographs are shown through
// catalogue elements, and the marks, their packing and their buffer (`loadMarkTemplates`,
// `ObjectTemplate`) live in the default package, which a named one cannot import from.

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.VertexElementType
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.draw.loadImage
import org.openrndr.draw.shadeStyle
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.extra.color.colormatrix.tint
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.Vector4
import org.openrndr.shape.Rectangle
import slideshow.Breathe
import slideshow.Frame
import slideshow.Palette
import slideshow.Slide
import slideshow.Stage
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.advanceWithSubscripts
import slideshow.drawers.setLine
import slideshow.frames
import slideshow.linear
import slideshow.smoothstep
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A project highlight: the storyline of a chapter brought down to one real building, just before
 * the chapter's takeaway. The project's photographs, a click each, and a box cut out of the pane's
 * corner naming it — the name in bold, where it stands under it.
 *
 * **The photograph is shown as it is, not duotoned.** The guide keeps photographs inside a shape
 * and in a piece's colour where the piece is the subject (`CaseStudy`); here the building is the
 * subject, and a hall of columns or a roof of solar panels is what is being pointed at.
 *
 * **Each photo arrives through the catalogue and then stands whole.** Every photo has its own
 * bin packing of the elements in [marks] (`data/svg/subset_svg`) over the pane, each element
 * stretched to fill its cell so nothing is black but the joints and the elements' own notches,
 * and the photo shows through them in pane pixels — nailed to the wall, each element a window
 * onto the part of it behind. The first photo builds up out of black an element at a time; the
 * elements then stand a moment and **dissolve one by one**, each cell filling with the whole of
 * its picture, until the photo stands entire. A click runs the same thing in reverse and on: the
 * whole photo breaks back into its elements, they go as a front crosses the pane while the next
 * photo's own packing grows in with the new picture in it, and those dissolve in turn.
 *
 * **The label is a hole in the grid, not a box laid over it.** The packing leaves out
 * [LABEL_COLUMNS] by one coarse cells in the bottom left corner, so the elements, the dissolved
 * photo and their joints all stop at its edge, and the name is set in the black that is left.
 *
 * The effect itself is [PhotoMosaic], which the case studies draw through too, so the two are one
 * implementation; this file is the highlight's photos, its timing on the deck and its label. The whole slide is a function of the click and of the frame
 * count while it opens — the click is long because it holds the handover and the dissolve, and
 * it is counted, so it runs on `linear` of the position rather than on the deck's ease.
 *
 * **Every photograph of the project, ending on the one that is the highlight.** The slide is
 * handed the project's folder, and the images in it are its states, a click each: the rest in name
 * order, then the [lead] last, so the mini show lands on the picture the chapter is about. A photo
 * dropped into the folder is in the talk on the next start. [everyPhoto] false shows the lead alone. The folder is listed in the constructor, not
 * in `load`, because the step count has to be known before `load` runs (the case study's trap).
 *
 * The picture breathes behind it all: a push of a few percent toward [focus] and back that never
 * repeats. With no marks to be had the photos are drawn whole and crossfade instead.
 */
class ProjectHighlight(
    /** The project's folder: every image in it is a state. None leaves the pane black with the label. */
    private val folder: File,
    /** The photo it lands on, by file name inside [folder]: the rest come first in name order, and this one last. */
    private val lead: String? = null,
    /** The project, set bold in the label. */
    private val project: String,
    /** One line under the name: where it stands and what it is — true of every photo. */
    private val line: String,
    private val boldPath: String,
    private val textPath: String,
    /** Where in the lead photograph the crop and the drift centre, as shares of its width and height; the others centre. */
    private val focus: Vector2 = Vector2(0.5, 0.5),
    /** The elements the photos are seen through: a folder of svgs, one to a mark, or a sheet. */
    private val marks: File = File("data/svg/subset_svg"),
    /** The pane everything is laid out for; `draw` fits it into whatever pane it gets. */
    private val pane: Vector2 = Vector2(1920.0, 1080.0),
    /**
     * How an element gives way to its whole cell in the dissolve: true fills the cell in one frame
     * on its turn — a count, one at a time, the house's instant removal — and false crossfades
     * it in over its own share of the dissolve.
     */
    private val instant: Boolean = true,
    /**
     * Every photo in [folder], a click each, ending on the [lead]; false shows the lead alone.
     */
    private val everyPhoto: Boolean = true
) : Slide() {
    override val name = "Highlight"
    override val background: ColorRGBa = Palette.onBlack.paper
    override val settle get() = frames(PhotoMosaic.OPENING)

    /** The photographs, the lead last. */
    private val photos: List<File> = (folder.listFiles() ?: emptyArray())
        .filter { it.isFile && it.extension.lowercase() in IMAGES }
        .sortedWith(compareBy({ it.name == lead }, { it.name.lowercase() }))
        .let { all -> if (everyPhoto || lead == null) all else all.filter { it.name == lead }.ifEmpty { all.take(1) } }
        .also { if (it.isEmpty()) println("project highlight: no photos in ${folder.path}") }

    override val steps get() = photos.size.coerceAtLeast(1)

    /** A click is the break-up, the handover, a hold and the dissolve, end to end. */
    override val stepFrames get() = frames(PhotoMosaic.CLICK)

    override fun stepName(step: Int) = photos.getOrNull(step)?.nameWithoutExtension

    private val palette = Palette.onBlack
    private val breathe = Breathe(DRIFT_PERIOD)

    private lateinit var bold: FontImageMap
    private lateinit var text: FontImageMap
    private var pictures: List<MosaicPicture> = emptyList()

    /** The effect, shared with the case studies; empty marks draw the photos whole instead. */
    private var mosaic: PhotoMosaic? = null

    /** The hole the label stands in: the mosaic's, or the same two coarse cells without one. */
    private val labelBox: Rectangle
        get() = mosaic?.hole ?: (pane.x / COLUMNS).let { w ->
            val h = pane.y / (pane.y / (w / PhotoMosaic.SHAPE)).roundToInt().coerceAtLeast(2)
            Rectangle(0.0, pane.y - h, w * LABEL_COLUMNS, h)
        }

    override fun load(program: Program) {
        bold = program.loadFont(boldPath, NAME_EM, TYPE_CHARACTERS, contentScale = 1.0)
        text = program.loadFont(textPath, TEXT_EM, TYPE_CHARACTERS, contentScale = 1.0)
        pictures = photos.map { MosaicPicture(loadPhoto(program.drawer, it), focusOf(it)) }
        val templates = loadMarkTemplates(marks)
        if (templates.isEmpty()) println("project highlight: no marks at ${marks.path}, photos drawn whole")
        else mosaic = PhotoMosaic(Rectangle(0.0, 0.0, pane.x, pane.y), templates, COLUMNS,
            holeCells = LABEL_COLUMNS, instant = instant, seed = SEED).also { it.prepare(photos.indices) }
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val w = stage.width
        val h = stage.height
        // The opening runs only on the first state, so stepping back into the slide finds it whole.
        val opened = stage.step > 0 || stage.position > 0.0

        drawer.isolated {
            drawer.scale(w / pane.x, h / pane.y)
            if (pictures.isNotEmpty()) {
                val zoom = 1.0 + DRIFT * breathe.at(stage.frame)
                val at = stage.position.coerceIn(0.0, (pictures.size - 1).toDouble())
                val from = at.toInt()
                val s = linear(at - from) * PhotoMosaic.CLICK          // seconds into the click, evenly
                val next = (from + 1).takeIf { s > 0.0 && it < pictures.size }
                val m = mosaic
                if (m != null) {
                    val opening = if (opened) null else stage.frame / frames(1.0).toDouble()
                    m.draw(drawer, Vector2(w / pane.x, h / pane.y), zoom, { pictures[it] }, from, next, s, opening)
                } else {
                    photo(drawer, from, w, h, zoom, 1.0)
                    if (next != null) photo(drawer, next, w, h, zoom, (s / PhotoMosaic.CLICK).coerceIn(0.0, 1.0))
                }
            }
            label(drawer, stage, opened)
        }
    }

    /**
     * The label, in the hole the packing left for it: the name over its line, the pair centred down
     * the box and ranged left on the pane's margin. Either is set smaller only if it would not fit
     * the box's measure. No kicker and no count of the photos: the name is the whole of it.
     */
    private fun label(drawer: Drawer, stage: Stage, opened: Boolean) {
        val words = if (opened) 1.0 else smoothstep(stage.since(frames(PhotoMosaic.BUILD * 0.5), frames(WORDS_IN)))
        if (words <= 0.0) return
        val box = labelBox
        val inset = pane.x * Frame.MARGIN
        val measure = box.width - 2.0 * inset
        fun fit(piece: String, face: FontImageMap, size: Double, em: Double) =
            minOf(size, measure / (face.advanceWithSubscripts(piece) / em).coerceAtLeast(1e-6))
        val nameSize = fit(project, bold, pane.y * NAME, NAME_EM)
        val lineSize = fit(line, text, pane.y * TEXT, TEXT_EM)

        val block = nameSize * ASCENT + lineSize * NAME_LEAD + lineSize * DESCENT
        val rise = (1.0 - words) * nameSize * RISE
        var baseline = box.y + (box.height - block) / 2.0 + nameSize * ASCENT + rise
        val left = box.x + inset
        drawer.stroke = null
        drawer.fill = palette.ink.opacify(words)
        drawer.setLine(project, bold, Vector2(left, baseline), nameSize, NAME_EM)
        baseline += lineSize * NAME_LEAD
        drawer.fill = QUIET.opacify(words)
        drawer.setLine(line, text, Vector2(left, baseline), lineSize, TEXT_EM)
    }

    private fun focusOf(file: File) = if (file.name == lead) focus else Vector2(0.5, 0.5)

    /** Photo [i] covering the pane, [zoom] times in, at [opacity]: the fallback with no marks. */
    private fun photo(drawer: Drawer, i: Int, w: Double, h: Double, zoom: Double, opacity: Double) {
        val pic = pictures[i]
        drawer.isolated {
            drawer.drawStyle.colorMatrix = tint(ColorRGBa.WHITE.opacify(opacity))
            drawer.image(pic.image, PhotoMosaic.crop(pic.image, w / h, zoom, pic.focus), Rectangle(0.0, 0.0, pane.x, pane.y))
        }
    }

    private companion object {
        /** How many coarse cells across, the packing's seed, and how many cells the label's hole takes. */
        const val COLUMNS = 6
        const val SEED = 7
        const val LABEL_COLUMNS = 2

        /** Seconds the label's words take to come up. */
        const val WORDS_IN = 0.6

        /** What counts as a photograph in a project's folder. */
        val IMAGES = setOf("jpg", "jpeg", "png")

        /** How far the drift pushes in at its deepest, and the period of its slower wave. */
        const val DRIFT = 0.05
        const val DRIFT_PERIOD = 50.0

        /** Sizes as shares of the pane's height, and the ems the faces load at. */
        const val NAME = 0.046
        const val TEXT = 0.024
        const val NAME_EM = 64.0
        const val TEXT_EM = 32.0

        /** Name baseline to line baseline, as a multiple of the line's size. */
        const val NAME_LEAD = 1.6

        /** Cap height and descent, as shares of the size, for centring the stack in its box. */
        const val ASCENT = 0.75
        const val DESCENT = 0.3

        /** How far below its place the lettering rises from, as a share of the name's size. */
        const val RISE = 0.35

        /** The line under the name: `PixelMap`'s address grey. */
        val QUIET: ColorRGBa = ColorRGBa.fromHex("9A9A9A")
    }
}
