// ============================================================================ //
//  No `package` declaration: it reads .env through Env, which is in the default
//  package. The courses are a sandbox beside the show — nothing in Slideshow.kt,
//  show-order.json or the organizer knows they exist.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.BufferMultisample
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.ColorFormat
import org.openrndr.draw.ColorType
import org.openrndr.draw.Drawer
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.renderTarget
import org.openrndr.math.Vector2
import org.openrndr.math.transforms.buildTransform
import org.openrndr.shape.Rectangle
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.triangulate
import org.openrndr.extra.svg.loadSVG
import slideshow.Clip
import slideshow.MosaicCells
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.random.Random

/**
 * One catalogue mark, normalised to height 1 and centred, y up — the same normalisation as
 * `markTemplate` in ObjectMosaic.kt, so the proportions and the order match the show's packing
 * and the random stream deals the same marks. It keeps the outlines as well as the triangles,
 * which the 2D stage draws and the 3D stages extrude.
 */
class MarkShape(val triangles: List<Vector2>, val contours: List<ShapeContour>, val aspect: Double)

fun loadMarkShapes(dir: File): List<MarkShape> =
    dir.listFiles()
        ?.filter { it.isFile && it.extension.equals("svg", ignoreCase = true) }
        ?.sortedBy { it.name }
        ?.mapNotNull { file ->
            val shapes = loadSVG(file).findShapes().map { it.effectiveShape }.filter { !it.empty }
            if (shapes.isEmpty()) return@mapNotNull null
            val boxes = shapes.map { it.bounds }
            val left = boxes.minOf { it.corner.x }
            val top = boxes.minOf { it.corner.y }
            val right = boxes.maxOf { it.corner.x + it.width }
            val bottom = boxes.maxOf { it.corner.y + it.height }
            val width = right - left
            val height = bottom - top
            if (width <= 0.0 || height <= 0.0) return@mapNotNull null
            val s = 1.0 / height
            val cx = (left + right) / 2.0
            val cy = (top + bottom) / 2.0
            val triangles = shapes.flatMap { triangulate(it) }.map { Vector2((it.x - cx) * s, -(it.y - cy) * s) }
            val flip = buildTransform { scale(s, -s); translate(-cx, -cy) }
            val contours = shapes.flatMap { it.contours }.map { it.transform(flip) }
            if (triangles.isEmpty()) null else MarkShape(triangles, contours, width / height)
        }
        .orEmpty()

/**
 * The ground every course stands on: `shadow-mosaic`'s own grid, dealt from its own seed.
 *
 * **It is the same deal, not a lookalike.** The boxes are cut and the light packing split with
 * exactly the calls and the random stream `ShadowMosaic` uses at its defaults — seed 21, twelve
 * coarse cells across for the boxes, twenty-four for the marks, a 1 px joint — so a mark on the
 * Aperitif wall stands where the Opening course lights it and where the block city raises it.
 * That is the whole idea of the courses: one site, seen with more dimension each time.
 */
