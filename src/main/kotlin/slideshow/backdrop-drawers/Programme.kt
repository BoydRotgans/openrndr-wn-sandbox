package slideshow.backdrops

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Arrival
import slideshow.Backdrop
import slideshow.Palette
import slideshow.Sound
import slideshow.Stage
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.setLine
import slideshow.drawers.wrapped
import slideshow.frames
import slideshow.pitchStep
import slideshow.smoothstep
import kotlin.math.floor
import kotlin.math.min

/** One entry of the evening: a moment around the talk, or a chapter of it with its key message. */
class Entry(val label: String, val chapter: Boolean = false, val message: String = "")

/**
 * The programme of the evening, on the two projectors as two panes: **the left pane is the
 * programme as a menu**, the four chapters numbered and set large in the order the show plays
 * them, the courses and moments between them as small quiet labels; **the right pane is the
 * chapter that is forward**, its number and its key message. Before any chapter is forward the right pane is
 * empty, since the list is the title; after the last it carries the title alone, large, as the
 * name tag carries the speaker's name (review of 22 September: the four messages stacked there
 * were a lot of text, and read as the list said twice). A click a chapter; the last click settles the whole.
 *
 * It carried a large "Het programma" wordmark in the right pane once, and dropped it on 16
 * September: a list of the evening does not need to be told what it is. [title] is set small
 * above the list instead, as a heading.
 *
 * Two panes rather than one line across the wall, because the wall is two projectors meeting at
 * the middle and a line of type that runs from one into the other breaks at the seam. Nothing
 * here crosses it: the list stays in the left 1920 and the message in the right.
 *
 * The list builds top to bottom on the wall's own clock when the slide comes up. The layout is a
 * function of the list — a chapter row takes two shares of the height and a moment one.
 */
