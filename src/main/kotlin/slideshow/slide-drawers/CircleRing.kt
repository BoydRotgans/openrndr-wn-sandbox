// ============================================================================ //
//  No `package` declaration: it stands on CircleCatalogue, in the default package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.math.Vector2
import kotlin.math.roundToInt
import slideshow.Arrival
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.Want
import slideshow.frames
import slideshow.voiced

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

    override fun load(program: Program) = scene.load(program)

    override fun draw(drawer: Drawer, stage: Stage) {
        scene.draw(drawer, stage, List(scene.count) { scene.ringPose(it, stage.frame) })
        scene.title(drawer, stage, scene.ringTitle)
    }

    // --- the slide as timing ------------------------------------------------------------ //
    //
    //  Two lanes, every note read off `ringPose`: a piece landing on the mark, and the same piece
    //  settling from white into the grey. Pitch is where it comes to rest, top highest, so the score
    //  traces the mark as it is laid down: round the ring and up the arch. The grid's rule.

    override val lanes: List<String> get() = listOf("landing", "settling", "states")

    override fun arrivals(clicks: List<Int>): List<Arrival> {
        val states = super.arrivals(clicks).map { it.copy(lane = lanes.lastIndex) }
        val n = scene.count
        if (n <= 0) return states
        fun height(p: Vector2) = ((1.0 - p.y / scene.pane.y) * (NOTES - 1)).roundToInt().coerceIn(0, NOTES - 1)
        val wants = (0 until n).flatMap { i ->
            val start = scene.ringStart(i)
            val pitch = height(scene.ringPlace(i))
            listOf(
                Want(0, pitch, start, frames(scene.landing), 0, NOTES - 1),
                Want(1, pitch, start + scene.settleFrom, scene.settleFrames, 0, NOTES - 1))
        }
        return voiced(wants) + states
    }

    private companion object {
        const val NOTES = 48
    }
}
