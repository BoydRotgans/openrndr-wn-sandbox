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
import slideshow.linear
import slideshow.smoothstep

/**
 * One bar chart: its heading, a bar a year, the axis it stands on, an optional target line
 * across the same years, what to say at the end of the line and at the top of the last bar,
 * and the bullets under it.
 */
class BarChart(
    /** A `\n` breaks the heading where the client's frame breaks it; the rest wraps to fit. */
    val heading: String,
    val years: List<String>,
    val values: List<Double>,
    val max: Double,
    val step: Double,
    val target: List<Double> = emptyList(),
    /** Set at the end of the target line, in the line's colour, hanging from its leader. */
    val targetLabel: List<String> = emptyList(),
    /** Set on a leader from the top of the last bar, hanging from it. */
    val reachedLabel: List<String> = emptyList(),
    /** The unit, set up the side of the axis. */
    val unit: String = "",
    val bullets: List<String> = emptyList(),
    /** How far the bullets may run, mark included, as a share of the pane's width. */
    val measure: Double = 0.44
)

/**
 * The numbers behind the ladder: two bar charts side by side, and the bullets under each.
 *
 *     0  the left chart's bars, growing from the axis on the slide's clock, one after another
 *     1  the target line draws across them, and the labels at its end and the last bar's top
 *     2  the right chart's bars, and its label
 *     3  the bullets, one after another under both
 *
 * The finished state is the client's frame 2-08 of the speaker-notes deck, measured off it: the
 * title in two weights, the axes ruled in dotted grey with no ticks, every other year dimmed, the
 * labels hanging from their leaders, all nine bullets. What it keeps of its own is the house
 * family (Rockwell throughout, where the frame sets the chart's figures in a grotesque), the
 * house red and blue, and the spelling: CO₂, geïndexeerde, elektrische.
 *
 * Bars grow from their baseline on the deck's own ease and the line draws across on it; the
 * bullets are counted, so they run on `linear(on(3))` — the city's distinction.
 */
