// ============================================================================ //
//  No `package` declaration: it stands on loadObjectSheet, in the default package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import org.openrndr.shape.contour
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.advanceOf
import slideshow.drawers.setLine
import slideshow.drawers.setToFit
import slideshow.frames
import slideshow.smoothstep
import java.io.File
import kotlin.math.floor
import kotlin.math.min

/** An element at the middle of a tree: which drawing on the sheet, in what. */
class Centre(val piece: Int, val colour: ColorRGBa)

/**
 * The domino effect: the decision tree, then four of them, then sixteen, then one word.
 *
 *     0  one tree, the pane's own — the fans of labels either side of an element
 *     1  four, two by two, each round its own element, the type scaled down with the cell
 *     2  sixteen, four by four, the labels now too small to read — which is the point
 *     3  the field gives way to DUURZAAM, set to the pane
 *
 * **Every tree is named by the cell it ends in**, `(R, C)` on the four-by-four grid. It stands
 * in the two-by-two grid when R and C are even, at `(R/2, C/2)`, and alone when both are zero
 * — so the multiplication is each tree travelling from its old cell to its new one while the
 * newcomers grow from their own middles, a pure function of `position`, and clicking back
 * plays it undone. A tree is one drawing at one scale: the cell's share of the pane.
 *
 * The fan is the tree slide's picture — d3's `linkHorizontal` from the element's edge to each
 * label, the labels ranged away from the middle — drawn here at any scale, which is what a
 * parallel drawing allows: a scale and a shift per cell and nothing else.
 */
