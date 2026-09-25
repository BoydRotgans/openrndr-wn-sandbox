import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.shadeStyle
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.math.Matrix44
import org.openrndr.math.Vector3
import org.openrndr.math.transforms.buildTransform
import org.openrndr.shape.Rectangle
import org.openrndr.shape.ShapeContour
import slideshow.MosaicCells
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

// sketch-variant-of: CourseFlow
/**
 * v4 flow, v2: **the same loop, the wall laid on the shadow mosaic's grid, closer in.** The catalogue
 * drifts left to right, loose at either end and joined in the middle, round and round, as in
 * [flowCourse] — but what joins is not columns of panels, it is the mosaic: coarse cells on the
 * catalogue's 1.85 proportion, each split at random into halves and quarters, every leaf standing
 * one of the three drawings nearest its own shape, stretched to fill it less a joint — never more than
 * `FLOW2_STRETCH` out of the drawing's own proportion, a plain slab stood on end where that fits a
 * narrow cell better, and a cell too few drawings fit cut in half again. `ShadowMosaic`
 * packs twelve coarse cells across the wall; this packs `FLOW2_ROWS` of them up it, four by default,
 * which is about seven and a half across — the same grid seen from much nearer.
 *
 *     ./gradlew run -Popenrndr.application=CourseFlow2Kt
 *
 * **Every leaf travels on its own, and no two ever overlap.** Its edges ride the strip and are
 * carried onto the screen by the same stretch as the columns of v1, so where the wall is loose the
 * leaves stand apart across as well as up, and where it joins they close into the mosaic exactly.
 * The stretch only ever pulls apart, so the cell a leaf had in the mosaic, carried onto the wall,
 * is a room of its own that no other leaf's room overlaps — and a loose leaf wanders and floats
 * only inside it: `FLOW2_STRAY` of the room, and `FLOW2_DRIFT` more as it floats.
 *
 * **It opens on a build**: a front rises up the wall from the floor and each piece scales in as it
 * passes — `FLOW2_BUILD`, `_BUILD_GROW` and `_BUILD_JITTER`. `FLOW2_BUILD_FROM=right` is the first
 * version, the front crossing the wall from right to left (`left` and `top` also work).
 *
 * The motion is v1's and reads v1's keys — `FLOW_SPEED`, `_SPREAD`, `_LIFT`, `_JOIN`, the colours
 * and the seed — unless a `FLOW2_` key of the same name says otherwise; `FLOW2_ROWS`, `_JOINT`,
 * `_STRAY` and `_DRIFT` are its own. Black and white, as v1 is for now.
 */
fun main() = runCourse("course-v4-flow-v2", preview = 20.0) { flowMosaicCourse() }

