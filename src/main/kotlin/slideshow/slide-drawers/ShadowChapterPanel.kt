package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import slideshow.Cut
import slideshow.Section
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.backdrops.ShadowFacade

/**
 * A chapter card cut into the Shadows facade: the chapter's title picture read as a mask, its
 * letters cut shallow into a wall of deep cells, and a sun that leans away as the chapter opens
 * so the words are left standing in the light while the ground fills with shadow.
 *
 * **The facade is the backdrop's own, not a copy of it.** [ShadowFacade] is one shader on one
 * rectangle and lays out against whatever stage it is handed, so the card only has to be the
 * thing a panel deck can hold — a slide the width of the pane, with a chapter's name, its sting
 * and a cut — and hand the facade its stage. A change to the wall is a change to both.
 *
 * The facade should be built with a `reveal`, so the title is read out once as the card arrives
 * and then stays; the card declares the facade's loop, so the stage it hands on turns the sun at
 * the facade's own rate.
 */
class ShadowChapterPanel(
    private val section: Section,
    private val facade: ShadowFacade,
    override val sound: Sound? = null
) : Slide() {

    override val name get() = section.chapter.ifBlank { "Panel" }
    override val loop get() = facade.loop
    override val background: ColorRGBa get() = facade.background
    override val transition = Cut

    override fun load(program: Program) = facade.load(program)

    override fun key(name: String): Boolean = facade.key(name)

    override fun draw(drawer: Drawer, stage: Stage) = facade.draw(drawer, stage)
}
