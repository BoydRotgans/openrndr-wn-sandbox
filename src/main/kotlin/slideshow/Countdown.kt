package slideshow

import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.shape.Rectangle
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * How much of an animation is still to play, as a line bottom left of the wall — one system all over the
 * presentation (12 October 2026). The line is the share left: it starts full and comes down to nothing as
 * the animation plays, over its full length drawn faintly, so it is seen coming down; when the animation is
 * done it is gone. No plate: a thin black keyline under it is all that keeps it readable on a light wall,
 * and it is invisible on a dark one.
 *
 * Two callers, one drawing, so they cannot drift: the course transition draws it into its own wall, so a
 * film carries it, and the driver draws it on the window over every other animation while the wait for the
 * animation is on ([Settings.waitForAnimation]), stepping aside on a slide that draws its own. A refused
 * click swells the line toward a colour and back ([felt]), so the presenter sees the click arrived.
 *
 * **Handed a face, it sets the seconds over the line**, m:ss, with the same black keyline round the
 * figures: the course transition does, since its wait is long enough to want saying in seconds — the
 * speaker is getting ready for a chapter. Every other animation is the line alone.
 *
 * It is laid out in canvas pixels against the [wall] it is handed, at the house margin, on the foot.
 */
class Countdown(private val face: FontImageMap? = null) {

    /**
     * [left] seconds of [total], bottom left of [wall]; [alpha] how present the whole of it is, and [felt]
     * how far a refused click's pulse has swelled toward [pulse], 0 to 1 — see [pulseAt].
     */
    fun draw(drawer: Drawer, wall: Rectangle, left: Double, total: Double,
             alpha: Double = 1.0, felt: Double = 0.0, pulse: ColorRGBa = Palette.BLUE) {
        if (alpha <= 0.0) return
        val x = wall.x + wall.height * 16.0 / 9.0 * MARGIN
        val length = wall.height * LENGTH
        val weight = WEIGHT + SWELL * felt
        val y = wall.y + wall.height * FOOT - weight / 2.0
        val share = (if (total > 0.0) left / total else 0.0).coerceIn(0.0, 1.0)
        drawer.isolated {
            drawer.stroke = null
            drawer.fill = ColorRGBa.BLACK.opacify(alpha)
            drawer.rectangle(x - KEYLINE, y - KEYLINE, length + 2 * KEYLINE, weight + 2 * KEYLINE)
            drawer.fill = ColorRGBa.WHITE.opacify(TRACK * alpha)
            drawer.rectangle(x, y, length, weight)
            if (share > 0.0) {
                drawer.fill = ColorRGBa.WHITE.mix(pulse, felt).opacify(alpha)
                drawer.rectangle(x, y, length * share, weight)
            }
            face?.let { f ->
                val whole = kotlin.math.round(left).toInt().coerceAtLeast(0)
                val text = "%d:%02d".format(whole / 60, whole % 60)
                // On the line, a gap above its keyline; ranged with it on the margin.
                val baseline = y - KEYLINE - GAP
                drawer.fontMap = f
                // The keyline: the figures in black a hair out in every direction, then white over them.
                drawer.fill = ColorRGBa.BLACK.opacify(alpha)
                for (dx in -1..1) for (dy in -1..1) if (dx != 0 || dy != 0)
                    drawer.text(text, x + dx * KEYLINE, baseline + dy * KEYLINE)
                drawer.fill = ColorRGBa.WHITE.opacify(alpha)
                drawer.text(text, x, baseline)
            }
        }
    }

    /**
     * A dot where the line starts, [felt] 0 to 1 how far its pulse has swelled — see [pulseAt]: a click
     * received during an animation too short to count down ([SHORTEST]), so the presenter still sees it
     * arrived (12 October 2026). The line's own keyline round it, so it reads on a light wall too.
     */
    fun drawDot(drawer: Drawer, wall: Rectangle, felt: Double, colour: ColorRGBa = Palette.BLUE) {
        if (felt <= 0.0) return
        val x = wall.x + wall.height * 16.0 / 9.0 * MARGIN + DOT
        val y = wall.y + wall.height * FOOT
        drawer.isolated {
            drawer.stroke = null
            drawer.fill = ColorRGBa.BLACK.opacify(felt)
            drawer.circle(x, y, DOT + KEYLINE)
            drawer.fill = colour.opacify(felt)
            drawer.circle(x, y, DOT)
        }
    }

    companion object {
        /**
         * Whether the countdown line is off the wall: set by the driver each frame while the speaker's autocue is
         * up, which counts every wait down itself (15 October 2026), and read by a slide that draws its own line,
         * the course transition. Never set on a film, a still or a bench, where there is no autocue.
         */
        @Volatile var hidden: Boolean = false
        /** The dot's radius in canvas pixels: a click received during an animation too short for the line. */
        const val DOT = 9.0
        /** The size the figures' face is loaded at: a slide's title on a 1080 pane. */
        const val SIZE = Scale.TITLE * 1080.0
        /** From the top of the line's keyline to the figures' baseline, in canvas pixels. */
        const val GAP = 14.0
        /** The house margin and foot, as `Frame` states them for a pane: where the line starts and stands. */
        const val MARGIN = 0.02
        const val FOOT = 0.94
        /** The line's full length as a share of the wall's height, and its weight in canvas pixels. */
        const val LENGTH = 0.16
        const val WEIGHT = 3.0
        /** How much heavier a refused click's pulse makes the line at its height, in canvas pixels. */
        const val SWELL = 5.0
        /** How strongly the full length shows under the line: the 100%, faintly. */
        const val TRACK = 0.3
        /** The black edge round the line, in canvas pixels, so it reads on a light wall. */
        const val KEYLINE = 2.0
        /**
         * Seconds an animation has to run, all told, for the driver to count it down: a shorter one is over
         * before a line could be read, and a line at every short click would be noise (12 October 2026).
         */
        const val SHORTEST = 2.0
        /** Seconds the empty track takes to go once the animation is done. */
        const val FADE = 0.25
        /**
         * Seconds a refused click's pulse takes, up and down: the course transition's default. The transition
         * reads its own from `SLIDES_COURSE_TRANSITION_PULSE_LENGTH`; the driver's takes this and [Palette.BLUE].
         */
        const val PULSE_LENGTH = 0.9

        /**
         * How far a refused click's pulse has swelled, [since] seconds after it over [length]: up and back
         * down, soft at both ends (sin squared), and 0 outside it.
         */
        fun pulseAt(since: Double, length: Double): Double =
            (since / length).takeIf { it in 0.0..1.0 }?.let { sin(PI * it).pow(2) } ?: 0.0
    }
}
