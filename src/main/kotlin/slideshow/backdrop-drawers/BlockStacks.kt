// ============================================================================ //
//  No `package` declaration, for the reason YardScene has none: the pieces in the
//  cubes are loaded by loadObjMesh, which lives in the default package and cannot be
//  imported into a named one. The file still belongs in backdrop-drawers/.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.VertexBuffer
import org.openrndr.math.Vector3
import org.openrndr.math.transforms.lookAt
import org.openrndr.math.transforms.ortho
import slideshow.Backdrop
import slideshow.Cut
import slideshow.FPS
import slideshow.Sound
import slideshow.Stage
import slideshow.Transition
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The block city as building blocks: every element one cube, stacked into columns, drawn as a
 * see-through wire model — every edge of every cube, the hidden ones as well — with a red
 * catalogue piece standing inside each cube, like a part in a vitrine.
 *
 * **It cannot stand on [BlockCity], which is why it is its own drawer.** That wall finds its lines
 * on the picture, where the face under a pixel changes, so a line exists only where a face is
 * seen; hidden lines are removed by construction. A wire model is the other thing entirely: the
 * edges are geometry.
 *
 * **One cube is the only element.** A building is a cluster of columns on a footprint, each column
 * a stack of unit cubes a step or so off its neighbours' height, so a building reads as blocks
 * stacked rather than as one box — and every cube edge is drawn, so the stacking is what is seen.
 * An edge two cubes share is drawn once: the edges are gathered as lattice keys, so a column
 * standing beside another adds only what the other did not already have.
 *
 * **The pieces are solid and the cubes are glass**: the drawing — edges as camera-facing quads,
 * the pieces, the one multisampled pass with a depth test — is [WireCubes], shared with
 * [BlockCluster].
 *
 * **Streets are kept open**: a building's footprint must clear every other by [street] cells, so the
 * columns stand in blocks with space between, which a wire model needs more than a solid one —
 * run edge to edge, the lattice of a whole district seen through itself is a grey field.
 *
 * **The view is off the true isometric on purpose, both ways.** At a yaw of 45 a cube's back
 * vertical edge stands exactly behind its front one whatever the pitch, and at 35.264 degrees of
 * pitch its back corner lands on its front one as well: a see-through stack collapses into
 * hexagons with a Y in them, which is what the first still showed. So the yaw swings about 56 and
 * never reaches 45, and the pitch is 30, and every line of every cube stays its own line.
 *
 * The camera and the loop are [BlockCity]'s: a parallel view one tile diagonally along per
 * [period], the yaw swaying a whole number of times a period about its own [yaw], the city a tile
 * on a torus with the camera kept inside the base tile, so the end of a period is its start.
 * Everything is a function of the frame.
 */
