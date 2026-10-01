// ============================================================================ //
//  No `package` declaration: it stands on KitView, ObjMesh and IsoPieces, in the
//  default package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.BufferMultisample
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadFont
import org.openrndr.draw.renderTarget
import org.openrndr.extra.composition.findShapes
import org.openrndr.extra.svg.loadSVG
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.Vector4
import org.openrndr.math.transforms.buildTransform
import org.openrndr.shape.Rectangle
import slideshow.CubicBezier
import slideshow.FPS
import slideshow.Frame
import slideshow.Palette
import slideshow.SNAP_SECONDS
import slideshow.Scale
import slideshow.Stage
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.advanceWithSubscripts
import slideshow.drawers.setLine
import slideshow.frames
import slideshow.mix
import slideshow.ramp
import slideshow.smoothstep
import slideshow.snap
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **The catalogue the fourth chapter clicks through**: the ring, the grid and the box as one thing. The whole
 * catalogue lands along the WN mark ([CircleRing]), leaves it for a sheet of cells ([CircleGrid]), and the
 * pieces the kit is cut from are picked out of that sheet and poured into the webtool's button, out of which the
 * kit bursts ([WebtoolKit]). Three slides, so the cue sheet, the subtitles and the voice keep the states they
 * were made for, and **one scene under all three**, so every cut between them is the next frame of one picture
 * rather than two drawers that happen to agree.
 *
 * **Everything is drawn the webtool's way**: through its own `KitView`, which is the kit course's renderer —
 * flat faces with a white line on every real edge, on black, from the same isometric corner. A piece is placed on
 * the pane in pixels (where its middle is, how many pixels a unit of the mesh is, how far it has turned), and
 * [rowPieces] turns that into the kit's world with `offsetFor`, so a piece of the catalogue and a block of the
 * kit stand in one space and can share a frame. **Colour says what is chosen**: the catalogue is [grey], and a
 * piece turns the kit's red or blue once the webtool picks it for the building.
 *
 * **The grid's click shows the catalogue to scale**: every piece takes its real size in metres, one scale for all,
 * in its own row and its own place in the row, the rows spread to what they hold, and a standing adult out of the
 * people model steps into a band opened across the middle. See [sheetPose].
 *
 * **The one thing handed across a cut is where the ring had got to.** The ring turns on its own frame count, so
 * the grid can only pick it up exactly if it knows how long the ring stood; the deck says so through
 * `Slide.cameFrom`, and the grid writes it here as [ringStood]. Every pose is otherwise a pure function of the
 * frame and the click, the deck's rule. Opened without the ring before it — in the studio, or a jump — the grid
 * takes the ring as it stands [FALLBACK] seconds after its last piece has landed.
 */