class Site(
    val marks: List<MarkShape>,
    val width: Double = 3840.0,
    val height: Double = 1080.0,
    private val seed: Int = 21,
    val columns: Int = 12,
    private val innerColumns: Int = 24,
    private val pane: Double = 1920.0,
    private val boxSplit: List<Double> = listOf(1.0, 0.85, 0.6, 0.35),
    private val gap: Double = 1.0,
    /** The box cycle, as the Opening course runs it: 30 s to fill, 18 s to stand, twice. */
    val grow: Double = 30.0,
    val hold: Double = 18.0,
    /** The ring a mark comes up over as the rim passes, in pixels. */
    val band: Double = 36.0
) {
    class Box(val rect: Rectangle, val delay: Double)

    /** One mark on the site: its cell, which mark, its grey, its height 0..1, its box. */
    class Leaf(val rect: Rectangle, val mark: Int, val tone: Double, val height: Double, val box: Int) {
        val centre: Vector2 get() = rect.center
    }

    val period = 2.0 * grow + 2.0 * hold

    val boxes: List<Box> = run {
        val random = Random(seed * 17)
        val rows = MosaicCells.rows(Rectangle(0.0, 0.0, width, height), columns)
        val cw = width / columns
        val ch = height / rows
        val perPane = (pane / cw).toInt().coerceAtLeast(1)
        val out = mutableListOf<Rectangle>()
        fun cut(x: Int, y: Int, w: Int, h: Int, depth: Int) {
            val chance = boxSplit.getOrElse(depth) { 0.0 }
            if (w * h > 1 && random.nextDouble() < chance) {
                if (w >= h && w > 1) {
                    val k = 1 + random.nextInt(w - 1)
                    cut(x, y, k, h, depth + 1); cut(x + k, y, w - k, h, depth + 1); return
                }
                if (h > 1) {
                    val k = 1 + random.nextInt(h - 1)
                    cut(x, y, w, k, depth + 1); cut(x, y + k, w, h - k, depth + 1); return
                }
            }
            out += Rectangle(x * cw, y * ch, w * cw, h * ch)
        }
        for (p in 0 until (columns + perPane - 1) / perPane) cut(p * perPane, 0, minOf(perPane, columns - p * perPane), rows, 0)
        val dealt = out.take(48).map { Box(it, random.nextDouble()) }
        val first = dealt.minOfOrNull { it.delay } ?: 0.0
        dealt.map { Box(it.rect, it.delay - first) }
    }

    /** The coarse cells the boxes are cut on, for ruling the ground. */
    val coarse: List<Rectangle> = run {
        val rows = MosaicCells.rows(Rectangle(0.0, 0.0, width, height), columns)
        val cw = width / columns
        val ch = height / rows
        (0 until rows).flatMap { r -> (0 until columns).map { c -> Rectangle(c * cw, r * ch, cw, ch) } }
    }

    val leaves: List<Leaf> = run {
        val random = Random(seed + 1)
        val area = Rectangle(0.0, 0.0, width, height)
        val rows = MosaicCells.rows(area, innerColumns)
        val cw = width / innerColumns
        val ch = height / rows
        val out = mutableListOf<Leaf>()
        for (row in 0 until rows) for (column in 0 until innerColumns) {
            MosaicCells.split(Rectangle(column * cw, row * ch, cw, ch), random) { r ->
                val box = r.offsetEdges(-gap / 2.0)
                val aspect = box.width / box.height
                val nearest = marks.indices.sortedBy { abs(ln(marks[it].aspect / aspect)) }.take(3)
                val mark = if (nearest.isEmpty()) -1 else nearest[random.nextInt(nearest.size)]
                val t = random.nextDouble()
                val tone = 0.06 + (0.85 - 0.06) * t * t
                val h = 0.2 + 0.8 * random.nextDouble()
                val owner = boxes.indexOfFirst { it.rect.contains(box.center) }.coerceAtLeast(0)
                out += Leaf(box, mark, tone, h, owner)
            }
        }
        out
    }

    /** The radius that fills box [i]: its far corner and the band past it. */
    fun reach(i: Int): Double = boxes[i].rect.let { it.center.distanceTo(it.corner) } + band

    /**
     * How far [leaf] stands at [time], 0..1, by the Opening course's own rule: its box's circle
     * grows on an ease-out and raises it as the rim passes, it stands through the hold, a second
     * circle takes it down again, and the box stands empty. Before its first start it is down.
     */
    fun standing(leaf: Leaf, time: Double): Double {
        val b = boxes[leaf.box]
        var t = time - b.delay * period
        t = if (t < 0.0) period - 0.001 else t.mod(period)
        val d = leaf.centre.distanceTo(b.rect.center)
        val r0 = reach(leaf.box)
        fun rim(u: Double) = r0 * (1.0 - (1.0 - u) * (1.0 - u) * (1.0 - u))
        fun smooth(x: Double) = x.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
        return if (t < grow + hold) smooth((rim((t / grow).coerceIn(0.0, 1.0)) - d) / band + 0.5)
        else 1.0 - smooth((rim(((t - grow - hold) / grow).coerceIn(0.0, 1.0)) - d) / band + 0.5)
    }

    companion object {
        fun load(): Site = Site(loadMarkShapes(File(Env["COURSE_MARKS"] ?: "data/svg/subset_svg")))
    }
}

/**
 * The harness every course sketch runs in: the wall composed at 3840x1080, multisampled, shown in
 * the window at half, and driven by a clock that is a pure function of the frame.
 *
 * - interactive: `space` holds, `←` `→` jump ten seconds, `0` back to the start.
 * - `COURSE_AT=5,40` writes a still at each of those seconds to `screenshots/courses/` and quits.
 * - `COURSE_RECORD=true` writes `video/courses/<name>.mp4`, `COURSE_DURATION` seconds from
 *   `COURSE_FROM`, at `COURSE_FPS`, at `COURSE_RECORD_SCALE` of the canvas (half), under the concrete
 *   wall when `COURSE_WALL` is on, and `-view<n>` in the name when `COURSE_VIEW` holds a saved angle. It is rendered rather than filmed, so a clip of a
 *   slow wall comes out as fast as the GPU can draw it and every frame is exact.
 */
