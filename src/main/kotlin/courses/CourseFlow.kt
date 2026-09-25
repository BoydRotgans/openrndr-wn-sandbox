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
import org.openrndr.shape.ShapeContour
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// The Sketches tab: what the sketch's own run is called, then its variants, one a line —
// name | main class (empty for this file's) | .env values | what it is.
// sketch-default: v1, black and white
// sketch-variant: v2, on the mosaic grid | CourseFlow2Kt | | the same loop on the shadow mosaic's grid, closer in: every leaf its own piece, none overlapping
// sketch-variant: v2, built from the right | CourseFlow2Kt | FLOW2_BUILD_FROM=right | v2 as it first opened: the build crossing the wall from right to left rather than rising from the floor
// sketch-variant: in colour, slanted | | FLOW_TINT=0.2 FLOW_SIDE=4E5056 FLOW_INK=9A9CA2 FLOW_LINE=1 FLOW_SHEAR=0.35 | the first version: the v3 grid's colours and the panels slanted into chevrons
/**
 * v4, assembling and taking apart, flat: **the catalogue drifting across the wall from left to
 * right, loose at either end and joined into one wall in the middle.** After the sketch of 24
 * September.
 *
 *     ./gradlew run -Popenrndr.application=CourseFlowKt
 *
 * The wall is a run of columns. A wide column is a stack of wall panels off `data/subset_svg` — a
 * doorway on the floor row where it has one, windows and plain panels above — each panel stretched
 * to one box and drawn square on, as the catalogue draws it in elevation. A narrow column is a
 * notched slab stood on end, repeated up the wall. `FLOW_SHEAR` slants the panels instead, the slant
 * turning the other way column by column so the joined wall reads as chevrons, as the sketch had it.
 *
 * **Where a column stands decides how joined it is.** Toward either end of the wall its pieces
 * float apart — the columns spread `FLOW_SPREAD` times their own width apart, the rows open
 * upward off the floor by `FLOW_LIFT`, and every piece strays and drifts a little on its own;
 * through the middle all of that closes to nothing and the columns stand edge to edge, one wall.
 * `FLOW_JOIN` places the four edges of that, as shares of the wall: loose until the first,
 * joined between the second and third, loose again from the fourth.
 *
 * **It is a loop.** The columns are one strip moving right at `FLOW_SPEED`, and the screen is a
 * stretch of it pulled out at the ends and pressed together in the middle — a column slows as it
 * joins and speeds up as it comes apart. What leaves on the right comes back on the left, just
 * as loose as it left; every position is a function of the second, nothing carried between frames.
 *
 * **Black and white for now**: every piece white on black, a black line on every edge so the pieces
 * still read apart once they are joined. `FLOW_TINT` runs every other row toward the WN blue or red,
 * and `FLOW_SIDE` greys the slabs, for the v3 grid's colours.
 */
fun main() = runCourse("course-v4-flow", preview = 20.0) { flowCourse() }

