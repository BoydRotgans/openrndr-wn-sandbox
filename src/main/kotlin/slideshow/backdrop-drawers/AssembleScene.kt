// ============================================================================ //
//  No `package` declaration: it stands on loadObjectSheet and loadPieceDetails,
//  which live in the default package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.DepthTestPass
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.WrapMode
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.loadImage
import org.openrndr.draw.Drawer
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.isolated
import org.openrndr.math.Vector2
import org.openrndr.math.Matrix44
import org.openrndr.math.Vector3
import org.openrndr.math.Vector4
import org.openrndr.math.transforms.lookAt
import org.openrndr.math.transforms.ortho
import org.openrndr.shape.Rectangle
import slideshow.Backdrop
import slideshow.Cut
import slideshow.FPS
import slideshow.Sound
import slideshow.Stage
import slideshow.SNAP_SECONDS
import slideshow.Transition
import slideshow.CubicBezier
import slideshow.snap
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Assemble, disassemble, assemble — a DRAFT. A building of catalogue beams stands, is taken apart a
 * beam at a time into an exploded view laid out on a grid, its beams re-arrange along the grid, and
 * they are built up into a different building; then again, for ever. After an exploded axonometric
 * drawing (`Brique-à-brac`) and a sheet of the same blocks packed several ways.
 *
 * **Only the iso sheet is drawn**, every element one of `objects-iso.svg`'s drawings in the WN red on
 * black, outlined in the black of the ground so the joints between elements read.
 *
 * **Every building is solid, and that is why every block is the same beam.** A building is a stack
 * of layers, each a footprint in multiples of three cells laid with three-cell beams, the layers
 * running alternately across and along like a Jenga tower, each footprint standing inside the one
 * below; so there is no hole in it anywhere and a building steps back as it rises. A pile of bricks
 * of different sizes, the first draft, could not close into a solid mass. And because the blocks are
 * one size, any arrangement of them is any building: the next is the same beams dealt anew.
 *
 * **A beam's drawing is the catalogue piece that best fits a beam's box.** A 3 x 1 x 1 box in
 * isometric projects to a known hexagon, and every structural drawing is laid over it in the same
 * rectangle, as drawn and mirrored; the beams are the drawings that overlap it most. Measured by
 * proportion and fill instead, cones and angles came through — they have a box's proportion and
 * fill without its shape — and left dents in the building. Each is stretched to fill its beam's box
 * exactly, and mirrored where the beam runs the other way: mirrored, an isometric drawing is the
 * same piece turned a quarter.
 *
 * **The exploded view is the building's lattice widened**, far across and less up and down, so the
 * beams stand apart on a regular grid rather than scattering.
 *
 * **The motion is snaps, measured off `input/motion-snap.mov`** — [snap] in `Timing.kt`, a short
 * wind-up, one steep frame and a settle, `cubic-bezier(0.7, 0, 0, 1)` over a third of a second
 * whatever the distance — and elements move one after another, not together. So every beam moves on
 * its own, [move] seconds, a [stagger] after the one before: taken off from the top down, moved along
 * the grid one axis at a time as the reference's pieces slide along its lines, and set down from the
 * ground up — so a building is seen built. (It ran on a pure exponential ease-out first, read off
 * the reference too quickly; measured properly, that misses the wind-up.)
 *
 * **Under `solid` the building is precast**: the catalogue's own wall panels — the door wall, the walls
 * with windows — on every face open to the air, and a floor plate over every bay of every storey, every
 * building one of the massings that takes exactly the same kit; see [solidBuilding].
 *
 * One building a projector — anything to be read stays in one of them — each on its own clock, the
 * right half a cycle behind the left. A pure function of the frame.
 */
