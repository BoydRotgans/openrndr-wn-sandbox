package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.frames
import slideshow.smoothstep
import kotlin.math.min

/** One rung of the ladder: its label, set bold, and what it stands for, set regular. */
class Rung(val label: String, val text: String)

/**
 * The CO₂-prestatieladder as a staircase: white rungs on black, each standing on the one
 * below and a run to the right of it, so the three climb across the pane from bottom-left
 * to top-right. Read off `data/ref/CO₂-prestatieladder.pdf`, whose four frames are the four
 * states here — the first rung, the second, the third, and then the certificate.
 *
 * **Each rung arrives by taking its step.** It comes in from a run to the *left* of where
 * it will stand — which is directly above the rung below it — and slides right into place
 * as it fades up, so what the click shows is the ladder gaining a step rather than a block
 * appearing. The travel is the deck's own eased `on(n)`, undoubled, and the fade is the
 * same number, so the two cannot come apart. The first rung has no click to arrive on, the
 * slide opening on a cut, so it takes its step on the slide's own clock over one click's
 * length — and only while the slide is still on its first state, so stepping back into it
 * from the next slide finds the ladder built rather than the bottom rung sliding in again
 * under the finished top.
 *
 * **The rungs are fitted to the frame, not clipped by it.** Three runs of 0.148 carry the
 * top rung's right edge past the pane, and the reference keeps it inside a small margin by
 * drawing that rung narrower. So a rung's width is the lesser of the ladder's width and
 * what is left to the margin from where it *rests* — from where it rests and not from where
 * it is, or it would grow as it slid.
 *
 * **The certificate points, it does not decorate.** The last click sets the note left of the
 * rung it is about and runs a hairline from the note to the rung's edge, growing from the
 * words toward the rung on the same number the words fade up on, so the line reaches the
 * rung as the note reaches full strength.
 *
 * The subscript in CO₂ is set rather than asked for — see [setLine] and why Rockwell cannot
 * be asked.
 */
class CarbonLadder(
    private val title: String = "CO₂-prestatieladder",
    /** The rungs, bottom first: each arrives on its own click, the first on the slide's clock. */
    private val rungs: List<Rung> = listOf(
        Rung("Trede 1:", "CO₂-reductie in de eigen organisatie"),
        Rung("Trede 2:", "CO₂-reductie in de keten"),
        Rung("Trede 3:", "CO₂-reductie naar nul in 2050")
    ),
    /** The note the last click adds, a line each, left of the rung it points at. Empty for none. */
    private val note: List<String> = listOf("De Willy Naessens Group", "is gecertificeerd op Trede 3"),
    /** Which rung the note points at, counting from the bottom. */
    private val noted: Int = 2,
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    /** The rungs, and the lettering on the ground; the lettering on a rung is the ground's colour. */
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    /**
     * How far a rung slides in from, as a share of the pane's width. One run by default, so a
     * rung starts directly over the one below it and takes its step.
     */
    private val slide: Double = 0.148,
    override val stepFrames: Int = frames(0.8),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "Ladder"

    /** A click a rung after the first, and one for the note. */
    override val steps get() = rungs.size + (if (note.isEmpty()) 0 else 1)

    override fun stepName(step: Int): String? = when {
        step in 1 until rungs.size -> rungs[step].label.trimEnd(':', ' ')
        step == rungs.size && note.isNotEmpty() -> "the certificate"
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
        drawer.setLine(title, bold, Vector2(w * TITLE_X, h * TITLE_Y), h * TITLE, SIZE)

        val rungHeight = h * RUNG
        val rise = h * (RUNG + GAP)
        val run = w * RUN

        /** Where rung [k] rests: its left edge and its top. */
        fun restX(k: Int) = w * LEFT + k * run
        fun top(k: Int) = h * (1.0 - FOOT) - rungHeight - k * rise

        rungs.forEachIndexed { k, rung ->
            val shown = when {
                k > 0 -> stage.on(k)
                stage.step > 0 -> 1.0
                else -> smoothstep(stage.since(0, stepFrames))
            }
            if (shown <= 0.0) return@forEachIndexed

            val x = restX(k) - w * slide * (1.0 - shown)
            val y = top(k)
            val width = min(w * WIDTH, w * (1.0 - RIGHT) - restX(k))

            drawer.fill = ink.opacify(shown)
            drawer.rectangle(x, y, width, rungHeight)

            drawer.fill = background.opacify(shown)
            drawer.setLine(rung.label, bold, Vector2(x + w * INSET, y + h * LABEL_Y), h * TEXT, SIZE)
            drawer.setLine(rung.text, text, Vector2(x + w * INSET, y + h * TEXT_Y), h * TEXT, SIZE)
        }

        if (note.isEmpty()) return
        val on = stage.on(rungs.size)
        if (on <= 0.0) return

        val k = noted.coerceIn(rungs.indices)
        val y = top(k)
        val x = w * NOTE_X
        drawer.fill = ink.opacify(on)
        note.forEachIndexed { i, line ->
            drawer.setLine(line, text, Vector2(x, y + h * (NOTE_Y + i * NOTE_LEAD)), h * TEXT, SIZE)
        }

        // From just past the note's widest line to the rung's edge, reaching it as the note
        // reaches full strength.
        val widest = note.maxOf { text.advanceWithSubscripts(it) } * (h * TEXT / SIZE)
        val from = Vector2(x + widest + w * NOTE_GAP, y + h * NOTE_LINE_Y)
        val to = Vector2(restX(k), from.y)
        drawer.stroke = ink.opacify(on)
        drawer.strokeWeight = LINE
        drawer.lineSegment(from, from + (to - from) * on)
        drawer.stroke = null
    }

    private companion object {
        const val SIZE = 200.0

        // Measured off the reference, as shares of the pane: the rungs 0.269 tall with a
        // 0.009 gap, standing 0.148 further right each, the bottom one 0.0245 in from the
        // left and 0.037 up from the foot, and the top one held 0.0135 short of the right.
        const val RUNG = 0.269
        const val GAP = 0.0093
        const val RUN = 0.148
        const val WIDTH = 0.696
        const val LEFT = 0.0245
        const val RIGHT = 0.0135
        const val FOOT = 0.037

        /**
         * The lettering: the title top-left, and on a rung the label over its text. The sizes
         * are ems, and were first read off the reference's word boxes at 1.25 em a box — a
         * third too large: those boxes are 1.6 em tall, and the widths are what settle it, the
         * rung's line spanning 570 of the 1920.
         */
        const val TITLE = 0.041
        const val TITLE_X = 0.02
        const val TITLE_Y = 0.055
        const val TEXT = 0.0287
        const val INSET = 0.0104
        const val LABEL_Y = 0.045
        const val TEXT_Y = 0.079

        /** The note: at the pane's left, its lines from the rung's top, and its line a little under its middle. */
        const val NOTE_X = 0.017
        const val NOTE_Y = 0.023
        const val NOTE_LEAD = 0.035
        const val NOTE_LINE_Y = 0.031
        const val NOTE_GAP = 0.012
        const val LINE = 2.0
    }
}
