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
import kotlin.math.floor
import kotlin.math.min

/**
 * One reading of the column: its title, the bands from the foot up, and what stands beside or
 * inside it. A caption is set into the lowest band, which is where the reference puts it.
 */
class ColumnState(
    val title: String,
    val bands: List<Band>,
    val caption: String = ""
)

/**
 * One stacked column, read several times: the make-up of concrete's carbon footprint, then
 * concrete's share of the world's emissions, then of the Netherlands' — the same column at every
 * state, and the states differ only in what its bands are.
 *
 * **A band is a slot keyed by name, and a slot's value in a state it is absent from is zero.**
 * So the column is always a stack of every key the states between them mention, in the order
 * they are first mentioned, and a click is the values moving: the cement band that is 80 of the
 * first reading shrinks to the 7 of the second under the same key, while the four blue bands over
 * it close to nothing and the one blue band of the second reading opens above. Nothing fades and
 * nothing is swapped — a band leaves by having no height, which the stack closes over by itself.
 * `stackedFrom` in [ChartKit] is that stack, and it is `packBoxes` and `stateAt` again.
 *
 * **Labels ride their bands, and are kept apart.** Each band's label stands to the right on a
 * leader to the band's middle, fading with the band's height so a band of nothing says nothing;
 * thin bands stacked together would set their labels over one another, so the labels are pushed
 * down a line at a time from the top until none overlap, and the leaders go where the labels went.
 * A label that changes on a slot crossfades on the ladder's rule; the title crossfades straight,
 * because every reading begins the same way and only the ending is seen to change.
 *
 * The first reading builds on the slide's own clock, the whole stack growing from the foot.
 */
