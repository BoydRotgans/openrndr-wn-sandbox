import org.openrndr.KEY_ARROW_LEFT
import org.openrndr.KEY_ARROW_RIGHT
import org.openrndr.KEY_SPACEBAR
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.isolated
import org.openrndr.extra.composition.ShapeNode
import org.openrndr.extra.composition.findShapes
import org.openrndr.extra.svg.loadSVG
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.shape.Rectangle
import org.openrndr.shape.Shape
import java.io.File
import kotlin.math.max
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * demo02 — a line of objects, getting longer.
 *
 * demo01 with the plain rectangles replaced by the drawings from data/svg, and its grid
 * replaced by [packTrain]: the objects are one strip laid end to end, a fixed margin
 * apart, wrapped across as many lines as fill the frame. An object reaching the end of a
 * line is cut there and continues at the start of the next, so every line but the last is
 * full edge to edge while each object keeps its own proportions and the same margin.
 *
 * Objects arrive in [HIGHLIGHT] and turn to [SOLID] once the next one lands, so the colour
 * marks the head of the line rather than the object.
 *
 *     ./gradlew run -Popenrndr.application=Demo02Kt
 *
 *     →  /  space   next
 *     ←              back
 *     r              reset to one object
 *
 * DEMO02_SHEET picks the sheet; it defaults to the hand-picked subset. DEMO02_WIDTH /
 * _HEIGHT set the canvas (3840x1080) and _WINDOW_SCALE how large it is shown. The same
 * _RECORD / _FPS / _AUTOSTEP / _DURATION keys as demo01 record a clip, at canvas size.
 */
