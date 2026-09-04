package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.loadFont
import org.openrndr.shape.Rectangle
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
 */
class QuoteSlide(
    private val quote: String,
    private val fontPath: String = "data/fonts/default.otf",
    /** Break the quote over exactly this many lines; left off, it takes whatever fits. */
    private val lines: Int? = null
) : Slide() {
    override val name = "Quote"
    override val background = ColorRGBa.fromHex("3C3C3C")

    private val ink = ColorRGBa.WHITE
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

        drawer.fill = ink.opacify(opening)
        face.setToFit(quote, box, SIZE, LEADING, lines).draw(drawer, box.center)
    }

    private companion object {
        const val SIZE = 190.0

        /** Open, because this is a sentence being read rather than a heading being seen. */
        const val LEADING = 1.24

        /** How far the measure is held inside the pane, left and right. */
        const val INSET = 300.0

        /** And top and bottom, so the quote sits in air rather than filling the frame. */
        const val HEAD = 200.0
    }
}
