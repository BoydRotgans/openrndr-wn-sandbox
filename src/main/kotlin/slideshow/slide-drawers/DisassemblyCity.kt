// ============================================================================ //
//  No `package` declaration: it stands on loadObjMesh, in the default package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.math.Vector3
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.backdrops.BlockCity
import slideshow.backdrops.CityPiece
import slideshow.backdrops.Roof
import slideshow.drawers.staggered
import slideshow.frames
import slideshow.smoothstep
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Demountable building in the block city: catalogue pieces standing on the city's roofs, lifted
 * off on the click and set down on other roofs — the city as a stock of parts being reused.
 *
 * **It is the block city itself, not a picture of it.** A [BlockCity] is held inside this slide
 * and drawn into the pane, and the pieces are handed to it as a function of the stage: they go
 * through the city's own depth map and outline pass, so a piece shadows the roof it stands on
 * and the tower beside it shadows the piece. Two states, one click: every piece rises in an arc
 * from the roof it stood on to the roof it was dealt, a little apart from the last, on the deck's
 * own ease. The roofs are dealt from the seed among those near the point the camera holds, so
 * the pieces are in the frame while the city drifts.
 */
class DisassemblyCity(
    private val objects: File,
    /** The pieces to stand on the roofs, by mesh name, taken in turn. */
    private val picks: List<String>,
    private val count: Int = 6,
    /** A piece's longest reach in lattice units. */
    private val size: Double = 3.0,
    /** How high a piece rises between roofs, in lattice units. */
    private val lift: Double = 3.0,
    private val seed: Int = 11,
    private val accent: ColorRGBa = ColorRGBa.fromHex("FF0000"),
    override val stepFrames: Int = frames(1.5),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "Disassembly"
    override val steps get() = 2
    override fun stepName(step: Int): String? = if (step == 1) "set down elsewhere" else null
    override val background: ColorRGBa get() = city.background

    /** The drawing's grey city with the accent held for the pieces alone: no block takes it. */
    private val city = BlockCity(sun = 0, accent = accent, accents = 0.0, pieces = { stage -> standing(stage) })
    private class Stand(val mesh: ObjMesh, val from: Roof, val to: Roof, val yaw: Double)
    private var stands: List<Stand> = emptyList()

    override fun load(program: Program) {
        city.load(program)
        val meshes = picks.mapNotNull { name ->
            File(objects, "$name.obj").takeIf { it.isFile }?.let { loadObjMesh(it) }
                ?: run { println("disassembly: no mesh $name in $objects"); null }
        }
        if (meshes.isEmpty()) return
        val random = Random(seed)
        // Roofs near the held point, big enough to carry a piece, dealt twice without repeats.
        // The high roofs near the held point: a piece on a low roof is behind the tower beside it.
        val near = city.roofs.filter { r ->
            r.w >= 2.0 && r.d >= 2.0 &&
                    kotlin.math.abs(r.x + r.w / 2.0 - HELD_X) < REACH && kotlin.math.abs(r.z + r.d / 2.0 - HELD_Z) < REACH
        }.sortedByDescending { it.top }.take(count * 3).shuffled(random)
        val n = minOf(count, near.size / 2)
        stands = (0 until n).map { i -> Stand(meshes[i % meshes.size], near[i], near[n + i], random.nextDouble() * 2.0 * PI) }
        println("disassembly: ${stands.size} pieces on ${near.size} roofs near the held point")
    }

    private fun standing(stage: Stage): List<CityPiece> {
        val t = stage.on(1)
        return stands.mapIndexed { i, s ->
            val k = staggered(t, i, stands.size, LAG)
            // Sized by the piece's longest reach, not its height: a mesh is normalised to a unit
            // sphere, so scaling a 12 m TT plate to a height came out forty units across.
            val scale = size / 2.0
            fun at(r: Roof) = Vector3(r.x + r.w / 2.0, r.top + s.mesh.halfHeight * scale, r.z + r.d / 2.0)
            val a = at(s.from); val b = at(s.to)
            val centre = a + (b - a) * k + Vector3.UNIT_Y * (lift * sin(PI * k))
            CityPiece(s.mesh.vertexBuffer, centre, scale, s.yaw + TURN * smoothstep(k), accent = true)
        }
    }

    override fun draw(drawer: Drawer, stage: Stage) = city.draw(drawer, stage)

    private companion object {
        /** The middle of the base tile, where the city holds its frame at the start of a period. */
        const val HELD_X = 32.0
        const val HELD_Z = 32.0
        const val REACH = 7.0
        const val LAG = 0.12
        const val TURN = 0.0
    }
}