fun main() = application {
    // The piece is made for a wide canvas; the window shows it at DEMO02_WINDOW_SCALE so
    // it fits on a laptop. Nothing here is drawn from pixels, so the two are the same
    // composition at different sizes — only the canvas proportion changes the layout.
    val canvasWidth = Env["DEMO02_WIDTH"]?.toDoubleOrNull() ?: 3840.0
    val canvasHeight = Env["DEMO02_HEIGHT"]?.toDoubleOrNull() ?: 1080.0
    val windowScale = Env["DEMO02_WINDOW_SCALE"]?.toDoubleOrNull() ?: 0.4

    configure {
        width = (canvasWidth * windowScale).toInt()
        height = (canvasHeight * windowScale).toInt()
    }

    program {
        val sheet = File(Env["DEMO02_SHEET"] ?: DEFAULT_SHEET)
        val objects = loadObjectSheet(sheet)
        println("${objects.size} objects from ${sheet.path}")

        val maxBoxes = min(MAX_BOXES, objects.size)

        /** The space the objects divide. */
        fun space(): Rectangle {
            val margin = min(width, height) * MARGIN
            return Rectangle(margin, margin, width - 2 * margin, height - 2 * margin)
        }

        val boxes = mutableListOf<Slot>()
        var count = 1
        var startedAt = 0.0

        /** The line geometry of the layout being animated towards; see [Train]. */
        var carry = Train(emptyList(), space().width, 0.0)

        fun progress(now: Double, index: Int) =
            easeInOutCubic(((now - startedAt - index * STAGGER) / DURATION).coerceIn(0.0, 1.0))

        fun rectOf(now: Double, index: Int) =
            lerp(boxes[index].from, boxes[index].to, progress(now, index))

        /** Re-runs the layout for [newCount] boxes and starts a transition towards it. */
        fun retarget(newCount: Int, space: Rectangle, now: Double, instant: Boolean = false) {
            val train = packTrain(space, (0 until newCount).map { objects[it].aspect })
            val targets = train.boxes
            carry = train
            val current = boxes.indices.map { rectOf(now, it) }

            while (boxes.size < newCount) boxes += Slot(boxes.size)

            boxes.forEachIndexed { index, slot ->
                val target = if (index < newCount) targets[index] else flattened(current[index], space)
                slot.from = if (instant) target else current.getOrElse(index) { flattened(target, space) }
                slot.to = target
                slot.leaving = index >= newCount
                when {
                    // stepping back can make an earlier object the newest again
                    index == newCount - 1 -> slot.settledAt = Double.POSITIVE_INFINITY
                    instant -> slot.settledAt = min(slot.settledAt, now - DURATION)
                    slot.settledAt == Double.POSITIVE_INFINITY -> slot.settledAt = now
                }
            }
            startedAt = if (instant) now - DURATION - boxes.size * STAGGER else now
        }

        var lastSpace = space()
        var applied = 0

        // Only the count is set here; the retarget happens in the draw loop, where
        // `seconds` is the clock the animation is read back with. See demo01.
        keyboard.keyDown.listen { event ->
            when {
                event.key == KEY_ARROW_RIGHT || event.key == KEY_SPACEBAR ->
                    count = (count + 1).coerceAtMost(maxBoxes)

                event.key == KEY_ARROW_LEFT -> count = (count - 1).coerceAtLeast(1)
                event.name == "r" -> count = 1
            }
        }

        if (Env.boolean("DEMO02_RECORD")) {
            extend(ScreenRecorder().apply {
                frameRate = Env["DEMO02_FPS"]?.toIntOrNull() ?: 60
                // so the clip comes out at the canvas size, not the window's
                contentScale = 1.0 / windowScale
                Env["DEMO02_DURATION"]?.toDoubleOrNull()?.let { maximumDuration = it }
            })
        }

        val autoStep = Env["DEMO02_AUTOSTEP"]?.toDoubleOrNull() ?: 0.0

        extend {
            val now = seconds
            val space = space()

            if (autoStep > 0.0) count = (1 + (now / autoStep).toInt()).coerceAtMost(maxBoxes)

            if (space != lastSpace) {
                lastSpace = space
                applied = count
                retarget(count, space, now, instant = true)
            }

            if (count != applied) {
                applied = count
                retarget(count, space, now)
            }

            drawer.clear(BACKGROUND)
            drawer.stroke = null

            boxes.forEachIndexed { index, slot ->
                val rect = rectOf(now, index)
                if (rect.height < 1.0 || rect.width < 1.0) return@forEachIndexed

                drawer.fill = slot.fill(now)
                val drawing = objects[index % objects.size]

                // an object that runs off the end of a line is cut there and drawn again
                // where it continues, one line down
                var part = rect
                var carried = 0
                while (true) {
                    drawer.isolated {
                        drawer.drawStyle.clip = space
                        drawing.drawFitted(drawer, part)
                    }
                    if (part.corner.x + part.width <= space.corner.x + space.width || carried++ >= 2) break
                    part = Rectangle(
                        part.corner.x - carry.lineWidth,
                        part.corner.y + carry.linePitch,
                        part.width,
                        part.height
                    )
                }
            }

            boxes.removeAll { it.leaving && progress(now, it.index) >= 1.0 }
        }
    }
}

// ------------------------------------------------------------------------------ //

/**
 * One drawing cut out of a sheet: the shapes that make it up, in sheet coordinates,
 * together with the box they occupy there.
 */
class SheetObject(val shapes: List<Shape>, val bounds: Rectangle) {
    /** Width over height, the proportion a box has to have to hold it without waste. */
    val aspect = bounds.width / bounds.height

    /**
     * Draws the object as large as it fits inside [rect], centred, without distorting it.
     * Only the drawer's transform changes, so the shapes themselves stay as loaded.
     */
    fun drawFitted(drawer: Drawer, rect: Rectangle) {
        val fit = min(rect.width / bounds.width, rect.height / bounds.height) * (1.0 - PADDING)
        drawer.translate(rect.center)
        drawer.scale(fit, fit)
        drawer.translate(-bounds.center)
        drawer.shapes(shapes)
    }
}

/**
 * Lays [aspects] end to end as one strip, each object a fixed [gap] from the last, and
 * wraps that strip across however many lines fill [space]. Returns a box per object, in
 * order, along with the geometry needed to carry an object over a line end.
 *
 * Treating the objects as one line that is simply getting longer — rather than as rows to
 * be packed — is what lets the space be used completely. An object that reaches the end of
 * a line is not moved down whole and it is not stretched to hide the shortfall: the line
 * is cut and the rest of it continues at the start of the next one. So every line but the
 * last is full edge to edge by construction, every object keeps both its proportions and
 * the same small gap to its neighbours, and the only space left over is the open end of
 * the line still being laid.
 *
 * The number of lines is the fewest the strip will fit on; the object height then follows
 * from those lines having to fill the height exactly. Adding an object lengthens the strip
 * and, when it no longer fits, drops the height a step — everything then slides along the
 * line together rather than being re-arranged.
 */