fun runCourse(
    name: String, preview: Double = 30.0,
    /** The canvas, 3840 x 1080 for a wall; a course that composes for one projector says 1920. */
    canvasWidth: Int = 3840, canvasHeight: Int = 1080,
    course: Program.() -> (Drawer, Double) -> Unit
) = application {
    // The window shows the canvas at COURSE_WINDOW of its size; left empty, as large as fits 1920 wide — a
    // one-projector course at its own 1920x1080, a wall at half.
    val windowScale = Env["COURSE_WINDOW"]?.toDoubleOrNull() ?: minOf(1.0, 1920.0 / canvasWidth)
    configure {
        width = (canvasWidth * windowScale).toInt()
        height = (canvasHeight * windowScale).toInt()
        title = name
    }
    program {
        CourseControl.fixed = CourseViews.asked(name)
        CourseCanvas.width = canvasWidth; CourseCanvas.height = canvasHeight
        val frame = course()
        val w = canvasWidth
        val h = canvasHeight
        val canvas = renderTarget(w, h, multisample = BufferMultisample.SampleCount(8)) {
            colorBuffer(); depthBuffer()
        }
        val resolved = colorBuffer(w, h)
        fun render(time: Double): ColorBuffer {
            drawer.isolatedWithTarget(canvas) {
                ortho(canvas)
                clear(ColorRGBa.BLACK)
                frame(this, time)
            }
            canvas.colorBuffer(0).copyTo(resolved)
            return resolved
        }

        // The organizer's Sketches tab: with SKETCH_PREVIEW the wall at [preview] seconds (or
        // SKETCH_PREVIEW_AT), at half the canvas, to sketch-previews/ under SKETCH_PREVIEW_NAME — the
        // sketch's, or one of its variants' — and quit. Clean, without the concrete wall, so the
        // thumbnail is the drawing.
        if (Env.boolean("SKETCH_PREVIEW")) {
            val at = Env["SKETCH_PREVIEW_AT"]?.toDoubleOrNull() ?: preview
            val file = File("$PREVIEW_DIR/${Env["SKETCH_PREVIEW_NAME"] ?: name}.png")
            file.parentFile.mkdirs()
            val half = renderTarget(w / 2, h / 2) { colorBuffer() }
            val image = render(at)
            drawer.isolatedWithTarget(half) {
                ortho(half)
                image(image, 0.0, 0.0, w / 2.0, h / 2.0)
            }
            half.colorBuffer(0).saveToFile(file, async = false)
            println("saved ${file.path}")
            application.exit()
            return@program
        }

        val stills = Env["COURSE_AT"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }.orEmpty()
        if (stills.isNotEmpty()) {
            File("screenshots/courses").mkdirs()
            stills.forEach { t ->
                val file = File("screenshots/courses/$name-${"%05.1f".format(t)}s.png")
                render(t).saveToFile(file, async = false)
                println("saved ${file.path}")
            }
            application.exit()
            return@program
        }

        if (Env.boolean("COURSE_RECORD")) {
            val fps = Env["COURSE_FPS"]?.toIntOrNull() ?: 30
            val from = Env["COURSE_FROM"]?.toDoubleOrNull() ?: 0.0
            val duration = Env["COURSE_DURATION"]?.toDoubleOrNull() ?: 20.0
            // At COURSE_RECORD_SCALE of the canvas (half unless asked), under the show's concrete wall
            // when COURSE_WALL is on — the picture the studio shows, without its plate. A clip held on
            // a saved view is named after it.
            val scale = (Env["COURSE_RECORD_SCALE"]?.toDoubleOrNull() ?: 0.5).coerceIn(0.1, 1.0)
            val cw = (w * scale).toInt() / 2 * 2
            val ch = (h * scale).toInt() / 2 * 2
            val wall = if (Env["COURSE_WALL"]?.let { it == "true" } ?: true) slideshow.ConcreteWall.load(
                Env["SLIDES_CONCRETE"],
                Env["SLIDES_CONCRETE_MIX"]?.toDoubleOrNull() ?: 1.0,
                Env["SLIDES_CONCRETE_SCALE"]?.toDoubleOrNull() ?: 1.0,
                Env["SLIDES_CONCRETE_FLOOR"]?.toDoubleOrNull() ?: 0.0
            ) else null
            val wallStyle = wall?.style(Vector2(w.toDouble(), h.toDouble()))
            val out: RenderTarget = renderTarget(cw, ch) { colorBuffer() }
            val pixels = ByteBuffer.allocateDirect(cw * ch * 4)
            val suffix = if (CourseControl.fixed != null) "-view${Env["COURSE_VIEW"]}" else ""
            val file = File("video/courses/$name$suffix.mp4")
            val clip = Clip(file, cw, ch, fps)
            val frames = (duration * fps).toInt()
            for (f in 0 until frames) {
                val image = render(from + f.toDouble() / fps)
                drawer.isolatedWithTarget(out) {
                    ortho(out)
                    if (wallStyle != null) shadeStyle = wallStyle
                    image(image, 0.0, 0.0, cw.toDouble(), ch.toDouble())
                }
                out.colorBuffer(0).read(pixels, ColorFormat.RGBa, ColorType.UINT8)
                clip.frame(pixels)
                if (f % (fps * 10) == 0) println("$name: ${f / fps}s of ${duration.toInt()}s")
            }
            println("wrote ${file.path}, ${clip.close()} frames")
            application.exit()
            return@program
        }

        var time = Env["COURSE_FROM"]?.toDoubleOrNull() ?: 0.0
        var paused = false
        keyboard.keyDown.listen {
            when (it.name) {
                "space" -> paused = !paused
                "arrow-right" -> time += 10.0
                "arrow-left" -> time = (time - 10.0).coerceAtLeast(0.0)
                "0" -> time = 0.0
            }
        }
        extend {
            if (!paused) time += 1.0 / 60.0
            drawer.image(render(time), 0.0, 0.0, width.toDouble(), height.toDouble())
        }
    }
}

