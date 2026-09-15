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
import slideshow.linear
import slideshow.smoothstep

/**
 * One bar chart: its heading, a bar a year, the axis it stands on, an optional target line
 * across the same years, what to say at the end of the line and at the top of the last bar,
 * and the bullets under it.
 */
class BarChart(
    val heading: String,
    val years: List<String>,
    val values: List<Double>,
    val max: Double,
    val step: Double,
    val target: List<Double> = emptyList(),
    /** Set at the end of the target line, in the line's colour. */
    val targetLabel: List<String> = emptyList(),
    /** Set on a leader from the top of the last bar. */
    val reachedLabel: List<String> = emptyList(),
    val bullets: List<String> = emptyList()
)

/**
 * The numbers behind the ladder: two bar charts side by side, and the bullets under each.
 *
 *     0  the left chart's bars, growing from the axis on the slide's clock, one after another
 *     1  the target line draws across them, and the labels at its end and the last bar's top
 *     2  the right chart's bars, and its label
 *     3  the bullets, one after another under both
 *
 * Bars grow from their baseline on the deck's own ease and the line draws across on it; the
 * bullets are counted, so they run on `linear(on(3))` — the city's distinction. Type sizes are
 * the ladder's, since it stands right after it.
 */
class CarbonCharts(
    private val title: String,
    private val left: BarChart,
    private val right: BarChart,
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    private val bar: ColorRGBa = ColorRGBa.fromHex("FF0000"),
    private val line: ColorRGBa = ColorRGBa.fromHex("4674D6"),
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    private val grey: ColorRGBa = ColorRGBa.fromHex("D9D9D9"),
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

        drawer.fill = ink
        drawer.setLine(title, bold, Vector2(w / 2.0, h * TITLE_Y), h * TITLE, SIZE, align = 0.5)

        val opening = stage.step == 0 && stage.position < 1e-6
        val leftGrown = if (opening) smoothstep(stage.since(0, stepFrames * 2)) else 1.0
        chart(drawer, left, Rectangle(w * LEFT_X, h * PLOT_TOP, w * PLOT_W, h * (PLOT_BOTTOM - PLOT_TOP)), w * LEFT_HEAD, 1.0, leftGrown, stage.on(1), h, w)
        chart(drawer, right, Rectangle(w * RIGHT_X, h * PLOT_TOP, w * PLOT_W, h * (PLOT_BOTTOM - PLOT_TOP)), w * RIGHT_HEAD, stage.on(2), stage.on(2), stage.on(2), h, w)

        // The bullets, counted out under both charts in turn.
        val counted = linear(stage.on(3))
        val all = left.bullets.size + right.bullets.size
        drawer.bullets(
            left.bullets, Vector2(w * LEFT_HEAD, h * BULLETS_Y), w * BULLET_W, text, h * TEXT, SIZE, h * LEAD,
            ink, line, h * MARK, w * INDENT, h * ITEM_GAP
        ) { i -> staggered(counted, i, all, LAG) }
        drawer.bullets(
            right.bullets, Vector2(w * RIGHT_HEAD, h * BULLETS_Y), w * BULLET_W, text, h * TEXT, SIZE, h * LEAD,
            ink, line, h * MARK, w * INDENT, h * ITEM_GAP
        ) { i -> staggered(counted, left.bullets.size + i, all, LAG) }
    }

    /** One chart in [plot]: heading at [headX], bars grown by [grown], the line and labels by [lined]. */
    private fun chart(drawer: Drawer, c: BarChart, plot: Rectangle, headX: Double, shown: Double, grown: Double, lined: Double, h: Double, w: Double) {
        if (shown <= 0.0) return
        drawer.fill = ink.opacify(shown)
        bold.wrapped(c.heading, (w * HEAD_W) * SIZE / (h * HEADING)).forEachIndexed { i, l ->
            drawer.setLine(l, bold, Vector2(headX, h * HEADING_Y + i * h * HEADING_LEAD), h * HEADING, SIZE)
        }
        drawer.axis(plot, c.max, c.step, text, h * AXIS, SIZE, grey, alpha = 0.8 * shown)

        val n = c.years.size
        val slot = plot.width / n
        val width = slot * BAR
        val tops = c.values.mapIndexed { i, v ->
            val rect = Rectangle(plot.x + i * slot + (slot - width) / 2.0, plot.y + plot.height * (1.0 - v / c.max), width, plot.height * v / c.max)
            val g = staggered(grown, i, n, LAG)
            drawer.fill = bar.opacify(shown)
            drawer.rectangle(grownFromBase(rect, g))
            drawer.fill = grey.opacify(shown)
            drawer.setLine(c.years[i], text, Vector2(rect.center.x, plot.y + plot.height + h * YEAR_Y), h * AXIS, SIZE, align = 0.5)
            Vector2(rect.center.x, rect.y)
        }

        // The target line, drawn across from the first year, a dot at each; then the labels.
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
            val labelAlpha = ((lined - LABEL_FROM) / (1.0 - LABEL_FROM)).coerceIn(0.0, 1.0)
            val x = plot.x + plot.width + w * LABEL_GAP
            drawer.leaderLabel(c.targetLabel, Vector2(x, points.last().y), points.last(), text, h * TEXT, SIZE, line, h * LEAD, labelAlpha, gap = w * LEADER_GAP)
            val reached = tops.last()
            val ty = maxOf(reached.y, points.last().y + (c.targetLabel.size + c.reachedLabel.size) * h * LEAD / 2.0 + h * LEAD * 0.4)
            drawer.leaderLabel(c.reachedLabel, Vector2(x, ty), reached, text, h * TEXT, SIZE, ink, h * LEAD, labelAlpha, gap = w * LEADER_GAP)
        } else if (c.reachedLabel.isNotEmpty() && lined > 0.0) {
            val labelAlpha = ((lined - LABEL_FROM) / (1.0 - LABEL_FROM)).coerceIn(0.0, 1.0)
            val top = tops.last()
            drawer.leaderLabel(c.reachedLabel, Vector2(plot.x + plot.width + w * LABEL_GAP, top.y), top, text, h * TEXT, SIZE, ink, h * LEAD, labelAlpha, gap = w * LEADER_GAP)
        }
    }

    private companion object {
        const val SIZE = 200.0

        /** Measured off the frame: two plots 0.2 wide from 0.095 and 0.555, 0.30 to 0.64 down; headings at 0.065 and 0.527. */
        const val LEFT_X = 0.095
        const val RIGHT_X = 0.555
        const val PLOT_W = 0.20
        const val PLOT_TOP = 0.30
        const val PLOT_BOTTOM = 0.64
        const val LEFT_HEAD = 0.065
        const val RIGHT_HEAD = 0.527
        const val HEAD_W = 0.40
        const val BAR = 0.86
        const val YEAR_Y = 0.035
        const val LABEL_GAP = 0.03
        const val LEADER_GAP = 0.008
        const val BULLETS_Y = 0.72
        const val BULLET_W = 0.44
        const val INDENT = 0.024
        const val MARK = 0.016
        const val ITEM_GAP = 0.008

        const val TITLE = 0.036
        const val TITLE_Y = 0.06
        const val HEADING = 0.036
        const val HEADING_Y = 0.19
        const val HEADING_LEAD = 0.045
        const val AXIS = 0.022
        const val TEXT = 0.026
        const val LEAD = 0.032
        const val LINE = 2.0
        const val DOT = 5.0

        const val LAG = 0.15
        const val LABEL_FROM = 0.6
    }
}
