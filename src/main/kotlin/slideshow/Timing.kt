package slideshow

import kotlin.math.pow

/**
 * Time in the slideshow is counted in **frames**, never in seconds.
 *
 * A slide says "this takes 30 frames", not "half a second", and every animation is a
 * pure function of a frame count. That buys three things at once:
 *
 *  - the same click always produces the same pixels, so a still and a recorded frame
 *    and what is on screen are the same image;
 *  - the piece can be scrubbed, paused and stepped, because nothing is integrated over
 *    deltas — ask for frame 412 and you get frame 412;
 *  - recording is safe. `ScreenRecorder` swaps `program.clock` for a frame clock inside
 *    the draw loop, so a sketch that reads a wall clock in a key handler drifts out of
 *    step with its own video (see the note under demo01 in CLAUDE.md). Here the key
 *    handlers change *which step* the deck is on and nothing else; the frame number is
 *    read once per draw, in the draw loop, by [Clock].
 *
 * [FPS] is the rate frame counts are quoted at, not the display's refresh rate. On a
 * 120 Hz screen the clock still advances 60 frames a second, so a 30-frame move takes
 * half a second on any machine. Recording at another rate is equally safe: the recorder
 * makes `seconds` advance by 1/rate per drawn frame, so the clock steps by however many
 * frames that is and the video plays back at the right speed, just at another cadence.
 */
const val FPS = 60

/** [seconds] as a whole number of frames — the unit slides are written in. */
fun frames(seconds: Double): Int = (seconds * FPS).toInt().coerceAtLeast(0)

/** The inverse. Frames are what the deck runs on; seconds are for reading. */
fun seconds(frames: Int): Double = frames.toDouble() / FPS

/**
 * The deck's frame counter, driven from the program clock but advancing in whole frames
 * and only ever forwards.
 *
 * It is a counter rather than a plain `(seconds * FPS)` because it can be held: pausing
 * stops it where it stands and [step] nudges it on one frame at a time, which is how a
 * transition gets inspected. Resuming continues from there rather than snapping to
 * wall-clock time.
 */
class Clock(private val rate: Int = FPS) {

    /** Frames since the show started, not counting time spent paused. */
    var frame: Int = 0
        private set

    var paused: Boolean = false

    private var lastRaw = 0
    private var started = false

    /** Advances to match [now] — the program clock, read **inside the draw loop**. */
    fun advance(now: Double): Int {
        val raw = (now * rate).toInt()
        if (!started) {
            started = true
            lastRaw = raw
        }
        val delta = (raw - lastRaw).coerceAtLeast(0)
        lastRaw = raw
        if (!paused) frame += delta
        return frame
    }

    /** Moves [by] frames by hand, for stepping through a paused show. */
    fun step(by: Int = 1) {
        frame = (frame + by).coerceAtLeast(0)
    }
}

// ------------------------------------------------------------------------------ //

/** How far [elapsed] frames is into a move of [length] frames, unclamped ends removed. */
fun ramp(elapsed: Int, length: Int): Double =
    if (length <= 0) 1.0 else (elapsed.toDouble() / length).coerceIn(0.0, 1.0)

fun easeInOutCubic(t: Double): Double =
    if (t < 0.5) 4.0 * t * t * t else 1.0 - (-2.0 * t + 2.0).pow(3.0) / 2.0

/**
 * The fraction of a click that has really elapsed, given the eased number a slide is handed —
 * the closed-form inverse of [easeInOutCubic], so `linear(stage.on(n))` is click time.
 *
 * A slide only ever sees the eased number, which is right for anything that *travels* and
 * wrong for anything that is *counted* out along the click — removals, arrivals, one thing
 * after another — which on an eased number crowd into the middle and read as a swell.
 */
fun linear(eased: Double): Double {
    val y = eased.coerceIn(0.0, 1.0)
    return if (y < 0.5) Math.cbrt(y / 4.0) else 1.0 - Math.cbrt(2.0 * (1.0 - y)) / 2.0
}

fun smoothstep(t: Double): Double = t.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }

fun mix(a: Double, b: Double, t: Double): Double = a + (b - a) * t
