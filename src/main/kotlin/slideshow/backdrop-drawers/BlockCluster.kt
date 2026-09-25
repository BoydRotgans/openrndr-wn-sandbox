// ============================================================================ //
//  No `package` declaration: it stands on WireCubes, whose pieces come from
//  loadObjMesh in the default package. See BlockStacks.
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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * A row of buildings made of building blocks, built up on the right and taken down on the left:
 * [BlockStacks]' glass cubes with a red piece in each, raised a building at a time with a street
 * between one building and the next.
 *
 * **A building is finished before the next is begun, and taken down whole before the next goes.**
 * Buildings follow one another along the row, each a few slices wide and a few stacks deep with a
 * street of [gap] or more slices after it, and every cube has a place in two orders: the build,
 * building after building, each from the ground up a layer at a time; and the take-down, in the
 * same order of buildings, each from the top down. So nothing ever floats — a cube is only put on
 * another and only taken off the top — and the space between buildings is always there, because a
 * street is never built on.
 *
 * **The count never changes.** Every [beat] one cube goes on the left and one comes on the right, on
 * the same frame, with no transition. After `k` beats what stands is the first `k + count` cubes of
 * the build less the first `k` of the take-down, which is always [count], so long as no building
 * holds as many as [count] on its own. It is a pure function of the frame: the buildings are dealt
 * from the seed by their number, and the row at any moment is worked out from the clock alone.
 *
 * **The camera follows the middle of the two working ends.** Buildings hold different numbers of
 * cubes and streets hold none, so the row does not advance at one rate; a cube's place in the build
 * is read as a position along the row — a building's cubes spread over it and the street after it —
 * and the camera holds the middle of where the two ends are, averaged over a few beats so it does
 * not lurch at a street.
 *
 * **The view is turned further than the city's.** The row runs along one axis of the lattice, and a
 * lattice axis climbs across a parallel view by `sin(pitch) / tan(yaw)` — at the city's 56 degrees
 * and 30 of pitch a long row climbed 16 degrees and ran off the wall, and at 72 and 28 a row
 * reaching across the wall still put six-storey towers off its top and bottom. At 76 and 26 it
 * climbs about 6, and stays well clear of 45, where a see-through cube folds onto itself.
 */
