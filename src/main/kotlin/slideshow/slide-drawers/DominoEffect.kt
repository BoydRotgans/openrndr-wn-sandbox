// ============================================================================ //
//  No `package` declaration: it stands on loadObjectSheet, in the default package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadFont
import org.openrndr.draw.parameter
import org.openrndr.draw.renderTarget
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import org.openrndr.shape.contour
import slideshow.Arrival
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.advanceOf
import slideshow.drawers.setLine
import slideshow.drawers.setToFit
import slideshow.frames
import slideshow.linear
import slideshow.smoothstep
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** An element at the middle of a tree: which drawing on the sheet, in what. */
class Centre(val piece: Int, val colour: ColorRGBa)

/**
 * The domino effect: the decision tree, then nine of them, then twenty-five, then one word.
 *
 *     0  one tree, the pane's own — the fans of labels either side of an element
 *     1  nine, three by three, the camera a third of the way back
 *     2  twenty-five, five by five, the labels now too small to read — which is the point
 *     3  the field gives way to DUURZAAM, set to the pane
 *
 * **The multiplication is a zoom out, not a reshuffle**, which is what it was asked to be on
 * 16 September. Every tree stands at `(row, column)` on a lattice measured from the middle of
 * the field, and the only thing a click changes is how many trees the field is divided into:
 * the lattice contracts about its own centre, every tree keeps its place in it, and the ones
 * that were beyond the edge come into shot. So the tree the slide opens on never moves — it
 * only shrinks, in the middle, while the ring around it arrives. It was each tree travelling
 * from its cell in one grid to its cell in the next until then, which reads as a set of
 * drawings being re-dealt rather than as the camera pulling back.
 *
 * **The grids are odd for that reason and no other.** A pure zoom about the centre needs
 * something *at* the centre to hold still, and an even grid has a corner there — one, four,
 * sixteen cannot be a zoom at all, because the single tree has to move into a quadrant. One,
 * nine, twenty-five is the same idea with a middle, and says more: the field ends 25 trees deep
 * rather than 16.
 *
 * **The zoom runs in log space**, the case study's rule: read linearly a scale races at one end
 * of the move and crawls at the other, and what the eye reads as even is an even *ratio*.
 *
 * The fan is the tree slide's picture — d3's `linkHorizontal` from the element's edge to each
 * label, the labels ranged away from the middle — drawn here at any scale, which is what a
 * parallel drawing allows: a scale and a shift per cell and nothing else.
 *
 * **The word is packed out of the catalogue rather than set as type.** It is set into a plate
 * nobody sees, and [objectMosaic] walks a quadtree over that plate: a square wholly inside a
 * letter stands one element at its own size, a square that straddles the edge quarters and asks
 * again, down to [FINEST] — so a stroke is filled with big pieces and traced with small ones, and
 * the size of a mark says where in the letter it is. The ground cells are thrown away, which is
 * the one thing this does differently from the chapter card: the card packs the whole plate and
 * lets colour tell ink from ground, where here the word has to stand alone on black.
 *
 * **The marks arrive one at a time, and the whole word is still one draw call.** Every vertex
 * carries its element's centre and its place in the order, so [MOSAIC_ARRIVAL] grows each one
 * from its own middle as its number comes up — a few thousand elements without a few thousand
 * draw calls, and nothing to keep between frames. The count runs off the same number the field
 * fades on, so clicking back packs the word away again rather than cutting it.
 *
 * **The plate is painted in [load] at a stated pane size**, the rule `CityMapSlide` keeps and for
 * its reason: the packing is a tenth of a second of quadtree and it may not land on the click
 * that plays it, and `load` is not told how big a pane will be. `draw` fits what was packed into
 * whatever pane it gets.
 */
