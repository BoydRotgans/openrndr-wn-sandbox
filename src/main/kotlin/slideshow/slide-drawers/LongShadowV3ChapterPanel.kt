package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import slideshow.Arrival
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
 * and a cut — and a value tuned in the sketch is the value the card runs. The title is the chapter's
 * own words set large in Rockwell, a letter a piece; [svg] gives a drawn title instead. The card's frame count
 * starts again when its chapter opens, so the transition plays with the chapter.
 *
 * **Beside a [ChapterOpening] it is the first pane of that wall.** The two share one effect, the
 * card draws pane 0 of it, and the show starts the card on the frame the wall came up — so when
 * the talk moves on from the opening, the card is the left half of the picture that was just on
 * the wall, mid-flight, rather than the reveal starting again.
 */
class LongShadowV3ChapterPanel(
    val section: Section,
    private val shadow: LongShadowV3,
    private val svg: File? = null,
    override val sound: Sound? = null
) : Slide() {

    override val name get() = section.chapter.ifBlank { "Panel" }
    override val background: ColorRGBa get() = shadow.paper
    override val transition = Cut

    override fun load(program: Program) = shadow.load(program)

    // The card's build as MIDI: a note for every block each time it animates, regular blocks and
    // letter blocks on lanes of their own. Worked out on first ask and kept, off the effect's plan —
    // where the face can be read; with no window there is nothing to set type with (see canSetType).
    private var score: Pair<List<String>, List<Arrival>>? = null
    private fun score() = score ?: if (shadow.canSetType) shadow.midi(svg, section.chapter).also { score = it } else null
    override val lanes: List<String> get() = score()?.first ?: super.lanes
    override fun arrivals(clicks: List<Int>): List<Arrival> = score()?.second ?: super.arrivals(clicks)

    /** How long the reveal takes to come to rest, so a preview of a slide beside it finds it settled. */
    override val settle: Int get() = slideshow.frames(shadow.settled(svg, section.chapter))

    override fun draw(drawer: Drawer, stage: Stage) =
        shadow.draw(drawer, stage.bounds, section.chapter, stage.frame, svg = svg, pane = 0)
}
