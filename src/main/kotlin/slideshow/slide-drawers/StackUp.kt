// ============================================================================ //
//  No `package` declaration: it stands on loadObjectSheet, in the default
//  package, which a named package cannot import from. The folder is
//  slide-drawers because that is where a slide's drawing lives.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Grow
import slideshow.Palette
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.advanceOf
import slideshow.frames
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * One band of the stack: what stands on it, how tall it is against the others, and whether it
 * is the one picked out.
 *
 * [span] left null is taken from the shape of the band — a full-width one stands taller than a
 * band split in two, which is what the drawing wants and one less thing to state. Give it a
 * number where a particular band should carry more or less weight than that.
 */
data class StackRow(
    val labels: List<String>,
    val span: Double? = null,
    val accent: Boolean = false
) {
    val weight: Double get() = span ?: if (labels.size <= 1) FULL_SPAN else 1.0

    private companion object {
        /** How much taller a band that runs the full width stands than one split in two. */
        const val FULL_SPAN = 2.4
    }
}

/** `row("Eigen transport", "Eigen Montageploegen")` — one band, and what is on it. */
fun row(vararg labels: String, span: Double? = null, accent: Boolean = false) =
    StackRow(labels.toList(), span, accent)

/**
 * A stack built a band at a time: the first is the whole frame, and every click adds another
 * underneath while the ones already up close ranks above it.
 *
 * This is `demo01`'s `packBoxes` as a slide, and the same idea carried across: **the layout is
 * a pure function of one number**. [stackRows] maps a space and a count of bands to every
 * rectangle on screen — no time in it, no state — and the count it is asked for is
 * `stage.position`, which is continuous. So the whole build is one expression: at 3.0 it is
 * three bands, at 3.4 the fourth is four tenths of its height and the three above have closed
 * up by exactly that much. Clicking back is a smaller number rather than an undo, and a jump
 * into the middle of the slide composes rather than fast-forwarding.
 *
 * The content is [rows] and nothing else, so the drawing is what the show says it is: add a
 * band, reword one, split one in two, and the stack re-proportions itself.
 *
 * **The type is one size across every band**, worked out from the *finished* stack rather than
 * the one on screen. Fitted to each band it would set the opening band — which is the whole
 * frame — enormous and shrink it click by click, so the reveal would read as the words
 * receding rather than as the stack filling. One size means the type is right at the end,
 * which is the state the slide is held on.
 *
 * **A click is one box, not one band.** The stack is rows of two, and revealing a row at a time
 * put two things on the wall for every click — asked to be one by one on 16 September, which is
 * also what a build the speaker talks over wants. So the count the layout runs on is boxes: a
 * band grows in height as its *first* box arrives, and its second grows out of its own left edge
 * on the click after. Everything else is unchanged, because the layout was already a pure
 * function of a number and only the number's meaning moved.
 *
 * **A box is one plain box, drawn with a catalogue piece's contour.** It was a *field* of pieces
 * tiled across the box for a day, and that was taken out on 16 September: a run of silhouettes
 * inside a bar reads as texture, where the bar itself is the thing being counted. What is left is
 * the profile — [barPiece] off [sheet], the notched slab that stood on "Eigen transport", stretched
 * to whatever box it fills — so every bar is a plain block with a toothed foot rather than a
 * rectangle, and the drawing still says concrete. With no sheet the bars are plain rectangles.
 */
