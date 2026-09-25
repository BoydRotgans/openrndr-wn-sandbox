package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import slideshow.Arrival
import slideshow.Cut
import slideshow.Scene
import slideshow.Section
import slideshow.Sound
import slideshow.Stage
import slideshow.frames
import java.io.File

/**
 * A chapter opening as one picture across the wall: the chapter card's field of elements running
 * over both projectors, the title coming up through it on the left exactly as the card alone does,
 * and then — once the title is whole — the field sinking into the floor in one wave outward from
 * the title, over the seam and across the right, with the chapter's quote appearing behind the wave,
 * a letter as soon as nothing is left standing that could throw a shadow on it. The quote is flat
 * type and throws no shadow itself. The title and the quote are left on the one ground.
 *
 * It replaced the card beside a [QuoteSlide] on the feedback of 24 September: "what about extending
 * the blocks also on the right side, and the sentence would come in a bit later, after the chapter
 * title has appeared. then blocks disappear, sentence shows".
 *
 * **It is one effect with the chapter's card, not two that match.** [shadow] is a [LongShadowV3]
 * two panes wide, worked out as a single composition, so a shadow thrown across the seam is simply
 * a shadow and the mosaic's joints run straight over it. The card is built from the same instance
 * by [card], which is what the chapter hands the show as its `panel`, and draws pane 0 of it.
 *
 * **Which is why it carries its card rather than covering it** ([carriesCard]). A wide slide
 * normally puts the card away and the card starts again with the next slide; this one keeps the
 * card running underneath, started on the very frame the wall came up, so on the click the left
 * pane goes on as the half of the wall it was — the cut into the chapter's first slide cannot be
 * seen. Stepping back into the opening lands it built, the deck's rule for going back, and `r` runs
 * both again from the start.
 *
 * The chapter's title reaches it through [card]: the section names the chapter, and the chapter's
 * `panel` is called as the opening goes into the show, before anything is drawn.
 */
class ChapterOpening(
    private val shadow: LongShadowV3,
    /** A drawn title instead of the chapter's words, as the card takes one, for the chapter's section. */
    private val drawnTitle: (Section) -> File? = { null },
    /** The card's sting, handed on to the card; the opening itself says nothing of its own. */
    private val cardSound: Sound? = null
) : Scene() {

    /** The chapter's words, from the section its card was built for, and its drawn title if it has one. */
    private var chapter = ""
    private var svg: File? = null

    override val name get() = "Chapter opening"
    override val carriesCard get() = true
    override val background: ColorRGBa get() = shadow.paper
    override val transition = Cut

    /**
     * The chapter's card — pass this as the chapter's `panel`. It is pane 0 of this same wall, on
     * the same effect, so it goes on from wherever the opening leaves it.
     */
    fun card(section: Section): LongShadowV3ChapterPanel {
        chapter = section.chapter
        svg = drawnTitle(section)
        return LongShadowV3ChapterPanel(section, shadow, svg, cardSound)
    }

    override fun load(program: Program) = shadow.load(program)

    /** The whole reveal, title then quote then the field going: what a hands-off run waits for. */
    override val settle: Int get() = frames(shadow.settled(svg, chapter))

    // The wall's build as MIDI: the card's lanes and the quote's beside them. Worked out on first ask
    // and kept — but only where the face can be read: the organizer's launcher asks with no window,
    // and gets the states, as any slide gives them, until the show itself is up.
    private var score: Pair<List<String>, List<Arrival>>? = null
    private fun score() = score ?: if (shadow.canSetType) shadow.midi(svg, chapter).also { score = it } else null
    override val lanes: List<String> get() = score()?.first ?: super.lanes
    override fun arrivals(clicks: List<Int>): List<Arrival> = score()?.second ?: super.arrivals(clicks)

    override fun draw(drawer: Drawer, stage: Stage) {
        if (chapter.isEmpty()) {
            if (!warned) println("chapter opening: no card was built from it, so it has no title — pass its card as the chapter's panel")
            warned = true
            return
        }
        shadow.draw(drawer, stage.bounds, chapter, stage.frame, svg = svg)
    }

    private var warned = false
}