class DominoEffect(
    private val title: String,
    private val left: List<String>,
    private val right: List<String>,
    private val sheet: File,
    /** The elements the cells take in turn. */
    private val centres: List<Centre>,
    private val word: String = "DUURZAAM",
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val stepFrames: Int = frames(1.2),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "Domino"
    override val steps get() = 4
    override fun stepName(step: Int): String? = when (step) { 1 -> "four"; 2 -> "sixteen"; 3 -> word; else -> null }

    private lateinit var bold: FontImageMap
    private lateinit var text: FontImageMap
    private var pieces: List<SheetObject> = emptyList()

    override fun load(program: Program) {
        bold = program.loadFont(boldPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        text = program.loadFont(textPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        pieces = if (sheet.isFile) loadObjectSheet(sheet) else emptyList<SheetObject>().also { println("domino: no sheet at ${sheet.path}") }
    }

    /**
     * Where tree [r],[c] stands at whole state [s]: its cell's centre and its scale, or null when
     * it is not there. The grid is the field under the title, not the pane: on the whole pane the
     * top row's labels ran under the title.
     */
    private fun cell(r: Int, c: Int, s: Int, w: Double, h: Double): Pair<Vector2, Double>? {
        val top = h * FIELD_TOP
        val fh = h - top
        val fit = fh / h
        return when (s) {
            0 -> if (r == 0 && c == 0) Vector2(w / 2.0, top + fh / 2.0) to fit else null
            1 -> if (r % 2 == 0 && c % 2 == 0) Vector2(w * (c / 2 + 0.5) / 2.0, top + fh * (r / 2 + 0.5) / 2.0) to fit * 0.5 else null
            else -> Vector2(w * (c + 0.5) / 4.0, top + fh * (r + 0.5) / 4.0) to fit * 0.25
        }
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val w = stage.width
        val h = stage.height
        drawer.stroke = null
        drawer.fill = ink
        drawer.setLine(title, bold, Vector2(w / 2.0, h * TITLE_Y), h * TITLE, SIZE, align = 0.5)

        val p = stage.position.coerceIn(0.0, 3.0)
        val a = floor(p).toInt()
        val b = min(a + 1, 3)
        val t = p - a
        val opening = stage.step == 0 && p < 1e-6
        val grown0 = if (opening) smoothstep(stage.since(0, stepFrames)) else 1.0

        // The field, until the word takes over.
        val field = if (a >= 3) 0.0 else if (b == 3) (1.0 - t / FADE).coerceIn(0.0, 1.0) else 1.0
        if (field > 0.0) {
            val sa = min(a, 2)
            val sb = min(b, 2)
            for (r in 0 until 4) for (c in 0 until 4) {
                val from = cell(r, c, sa, w, h)
                val to = cell(r, c, sb, w, h)
                val centre: Vector2
                val scale: Double
                val alpha: Double
                when {
                    from != null && to != null -> {
                        centre = from.first + (to.first - from.first) * t
                        scale = from.second + (to.second - from.second) * t
                        alpha = 1.0
                    }
                    to != null -> {
                        val g = smoothstep(t)
                        if (g <= 0.0) continue
                        centre = to.first; scale = to.second * g; alpha = g
                    }
                    else -> continue
                }
                val which = centres.getOrNull((r * 4 + c) % centres.size.coerceAtLeast(1))
                val g0 = if (r == 0 && c == 0) grown0 else 1.0
                tree(drawer, centre, scale * g0, alpha * field * g0, which, w, h)
            }
        }

        // The word, set to the pane, in from the last third of the click.
        val worded = if (a >= 3) 1.0 else if (b == 3) ((t - (1.0 - FADE)) / FADE).coerceIn(0.0, 1.0) else 0.0
        if (worded > 0.0) {
            val box = Rectangle(w * WORD_INSET, h * WORD_TOP, w * (1.0 - 2.0 * WORD_INSET), h * (WORD_BOTTOM - WORD_TOP))
            drawer.fill = ink.opacify(worded)
            bold.setToFit(word, box, SIZE, 1.0, 1).draw(drawer, box.center)
        }
    }

    /** One tree at [scale] of the pane, centred on [centre], the pane being [w] by [h]. */
    private fun tree(drawer: Drawer, centre: Vector2, scale: Double, alpha: Double, which: Centre?, w: Double, h: Double) {
        if (scale <= 0.0 || alpha <= 0.0) return
        val rows = maxOf(left.size, right.size, 1)
        val pitch = (h - 2.0 * h * INSET) / rows
        val widest = (left + right).maxOfOrNull { text.advanceOf(it) } ?: 1.0
        val type = min(pitch * LINE, w * COLUMN * SIZE / widest)      // pane pixels
        val column = widest * type / SIZE
        val tip = w / 2.0 - w * INSET - column - w * LABEL_GAP
        val root = h * ROOT
        val hub = root * 0.6 + w * HUB_GAP

        drawer.isolated {
            drawer.translate(centre)
            drawer.scale(scale)
            drawer.strokeWeight = LINK / scale.coerceAtLeast(0.25)
            for (side in listOf(-1, 1)) {
                val branch = if (side < 0) left else right
                val top = -branch.size * pitch / 2.0
                branch.forEachIndexed { i, label ->
                    val at = Vector2(side * tip, top + (i + 0.5) * pitch)
                    val from = Vector2(side * hub, 0.0)
                    val mid = (from.x + at.x) / 2.0
                    drawer.fill = null
                    drawer.stroke = ink.opacify(alpha)
                    drawer.contour(contour { moveTo(from); curveTo(Vector2(mid, from.y), Vector2(mid, at.y), at) })
                    drawer.stroke = null
                    drawer.fill = ink.opacify(alpha)
                    drawer.setLine(label, text, Vector2(at.x + side * w * LABEL_GAP, at.y + type * 0.34), type, SIZE, align = if (side < 0) 1.0 else 0.0)
                }
            }
            // The element in the middle, off the sheet, in its own colour.
            val piece = which?.let { pieces.getOrNull(it.piece) }
            drawer.stroke = null
            if (piece != null && which != null) {
                drawer.fill = which.colour.opacify(alpha)
                drawer.isolated { piece.drawFitted(drawer, Rectangle(-root / 2.0, -root / 2.0, root, root)) }
            } else {
                drawer.fill = ink.opacify(alpha)
                drawer.rectangle(Rectangle(-root / 2.0, -root / 2.0, root, root))
            }
        }
    }

    private companion object {
        const val SIZE = 200.0
        const val TITLE = 0.036
        const val TITLE_Y = 0.06
        const val INSET = 0.05
        const val FIELD_TOP = 0.1
        const val LINE = 0.66
        const val COLUMN = 0.28
        const val LABEL_GAP = 0.008
        const val HUB_GAP = 0.01
        const val ROOT = 0.1
        const val LINK = 1.6
        const val FADE = 0.3
        const val WORD_INSET = 0.04
        const val WORD_TOP = 0.3
        const val WORD_BOTTOM = 0.7
    }
}