class AssembleScene(
    override val name: String = "Assemble",
    private val sheet: File,
    private val details: File,
    /** Beams in a building — a multiple of three, since the smallest layer holds three. */
    private val beams: Int = 24,
    /** Pixels one lattice unit takes. */
    private val unit: Double = 56.0,
    /** How far the exploded view widens the lattice across, and up and down: 1 closed. */
    private val spread: Double = 2.4,
    private val rise: Double = 1.6,
    /** Seconds one beam's move takes — the reference's snap, whatever the distance — and between one beam setting off and the next. */
    private val move: Double = SNAP_SECONDS,
    private val stagger: Double = 0.05,
    /** Seconds held assembled, held apart, and held between the two legs of the re-arranging. */
    private val hold: Double = 2.5,
    private val apart: Double = 0.8,
    private val between: Double = 0.15,
    /**
     * How the beams move. `gesture`: a phase of beams a [stagger] apart, each on one snap — the first
     * draft. `grid`: the whole of the motion study (`style-guide/motion.md`, "The snap") — see
     * [gridNow]. `row`: a street of buildings across the whole wall under a slow pan, built at the
     * right and taken down as they leave on the left — see [drawRow]; solid only. `catalogue`: the kit
     * laid flat in a catalogue, built up into a building and laid back, another building each time —
     * see [catalogueFrame]; solid only.
     */
    private val grammar: String = "gesture",
    /** Under `grid`: seconds between the first two beams setting off and between the last two — the phase thickens. */
    private val gapStart: Double = 0.2,
    private val gapEnd: Double = 0.06,
    /** Under `grid`: how fast the camera pulls back, as a share of the view a second, linear, cut at the next building. */
    private val zoom: Double = 0.006,
    private val paper: ColorRGBa = ColorRGBa.BLACK,
    private val piece: ColorRGBa = ColorRGBa.fromHex("FF0000"),
    private val ink: ColorRGBa = ColorRGBa.BLACK,
    /** The outline's weight in pixels. */
    private val line: Double = 2.0,
    /**
     * What stands for a beam. `sheet`: its drawing off `objects-iso.svg`. `solid`: a precast building
     * of catalogue meshes off [objects] — wall panels and floor plates, not beams; see [solidBuilding].
     */
    private val render: String = "sheet",
    private val objects: File? = null,
    /** Under `solid`: the wall panels and the floor plates, by part name, dealt in turn to the pieces of the kit. */
    private val walls: List<String> = listOf("WAND_27", "WAND_33", "WAND_17", "WAND_23", "WAND_6", "WAND_8",
        "WAND_11", "WAND_28", "WAND_10", "WAND_22", "WAND_12", "WAND"),
    private val floors: List<String> = listOf("VLOER", "VLOER_3", "PREDAL", "VLOER_2"),
    /** Under `solid`: the panels that stand on the faces in view, from the ground up in this order — the door and the windows. */
    private val front: List<String> = listOf("WAND_27", "WAND_17", "WAND_33", "WAND_23"),
    /**
     * Under `solid`: how many floor plates and wall panels every building is made of, and how many
     * storeys it may rise. Every building is one of the massings that take exactly these.
     */
    private val plates: Int = 8,
    private val panels: Int = 20,
    private val storeys: Int = 3,
    /** Under `solid`: whether the paths the beams travel are drawn, and in what; see [travelled]. */
    private val guides: Boolean = true,
    private val guide: ColorRGBa = ColorRGBa.fromHex("8C8E96"),
    /** Under `grid`: seconds the finished building stands before the cut. */
    private val endHold: Double = 0.0,
    /**
     * Under `row`: seconds between one building and the next, how many buildings on from the one being
     * taken down the one being built is, and where on the wall — a share of its width from the left — a
     * building is begun and where it is taken down. The pan is what carries a building from the one to
     * the other over [rowLag] periods, so its speed follows from these.
     */
    private val rowPeriod: Double = 14.0,
    private val rowLag: Int = 2,
    private val buildAt: Double = 0.82,
    private val leaveAt: Double = 0.09,
    /** Under `row`: seconds a piece stands outside before it is set in, and before it goes; and how long its path takes to fade once it is in. */
    private val wait: Double = 0.3,
    private val dwell: Double = 0.5,
    private val fade: Double = 0.8,
    /**
     * The curve every leg runs on: [snap] by default, the reference's. The row wall runs a calmer one —
     * asked for as "more calm and friendly" — and grows a piece in and out over [grow] seconds rather
     * than popping it (0 pops).
     */
    private val curve: CubicBezier = snap,
    private val grow: Double = 0.0,
    /**
     * Under `solid`: a look-through drawing when above 0 — every face drawn this opaque with no depth
     * test, so the pieces behind and every edge, the hidden ones too, show through — in [edge], which
     * defaults to the piece colour (the black ink would vanish into the ground).
     */
    private val glass: Double = 0.0,
    private val edge: ColorRGBa? = null,
    /**
     * Under `solid`: a concrete texture worked into the pieces' faces against its own average — the
     * grain without the stone's grey — [grain] strong, one repeat of it across [concreteCells] cells.
     * It is laid on each piece's own faces, so a piece carries its stone as it moves.
     */
    private val concrete: File? = null,
    private val grain: Double = 1.0,
    private val concreteCells: Double = 6.0,
    /**
     * Under `solid`: the exploded view lifts off the ground rather than spreading up and down about the
     * building's middle — what a building standing on a ground needs, where the black wall has none.
     */
    private val fromGround: Boolean = false,
    /**
     * Under `row`: how a piece taken out goes. 0 shrinks it away where it floats. Above 0 it floats,
     * rising and settling [bob] cells over [bobPeriod] seconds, for as long as it stands outside, and then
     * falls — this many cells a second squared, the same for every piece — down through the floor.
     */
    private val gravity: Double = 0.0,
    private val bob: Double = 0.0,
    private val bobPeriod: Double = 3.0,
    /**
     * Under `catalogue`: where the catalogue lies — its middle on the ground, in cells from the
     * building's — how wide it is, and seconds held as a catalogue and as a building.
     */
    private val catalogueAt: Vector2 = Vector2(-20.0, 20.0),
    private val catalogueWidth: Double = 24.0,
    private val catalogueHold: Double = 2.5,
    private val buildHold: Double = 3.0,
    /** Under `gallery`: seconds a piece takes between its cell on the sheet and the air over its place. */
    private val flight: Double = 1.8,
    /**
     * Under `catalogue`: how the kit is laid out. `cells`: a ruled page of cells packed edge to edge.
     * `lattice`: an isometric grid — a hexagon of [latticeRadius] steps of [latticeStep] cells, drawn as
     * its three families of lines — with a piece lying at a node, dealt from the seed. `boxes`: the same
     * hexagon of nodes, each a wire cube [latticeStep] a side, a piece standing in each as it stands in the
     * cube — the middle box the cube's own.
     */
    private val layout: String = "cells",
    private val latticeStep: Double = 6.0,
    private val latticeRadius: Int = 4,
    /** Under `catalogue` on a `lattice`: cells round the building's middle kept clear of pieces, for a grid laid round it. */
    private val latticeClear: Double = 0.0,
    /**
     * Under `boxes`: layers of boxes above and below the cube's, and how far the exploded view carries a
     * piece out — its place in the cube, from the cube's middle, times this — to find its box.
     */
    private val latticeLayers: Int = 0,
    private val latticeSpread: Double = 0.0,
    /**
     * Under `boxes`: which boxes a piece may be carried to, by the box's middle from the cube's — the drawer
     * says which it can show whole. Null: any.
     */
    private val latticeFits: ((Vector3) -> Boolean)? = null,
    /**
     * Under `solid`: what the pieces make. `building`: the precast building of bays and storeys. `cube`: a
     * cube [cubeSize] cells a side cut into blocks — at least [blocks] of them, none longer than [maxSide] —
     * each a catalogue piece stretched to fill it; the same blocks every round, the cube turned another of
     * its 24 ways. See [partitionCube].
     */
    private val form: String = "building",
    /**
     * Under `catalogue`: an exploded view rather than a piece at a time — every piece in one straight move
     * to its place, [stagger] after the one before, the outermost first out and the innermost first back,
     * each to the box that lies outward from where it stands in the cube. The cube then stands the same way
     * every round, since a piece that stood another way in its box would have to flip in the air.
     */
    private val explode: Boolean = false,
    private val cubeSize: Int = 12,
    private val blocks: Int = 30,
    private val maxSide: Int = 6,
    private val seed: Int = 5,
    override val transition: Transition = Cut,
    override val sound: Sound? = null
) : Backdrop() {

    override val background: ColorRGBa get() = paper

    /** A drawing that stands for a beam, and whether it runs along x as drawn (else along z). */
    private class Drawing(val shape: SheetObject, val alongX: Boolean)

    /** Every beam's drawing, fixed for the life of the wall, so a beam can be followed. */
    private var drawings: List<Drawing> = emptyList()

    /**
     * Under `solid`: a piece's mesh, its box's middle and size in the mesh's own frame, and which of the
     * mesh's axes runs along the piece, which stands up, and which goes across it (a panel's thickness).
     */
    private class Solid(val mesh: ObjMesh, val centre: Vector3, val extent: Vector3, val len: Int, val up: Int, val across: Int)

    /** Under `solid`: every piece's mesh, fixed for the life of the wall — the panels first, then the plates. */
    private var solids: List<Solid> = emptyList()
    private val solid = render == "solid"
    private var ready = false
    private var stone: ColorBuffer? = null
    private val blank by lazy { colorBuffer(1, 1).also { it.fill(ColorRGBa.WHITE) } }
    private val wire by lazy { WireCubes(emptyList(), piece, 1.0, ink, paper, line) }
    private var guideLines: VertexBuffer? = null

    /** A piece's place in a building: its lowest corner and its box in cells, and whether it runs along x. */
    private class Slot(val corner: Vector3, val size: Vector3, val alongX: Boolean)

    /** Under `solid`: storeys over a grid of bays, [w] along x and [d] along z. */
    private class Massing(val w: Int, val d: Int, val h: IntArray) {
        fun at(i: Int, j: Int) = if (i in 0 until w && j in 0 until d) h[i + j * w] else 0
    }

    /** How many pieces a building is made of. */
    val pieceCount: Int get() = count

    /** Under `solid`: the height, in cells, every building stands on. */
    val ground: Double get() = if (form == "cube") -cubeSize / 2.0 else -storeys * STOREY / 2.0

    /** Under `solid`: every massing the kit builds exactly, in the order they are built. */
    private var massings: List<Massing> = emptyList()

    private val buildings = HashMap<Long, List<Slot>>()
    private val cubeForm = form == "cube"

    /** Under the `cube` form: a block of the cube — its lowest corner, from the cube's, and its size, in cells. */
    private class Block(val corner: Vector3, val size: Vector3)

    /**
     * Under the `cube` form: the cube cut into blocks, a guillotine cut at a time — the biggest block, give
     * or take, cut across its longest side at a whole cell — until there are [blocks] of them and none is
     * longer than [maxSide]. Blocks of many sizes packed without a gap, as a mason's cube is. Dealt from the
     * seed, once: the pieces are these blocks for the life of the wall.
     */
    private fun partitionCube(): List<Block> {
        fun c(v: Vector3, a: Int) = when (a) { 0 -> v.x; 1 -> v.y; else -> v.z }
        fun with(v: Vector3, a: Int, value: Double) = when (a) { 0 -> Vector3(value, v.y, v.z); 1 -> Vector3(v.x, value, v.z); else -> Vector3(v.x, v.y, value) }
        val rnd = Random(seed * 131 + 17)
        val side = cubeSize.toDouble()
        val out = mutableListOf(Block(Vector3.ZERO, Vector3(side, side, side)))
        while (true) {
            val cuttable = out.filter { b -> (0..2).any { c(b.size, it) >= 2.0 } }
            val tooLong = cuttable.filter { b -> (0..2).any { c(b.size, it) > maxSide } }
            if (cuttable.isEmpty() || (tooLong.isEmpty() && out.size >= blocks)) break
            val pick = tooLong.ifEmpty { cuttable }.maxBy { it.size.x * it.size.y * it.size.z * (0.5 + rnd.nextDouble()) }
            val axis = (0..2).filter { c(pick.size, it) >= 2.0 }.maxBy { c(pick.size, it) + rnd.nextDouble() * 0.9 }
            val length = c(pick.size, axis).toInt()
            val cut = 1 + rnd.nextInt(length - 1)
            out -= pick
            out += Block(pick.corner, with(pick.size, axis, cut.toDouble()))
            out += Block(with(pick.corner, axis, c(pick.corner, axis) + cut), with(pick.size, axis, (length - cut).toDouble()))
        }
        return out
    }

    private val cubeBlocks: List<Block> = if (cubeForm) partitionCube() else emptyList()

    /** Under the `cube` form: the cube's 24 turns, as the axis each axis takes and its sign, in the order the rounds take them. */
    private val turns: List<Pair<IntArray, IntArray>> by lazy {
        val perms = listOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2), intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0))
        val parity = listOf(1, -1, -1, 1, 1, -1)
        perms.indices.flatMap { k ->
            (0 until 8).map { m -> intArrayOf(if (m and 1 == 0) 1 else -1, if (m and 2 == 0) 1 else -1, if (m and 4 == 0) 1 else -1) }
                .filter { sg -> sg[0] * sg[1] * sg[2] == parity[k] }.map { perms[k] to it }
        }.shuffled(Random(seed * 7 + 3))
    }

    private val count = when {
        cubeForm -> cubeBlocks.size
        solid -> plates + panels
        else -> (beams / 3).coerceAtLeast(1) * 3
    }

    /** One phase of beams, a stagger apart: from the first setting off to the last arriving. */
    private val phase get() = stagger * (count - 1) + move
    private val gesture = grammar == "gesture"
    private val row = grammar == "row"
    private val catalogueMode = grammar == "catalogue"

    /**
     * Under `grid`: when each beam sets off in a phase, by its turn — the gaps closing from [gapStart]
     * to [gapEnd], so a phase starts sparse and thickens, as the reference does.
     */
    private val starts: DoubleArray by lazy {
        val out = DoubleArray(count)
        for (k in 1 until count) {
            val f = if (count > 2) (k - 1).toDouble() / (count - 2) else 0.0
            out[k] = out[k - 1] + gapStart + (gapEnd - gapStart) * f
        }
        out
    }

    /** Under `grid`: a phase, from the first beam setting off to the last one's third leg landing. */
    private val gridPhase get() = starts.last() + 3.0 * move

    private val cycle get() =
        if (gesture) hold + phase + apart + phase + between + phase + apart + phase
        else hold + gridPhase + apart + gridPhase + apart + gridPhase + endHold

    override fun load(program: Program) {
        if (solid) { loadSolid(); return }
        val sheetDrawings = if (sheet.isFile) loadObjectSheet(sheet) else emptyList()
        if (sheetDrawings.isEmpty()) { println("assemble: no sheet at ${sheet.path}"); return }
        val register = loadPieceDetails(details).takeIf { it.size == sheetDrawings.size } ?: emptyList()

        // Each structural drawing laid over the outline a 3 x 1 x 1 box projects to, in the same
        // rectangle, as drawn and mirrored: how much the two overlap is how well it can stand for a
        // beam. Measuring proportion and fill instead picked cones and angles, which have a box's
        // proportion and fill and are not box-shaped.
        val ranked = sheetDrawings.indices.filter { i ->
            val d = register.getOrNull(i) ?: return@filter true
            d.type != "IfcDiscreteAccessory" && !NOT_PARTS.containsMatchIn(d.name)
        }.map { i ->
            val asDrawn = overlap(sheetDrawings[i], mirrored = false)
            val mirrored = overlap(sheetDrawings[i], mirrored = true)
            Triple(i, asDrawn >= mirrored, maxOf(asDrawn, mirrored))
        }.sortedByDescending { it.third }
        val chosen = ranked.take(CANDIDATES)
        if (chosen.isEmpty()) { println("assemble: no drawing on ${sheet.name} can stand for a beam"); return }
        val rnd = Random(seed)
        drawings = List(count) { k ->
            val (i, alongX, _) = if (k < chosen.size) chosen[k] else chosen[rnd.nextInt(chosen.size)]
            Drawing(sheetDrawings[i], alongX)
        }.shuffled(rnd)
        ready = true
        println("assemble: $count beams from ${chosen.size} drawings of ${sheetDrawings.size}" +
                if (register.isEmpty()) "" else chosen.joinToString(prefix = " — ") { register[it.first].name })
    }

    /**
     * How much [drawing] overlaps the outline of a beam running along x — or along z when
     * [mirrored] — each fitted to the same rectangle: intersection over union, on a coarse grid.
     */
    private fun overlap(drawing: SheetObject, mirrored: Boolean): Double {
        val rings = drawing.shapes.flatMap { it.contours }.map { it.equidistantPositions(120) }
        val b = drawing.bounds
        // The beam's outline in the unit square: the hull of its box's corners projected.
        val corners = listOf(0.0, 3.0).flatMap { x -> listOf(0.0, 1.0).flatMap { y -> listOf(0.0, 1.0).map { z ->
            Vector2((x - z) * COS30, -y + (x + z) * 0.5) } } }
        val lo = Vector2(corners.minOf { it.x }, corners.minOf { it.y })
        val hi = Vector2(corners.maxOf { it.x }, corners.maxOf { it.y })
        val hull = convexHull(corners.map { (it - lo) / (hi - lo) }.map { if (mirrored) Vector2(1.0 - it.x, it.y) else it })
        var both = 0; var either = 0
        for (gy in 0 until GRID) for (gx in 0 until GRID) {
            val u = (gx + 0.5) / GRID; val v = (gy + 0.5) / GRID
            val inDrawing = evenOdd(rings, Vector2(b.x + u * b.width, b.y + v * b.height))
            val inBox = inConvex(hull, Vector2(u, v))
            if (inDrawing && inBox) both++
            if (inDrawing || inBox) either++
        }
        return if (either == 0) 0.0 else both.toDouble() / either
    }

    private fun evenOdd(rings: List<List<Vector2>>, p: Vector2): Boolean {
        var inside = false
        for (ring in rings) for (k in ring.indices) {
            val a = ring[k]; val c = ring[(k + 1) % ring.size]
            if ((a.y > p.y) != (c.y > p.y) && p.x < a.x + (p.y - a.y) * (c.x - a.x) / (c.y - a.y)) inside = !inside
        }
        return inside
    }

    private fun convexHull(points: List<Vector2>): List<Vector2> {
        val sorted = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        fun cross(o: Vector2, a: Vector2, b: Vector2) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = mutableListOf<Vector2>()
        for (p in sorted) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.lastIndex); lower += p }
        val upper = mutableListOf<Vector2>()
        for (p in sorted.reversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.lastIndex); upper += p }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    private fun inConvex(hull: List<Vector2>, p: Vector2): Boolean {
        var sign = 0
        for (k in hull.indices) {
            val a = hull[k]; val c = hull[(k + 1) % hull.size]
            val cr = (c.x - a.x) * (p.y - a.y) - (c.y - a.y) * (p.x - a.x)
            val sg = if (cr > 0) 1 else if (cr < 0) -1 else 0
            if (sg != 0) { if (sign == 0) sign = sg else if (sg != sign) return false }
        }
        return true
    }

    /**
     * Building [n]: a place for every beam, dealt from the seed and [n]. Layers from the ground up,
     * each a footprint in multiples of three standing inside the one below and holding a third of
     * its cells in beams, running across and along by turns, until every beam has a place — then
     * the beams shuffled onto the places, and the whole centred on its own middle.
     */
    private fun building(n: Long): List<Slot> = buildings.getOrPut(n) {
        if (buildings.size > 16) buildings.keys.filter { it < n - 4 }.forEach { buildings.remove(it) }
        when {
            cubeForm -> cubeBuilding(n)
            solid -> solidBuilding(n)
            else -> beamBuilding(n)
        }
    }

    /**
     * Under the `cube` form: round [n]'s cube — the blocks turned about the cube's middle by one of its 24
     * turns, a joint's width clear of each other, standing on the ground.
     */
    private fun cubeBuilding(n: Long): List<Slot> {
        fun c(v: Vector3, a: Int) = when (a) { 0 -> v.x; 1 -> v.y; else -> v.z }
        val (axes, signs) = if (explode) intArrayOf(0, 1, 2) to intArrayOf(1, 1, 1)
                            else turns[Math.floorMod(n, turns.size.toLong()).toInt()]
        val half = cubeSize / 2.0
        fun turned(v: Vector3) = DoubleArray(3) { i -> signs[i] * c(v, axes[i]) }.let { Vector3(it[0], it[1], it[2]) }
        return cubeBlocks.map { b ->
            val a = turned(b.corner - Vector3(half, half, half))
            val z = turned(b.corner + b.size - Vector3(half, half, half))
            val lo = Vector3(min(a.x, z.x), min(a.y, z.y), min(a.z, z.z)) + Vector3(JOINT / 2.0)
            val hi = Vector3(max(a.x, z.x), max(a.y, z.y), max(a.z, z.z)) - Vector3(JOINT / 2.0)
            Slot(lo, hi - lo, true)
        }
    }

    private fun beamBuilding(n: Long): List<Slot> {
        val rnd = Random(seed * 1_000_003L + n * 7_919L)
        val slots = mutableListOf<Slot>()
        // Footprints in thirds: 1 x 1 is 3 x 3 cells and 3 beams.
        var w = 1 + rnd.nextInt(3); var d = 1 + rnd.nextInt(3)
        var ox = 0; var oz = 0
        var y = 0
        var alongX = rnd.nextBoolean()
        while (slots.size < count) {
            val left = count - slots.size
            // Step back now and then, and whenever this footprint would take more beams than are left.
            while (w * d * 3 > left || (y > 0 && w * d > 1 && rnd.nextDouble() < 0.3)) {
                if ((w > d || (w == d && rnd.nextBoolean())) && w > 1) { w--; if (rnd.nextBoolean()) ox++ }
                else if (d > 1) { d--; if (rnd.nextBoolean()) oz++ }
                else break
            }
            val cw = w * 3; val cd = d * 3
            if (alongX) {
                for (z in 0 until cd) for (x in 0 until cw step 3) slots += Slot(Vector3((ox * 3 + x).toDouble(), y.toDouble(), (oz * 3 + z).toDouble()), BEAM_X, true)
            } else {
                for (x in 0 until cw) for (z in 0 until cd step 3) slots += Slot(Vector3((ox * 3 + x).toDouble(), y.toDouble(), (oz * 3 + z).toDouble()), BEAM_Z, false)
            }
            y++
            alongX = !alongX
        }
        val placed = slots.take(count)
        val lo = placed.map { it.corner }
        val hi = placed.map { it.corner + it.size }
        val middle = Vector3(
            (lo.minOf { it.x } + hi.maxOf { it.x }) / 2.0,
            (lo.minOf { it.y } + hi.maxOf { it.y }) / 2.0,
            (lo.minOf { it.z } + hi.maxOf { it.z }) / 2.0
        )
        return placed.map { Slot(it.corner - middle, it.size, it.alongX) }.shuffled(rnd)
    }

    /**
     * Under `solid`: every massing of up to [storeys] storeys on a grid of two bays by three that takes
     * exactly [plates] floor plates and [panels] wall panels — a plate over every bay of every storey,
     * a panel on every face of one that is open to the air — in one piece, and each once however it
     * was found. Shuffled from the seed: that is the order the buildings come in.
     */
    private fun massingsFor(): List<Massing> {
        val out = LinkedHashMap<String, Massing>()
        val levels = storeys + 1
        for ((w, d) in listOf(3 to 2, 2 to 3)) {
            val cells = w * d
            var total = 1
            repeat(cells) { total *= levels }
            for (code in 0 until total) {
                val h = IntArray(cells)
                var c = code
                for (k in 0 until cells) { h[k] = c % levels; c /= levels }
                if (h.sum() != plates) continue
                val m = Massing(w, d, h)
                var faces = 0
                for (j in 0 until d) for (i in 0 until w) for ((di, dj) in SIDES) faces += max(0, m.at(i, j) - m.at(i + di, j + dj))
                if (faces != panels || !connected(m)) continue
                val t = trimmed(m)
                out.getOrPut("${t.w}x${t.d}:${t.h.joinToString(",")}") { t }
            }
        }
        return out.values.toList().shuffled(Random(seed))
    }

    private fun connected(m: Massing): Boolean {
        val built = (0 until m.w * m.d).filter { m.h[it] > 0 }
        if (built.isEmpty()) return false
        val seen = hashSetOf(built.first())
        val todo = ArrayDeque(listOf(built.first()))
        while (todo.isNotEmpty()) {
            val k = todo.removeFirst()
            val i = k % m.w; val j = k / m.w
            for ((di, dj) in SIDES) {
                val a = i + di; val b = j + dj
                if (m.at(a, b) > 0 && seen.add(a + b * m.w)) todo += a + b * m.w
            }
        }
        return seen.size == built.size
    }

    /** [m] with its empty rows and columns taken off, so a massing found in two places is one. */
    private fun trimmed(m: Massing): Massing {
        val built = (0 until m.w * m.d).filter { m.h[it] > 0 }
        val i0 = built.minOf { it % m.w }; val i1 = built.maxOf { it % m.w }
        val j0 = built.minOf { it / m.w }; val j1 = built.maxOf { it / m.w }
        val w = i1 - i0 + 1; val d = j1 - j0 + 1
        return Massing(w, d, IntArray(w * d) { m.at(i0 + it % w, j0 + it / w) })
    }

    /**
     * Under `solid`: building [n], a precast building — a floor plate over every bay of every storey
     * and a wall panel on every face open to the air, every piece a [JOINT] clear of the next so each
     * reads as a piece. A panel along x runs the bay's whole length and one along z stops short of it,
     * so two meet at a corner without passing through each other.
     *
     * The pieces are dealt onto the places: the [front] panels — the door and the windows — onto the
     * faces in view, from the ground up in the order they are named, so the door stands at the foot of
     * the building; every other panel and plate anywhere. The ground is the same height for every
     * building, so the next one stands where this one stood.
     */
    private fun solidBuilding(n: Long): List<Slot> {
        val rnd = Random(seed * 1_000_003L + n * 7_919L)
        val m = massings[Math.floorMod(n, massings.size.toLong()).toInt()]
        class Place(val slot: Slot, val inView: Boolean, val storey: Int)
        val wallsAt = ArrayList<Place>()
        val platesAt = ArrayList<Place>()
        val g = JOINT; val t = WALL; val s = PLATE
        val tall = STOREY - s - g
        val shift = Vector3(-m.w * BAY / 2.0, ground, -m.d * BAY / 2.0)
        for (j in 0 until m.d) for (i in 0 until m.w) for (k in 0 until m.at(i, j)) {
            val x0 = i * BAY; val y0 = k * STOREY; val z0 = j * BAY
            fun open(di: Int, dj: Int) = m.at(i + di, j + dj) <= k
            platesAt += Place(Slot(Vector3(x0 + g / 2, y0 + STOREY - s, z0 + g / 2) + shift,
                Vector3(BAY - g, s, BAY - g), rnd.nextBoolean()), false, k)
            for (dj in intArrayOf(-1, 1)) if (open(0, dj))
                wallsAt += Place(Slot(Vector3(x0 + g / 2, y0, if (dj > 0) z0 + BAY - t else z0) + shift,
                    Vector3(BAY - g, tall, t), true), dj > 0, k)
            for (di in intArrayOf(-1, 1)) if (open(di, 0)) {
                val from = z0 + g / 2 + (if (open(0, -1)) t else 0.0)
                val to = z0 + BAY - g / 2 - (if (open(0, 1)) t else 0.0)
                wallsAt += Place(Slot(Vector3(if (di > 0) x0 + BAY - t else x0, y0, from) + shift,
                    Vector3(t, tall, to - from), false), di > 0, k)
            }
        }
        val slots = arrayOfNulls<Slot>(count)
        val taken = HashSet<Place>()
        fun rank(b: Int) = front.indexOf(solids[b].mesh.name)
        val featured = (0 until panels).filter { rank(it) >= 0 }.sortedWith(compareBy({ rank(it) }, { it }))
        val inView = wallsAt.filter { it.inView }.map { it to rnd.nextDouble() }
            .sortedWith(compareBy({ it.first.storey }, { it.second })).map { it.first }
        featured.zip(inView).forEach { (b, p) -> slots[b] = p.slot; taken += p }
        (0 until panels).filter { slots[it] == null }.shuffled(rnd)
            .zip(wallsAt.filter { it !in taken }.shuffled(rnd)).forEach { (b, p) -> slots[b] = p.slot }
        platesAt.shuffled(rnd).forEachIndexed { k, p -> slots[panels + k] = p.slot }
        return slots.map { it!! }
    }

    /**
     * Beam [b]'s corner in building [n], closed or apart: apart its lattice is widened, the beam its own
     * size. A solid piece is moved out by its middle rather than its corner, so a thin panel on the far
     * face goes out as far as one on the near face.
     */
    private fun at(n: Long, b: Int, apart: Boolean): Vector3 {
        val slot = building(n)[b]
        val c = slot.corner
        if (!apart) return c
        if (!solid) return Vector3(c.x * spread, c.y * rise, c.z * spread)
        val m = c + slot.size * 0.5
        val pivot = if (fromGround) ground else 0.0
        return Vector3(m.x * spread, pivot + (m.y - pivot) * rise, m.z * spread) - slot.size * 0.5
    }

    /**
     * Each beam's turn in building [n] from the top down — the order it is taken off in; set down,
     * the order runs the other way, from the ground up. Outer before inner on a level.
     */
    private fun topDown(n: Long): IntArray {
        val slots = building(n)
        val order = slots.indices.sortedWith(compareByDescending<Int> { slots[it].corner.y }
            .thenByDescending { slots[it].corner.x * slots[it].corner.x + slots[it].corner.z * slots[it].corner.z })
        return IntArray(slots.size).also { rank -> order.forEachIndexed { k, b -> rank[b] = k } }
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        if (!ready) return
        val t = stage.frame.toDouble() / FPS
        if (row) { drawRow(drawer, stage.bounds, t); return }
        if (catalogueMode) { val (p, l) = catalogueFrame(t); drawSolid(drawer, stage.bounds, p, 1.0, l); return }
        val half = stage.width / 2.0
        for (side in 0..1) {
            val pane = Rectangle(stage.bounds.x + side * half, stage.bounds.y, half, stage.height)
            drawBuilding(drawer, pane, t - side * cycle / 2.0)
        }
    }

    /** Where a beam is, its box, and which way it runs, at one moment. */
    private class Now(val corner: Vector3, val size: Vector3, val alongX: Boolean, val lying: Boolean = false)

    /**
     * Under `solid`: piece [b] where [now] says, [seed] telling one copy of it from another, and how far
     * it is from standing in its building — 1 outside or on its way, 0 set in.
     */
    private class Placed(val b: Int, val now: Now, val seed: Int, val active: Double = 0.0)

    /** A piece of the row for a drawer of its own: its mesh, its model matrix in cells, how far from set in it is (1 outside, 0 in), and which copy. */
    class RowPiece(val mesh: ObjMesh, val model: Matrix44, val active: Double, val seed: Int)

    /**
     * The row at [t] seconds for a drawer of its own — the city course draws it under its sun:
     * building k stands at [place] (k, seconds since it was begun), in cells. Every piece up with its
     * model matrix, and the paths drawn so far, each with its strength.
     */
    fun row(t: Double, place: (Long, Double) -> Vector3): Pair<List<RowPiece>, List<Pair<Pair<Vector3, Vector3>, Double>>> {
        if (!ready) return emptyList<RowPiece>() to emptyList()
        val (placed, lines) = rowFrame(t, place)
        return placed.map { RowPiece(solids[it.b].mesh, modelOf(solids[it.b], it.now), it.active, it.seed) } to lines
    }

    /** One building's cycle at [time] seconds, into [pane]. */
    private fun drawBuilding(drawer: Drawer, pane: Rectangle, time: Double) {
        val n = floor(time / cycle).toLong()
        val u = time - n * cycle
        val moves = if (gesture) emptyList() else gridMoves(n)
        val now = if (gesture) gestureNow(n, u) else gridNow(n, u, moves)
        // The camera: still for a gesture; under `grid` a linear pull-back through the whole cycle,
        // cut when the next building begins — mid-move, as the reference cuts.
        val lens = if (gesture) 1.0 else 1.0 + zoom * (cycle - u)
        if (solid) { drawSolid(drawer, pane, now.indices.map { Placed(it, now[it], it) }, lens, if (gesture || !guides) emptyList() else travelled(u, moves)); return }

        val origin = pane.center + Vector2(0.0, pane.height * 0.08)
        // Far to near: the viewer looks down from +x, +y, +z, so a larger sum is nearer.
        val order = (0 until count).sortedBy { b -> (now[b].corner + now[b].size * 0.5).let { it.x + it.y + it.z } }
        drawer.isolated {
            drawer.drawStyle.clip = pane
            drawer.fill = piece
            drawer.stroke = ink
            for (b in order) {
                val rect = projected(now[b].corner, now[b].size, origin, unit * lens)
                val drawing = drawings[b]
                val mirror = drawing.alongX != now[b].alongX
                drawer.isolated {
                    // Stretched to fill the beam's box exactly, and mirrored where it runs the other way.
                    val sx = rect.width / drawing.shape.bounds.width
                    val sy = rect.height / drawing.shape.bounds.height
                    drawer.translate(rect.center)
                    drawer.scale(if (mirror) -sx else sx, sy)
                    drawer.translate(-drawing.shape.bounds.center)
                    drawer.strokeWeight = line / sqrt(sx * sy)
                    drawer.shapes(drawing.shape.shapes)
                }
            }
        }
    }

    /** The first draft's motion: a phase of beams a [stagger] apart, each on one snap, the re-arranging in two legs. */
    private fun gestureNow(n: Long, u: Double): List<Now> {
        // The phases: held, taken apart, held apart, moved across, moved along, held apart, set down.
        val off = hold
        val across = off + phase + apart
        val along = across + phase + between
        val down = along + phase + apart
        val takeOff = topDown(n)
        val setDown = topDown(n + 1).let { r -> IntArray(r.size) { count - 1 - r[it] } }

        /** How far beam [rank]'s move in the phase starting at [start] has gone: a snap, 0 to 1. */
        fun snapped(start: Double, rank: Int): Double = snap((u - start - rank * stagger) / move)

        return (0 until count).map { b ->
            val a0 = at(n, b, false); val a1 = at(n, b, true)
            val b1 = at(n + 1, b, true); val b0 = at(n + 1, b, false)
            val was = building(n)[b]; val will = building(n + 1)[b]
            val rank = b
            when {
                u < across -> Now(a0 + (a1 - a0) * snapped(off, takeOff[b]), was.size, was.alongX)
                u < along -> {
                    // Across the grid first: x, and up or down.
                    val f = snapped(across, rank)
                    Now(Vector3(a1.x + (b1.x - a1.x) * f, a1.y + (b1.y - a1.y) * f, a1.z), was.size, was.alongX)
                }
                u < down -> {
                    // Then along it, turned the quarter it runs the other way in the next building.
                    val f = snapped(along, rank)
                    val turned = u >= along + rank * stagger
                    val p = Vector3(b1.x, b1.y, a1.z + (b1.z - a1.z) * f)
                    if (turned) Now(p, will.size, will.alongX) else Now(p, was.size, was.alongX)
                }
                else -> Now(b1 + (b0 - b1) * snapped(down, setDown[b]), will.size, will.alongX)
            }
        }
    }

    /**
     * The motion study's whole grammar, where [gestureNow] took only its curve:
     *
     * - **Paths on the grid.** A beam moves one axis at a time, each leg a snap: lifted off, slid
     *   across, slid along — and set down the other way round, slid into its column and dropped
     *   last, so a building is seen filled from above. A diagonal is two legs.
     * - **Sparse, then dense.** Beams set off [gapStart] apart at first and [gapEnd] by the end, so a
     *   phase gathers pace instead of ticking at one rate.
     * - **Pops.** A beam that runs the other way in the next building turns in one frame, as its
     *   first slide begins — a change of state in place, never animated.
     * - **One direction each phase.** Taken off from the top down; moved across the exploded grid in
     *   reading order, left to right; set down from the ground up.
     */
    private fun gridNow(n: Long, u: Double, moves: List<Array<Move>>): List<Now> {
        val t1 = hold; val t3 = t1 + gridPhase + apart; val t5 = t3 + gridPhase + apart
        return (0 until count).map { b ->
            val m = moves[b]
            when {
                u < t1 -> Now(m[0].from, m[0].sizeBefore, m[0].before)
                u < t3 -> m[0].at(u, move, curve)
                u < t5 -> m[1].at(u, move, curve)
                else -> m[2].at(u, move, curve)
            }
        }
    }

    /**
     * One beam's move in one phase: the corner [from] to the corner [to] one axis at a time in [axes]
     * order, a snap a leg from [start]; running [before] in a box of [sizeBefore] until [turn], and
     * [after] in [sizeAfter] from then on. [centred] moves the box's middle rather than its corner, so a
     * piece that turns turns about its middle.
     */
    private class Move(val from: Vector3, val to: Vector3, val axes: IntArray, val start: Double,
                       val before: Boolean, val after: Boolean, val sizeBefore: Vector3, val sizeAfter: Vector3,
                       val turn: Double, val centred: Boolean) {
        /** Where the beam is at [u], a leg taking [move] seconds on [curve]. */
        fun at(u: Double, move: Double, curve: CubicBezier = snap): Now {
            val f = DoubleArray(3) { 1.0 }
            axes.forEachIndexed { i, a -> f[a] = curve((u - start - i * move) / move) }
            val turned = u >= turn
            val size = if (turned) sizeAfter else sizeBefore
            val a = if (centred) from + sizeBefore * 0.5 else from
            val b = if (centred) to + sizeAfter * 0.5 else to
            val p = Vector3(a.x + (b.x - a.x) * f[0], a.y + (b.y - a.y) * f[1], a.z + (b.z - a.z) * f[2])
            return Now(if (centred) p - size * 0.5 else p, size, if (turned) after else before)
        }
    }

    /**
     * Every beam's three moves in building [n]'s cycle — taken apart, moved across the grid, set down —
     * each with its path and its clock, so the motion and the path lines read the one schedule.
     */
    private fun gridMoves(n: Long): List<Array<Move>> {
        val t1 = hold; val t3 = t1 + gridPhase + apart; val t5 = t3 + gridPhase + apart
        val takeOff = topDown(n)
        val setDown = topDown(n + 1).let { r -> IntArray(r.size) { count - 1 - r[it] } }
        // Across the exploded grid in reading order: left to right on the wall, then back to front.
        val reading = (0 until count).sortedWith(compareBy<Int> { at(n, it, true).let { p -> p.x - p.z } }
            .thenBy { at(n, it, true).let { p -> p.x + p.z } })
            .let { order -> IntArray(count).also { rank -> order.forEachIndexed { k, b -> rank[b] = k } } }
        return (0 until count).map { b ->
            val a0 = at(n, b, false); val a1 = at(n, b, true)
            val b1 = at(n + 1, b, true); val b0 = at(n + 1, b, false)
            val was = building(n)[b]; val will = building(n + 1)[b]
            val across = t3 + starts[reading[b]]
            arrayOf(
                Move(a0, a1, UP_ACROSS_ALONG, t1 + starts[takeOff[b]], was.alongX, was.alongX, was.size, was.size, Double.MAX_VALUE, solid),
                // It turns as its first slide begins: one frame, no animation.
                Move(a1, b1, UP_ACROSS_ALONG, across, was.alongX, will.alongX, was.size, will.size, across + move, solid),
                Move(b1, b0, ALONG_ACROSS_DOWN, t5 + starts[setDown[b]], will.alongX, will.alongX, will.size, will.size, Double.MAX_VALUE, solid)
            )
        }
    }

    /**
     * Under `row`: a street of buildings across the whole wall, the camera panning slowly right along
     * it — linear, the one continuous motion — so every building drifts from right to left.
     *
     * - **A building is built where it enters, at the right of the wall** ([buildAt]): its pieces
     *   arrive outside it, in the exploded view, from the ground up, each growing in where it stands
     *   ([grow]) with its path to its place drawn at once, and each is set into the building a moment
     *   later, one axis at a time.
     * - **It is taken down as it leaves on the left** ([leaveAt]): from the top down each piece is
     *   lifted out to the exploded view, its path drawn as it lands, and a moment later it goes.
     * - **One goes as one comes.** The building taken down is [rowLag] on from the one being built, so
     *   the two run on one clock: a piece goes on the left on the very frame a piece arrives on the
     *   right, and the wall always holds the same number of pieces. The pan is what carries a building
     *   from the one place to the other, so its speed is that distance over [rowLag] periods.
     *
     * Each building is one of the kit's massings, the next dealt from the seed. A pure function of the
     * frame: which buildings are up, where, and how far each piece has got are all read off [t].
     */
    private fun drawRow(drawer: Drawer, pane: Rectangle, t: Double) {
        val w = pane.width
        val speed = (buildAt - leaveAt) * w / (rowLag * rowPeriod)
        // A step (a, 0, -a) along the street is sqrt(3) * a pixels across the wall.
        val perUnit = sqrt(3.0) * unit
        val (pieces, lines) = rowFrame(t) { _, rel ->
            val a = (buildAt * w - speed * rel - w / 2.0) / perUnit
            Vector3(a, 0.0, -a)
        }
        drawSolid(drawer, pane, pieces, 1.0, lines)
    }

    /** The row at [t]: every piece up and the paths drawn, building k standing at [place] (k, seconds since it was begun). */
    private fun rowFrame(t: Double, place: (Long, Double) -> Vector3): Pair<List<Placed>, List<Pair<Pair<Vector3, Vector3>, Double>>> {
        val last = starts.last()
        val newest = floor(t / rowPeriod).toLong()
        val pieces = ArrayList<Placed>()
        val lines = ArrayList<Pair<Pair<Vector3, Vector3>, Double>>()
        for (k in newest - rowLag - 1..newest) {
            val built = k * rowPeriod
            val leaves = built + rowLag * rowPeriod
            val rel = t - built
            val relLeave = t - leaves
            if (rel < 0.0 || relLeave >= last + grow) continue
            val offset = place(k, rel)
            val down = topDown(k)
            val slots = building(k)
            for (e in 0 until count) {
                val slot = slots[e]
                val outside = at(k, e, true); val inside = at(k, e, false)
                // It arrives in its turn from the ground up, as the piece in the same turn goes on the left.
                val arrives = starts[count - 1 - down[e]]
                if (rel < arrives) continue
                val goes = starts[down[e]]
                val setIn = Move(outside, inside, ALONG_ACROSS_DOWN, arrives + grow + move + wait,
                    slot.alongX, slot.alongX, slot.size, slot.size, Double.MAX_VALUE, true)
                val takeOut = Move(inside, outside, UP_ACROSS_ALONG, goes - dwell - 3.0 * move,
                    slot.alongX, slot.alongX, slot.size, slot.size, Double.MAX_VALUE, true)
                val out = takeOut.start + 3.0 * move
                // Floating: a slow rise and settle from the moment it lands outside, frozen as it lets go.
                fun floating(u: Double) = if (bob <= 0.0 || u <= out) 0.0
                    else bob * sin(2.0 * PI * (min(u, goes) - out) / bobPeriod)
                val falls = gravity > 0.0
                // It is gone once it has fallen its whole height below the floor, or shrunk away.
                val drop = outside.y + slot.size.y + floating(goes) - ground
                val fallTime = if (falls) sqrt(2.0 * max(drop, 0.0) / gravity) else grow
                if (relLeave >= goes + fallTime) continue
                val moved = if (relLeave >= takeOut.start) takeOut.at(relLeave, move, curve) else setIn.at(rel, move, curve)
                val fallen = if (falls && relLeave > goes) 0.5 * gravity * (relLeave - goes) * (relLeave - goes) else 0.0
                val now = Now(moved.corner + Vector3(0.0, floating(relLeave) - fallen, 0.0), moved.size, moved.alongX)
                // Grown in where it arrives and, unless it falls, shrunk away where it goes, about its own middle.
                val size = when {
                    !falls && relLeave >= goes -> 1.0 - curve((relLeave - goes) / grow.coerceAtLeast(1e-6))
                    rel < arrives + grow -> curve((rel - arrives) / grow.coerceAtLeast(1e-6))
                    else -> 1.0
                }
                if (size <= 0.0) continue
                val middle = now.corner + now.size * 0.5
                val landed = setIn.start + 3.0 * move
                val active = if (relLeave >= takeOut.start) ((relLeave - takeOut.start) / 0.3).coerceIn(0.0, 1.0)
                             else 1.0 - ((rel - landed) / ACCENT_FADE).coerceIn(0.0, 1.0)
                pieces += Placed(e, Now(middle - now.size * (size * 0.5) + offset, now.size * size, now.alongX, now.lying), (k * count + e).toInt(), active)
                if (!guides) continue
                // Its way in, drawn as it arrives outside and gone once it is in.
                val strength = 1.0 - ((rel - landed) / fade).coerceIn(0.0, 1.0)
                if (strength > 0.0) pathLines(setIn, arrives, rel, strength, offset, lines)
                // Its way out, drawn as it lands outside, and gone with it — fading as it falls.
                val leaving = if (falls) 1.0 - ((relLeave - goes) / fallTime.coerceAtLeast(1e-6)).coerceIn(0.0, 1.0) else size
                if (relLeave >= out && leaving > 0.0) pathLines(takeOut, out, relLeave, leaving, offset, lines)
            }
        }
        return pieces to lines
    }

    /** Under `catalogue`: a piece's box in the catalogue — the cell ruled round it, and where it lies in it. */
    private class Home(val cell: Rectangle, val corner: Vector3, val size: Vector3, val alongX: Boolean)

    /** Under `catalogue`: every piece's box, fixed for the life of the wall — its place in the kit. */
    private var homes: List<Home> = emptyList()

    /** Under `catalogue` on a `lattice`: its lines on the floor, drawn the whole time. */
    private var latticeLines: List<Pair<Vector3, Vector3>> = emptyList()

    /** Under `catalogue`: seconds from the start of a cycle to the first building standing whole. */
    val builtAt: Double get() = catalogueHold + catalogueLift

    /**
     * Under `catalogue` on a `lattice`: the isometric grid. Nodes (i, j) a [latticeStep] apart on the floor,
     * kept where |i|, |j| and |i - j| are all within the radius — a hexagon standing on its points, its
     * sides upright on screen — and its lines along x, along z and along the diagonal, which the isometric
     * view stands upright. The pieces are dealt to nodes from the seed, each lying face up, its length
     * across x or z. The radius grows if the hexagon has fewer nodes than there are pieces.
     */
    private fun packLattice(): List<Home> {
        val rnd = Random(seed * 31 + 7)
        val s = latticeStep
        // A node is free unless it lies within the clearing round the building's middle.
        fun free(i: Int, j: Int) = latticeClear <= 0.0 ||
            abs(catalogueAt.x + i * s) >= latticeClear || abs(catalogueAt.y + j * s) >= latticeClear
        // The region: a hexagon on its points for the flat lattice; for boxes a square, the same from every
        // corner a quarter turn apart, since a camera goes round the boxes and a hexagon seen from the next
        // corner is a long flat strip.
        fun inside(i: Int, j: Int, r: Int) = if (layout == "boxes") true else abs(i - j) <= r
        fun nodesOf(r: Int) = (-r..r).flatMap { i -> (-r..r).map { j -> i to j } }.filter { (i, j) -> inside(i, j, r) && free(i, j) }
        var r = latticeRadius
        while (nodesOf(r).size < count) r++
        val y = ground + 0.02
        fun at(i: Int, j: Int) = Vector3(catalogueAt.x + i * s, y, catalogueAt.y + j * s)
        val lines = mutableListOf<Pair<Vector3, Vector3>>()
        for (c in -r..r) {
            lines += at(maxOf(-r, c - r), c) to at(minOf(r, c + r), c)
            lines += at(c, maxOf(-r, c - r)) to at(c, minOf(r, c + r))
            val j0 = maxOf(-r, -r - c); val j1 = minOf(r, r - c)
            lines += at(j0 + c, j0) to at(j1 + c, j1)
        }
        if (layout == "boxes") {
            // Every box of the hexagon, the middle one too, as the edges of its cube, each edge once.
            val edges = WireCubes.Edges()
            for (i in -r..r) for (j in -r..r) if (inside(i, j, r)) for (k in -latticeLayers..latticeLayers) edges.cube(i, k, j)
            lines.clear()
            edges.forEach { x, yy, z, axis ->
                val a = Vector3(catalogueAt.x + (x - 0.5) * s, ground + yy * s, catalogueAt.y + (z - 0.5) * s)
                val along = when (axis) { 0 -> Vector3.UNIT_X; 1 -> Vector3.UNIT_Y; else -> Vector3.UNIT_Z }
                lines += a to (a + along * s)
            }
        }
        latticeLines = lines
        // Every box of every layer: (i, j) on the floor and k the layer, the cube's box (0, 0, 0) left out.
        val boxLayers = if (layout == "boxes") latticeLayers else 0
        val nodes3 = nodesOf(r).flatMap { (i, j) -> (-boxLayers..boxLayers).map { k -> Triple(i, j, k) } } +
            (if (layout == "boxes" && latticeClear > 0.0 && boxLayers > 0) (1..boxLayers).flatMap { k -> listOf(Triple(0, 0, k), Triple(0, 0, -k)) } else emptyList())
        val nodes: List<Triple<Int, Int, Int>> = if (explode && cubeForm) {
            // Each block to the free box nearest to where it stands in the cube carried outward, the
            // outermost choosing first, so the kit comes apart as an exploded drawing does.
            val centres = building(0).map { it.corner + it.size * 0.5 - Vector3(0.0, ground + cubeSize / 2.0, 0.0) }
            val gain = if (latticeSpread > 0.0) latticeSpread else r * s / (cubeSize / 2.0) * 0.75
            // Only boxes the drawer can show whole, and if they are too few, the nearest of the rest.
            fun middle(n: Triple<Int, Int, Int>) =
                Vector3(catalogueAt.x + n.first * s, ground + (n.third + 0.5) * s - (ground + cubeSize / 2.0), catalogueAt.y + n.second * s)
            val all = nodes3.distinct()
            val seen = all.filter { latticeFits?.invoke(middle(it)) ?: true }
            val free = (seen + all.filter { it !in seen }.sortedBy { middle(it).squaredLength }.take(maxOf(0, count - seen.size))).toMutableList()
            val pick = arrayOfNulls<Triple<Int, Int, Int>>(count)
            (0 until count).sortedByDescending { centres[it].squaredLength }.forEach { e ->
                val want = centres[e] * gain
                val best = free.minBy { (i, j, k) -> Vector3(i * s - want.x, k * s - want.y, j * s - want.z).squaredLength }
                free -= best
                pick[e] = best
            }
            pick.map { it!! }
        } else nodes3.distinct().shuffled(rnd)
        return (0 until count).map { e ->
            val (i, j, k) = nodes[e]
            val alongX = e >= panels || rnd.nextBoolean()
            val size = when {
                // In a box a block stands as it stands in the cube.
                cubeForm && layout == "boxes" -> cubeBlocks[e].size - Vector3(JOINT)
                // A block lies on its biggest face, its longest side across x or z.
                cubeForm -> cubeBlocks[e].size.let { b -> listOf(b.x, b.y, b.z).sortedDescending() }
                    .map { it - JOINT }.let { d -> if (alongX) Vector3(d[0], d[2], d[1]) else Vector3(d[1], d[2], d[0]) }
                e >= panels -> Vector3(BAY - JOINT, PLATE, BAY - JOINT)
                alongX -> Vector3(BAY - JOINT, WALL, STOREY - PLATE - JOINT)
                else -> Vector3(STOREY - PLATE - JOINT, WALL, BAY - JOINT)
            }
            val c = at(i, j)
            // In a box a piece floats at its middle; on the flat grid it lies on the floor.
            val y = if (layout == "boxes") ground + (k + 0.5) * s - size.y / 2.0 else ground
            Home(Rectangle(c.x - s / 2.0, c.z - s / 2.0, s, s), Vector3(c.x - size.x / 2.0, y, c.z - size.z / 2.0), size, alongX)
        }
    }

    /**
     * Under `catalogue`: the catalogue page, after a specimen sheet — every piece lying flat in a cell of
     * its own, a margin round it, the cells packed edge to edge in rows [catalogueWidth] wide, each row's
     * cells stretched to fill it, so the page is one ruled rectangle of cells of many sizes. A wall lies
     * with its face up — the door wall shows its doorway as a silhouette — along x or along z from the
     * seed; a plate lies as it always does.
     */
    private fun packCatalogue(): List<Home> {
        val rnd = Random(seed * 31 + 7)
        val margin = CATALOGUE_MARGIN
        class Item(val e: Int, val w: Double, val d: Double, val size: Vector3, val alongX: Boolean)
        val items = (0 until count).map { e ->
            if (e < panels) {
                val alongX = rnd.nextDouble() < 0.6
                val size = if (alongX) Vector3(BAY - JOINT, WALL, STOREY - PLATE - JOINT) else Vector3(STOREY - PLATE - JOINT, WALL, BAY - JOINT)
                Item(e, size.x + 2.0 * margin, size.z + 2.0 * margin, size, alongX)
            } else Item(e, BAY - JOINT + 2.0 * margin, BAY - JOINT + 2.0 * margin, Vector3(BAY - JOINT, PLATE, BAY - JOINT), true)
        }.shuffled(rnd)
        val shelves = mutableListOf<MutableList<Item>>()
        var shelf = mutableListOf<Item>()
        var used = 0.0
        for (item in items) {
            if (shelf.isNotEmpty() && used + item.w > catalogueWidth) { shelves += shelf; shelf = mutableListOf(); used = 0.0 }
            shelf += item; used += item.w
        }
        if (shelf.isNotEmpty()) shelves += shelf
        val out = arrayOfNulls<Home>(count)
        var z = -shelves.sumOf { r -> r.maxOf { it.d } } / 2.0
        for (r in shelves) {
            val depth = r.maxOf { it.d }
            val stretch = catalogueWidth / r.sumOf { it.w }
            var x = -catalogueWidth / 2.0
            for (item in r) {
                val w = item.w * stretch
                val cell = Rectangle(catalogueAt.x + x, catalogueAt.y + z, w, depth)
                out[item.e] = Home(cell, Vector3(cell.center.x - item.size.x / 2.0, ground, cell.center.y - item.size.z / 2.0), item.size, item.alongX)
                x += w
            }
            z += depth
        }
        return out.map { it!! }
    }

    /**
     * A piece's way through the air: one axis a leg, through [centres] — its middle, so a piece that
     * turns turns about it — a leg [move] seconds from [start]; standing [before] until the start of leg
     * [turnLeg] and [after] from then on, the change made in one frame.
     */
    private class Path(val centres: List<Vector3>, val start: Double, val before: Now, val after: Now, val turnLeg: Int) {
        fun at(u: Double, move: Double, curve: CubicBezier): Now {
            var p = centres.first()
            for (i in 0 until centres.size - 1) {
                val f = curve((u - start - i * move) / move)
                if (f <= 0.0) break
                p = centres[i] + (centres[i + 1] - centres[i]) * f
            }
            val o = if (u >= start + turnLeg * move) after else before
            return Now(p - o.size * 0.5, o.size, o.alongX, o.lying)
        }
        fun box(i: Int) = if (i >= turnLeg) after.size else before.size
    }

    /**
     * Under `gallery`: a piece for a drawer that keeps the catalogue as a flat sheet over the scene. [model]
     * stands the piece, in cells, where it is in the scene or over its place while it is on its way;
     * [flight] is 0 in its cell on the sheet, 1 in the scene, and between them in the air; [plate] says
     * it lies flat in both. The drawer turns it from the one view to the other.
     */
    class GalleryPiece(val mesh: ObjMesh, val model: Matrix44, val centre: Vector3, val alongX: Boolean,
                       val plate: Boolean, val flight: Double, val index: Int)

    /** Under `gallery`: piece [e]'s footprint lying face up with its length across, in cells — its cell on the sheet. */
    fun footprint(e: Int): Vector2 =
        if (e < panels) Vector2(BAY - JOINT, STOREY - PLATE - JOINT) else Vector2(BAY - JOINT, BAY - JOINT)

    /**
     * Under `gallery`: **the catalogue as a sheet, and the building in the scene.** The cycle is the
     * catalogue's: the sheet held; each piece, from the ground up of the building to come, [flight]
     * seconds from its cell to the air over its place — the drawer turning it from the sheet's view to the
     * scene's on the way — and then set down into it, one leg; the building held; then each from the top,
     * lifted into the air over its place and [flight] seconds back to its cell. Every piece has one cell.
     */
    fun gallery(t: Double): List<GalleryPiece> {
        if (!ready) return emptyList()
        val build = starts.last() + flight + move
        val cycle = catalogueHold + build + buildHold + build
        val n = floor(t / cycle).toLong()
        val u = t - n * cycle
        val slots = building(n)
        val down = topDown(n)
        val air = ground + (if (cubeForm) cubeSize.toDouble() else storeys * STOREY) + FLIGHT_CLEAR
        return (0 until count).map { e ->
            val slot = slots[e]
            val over = Now(Vector3(slot.corner.x, air, slot.corner.z), slot.size, slot.alongX)
            val inStart = catalogueHold + starts[count - 1 - down[e]]
            val outStart = catalogueHold + build + buildHold + starts[down[e]]
            val lower = Move(over.corner, slot.corner, intArrayOf(1), inStart + flight, slot.alongX, slot.alongX,
                slot.size, slot.size, Double.MAX_VALUE, true)
            val raise = Move(slot.corner, over.corner, intArrayOf(1), outStart, slot.alongX, slot.alongX,
                slot.size, slot.size, Double.MAX_VALUE, true)
            val (f, now) = when {
                u < inStart -> 0.0 to over
                u < inStart + flight -> curve((u - inStart) / flight) to over
                u < outStart -> 1.0 to lower.at(u, move, curve)
                u < outStart + move -> 1.0 to raise.at(u, move, curve)
                else -> (1.0 - curve((u - outStart - move) / flight)) to over
            }
            GalleryPiece(solids[e].mesh, modelOf(solids[e], now), now.corner + now.size * 0.5, slot.alongX, e >= panels, f, e)
        }
    }

    /** Under `catalogue`, the pieces and lines at [t], for a drawer of its own: see [catalogueFrame]. */
    fun catalogue(t: Double): Pair<List<RowPiece>, List<Pair<Pair<Vector3, Vector3>, Double>>> {
        if (!ready) return emptyList<RowPiece>() to emptyList()
        val (placed, lines) = catalogueFrame(t)
        return placed.map { RowPiece(solids[it.b].mesh, modelOf(solids[it.b], it.now), it.active, it.seed) } to lines
    }

    /**
     * Under `catalogue`: **a building, or the kit laid out as a catalogue, and each time another building**
     * — Lego's way with its bricks. The cycle: the catalogue held; the pieces lifted out of their cells from
     * the ground up of the building to come, each stood up in the air, carried across and along and set
     * down into its place; the building held; then taken down from the top, each laid flat in the air and
     * put back in its own cell; and the next building from the same kit. Every leg one axis, on [curve].
     *
     * The cells are ruled on the ground the whole time, as the sheet's are; a piece's path is drawn as it
     * lands, in the building or in its cell, and fades over [fade].
     */
    /** Under `catalogue`: seconds all the pieces take to go from the one state to the other. */
    private val catalogueLift: Double get() = if (explode) stagger * (count - 1) + move else starts.last() + 4 * move

    /**
     * Under `catalogue`: quarter turns a camera may take round the kit by [t] — a whole one each time the
     * kit stands exploded, eased over that hold, and none while anything moves.
     */
    fun turnsAt(t: Double): Double {
        val cycle = catalogueHold + catalogueLift + buildHold + catalogueLift
        val n = floor(t / cycle)
        val f = ((t - n * cycle) / catalogueHold).coerceIn(0.0, 1.0)
        return n + f * f * f * (f * (f * 6.0 - 15.0) + 10.0)
    }

    private fun catalogueFrame(t: Double): Pair<List<Placed>, List<Pair<Pair<Vector3, Vector3>, Double>>> {
        val legs = if (explode) 1 else 4
        val lift = catalogueLift
        val cycle = catalogueHold + lift + buildHold + lift
        val n = floor(t / cycle).toLong()
        val u = t - n * cycle
        val slots = building(n)
        val down = topDown(n)
        // Under explode: each piece's turn by how far it stands from the cube's middle, outermost first.
        val outward = IntArray(count).also { rank ->
            (0 until count).sortedByDescending { e -> (slots[e].corner + slots[e].size * 0.5).let { it.x * it.x + it.z * it.z + (it.y - ground - cubeSize / 2.0).let { y -> y * y } } }
                .forEachIndexed { k, e -> rank[e] = k }
        }
        val flight = ground + storeys * STOREY + FLIGHT_CLEAR
        val pieces = ArrayList<Placed>()
        val lines = ArrayList<Pair<Pair<Vector3, Vector3>, Double>>()
        // The page: the lattice's lines, or every cell ruled on the floor, each edge once.
        if (layout == "lattice" || layout == "boxes") latticeLines.forEach { lines += it to 1.0 }
        else if (guides) {
            val y = ground + 0.02
            val edges = HashSet<List<Long>>()
            fun key(v: Double) = Math.round(v * 1000.0)
            for (h in homes) {
                val r = h.cell
                val corners = listOf(Vector2(r.x, r.y), Vector2(r.x + r.width, r.y), Vector2(r.x + r.width, r.y + r.height), Vector2(r.x, r.y + r.height))
                for (k in 0..3) {
                    val a = corners[k]; val b = corners[(k + 1) % 4]
                    val id = listOf(key(a.x), key(a.y), key(b.x), key(b.y)).let { e -> if (e[0] > e[2] || (e[0] == e[2] && e[1] > e[3])) listOf(e[2], e[3], e[0], e[1]) else e }
                    if (edges.add(id)) lines += (Vector3(a.x, y, a.y) to Vector3(b.x, y, b.y)) to 1.0
                }
            }
        }
        for (e in 0 until count) {
            val home = homes[e]; val slot = slots[e]
            val lying = e < panels
            val laid = Now(home.corner, home.size, home.alongX, lying)
            val standing = Now(slot.corner, slot.size, slot.alongX, false)
            val hc = home.corner + home.size * 0.5; val sc = slot.corner + slot.size * 0.5
            val inStart = catalogueHold + if (explode) (count - 1 - outward[e]) * stagger else starts[count - 1 - down[e]]
            val outStart = catalogueHold + lift + buildHold + if (explode) outward[e] * stagger else starts[down[e]]
            val goIn = if (explode) Path(listOf(hc, sc), inStart, laid, standing, 1)
                       else Path(listOf(hc, Vector3(hc.x, flight, hc.z), Vector3(sc.x, flight, hc.z), Vector3(sc.x, flight, sc.z), sc), inStart, laid, standing, 1)
            val goOut = if (explode) Path(listOf(sc, hc), outStart, standing, laid, 1)
                        else Path(listOf(sc, Vector3(sc.x, flight, sc.z), Vector3(hc.x, flight, sc.z), Vector3(hc.x, flight, hc.z), hc), outStart, standing, laid, 1)
            val now = if (u >= outStart) goOut.at(u, move, curve) else goIn.at(u, move, curve)
            val inLands = inStart + legs * move
            val outLands = outStart + legs * move
            val active = when {
                u >= outStart -> 1.0 - ((u - outLands) / ACCENT_FADE).coerceIn(0.0, 1.0)
                u >= inStart -> 1.0 - ((u - inLands) / ACCENT_FADE).coerceIn(0.0, 1.0)
                else -> 0.0
            }
            pieces += Placed(e, now, e, active)
            if (!guides) continue
            if (u >= inLands && u < outStart) {
                val strength = 1.0 - ((u - inLands) / fade).coerceIn(0.0, 1.0)
                if (strength > 0.0) legLines(goIn.centres, goIn::box, inLands, u, strength, Vector3.ZERO, lines)
            }
            if (u >= outLands) {
                val strength = 1.0 - ((u - outLands) / fade).coerceIn(0.0, 1.0)
                if (strength > 0.0) legLines(goOut.centres, goOut::box, outLands, u, strength, Vector3.ZERO, lines)
            }
        }
        return pieces to lines
    }

    /**
     * Under `solid`: the lines a piece travelled along — fixed in space, where it went, not carried with
     * it. A leg along one axis sweeps the piece's box along that axis, and the four edges of the box that
     * run that way are the lines.
     *
     * **They are drawn the moment the piece is placed outside**, not while it moves: as it lands in the
     * exploded view, its path is drawn from the building out to it over one snap, leg after leg, and then
     * stands — a construction drawing made of where each piece went. The same when it lands in its new
     * place across the grid. Setting a piece down into the building draws nothing: a piece is only
     * placed outside on the way out. A phase's lines stay while the pieces stand apart and fade across
     * the next phase, when they no longer describe where anything is. Returns each line with how strong
     * it is.
     */
    private fun travelled(u: Double, moves: List<Array<Move>>): List<Pair<Pair<Vector3, Vector3>, Double>> {
        val t3 = hold + gridPhase + apart; val t5 = t3 + gridPhase + apart
        val lines = ArrayList<Pair<Pair<Vector3, Vector3>, Double>>()
        for (b in 0 until count) for (k in 0..1) {
            val m = moves[b][k]
            val landed = m.start + m.axes.size * move
            if (u < landed) continue
            val strength = 1.0 - ((u - if (k == 0) t3 else t5) / gridPhase).coerceIn(0.0, 1.0)
            if (strength > 0.0) pathLines(m, landed, u, strength, Vector3.ZERO, lines)
        }
        return lines
    }

    /**
     * The path of [m] as lines, drawn from [from] over one leg's time, leg after leg in the order the
     * piece travels them, moved by [offset]: the four edges of the piece's box that run along each leg,
     * swept from where the leg begins to where it ends. Into [into], each with [strength].
     */
    private fun pathLines(m: Move, from: Double, u: Double, strength: Double, offset: Vector3,
                          into: MutableList<Pair<Pair<Vector3, Vector3>, Double>>) {
        fun c(v: Vector3, a: Int) = when (a) { 0 -> v.x; 1 -> v.y; else -> v.z }
        val a1 = m.to + m.sizeAfter * 0.5
        val centres = mutableListOf(m.from + m.sizeBefore * 0.5)
        m.axes.forEach { a -> val at = DoubleArray(3) { c(centres.last(), it) }; at[a] = c(a1, a); centres += Vector3(at[0], at[1], at[2]) }
        legLines(centres, { i -> if (m.start + i * move >= m.turn) m.sizeAfter else m.sizeBefore }, from, u, strength, offset, into)
    }

    /**
     * A path of one-axis legs through [centres] as lines, drawn from [from] over one leg's time, leg after
     * leg: the four edges of the box ([box] of the leg) that run along each leg, swept from its start to its
     * end, moved by [offset]. Into [into], each with [strength].
     */
    private fun legLines(centres: List<Vector3>, box: (Int) -> Vector3, from: Double, u: Double, strength: Double,
                         offset: Vector3, into: MutableList<Pair<Pair<Vector3, Vector3>, Double>>) {
        fun c(v: Vector3, a: Int) = when (a) { 0 -> v.x; 1 -> v.y; else -> v.z }
        fun v(a: Int, value: Double, b: Int, bv: Double, cAxis: Int, cv: Double): Vector3 {
            val out = DoubleArray(3); out[a] = value; out[b] = bv; out[cAxis] = cv
            return Vector3(out[0], out[1], out[2]) + offset
        }
        val legs = centres.zipWithNext()
        val lengths = legs.map { (p, q) -> (q - p).length }
        var drawn = curve((u - from) / move) * lengths.sum()
        legs.forEachIndexed { i, (p, q) ->
            val f = if (lengths[i] < 1e-6) 0.0 else (drawn / lengths[i]).coerceIn(0.0, 1.0)
            drawn -= lengths[i]
            if (f <= 0.0) return@forEachIndexed
            val d = q - p
            val a = (0..2).maxBy { abs(c(d, it)) }
            val b = box(i)
            val lo = min(c(p, a), c(p, a) + c(d, a) * f) - c(b, a) / 2.0
            val hi = max(c(p, a), c(p, a) + c(d, a) * f) + c(b, a) / 2.0
            val others = (0..2).filter { it != a }
            val bAxis = others[0]; val cAxis = others[1]
            for (bv in doubleArrayOf(c(p, bAxis) - c(b, bAxis) / 2.0, c(p, bAxis) + c(b, bAxis) / 2.0))
                for (cv in doubleArrayOf(c(p, cAxis) - c(b, cAxis) / 2.0, c(p, cAxis) + c(b, cAxis) / 2.0))
                    into += (v(a, lo, bAxis, bv, cAxis, cv) to v(a, hi, bAxis, bv, cAxis, cv)) to strength
        }
    }

    /**
     * Under `solid`: the kit — [panels] wall panels dealt from [walls] in turn and [plates] floor plates
     * from [floors] — and the massings it builds. Each mesh's axes are read off its box: a panel's
     * thinnest is its thickness and it stands as it was modelled; a plate's thinnest is its thickness
     * and is turned to lie flat, which is what `PREDAL`, modelled standing, needs.
     */
    private fun loadSolid() {
        val dir = objects ?: run { println("assemble: no objects folder for the solid pieces"); return }
        fun piece(name: String, plate: Boolean): Solid? {
            val m = loadObjMesh(File(dir, "$name.obj")) ?: run { println("assemble: no piece $name in ${dir.path}"); return null }
            val xs = m.points.map { it.x }; val ys = m.points.map { it.y }; val zs = m.points.map { it.z }
            val lo = Vector3(xs.min(), ys.min(), zs.min()); val hi = Vector3(xs.max(), ys.max(), zs.max())
            val ext = hi - lo
            val e = doubleArrayOf(ext.x, ext.y, ext.z)
            val thin = (0..2).minBy { e[it] }
            val up = when {
                plate -> thin
                thin != 1 -> 1
                else -> (0..2).filter { it != thin }.minBy { e[it] }
            }
            val len = if (plate) (0..2).filter { it != up }.maxBy { e[it] } else (0..2).first { it != thin && it != up }
            val across = (0..2).first { it != up && it != len }
            return Solid(m, (lo + hi) * 0.5, ext, len, up, across)
        }
        val wallKit = walls.mapNotNull { piece(it, false) }
        val plateKit = floors.mapNotNull { piece(it, true) }
        if (wallKit.isEmpty() || plateKit.isEmpty()) { println("assemble: no wall panels or no floor plates in ${dir.path}"); return }
        if (cubeForm) {
            // Each block the piece nearest its proportions, sides taken longest to longest; one of the
            // nearest three, so the cube is a mix rather than one piece over and over.
            fun shape(v: Vector3) = listOf(v.x, v.y, v.z).sortedDescending().let { d -> doubleArrayOf(ln(d[1] / d[0]), ln(max(d[2], 1e-3) / d[0])) }
            val kitAll = wallKit + plateKit
            val rnd = Random(seed * 53 + 11)
            solids = cubeBlocks.map { b ->
                val want = shape(b.size)
                val near = kitAll.sortedBy { k -> shape(k.extent).let { (want[0] - it[0]).let { d -> d * d } + (want[1] - it[1]).let { d -> d * d } * 0.25 } }.take(3)
                near[rnd.nextInt(near.size)]
            }
            if (catalogueMode) homes = if (layout == "lattice" || layout == "boxes") packLattice() else packCatalogue()
            ready = true
            println("assemble: a cube of ${cubeBlocks.size} blocks, ${cubeSize} a side")
            return
        }
        massings = massingsFor()
        if (massings.isEmpty()) {
            println("assemble: no building of $storeys storeys or fewer takes exactly $plates plates and $panels panels")
            return
        }
        solids = List(panels) { wallKit[it % wallKit.size] } + List(plates) { plateKit[it % plateKit.size] }
        if (catalogueMode) homes = if (layout == "lattice" || layout == "boxes") packLattice() else packCatalogue()
        stone = concrete?.let { f ->
            if (!f.isFile) { println("assemble: no concrete at ${f.path}"); null }
            else loadImage(f).also {
                it.wrapU = WrapMode.REPEAT; it.wrapV = WrapMode.REPEAT
                it.generateMipmaps(); it.filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
            }
        }
        ready = true
        println("assemble: ${massings.size} buildings of $panels panels and $plates plates — " +
                (wallKit + plateKit).joinToString { it.mesh.name })
    }

    /**
     * Under `solid`: the pieces as catalogue meshes in a true isometric view, one multisampled pass with
     * a depth test ([WireCubes]). Each mesh is laid into its piece's box — its length along the box's
     * run, its height up, its thickness across — and stretched to fill it exactly, so a building is as
     * closed as its boxes, and drawn flat in the red with its own real edges in black: the mesh's
     * creased edges, the ones the loader marks, so the outline is the piece's and not its box's, and a
     * door or a window reads as an opening. Shaded by which way a face points, it read as oddly lit
     * rather than as a drawing.
     *
     * [lines] are the paths the beams have travelled ([travelled]), after the construction lines of the
     * exploded axonometric this wall is after: thin, grey, fixed in space, behind whatever stands in
     * front of them.
     */
    private fun drawSolid(drawer: Drawer, pane: Rectangle, pieces: List<Placed>, lens: Double, lines: List<Pair<Pair<Vector3, Vector3>, Double>>) {
        val w = pane.width; val h = pane.height
        // A drawn isometric puts a unit edge a unit long; the projection shortens it by sqrt(2/3).
        val scale = unit * lens * sqrt(1.5)
        val e = Vector3(sqrt(0.5) * cos(ISO), sin(ISO), sqrt(0.5) * cos(ISO))
        val view = lookAt(e * 400.0, Vector3.ZERO, Vector3.UNIT_Y)
        val projection = ortho(-w / scale / 2.0, w / scale / 2.0, -h / scale * (1.0 - ORIGIN), h / scale * ORIGIN, 1.0, 800.0)

        // The lines by strength, in a few steps, so each step is one draw.
        val steps = lines.groupBy { (it.second * STRENGTHS).toInt().coerceIn(1, STRENGTHS) }.toSortedMap()
        val ordered = steps.values.flatten().map { it.first }
        if (ordered.isNotEmpty()) guideLines = wire.segments(ordered, guideLines).first

        wire.frame(drawer, pane, projection, view) {
            val see = glass > 0.0
            outlined.parameter("fill", piece)
            outlined.parameter("ink", if (see) edge ?: piece else ink)
            outlined.parameter("width", line)
            outlined.parameter("glass", if (see) glass else 1.0)
            val grained = stone
            outlined.parameter("stoned", if (grained != null) 1.0 else 0.0)
            outlined.parameter("stone", grained ?: blank)
            outlined.parameter("cells", concreteCells)
            outlined.parameter("grain", grain)
            // Seen through: nothing hides anything, so every face and every edge is drawn.
            if (see) { drawer.depthWrite = false; drawer.depthTestPass = DepthTestPass.ALWAYS }
            drawer.shadeStyle = outlined
            for (placed in pieces) {
                val b = placed.b; val now = placed.now
                outlined.parameter("origin", now.corner)
                outlined.parameter("shift", Vector2(fract(placed.seed * 0.618034), fract(placed.seed * 0.381966 + 0.5)))
                val s = solids[b]
                val model = modelOf(s, now)
                drawer.isolated {
                    drawer.model = drawer.model * model
                    drawer.vertexBuffer(s.mesh.vertexBuffer, DrawPrimitive.TRIANGLES)
                }
            }
            drawer.shadeStyle = null
            val buffer = guideLines
            if (buffer != null && ordered.isNotEmpty()) {
                drawer.depthWrite = false
                var first = 0
                for ((step, group) in steps) {
                    wire.edges(drawer, buffer, group.size * 6, e, scale, first = first,
                        colour = guide.opacify(0.85 * step / STRENGTHS), width = 1.2)
                    first += group.size * 6
                }
                drawer.depthWrite = true
            }
        }
    }

    /**
     * The matrix that lays [s]'s mesh into [now]'s box: which of the mesh's axes each world axis takes —
     * its length along the box's run, its height up, its thickness across — stretched to fill it.
     */
    private fun modelOf(s: Solid, now: Now): Matrix44 {
        fun c(v: Vector3, a: Int) = when (a) { 0 -> v.x; 1 -> v.y; else -> v.z }
        val box = now.size
        val centre = now.corner + box * 0.5
        val run = if (now.alongX) 0 else 2
        val role = IntArray(3)
        if (cubeForm) {
            // A block: the mesh's longest side along the block's longest, and so on down.
            val mesh = (0..2).sortedByDescending { c(s.extent, it) }
            val sides = (0..2).sortedByDescending { c(box, it) }
            for (k in 0..2) role[sides[k]] = mesh[k]
        } else {
            // Lying, a panel's thickness is what stands up and its height lies across.
            role[run] = s.len
            if (now.lying) { role[1] = s.across; role[2 - run] = s.up } else { role[1] = s.up; role[2 - run] = s.across }
        }
        val stretch = DoubleArray(3) { w -> c(s.extent, role[w]).let { e -> if (e < 1e-12) 1.0 else c(box, w) / e } }
        val columns = Array(3) { a ->
            val column = DoubleArray(3)
            val w = role.indexOf(a)
            column[w] = stretch[w]
            Vector4(column[0], column[1], column[2], 0.0)
        }
        val shift = DoubleArray(3) { w -> c(centre, w) - stretch[w] * c(s.centre, role[w]) }
        return Matrix44.fromColumnVectors(columns[0], columns[1], columns[2], Vector4(shift[0], shift[1], shift[2], 1.0))
    }

    /**
     * A piece flat in its fill with its real edges in ink: the barycentric distance to the nearest edge
     * the loader marked as a crease, divided by its screen derivative, is pixels — the Objects line
     * style's measure — so the outline is [line] wide however the piece is stretched or turned.
     */
    private val outlined = org.openrndr.draw.shadeStyle {
        vertexPreamble = "out vec3 vBary; out vec3 vEdges;"
        vertexTransform = "vBary = va_bary; vEdges = va_edges;"
        fragmentPreamble = "in vec3 vBary; in vec3 vEdges;"
        fragmentTransform = """
            vec3 w = max(fwidth(vBary), vec3(1e-6));
            vec3 sel = mix(vec3(1e6), vBary / w, step(0.5, vEdges));
            float d = min(min(sel.x, sel.y), sel.z);
            float edge = 1.0 - smoothstep(p_width * 0.5 - 0.6, p_width * 0.5 + 0.6, d);
            vec3 fill = p_fill.rgb;
            float glass = p_glass;
            // The concrete, laid on the piece's own face — the two world axes the face lies along,
            // counted from the piece's corner — and blended in against its own average: how far a
            // texel is off the stone's mean, [grain] times over, darkens or lightens the red. Seen
            // through, it thins and thickens the glass too, so the stone reads in a faint fill.
            if (p_stoned > 0.5) {
                vec3 n = abs(v_worldNormal);
                vec3 q = v_worldPosition - p_origin;
                vec2 uv = n.y >= max(n.x, n.z) ? q.xz : (n.x >= n.z ? q.zy : q.xy);
                float g = dot(texture(p_stone, uv / p_cells + p_shift).rgb, vec3(1.0 / 3.0));
                float m = dot(textureLod(p_stone, vec2(0.5), 20.0).rgb, vec3(1.0 / 3.0));
                float k = (g / max(m, 0.01) - 1.0) * p_grain;
                fill *= clamp(1.0 + k, 0.0, 2.0);
                if (p_glass < 1.0) glass *= clamp(1.0 + 2.0 * k, 0.15, 2.5);
            }
            x_fill = mix(vec4(clamp(fill, 0.0, 1.0), glass), p_ink, edge);
        """
    }

    /** The box a beam with lowest corner [p] and [size] projects to, isometric, about [origin], at [scale] pixels a cell. */
    private fun projected(p: Vector3, size: Vector3, origin: Vector2, scale: Double): Rectangle {
        val q = p + size
        val c = COS30 * scale
        val s = 0.5 * scale
        val left = (p.x - q.z) * c
        val right = (q.x - p.z) * c
        val top = -q.y * scale + (p.x + p.z) * s
        val bottom = -p.y * scale + (q.x + q.z) * s
        return Rectangle(origin.x + left, origin.y + top, right - left, bottom - top)
    }

    private companion object {
        val COS30 = sqrt(3.0) / 2.0

        fun fract(x: Double) = x - floor(x)

        /** Seconds a set-in piece takes to lose the accent it carries while it is outside. */
        const val ACCENT_FADE = 0.6

        /** Under `catalogue`: cells of margin round a piece in its cell, and how far over the tallest building the pieces fly. */
        const val CATALOGUE_MARGIN = 0.8
        const val FLIGHT_CLEAR = 3.0

        /** How many of the best-fitting drawings the beams are dealt from. */
        const val CANDIDATES = 12

        /** The isometric's elevation, atan(1 / sqrt 2): the angle that foreshortens the three axes alike. */
        val ISO = kotlin.math.atan(1.0 / sqrt(2.0))

        /**
         * Under `solid`, where the building's middle stands down a pane, 0 top to 1 foot. Above the
         * middle, because the exploded view spreads toward the viewer and so down the screen: about 16
         * cells above the middle to 19 below it.
         */
        const val ORIGIN = 0.46

        /** A beam's box, running along x and along z. */
        val BEAM_X = Vector3(3.0, 1.0, 1.0)
        val BEAM_Z = Vector3(1.0, 1.0, 3.0)

        /** Under `solid`, in cells: a bay's side, a storey's height, a wall panel's and a floor plate's thickness, and the joint between two pieces. */
        const val BAY = 5.0
        const val STOREY = 3.0
        const val WALL = 0.35
        const val PLATE = 0.35
        const val JOINT = 0.12

        /** The four neighbours of a bay. */
        val SIDES = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

        /** Steps the path lines' strength is drawn in: one draw a step. */
        const val STRENGTHS = 8

        /** The axes a beam's legs run along, in turn: up first to take it off, down last to set it down. */
        val UP_ACROSS_ALONG = intArrayOf(1, 0, 2)
        val ALONG_ACROSS_DOWN = intArrayOf(2, 0, 1)

        /** Cells a side of the grid a drawing is laid over its beam's outline on. */
        const val GRID = 48

        /** Beams by class that are really fixings — anchors, clamps, rods, symbols — and not parts of a building. */
        val NOT_PARTS = Regex("^(KONN|HALFEN|HKZ|DRST|KANTA|STORTZIJDE|HPKM|STABOX|TAND$)")
    }
}
