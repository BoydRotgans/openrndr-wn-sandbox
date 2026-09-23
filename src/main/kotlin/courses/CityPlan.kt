import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.ColorType
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.renderTarget
import org.openrndr.math.Vector2
import org.openrndr.shape.Circle
import org.openrndr.shape.Rectangle
import org.openrndr.shape.Shape
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.contains
import org.openrndr.shape.triangulate
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * A town laid over the site: avenues and streets, straight and curved, with a kerb either side, and
 * parks with trees in them. The mosaic's marks are kept only where they stand clear of it, so the
 * cells gather into street blocks and the grid underneath reads as a city rather than a field.
 *
 * **Everything wraps on the site's own tile** — an avenue running across is a whole number of waves
 * over the width, a street running down a whole number over the height — so the site can be laid
 * edge to edge in every direction with no seam, which is what lets the camera stand anywhere without
 * ever seeing the edge of the world.
 */
class CityPlan(private val width: Double = 3840.0, private val height: Double = 1080.0, seed: Int = 7, trees: Boolean = true) {

    class Road(val path: ShapeContour, val width: Double)

    /** A kerb's width either side of a road, in site pixels. */
    val kerb = 7.0

    val roads: List<Road> = run {
        fun across(y0: Double, amp: Double, waves: Int, phase: Double, w: Double) = Road(
            ShapeContour.fromPoints((-10..(width / 8).toInt() + 10).map { i ->
                val x = i * 8.0
                Vector2(x, y0 + amp * sin(2.0 * PI * waves * x / width + phase))
            }, closed = false), w)
        fun down(x0: Double, amp: Double, waves: Int, phase: Double, w: Double) = Road(
            ShapeContour.fromPoints((-10..(height / 8).toInt() + 10).map { i ->
                val y = i * 8.0
                Vector2(x0 + amp * sin(2.0 * PI * waves * y / height + phase), y)
            }, closed = false), w)
        listOf(
            across(300.0, 70.0, 1, 0.0, 46.0),        // the main avenue, one long curve over the tile
            across(835.0, 36.0, 2, 1.0, 32.0),
            down(430.0, 0.0, 0, 0.0, 28.0),
            down(1160.0, 110.0, 1, 0.0, 34.0),        // a street bending through the left projector
            down(1935.0, 0.0, 0, 0.0, 38.0),
            down(2680.0, 80.0, 1, 2.0, 28.0),
            down(3360.0, 0.0, 0, 0.0, 30.0)
        )
    }

    val parks: List<Shape> = listOf(
        Rectangle(1300.0, 430.0, 460.0, 300.0).shape,
        Circle(2980.0, 560.0, 175.0).shape,
        Rectangle(470.0, 880.0, 380.0, 200.0).shape,
        Rectangle(2150.0, 40.0, 330.0, 190.0).shape
    )

    /** Whether a cell stands clear of every road, its kerbs, and every park. */
    fun clear(r: Rectangle): Boolean {
        val probe = listOf(r.center, r.corner, Vector2(r.x + r.width, r.y), Vector2(r.x, r.y + r.height),
            Vector2(r.x + r.width, r.y + r.height), Vector2(r.center.x, r.y), Vector2(r.center.x, r.y + r.height),
            Vector2(r.x, r.center.y), Vector2(r.x + r.width, r.center.y))
        if (parks.any { park -> probe.any { park.contains(it) } || park.bounds.intersects(r) && park.contains(r.center) }) return false
        return roads.none { road -> probe.any { road.path.nearest(it).position.distanceTo(it) < road.width / 2.0 + kerb + 2.0 } }
    }

    /** Trees scattered through the parks: a centre, a radius, and a height as a multiple of the radius. */
    val trees: List<Triple<Vector2, Double, Double>> = if (!trees) emptyList() else run {
        val random = Random(seed)
        val step = 30.0
        parks.flatMap { park ->
            val b = park.bounds
            val out = mutableListOf<Triple<Vector2, Double, Double>>()
            var y = b.y + step * 0.6
            while (y < b.y + b.height) {
                var x = b.x + step * 0.6
                while (x < b.x + b.width) {
                    val c = Vector2(x + (random.nextDouble() - 0.5) * step * 0.7, y + (random.nextDouble() - 0.5) * step * 0.7)
                    val r = 7.0 + random.nextDouble() * 7.0
                    // A share of the park left open as lawn, and the trees kept off its edge.
                    if (random.nextDouble() < 0.62 && park.contains(c) &&
                        park.contours.all { it.nearest(c).position.distanceTo(c) > r + 4.0 })
                        out += Triple(c, r, 0.9 + random.nextDouble() * 0.8)
                    x += step
                }
                y += step
            }
            out
        }
    }