class StackUp(
    private val rows: List<StackRow> = emptyList(),
    private val title: String = "",
    private val subtitle: String = "",
    private val fontPath: String = "data/fonts/default.otf",
    /** The catalogue sheet the bars take their profile from. Null draws them as plain bars. */
    private val sheet: File? = null,
    /**
     * Which piece of [sheet] gives every bar its outline, counted over the holeless pieces.
     *
     * 3 is the notched slab — a block with five teeth along its bottom edge — which is the one
     * that happened to fall on "Eigen transport" while each bar took a piece of its own, and the
     * one that was asked for on 16 September. Stated rather than derived, so re-ordering the rows
     * cannot quietly change what the whole stack is drawn with.
     */
    private val barPiece: Int = 3,
    private val block: ColorRGBa = Palette.onBlack.structure,
    private val accent: ColorRGBa = Palette.onBlack.accent,
    private val ink: ColorRGBa = Palette.onBlack.ink,
    override val background: ColorRGBa = Palette.onBlack.paper,
    private val pace: Double = 0.55,
    /** The cue as the slide comes up, over the opening band. */
    override val sound: Sound? = null,
    /** A mark as each band after the first lands; the first arrives with the slide. */
    override val stepCues: List<Sound> = emptyList()
) : Slide() {
    override val name = "Stack up"

    /** Where each band's first box falls in the run of boxes. */
    private val firstBox: List<Int> = rows.runningFold(0) { at, row -> at + row.labels.size }

    /** The first box is up before anything is clicked, so a click a box leaves this many. */
    override val steps = firstBox.last().coerceAtLeast(1)

    override val stepFrames = frames(pace)

    override fun stepName(step: Int): String? {
        val band = rows.indices.lastOrNull { firstBox[it] <= step } ?: return null
        return rows[band].labels.getOrNull(step - firstBox[band])
    }

    private lateinit var face: FontImageMap
    private lateinit var head: FontImageMap
    private lateinit var sub: FontImageMap

    /** The one piece every bar is drawn with. Null where there is no sheet, and bars are plain. */
    private var profile: SheetObject? = null

    override fun load(program: Program) {
        face = program.loadFont(fontPath, ATLAS, TYPE_CHARACTERS, contentScale = 1.0)
        head = program.loadFont(fontPath, HEAD, TYPE_CHARACTERS, contentScale = 1.0)
        sub = program.loadFont(fontPath, SUB, TYPE_CHARACTERS, contentScale = 1.0)
        // A shape with more than one contour is an outline with a hole punched in it. Those are
        // dropped: a hole in a bar shows the ground through it.
        val holeless = sheet?.takeIf { it.isFile }
            ?.let { runCatching { loadObjectSheet(it) }.getOrNull() }
            ?.filter { o -> o.shapes.all { it.contours.size <= 1 } }
            .orEmpty()
        profile = holeless.getOrNull(barPiece)
        if (sheet != null)
            println("stack: piece $barPiece of ${holeless.size} holeless off ${sheet.path}")
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        if (rows.isEmpty()) return

        val crown = if (title.isBlank() && subtitle.isBlank()) 0.0 else CROWN
        val space = Rectangle(
            INSET, INSET + crown,
            stage.width - 2 * INSET,
            stage.height - 2 * INSET - crown
        )

        heading(drawer, stage)

        // One size for every band, off the *finished* stack: the boxes are widest and tallest
        // on the first click and narrow as the stack fills, so type fitted to what is on
        // screen would start huge and shrink the whole way down.
        val scale = sizeFor(space)

        // Boxes, not bands: a band stands as far as its first box has arrived.
        val shown = 1.0 + stage.position
        val bands = stackRows(space, rows, rows.indices.map { (shown - firstBox[it]).coerceIn(0.0, 1.0) }, GAP)

        bands.forEachIndexed { index, band ->
            if (band.height <= 1.0) return@forEachIndexed
            val on = rows[index]
            val boxes = across(band, on.labels.size, GAP)

            drawer.stroke = null
            on.labels.forEachIndexed { column, label ->
                val arrived = (shown - (firstBox[index] + column)).coerceIn(0.0, 1.0)
                if (arrived <= 0.0) return@forEachIndexed
                // The first box of a band arrives with the band's own height; the ones after it
                // grow out of their own left edge, so nothing on the wall jumps sideways.
                val box = if (column == 0) boxes[column] else Grow.fromLeft(boxes[column], arrived)
                bar(drawer, box, if (on.accent) accent else block)
                drawer.fill = ink.opacify(arrived)
                line(drawer, label, boxes[column], scale)
            }
        }
    }

    /**
     * One box: [profile]'s outline stretched to fill it, or a plain rectangle where there is no
     * sheet.
     *
     * **Stretched rather than fitted, which is the whole of what makes it a box.** A bar here is
     * anything from the whole frame to a tenth of it and from a full band to half of one, so a
     * silhouette held at its own proportion would stand in the middle of its box with air around
     * it — a component standing *in* a bar rather than the bar itself. Stretched, the box keeps
     * the piece's profile and nothing else: the notched foot runs the width of whatever it fills.
     */
    private fun bar(drawer: Drawer, box: Rectangle, tint: ColorRGBa) {
        drawer.fill = tint
        val piece = profile
        val bounds = piece?.bounds
        if (piece == null || bounds == null || bounds.width <= 0.0 || bounds.height <= 0.0 ||
            box.width <= 1.0 || box.height <= 1.0
        ) {
            drawer.rectangle(box)
            return
        }
        drawer.isolated {
            drawer.translate(box.corner)
            drawer.scale(box.width / bounds.width, box.height / bounds.height)
            drawer.translate(-bounds.corner)
            drawer.shapes(piece.shapes)
        }
    }

    /** What the stack is called, standing above it. */
    private fun heading(drawer: Drawer, stage: Stage) {
        if (title.isBlank() && subtitle.isBlank()) return
        drawer.fill = ink
        if (title.isNotBlank()) {
            drawer.fontMap = head
            drawer.text(title, (stage.width - head.advanceOf(title)) / 2.0, INSET + HEAD * 0.9)
        }
        if (subtitle.isNotBlank()) {
            drawer.fontMap = sub
            drawer.text(subtitle, (stage.width - sub.advanceOf(subtitle)) / 2.0, INSET + HEAD * 0.9 + SUB * 1.35)
        }
    }

    /**
     * The size every label is set at: the largest that clears the narrowest box of the
     * finished stack and its own band's height.
     */
    private fun sizeFor(space: Rectangle): Double {
        val finished = stackRows(space, rows, rows.map { 1.0 }, GAP)
        var scale = Double.MAX_VALUE
        rows.forEachIndexed { index, on ->
            val boxes = across(finished[index], on.labels.size, GAP)
            on.labels.forEachIndexed { column, label ->
                val room = boxes[column].width - 2 * PAD
                scale = min(scale, room / face.advanceOf(label).coerceAtLeast(1.0))
                scale = min(scale, finished[index].height * LINE / ATLAS)
            }
        }
        return scale.coerceAtMost(LARGEST / ATLAS)
    }

    /** One label, centred in its box. */
    private fun line(drawer: Drawer, text: String, box: Rectangle, scale: Double) {
        drawer.fontMap = face
        drawer.isolated {
            drawer.translate(
                box.center.x - face.advanceOf(text) * scale / 2.0,
                box.center.y + ATLAS * scale * BASELINE
            )
            drawer.scale(scale)
            drawer.text(text, 0.0, 0.0)
        }
    }

    private companion object {
        const val ATLAS = 64.0
        const val HEAD = 40.0
        const val SUB = 34.0

        /** The frame's own margin. */
        const val INSET = 34.0

        /** Room kept at the top for the heading. */
        const val CROWN = 118.0

        /** Between one band and the next, and between two boxes on a band. */
        const val GAP = 10.0

        /** Kept clear inside a box, so a label is never against its edge. */
        const val PAD = 26.0

        /** Of a band's height, how much a line of type may take. */
        const val LINE = 0.42

        /** However much room a short label has, type is not set larger than this. */
        const val LARGEST = 30.0

        const val BASELINE = 0.34
    }
}