class Programme(
    private val title: String = "Het programma",
    private val entries: List<Entry>,
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    private val ink: ColorRGBa = Palette.onBlack.ink,
    private val quiet: ColorRGBa = Palette.onBlack.quiet,
    private val accent: ColorRGBa = Palette.onBlack.accent,
    override val background: ColorRGBa = Palette.onBlack.paper,
    override val stepFrames: Int = frames(0.8),
    override val sound: Sound? = null,
    /**
     * The one pane the list stands in, with nothing on the other: null is the list left and the
     * chapter's message right. The intro wall stands the list alone in the right projector beside
     * the speaker's name, a click a chapter bringing it forward among the others dimmed.
     */
    val side: Int? = null
) : Backdrop() {

    override val name = "Programme"
    private val chapters = entries.indices.filter { entries[it].chapter }
    override val steps get() = chapters.size + 2

    /**
     * A note an entry as the evening writes itself out, and one a chapter as it is brought
     * forward. Two lanes, because the two are different things: the list arrives once, on the
     * wall's own clock, where the chapters come round on the speaker's clicks.
     *
     * The list's rule is `draw`'s own — every entry inside `BUILD`, so the whole evening lands
     * in a second and the run is dense — and the count follows the show rather than being
     * stated, so an entry added to `Slideshow.kt` is a note without anything else changing.
     */
    override val lanes: List<String> get() = listOf("the evening", "the chapters")

    override fun arrivals(clicks: List<Int>): List<Arrival> {
        val n = entries.size
        if (n <= 0) return super.arrivals(clicks)
        val written = entries.indices.map { i ->
            Arrival(lane = 0, index = pitchStep(i, n), start = i * frames(BUILD) / n, length = frames(ARRIVE))
        }
        val forward = clicks.mapIndexed { k, at -> Arrival(lane = 1, index = k, start = at, length = stepLength(k + 1)) }
        return written + forward
    }
    override val settle get() = frames(BUILD) + frames(ARRIVE)
    override fun stepName(step: Int): String? = when {
        step in 1..chapters.size -> entries[chapters[step - 1]].label
        step == chapters.size + 1 -> "the whole programme"
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
        val pane = w / 2.0
        drawer.stroke = null
        if (entries.isEmpty()) return

        val p = stage.position.coerceIn(0.0, (steps - 1).toDouble())
        val a = floor(p).toInt()
        val b = min(a + 1, steps - 1)
        val t = p - a
        val out = (1.0 - t / FADE).coerceIn(0.0, 1.0)
        val back = ((t - (1.0 - FADE)) / FADE).coerceIn(0.0, 1.0)
        val opening = stage.step == 0 && p < 1e-6

        // Which chapter is forward at state s: none at 0 and at the last.
        fun forward(s: Int) = if (s in 1..chapters.size) chapters[s - 1] else -1
        val fa = forward(a); val fb = forward(b)

        // --- the menu, in the left pane or in [side] -------------------------------------- //
        val x = pane * (side ?: 0) + pane * MARGIN
        val headed = if (opening) smoothstep(stage.since(0, frames(ARRIVE))) else 1.0
        // The small heading gives way to the large title on the right at the end.
        val lastState = chapters.size + 1
        fun headingAt(s: Int) = if (s == lastState && side == null) 0.0 else 1.0
        val heading = headingAt(a) + (headingAt(b) - headingAt(a)) * t
        drawer.fill = quiet.opacify(headed * QUIET * heading)
        drawer.setLine(title, text, Vector2(x, h * HEADING_Y), h * MOMENT, SIZE)

        val weights = entries.map { if (it.chapter) CHAPTER else 1.0 }
        val unit = h * (LIST_BOTTOM - LIST_TOP) / weights.sum()
        var y = h * LIST_TOP
        var number = 0
        entries.forEachIndexed { i, e ->
            val rowH = weights[i] * unit
            val built = if (opening) smoothstep(stage.since(i * frames(BUILD) / entries.size, frames(ARRIVE))) else 1.0
            fun dimAt(f: Int) = if (f < 0 || f == i) 1.0 else DIM
            val dim = dimAt(fa) + (dimAt(fb) - dimAt(fa)) * t
            if (built > 0.0) {
                if (e.chapter) {
                    number++
                    val baseline = y + rowH * 0.5 + h * CHAPTER_SIZE * 0.34
                    drawer.fill = accent.opacify(built * dim)
                    drawer.setLine(number.toString(), bold, Vector2(x, baseline), h * CHAPTER_SIZE, SIZE)
                    drawer.fill = ink.opacify(built * dim)
                    drawer.setLine(e.label, bold, Vector2(x + pane * NUMBER_W, baseline), h * CHAPTER_SIZE, SIZE)
                } else {
                    // A course: its name small and quiet, and nothing else. It carried a rule
                    // running out to the edge of the list for a day — a menu card's divider —
                    // and it was taken off on 16 September: a dozen long lines down the pane
                    // read as ruling rather than as the quiet spacing the courses want.
                    val size = h * MOMENT
                    val baseline = y + rowH * 0.5 + size * 0.34
                    drawer.fill = quiet.opacify(built * dim * QUIET)
                    drawer.setLine(e.label, text, Vector2(x + pane * NUMBER_W, baseline), size, SIZE)
                }
            }
            y += rowH
        }

        // --- the right pane: the chapter that is forward, or at the end the whole evening -- //
        if (side != null) return
        val right = Rectangle(pane + pane * MARGIN, h * RIGHT_TOP, pane * (1.0 - 2.0 * MARGIN), h * (RIGHT_BOTTOM - RIGHT_TOP))
        val last = chapters.size + 1
        fun message(f: Int, alpha: Double) {
            if (f < 0 || alpha <= 0.0) return
            val e = entries[f]
            val n = chapters.indexOf(f) + 1
            drawer.fill = accent.opacify(alpha)
            drawer.setLine(n.toString(), bold, Vector2(right.x, right.y + h * NUMBER_BIG), h * NUMBER_BIG, SIZE)
            drawer.fill = ink.opacify(alpha)
            text.wrapped(e.message, right.width * SIZE / (h * MESSAGE)).forEachIndexed { j, line ->
                drawer.setLine(line, text, Vector2(right.x, right.y + h * (MESSAGE_Y + j * MESSAGE_LEAD)), h * MESSAGE, SIZE)
            }
        }
        // The whole evening: the list on the left says it all, so the right pane carries only the
        // title, set as the name tag sets the speaker's name — large, ranged left, level with the
        // middle. It carried all four messages stacked once, which was the list read twice.
        fun whole(alpha: Double) {
            if (alpha <= 0.0) return
            val size = h * TITLE_BIG
            drawer.fill = ink.opacify(alpha)
            drawer.setLine(title, bold, Vector2(right.x, h * 0.5 + size * 0.34), size, SIZE)
        }
        fun rightAt(s: Int, alpha: Double) = when {
            s in 1..chapters.size -> message(chapters[s - 1], alpha)
            s == last -> whole(alpha)
            else -> Unit
        }
        if (a == b) rightAt(a, 1.0) else { rightAt(a, out); rightAt(b, back) }
    }

    private companion object {
        const val SIZE = 200.0
        const val MARGIN = 0.05
        const val CHAPTER = 2.0
        const val LIST_TOP = 0.16
        const val LIST_BOTTOM = 0.9
        /** The heading over the list, and the list's own top. */
        const val HEADING_Y = 0.1
        const val NUMBER_W = 0.06
        /** The chapters at slide-title size on the wall, so the menu's courses read against them. */
        const val CHAPTER_SIZE = 0.056
        const val MOMENT = 0.026
        const val QUIET = 0.7
        /** The title on the right pane at the end: the name tag's name size. */
        const val TITLE_BIG = 0.095
        const val DIM = 0.3
        const val RIGHT_TOP = 0.2
        const val RIGHT_BOTTOM = 0.8
        const val NUMBER_BIG = 0.16
        const val MESSAGE = 0.05
        const val MESSAGE_Y = 0.26
        const val MESSAGE_LEAD = 0.062
        const val FADE = 0.3
        const val BUILD = 1.0
        const val ARRIVE = 0.5
    }
}
