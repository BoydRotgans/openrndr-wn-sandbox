package slideshow.backdrops

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Backdrop
import slideshow.Sound
import slideshow.Stage
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.setLine
import slideshow.drawers.setToFit
import slideshow.drawers.wrapped
import slideshow.frames
import slideshow.smoothstep
import kotlin.math.floor
import kotlin.math.min

/** One entry of the evening: a moment around the talk, or a chapter of it with its key message. */
class Entry(val label: String, val chapter: Boolean = false, val message: String = "")

/**
 * The programme of the evening, on the two projectors as two panes: **the left pane is the
 * programme as a list**, moments and chapters in the order the show plays them, the chapters
 * numbered and set large and the moments small and quieter between them; **the right pane is the
 * chapter that is forward** — its number and its key message — and, before any is and after the
 * last, the title. A click a chapter; the last click settles the whole line.
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
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    private val quiet: ColorRGBa = ColorRGBa.fromHex("D9D9D9"),
    private val accent: ColorRGBa = ColorRGBa.fromHex("FF0000"),
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val stepFrames: Int = frames(0.8),
    override val sound: Sound? = null
) : Backdrop() {

    override val name = "Programme"
    private val chapters = entries.indices.filter { entries[it].chapter }
    override val steps get() = chapters.size + 2
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

        // --- the left pane: the list ------------------------------------------------ //
        val weights = entries.map { if (it.chapter) CHAPTER else 1.0 }
        val unit = h * (LIST_BOTTOM - LIST_TOP) / weights.sum()
        var y = h * LIST_TOP
        var number = 0
        entries.forEachIndexed { i, e ->
            val rowH = weights[i] * unit
            val built = if (opening) smoothstep(stage.since(i * frames(BUILD) / entries.size, frames(ARRIVE))) else 1.0
            fun dimAt(f: Int) = if (f < 0 || f == i) 1.0 else DIM
            val dim = dimAt(fa) + (dimAt(fb) - dimAt(fa)) * t
            val x = pane * MARGIN
            if (built > 0.0) {
                if (e.chapter) {
                    number++
                    val baseline = y + rowH * 0.5 + h * CHAPTER_SIZE * 0.34
                    drawer.fill = accent.opacify(built * dim)
                    drawer.setLine(number.toString(), bold, Vector2(x, baseline), h * CHAPTER_SIZE, SIZE)
                    drawer.fill = ink.opacify(built * dim)
                    drawer.setLine(e.label, bold, Vector2(x + pane * NUMBER_W, baseline), h * CHAPTER_SIZE, SIZE)
                } else {
                    drawer.fill = quiet.opacify(built * dim * QUIET)
                    drawer.setLine(e.label, text, Vector2(x + pane * NUMBER_W, y + rowH * 0.5 + h * MOMENT * 0.34), h * MOMENT, SIZE)
                }
            }
            y += rowH
        }

        // --- the right pane: the title, or the chapter that is forward ---------------- //
        val right = Rectangle(pane + pane * MARGIN, h * RIGHT_TOP, pane * (1.0 - 2.0 * MARGIN), h * (RIGHT_BOTTOM - RIGHT_TOP))
        fun titleCard(alpha: Double) {
            if (alpha <= 0.0) return
            drawer.fill = ink.opacify(alpha)
            bold.setToFit(title, right, SIZE, 1.1, 1).draw(drawer, right.center)
        }
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
        val titleAlpha = if (opening) smoothstep(stage.since(0, frames(ARRIVE))) else 1.0
        if (fa == fb) {
            if (fa < 0) titleCard(titleAlpha) else message(fa, 1.0)
        } else {
            if (fa < 0) titleCard(out) else message(fa, out)
            if (fb < 0) titleCard(back) else message(fb, back)
        }
    }

    private companion object {
        const val SIZE = 200.0
        const val MARGIN = 0.05
        const val CHAPTER = 2.0
        const val LIST_TOP = 0.12
        const val LIST_BOTTOM = 0.9
        const val NUMBER_W = 0.06
        const val CHAPTER_SIZE = 0.046
        const val MOMENT = 0.026
        const val QUIET = 0.7
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