fun packTrain(space: Rectangle, aspects: List<Double>, gap: Double = OBJECT_GAP): Train {
    if (aspects.isEmpty()) return Train(emptyList(), space.width, 0.0)

    // where each object begins along the strip, measured in object heights
    val starts = DoubleArray(aspects.size)
    var along = 0.0
    for (index in aspects.indices) {
        starts[index] = along
        along += aspects[index] + gap
    }
    val length = along - gap

    // How many lines to wrap onto. On `lines` lines an object can be no taller than the
    // lines leave room for, and no taller than a strip of `lines * width` will carry; the
    // best number of lines is simply the one that lets the objects be biggest. Whichever
    // of the two limits binds is the one that is met exactly: either the lines fill the
    // height, or the strip fills every line edge to edge.
    var lines = 1
    var height = 0.0
    for (candidate in 1..aspects.size) {
        val fits = min(
            space.height / (candidate + (candidate - 1) * gap),
            candidate * space.width / length
        )
        if (fits > height) {
            height = fits
            lines = candidate
        }
    }
    val lineLength = space.width / height
    val used = height * (lines + (lines - 1) * gap)
    val top = space.corner.y + (space.height - used) / 2.0

    val boxes = aspects.indices.map { index ->
        val line = floor(starts[index] / lineLength)
        Rectangle(
            space.corner.x + (starts[index] - line * lineLength) * height,
            top + line * height * (1.0 + gap),
            aspects[index] * height,
            height
        )
    }
    return Train(boxes, space.width, height * (1.0 + gap))
}

/**
 * A strip of boxes, with the step that carries a box past the end of a line: draw it again
 * [lineWidth] to the left and [linePitch] lower and the part that ran off the end lands
 * where it continues.
 */
class Train(val boxes: List<Rectangle>, val lineWidth: Double, val linePitch: Double)

/**
 * Reads a sheet of drawings laid out on a grid and returns them in reading order.
 *
 * The sheet is one flat list of paths with no grouping, so the objects are recovered from
 * the geometry: captions are dropped, then the gaps that run clear across the sheet give
 * the column and row lines, and everything landing in the same cell is one object. That
 * derives the grid from the file rather than assuming one, so a re-export with different
 * spacing still reads correctly.
 */
fun loadObjectSheet(file: File): List<SheetObject> {
    require(file.isFile) { "no shape sheet at ${file.path} — set DEMO02_SHEET in .env" }

    val nodes = loadSVG(file).findShapes()
    check(nodes.isNotEmpty()) { "${file.path} holds no shapes" }

    // The sheet is one flat list of paths — the export keeps no group names — so the
    // captions have to be told apart from the drawings by eye, as it were.
    //
    // Artwork and captions use a colour each, and the caption colour is the group whose
    // shapes are far shorter, being outlined type. That alone is not enough: a cell's
    // first caption line is set in the artwork colour. What holds for every caption is
    // that type sits with type, so a path landing within a couple of line heights above
    // the top of a known caption is a caption too. Drawings clear it by a wide margin.
    val byFill = nodes.groupBy { it.effectiveFill }
    val artwork = if (byFill.size < 2) nodes else {
        val captionFill = byFill.minByOrNull { (_, group) -> group.medianHeight() }!!.key
        val lineHeight = byFill.getValue(captionFill).medianHeight()
        val captionTops = nodes.filter { it.effectiveFill == captionFill }
            .map { it.effectiveShape.bounds.corner.y }
            .sorted()

        nodes.filter { node ->
            if (node.effectiveFill == captionFill) return@filter false
            val bounds = node.effectiveShape.bounds
            val bottom = bounds.corner.y + bounds.height
            val below = captionTops.firstOrNull { it >= bottom }
            below == null || below - bottom > lineHeight * CAPTION_REACH
        }
    }
    check(artwork.isNotEmpty()) { "${file.path} holds captions but no drawings" }

    // Gaps that run clear across the sheet give the column and row lines; everything
    // landing in the same cell is one object. That reads the grid off the file rather
    // than assuming one, so a re-export with different spacing still works.
    val bounds = artwork.map { it.effectiveShape.bounds }
    val columns = gutters(bounds.map { it.corner.x to it.corner.x + it.width })
    val rows = gutters(bounds.map { it.corner.y to it.corner.y + it.height })

    // LinkedHashMap: cells keep the order they first appear in the file, which for an
    // exported sheet is reading order.
    val cells = LinkedHashMap<Pair<Int, Int>, MutableList<ShapeNode>>()
    for (node in artwork) {
        val centre = node.effectiveShape.bounds.center
        val cell = rows.count { it < centre.y } to columns.count { it < centre.x }
        cells.getOrPut(cell) { mutableListOf() } += node
    }

    return cells.values.map { cell ->
        val shapes = cell.map { it.effectiveShape }
        SheetObject(shapes, shapes.map { it.bounds }.union())
    }
}