class BlockCluster(
    override val name: String = "Block cluster",
    /** Pixels one cube takes across the wall. */
    private val unit: Double = 130.0,
    private val pitch: Double = 26.0,
    private val yaw: Double = 76.0,
    /** How many cubes stand at every moment. */
    private val count: Int = 130,
    /** The deepest a building goes across the row, in stacks. */
    private val depth: Int = 3,
    /** The tallest stack, in cubes. */
    private val tallest: Int = 5,
    /** The fewest slices of street between two buildings. */
    private val gap: Int = 1,
    /** Seconds between one cube going and one coming. */
    private val beat: Double = 0.4,
    /** Where the row's middle stands down the wall, 0 top to 1 foot. */
    private val horizon: Double = 0.5,
    private val paper: ColorRGBa = ColorRGBa.WHITE,
    private val ink: ColorRGBa = ColorRGBa.BLACK,
    private val line: Double = 2.0,
    private val pieces: List<File> = emptyList(),
    private val piece: ColorRGBa = ColorRGBa.fromHex("FF0000"),
    private val fill: Double = 0.72,
    private val seed: Int = 7,
    override val transition: Transition = Cut,
    override val sound: Sound? = null
) : Backdrop() {

    override val background: ColorRGBa get() = paper

    private val wire = WireCubes(pieces, piece, fill, ink, paper, line)

    /**
     * One building: where it starts along the row, how many slices it and its street take, the cube
     * it is first in the build, and its cubes as (x, y, slice within it) in build order.
     */
    private class Building(val start: Long, val span: Int, val first: Long, val cubes: List<IntArray>) {
        /** The same cubes in take-down order: the top layer first. */
        val down: List<IntArray> by lazy { cubes.sortedWith(compareByDescending<IntArray> { it[1] }.thenBy { it[2] }.thenBy { it[0] }) }
    }

    private val buildings = ArrayList<Building>()

    /** The edges standing at [shownStep], as quads, and the cubes standing with them. */
    private var lines: VertexBuffer? = null
    private var lineCount = 0
    private var shownStep = Long.MIN_VALUE
    private var standing: List<Standing> = emptyList()
    private var shownFrom = 0L

    private class Standing(val x: Int, val y: Int, val slice: Long, val which: Int, val quarter: Int)

    override fun load(program: Program) = wire.load()

    /** Building [b], dealing the ones before it first: each starts where the last one's street ends. */
    private fun building(b: Int): Building {
        while (buildings.size <= b) {
            val n = buildings.size
            val last = buildings.lastOrNull()
            val start = last?.let { it.start + it.span } ?: 0L
            val first = last?.let { it.first + it.cubes.size } ?: 0L
            val rnd = Random(seed * 1_000_003L + n * 7_919L)
            val width = rnd.pick(WIDTH)
            val deep = (1 + rnd.nextInt(depth)).coerceAtLeast(minOf(2, depth))
            val offset = rnd.nextInt(depth - deep + 1)
            val base = rnd.pick(HEIGHT).coerceIn(1, tallest)
            val heights = Array(width) { IntArray(deep) { (base + if (rnd.nextDouble() < 0.4) rnd.nextInt(3) - 1 else 0).coerceIn(1, tallest) } }
            val street = gap + if (rnd.nextDouble() < 0.35) 1 else 0
            // The build: a layer at a time from the ground, along the row and then across it.
            val cubes = (0 until tallest).flatMap { y ->
                (0 until width).flatMap { s -> (0 until deep).filter { heights[s][it] > y }.map { intArrayOf(it + offset, y, s) } }
            }
            buildings += Building(start, width + street, first, cubes)
        }
        return buildings[b]
    }

    /** The building cube [i] of the build belongs to. */
    private fun buildingOf(i: Long): Int {
        var b = 0
        // Walk out as far as needed, then search: the buildings are in build order.
        while (building(b).first + building(b).cubes.size <= i) b = if (b == 0) 1 else b * 2
        var lo = 0; var hi = b
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (building(mid).first + building(mid).cubes.size <= i) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Where along the row, in slices, the build stands at [i] cubes in: a building's cubes spread over it and its street. */
    private fun along(i: Double): Double {
        val b = buildingOf(floor(i).toLong().coerceAtLeast(0L))
        val it = building(b)
        return it.start + (i - it.first) / it.cubes.size * it.span
    }

    /** The cubes standing after [k] beats: the building going with its top taken off, those between whole, the building coming a layer at a time. */
    private fun cubesAt(k: Long): List<Standing> {
        val going = buildingOf(k)
        val coming = buildingOf(k + count - 1)
        val out = mutableListOf<Standing>()
        for (b in going..coming) {
            val it = building(b)
            val shown = when (b) {
                going -> it.down.drop((k - it.first).toInt()).let { left ->
                    if (b == coming) left.filter { c -> it.cubes.indexOf(c) < (k + count - it.first) } else left
                }
                coming -> it.cubes.take((k + count - it.first).toInt())
                else -> it.cubes
            }
            for (c in shown) {
                val slice = it.start + c[2]
                val deal = Random(seed * 31L + slice * 7_919L + c[0] * 104_729L + c[1] * 1_299_709L)
                out += Standing(c[0], c[1], slice, if (pieces.isEmpty()) -1 else deal.nextInt(pieces.size), deal.nextInt(4))
            }
        }
        return out
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val w = stage.width
        val h = stage.height
        val t = stage.frame.toDouble() / FPS
        val k = floor(t / beat).toLong()

        // The cubes change only on a beat; the edges are gathered and written again then, kept
        // relative to the building going so the lattice keys stay small however long it has run.
        if (k != shownStep) {
            standing = cubesAt(k)
            shownFrom = building(buildingOf(k)).start
            val edges = WireCubes.Edges()
            // Slice s stands at z from -s - 1 to -s: the row grows along -z, which runs to the right.
            for (c in standing) edges.cube(c.x, c.y, -(c.slice - shownFrom).toInt() - 1)
            wire.quads(edges, lines).let { (buffer, n) -> lines = buffer; lineCount = n }
            shownStep = k
        }

        // The camera holds the middle of the two working ends, averaged over a few beats.
        val now = t / beat
        val middle = (-SMOOTH..SMOOTH).map { o ->
            val at = (now + o * SMOOTH_STEP).coerceAtLeast(0.0)
            (along(at) + along(at + count)) / 2.0
        }.average()
        val c = Vector3(depth / 2.0, tallest * 0.4, -middle)
        val turn = Math.toRadians(yaw)
        val elev = Math.toRadians(pitch)
        val e = Vector3(cos(elev) * sin(turn), sin(elev), cos(elev) * cos(turn))
        val view = lookAt(c + e * 400.0, c, Vector3.UNIT_Y)
        val projection = ortho(-w / unit / 2.0, w / unit / 2.0, -h / unit * (1.0 - horizon), h / unit * horizon, 1.0, 800.0)
        val shift = Vector3(0.0, 0.0, -shownFrom.toDouble())

        wire.frame(drawer, stage.bounds, projection, view) {
            lines?.let { wire.edges(drawer, it, lineCount, e, unit, shift) }
            if (wire.meshes.isNotEmpty()) {
                wire.pieces(drawer)
                for (cube in standing) {
                    val centre = Vector3(cube.x + 0.5, cube.y + 0.5, -(cube.slice - shownFrom) - 0.5) + shift
                    wire.piece(drawer, centre, cube.which, cube.quarter)
                }
            }
        }
    }

    private companion object {
        /** Slices a building takes along the row, and its storeys. */
        val WIDTH = listOf(2 to 0.35, 3 to 0.4, 4 to 0.25)
        val HEIGHT = listOf(2 to 0.22, 3 to 0.32, 4 to 0.28, 5 to 0.18)

        /** The camera's average: 2·SMOOTH + 1 samples, SMOOTH_STEP beats apart. */
        const val SMOOTH = 4
        const val SMOOTH_STEP = 2.0

        fun Random.pick(table: List<Pair<Int, Double>>): Int {
            var r = nextDouble() * table.sumOf { it.second }
            for ((value, weight) in table) { r -= weight; if (r <= 0.0) return value }
            return table.last().first
        }
    }
}
