package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import slideshow.Cut
import slideshow.Section
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage

/**
 * A chapter card in long shadow type: the chapter's title set in the deck's face, each word rising
 * out of the floor as a tower and throwing its shadow under a setting sun.
 *
 * **The effect is [LongShadow], the one the `LongShadowType` sketch draws through**, so the card is
 * only what a panel deck can hold — a slide the width of the pane, with a chapter's name, its sting
 * and a cut — and a change tuned in the sketch is a change to the card.
 *
 * The title is the section's own words, set as type rather than read off a picture, so a chapter
 * renamed in `Slideshow.kt` needs nothing else changed. The card's frame count starts again when
 * its chapter opens, so the towers rise with the chapter.
 */
class LongShadowChapterPanel(
    private val section: Section,
    private val shadow: LongShadow,
    override val sound: Sound? = null
) : Slide() {

    override val name get() = section.chapter.ifBlank { "Panel" }
    override val background: ColorRGBa get() = shadow.paper
    override val transition = Cut

    override fun load(program: Program) = shadow.load(program)

    override fun draw(drawer: Drawer, stage: Stage) =
        shadow.draw(drawer, stage.bounds, section.chapter, stage.frame)
}
