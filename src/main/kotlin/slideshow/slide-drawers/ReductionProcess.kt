package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.frames
import slideshow.smoothstep

/**
 * The carbon footprint of the ordinary process against the process with reuse in it: two
 * columns of steps, and the second one is the first with a step put in and most of it crossed
 * off. Four states:
 *
 *     0  the ordinary process, a column of steps growing down on the slide's clock
 *     1  the reuse process beside it: the same steps, each growing out from the column it copies
 *     2  the steps reuse does away with turn red, and the bracket says what share they are
 *     3  the disassembly step slides in between, and the bracket for what is left
 *
 * **The right column's layout is a pure function of how many steps it holds**, which is
 * `steps + on(3)`: the boxes share the column's height, so the new one arriving is not a box
 * fading in over the others but a slot opening — the two below it move down as it grows, and
 * clicking back closes it again. `stackRows` and `LifeCycle`'s columns, once more.
 *
 * **A copied step grows out of the column it copies.** Each right-hand box grows from its left
 * edge to its full width, top to bottom a little apart, its lettering fading up over the last
 * third — it has somewhere to come from, so it comes from there rather than popping in. Sliding
 * the copies across was filmed first, and they crossed the left column's lettering on the way.
 *
 * The copy stays blue and only the steps reuse removes turn red, on the third click, the
 * brackets drawing down beside them on the same number their figures fade up on. The colours
 * and the shares are the show's; the drawer only knows that the first [reused] steps are the
 * ones that go.
 */
