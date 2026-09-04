package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.isolated
import org.openrndr.extra.meshgenerators.boxMesh
import org.openrndr.math.Vector3
import org.openrndr.math.smoothstep
import slideshow.Slide
import slideshow.Stage
import slideshow.seconds

/**
 * A row of upright panels, each turning a quarter as a wave passes along the row and
 * settling again behind it. `demos/Swivel01.kt` as a slide.
 *
 * **The wave runs off the slide's own frame count, not off a clock.** The sketch it came
 * from read `program.seconds` directly, which a slide may not do — a deck has to be able
 * to click backwards, jump into a slide and be paused, and none of that works against a
 * wall clock (and under `ScreenRecorder` the two disagree outright; see the note under
 * demo01). `stage.frame` is the same number every time the slide is on its tenth frame,
 * so the sweep plays identically whether it is watched, recorded or stepped.
 *
 * It plays once on arrival and rests, rather than looping: the sweep has a beginning and
 * an end, and about [SWEEP] seconds in, every panel is square again. Give the slide a
 * `loop` if it should keep going instead.
 */
class Swivel01Slide(
    private val ink: ColorRGBa = ColorRGBa.fromHex("1C1C1C"),
    override val background: ColorRGBa = ColorRGBa.fromHex("F5F3EE"),
    /** World units across the pane. The sketch framed this scene 1440 wide. */
    private val across: Double = 1440.0
) : Slide() {
    override val name = "Swivel01"

    private lateinit var panel: VertexBuffer

    override fun load(program: Program) {
        panel = boxMesh(110.0, 370.0, 10.0)
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        // The scene is in world units, so the frame is fitted to the pane rather than the
        // other way round: `across` decides how much is seen and the height follows the
        // pane's own shape, which is what lets a 4:1 sketch sit in a 16:9 slide without
        // being stretched into it.
        val halfWidth = across / 2.0
        val halfHeight = halfWidth * stage.height / stage.width
        drawer.ortho(-halfWidth, halfWidth, -halfHeight, halfHeight, -1000.0, 1000.0)
        drawer.lookAt(Vector3(0.0, 300.0, -300.0), Vector3.ZERO)

        drawer.fill = ink
        drawer.stroke = null

        val t = 10.0 - seconds(stage.frame) * 2.0
        for (i in -5..5) drawer.isolated {
            drawer.translate(i * 120.0, 0.0, 0.0)
            // two smoothsteps facing each other: the panel turns as the wave reaches it
            // and turns back as the wave leaves, so the row is square before and after
            val turn = 45.0 * smoothstep(i + 0.0, i + 2.0, t) * smoothstep(i + 5.0, i + 3.0, t)
            drawer.rotate(Vector3.UNIT_Y, turn)
            drawer.vertexBuffer(panel, DrawPrimitive.TRIANGLES)
        }
    }

    private companion object {
        /** Seconds the wave takes to cross the row, for anyone timing a slide against it. */
        const val SWEEP = 7.5
    }
}