fun Program.flowCourse(): (Drawer, Double) -> Unit {
    fun key(k: String) = Env["FLOW_$k"]
    fun number(k: String, default: Double) = key(k)?.toDoubleOrNull() ?: default
    fun indices(k: String, default: String) = (key(k) ?: default).split(",").mapNotNull { it.trim().toIntOrNull() }

    val marks = loadMarkShapes(File(key("MARKS") ?: "data/svg/subset_svg"))
    require(marks.isNotEmpty()) { "flow: no marks" }
    fun mark(i: Int) = marks[i.coerceIn(0, marks.size - 1)]
    val format = vertexFormat { position(3) }
    val buffers: List<VertexBuffer> = marks.map { m ->
        vertexBuffer(format, m.triangles.size).also { vb ->
            vb.put { m.triangles.forEach { write(Vector3(it.x, it.y, 0.0)) } }
        }
    }

    // The pieces: panels for the wide columns, doorways for their floor row, windows among the rows
    // above, and the slabs that stand on end as the narrow ones.
    val panels = indices("PANELS", "1,2,7,11")
    val doors = indices("DOORS", "12")
    val windows = indices("WINDOWS", "9,10")
    val slabs = indices("SLABS", "3,4,6")

    val width = 3840.0
    val height = 1080.0
    val rows = key("ROWS")?.toIntOrNull() ?: 10
    val rowHeight = height / rows
    val panelWidth = rowHeight * number("PANEL_SHAPE", 1.8)
    val slabThickness = rowHeight * number("SLAB", 0.4)
    val shear = number("SHEAR", 0.0)

    fun hex(c: String) = ColorRGBa.fromHex(c)
    /** Two hex colours mixed, stated as hex again so the result passes to the wall as exactly as they do. */
    fun mixHex(a: String, b: String, t: Double): ColorRGBa {
        val x = hex(a); val y = hex(b)
        fun ch(p: Double, q: Double) = ((p + (q - p) * t) * 255.0).toInt().coerceIn(0, 255)
        return hex("%02X%02X%02X".format(ch(x.r, y.r), ch(x.g, y.g), ch(x.b, y.b)))
    }
    val face = key("FACE") ?: "FFFFFF"
    val tints = (key("TINTS") ?: "3D5AE0,FF0000").split(",").map { it.trim() }
    val tint = number("TINT", 0.0)
    val white = hex(face)
    val blue = mixHex(face, tints[0], tint)
    val red = mixHex(face, tints.getOrElse(1) { tints[0] }, tint)
    val side = hex(key("SIDE") ?: "FFFFFF")
    val ink = hex(key("INK") ?: "000000")
    val paper = hex(key("PAPER") ?: "000000")
    val line = number("LINE", 2.0)

    // The deck: columns in the order they travel, dealt from the seed — a wide column, then none, one
    // or two narrow ones, never more than two of either kind together, and an even count of wide
    // ones so their slant alternates all the way round the loop, the join included.
    class Column(val wide: Boolean, val width: Double, val slant: Double, val tones: Pair<ColorRGBa, ColorRGBa>,
                 val floor: Int?, val slab: Int, val lift: Double, val seed: Int) {
        var start = 0.0
    }
    val random = Random(key("SEED")?.toIntOrNull() ?: 7)
    val spread = number("SPREAD", 2.4)
    val join = (key("JOIN") ?: "0.14,0.38,0.62,0.86").split(",").map { it.trim().toDouble() }
    // The margin is wide enough that a column and its strays are off the wall before it wraps.
    val strip = FlowStrip(width, spread, join, (panelWidth * spread + panelWidth) * 1.2)
    fun joined(x: Double) = strip.joined(x)
    fun screenX(q: Double) = strip.screenX(q)
    val visible = strip.visible

    val deck = mutableListOf<Column>()
    var total = 0.0
    var wideCount = 0
    var run = 0; var lastWide = false
    val doorShare = number("DOOR_SHARE", 0.5)
    while (total < visible || wideCount % 2 == 1) {
        val wide = if (run >= 2) !lastWide else random.nextDouble() < 0.55
        if (wide == lastWide) run++ else { run = 1; lastWide = wide }
        val column = if (wide) {
            val sign = if (wideCount % 2 == 0) 1.0 else -1.0
            wideCount++
            val tones = if (random.nextDouble() < 0.65) white to blue else white to red
            Column(true, panelWidth, sign * shear, tones,
                if (random.nextDouble() < doorShare && doors.isNotEmpty()) doors[random.nextInt(doors.size)] else null,
                0, 0.0, random.nextInt())
        } else {
            val slab = slabs[random.nextInt(slabs.size)]
            val tone = if (random.nextDouble() < 0.7) side else blue
            Column(false, slabThickness, 0.0, tone to tone, null, slab,
                random.nextDouble() * slabThickness * mark(slab).aspect, random.nextInt())
        }
        column.start = total
        deck += column
        total += column.width
    }
    println("flow: ${deck.size} columns, $wideCount wide, strip ${"%.0f".format(total)} px against ${"%.0f".format(visible)} on and around the wall")

    val speed = number("SPEED", 24.0)
    val lift = number("LIFT", 0.7)
    val stray = number("STRAY", 0.35)
    val fill = shadeStyle { fragmentTransform = "x_fill = p_color;" }

    return { drawer: Drawer, time: Double ->
        CourseControl.current = null
        drawer.clear(paper)
        val outlines = mutableListOf<ShapeContour>()
        drawer.isolated {
            drawer.shadeStyle = fill
            drawer.stroke = null
            deck.forEach { column ->
                val q = (column.start + column.width / 2.0 + time * speed).mod(total)
                val cx = screenX(q) ?: return@forEach
                val loose = 1.0 - joined(cx)
                /** One piece of the column: its mark, its box on the wall and its colour. */
                fun piece(markIndex: Int, centreY: Double, standing: Boolean, colour: ColorRGBa, j: Int) {
                    val r = Random(column.seed * 31 + j)
                    val phase = r.nextDouble() * 6.2832
                    val dx = (r.nextDouble() * 2.0 - 1.0) * stray * column.width * loose +
                        cos(time * 0.37 + phase) * column.width * 0.05 * loose
                    val dy = (r.nextDouble() * 2.0 - 1.0) * stray * rowHeight * loose +
                        sin(time * 0.5 + phase) * rowHeight * 0.08 * loose
                    val m = mark(markIndex)
                    val model = if (standing) buildTransform {
                        translate(cx + dx, centreY + dy, 0.0)
                        scale(slabThickness, slabThickness, 1.0)
                        rotate(Vector3.UNIT_Z, 90.0)
                    } else buildTransform {
                        translate(cx + dx, centreY + dy, 0.0)
                    } * Matrix44(
                        1.0, 0.0, 0.0, 0.0,
                        column.slant, 1.0, 0.0, 0.0,
                        0.0, 0.0, 1.0, 0.0,
                        0.0, 0.0, 0.0, 1.0
                    ) * buildTransform { scale(column.width / m.aspect, -rowHeight, 1.0) }
                    fill.parameter("color", colour)
                    drawer.model = model
                    drawer.vertexBuffer(buffers[markIndex.coerceIn(0, buffers.size - 1)], DrawPrimitive.TRIANGLES)
                    if (line > 0.0) m.contours.forEach { outlines += it.transform(model) }
                }
                // The rows stand on the floor, the bottom of the wall, and open upward off it as the
                // column comes loose: the floor row stays down, the rows above lift away.
                val open = 1.0 + lift * loose
                val floorY = height
                if (column.wide) {
                    // From a row under the floor, so the slant of the floor row leaves no gap at the foot.
                    var j = -1
                    while (true) {
                        val centreY = floorY - (j + 0.5) * rowHeight * open
                        if (centreY < -rowHeight * 2.0) break
                        val markIndex = when {
                            j == 0 && column.floor != null -> column.floor!!
                            else -> Random(column.seed + 977 * j).let { rr ->
                                if (windows.isNotEmpty() && rr.nextDouble() < 0.25) windows[rr.nextInt(windows.size)]
                                else panels[rr.nextInt(panels.size)]
                            }
                        }
                        piece(markIndex, centreY, false, if (j.mod(2) == 0) column.tones.first else column.tones.second, j)
                        j++
                    }
                } else {
                    val length = slabThickness * mark(column.slab).aspect
                    var j = 0
                    while (true) {
                        val centreY = floorY + column.lift - (j + 0.5) * length * open
                        if (centreY < -length) break
                        piece(column.slab, centreY, true, column.tones.first, j)
                        j++
                    }
                }
            }
            drawer.model = Matrix44.IDENTITY
        }
        if (outlines.isNotEmpty()) {
            drawer.isolated {
                drawer.fill = null
                drawer.stroke = ink
                drawer.strokeWeight = line
                drawer.contours(outlines)
            }
        }
    }
}