class CircleCatalogue(
    private val objects: File,
    /** The WN mark's centreline as an svg; null or missing stands a plain ring open at the top in its place. */
    private val path: File?,
    private val boldPath: String,
    val pane: Vector2 = Vector2(1920.0, 1080.0),
    /** The corner the camera holds, in degrees round: the webtool's. */
    val yaw: Double = 45.0,
    /** The catalogue's colour, and the tone a piece lands in on the ring before it settles into it. */
    val grey: ColorRGBa = ColorRGBa.fromHex("8C8C8C"),
    private val arriving: ColorRGBa = ColorRGBa.WHITE,
    /** The ring: seconds between one piece landing and the next, seconds a piece takes to land, and to turn once. */
    val cadence: Double = 0.07,
    val landing: Double = 0.6,
    val spin: Double = 30.0,
    /** The titles of the first two slides, which the next one pops its own over. */
    val ringTitle: String = "The Circle, in elementen",
    val gridTitle: String = "100 elementen",
    /** The people model the scale figure is lifted out of; null or missing shows the catalogue to scale alone. */
    private val people: File? = null,
    /** The scale figure's colour: the house blue, so it stands out of the grey catalogue. */
    private val personColour: ColorRGBa = Palette.BLUE,
    /** The figure by its group in the people model; null takes the adult standing stillest. */
    private val personName: String? = null,
    /** The two colours every piece is dealt on the way into the webtool: the kit's, in the kit's order. */
    private val red: ColorRGBa = Palette.RED,
    private val blue: ColorRGBa = Palette.BLUE,
    /**
     * The longest a piece may be, in metres, to stay on the sheet to scale; anything longer shrinks away on the
     * grid's click. The catalogue has a gap from 6.3 m to 11.7 m, so 10 keeps the walls, slabs and fittings and
     * lets the long beams, the columns, the work floors and the model's whole floors go.
     */
    private val largest: Double = 10.0,
    /**
     * Pieces left out of the ring, the grid and everything after them, by name. The meeting of 30
     * September asked for the round ones to go: threaded rods, lifting eyes, ball-head anchors, piles and
     * void formers are fittings rather than building elements, and at the sheet's size they read as discs.
     */
    private val exclude: Set<String> = emptySet()
) {
    /** A piece on the pane: where the mesh's own middle projects (pixels, y down), pixels a mesh unit, its turn, its colour. */
    class Pose(val at: Vector2, val scale: Double, val angle: Double, val colour: ColorRGBa)

    /** The kit's renderer, shared with the webtool: the same view, so the same pixels. */
    var view: KitView? = null
        private set
    private var loaded = false
    private lateinit var bold: FontImageMap
    private var meshes: List<ObjMesh> = emptyList()
    val count: Int get() = meshes.size

    /** Pixels a mesh unit at one piece height on the ring, per piece: the any-angle fit `IsoPieces` uses. */
    private var ringFit = DoubleArray(0)
    /** Every piece's place on the mark, in pane pixels. */
    private var ringAt: List<Vector2> = emptyList()

    /** The sheet: its cells' middles in reading order, and their size. */
    private var cells: List<Vector2> = emptyList()
    private var cell = Vector2.ONE
    /** A piece's box on the pane at each quarter turn, in mesh units, y down: min x, max x, min y, max y. */
    private var boxes: List<List<DoubleArray>> = emptyList()
    /** The cell each piece takes coming off the ring. */
    private var first = IntArray(0)
    /** Each piece's rank outward from the middle of the sheet: the order the grid's click takes them to scale in. */
    private var outward = IntArray(0)
    /** Metres a mesh unit, per piece: its real size over its normalised one. */
    private var metres = DoubleArray(0)

    /** The scale figure — a standing adult out of [people] — its metres a mesh unit, its turn, and its box at that turn. */
    private var person: ObjMesh? = null
    private var personMetres = 1.0
    private var personTurn = 0.0
    private var personBox = DoubleArray(4)

    /** The pieces the kit's cube is cut from, by index, with the kit's colour for each. */
    var lit: Map<Int, ColorRGBa> = emptyMap()
        private set

    /**
     * Frames the ring had stood when the deck left it for the grid, written by the grid as it arrives. Null until
     * the ring has been left for the grid, and then the grid takes the ring as it stands at [fallbackFrame].
     */
    var ringStood: Int? = null
    /** The grid's click when the deck left it for the webtool: 1 to scale, 0 as it came off the ring. */
    var gridLeftOn: Int = 1

    private var target: RenderTarget? = null
    private var resolved: ColorBuffer? = null

    /** Loads everything the three slides share, once, whichever of them asks first. */
    fun load(program: Program) {
        if (loaded) return
        loaded = true
        val started = System.currentTimeMillis()
        bold = program.loadFont(boldPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        val kit = KitView(program, ::webtoolKitKey, pane.x.toInt(), pane.y.toInt())
        view = kit
        meshes = loadObjMeshes(objects).filter { it.name !in exclude }
        if (exclude.isNotEmpty()) println("circle catalogue: ${meshes.size} pieces, ${exclude.size} left out")
        if (meshes.isEmpty()) { println("circle catalogue: no pieces in ${objects.path}"); return }
        val n = meshes.size
        val iso = IsoPieces()
        ringFit = DoubleArray(n) { iso.fit(meshes[it], WIDEST).scale }
        ringAt = ringPlaces(n)
        boxes = meshes.map { m -> (0 until 4).map { q -> projectedBox(m, q) } }
        layGrid(n)
        // Off the ring and onto the sheet with the least travel in all: a minimum-cost matching of the places on
        // the mark to the cells, which is also what keeps the paths from crossing.
        first = assignment(Array(n) { i -> DoubleArray(n) { j -> ringAt[i].distanceTo(cells[j]) } })
        val middle = Frame(Rectangle(0.0, 0.0, pane.x, pane.y)).body.center
        outward = IntArray(n).also { o -> (0 until n).sortedBy { cells[first[it]].distanceTo(middle) }.forEachIndexed { k, i -> o[i] = k } }
        metres = DoubleArray(n) { i -> meshes[i].size.let { maxOf(it.x, it.y, it.z) } / extentOf(meshes[i]).coerceAtLeast(1e-9) }
        people?.let { loadPerson(it) }
        lit = litPieces(kit)
        println("circle catalogue: $n pieces along ${path?.takeIf { it.isFile }?.path ?: "a plain ring"}, a sheet of " +
            "${cols} by ${rows}, ${lit.size} of them the kit's (${lit.keys.joinToString(",") { meshes[it].name }}), " +
            "in ${System.currentTimeMillis() - started} ms")
        val scaled = toScale()
        println("circle catalogue: to scale at %.1f px a metre, the figure %s; left out at scale: %s".format(
            scaled.perMetre,
            if (person == null) "missing" else "%.0f px high".format((personBox[3] - personBox[2]) * personMetres * scaled.perMetre),
            (0 until n).filter { outsize(it) }.joinToString(", ") { meshes[it].name }.ifEmpty { "none" }))
    }

    // --- the ring -------------------------------------------------------------------------- //

    /** Frames the ring takes to land its last piece: [CircleRing]'s settle. */
    val ringSettle: Int get() = frames(cadence) * (count.coerceAtLeast(1) - 1) + frames(landing)

    /** Where the grid takes the ring from when it was not handed one. */
    val fallbackFrame: Int get() = ringSettle + frames(FALLBACK)
    private fun handedFrame() = ringStood ?: fallbackFrame

    fun ringAngle(i: Int, frame: Int): Double =
        2.0 * PI * frame / frames(spin) + 2.0 * PI * ((i * GOLDEN) % 1.0)

    /**
     * Piece [i] on the ring at [frame]: landing a little from above along the mark, a size up and white, settling
     * into the grey over [GLOW]; null before it has started to land. `RingOfPieces`' rule, in the kit's drawing.
     */
    fun ringPose(i: Int, frame: Int): Pose? {
        val start = i * frames(cadence)
        val landed = smoothstep(ramp(frame - start, frames(landing)))
        if (landed <= 0.0) return null
        val glow = 1.0 - smoothstep(ramp(frame - start - frames(landing * 0.5), frames(GLOW)))
        val p = ringAt[i]
        val size = pane.y * UNIT * landed * (1.0 + POP * glow)
        return Pose(Vector2(p.x, p.y - pane.y * DROP * (1.0 - landed)), ringFit[i] * size, ringAngle(i, frame), grey.mix(arriving, glow))
    }

    /** The mark's path, resampled evenly for [n] pieces and fitted under the title, in pane pixels. */
    private fun ringPlaces(n: Int): List<Vector2> {
        val count = n.coerceAtLeast(2)
        val drawn = path?.takeIf { it.isFile }?.let { file ->
            loadSVG(file).findShapes().flatMap { it.effectiveShape.contours }.maxByOrNull { it.length }?.equidistantPositions(count)
        }
        val points = drawn ?: run {
            println("circle catalogue: no mark at ${path?.path ?: "(none)"} — a plain ring open at the top stands in")
            val from = -PI / 2.0 + Math.toRadians(GAP_DEG)
            val to = -PI / 2.0 - Math.toRadians(GAP_DEG) + 2.0 * PI
            List(count) { i -> val a = from + (to - from) * i / (count - 1); Vector2(0.5 + 0.5 * cos(a), 0.5 + 0.5 * sin(a)) }
        }
        val x0 = points.minOf { it.x }; val y0 = points.minOf { it.y }
        val box = Rectangle(x0, y0, (points.maxOf { it.x } - x0).coerceAtLeast(1e-6), (points.maxOf { it.y } - y0).coerceAtLeast(1e-6))
        val room = Rectangle(pane.x * RING_MARGIN, pane.y * RING_TOP, pane.x * (1.0 - 2.0 * RING_MARGIN), pane.y * (RING_BOTTOM - RING_TOP))
        val scale = minOf(room.width / box.width, room.height / box.height)
        val corner = room.center - Vector2(box.width, box.height) * (scale / 2.0)
        return points.map { corner + (it - box.corner) * scale }
    }

    // --- the grid -------------------------------------------------------------------------- //

    private var cols = 1
    private var rows = 1

    /**
     * The sheet under the title: the columns and rows that let a piece of the catalogue's typical proportion be
     * largest, the last row as long as the count leaves it and centred, so there is no hole in the sheet.
     */
    private fun layGrid(n: Int) {
        val body = Frame(Rectangle(0.0, 0.0, pane.x, pane.y)).body
        val aspect = boxes.map { b -> b[0].let { (it[1] - it[0]) / (it[3] - it[2]).coerceAtLeast(1e-6) } }.sorted()[n / 2]
        var best = -1.0
        for (r in 1..n) {
            val c = ceil(n.toDouble() / r).toInt()
            if (c * (r - 1) >= n) continue
            val size = min(body.width / c / aspect, body.height / r)
            if (size > best) { best = size; cols = c; rows = r }
        }
        cell = Vector2(body.width / cols, body.height / rows)
        cells = (0 until n).map { k ->
            val r = k / cols
            val inRow = if (r == rows - 1) n - cols * (rows - 1) else cols
            val lead = (cols - inRow) * cell.x / 2.0
            Vector2(body.x + lead + (k % cols + 0.5) * cell.x, body.y + (r + 0.5) * cell.y)
        }
    }

    /** Piece [i] standing in a cell at [centre], a quarter turn [q] round, [size] of its fit. */
    fun cellPose(i: Int, centre: Vector2, q: Int, size: Double = 1.0, colour: ColorRGBa = grey): Pose {
        val b = boxes[i][q]
        val s = FILL * min(cell.x / (b[1] - b[0]).coerceAtLeast(1e-6), cell.y / (b[3] - b[2]).coerceAtLeast(1e-6)) * size
        return posed(i, q, centre, s, colour)
    }

    /** Piece [i] a quarter turn [q] round, the middle of its box on the pane at [middle], [s] pixels a mesh unit. */
    private fun posed(i: Int, q: Int, middle: Vector2, s: Double, colour: ColorRGBa = grey): Pose {
        val b = boxes[i][q]
        return Pose(middle - Vector2((b[0] + b[1]) / 2.0, (b[2] + b[3]) / 2.0) * s, s, q * PI / 2.0, colour)
    }

    /** The middle of piece [i]'s box on the pane, where [pose] stands it. */
    private fun middleOf(i: Int, pose: Pose): Vector2 {
        val b = boxes[i][restQuarter(i)]
        return pose.at + Vector2((b[0] + b[1]) / 2.0, (b[2] + b[3]) / 2.0) * pose.scale
    }

    /** Seconds into the grid's arrival that piece [i] leaves the ring: in the order it was laid down along the mark. */
    fun moveStart(i: Int): Double = MOVE_LEAD + MOVE_SPREAD * i / (count - 1).coerceAtLeast(1)

    /** Seconds into the grid's click that piece [i] takes its real size: outward from the middle of the sheet. */
    fun scaleStart(i: Int): Double = SCALE_SPREAD * outward[i] / (count - 1).coerceAtLeast(1)

    /** Seconds into the grid's click the figure steps in: once the middle has cleared. */
    val personAt: Double get() = PERSON_AT

    /** Seconds the grid's arrival takes, and its click. */
    val gatherSeconds: Double get() = MOVE_LEAD + MOVE_SPREAD + SNAP_SECONDS
    val scaleSeconds: Double get() = maxOf(SCALE_SPREAD, PERSON_AT) + SNAP_SECONDS
    /** Seconds into the grid's arrival its title takes over from the ring's. */
    val gridTitleAt: Double get() = MOVE_LEAD

    /** The whole number of quarter turns piece [i] comes to rest on: the nearest to where it had turned as it left. */
    private fun restTurns(i: Int): Long = (ringAngle(i, handedFrame() + frames(moveStart(i))) / (PI / 2.0)).roundToLong()
    fun restQuarter(i: Int): Int = Math.floorMod(restTurns(i), 4L).toInt()

    /**
     * Piece [i] on the sheet [scaling] seconds into the grid's click: in its cell, and from its turn on at its real
     * size in its place on the sheet to scale, one snap. A piece left out at scale shrinks away where it stands;
     * null once it has gone.
     */
    fun sheetPose(i: Int, scaling: Double): Pose? {
        val q = restQuarter(i)
        val inCell = cellPose(i, cells[first[i]], q)
        val p = snap((scaling - scaleStart(i)) / SNAP_SECONDS)
        if (p <= 0.0) return inCell
        val scaled = toScale()
        val real = scaled.at[i] ?: return if (p >= 1.0) null else cellPose(i, cells[first[i]], q, 1.0 - p)
        val b = posed(i, q, real, scaled.perMetre * metres[i])
        return Pose(lerp(inCell.at, b.at, p), mix(inCell.scale, b.scale, p), inCell.angle, grey)
    }

    /** The scale figure [scaling] seconds into the grid's click: stepping in at the middle of the sheet; null before. */
    fun personPose(scaling: Double): Pose? {
        val scaled = toScale()
        val at = scaled.person ?: return null
        val p = snap((scaling - PERSON_AT) / SNAP_SECONDS)
        return if (p <= 0.0) null else personPosed(at, scaled.perMetre * personMetres * p)
    }

    /** The figure with the middle of its box at [middle], [s] pixels a mesh unit. */
    private fun personPosed(middle: Vector2, s: Double) =
        Pose(middle - Vector2((personBox[0] + personBox[1]) / 2.0, (personBox[2] + personBox[3]) / 2.0) * s, s, personTurn, personColour)

    /**
     * Piece [i] on the grid: [frame] into its arrival when [gathering], [scaling] seconds into its click. Coming off
     * the ring it goes on as the ring would — turning, and landing if it had not yet — until its turn to leave, then
     * snaps to its cell and to the nearest quarter turn in one move. A piece not yet landed grows into its cell.
     */
    fun gridPose(i: Int, frame: Int, gathering: Boolean, scaling: Double): Pose? {
        val target = sheetPose(i, scaling) ?: return null
        if (!gathering) return target
        val e = snap((frame / FPS.toDouble() - moveStart(i)) / SNAP_SECONDS)
        if (e >= 1.0) return target
        val handed = handedFrame()
        val ring = ringPose(i, handed + frame)
        if (e <= 0.0) return ring
        val turned = ringAngle(i, handed + min(frame, frames(moveStart(i))))
        val from = ring ?: Pose(ringAt[i], 0.0, turned, grey)
        return Pose(lerp(from.at, target.at, e), mix(from.scale, target.scale, e),
            mix(turned, restTurns(i) * PI / 2.0, e), from.colour.mix(target.colour, e))
    }

    // --- to scale -------------------------------------------------------------------------- //

    /** The sheet to scale: pixels a metre, every piece's middle on the pane (null for one left out), the figure's. */
    private class ToScale(val perMetre: Double, val at: Array<Vector2?>, val person: Vector2?)
    private var scaledFor: Pair<Int, ToScale>? = null

    /** Whether piece [i] is left out at scale: longer than [largest], which would shrink everything else to specks. */
    private fun outsize(i: Int) = meshes[i].size.let { maxOf(it.x, it.y, it.z) } > largest

    /**
     * The positions nearest [homes], in order, that keep a [GAP] between neighbours of half widths [half] and stay
     * between [left] and [right] — least squares, so a piece moves only as far as the sizes around it make it. Taking
     * off each position the room the ones before it need turns the gaps into a plain ordering, which the pool of
     * adjacent violators solves exactly; clamping the result then respects the edges. Null when the row cannot fit.
     */
    private fun nearest(homes: List<Double>, half: List<Double>, left: Double, right: Double): DoubleArray? {
        val n = homes.size
        val room = DoubleArray(n)
        for (j in 1 until n) room[j] = room[j - 1] + half[j - 1] + half[j] + GAP
        val lo = left + half[0]
        val hi = right - half[n - 1] - room[n - 1]
        if (lo > hi) return null
        // Pool adjacent violators: blocks of (sum, count) whose means never fall.
        val sums = ArrayList<Double>(); val counts = ArrayList<Int>()
        for (j in 0 until n) {
            sums += homes[j] - room[j]; counts += 1
            while (sums.size > 1 && sums[sums.size - 2] / counts[counts.size - 2] > sums.last() / counts.last()) {
                val s = sums.removeAt(sums.size - 1); val c = counts.removeAt(counts.size - 1)
                sums[sums.size - 1] += s; counts[counts.size - 1] += c
            }
        }
        val xs = DoubleArray(n)
        var j = 0
        for (b in sums.indices) repeat(counts[b]) { xs[j] = (sums[b] / counts[b]).coerceIn(lo, hi) + room[j]; j++ }
        return xs
    }

    /** One thing on the sheet to scale: a piece by its index, or the figure as -1; where it stands across; its size. */
    private class Spot(val index: Int, val home: Double, val wide: Double, val high: Double)

    /**
     * **Every piece at its real size, one scale for all, and each where it was.** A piece keeps its column as near
     * as it can: a row stands at the positions nearest its cells' x that keep a [GAP] between neighbours and stay
     * inside the body ([nearest]), so the sheet spaces out as much as the sizes need and no more. A row keeps its line,
     * every piece standing on it, and rises until one of its pieces would come within a [GAP] of one above: a 13 m
     * column no longer makes its whole row tall, it stands up into the room the row above leaves it. The figure has
     * a row of its own across the middle, at the sheet's centre. The scale is the largest at which all of it fits the
     * body, found by halving; worked out once for each turn the pieces rest on, which follows how long the ring stood.
     */
    private fun toScale(): ToScale {
        val handed = handedFrame()
        scaledFor?.takeIf { it.first == handed }?.let { return it.second }
        val n = count
        val body = Frame(Rectangle(0.0, 0.0, pane.x, pane.y)).body
        fun box(i: Int) = boxes[i][restQuarter(i)]
        val lines = ArrayList<List<Spot>>()
        val inRow = (0 until n).filter { !outsize(it) }.groupBy { first[it] / cols }
        for (r in 0 until rows) {
            if (r == rows / 2 && person != null) lines += listOf(Spot(-1, body.center.x,
                (personBox[1] - personBox[0]) * personMetres, (personBox[3] - personBox[2]) * personMetres))
            val row = inRow[r].orEmpty().sortedBy { first[it] }
            if (row.isNotEmpty()) lines += row.map { i ->
                box(i).let { b -> Spot(i, cells[first[i]].x, (b[1] - b[0]) * metres[i], (b[3] - b[2]) * metres[i]) }
            }
        }
        /** Every spot's middle at [k] pixels a metre, the sheet's top at 0; null where it does not fit the body. */
        fun lay(k: Double): Map<Int, Vector2>? {
            val placed = ArrayList<DoubleArray>()          // x0, x1, top, bottom of everything down so far
            val middles = HashMap<Int, Vector2>()
            var above = Double.NEGATIVE_INFINITY
            for (line in lines) {
                val half = line.map { it.wide * k / 2.0 }
                val xs = nearest(line.map { it.home }, half, body.x, body.x + body.width) ?: return null
                var floor = if (above.isInfinite()) line.maxOf { it.high * k } else above + 2.0 * GAP
                for ((j, spot) in line.withIndex()) for (q in placed)
                    if (xs[j] + half[j] + GAP > q[0] && xs[j] - half[j] - GAP < q[1]) floor = maxOf(floor, q[3] + GAP + spot.high * k)
                for ((j, spot) in line.withIndex()) {
                    placed += doubleArrayOf(xs[j] - half[j], xs[j] + half[j], floor - spot.high * k, floor)
                    middles[spot.index] = Vector2(xs[j], floor - spot.high * k / 2.0)
                }
                above = floor
            }
            val top = placed.minOf { it[2] }; val bottom = placed.maxOf { it[3] }
            if (bottom - top > body.height) return null
            val shift = body.center.y - (top + bottom) / 2.0
            return middles.mapValues { it.value + Vector2(0.0, shift) }
        }
        var lo = 0.0; var hi = body.width
        repeat(40) { val k = (lo + hi) / 2.0; if (lay(k) != null) lo = k else hi = k }
        val middles = lay(lo).orEmpty()
        return ToScale(lo, Array(n) { middles[it] }, middles[-1]).also { scaledFor = handed to it }
    }

    /**
     * One standing adult out of the people model: the file is centimetres, Y up, a group a figure. Among the adults —
     * the three quarters of the tallest that `Crowd` keeps, so it is a grown-up rather than a child or a seated
     * figure — it takes the one **standing stillest**: whose legs, the lower 45% of it, spread least, with half its
     * whole reach added so arms held out count against it too. Measured, that is `people_silhouette114`, 1.71 m, feet
     * together and hands at the hips; ranking on the feet alone let a runner through on its one planted foot.
     * [personName] names another. It is written out as an obj of its own, Z up, so the one
     * loader reads it, creases and all, and turned so its widest side — its front or its back — faces the room.
     */
    private fun loadPerson(file: File) {
        if (!file.isFile) { println("circle catalogue: no people at ${file.path} — the catalogue stands to scale alone"); return }
        val points = ArrayList<Vector3>()
        val faces = LinkedHashMap<String, MutableList<List<Int>>>()
        var group = ""
        val space = Regex("\\s+")
        file.forEachLine { raw ->
            val line = raw.trim()
            when {
                line.startsWith("v ") -> line.split(space).let { points += Vector3(it[1].toDouble(), it[2].toDouble(), it[3].toDouble()) }
                line.startsWith("g ") -> group = line.substring(2).trim()
                line.startsWith("f ") -> faces.getOrPut(group) { mutableListOf() } +=
                    line.split(space).drop(1).map { c -> c.substringBefore('/').toInt().let { if (it < 0) points.size + it else it - 1 } }
            }
        }
        fun heightOf(fs: List<List<Int>>) = fs.flatten().let { ix -> ix.maxOf { points[it].y } - ix.minOf { points[it].y } }
        val figures = faces.entries.filter { it.value.isNotEmpty() }
        if (figures.isEmpty()) return
        val tallest = figures.maxOf { heightOf(it.value) }
        fun spread(fs: List<List<Int>>, below: Double): Double {
            val ix = fs.flatten().distinct()
            val y0 = ix.minOf { points[it].y }
            val h = heightOf(fs)
            val low = ix.filter { points[it].y < y0 + below * h }.map { points[it] }
            return maxOf(low.maxOf { it.x } - low.minOf { it.x }, low.maxOf { it.z } - low.minOf { it.z }) / h
        }
        val adults = figures.filter { heightOf(it.value) >= ADULTS * tallest }
        val figure = figures.firstOrNull { it.key == personName }
            ?: adults.minBy { spread(it.value, LEGS) + 0.5 * spread(it.value, 1.01) }
        val used = figure.value.flatten().distinct()
        val index = used.withIndex().associate { (k, v) -> v to k + 1 }
        val out = File("build/circle-catalogue/person.obj").also { it.parentFile.mkdirs() }
        out.printWriter().use { w ->
            w.println("o ${figure.key}")
            // Centimetres to metres, and Y up to the Z up the loader turns back.
            for (v in used) points[v].let { w.println("v ${it.x / 100.0} ${-it.z / 100.0} ${it.y / 100.0}") }
            for (f in figure.value) w.println("f " + f.joinToString(" ") { index.getValue(it).toString() })
        }
        // No creases: a low-poly figure is creased on nearly every facet, and at thirty pixels the catalogue's white
        // edge line would cover it; drawn flat it reads as a figure, in its own colour.
        val mesh = loadObjMesh(out, creaseDegrees = 180.0) ?: return
        person = mesh
        personMetres = mesh.size.let { maxOf(it.x, it.y, it.z) } / extentOf(mesh).coerceAtLeast(1e-9)
        // Its footprint's widest direction, turned to lie across the screen.
        val xs = mesh.points.map { it.x }; val zs = mesh.points.map { it.z }
        val mx = xs.average(); val mz = zs.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }; val szz = zs.sumOf { (it - mz) * (it - mz) }
        val sxz = xs.indices.sumOf { (xs[it] - mx) * (zs[it] - mz) }
        val widest = 0.5 * atan2(2.0 * sxz, sxx - szz)
        val w = Vector4(cos(widest), 0.0, sin(widest), 0.0)
        val right = screenAxes().first
        personTurn = Math.toRadians((0 until 360).maxBy { d -> abs((buildTransform { rotate(Vector3.UNIT_Y, d.toDouble()) } * w).xyz.dot(right)) }.toDouble())
        personBox = projectedBox(mesh, Math.toDegrees(personTurn))
        println("circle catalogue: the scale figure is ${figure.key}, %.2f m tall".format(mesh.size.y))
    }

    // --- into the webtool ------------------------------------------------------------------ //

    /** Seconds into the webtool's arrival: every piece coloured; the sheet drawn into the button; the cursor. */
    val buttonAt: Double get() = GATHER_AT
    val cursorAt: Double get() = GATHER_AT + GATHER - CURSOR_LEAD
    val arrivalSeconds: Double get() = cursorAt + CURSOR_IN
    /** Seconds the sheet takes to go into the button. */
    val intoButtonSeconds: Double get() = GATHER

    /**
     * How far the sheet has gone into the button [a] seconds into the webtool's arrival, 0 to 1: **one movement on
     * one ease** for every piece and the figure alike, so the sheet collapses into the button's middle as one thing,
     * and the button grows out of that point on the same curve.
     */
    fun gathered(a: Double): Double = GATHER_CURVE((a - GATHER_AT) / GATHER)

    /**
     * The pieces on the sheet as the grid left it, each with its place in the colouring — outward from the figure,
     * so the colour spreads from where the person stands. Worked out once for each way the sheet can be left.
     */
    private class Order(val colour: IntArray, val count: Int)
    private var ordered: Triple<Int, Int, Order>? = null
    private fun order(leftOn: Int): Order {
        val handed = handedFrame()
        ordered?.takeIf { it.first == handed && it.second == leftOn }?.let { return it.third }
        val middle = pane / 2.0
        val on = (0 until count).mapNotNull { i -> sheetOf(i, leftOn)?.let { i to middleOf(i, it) } }
        val from = (if (leftOn >= 1) toScale().person else null) ?: middle
        val colour = IntArray(count) { -1 }
        on.sortedBy { it.second.distanceTo(from) }.forEachIndexed { k, (i, _) -> colour[i] = k }
        return Order(colour, on.size).also { ordered = Triple(handed, leftOn, it) }
    }
    private fun spreadAt(k: Int, n: Int, at: Double, spread: Double) = at + spread * k / (n - 1).coerceAtLeast(1)

    /** When piece [i] takes its colour, in seconds into the webtool's arrival; null for one not on the sheet. */
    fun colourStartOf(i: Int, leftOn: Int): Double? =
        order(leftOn).let { o -> o.colour[i].takeIf { it >= 0 }?.let { spreadAt(it, o.count, COLOUR_AT, COLOUR_SPREAD) } }

    /** The colour piece [i] is dealt: the kit's own where it is one of the kit's pieces, red and blue by turns otherwise. */
    private fun colourFor(i: Int, rank: Int): ColorRGBa = lit[i] ?: if (rank % 2 == 0) red else blue

    /** Piece [i] on the sheet as the grid was left: to scale after its click, in its cell before it. */
    private fun sheetOf(i: Int, leftOn: Int) = sheetPose(i, if (leftOn >= 1) Double.MAX_VALUE else 0.0)

    /**
     * Piece [i], [a] seconds into the webtool's arrival, the grid having been left on click [leftOn]: every piece on
     * the sheet takes its red or blue one after another, outward from the figure, and then the whole sheet goes into
     * the button's middle at [centre] in one movement ([gathered]) — every piece the same share of the way there and
     * the same share smaller, which is the sheet shrinking into that point. Null once it is in.
     */
    fun pourPose(i: Int, a: Double, leftOn: Int, centre: Vector2): Pose? {
        val base = sheetOf(i, leftOn) ?: return null
        val o = order(leftOn)
        val coloured = a >= spreadAt(o.colour[i], o.count, COLOUR_AT, COLOUR_SPREAD)
        val e = gathered(a)
        if (e >= 1.0) return null
        return posed(i, restQuarter(i), lerp(middleOf(i, base), centre, e), base.scale * (1.0 - e),
            if (coloured) colourFor(i, o.colour[i]) else grey)
    }

    /** The scale figure, [a] seconds into the webtool's arrival: standing while the pieces are coloured, and drawn into the button with them. */
    fun personLeaving(a: Double, leftOn: Int, centre: Vector2): Pose? {
        if (leftOn < 1) return null
        val full = personPose(Double.MAX_VALUE) ?: return null
        val e = gathered(a)
        if (e >= 1.0) return null
        val middle = full.at + Vector2((personBox[0] + personBox[1]) / 2.0, (personBox[2] + personBox[3]) / 2.0) * full.scale
        return personPosed(lerp(middle, centre, e), full.scale * (1.0 - e))
    }

    /** Where piece [i] stands on the sheet the webtool opens on, in pane pixels; null for one left out at scale. */
    fun sheetAt(i: Int, leftOn: Int): Vector2? = sheetOf(i, leftOn)?.let { middleOf(i, it) }

    /**
     * The kit's own pieces among the catalogue's, by name, each in the colour the kit dealt most of its blocks of
     * that piece: which pieces light up is what the webtool will build with, not a list kept beside it.
     */
    private fun litPieces(kit: KitView): Map<Int, ColorRGBa> {
        val built = kit.kit.catalogue(kit.kit.builtAt).first
        val byName = built.groupBy { it.mesh.name }.mapValues { (_, ps) ->
            ps.distinctBy { it.seed }.mapNotNull { kit.blockColour(it.seed) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: grey
        }
        return meshes.withIndex().mapNotNull { (i, m) -> byName[m.name]?.let { i to it } }.toMap()
    }

    // --- drawing --------------------------------------------------------------------------- //

    /**
     * [poses] as the kit's pieces, for a frame [w] by [h]: each placed where it projects to its pixel on the pane,
     * the size and the turn it is given, and a colour a piece, by seeds that cannot meet the kit's own blocks'.
     */
    fun rowPieces(poses: List<Pose?>, w: Int, h: Int, figure: Pose? = null): Pair<List<AssembleScene.RowPiece>, Map<Int, ColorRGBa>> {
        val kit = view ?: return emptyList<AssembleScene.RowPiece>() to emptyMap()
        val perPixel = kit.offsetFor(Vector2(1.0, 0.0), kit.cycle, yaw, w, h).length
        val middle = Vector2(w / 2.0, h / 2.0)
        val pieces = ArrayList<AssembleScene.RowPiece>(poses.size)
        val colours = HashMap<Int, ColorRGBa>()
        fun place(mesh: ObjMesh, p: Pose, seed: Int) {
            val centre: Vector3 = kit.look + kit.offsetFor(p.at - middle, kit.cycle, yaw, w, h)
            val model = buildTransform {
                translate(centre)
                rotate(Vector3.UNIT_Y, Math.toDegrees(p.angle))
                scale(p.scale * perPixel)
            }
            pieces += AssembleScene.RowPiece(mesh, model, 0.0, seed)
            colours[seed] = p.colour
        }
        poses.forEachIndexed { i, p ->
            if (p != null && p.scale > 1e-6 && i < meshes.size) place(meshes[i], p, SEED_BASE - i)
        }
        val figureMesh = person
        if (figure != null && figure.scale > 1e-6 && figureMesh != null) place(figureMesh, figure, PERSON_SEED)
        return pieces to colours
    }

    /** [poses] drawn on black as the webtool draws its kit — into a multisampled target of the frame's size — and laid on the pane. */
    fun draw(drawer: Drawer, stage: Stage, poses: List<Pose?>, figure: Pose? = null) {
        val kit = view ?: return
        val w = stage.width.toInt(); val h = stage.height.toInt()
        val (pieces, colours) = rowPieces(poses, w, h, figure)
        val (msaa, image) = targetFor(w, h)
        drawer.isolatedWithTarget(msaa) {
            ortho(msaa)
            clear(kit.paper)
            kit.drawPieces(this, pieces, emptyList(), yaw, 1.0, 0.0, KitPick(colours = colours), clear = false)
        }
        msaa.colorBuffer(0).copyTo(image)
        drawer.isolated {
            drawer.shadeStyle = null
            drawer.image(image, stage.bounds.x, stage.bounds.y, stage.width, stage.height)
        }
    }

    /** A slide's title where the webtool sets its own: ranged left on the frame's title line, white on a plate of the ground. */
    fun title(drawer: Drawer, stage: Stage, text: String) {
        val size = Scale.title(stage.height)
        val at = Frame(stage.bounds).titleLeft
        val pad = size * PAD
        drawer.isolated {
            drawer.shadeStyle = null
            drawer.stroke = null
            drawer.fill = Palette.onBlack.paper
            drawer.rectangle(at.x - pad, at.y - size * ABOVE, bold.advanceWithSubscripts(text) * size / SIZE + 2.0 * pad, size * (ABOVE + BELOW))
            drawer.fill = Palette.onBlack.ink
            drawer.setLine(text, bold, at, size, SIZE)
        }
    }

    private fun targetFor(w: Int, h: Int): Pair<RenderTarget, ColorBuffer> {
        val t = target?.takeIf { it.width == w && it.height == h }
            ?: renderTarget(w, h, multisample = BufferMultisample.SampleCount(8)) { colorBuffer(); depthBuffer() }.also {
                target?.destroy(); target = it
                resolved?.destroy(); resolved = colorBuffer(w, h)
            }
        return t to resolved!!
    }

    /** The screen's right and up in the world, from the corner the camera holds. */
    private fun screenAxes(): Pair<Vector3, Vector3> {
        val iso = atan(1.0 / sqrt(2.0))
        val y = Math.toRadians(yaw)
        val eye = Vector3(sin(y) * cos(iso), sin(iso), cos(y) * cos(iso))
        val up = Vector3(-sin(y) * sin(iso), cos(iso), -cos(y) * sin(iso))
        return up.cross(eye) to up
    }

    /** Mesh [m]'s largest extent, in its own normalised units. */
    private fun extentOf(m: ObjMesh) = maxOf(
        m.points.maxOf { it.x } - m.points.minOf { it.x },
        m.points.maxOf { it.y } - m.points.minOf { it.y },
        m.points.maxOf { it.z } - m.points.minOf { it.z })

    /** Mesh [m] a quarter turn [q] round, seen from the corner: its box across and down the pane, in mesh units. */
    private fun projectedBox(m: ObjMesh, q: Int): DoubleArray = projectedBox(m, q * 90.0)

    /** Mesh [m] turned [degrees] round, seen from the corner: its box across and down the pane, in mesh units. */
    private fun projectedBox(m: ObjMesh, degrees: Double): DoubleArray {
        val (right, up) = screenAxes()
        val turn = buildTransform { rotate(Vector3.UNIT_Y, degrees) }
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (p in m.points) {
            val r = (turn * Vector4(p.x, p.y, p.z, 1.0)).xyz
            val sx = r.dot(right); val sy = -r.dot(up)
            if (sx < x0) x0 = sx; if (sx > x1) x1 = sx
            if (sy < y0) y0 = sy; if (sy > y1) y1 = sy
        }
        return doubleArrayOf(x0, x1, y0, y1)
    }

    private companion object {
        const val SIZE = 200.0
        const val GOLDEN = 0.6180339887498949
        /** The ring: a piece's height as a share of the pane's, its widest in heights, how far it drops as it
         *  lands, seconds it glows, how much larger it lands; the plain ring's gap; and the room it is fitted into. */
        const val UNIT = 0.075
        const val WIDEST = 2.4
        const val DROP = 0.05
        const val GLOW = 1.2
        const val POP = 0.35
        const val GAP_DEG = 26.0
        const val RING_MARGIN = 0.16
        const val RING_TOP = 0.15
        const val RING_BOTTOM = 0.95
        /** Seconds after the ring's last landing the grid takes it from, when it was not handed the ring. */
        const val FALLBACK = 3.0
        /** How much of its cell a piece fills. */
        const val FILL = 0.8
        /** The grid's arrival: a beat before the first piece leaves the ring, and the spread of the leaving. */
        const val MOVE_LEAD = 0.25
        const val MOVE_SPREAD = 2.0
        /** The grid's click: the spread of the pieces taking their real size, outward from the middle, and the
         *  moment the figure steps in, once the middle has cleared. */
        const val SCALE_SPREAD = 1.4
        const val PERSON_AT = 0.45
        /** The sheet to scale: pixels between two pieces and two rows, whatever the scale; and the adults among the
         *  people model, as a share of the tallest figure. */
        const val GAP = 14.0
        const val ADULTS = 0.75
        /** A figure's legs, as the share of its height from the ground, for telling one standing still. */
        const val LEGS = 0.45
        /** The webtool's arrival, in seconds: every piece coloured, one after another, over a spread; the whole sheet
         *  going into the button in one movement, on the webtool's own gather curve, the button growing out of it;
         *  the cursor coming in, starting a little before the sheet is in. */
        const val COLOUR_AT = 0.2
        const val COLOUR_SPREAD = 1.1
        const val GATHER_AT = 1.45
        const val GATHER = 1.3
        val GATHER_CURVE = CubicBezier(0.6, 0.0, 0.2, 1.0)
        const val CURSOR_LEAD = 0.2
        const val CURSOR_IN = 0.6
        /** The title plate, as the webtool sets it: reach above and below the baseline and either side, in type sizes. */
        const val ABOVE = 0.95
        const val BELOW = 0.45
        const val PAD = 0.4
        /** The catalogue's pieces' seeds, below any block of the kit's. */
        const val SEED_BASE = -1000
        const val PERSON_SEED = -900
    }
}

private fun lerp(a: Vector2, b: Vector2, t: Double) = a + (b - a) * t

/**
 * The fewest total cost of giving each row its own column: the Hungarian method, for a square or wide [cost].
 * Row i's column is the answer's i.
 */
private fun assignment(cost: Array<DoubleArray>): IntArray {
    val n = cost.size
    if (n == 0) return IntArray(0)
    val m = cost[0].size
    val u = DoubleArray(n + 1); val v = DoubleArray(m + 1)
    val p = IntArray(m + 1); val way = IntArray(m + 1)
    for (i in 1..n) {
        p[0] = i
        var j0 = 0
        val minv = DoubleArray(m + 1) { Double.MAX_VALUE }
        val used = BooleanArray(m + 1)
        do {
            used[j0] = true
            val i0 = p[j0]
            var delta = Double.MAX_VALUE
            var j1 = 0
            for (j in 1..m) if (!used[j]) {
                val cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                if (cur < minv[j]) { minv[j] = cur; way[j] = j0 }
                if (minv[j] < delta) { delta = minv[j]; j1 = j }
            }
            for (j in 0..m) if (used[j]) { u[p[j]] += delta; v[j] -= delta } else minv[j] -= delta
            j0 = j1
        } while (p[j0] != 0)
        do { val j1 = way[j0]; p[j0] = p[j1]; j0 = j1 } while (j0 != 0)
    }
    val answer = IntArray(n)
    for (j in 1..m) if (p[j] != 0) answer[p[j] - 1] = j - 1
    return answer
}
