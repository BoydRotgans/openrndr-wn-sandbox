import org.openrndr.KEY_ARROW_LEFT
import org.openrndr.KEY_ARROW_RIGHT
import org.openrndr.KEY_SPACEBAR
import org.openrndr.application
import org.openrndr.color.ColorHSLa
import org.openrndr.color.ColorRGBa
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.shape.Rectangle
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow

/**
 * demo01 — boxes filling a space.
 *
 * One box fills the frame. Every press of `next` adds a box: the box that was filling
 * the frame moves up into a stack of rows and the new one takes the space that is left,
 * so the frame keeps subdividing. Everything moves to its new place at once, eased.
 *
 *     ./gradlew run -Popenrndr.application=Demo01Kt
 *
 *     →  /  space   next
 *     ←              back
 *     r              reset to one box
 *
 * The layout itself is [packBoxes]: a pure function from (space, number of boxes) to a
 * list of rectangles. Nothing in it is animated — the sketch simply asks it where every
 * box belongs, then interpolates from where the boxes are to where they should be.
 *
 * Set DEMO01_RECORD=true in .env to write the run to video/ at DEMO01_FPS. Recording
 * uses ScreenRecorder's frame clock, so the video advances one frame per drawn frame no
 * matter how slowly the encoder keeps up: the video is smooth, and the window feels like
 * slow motion while it records. That is the recording being right, not the sketch lagging.
 */
fun main() = application {
    configure {
        width = 1000
        height = 720    
    }

    program {
        /** The space the boxes divide. */
        fun space() = Rectangle(MARGIN, MARGIN, width - 2 * MARGIN, height - 2 * MARGIN)

        val boxes = mutableListOf<Box>()
        var count = 1
        var startedAt = 0.0

        /** How far box [index] is into the running transition, eased. */
        fun progress(now: Double, index: Int) =
            easeInOutCubic(((now - startedAt - index * STAGGER) / DURATION).coerceIn(0.0, 1.0))

        /** Where box [index] is on screen right now. */
        fun rectOf(now: Double, index: Int) =
            lerp(boxes[index].from, boxes[index].to, progress(now, index))

        /**
         * Re-runs the layout for [newCount] boxes and starts a transition towards it.
         * Boxes animate from wherever they happen to be, so pressing next again halfway
         * through picks up from the current frame instead of snapping.
         */
        fun retarget(newCount: Int, space: Rectangle, now: Double, instant: Boolean = false) {
            val targets = packBoxes(space, newCount)
            val current = boxes.indices.map { rectOf(now, it) }

            while (boxes.size < newCount) boxes += Box(boxes.size)

            boxes.forEachIndexed { index, box ->
                // boxes past the new count are on their way out; the rest go to a slot
                val target = if (index < newCount) targets[index] else flattened(current[index], space)
                // a box that did not exist yet grows out of the bottom edge
                box.from = if (instant) target else current.getOrElse(index) { flattened(target, space) }
                box.to = target
                box.leaving = index >= newCount
            }
            // an instant retarget backdates the clock so every box reads as finished
            startedAt = if (instant) now - DURATION - boxes.size * STAGGER else now
        }

        var lastSpace = space()
        var applied = 0

        // Only the count is set here. The retarget itself happens in the draw loop
        // below, because that is the one place where `seconds` is the clock the
        // animation is read back with — see the note on ScreenRecorder.
        keyboard.keyDown.listen { event ->
            when {
                event.key == KEY_ARROW_RIGHT || event.key == KEY_SPACEBAR ->
                    count = (count + STEP).coerceAtMost(MAX_BOXES)

                event.key == KEY_ARROW_LEFT -> count = (count - STEP).coerceAtLeast(1)
                event.name == "r" -> count = 1
            }
        }

        // ScreenRecorder swaps program.clock for a frame-index clock while it draws and
        // puts the system clock back afterwards, so `seconds` means one thing inside the
        // draw loop and another outside it. Sampling both mixes two clocks that drift
        // apart as soon as encoding runs slower than realtime, which reads as boxes that
        // stall and then jump. Everything below takes its time from `now`.
        if (Env.boolean("DEMO01_RECORD")) {
            extend(ScreenRecorder().apply {
                frameRate = Env["DEMO01_FPS"]?.toIntOrNull() ?: 60
                Env["DEMO01_DURATION"]?.toDoubleOrNull()?.let { maximumDuration = it }
            })
        }

        // Seconds between automatic steps, for recording a clip hands-off. Unset or 0
        // leaves the stepping to the keys.
        val autoStep = Env["DEMO01_AUTOSTEP"]?.toDoubleOrNull() ?: 0.0

        extend {
            val now = seconds
            val space = space()

            if (autoStep > 0.0) count = (1 + (now / autoStep).toInt()).coerceAtMost(MAX_BOXES)

            // window resizes re-run the layout without animating it
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

            boxes.forEachIndexed { index, box ->
                val rect = rectOf(now, index)
                if (rect.height < 1.0) return@forEachIndexed

                drawer.fill = box.fill
                drawer.rectangle(rect)
            }

            // a box is only dropped once it has finished collapsing
            boxes.removeAll { it.leaving && progress(now, it.index) >= 1.0 }
        }
    }
}

