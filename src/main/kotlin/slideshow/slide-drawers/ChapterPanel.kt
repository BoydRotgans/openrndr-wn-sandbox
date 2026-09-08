package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.loadFont
import org.openrndr.shape.Rectangle
import slideshow.Cut
import slideshow.Section
import slideshow.Slide
import slideshow.Stage
import slideshow.frames

/**
 * The left pane: the chapter, set as large as the card will carry — white on black,
 * centred, broken over as many lines as that takes.
 *
 * One card per section, built by [slideshow.ShowBuilder.panel] whenever the chapter or
 * subchapter changes, and left standing while the slides beside it are clicked through —
 * so it only ever changes when the show moves to another section, never on an ordinary
 * click. [Section] is read off the show's own structure in `Slideshow.kt`, so nothing here
 * needs telling twice what the chapters already say.
 *
 * **It stands on the left and stays there.** One step, so it is simply the left pane for as
 * long as the show is in its section — no entrance across the frame, nothing for the show to
 * click past before the first slide of a chapter can be seen.
 *
 * The engine still carries the other arrangement, and a card opts into it by declaring
 * **two** steps: it then arrives over the slide pane holding the whole right-hand frame, and
 * the first click carries it across to the left. That move cannot be drawn here — a pane is
 * 1920 wide and the card would have to cross 3840 — so `present` reads `stage.on(1)` off the
 * card and slides the finished pane with it. See "two panes" in CLAUDE.md.
 *
 * The type fades up over [FADE] as the card arrives, which is the one eased thing on it.
 * The card still cuts between chapters — a heading is *there* rather than dissolved into —
 * but arriving on a black frame with nothing else to look at, an instant title reads as a
 * flash where a fade reads as a title card.
 *
 * The setting is [setToFit] — see `TypeBlock.kt` for how the size and the breaks are
 * found, and why [lines] is worth being able to state.
 */
class ChapterPanel(
    private val section: Section,
    private val fontPath: String = "data/fonts/default.otf",
    /** Break the chapter over exactly this many lines, rather than over however many let it be biggest. */
    private val lines: Int? = null
) : Slide() {
    override val name get() = section.chapter.ifBlank { "Panel" }
    override val background = ColorRGBa.BLACK
    override val transition = Cut

    private val ink = ColorRGBa.WHITE
    private lateinit var face: FontImageMap

    override fun load(program: Program) {
        Type.load(program)
        // Loaded once, large, and scaled *down* to fit: a glyph atlas enlarged past its
        // own size goes soft, and the card is the biggest type in the show.
        face = program.loadFont(fontPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        if (section.chapter.isBlank()) return

        val box = Rectangle(
            INSET, INSET,
            stage.width - 2 * INSET,
            stage.height - 2 * INSET - FOOT
        )

        // Off the card's own frame count rather than off `enter`, which a [Cut] leaves at
        // 1 from the first frame — there is no handover to hang it on, and there should
        // not be: what fades is the title arriving, not one card dissolving into another.
        val appear = stage.since(0, FADE)

        drawer.fill = ink.opacify(appear)
        face.setToFit(section.chapter, box, SIZE, LEADING, lines).draw(drawer, box.center)

        // Where this sits in the show, kept quiet and out of the way of the chapter. A
        // chapter whose slides hang straight off it has no subchapter and shows none.
        if (section.subchapter.isNotBlank()) {
            drawer.fontMap = Type.panelSub
            drawer.fill = ink.opacify(0.45 * appear)
            val label = "${section.number}   ${section.subchapter}"
            drawer.text(label, (stage.width - Type.panelSub.advanceOf(label)) / 2.0, stage.height - MARGIN)
        }
    }

    private companion object {
        /** The size the atlas is baked at. Everything is scaled off it, never past it. */
        const val SIZE = 190.0

        /**
         * Line to line, as a multiple of the size. Tight, because the type is large and the
         * block is what fills the card: every notch of leading given back is type the height
         * constraint can spend instead, and at this size the lines still clear.
         */
        const val LEADING = 0.95

        /**
         * The card's own margin, tighter than the slides' [MARGIN]: there is one thing on
         * it, and holding it to the same inset as a slide's furniture leaves the type
         * smaller than the frame can carry.
         */
        const val INSET = 70.0

        /** Room kept at the foot of the card for the subchapter line. */
        const val FOOT = 104.0

        /** Frames the title takes to fade up as the card arrives. */
        val FADE = frames(0.8)
    }
}
