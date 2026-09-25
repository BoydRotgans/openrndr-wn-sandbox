import org.openrndr.draw.Drawer
import org.openrndr.color.ColorRGBa
import org.openrndr.math.transforms.buildTransform
import org.openrndr.shape.ShapeContour
import kotlin.math.cbrt

/**
 * The Aperitif, the first step: **the site as a drawing.** Nothing has a height and nothing is
 * filled. The grid is ruled faintly on the black, and each box draws its pieces in white line —
 * a pen travelling round every mark, starting as the box's circle reaches it, so a box fills from
 * its own middle outwards exactly as the Opening course will later light it. The box stands
 * drawn, then the same circle takes the lines back, pen running backwards, and the box stands
 * empty on its grid.
 *
 *     ./gradlew run -Popenrndr.application=CourseAperitifKt
 *
 * `APERITIF_TRACE` is the seconds one pen takes round one mark, `APERITIF_LINE` the weight, and
 * `APERITIF_PEN=true` puts a point at the head of every line still being drawn.
 */
fun main() = runCourse("course-1-aperitif", preview = 40.0) { aperitifCourse() }

/** The wall itself, a function of the drawer and the second, for [runCourse] and the course studio alike. */
fun org.openrndr.Program.aperitifCourse(): (Drawer, Double) -> Unit {
    val site = Site.load()
    val trace = Env["APERITIF_TRACE"]?.toDoubleOrNull() ?: 4.0
    val line = Env["APERITIF_LINE"]?.toDoubleOrNull() ?: 1.6
    val pen = Env.boolean("APERITIF_PEN")
    val ink = ColorRGBa.WHITE
    val rule = ColorRGBa.WHITE.opacify(0.10)

    // Every mark's outline laid on its cell once, stretched to fill it as the mosaic stretches it.
    class Drawn(val leaf: Site.Leaf, val contours: List<ShapeContour>, val shares: List<Double>)
    val drawn = site.leaves.filter { it.mark >= 0 }.map { leaf ->
        val m = site.marks[leaf.mark]
        val sx = leaf.rect.width / m.aspect
        val sy = leaf.rect.height
        val place = buildTransform { translate(leaf.centre); scale(sx, -sy) }
        val contours = m.contours.map { it.transform(place) }
        val lengths = contours.map { it.length }
        val total = lengths.sum().coerceAtLeast(1e-6)
        Drawn(leaf, contours, lengths.map { it / total })
    }

    /** Seconds after a box's circle sets off that its rim reaches distance [d]: the ease-out inverted. */
    fun arrival(d: Double, reach: Double): Double {
        val x = (d / reach).coerceIn(0.0, 1.0)
        return site.grow * (1.0 - cbrt(1.0 - x))
    }

    fun smooth(x: Double) = x.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }

    return { drawer: Drawer, time: Double ->
        drawer.stroke = rule
        drawer.strokeWeight = 1.0
        drawer.fill = null
        drawer.rectangles(site.coarse)

        val whole = mutableListOf<ShapeContour>()
        val parts = mutableListOf<ShapeContour>()
        drawn.forEach { dr ->
            val b = site.boxes[dr.leaf.box]
            var t = time - b.delay * site.period
            if (t < 0.0) return@forEach
            t = t.mod(site.period)
            val at = arrival(dr.leaf.centre.distanceTo(b.rect.center), site.reach(dr.leaf.box))
            // Drawn on through the first circle and the hold, taken back under the second circle.
            val shown = if (t < site.grow + site.hold) smooth((t - at) / trace)
                        else 1.0 - smooth((t - site.grow - site.hold - at) / trace)
            if (shown <= 0.0) return@forEach
            if (shown >= 1.0) { whole += dr.contours; return@forEach }
            // One pen through the mark's outlines in turn, so a mark with a hole draws its rim first.
            var left = shown
            dr.contours.forEachIndexed { i, c ->
                val share = dr.shares[i]
                if (left <= 0.0) return@forEachIndexed
                if (left >= share) whole += c else parts += c.sub(0.0, left / share)
                left -= share
            }
        }
        drawer.stroke = ink
        drawer.strokeWeight = line
        drawer.contours(whole)
        drawer.contours(parts)
        // The pen itself, a point at the head of every line still being drawn — off unless asked for.
        if (pen) {
            drawer.stroke = null
            drawer.fill = ink
            drawer.circles(parts.mapNotNull { it.segments.lastOrNull()?.end }, line * 1.8)
        }
    }
}