// ------------------------------------------------------------------------------ //

/**
 * Divides [space] between [count] boxes and returns their rectangles in box order, so
 * index 0 is the oldest box and the last entry is the newest one.
 *
 * The newest box gets the band at the bottom; the older ones stack above it in rows of
 * [columns], filling left to right, with a short final row spread over the full width.
 * Each row claims [rowFraction] of the height until the stack reaches [maxStackFraction],
 * after which the rows share that space and get thinner — the bottom band shrinks in
 * steps first and then holds, rather than being squeezed away.
 */
fun packBoxes(
    space: Rectangle,
    count: Int,
    gap: Double = 10.0,
    columns: Int = 2,
    rowFraction: Double = 0.16,
    maxStackFraction: Double = 0.6
): List<Rectangle> {
    if (count <= 0) return emptyList()
    if (count == 1) return listOf(space)

    val stacked = count - 1
    val rows = ceil(stacked.toDouble() / columns).toInt()

    val stackHeight = (space.height - gap) * min(maxStackFraction, rows * rowFraction)
    val rowHeight = (stackHeight - (rows - 1) * gap) / rows

    val rects = ArrayList<Rectangle>(count)
    var placed = 0
    for (row in 0 until rows) {
        val inRow = min(columns, stacked - placed)
        val cellWidth = (space.width - (inRow - 1) * gap) / inRow
        val y = space.corner.y + row * (rowHeight + gap)
        for (column in 0 until inRow) {
            rects += Rectangle(space.corner.x + column * (cellWidth + gap), y, cellWidth, rowHeight)
        }
        placed += inRow
    }

    // the newest box takes everything the stack left over
    val top = space.corner.y + stackHeight + gap
    rects += Rectangle(space.corner.x, top, space.width, space.corner.y + space.height - top)
    return rects
}

// ------------------------------------------------------------------------------ //

/** How many boxes a press of next adds. */
private const val STEP = 1
private const val MAX_BOXES = 31

private const val MARGIN = 28.0

/** Seconds a transition takes, and the head start each box has on the next one. */
private const val DURATION = 0.6
private const val STAGGER = 0.025

private val BACKGROUND = ColorRGBa.fromHex("0E0E10")

/** One box. Its colour is fixed; only where it is going changes. */
private class Box(val index: Int) {
    val fill = ColorHSLa(hueOf(index), 0.68, 0.56).toRGBa()
    var from = Rectangle(0.0, 0.0, 0.0, 0.0)
    var to = Rectangle(0.0, 0.0, 0.0, 0.0)
    var leaving = false
}

/** Golden angle steps, so neighbouring boxes never land on a similar hue. */
private fun hueOf(index: Int) = (index * 137.508) % 360.0

/** [rect] with no height, parked on the bottom edge of [space]: where boxes come and go. */
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
