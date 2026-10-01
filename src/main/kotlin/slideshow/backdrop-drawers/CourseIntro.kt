package slideshow.backdrops

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import slideshow.Backdrop
import slideshow.Stage
import slideshow.frames

/**
 * The silent moment before a course: the wall StudioBuik introduces the next dish over, with no
 * music under it. The course's own wall, with its playlist, is the click after it.
 *
 * Asked for at the meeting of 30 September — "before each backdrop scene there needs to be a silent
 * moment for StudioBuik to explain the next course". Every course moment opens on one, so it is one
 * mechanism rather than a quiet first state built into each course wall.
 *
 * **It is the name tag's block, set for a course.** The course in the name's place, StudioBuik under
 * the rule, and the idea the dish stands for set small, in the left projector where the speaker's
 * own tag stood — so the room reads it as the same kind of moment as being introduced to someone,
 * and the right projector is left black. It builds on its own clock and then holds.
 *
 * **It takes no music** ([momentMusic] false), which is the whole point: a moment's playlist plays
 * under every wall of the moment that takes it, so the bed starts on the course wall rather than
 * under the talk about the dish. No cue is declared for it either, so it is silent unless a sound
 * sheet names it.
 */
class CourseIntro(
    /** What the course is called: "Voorgerecht". */
    course: String,
    /** The idea the dish stands for, set small under the house: "Gieten". Empty sets nothing. */
    idea: String,
    fontPath: String = "data/fonts/default.otf",
    accent: ColorRGBa = ColorRGBa.WHITE,
    /** Who introduces it. */
    host: String = "StudioBuik",
    override val background: ColorRGBa = ColorRGBa.BLACK
) : Backdrop() {
    private val tag = NameTag(presenter = course, organisation = host, role = idea, fontPath = fontPath, accent = accent)

    override val name = "Course intro"
    override val buildsIn get() = false
    override val momentMusic get() = false
    override val settle get() = frames(NameTag.BUILT)

    override fun load(program: Program) = tag.load(program)

    override fun draw(drawer: Drawer, stage: Stage) = tag.draw(drawer, stage)
}