class Co2Column(
    private val states: List<ColumnState>,
    private val total: Double = 100.0,
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    private val grey: ColorRGBa = ColorRGBa.fromHex("D9D9D9"),
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val stepFrames: Int = frames(0.9),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "CO2 column"
    override val steps get() = states.size
    override fun stepName(step: Int): String? = states.getOrNull(step)?.title

    private lateinit var bold: FontImageMap
    private lateinit var text: FontImageMap

    /** Every key any state mentions, in the order first mentioned — the stack's order, foot up. */
    private val keys: List<String> = states.flatMap { s -> s.bands.map { it.key } }.distinct()

    override fun load(program: Program) {
        bold = program.loadFont(boldPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        text = program.loadFont(textPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        if (states.isEmpty()) return
        val w = stage.width
        val h = stage.height

        val p = stage.position.coerceIn(0.0, (states.size - 1).toDouble())
        val a = floor(p).toInt()
        val b = min(a + 1, states.size - 1)
        val t = p - a
        val from = states[a]
        val to = states[b]
        val out = (1.0 - t / FADE).coerceIn(0.0, 1.0)
        val back = ((t - (1.0 - FADE)) / FADE).coerceIn(0.0, 1.0)

        // The first reading builds itself, the stack growing from the foot.
        val built = if (a == 0 && stage.step == 0 && p < 1e-6) smoothstep(stage.since(0, stepFrames)) else 1.0

        drawer.stroke = null

        // The title. Out and back rather than a straight crossfade: the readings share a prefix,
        // but a centred title of another length stands the prefix somewhere else, so filmed
        // straight the two lay over each other mid-click as a smear.
        if (from.title == to.title) {
            drawer.fill = ink
            drawer.setLine(to.title, bold, Vector2(w / 2.0, h * TITLE_Y), h * TITLE, SIZE, align = 0.5)
        } else {
            drawer.fill = ink.opacify(out)
            drawer.setLine(from.title, bold, Vector2(w / 2.0, h * TITLE_Y), h * TITLE, SIZE, align = 0.5)
            drawer.fill = ink.opacify(back)
            drawer.setLine(to.title, bold, Vector2(w / 2.0, h * TITLE_Y), h * TITLE, SIZE, align = 0.5)
        }

        val box = Rectangle(w * LEFT, h * TOP, w * (RIGHT - LEFT), h * (BOTTOM - TOP))
        drawer.axis(box, total, total / 10.0, text, h * AXIS, SIZE, grey, alpha = 0.8)

        // Every slot's value this frame, and the stack it makes.
        val fa = from.bands.associateBy { it.key }
        val fb = to.bands.associateBy { it.key }
        val values = keys.map { k -> ((fa[k]?.value ?: 0.0) + ((fb[k]?.value ?: 0.0) - (fa[k]?.value ?: 0.0)) * t) * built }
        val rects = stackedFrom(box, values, total)

        keys.forEachIndexed { i, k ->
            val band = fb[k] ?: fa[k] ?: return@forEachIndexed
            val colour = fa[k]?.colour?.let { c -> fb[k]?.colour?.let { d -> c.mix(d, t) } ?: c } ?: band.colour
            drawer.fill = colour
            drawer.rectangle(rects[i])
        }

        // The labels: each at its band's middle, then pushed apart from the top down.
        val size = h * LABEL
        val lead = h * LEAD
        val x = box.x + box.width + w * LABEL_GAP
        val measure = w * (1.0 - EDGE) - x
        class Tag(val lines: List<String>, val y: Double, val alpha: Double, val to: Vector2)
        val tags = mutableListOf<Tag>()
        keys.forEachIndexed { i, k ->
            val r = rects[i]
            // A band a few pixels high still has its label in full — a 1% band is a real band —
            // and a band leaving on a click fades its label on the ladder's rule below instead.
            val present = (r.height / (lead * 0.15)).coerceIn(0.0, 1.0)
            if (present <= 0.0) return@forEachIndexed
            val la = fa[k]?.label ?: ""
            val lb = fb[k]?.label ?: ""
            val mid = Vector2(r.x + r.width, r.y + r.height / 2.0)
            if (la == lb) {
                if (la.isNotEmpty()) tags += Tag(bold.wrapped(la, measure * SIZE / size), mid.y, present, mid)
            } else {
                if (la.isNotEmpty() && out > 0.0) tags += Tag(bold.wrapped(la, measure * SIZE / size), mid.y, present * out, mid)
                if (lb.isNotEmpty() && back > 0.0) tags += Tag(bold.wrapped(lb, measure * SIZE / size), mid.y, present * back, mid)
            }
        }
        // Top down — the stack is foot up, so the last tags are the highest.
        val ordered = tags.sortedBy { it.y }
        val ys = DoubleArray(ordered.size)
        var floorY = h * TOP
        ordered.forEachIndexed { i, tag ->
            val half = (tag.lines.size - 1) * lead / 2.0
            ys[i] = maxOf(tag.y, floorY + half)
            floorY = ys[i] + half + lead
        }
        ordered.forEachIndexed { i, tag ->
            drawer.leaderLabel(tag.lines, Vector2(x, ys[i]), tag.to, bold, size, SIZE, ink, lead, tag.alpha, align = 0.0, gap = w * LEADER_GAP)
        }

        // The caption, beside the column in the clear space under the labels — never on a band,
        // which is where it sat while the column took 57% of the pane.
        fun caption(para: String, alpha: Double) {
            if (para.isEmpty() || alpha <= 0.0) return
            val lines = text.wrapped(para, measure * SIZE / (h * TEXT))
            val top = h * CAPTION_Y
            drawer.fill = ink.opacify(alpha)
            lines.forEachIndexed { j, line -> drawer.setLine(line, text, Vector2(x, top + j * h * TEXT_LEAD), h * TEXT, SIZE) }
        }
        if (from.caption == to.caption) caption(to.caption, built)
        else {
            caption(from.caption, out)
            caption(to.caption, back)
        }
    }

    private companion object {
        const val SIZE = 200.0

        /**
         * The column: a third of the pane across (it was 0.088 to 0.653, a flat red plane over
         * most of the pane), 0.134 to 0.954 down, with the labels and the paragraph in the clear
         * half beside it.
         */
        const val LEFT = 0.088
        const val RIGHT = 0.40
        const val TOP = 0.134
        const val BOTTOM = 0.954
        const val EDGE = 0.02

        const val TITLE = 0.036
        const val TITLE_Y = 0.062
        const val AXIS = 0.022
        const val LABEL = 0.028
        const val LEAD = 0.034
        const val LABEL_GAP = 0.03
        const val LEADER_GAP = 0.008
        const val TEXT = 0.0287
        const val TEXT_LEAD = 0.035
        /** Where the paragraph's first baseline sits, below the lowest label the bands can push to. */
        const val CAPTION_Y = 0.74

        const val FADE = 0.3
    }
}
