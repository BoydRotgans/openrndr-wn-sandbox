// ============================================================================ //
//  No `package` declaration: it stands on CircleCatalogue, in the default package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.math.Vector2
import kotlin.math.roundToInt
import slideshow.Arrival
import slideshow.FPS
import slideshow.SNAP_SECONDS
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.Want
import slideshow.frames
import slideshow.linear
import slideshow.voiced

/**
 * 100 elementen, **the second of the catalogue's three slides**: the pieces leave the WN mark for a sheet, and on
 * the click take their real size, with a person in the middle for scale.
 *
 * **It opens on the ring's own last frame.** The deck says how long the ring stood (`cameFrom`), so every piece
 * goes on exactly as the ring had it — turning, and landing if it had not yet — until its turn to leave. Then, in
 * the order they were laid down along the mark, each snaps to a cell of the sheet and to the nearest quarter turn,
 * so the catalogue comes to rest square to the grid and still, the iso sheet in the round. Which cell each piece
 * takes is the matching of places on the mark to cells with the least travel in all, which keeps the paths from
 * crossing. The sheet is sized so the typical piece is largest, the last row as long as the count leaves it and
 * centred, and every piece is fitted into its cell as it stands, the sheet's own rule.
 *
 * **The click shows the catalogue to scale**, and the pieces keep their places: every piece takes its real size
 * in metres, one scale for all, in its own row and its own place in the row, so the sheet spreads to what it holds.
 * A band opens across the middle and a standing adult out of the people model steps into it — the one thing on
 * the sheet whose size everybody knows. The pieces go outward from the middle, a snap each, and the figure comes
 * in once the middle has cleared. The three whole floors of the model, 120 to 145 m across and a millimetre thick,
 * are not elements and would cover the frame at scale; they shrink away. Timed in the seconds the click has run
 * (`linear` of it), so clicking back undoes it. See `CircleCatalogue.sheetPose`.
 *
 * The arrival runs on the slide's own frame count only when it opened on its first state: stepping back into it
 * from the webtool finds the sheet to scale and standing.
 *
 * **[toScale] false takes the click out** (29 September, "for now"): the slide is the arrival alone, one state, and
 * the webtool opens on the sheet in its cells. Stepping back into it from the webtool finds the sheet standing.
 * Its export then [runsInto] the webtool, so the clip and the score carry the sheet pouring into "Start a new
 * circle", which in the show is the webtool's state A.
 */
class CircleGrid(
    val scene: CircleCatalogue,
    /** Whether the click shows the catalogue to scale; false leaves the slide the sheet alone, one state. */
    private val toScale: Boolean = true,
    /** The slide an export of this one runs on into: the webtool, whose arrival is the sheet going into its button. */
    override val runsInto: Slide? = null,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "Circle grid"
    override val steps get() = if (toScale) 2 else 1
    override val stepFrames get() = if (toScale) frames(scene.scaleSeconds) else super.stepFrames
    override val settle get() = frames(scene.gatherSeconds)
    override fun stepName(step: Int): String? = if (step == 1) "to scale" else null

    // With no click the sheet is only ever left in its cells, so the webtool opens on that, jumped into or not.
    init { if (!toScale) scene.gridLeftOn = 0 }

    /** The click the deck opened this slide on: the arrival plays only from the first. */
    private var openedOn = 0
    /** Stepped back into from the webtool: with one state there is no later click to say so, so the sheet stands. */
    private var cameBack = false

    override fun cameFrom(previous: Slide, stood: Int, leftOn: Int, opensOn: Int) {
        openedOn = opensOn
        cameBack = !toScale && previous is WebtoolKit
        if (previous is CircleRing && previous.scene === scene) scene.ringStood = stood
    }

    override fun load(program: Program) = scene.load(program)

    override fun draw(drawer: Drawer, stage: Stage) {
        val gathering = !cameBack && (openedOn == 0 || (stage.step == 0 && stage.position < 1e-6))
        val scaling = linear(stage.on(1)) * scene.scaleSeconds
        scene.draw(drawer, stage, List(scene.count) { scene.gridPose(it, stage.frame, gathering, scaling) }, scene.personPose(scaling))
        val arrived = !gathering || stage.frame / FPS.toDouble() >= scene.gridTitleAt
        scene.title(drawer, stage, if (arrived) scene.gridTitle else scene.ringTitle)
    }

    // --- the slide as timing ------------------------------------------------------------ //
    //
    //  Two lanes, every note read off the schedule `draw` steps through: a piece leaving the ring for
    //  its cell, and a piece taking its real size on the click, with the figure stepping in. Pitch is
    //  height on the pane, top highest, so the sheet filling reads as it is built. With the click out
    //  there is no second lane.

    override val lanes: List<String>
        get() = if (toScale) listOf("off the ring", "to scale", "states") else listOf("off the ring", "states")

    override fun arrivals(clicks: List<Int>): List<Arrival> {
        val states = super.arrivals(clicks).map { it.copy(lane = lanes.lastIndex) }
        val n = scene.count
        if (n <= 0) return states
        val snapFrames = frames(SNAP_SECONDS)
        fun height(p: Vector2) = ((1.0 - p.y / scene.pane.y) * (NOTES - 1)).roundToInt().coerceIn(0, NOTES - 1)
        val wants = ArrayList<Want>()
        for (i in 0 until n)
            wants += Want(0, height(scene.sheetAt(i, 0) ?: continue), frames(scene.moveStart(i)), snapFrames, 0, NOTES - 1)
        clicks.firstOrNull()?.takeIf { toScale }?.let { at ->
            fun into(s: Double) = at + (s / scene.scaleSeconds * stepLength(1)).roundToInt()
            for (i in 0 until n) {
                // A piece left out at scale is scored where it stood, as it goes.
                val spot = scene.sheetAt(i, 1) ?: scene.sheetAt(i, 0) ?: continue
                wants += Want(1, height(spot), into(scene.scaleStart(i)), snapFrames, 0, NOTES - 1)
            }
            wants += Want(1, NOTES / 2, into(scene.personAt), snapFrames, 0, NOTES - 1)
        }
        return voiced(wants) + states
    }

    private companion object {
        const val NOTES = 48
    }
}