    /** A tree's shape: a disc of height 1, the marks' normalisation. */
    val treeShape: MarkShape = run {
        val disc = Circle(0.0, 0.0, 0.5)
        MarkShape(triangulate(disc.shape), listOf(disc.contour), 1.0)
    }

    /**
     * The ground as a picture the 3D steps read back: the tone of a road, a kerb, a park and the plots
     * in the red channel, drawn over the tile and its eight neighbours so every edge wraps.
     */
    fun ground(program: Program, road: Double = 0.05, kerbTone: Double = 0.17, park: Double = 0.09, plot: Double = 0.0): ColorBuffer {
        val target = renderTarget(width.toInt(), height.toInt()) { colorBuffer(type = ColorType.FLOAT16); depthBuffer() }
        // Hoisted: inside the drawer's block `width` would be the render target's, an Int.
        val tile = listOf(-width, 0.0, width)
        val tileY = listOf(-height, 0.0, height)
        program.drawer.isolatedWithTarget(target) {
            ortho(target)
            clear(ColorRGBa(plot, 0.0, 0.0, 1.0))
            for (dx in tile) for (dy in tileY) {
                translate(dx, dy)
                stroke = null
                fill = ColorRGBa(park, 0.0, 0.0, 1.0)
                shapes(parks)
                fill = null
                roads.forEach { r ->
                    stroke = ColorRGBa(kerbTone, 0.0, 0.0, 1.0); strokeWeight = r.width + 2.0 * kerb; contour(r.path)
                }
                roads.forEach { r ->
                    stroke = ColorRGBa(road, 0.0, 0.0, 1.0); strokeWeight = r.width; contour(r.path)
                }
                translate(-dx, -dy)
            }
        }
        return target.colorBuffer(0)
    }
}

/** The ground's tones: the plots between buildings, the roads, the kerbs and the parks. */
class Grounds(val plot: Double, val road: Double, val kerb: Double, val park: Double)

/** The site as a town, ready to draw: the plan, its ground, and the city standing on both. */
fun org.openrndr.Program.townCity(
    grounds: Grounds = Grounds(0.0, 0.05, 0.17, 0.09),
    concrete: Boolean = false,
    photographs: Boolean = false,
    trees: Boolean = true
): SiteCity {
    val plan = CityPlan(trees = trees)
    val stone = if (!concrete) null else java.io.File(Env["COURSE_CONCRETE"] ?: "data/concrete/concrete-052v2_crop.jpg")
        .takeIf { it.isFile }?.let { f ->
            org.openrndr.draw.loadImage(f).also {
                it.wrapU = org.openrndr.draw.WrapMode.REPEAT; it.wrapV = org.openrndr.draw.WrapMode.REPEAT
                it.generateMipmaps(); it.filterMin = org.openrndr.draw.MinifyingFilter.LINEAR_MIPMAP_LINEAR
            }
        }
    val atlas = if (photographs) photoAtlas(this) else null
    return SiteCity(
        Site.load(), plan, plan.ground(this, grounds.road, grounds.kerb, grounds.park, grounds.plot),
        stoneMap = stone, photos = atlas?.first, photoGrid = atlas?.second ?: 1, photoCount = atlas?.third ?: 0
    )
}

/**
 * WN's own project photographs — the case-study folders under `SLIDES_HIGHLIGHTS` — laid in a grid
 * atlas, each cut to a square from its middle: the real world the Dessert's buildings are clad in.
 */
fun photoAtlas(program: org.openrndr.Program): Triple<ColorBuffer, Int, Int>? {
    val root = java.io.File(Env["SLIDES_HIGHLIGHTS"] ?: "data/case-studies/Studie Cases")
    val files = root.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
        .sortedBy { it.path }.toList()
    if (files.isEmpty()) { println("courses: no photographs under ${root.path}"); return null }
    val grid = kotlin.math.ceil(kotlin.math.sqrt(files.size.toDouble())).toInt()
    val cell = 512
    val target = renderTarget(grid * cell, grid * cell) { colorBuffer() }
    program.drawer.isolatedWithTarget(target) {
        ortho(target)
        clear(ColorRGBa.BLACK)
        files.forEachIndexed { i, f ->
            val image = org.openrndr.draw.loadImage(f)
            val side = minOf(image.width, image.height).toDouble()
            val source = Rectangle((image.width - side) / 2.0, (image.height - side) / 2.0, side, side)
            // Drawn y-down into a target that is read y-up: each picture goes in upside down so it comes out right.
            val destination = Rectangle((i % grid) * cell.toDouble(), (i / grid) * cell.toDouble(), cell.toDouble(), cell.toDouble())
            image(image, source, destination)
            image.destroy()
        }
    }
    println("courses: ${files.size} photographs in a ${grid}x$grid atlas")
    return Triple(target.colorBuffer(0), grid, files.size)
}