class BlockStacks(
    override val name: String = "Block stacks",
    /** Pixels one cube takes across the wall. */
    private val unit: Double = 110.0,
    /** The camera's elevation in degrees — see above for why it is not 35.264. */
    private val pitch: Double = 30.0,
    /** The square the city repeats on, in cubes a side. */
    private val tile: Int = 48,
    /** Seconds the pan takes to cross one tile — the loop. */
    private val period: Double = 240.0,
    /** The yaw the camera swings about, and degrees it sways either side of it — kept clear of 45, see above. */
    private val yaw: Double = 56.0,
    private val sway: Double = 7.0,
    private val sways: Int = 1,
    /** Share of the ground the buildings stand on, streets included in what is left. */
    private val density: Double = 0.42,
    /** Cells of street every building keeps clear round it. */
    private val street: Int = 1,
    /** The tallest column, in cubes. */
    private val tallest: Int = 9,
    /** Where the ground point the camera holds stands down the wall, 0 top to 1 foot. */
    private val horizon: Double = 0.55,
    private val paper: ColorRGBa = ColorRGBa.WHITE,
    private val ink: ColorRGBa = ColorRGBa.BLACK,
    /** Line width in wall pixels. */
    private val line: Double = 2.0,
    /** The catalogue pieces the cubes hold, dealt among them from the seed; empty leaves the cubes empty. */
    private val pieces: List<File> = emptyList(),
    /** The pieces' colour, and how much of a cube a piece's longest side takes. */
    private val piece: ColorRGBa = ColorRGBa.fromHex("FF0000"),
    private val fill: Double = 0.72,
    private val seed: Int = 7,
    override val transition: Transition = Cut,
    override val sound: Sound? = null
) : Backdrop() {

    override val background: ColorRGBa get() = paper

    private val wire = WireCubes(pieces, piece, fill, ink, paper, line)
    /** Every edge of the tile once, as quads, in cube units: x and z across the ground, y up. */
    private var lines: VertexBuffer? = null
    private var lineCount = 0
    /** The tallest stack actually built, for culling the copies of the tile. */
    private var highest = 1
    /** Every cube of the tile as its corner (x, y, z), which piece it holds and its quarter turn. */
    private var cubes: IntArray = IntArray(0)

    override fun load(program: Program) {
        if (lines != null) return
        val heights = generate()
        highest = heights.maxOrNull()?.coerceAtLeast(1) ?: 1

        val edges = WireCubes.Edges()
        val placed = mutableListOf<Int>()
        // Which piece a cube holds and how it is turned come off a stream of their own, so the
        // city is laid out the same whichever pieces are named.
        val deal = Random(seed * 7919 + 1)
        for (z in 0 until tile) for (x in 0 until tile) {
            val h = heights[x + z * tile]
            for (y in 0 until h) {
                edges.cube(x, y, z)
                placed += x; placed += y; placed += z
                placed += if (pieces.isEmpty()) -1 else deal.nextInt(pieces.size)
                placed += deal.nextInt(4)
            }
        }
        cubes = placed.toIntArray()
        wire.quads(edges).let { (buffer, count) -> lines = buffer; lineCount = count }
        wire.load()
        println("block stacks: ${heights.count { it > 0 }} columns, ${cubes.size / 5} cubes, ${edges.size} edges on a ${tile}x$tile tile" +
                if (wire.meshes.isEmpty()) "" else ", holding ${wire.meshes.joinToString { it.name }}")
    }

    /**
     * The columns' heights, one a cell of the tile: footprints packed from the seed, each clearing
     * every other by [street] cells so the streets stay open, and each column a step or so off its
     * building's height so the building reads as stacked blocks.
     */
    private fun generate(): IntArray {
        val rnd = Random(seed)
        val cells = tile * tile
        val heights = IntArray(cells)
        val taken = BooleanArray(cells)
        fun cell(x: Int, z: Int) = Math.floorMod(x, tile) + Math.floorMod(z, tile) * tile
        var covered = 0
        var attempts = 0
        while (attempts < cells * 8 && covered < density * cells) {
            attempts++
            val w = rnd.pick(FOOTPRINT)
            val d = rnd.pick(FOOTPRINT)
            val x = rnd.nextInt(tile)
            val z = rnd.nextInt(tile)
            var free = true
            loop@ for (i in -street until w + street) for (j in -street until d + street) if (taken[cell(x + i, z + j)]) { free = false; break@loop }
            if (!free) continue
            val base = rnd.pick(HEIGHT).coerceAtMost(tallest)
            for (i in 0 until w) for (j in 0 until d) {
                val step = if (rnd.nextDouble() < 0.45) rnd.nextInt(3) - 1 else 0
                taken[cell(x + i, z + j)] = true
                heights[cell(x + i, z + j)] = (base + step).coerceIn(1, tallest)
            }
            covered += w * d
        }
        return heights
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val quads = lines ?: return
        val w = stage.width
        val h = stage.height

        // BlockCity's camera: the fraction of the period places everything, the camera kept in
        // the base tile while the world wraps around it, so the loop closes exactly.
        val t = stage.frame.toDouble() / FPS
        val phase = (t / period).let { it - floor(it) }
        val c = Vector3(tile * 0.5 + tile * phase, 0.0, tile * 0.5 - tile * phase)
        val turn = Math.toRadians(yaw + sway * sin(2.0 * PI * sways * phase))
        val elev = Math.toRadians(pitch)
        val e = Vector3(cos(elev) * sin(turn), sin(elev), cos(elev) * cos(turn))
        val right = (-e).cross(Vector3.UNIT_Y).normalized
        val up = right.cross(-e).normalized
        val view = lookAt(c + e * 400.0, c, Vector3.UNIT_Y)
        val projection = ortho(-w / unit / 2.0, w / unit / 2.0, -h / unit * (1.0 - horizon), h / unit * horizon, 1.0, 800.0)

        // Where a world point lands on the wall, for culling what is off it.
        fun sx(x: Double, y: Double, z: Double) = w / 2.0 + ((x - c.x) * right.x + y * right.y + (z - c.z) * right.z) * unit
        fun sy(x: Double, y: Double, z: Double) = h * horizon - ((x - c.x) * up.x + y * up.y + (z - c.z) * up.z) * unit

        wire.frame(drawer, stage.bounds, projection, view) {
            for (ti in -1..1) for (tj in -1..1) {
                val dx = ti * tile.toDouble()
                val dz = tj * tile.toDouble()
                // A copy of the tile is skipped whole when its box, stacks and all, is off the wall.
                var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
                for (cx in 0..1) for (cy in 0..1) for (cz in 0..1) {
                    val px = dx + cx * tile; val py = cy * highest.toDouble(); val pz = dz + cz * tile
                    val a = sx(px, py, pz); val b = sy(px, py, pz)
                    minX = min(minX, a); maxX = max(maxX, a); minY = min(minY, b); maxY = max(maxY, b)
                }
                if (maxX < 0.0 || minX > w || maxY < 0.0 || minY > h) continue

                wire.edges(drawer, quads, lineCount, e, unit, Vector3(dx, 0.0, dz))

                if (wire.meshes.isEmpty()) continue
                wire.pieces(drawer)
                val reach = unit * 1.5
                var i = 0
                while (i < cubes.size) {
                    val x = cubes[i] + dx + 0.5; val y = cubes[i + 1] + 0.5; val z = cubes[i + 2] + dz + 0.5
                    val which = cubes[i + 3]; val quarter = cubes[i + 4]
                    i += 5
                    val px = sx(x, y, z); val py = sy(x, y, z)
                    if (px < -reach || px > w + reach || py < -reach || py > h + reach) continue
                    wire.piece(drawer, Vector3(x, y, z), which, quarter)
                }
            }
        }
    }

    private companion object {
        val FOOTPRINT = listOf(1 to 0.30, 2 to 0.38, 3 to 0.24, 4 to 0.08)
        val HEIGHT = listOf(1 to 0.22, 2 to 0.28, 3 to 0.20, 4 to 0.13, 5 to 0.08, 6 to 0.05, 8 to 0.03, 9 to 0.01)

        fun Random.pick(table: List<Pair<Int, Double>>): Int {
            var r = nextDouble() * table.sumOf { it.second }
            for ((value, weight) in table) { r -= weight; if (r <= 0.0) return value }
            return table.last().first
        }
    }
}