/**
 * The middle of every gap that no span covers, i.e. the lines a grid can be cut along.
 * Gaps before the first span and after the last one are not cuts, only the ones between.
 */
private fun gutters(spans: List<Pair<Double, Double>>): List<Double> {
    if (spans.isEmpty()) return emptyList()
    val cuts = mutableListOf<Double>()
    var reach = Double.NEGATIVE_INFINITY
    for ((start, end) in spans.sortedBy { it.first }) {
        if (reach > Double.NEGATIVE_INFINITY && start > reach) cuts += (reach + start) / 2.0
        reach = max(reach, end)
    }
    return cuts
}

private fun List<ShapeNode>.medianHeight() =
    map { it.effectiveShape.bounds.height }.sorted()[size / 2]

private fun List<Rectangle>.union(): Rectangle {
    val x0 = minOf { it.corner.x }
    val y0 = minOf { it.corner.y }
    val x1 = maxOf { it.corner.x + it.width }
    val y1 = maxOf { it.corner.y + it.height }
    return Rectangle(x0, y0, x1 - x0, y1 - y0)
}

// ------------------------------------------------------------------------------ //

private const val DEFAULT_SHEET = "data/svg/subset.svg"
private const val MAX_BOXES = 31
/** Frame margin, as a fraction of the shorter side of the window. */
private const val MARGIN = 0.04

/** How much of each box is left empty around its object. */
private const val PADDING = 0.0

/** How close above the type block a path may sit before it counts as type itself. */
private const val CAPTION_REACH = 2.0

/** The margin kept between objects, as a fraction of their height. */
private const val OBJECT_GAP = 0.05

private const val DURATION = 0.6
private const val STAGGER = 0.025

private val BACKGROUND = ColorRGBa.fromHex("0E0E10")

/** The two colours of the piece: an object is highlighted while new, then built in. */
private val HIGHLIGHT = ColorRGBa.fromHex("FF0000")
private val SOLID = ColorRGBa.fromHex("5E00FF")

/**
 * One box in the wall. An object arrives highlighted and turns to the wall's colour once
 * the next one lands on it, so the change of colour is the moment it stops being the new
 * arrival and becomes part of the structure.
 */
private class Slot(val index: Int) {
    var from = Rectangle(0.0, 0.0, 0.0, 0.0)
    var to = Rectangle(0.0, 0.0, 0.0, 0.0)
    var leaving = false

    /** When this object stopped being the newest. Infinite while it still is. */
    var settledAt = Double.POSITIVE_INFINITY

    fun fill(now: Double) =
        HIGHLIGHT.mix(SOLID, easeInOutCubic(((now - settledAt) / DURATION).coerceIn(0.0, 1.0)))
}

private fun flattened(rect: Rectangle, space: Rectangle) =
    Rectangle(rect.corner.x, space.corner.y + space.height, rect.width, 0.0)

private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

private fun lerp(a: Rectangle, b: Rectangle, t: Double) = Rectangle(
    lerp(a.corner.x, b.corner.x, t),
    lerp(a.corner.y, b.corner.y, t),
    lerp(a.width, b.width, t),
    lerp(a.height, b.height, t)
)

private fun easeInOutCubic(t: Double) =
    if (t < 0.5) 4.0 * t * t * t else 1.0 - (-2.0 * t + 2.0).pow(3.0) / 2.0