// ------------------------------------------------------------------------------ //

/**
 * Bands filling [space], top to bottom, in proportion to their weights, each standing to the
 * degree [fractions] says.
 *
 * A pure function of a space and a list of numbers, the same as `packBoxes` in demo01 and
 * `stateAt` in Decision — no time in it and no state. A fraction is deliberately continuous: a
 * band four tenths of the way in is four tenths of its height and the ones above it have closed
 * up by exactly that much. That is the whole of the animation, and it is why clicking back plays
 * it out again for nothing.
 *
 * It took a single band *count* until 16 September, which is the same thing where bands arrive
 * one at a time; a list is what a stack whose bands arrive a box at a time needs, since the band
 * carrying the box that is arriving is not always the last one standing.
 */
fun stackRows(space: Rectangle, rows: List<StackRow>, fractions: List<Double>, gap: Double = 10.0): List<Rectangle> {
    if (rows.isEmpty()) return emptyList()

    val shown = rows.indices.lastOrNull { (fractions.getOrNull(it) ?: 0.0) > 0.0 }?.plus(1) ?: return emptyList()
    val weights = (0 until shown).map { rows[it].weight * (fractions.getOrNull(it) ?: 0.0).coerceIn(0.0, 1.0) }
    val total = weights.sum().coerceAtLeast(1e-6)
    val room = space.height - (shown - 1) * gap

    val out = ArrayList<Rectangle>(shown)
    var y = space.corner.y
    weights.forEach { weight ->
        val height = room * weight / total
        out += Rectangle(space.corner.x, y, space.width, height.coerceAtLeast(0.0))
        y += height + gap
    }
    return out
}

/** The same, where the bands arrive one at a time: [count] is how many are up, fractionally. */
fun stackRows(space: Rectangle, rows: List<StackRow>, count: Double, gap: Double = 10.0): List<Rectangle> =
    stackRows(space, rows, rows.indices.map { (count - it).coerceIn(0.0, 1.0) }, gap)

/** A band divided into [count] boxes side by side. */
fun across(band: Rectangle, count: Int, gap: Double = 10.0): List<Rectangle> {
    val n = count.coerceAtLeast(1)
    val width = (band.width - (n - 1) * gap) / n
    return (0 until n).map {
        Rectangle(band.corner.x + it * (width + gap), band.corner.y, width, band.height)
    }
}