class CarbonCharts(
    private val title: String,
    /** Set after [title] in the regular weight, as the frame sets "CO₂-prestatieladder". */
    private val titleAfter: String = "",
    private val left: BarChart,
    private val right: BarChart,
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    private val bar: ColorRGBa = slideshow.Palette.RED,
    private val line: ColorRGBa = slideshow.Palette.BLUE,
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    private val grey: ColorRGBa = ColorRGBa.fromHex("D9D9D9"),
    /** Every other year, as the frame dims 2021, 2023 and 2025. */
    private val dimGrey: ColorRGBa = ColorRGBa.fromHex("7A7A7C"),
    private val rule: ColorRGBa = ColorRGBa.fromHex("444444"),
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val stepFrames: Int = frames(0.9),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "Charts"
    override val steps get() = 4
    override val settle get() = stepFrames * 2

    override fun stepName(step: Int): String? = when (step) {
        1 -> "the target"
        2 -> "green power"
        3 -> "the measures"
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

        // The title in two weights, the pair centred where the frame centres it.
        val size = h * TITLE
        val first = bold.advanceWithSubscripts(title) * size / SIZE
        val gap = if (titleAfter.isEmpty()) 0.0 else h * TITLE_GAP
        val after = if (titleAfter.isEmpty()) 0.0 else text.advanceWithSubscripts(titleAfter) * size / SIZE
        val x0 = w * TITLE_X - (first + gap + after) / 2.0
        drawer.fill = ink
        drawer.setLine(title, bold, Vector2(x0, h * TITLE_Y), size, SIZE)
        if (titleAfter.isNotEmpty()) drawer.setLine(titleAfter, text, Vector2(x0 + first + gap, h * TITLE_Y), size, SIZE)

        val opening = stage.step == 0 && stage.position < 1e-6
        val leftGrown = if (opening) smoothstep(stage.since(0, stepFrames * 2)) else 1.0
        chart(drawer, left, Rectangle(w * LEFT_X, h * PLOT_TOP, w * PLOT_W, h * (PLOT_BOTTOM - PLOT_TOP)), w * LEFT_HEAD, 1.0, leftGrown, stage.on(1), h, w)
        chart(drawer, right, Rectangle(w * RIGHT_X, h * PLOT_TOP, w * PLOT_W, h * (PLOT_BOTTOM - PLOT_TOP)), w * RIGHT_HEAD, stage.on(2), stage.on(2), stage.on(2), h, w)

        // The bullets, counted out under both charts in turn.
        val counted = linear(stage.on(3))
        val all = left.bullets.size + right.bullets.size
        drawer.bullets(
            left.bullets, Vector2(w * LEFT_BULLETS, h * BULLETS_Y), w * left.measure, text, h * BULLET, SIZE, h * BULLET_LEAD,
            ink, line, h * MARK, w * INDENT, h * ITEM_GAP
        ) { i -> staggered(counted, i, all, LAG) }
        drawer.bullets(
            right.bullets, Vector2(w * RIGHT_BULLETS, h * BULLETS_Y), w * right.measure, text, h * BULLET, SIZE, h * BULLET_LEAD,
            ink, line, h * MARK, w * INDENT, h * ITEM_GAP
        ) { i -> staggered(counted, left.bullets.size + i, all, LAG) }
    }

    /** One chart in [plot]: heading at [headX], bars grown by [grown], the line and labels by [lined]. */
    private fun chart(drawer: Drawer, c: BarChart, plot: Rectangle, headX: Double, shown: Double, grown: Double, lined: Double, h: Double, w: Double) {
        if (shown <= 0.0) return
        drawer.fill = ink.opacify(shown)
        val measure = (w * HEAD_W) * SIZE / (h * HEADING)
        c.heading.split('\n').flatMap { text.wrapped(it, measure) }.forEachIndexed { i, l ->
            drawer.setLine(l, text, Vector2(headX, h * HEADING_Y + i * h * HEADING_LEAD), h * HEADING, SIZE)
        }

        // The grid behind the bars, the figures beside it, and the unit up the side.
        drawer.dottedGrid(plot, c.max, c.step, rule, over = w * GRID_OVER, alpha = shown)
        drawer.axis(plot, c.max, c.step, text, h * AXIS, SIZE, grey, tick = 0.0, gap = w * AXIS_GAP, alpha = shown)
        if (c.unit.isNotEmpty()) {
            drawer.fill = grey.opacify(shown)
            drawer.isolated {
                translate(w * UNIT_X, plot.center.y)
                rotate(-90.0)
                setLine(c.unit, text, Vector2(0.0, h * LABEL * 0.34), h * LABEL, SIZE, align = 0.5)
            }
        }

        val n = c.years.size
        val slot = plot.width / n
        val width = slot * BAR
        val tops = c.values.mapIndexed { i, v ->
            val rect = Rectangle(plot.x + i * slot + (slot - width) / 2.0, plot.y + plot.height * (1.0 - v / c.max), width, plot.height * v / c.max)
            val g = staggered(grown, i, n, LAG)
            drawer.fill = bar.opacify(shown)
            drawer.rectangle(grownFromBase(rect, g))
            drawer.fill = (if (i % 2 == 1) dimGrey else grey).opacify(shown)
            drawer.setLine(c.years[i], text, Vector2(rect.center.x, plot.y + plot.height + h * YEAR_Y), h * AXIS, SIZE, align = 0.5)
            Vector2(rect.center.x, rect.y)
        }

        // The target line, drawn across from the first year, a dot at each; then the labels.
        val x = plot.x + plot.width + w * LABEL_GAP
        val labelAlpha = ((lined - LABEL_FROM) / (1.0 - LABEL_FROM)).coerceIn(0.0, 1.0)
        val reached = tops.last()
        if (c.target.isNotEmpty() && lined > 0.0) {
            val points = c.target.mapIndexed { i, v -> Vector2(plot.x + i * slot + slot / 2.0, plot.y + plot.height * (1.0 - v / c.max)) }
            val total = (points.size - 1).toDouble()
            val reach = lined * total
            drawer.stroke = line
            drawer.strokeWeight = LINE
            for (i in 0 until points.size - 1) {
                val seg = (reach - i).coerceIn(0.0, 1.0)
                if (seg <= 0.0) break
                drawer.lineSegment(points[i], points[i] + (points[i + 1] - points[i]) * seg)
            }
            drawer.stroke = null
            drawer.fill = line
            points.forEachIndexed { i, p -> if (reach >= i - 1e-9) drawer.circle(p, DOT) }
            drawer.leaderLabel(c.targetLabel, Vector2(x, points.last().y), points.last(), text, h * LABEL, SIZE, line, h * LABEL_LEAD, labelAlpha, gap = w * LEADER_GAP, hang = true)
            // Under the target's label if the bar's top would put the two on top of each other.
            val ty = maxOf(reached.y, points.last().y + c.targetLabel.size * h * LABEL_LEAD)
            drawer.leaderLabel(c.reachedLabel, Vector2(x, ty), reached, text, h * LABEL, SIZE, ink, h * LABEL_LEAD, labelAlpha, gap = w * LEADER_GAP, hang = true)
        } else if (c.reachedLabel.isNotEmpty() && lined > 0.0) {
            drawer.leaderLabel(c.reachedLabel, Vector2(x, reached.y), reached, text, h * LABEL, SIZE, ink, h * LABEL_LEAD, labelAlpha, gap = w * LEADER_GAP, hang = true)
        }
    }

    private companion object {
        const val SIZE = 200.0

        /**
         * Measured off the client's frame 2-08 at 1920x1080: plots 0.193 wide from 0.093 and 0.556,
         * 327 to 687 px down; headings at 0.066 and 0.528; bullets from 0.070 and 0.536.
         */
        const val LEFT_X = 0.093
        const val RIGHT_X = 0.556
        const val PLOT_W = 0.193
        const val PLOT_TOP = 0.3028
        const val PLOT_BOTTOM = 0.6361
        const val LEFT_HEAD = 0.066
        const val RIGHT_HEAD = 0.528
        const val HEAD_W = 0.42
        const val BAR = 0.88
        const val GRID_OVER = 0.0052
        const val AXIS_GAP = 0.0154
        const val UNIT_X = 0.514
        const val YEAR_Y = 0.0417
        const val LABEL_GAP = 0.0164
        const val LEADER_GAP = 0.0036
        const val LEFT_BULLETS = 0.0703
        const val RIGHT_BULLETS = 0.536
        const val BULLETS_Y = 0.7537
        const val INDENT = 0.0203
        const val MARK = 0.0148
        const val ITEM_GAP = 0.0148

        /** Sizes in pane heights: the title, headings and bullets matched to the frame's widths. */
        const val TITLE = 0.0405
        const val TITLE_X = 0.4755
        const val TITLE_Y = 0.0611
        const val TITLE_GAP = 0.0454
        const val HEADING = 0.0406
        const val HEADING_Y = 0.1963
        const val HEADING_LEAD = 0.0491
        const val BULLET = 0.0221
        const val BULLET_LEAD = 0.0269
        /** The frame sets these in a grotesque; in Rockwell they are matched by the height of a capital. */
        const val AXIS = 0.0197
        const val LABEL = 0.0189
        const val LABEL_LEAD = 0.0236
        const val LINE = 3.0
        const val DOT = 5.5

        const val LAG = 0.15
        const val LABEL_FROM = 0.6
    }
}