fun Program.flowMosaicCourse(): (Drawer, Double) -> Unit {
    /** Its own key first, then the one v1 reads, so the two move alike until they are told apart. */
    fun key(k: String) = Env["FLOW2_$k"] ?: Env["FLOW_$k"]
    fun own(k: String) = Env["FLOW2_$k"]
    fun number(k: String, default: Double) = key(k)?.toDoubleOrNull() ?: default

    val marks = loadMarkShapes(File(key("MARKS") ?: "data/svg/subset_svg"))
    require(marks.isNotEmpty()) { "flow v2: no marks" }
    val format = vertexFormat { position(3) }
    val buffers: List<VertexBuffer> = marks.map { m ->
        vertexBuffer(format, m.triangles.size).also { vb -> vb.put { m.triangles.forEach { write(Vector3(it.x, it.y, 0.0)) } } }
    }

    val width = 3840.0
    val height = 1080.0
    val rows = own("ROWS")?.toIntOrNull() ?: 4
    val cellHeight = height / rows
    val cellWidth = cellHeight * MosaicCells.SHAPE
    val joint = own("JOINT")?.toDoubleOrNull() ?: 4.0
    val spread = number("SPREAD", 2.4)
    val join = (key("JOIN") ?: "0.14,0.38,0.62,0.86").split(",").map { it.trim().toDouble() }
    val strip = FlowStrip(width, spread, join, (cellWidth * spread + cellWidth) * 1.2)

    // The deck: whole coarse columns until the strip covers the wall and its margins, each column's
    // cells split from the seed. A leaf is kept in strip coordinates — across from the strip's start,
    // up from the floor — with the mark it stands and a seed of its own for its stray.
    class Leaf(val box: Rectangle, val mark: Int, val onEnd: Boolean, val seed: Int, val column: Int)

    // **No drawing is pulled more than `FLOW2_STRETCH` out of its own proportion.** The split makes
    // cells from half as wide as a drawing to twice as long as the longest, and stretching the nearest
    // drawing into those squeezed a window panel into a square and a slab to a sliver. So a cell draws
    // only from the drawings that fit it within that much: any piece upright, or one of the plain slabs
    // stood on end (`FLOW2_ON_END`) — never a door, a window or a gable, which on end read as fallen
    // over or skewed. A cell that fewer than `FLOW2_CHOICES` drawings fit is cut in half along its
    // length first, as the split would have — so a squarish cell becomes two panels rather than always
    // the one corner piece that fits it — and should nothing fit even then, the nearest is drawn
    // stretched only that far and smaller, standing in the middle of its cell.
    val tolerance = own("STRETCH")?.toDoubleOrNull() ?: 1.2
    val choices = own("CHOICES")?.toIntOrNull() ?: 2
    val onEnd = (own("ON_END") ?: "1,2,3,4,6,7,11").split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
    class Candidate(val mark: Int, val onEnd: Boolean, val aspect: Double)
    val candidates = marks.indices.flatMap { i ->
        listOf(Candidate(i, false, marks[i].aspect)) + if (i in onEnd) listOf(Candidate(i, true, 1.0 / marks[i].aspect)) else emptyList()
    }
    fun stretchOf(c: Candidate, aspect: Double) = kotlin.math.exp(abs(ln(c.aspect / aspect)))
    fun drawnAspect(r: Rectangle) = (r.width - joint).coerceAtLeast(1.0) / (r.height - joint).coerceAtLeast(1.0)

    val random = Random(key("SEED")?.toIntOrNull() ?: 7)
    val leaves = mutableListOf<Leaf>()
    var columns = 0
    var cut = 0
    fun place(r: Rectangle, column: Int) {
        val aspect = drawnAspect(r)
        val fits = candidates.filter { stretchOf(it, aspect) <= tolerance }
        if (fits.size < choices) {
            val halves = if (r.width >= r.height) listOf(Rectangle(r.x, r.y, r.width / 2.0, r.height), Rectangle(r.x + r.width / 2.0, r.y, r.width / 2.0, r.height))
                else listOf(Rectangle(r.x, r.y, r.width, r.height / 2.0), Rectangle(r.x, r.y + r.height / 2.0, r.width, r.height / 2.0))
            if (minOf(halves[0].width, halves[0].height) >= MosaicCells.THINNEST) { cut++; halves.forEach { place(it, column) }; return }
        }
        val pool = (fits.ifEmpty { candidates }).sortedBy { stretchOf(it, aspect) }.take(3)
        val pick = pool[random.nextInt(pool.size)]
        leaves += Leaf(r, pick.mark, pick.onEnd, random.nextInt(), column)
    }
    while (columns * cellWidth < strip.visible) {
        for (row in 0 until rows) {
            MosaicCells.split(Rectangle(columns * cellWidth, row * cellHeight, cellWidth, cellHeight), random) { r -> place(r, columns) }
        }
        columns++
    }
    val total = columns * cellWidth
    val worst = leaves.maxOf { l -> exp(abs(ln((if (l.onEnd) 1.0 / marks[l.mark].aspect else marks[l.mark].aspect) / drawnAspect(l.box)))) }
    println("flow v2: $columns coarse columns of $rows, ${leaves.size} leaves (${leaves.count { it.onEnd }} on end, $cut cells cut again), " +
        "furthest from its own shape ${"%.2f".format(worst)}x, strip ${"%.0f".format(total)} px")

    val speed = number("SPEED", 24.0)
    val lift = number("LIFT", 0.7)
    // How much of its own room a loose piece wanders over, and how far on top of that it floats.
    val stray = own("STRAY")?.toDoubleOrNull() ?: 0.8
    val drift = own("DRIFT")?.toDoubleOrNull() ?: 0.2
    val face = ColorRGBa.fromHex(key("FACE") ?: "FFFFFF")
    val paper = ColorRGBa.fromHex(key("PAPER") ?: "000000")
    val ink = ColorRGBa.fromHex(key("INK") ?: "000000")
    val line = own("LINE")?.toDoubleOrNull() ?: 0.0
    val fill = shadeStyle { fragmentTransform = "x_fill = p_color;" }

    // **It opens on a build.** A front rises up the wall from the floor over `FLOW2_BUILD` seconds
    // and every piece scales in about its own middle as the front reaches it, over `FLOW2_BUILD_GROW`,
    // each up to `FLOW2_BUILD_JITTER` seconds off the front so it arrives as a ragged edge of pieces
    // rather than a ruled line. The front is read off where a piece stands on the wall at the time, so
    // the build is a function of the second like everything else here; a piece only ever grows inside
    // its own room, so the build keeps the no-overlap rule too. 0 opens on the wall already standing.
    // `FLOW2_BUILD_FROM` is the edge the front sets off from: `bottom`, or `right` as it was first,
    // `left` or `top`. A piece lifted above the top of the wall is built as the front reaches the top,
    // so it is standing by the time a joining column brings it down into view.
    val build = own("BUILD")?.toDoubleOrNull() ?: 6.0
    val grow = (own("BUILD_GROW")?.toDoubleOrNull() ?: 0.9).coerceAtLeast(0.01)
    val buildJitter = own("BUILD_JITTER")?.toDoubleOrNull() ?: 1.0
    val buildFrom = own("BUILD_FROM")?.trim()?.lowercase() ?: "bottom"
    /** How far the front has to travel to reach a piece standing at `x, y`, as a share of the wall. */
    val reach: (Double, Double) -> Double = when (buildFrom) {
        "right" -> { x, _ -> (width - x.coerceIn(-width, 2.0 * width)) / width }
        "left" -> { x, _ -> x.coerceIn(-width, 2.0 * width) / width }
        "top" -> { _, y -> y.coerceIn(0.0, height) / height }
        else -> { _, y -> (height - y.coerceIn(0.0, height)) / height }
    }
    fun easeOut(u: Double) = 1.0 - (1.0 - u).let { it * it * it }

    return { drawer: Drawer, time: Double ->
        CourseControl.current = null
        drawer.clear(paper)
        val outlines = mutableListOf<ShapeContour>()
        drawer.isolated {
            drawer.shadeStyle = fill
            fill.parameter("color", face)
            // How loose each coarse column is, read at its middle: every piece in a column lifts by the
            // same share, so pieces stacked in it keep their order up the wall.
            val looseness = DoubleArray(columns) { c ->
                strip.screenX((c * cellWidth + cellWidth / 2.0 + time * speed).mod(total))?.let { 1.0 - strip.joined(it) } ?: 1.0
            }
            for (leaf in leaves) {
                // **A piece never leaves its own room, so no two ever overlap.** Its room is its cell in
                // the joined mosaic carried onto the wall by the spread: across, the two edges of the
                // cell each ride the strip, and the strip is only ever pulled apart, never pushed
                // together, so rooms that were side by side stay side by side; up, the whole coarse
                // column lifts by one share, so rooms that were one above the other stay so. Rooms are
                // then as far apart as the cells were, and a piece is drawn inside its room, the joint
                // kept — where the wall is joined the room is the cell and the piece stands still in it.
                val q0 = (leaf.box.x + time * speed).mod(total)
                val left = strip.screenX(q0) ?: continue
                val right = strip.screenX(q0 + leaf.box.width) ?: continue
                val loose = looseness[leaf.column]
                val rise = 1.0 + lift * loose
                val roomX = ((right - left) - leaf.box.width) / 2.0
                val roomY = leaf.box.height * (rise - 1.0) / 2.0
                val r = Random(leaf.seed)
                val phase = r.nextDouble() * 6.2832
                val ux = ((r.nextDouble() * 2.0 - 1.0) * stray + cos(time * 0.37 + phase) * drift).coerceIn(-1.0, 1.0)
                val uy = ((r.nextDouble() * 2.0 - 1.0) * stray + sin(time * 0.5 + phase) * drift).coerceIn(-1.0, 1.0)
                val x = (left + right) / 2.0 + ux * roomX
                // Up off the floor as it comes loose, the higher the further, as the rows of v1 do.
                val y = height - (leaf.box.y + leaf.box.height / 2.0) * rise + uy * roomY
                // Drawn after the stray's, so the stray stays what it was before the build existed.
                val lateness = r.nextDouble()
                val built = if (build <= 0.0) 1.0 else {
                    val front = reach(x, y) * build
                    easeOut(((time - front - lateness * buildJitter) / grow).coerceIn(0.0, 1.0))
                }
                if (built <= 0.0) continue
                val m = marks[leaf.mark]
                var w = (leaf.box.width - joint).coerceAtLeast(1.0)
                var h = (leaf.box.height - joint).coerceAtLeast(1.0)
                // Stretched to its cell, but never past the tolerance: beyond it, drawn smaller instead.
                val shape = if (leaf.onEnd) 1.0 / m.aspect else m.aspect
                if (w / h > shape * tolerance) w = h * shape * tolerance
                if (w / h < shape / tolerance) h = w / (shape / tolerance)
                w *= built
                h *= built
                val model = if (leaf.onEnd) buildTransform {
                    translate(x, y, 0.0)
                    rotate(Vector3.UNIT_Z, 90.0)
                    scale(h / m.aspect, -w, 1.0)
                } else buildTransform {
                    translate(x, y, 0.0)
                    scale(w / m.aspect, -h, 1.0)
                }
                drawer.model = model
                drawer.vertexBuffer(buffers[leaf.mark], DrawPrimitive.TRIANGLES)
                if (line > 0.0) m.contours.forEach { outlines += it.transform(model) }
            }
            drawer.model = Matrix44.IDENTITY
        }
        if (outlines.isNotEmpty()) drawer.isolated {
            drawer.fill = null
            drawer.stroke = ink
            drawer.strokeWeight = line
            drawer.contours(outlines)
        }
    }
}