class ReductionProcess(
    private val title: String = "Reductie van de carbon footprint",
    private val leftHeading: String = "Normaal productieproces",
    private val rightHeading: String = "Proces bij circulair hergebruik",
    /** The steps of the ordinary process, top to bottom. */
    private val stages: List<String>,
    /** How many of them, from the top, reuse does away with. */
    private val reused: Int = 3,
    /** The step reuse puts in their place, under them. */
    private val disassembly: String = "Uit elkaar halen bouwblokken",
    /** The brackets' figures: the share the removed steps are, and the share that remains. */
    private val shares: Pair<String, String> = "80%" to "20%",
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    private val blue: ColorRGBa = ColorRGBa.fromHex("4674D6"),
    private val red: ColorRGBa = ColorRGBa.fromHex("FF0000"),
    private val grey: ColorRGBa = ColorRGBa.fromHex("D9D9D9"),
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val stepFrames: Int = frames(0.8),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "Reduction"
    override val steps get() = 4
    override val settle get() = frames(STAGGER) * (stages.size - 1) + stepFrames

    override fun stepName(step: Int): String? = when (step) {
        1 -> "the process with reuse"
        2 -> "what reuse removes"
        3 -> "disassembly in its place"
        else -> null
    }

    private lateinit var bold: FontImageMap
    private lateinit var text: FontImageMap

    override fun load(program: Program) {
        bold = program.loadFont(boldPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        text = program.loadFont(textPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val w = stage.width
        val h = stage.height
        drawer.stroke = null

        drawer.fill = ink
        drawer.setLine(title, bold, Vector2(w / 2.0, h * TITLE_Y), h * TITLE, SIZE, align = 0.5)

        val left = Rectangle(w * LEFT_X, h * TOP, w * COLUMN, h * (BOTTOM - TOP))
        val right = Rectangle(w * RIGHT_X, h * TOP, w * COLUMN, h * (BOTTOM - TOP))
        val gap = h * GAP

        // The left column builds itself on the slide's clock, each box growing down from its top
        // edge, one after another. Stepping down from the row above was tried and filmed: a box
        // coming through the one over it is two translucent boxes on top of each other.
        val opening = stage.step == 0 && stage.position < 1e-6
        // The left column has no slot in it: the same layout with the slot's box left out.
        val leftBoxes = column(left, stages.size, 0.0).filterIndexed { k, _ -> k != reused }
        drawer.fill = ink
        drawer.setLine(leftHeading, text, Vector2(left.center.x, h * HEADING_Y), h * HEADING, SIZE, align = 0.5)
        leftBoxes.forEachIndexed { i, box ->
            val built = if (opening) smoothstep(stage.since(i * frames(STAGGER), stepFrames)) else 1.0
            if (built <= 0.0) return@forEachIndexed
            step(drawer, Rectangle(box.x, box.y, box.width, box.height * built), stages[i], blue, ink, 1.0, h, lettering = lettered(built))
        }

        // The right column: the copy arriving, the removed steps turning, the slot opening.
        val copied = stage.on(1)
        val turned = stage.on(2)
        val opened = stage.on(3)
        if (copied <= 0.0) return

        drawer.fill = ink.opacify(copied)
        drawer.setLine(rightHeading, text, Vector2(right.center.x, h * HEADING_Y), h * HEADING, SIZE, align = 0.5)

        val rightBoxes = column(right, stages.size, opened)
        // rightBoxes: the first `reused`, then the disassembly slot, then the rest.
        rightBoxes.forEachIndexed { k, box ->
            val slot = k == reused
            val i = if (k < reused) k else k - 1
            if (slot) {
                if (opened <= 0.0) return@forEachIndexed
                step(drawer, box, disassembly, grey, background, 1.0, h, lettering = lettered(opened))
                return@forEachIndexed
            }
            // A copy grows out from its left edge — from the column it copies — rather than
            // travelling across it: filmed travelling, the copies crossed the left column's
            // lettering on their way.
            val arrived = staggered(copied, i, stages.size, LAG)
            if (arrived <= 0.0) return@forEachIndexed
            val at = Rectangle(box.x, box.y, box.width * arrived, box.height)
            val colour = if (k < reused) blue.mix(red, turned) else blue
            step(drawer, at, stages[i], colour, ink, 1.0, h, lettering = lettered(arrived))
        }

        // The brackets: a rule down beside the steps it spans, its figure level with its middle.
        val x = w * BRACKET_X
        fun bracket(top: Double, bottom: Double, figure: String, alpha: Double) {
            if (alpha <= 0.0) return
            drawer.stroke = ink.opacify(alpha)
            drawer.strokeWeight = LINE
            drawer.lineSegment(Vector2(x, top), Vector2(x, top + (bottom - top) * alpha))
            drawer.stroke = null
            drawer.fill = ink.opacify(alpha)
            drawer.setLine(figure, text, Vector2(x + w * BRACKET_GAP, (top + bottom) / 2.0 + h * FIGURE * 0.34), h * FIGURE, SIZE)
        }
        bracket(rightBoxes.first().y, rightBoxes[reused - 1].let { it.y + it.height }, shares.first, turned)
        bracket(rightBoxes[reused].y, rightBoxes.last().let { it.y + it.height }, shares.second, opened)
    }

    /**
     * The boxes of a column of [count] steps with a slot [opened] of the way open after the
     * first [reused]: every box shares the height, the slot taking [opened] of a box.
     */
    private fun column(area: Rectangle, count: Int, opened: Double): List<Rectangle> {
        val gap = area.height * GAP / (BOTTOM - TOP)
        val n = count + opened
        val each = (area.height - (n - 1.0) * gap) / n
        val out = mutableListOf<Rectangle>()
        var y = area.y
        for (k in 0..count) {
            val slot = k == reused
            val height = if (slot) each * opened else each
            out += Rectangle(area.x, y, area.width, height)
            y += height + if (slot) gap * opened else gap
        }
        return out
    }

    /** How far a box's lettering has come, for a box [grown] of the way: it fades up over the last third. */
    private fun lettered(grown: Double) = ((grown - (1.0 - LETTER)) / LETTER).coerceIn(0.0, 1.0)

    /** One step: its box in [colour] with [label] centred in it, the box at [alpha] and the label at [lettering] of it. */
    private fun step(drawer: Drawer, box: Rectangle, label: String, colour: ColorRGBa, ink: ColorRGBa, alpha: Double, h: Double, lettering: Double = 1.0) {
        drawer.fill = colour.opacify(alpha)
        drawer.rectangle(box)
        if (box.height < h * LABEL || lettering <= 0.0) return
        drawer.fill = ink.opacify(alpha * lettering)
        drawer.setLine(label, text, Vector2(box.center.x, box.center.y + h * LABEL * 0.34), h * LABEL, SIZE, align = 0.5)
    }

    private companion object {
        const val SIZE = 200.0

        /** Measured off the frame: two columns 0.447 wide from 0.013 and 0.472, boxes 0.146 to 0.975 down. */
        const val LEFT_X = 0.013
        const val RIGHT_X = 0.472
        const val COLUMN = 0.447
        const val TOP = 0.146
        const val BOTTOM = 0.975
        const val GAP = 0.02
        const val BRACKET_X = 0.935
        const val BRACKET_GAP = 0.012

        const val TITLE = 0.036
        const val TITLE_Y = 0.06
        const val HEADING = 0.026
        const val HEADING_Y = 0.118
        const val LABEL = 0.030
        const val FIGURE = 0.026
        const val LINE = 2.0

        /** Seconds between the left column's boxes as they build; the click's share between copies. */
        const val STAGGER = 0.12
        const val LAG = 0.12
        /** The share of a box's growth over which its lettering fades up. */
        const val LETTER = 0.35
    }
}