/** The canvas the course is composed at, for a course that has to know the frame before it draws. */
object CourseCanvas {
    @Volatile var width: Int = 3840
    @Volatile var height: Int = 1080
}

/**
 * What the course studio hands the walls from outside: the cursor, as a share of the window across
 * and down, while it is over the window. Null everywhere else — a still, a recording, a standalone
 * run — so a wall that reads it falls back to its own settled view.
 */
object CourseControl {
    @Volatile var pointer: org.openrndr.math.Vector2? = null
    /** A stored view to hold the camera on, as degrees round (x) and up (y); it wins over the cursor. */
    @Volatile var fixed: org.openrndr.math.Vector2? = null
    /** The angle the camera stood at on the last frame drawn, in the same degrees, for the studio to store. */
    @Volatile var current: org.openrndr.math.Vector2? = null
}

/**
 * Camera angles picked in the studio, kept per course in `COURSE_VIEWS` (`course-views.json`), so a
 * view found with the cursor can be held again after a restart, and used for a still or a clip:
 * `COURSE_VIEW=2` holds the camera on the second stored view of whichever course is run.
 */
object CourseViews {
    private val file get() = File(Env["COURSE_VIEWS"] ?: "course-views.json")
    private val views = LinkedHashMap<String, MutableList<Vector2>>()

    init {
        runCatching {
            if (file.isFile) {
                val root = kotlinx.serialization.json.Json.parseToJsonElement(file.readText()) as kotlinx.serialization.json.JsonObject
                root.forEach { (course, list) ->
                    views[course] = (list as kotlinx.serialization.json.JsonArray).map { v ->
                        val o = v as kotlinx.serialization.json.JsonObject
                        fun n(k: String) = (o[k] as kotlinx.serialization.json.JsonPrimitive).content.toDouble()
                        Vector2(n("yaw"), n("pitch"))
                    }.toMutableList()
                }
            }
        }.onFailure { println("course views: could not read ${file.path} (${it.message})") }
    }

    fun of(course: String): List<Vector2> = views[course].orEmpty()

    fun add(course: String, view: Vector2): Int {
        val list = views.getOrPut(course) { mutableListOf() }
        list += view
        save()
        return list.size - 1
    }

    fun remove(course: String, index: Int) {
        views[course]?.let { if (index in it.indices) { it.removeAt(index); save() } }
    }

    private fun save() {
        val text = views.entries.joinToString(",\n", "{\n", "\n}\n") { (course, list) ->
            "  \"$course\": [" + list.joinToString(", ") {
                "{ \"yaw\": ${"%.1f".format(java.util.Locale.ROOT, it.x)}, \"pitch\": ${"%.1f".format(java.util.Locale.ROOT, it.y)} }"
            } + "]"
        }
        file.writeText(text)
    }

    /** The view `COURSE_VIEW` asks for on [course], counting from 1, if there is one. */
    fun asked(course: String): Vector2? =
        Env["COURSE_VIEW"]?.toIntOrNull()?.let { of(course).getOrNull(it - 1) }
}
