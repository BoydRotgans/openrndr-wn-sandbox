package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import slideshow.Cut
import slideshow.Section
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import java.io.File

/**
 * A chapter card in long shadow type v3: a full field of elements standing on the pane, going down
 * over the title one by one so the drawn stencil title comes up through them, the rest sinking into
 * the floor, and the title's shadow drawing in to a sliver.
 *
 * **The effect is [LongShadowV3], the one the `LongShadowType_v3` sketch draws through**, so this is
 * only what a panel deck can hold — a slide the width of the pane with a chapter's name, its sting
 * and a cut — and a value tuned in the sketch is the value the card runs. [svg] is the drawn title;
 * a chapter without one sets its own words in the face, built of blocks. The card's frame count
 * starts again when its chapter opens, so the transition plays with the chapter.
 */
class LongShadowV3ChapterPanel(
    private val section: Section,
    private val shadow: LongShadowV3,
    private val svg: File? = null,
    override val sound: Sound? = null
) : Slide() {

    override val name get() = section.chapter.ifBlank { "Panel" }
    override val background: ColorRGBa get() = shadow.paper
    override val transition = Cut

    override fun load(program: Program) = shadow.load(program)

    override fun draw(drawer: Drawer, stage: Stage) =
        shadow.draw(drawer, stage.bounds, section.chapter, stage.frame, svg = svg)
}
