package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.shape.Rectangle
import slideshow.Arrival
import slideshow.Cut
import slideshow.Scene
import slideshow.Section
import slideshow.Sound
import slideshow.Stage
import slideshow.frames
import slideshow.linear
import slideshow.seconds
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
 *
 * **It is two states, since the feedback of 28 September** ("make these scenes chapter title render
 * in two clicks"). It opens on the title coming up through the field, and the field then stands with
 * the title in it — the sun still turning — for as long as the speaker wants; the click lets the
 * field go, and the quote comes up behind the wave. The click only moves when the field starts to
 * leave ([LongShadowV3.draw]'s `leave`): everything after it is the one schedule it always was, laid
 * from that second, so the quote still waits for every shadow that could fall on it. The card is told
 * the same second ([leaveAt]), so beside the slides it goes on as the half of the wall it was.
 * Clicked before the title is whole, the field goes the moment it is.
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
    override val steps get() = 2
    override fun stepName(step: Int) = if (step == 0) "the title" else "the field goes, the quote"

    /**
     * The frame of this opening's own count the click came on, or null while it has not come. Kept
     * rather than derived because the card beside the slides has to know it after the opening has
     * gone: both count from the frame the opening came up, so the one number serves both.
     */
    private var leaveFrame: Int? = null

    /** When the field starts to leave, in seconds of the opening's (and the card's) count: null is the card's own schedule. */
    private fun leaveAt(): Double? = leaveFrame?.let { seconds(it) }
    override val background: ColorRGBa get() = shadow.paper
    override val transition = Cut

    /**
     * The chapter's card — pass this as the chapter's `panel`. It is pane 0 of this same wall, on
     * the same effect, so it goes on from wherever the opening leaves it.
     */
    fun card(section: Section): LongShadowV3ChapterPanel {
        chapter = section.chapter
        svg = drawnTitle(section)
        return LongShadowV3ChapterPanel(section, shadow, svg, cardSound, leave = ::leaveAt)
    }

    override fun load(program: Program) = shadow.load(program)

    /** The first state: the title coming whole. What a hands-off run waits for before the click. */
    override val settle: Int get() = frames(shadow.whole(svg, chapter))

    /** The click: the field going and the quote coming up behind it, counted from the second it is let go. */
    override fun stepLength(step: Int): Int {
        if (step < 1) return super.stepLength(step)
        val whole = shadow.whole(svg, chapter)
        return frames(shadow.settled(svg, chapter, leave = whole) - whole).coerceAtLeast(1)
    }

    // The wall's build as MIDI: the card's lanes and the quote's beside them. Worked out on first ask
    // and kept — but only where the face can be read: the organizer's launcher asks with no window,
    // and gets the states, as any slide gives them, until the show itself is up.
    // The field goes on the click, so the score is laid from it: one per click list, kept.
    private val scores = mutableMapOf<Int?, Pair<List<String>, List<Arrival>>>()
    private fun score(click: Int?) = scores[click] ?: if (shadow.canSetType)
        shadow.midi(svg, chapter, leave = click?.let { seconds(it) }).also { scores[click] = it } else null
    override val lanes: List<String> get() = score(null)?.first ?: super.lanes
    override fun arrivals(clicks: List<Int>): List<Arrival> = score(clicks.firstOrNull())?.second ?: super.arrivals(clicks)

    override fun draw(drawer: Drawer, stage: Stage) {
        if (chapter.isEmpty()) {
            if (!warned) println("chapter opening: no card was built from it, so it has no title — pass its card as the chapter's panel")
            warned = true
            return
        }
        // Waiting for the click the field stands; once it has come, the frame it came on is kept —
        // worked back from how far into the click the deck is, so a click landing between two draws
        // is still placed on its own frame. A replay, or a step back, forgets it.
        if (stage.step == 0 || leaveFrame?.let { stage.frame < it } == true) leaveFrame = null
        if (stage.step >= 1 && leaveFrame == null)
            leaveFrame = stage.frame - (linear(stage.on(1)) * stepLength(1)).toInt().coerceAtMost(stage.frame)
        val leave = if (stage.step == 0) Double.POSITIVE_INFINITY else leaveAt()
        shadow.draw(drawer, stage.bounds, chapter, stage.frame, svg = svg, leave = leave)
    }

    /**
     * The field this opening stands on its first frame, element by element, for something that builds
     * up to it — see `CourseTransition`. Null until the chapter's card has been built, or where the
     * face cannot be read.
     */
    fun elements(): LongShadowV3.Elements? = if (chapter.isEmpty()) null else shadow.elements(svg, chapter)

    /**
     * This opening's first frame with every element of [elements] standing at [rise] of its height:
     * 0 is the bare ground, and 1 for all of them is exactly what [draw] shows at frame 0. [ground] is
     * the light on the ground and its shadows, 0 black to 1 as the opening has it.
     */
    fun drawBuilding(drawer: Drawer, bounds: Rectangle, ground: Double = 1.0, rise: (Int) -> Double) {
        if (chapter.isNotEmpty()) shadow.draw(drawer, bounds, chapter, 0, svg = svg, rise = rise, ground = ground)
    }

    private var warned = false
}
