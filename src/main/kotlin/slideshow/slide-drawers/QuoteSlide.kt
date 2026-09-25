package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Palette
import slideshow.Slide
import slideshow.Stage
import slideshow.easeInOutCubic
import slideshow.frames

/**
 * A pull quote: one passage, centred on a grey ground, in the same serif the chapter cards
 * are set in.
 *
 * The quote is passed in rather than built in, so the slide is the *form* and the show
 * says the words — the same split as everywhere else here. Set with [setToFit], so a
 * longer or shorter quote is composed rather than clipped and rewriting one needs nothing
 * changed in this file.
 *
 * Unlike the chapter card this does **not** fill its frame. A quote is read, so it is set
 * to a measure with air around it: [INSET] holds it well inside the pane and the leading
 * is open, where the card's is tight. That is the difference between a heading and a
 * sentence, and it is the reason the two are separate drawers sharing one way of fitting
 * rather than one drawer with a flag.
 *
 * **Nothing stands above the passage.** A `lead` line carried the recap that re-entered a
 * chapter ("Voor de pauze keken we naar…") and the label over a takeaway ("Om mee te nemen"),
 * and both were taken out on 16 September: a title over a quote is read first and is not the
 * quote, so the passage stops being the only thing on the wall. What the recap said is said by
 * the speaker, which is where it belonged.
 */
class QuoteSlide(
    private val quote: String,
    private val fontPath: String = "data/fonts/default.otf",
    /** Break the quote over exactly this many lines; left off, it takes whatever fits. */
    private val lines: Int? = null,
    /**
     * Where the lines end, each the words a line ends on, in order; they win over [lines]. For
     * a quote no search sets well — the shortest measure that takes four lines can still leave a
     * short line with a gap after it — while the wording stays wherever it is said once.
     */
    private val breaks: List<String> = emptyList()
) : Slide() {
    override val name = "Quote"
    // Black like every slide: the grey concrete it stands on is the show's overlay, laid over the
    // whole wall, so the quote and the chapter card beside it are one ground by construction.
    override val background = Palette.onBlack.paper

    private val ink = Palette.onBlack.ink

    private val broken = brokenAt(quote, breaks)
    private lateinit var face: FontImageMap

    override fun load(program: Program) {
        face = program.loadFont(fontPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val opening = easeInOutCubic(stage.since(0, frames(0.8)))

        val box = Rectangle(
            INSET, HEAD,
            stage.width - 2 * INSET,
            stage.height - 2 * HEAD
        )

        // Ranged left, off the measure's own left edge, so the passage has one edge to come back
        // to. Centred, a quote of four ragged lines has no edge at all.
        drawer.stroke = null
        drawer.fill = ink.opacify(opening)
        face.setToFit(broken, box, SIZE, LEADING, lines)
            .draw(drawer, Vector2(box.x, box.center.y), align = 0.0)
    }

    /**
     * The quote's measure, public because the chapter wall sets its quote by the same numbers
     * ([LongShadowV3]'s second pane): where a quote stands and how open its lines are is this
     * slide's decision, and a second copy of it would drift.
     */
    companion object {
        const val SIZE = 190.0

        /** Open, because this is a sentence being read rather than a heading being seen. */
        const val LEADING = 1.24

        /** How far the measure is held inside the pane, left and right. */
        const val INSET = 300.0

        /** And top and bottom, so the quote sits in air rather than filling the frame. */
        const val HEAD = 200.0
    }
}

/**
 * [text] with a line ended after each of [breaks], in order — how [QuoteSlide] and the chapter
 * wall state a rag no search sets well. Each must be in the text, or the show would quietly set
 * the quote some other way.
 */
fun brokenAt(text: String, breaks: List<String>): String = breaks.fold(text) { t, end ->
    require(end in t) { "QuoteSlide: \"$end\" is not in the quote" }
    t.replaceFirst("$end ", "$end\n")
}
