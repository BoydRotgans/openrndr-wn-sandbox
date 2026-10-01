// ============================================================================ //
//  No `package` declaration: it stands on CircleCatalogue, in the default package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import slideshow.Arrival
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.frames
import slideshow.pitchStep

/**
 * The Circle, in elementen, **the first of the catalogue's three slides**: the whole catalogue placed along the WN
 * mark, one piece after another, each landing white and settling into the grey, and turning slowly on its own axis.
 * No clicks — it lays itself down on the slide's own frame count and keeps turning, and the click that leaves it is
 * the grid's arrival, which picks every piece up where it had got to. `RingOfPieces` drawn the webtool's way: the
 * layout, the timing and the turn are that slide's, and the drawing is [CircleCatalogue]'s, shared with the grid and
 * the webtool so the three are one picture.
 */
class CircleRing(
    val scene: CircleCatalogue,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val sound: Sound? = null
) : Slide() {

    override val name = "Circle ring"
    override val steps get() = 1
    override val settle get() = scene.ringSettle

    /** A note a piece, on the frame it starts to land — `RingOfPieces`' score, since the landing is the same. */
    override fun arrivals(clicks: List<Int>): List<Arrival> {
        val n = scene.count
        if (n <= 0) return super.arrivals(clicks)
        return (0 until n).map { i ->
            Arrival(lane = 0, index = pitchStep(i, n), start = i * frames(scene.cadence), length = frames(scene.landing))
        }
    }

    override fun load(program: Program) = scene.load(program)

    override fun draw(drawer: Drawer, stage: Stage) {
        scene.draw(drawer, stage, List(scene.count) { scene.ringPose(it, stage.frame) })
        scene.title(drawer, stage, scene.ringTitle)
    }
}
