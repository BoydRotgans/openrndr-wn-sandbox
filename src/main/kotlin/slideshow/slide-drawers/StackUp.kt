package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.frames
import kotlin.math.ceil
import kotlin.math.floor
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
 */
class StackUp(
    private val rows: List<StackRow> = emptyList(),
    private val title: String = "",
    private val subtitle: String = "",
    private val fontPath: String = "data/fonts/default.otf",
    private val block: ColorRGBa = ColorRGBa.fromHex("3D5AE0"),
    private val accent: ColorRGBa = ColorRGBa.fromHex("ED1C24"),
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    private val pace: Double = 0.55,
    /** The cue as the slide comes up, over the opening band. */
    override val sound: Sound? = null,
    /** A mark as each band after the first lands; the first arrives with the slide. */
    override val stepCues: List<Sound> = emptyList()
) : Slide() {
    override val name = "Stack up"

    /** The first band is up before anything is clicked, so a click a band leaves this many. */
    override val steps = rows.size.coerceAtLeast(1)

    override val stepFrames = frames(pace)

    private lateinit var face: FontImageMap
    private lateinit var head: FontImageMap
    private lateinit var sub: FontImageMap

    override fun load(program: Program) {
        face = program.loadFont(fontPath, ATLAS, TYPE_CHARACTERS, contentScale = 1.0)
        head = program.loadFont(fontPath, HEAD, TYPE_CHARACTERS, contentScale = 1.0)
        sub = program.loadFont(fontPath, SUB, TYPE_CHARACTERS, contentScale = 1.0)
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

        val bands = stackRows(space, rows, 1.0 + stage.position, GAP)
        bands.forEachIndexed { index, band ->
            if (band.height <= 1.0) return@forEachIndexed
            val on = rows[index]
            val boxes = across(band, on.labels.size, GAP)

            drawer.stroke = null
            drawer.fill = if (on.accent) accent else block
            boxes.forEach { drawer.rectangle(it) }

            drawer.fill = ink
            on.labels.forEachIndexed { column, label -> line(drawer, label, boxes[column], scale) }
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
        val finished = stackRows(space, rows, rows.size.toDouble(), GAP)
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
 * [count] bands filling [space], top to bottom, in proportion to their weights.
 *
 * A pure function of a space and a number, the same as `packBoxes` in demo01 and `stateAt` in
 * Decision — no time in it and no state. [count] is deliberately a `Double`: the band arriving
 * is given a fraction of its weight, so at 3.4 the fourth band is four tenths of its height
 * and the three above it have closed up by exactly that much. That is the whole of the
 * animation, and it is why clicking back plays it out again for nothing.
 */
fun stackRows(space: Rectangle, rows: List<StackRow>, count: Double, gap: Double = 10.0): List<Rectangle> {
    if (rows.isEmpty() || count <= 0.0) return emptyList()

    val whole = floor(count).toInt()
    val shown = min(rows.size, ceil(count).toInt())
    val arriving = count - whole

    val weights = (0 until shown).map { i ->
        val full = rows[i].weight
        if (i == shown - 1 && i >= whole) full * arriving else full
    }
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

/** A band divided into [count] boxes side by side. */
fun across(band: Rectangle, count: Int, gap: Double = 10.0): List<Rectangle> {
    val n = count.coerceAtLeast(1)
    val width = (band.width - (n - 1) * gap) / n
    return (0 until n).map {
        Rectangle(band.corner.x + it * (width + gap), band.corner.y, width, band.height)
    }
}