class DominoEffect(
    private val title: String,
    private val left: List<String>,
    private val right: List<String>,
    private val sheet: File,
    /** The elements the cells take in turn. */
    private val centres: List<Centre>,
    private val word: String = "DUURZAAM",
    /**
     * The pane the word is packed at. Stated because [load] is not told the pane — see the note
     * above — and [draw] fits the packing into whatever it is actually given.
     */
    private val paneWidth: Int = 1920,
    private val paneHeight: Int = 1080,
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
    override fun stepName(step: Int): String? = when (step) {
        1 -> "${GRIDS[1] * GRIDS[1]}"
        2 -> "${GRIDS[2] * GRIDS[2]}"
        3 -> word
        else -> null
    }

    private lateinit var bold: FontImageMap
    private lateinit var text: FontImageMap
    private var pieces: List<SheetObject> = emptyList()

    /** The word, packed. Null where there is no sheet, and it is set as plain type instead. */
    private var packed: VertexBuffer? = null
    private var marks = 0

    /**
     * The word's click is longer than the others, and it has to be.
     *
     * Every other click here is a zoom; this one both empties the field and packs a few thousand
     * elements into a word, one after another. At the deck's own [stepFrames] the whole arrival
     * would run in a third of a second, which is a cut with a blur on it.
     */
    override fun stepLength(step: Int): Int = if (step == 3) frames(WORD_CLICK) else stepFrames

    /**
     * The field on one lane and the word on another, which is what the slide really is: three
     * states of trees multiplying, and then a thousand-odd elements landing as DUURZAAM.
     *
     * **The trees are three events, not many.** A click brings a whole ring of them up together —
     * `alpha = smoothstep(t)` for every newcomer at once — so there is nothing per tree to write
     * down, and saying otherwise would invent a texture the slide does not have.
     *
     * **The word is the opposite**, and it is where the notes are. `MOSAIC_ARRIVAL` grows element
     * *i* once `arrived * (marks + ramp)` passes it, so its start is exact; it is banded into
     * [BANDS] for the reason `Crowd`'s figures are, since [marks] runs past a thousand. Two things
     * have to be undone to get the frame: the word only begins at `1 - FADE` of the click, and
     * `stage.position` is *eased*, so a fraction of the click is `linear()` of it — the city
     * cull's distinction, and the difference between a run that rises evenly and one that
     * crowds into the middle.
     */
    override val lanes: List<String> get() = listOf("the field", "the word")

    override fun arrivals(clicks: List<Int>): List<Arrival> {
        val field = listOf(Arrival(lane = 0, index = 0, start = 0, length = stepFrames)) +
                clicks.take(2).mapIndexed { k, at -> Arrival(lane = 0, index = k + 1, start = at, length = stepLength(k + 1)) }
        val at = clicks.getOrNull(2) ?: return field
        val span = stepLength(3)
        if (marks <= 0) return field + Arrival(lane = 1, index = 0, start = at, length = span)

        val ramp = (marks * RAMP).coerceAtLeast(1.0)
        val n = min(BANDS, marks)
        /** The frame the word's progress reaches [worded], undoing the fade window and the ease. */
        fun frameOf(worded: Double): Int {
            val eased = (1.0 - FADE) + FADE * worded.coerceIn(0.0, 1.0)
            return at + (linear(eased) * span).toInt()
        }
        val word = (0 until n).map { b ->
            val i = (b.toDouble() / n) * marks
            val start = frameOf(i / (marks + ramp))
            val end = frameOf((i + ramp) / (marks + ramp))
            Arrival(lane = 1, index = b, start = start, length = (end - start).coerceAtLeast(1))
        }
        return field + word
    }

    override fun load(program: Program) {
        bold = program.loadFont(boldPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        text = program.loadFont(textPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        pieces = if (sheet.isFile) loadObjectSheet(sheet) else emptyList<SheetObject>().also { println("domino: no sheet at ${sheet.path}") }
        packWord(program)
    }

    /**
     * The word set into a plate nobody sees, read back as a field of catalogue elements.
     *
     * Only the cells standing *in* the ink are kept. The card packs the ground as well, because
     * there the ground is a grey field the letters are cut out of; here the word has to arrive on
     * a black pane with nothing else on it, so the ground's cells are simply not built.
     */
    private fun packWord(program: Program) {
        val templates = if (sheet.isFile) loadObjectTemplates(sheet) else emptyList()
        if (templates.isEmpty()) return

        val target = renderTarget(paneWidth, paneHeight) { colorBuffer() }
        val drawer = program.drawer
        drawer.isolatedWithTarget(target) {
            drawer.ortho(target)
            // White is ink and black is ground, which is what the coverage test reads.
            drawer.clear(ColorRGBa.BLACK)
            drawer.stroke = null
            drawer.fill = ColorRGBa.WHITE
            val box = wordBox(paneWidth.toDouble(), paneHeight.toDouble())
            bold.setToFit(word, box, SIZE, 1.0, 1).draw(drawer, box.center)
        }

        val cells = objectMosaic(
            target.colorBuffer(0), templates,
            coarse = COARSE, finest = FINEST, shape = CELL, fill = FILL,
            solid = SOLID, threshold = THRESHOLD, seed = SEED
        ).filter { it.filled }

        target.colorBuffer(0).destroy()
        target.destroy()

        marks = cells.size
        packed = mosaicBuffer(cells, templates)
        println("domino: \"$word\" packed into $marks elements off ${sheet.path}")
    }

    /** Where the word is set, in a pane of [w] by [h]. One definition, read by the plate and the fallback. */
    private fun wordBox(w: Double, h: Double) =
        Rectangle(w * WORD_INSET, h * WORD_TOP, w * (1.0 - 2.0 * WORD_INSET), h * (WORD_BOTTOM - WORD_TOP))

    /** How many trees across the field carries at whole state [s] — always odd. See the note above. */
    private fun grid(s: Int) = GRIDS[s.coerceIn(0, GRIDS.size - 1)]

    /** How far out from the middle the lattice reaches at state [s], in trees. */
    private fun ringAt(s: Int) = grid(s) / 2

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
            // The grid is the field under the title, not the pane: on the whole pane the top
            // row's labels ran under the title.
            val top = h * FIELD_TOP
            val fh = h - top
            val middle = Vector2(w / 2.0, top + fh / 2.0)

            // How many trees the field is divided into, right now. Everything else follows from
            // it: the lattice pitch, the scale a tree is drawn at, and how far the ring reaches.
            val na = grid(min(a, 2)).toDouble()
            val nb = grid(min(b, 2)).toDouble()
            val n = exp(ln(na) + (ln(nb) - ln(na)) * t)
            val pitch = Vector2(w / n, fh / n)
            val scale = fh / h / n

            val here = ringAt(min(a, 2))
            val coming = ringAt(min(b, 2))
            for (r in -coming..coming) for (c in -coming..coming) {
                // Everything already standing holds its place in the lattice and only shrinks;
                // the ring beyond it is off the pane until the zoom brings it in, and fades up
                // as it comes rather than appearing at the edge.
                val standing = max(abs(r), abs(c)) <= here
                val alpha = if (standing) 1.0 else smoothstep(t)
                if (alpha <= 0.0) continue
                val centre = middle + Vector2(c * pitch.x, r * pitch.y)
                val which = centres.getOrNull(
                    ((r + REACH) * (2 * REACH + 1) + (c + REACH)) % centres.size.coerceAtLeast(1)
                )
                val g0 = if (r == 0 && c == 0) grown0 else 1.0
                tree(drawer, centre, scale * g0, alpha * field * g0, which, w, h)
            }
        }

        // The word, packing itself in from the last of the click.
        val worded = if (a >= 3) 1.0 else if (b == 3) ((t - (1.0 - FADE)) / FADE).coerceIn(0.0, 1.0) else 0.0
        if (worded > 0.0) word(drawer, worded, w, h)
    }

    /**
     * The word: the packed field where there is one, and plain type where there is not — the same
     * honest fallback the chapter card makes when its sheet is missing.
     *
     * The field was packed against a stated pane, so it is *fitted* into this one rather than
     * laid out against it: one scale about the middle, which is all a parallel drawing ever needs.
     */
    private fun word(drawer: Drawer, arrived: Double, w: Double, h: Double) {
        val mesh = packed
        if (mesh == null) {
            val box = wordBox(w, h)
            drawer.fill = ink.opacify(arrived)
            bold.setToFit(word, box, SIZE, 1.0, 1).draw(drawer, box.center)
            return
        }

        val fit = min(w / paneWidth, h / paneHeight)
        // The count runs a ramp's worth past the last mark, or the one that arrives last is still
        // half grown when the click lands — the same trap the city's cull has with its fade.
        val ramp = (marks * RAMP).coerceAtLeast(1.0)
        drawer.stroke = null
        drawer.fill = ink
        drawer.isolated {
            drawer.translate(w / 2.0, h / 2.0)
            drawer.scale(fit, fit)
            drawer.translate(-paneWidth / 2.0, -paneHeight / 2.0)
            drawer.shadeStyle = shadeStyle {
                vertexTransform = MOSAIC_ARRIVAL
                parameter("arrived", arrived * (marks + ramp))
                parameter("ramp", ramp)
            }
            drawer.vertexBuffer(mesh, DrawPrimitive.TRIANGLES)
            drawer.shadeStyle = null
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
        /**
         * Trees across the field at each state. Odd, so a zoom has something at its centre to
         * hold still — see the note above. Adding a fourth here adds a state.
         */
        val GRIDS = listOf(1, 3, 5)

        /** How far the widest lattice reaches from the middle, in trees. */
        val REACH = GRIDS.max() / 2

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
        const val FADE = 0.45
        const val WORD_INSET = 0.04
        const val WORD_TOP = 0.3
        const val WORD_BOTTOM = 0.7

        /** Seconds the last click takes — the field emptying and the word packing itself in. */
        const val WORD_CLICK = 2.6

        /**
         * The word's packing: the coarsest square, the finest, and the cell's own proportion.
         *
         * [CELL] is the subset's median piece, 1.85 wide to high — a square cell letterboxes the
         * median element and halves the ink, the note under the chapter card. [COARSE] and
         * [FINEST] are a range rather than one size because a letter needs both: DUURZAAM set to
         * the pane has strokes around 60px, so a coarse mark carries the middle of a stroke and
         * the fine ones trace its edge.
         */
        const val COARSE = 36.0
        const val FINEST = 8.0
        const val CELL = 1.85
        const val FILL = 0.9

        /**
         * How much ink counts as wholly inside a letter, and as worth keeping at [FINEST].
         *
         * [SOLID] is the card's own 0.62 rather than the strict default: a stroke this narrow has
         * few cells that are *wholly* inside it, so a strict test sends nearly every cell in the
         * word down to the finest level and the letters come out as one fine grain with no big
         * marks in them at all — a picture of type rather than type made of components.
         */
        const val SOLID = 0.62
        const val THRESHOLD = 0.3

        /** Which element lands where — fixed, so the word packs the same way every run. */
        const val SEED = 7

        /** Of the whole count, how many marks are part way in at any moment. */
        const val RAMP = 0.18

        /** Notes the word's arrival is banded into — `Crowd`'s reason, and its number. */
        const val BANDS = 24
    }
}