/**
 * The screen as a stretch of a strip moving right: walking the wall from [margin] left of it to
 * [margin] right of it, a pixel of screen covers 1/stretch of strip, the stretch [spread] where the
 * wall is loose and 1 where it is joined — so the strip is pressed together where the wall joins,
 * and a piece slows as it joins and speeds up as it comes apart. [join] places the four edges of the
 * joined stretch as shares of the wall: loose until the first, joined from the second to the third,
 * loose again from the fourth. Both flow sketches stand on it.
 */
class FlowStrip(private val width: Double, private val spread: Double, private val join: List<Double>, margin: Double) {
    private fun smooth(e0: Double, e1: Double, x: Double) = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
    /** How joined a piece standing at screen [x] is: 0 loose, 1 one wall. */
    fun joined(x: Double): Double { val u = x / width; return smooth(join[0], join[1], u) * (1.0 - smooth(join[2], join[3], u)) }
    private fun stretch(x: Double) = 1.0 + (spread - 1.0) * (1.0 - joined(x))

    private val step = 2.0
    private val xs = generateSequence(-margin) { it + step }.takeWhile { it <= width + margin }.toList()
    private val qs = DoubleArray(xs.size).also { qs -> for (i in 1 until xs.size) qs[i] = qs[i - 1] + step / stretch((xs[i - 1] + xs[i]) / 2.0) }
    /** How much strip the wall and its margins hold. */
    val visible = qs.last()

    /** Where strip position [q] stands on screen, or null in the stretch of strip past the margins. */
    fun screenX(q: Double): Double? {
        if (q < 0.0 || q > visible) return null
        var lo = 0; var hi = qs.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (qs[mid] <= q) lo = mid else hi = mid }
        val f = (q - qs[lo]) / (qs[hi] - qs[lo]).coerceAtLeast(1e-9)
        return xs[lo] + f * (xs[hi] - xs[lo])
    }
}
