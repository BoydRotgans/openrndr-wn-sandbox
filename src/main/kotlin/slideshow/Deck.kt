package slideshow

import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.Drawer
import org.openrndr.shape.Rectangle

/**
 * The deck: which slide is up, which click it is on, and the handover to the next one.
 *
 * It owns every piece of state in the show, and it is the only thing that does. Slides
 * are pure — they are handed a [Stage] and draw it — and the driver only pumps frames in
 * and reads pictures out. So the entire show is (frame, index, step) and nothing else,
 * which is why it can be jumped about in, stepped backwards, paused and replayed without
 * any slide knowing.
 *
 * Nothing here reads a clock. [tick] is handed the frame number once per draw and every
 * animation is measured against the frame something started on.
 */
class Deck(
    val slides: List<Slide>,
    start: Int = 0,
    /** Where each slide sits in the show, when the deck was read from a file. */
    val outline: Outline = Outline.EMPTY
) {

    init {
        require(slides.isNotEmpty()) { "a deck needs at least one slide" }
    }

    /** The frame the deck was last ticked to. */
    var frame: Int = 0
        private set

    private var current: Playhead = Playhead(start.coerceIn(slides.indices), 0)
    private var leaving: Playhead? = null

    private var transition: Transition = Cut
    private var transitionAt = 0
    private var reversed = false

    // ---------------------------------------------------------------------------- //

    val index: Int get() = current.index
    val slide: Slide get() = current.slide
    val step: Int get() = current.step
    val count: Int get() = slides.size

    /** The slide on its way out while a handover runs; null once one slide has the frame. */
    val leavingSlide: Slide? get() = leaving?.slide

    /** 0..1 across the handover between two slides; 1 when one slide has the frame. */
    val handover: Double get() = ramp(frame - transitionAt, transition.length)

    val transitioning: Boolean get() = leaving != null
    val transitionName: String get() = transition::class.simpleName ?: "transition"

    /** Frames the running handover takes. Going back, this is the transition being undone. */
    val transitionFrames: Int get() = transition.length

    /** +1 while a click is playing forward, -1 while it is playing back, 0 at rest. */
    val direction: Int get() = current.direction

    /** Frames into the click being played, and how many it takes. */
    val moveElapsed: Int get() = (frame - current.changedAt).coerceIn(0, moveFrames)
    val moveFrames: Int get() = current.slide.stepFrames

    /** The click in play — going back, the one being undone rather than the one landed on. */
    val movingStep: Int get() = current.movingStep

    /** Advances the show to [frame]. Called once per draw, from the draw loop. */
    fun tick(frame: Int) {
        this.frame = frame
        if (handover >= 1.0) leaving = null
    }

    // --- navigation. These only move the deck; nothing here draws or reads a clock. --- //

    /** The next click, running on into the next slide once this one is out of clicks. */
    fun next() {
        if (current.step < current.slide.steps - 1) current.goTo(current.step + 1, instant = false)
        else if (current.index < slides.size - 1) show(current.index + 1, 0, reverse = false, cut = false)
    }

    /**
     * The click before. Stepping back off the front of a slide lands on the *last* click
     * of the one before it, fully built — so going back retraces the way you came rather
     * than resetting what you already showed.
     */
    fun back() {
        if (current.step > 0) current.goTo(current.step - 1, instant = false)
        else if (current.index > 0) {
            val previous = current.index - 1
            show(previous, slides[previous].steps - 1, reverse = true, cut = false)
        }
    }

    /** Whole slides, skipping whatever clicks are left. */
    fun nextSlide() {
        if (current.index < slides.size - 1) show(current.index + 1, 0, reverse = false, cut = false)
    }

    fun previousSlide() {
        if (current.index > 0) show(current.index - 1, 0, reverse = true, cut = false)
    }

    /** Jumps straight to a slide. Cuts by default: this is for finding a slide, not showing it. */
    fun goTo(index: Int, step: Int = 0, cut: Boolean = true) {
        val target = index.coerceIn(slides.indices)
        if (target == current.index) {
            current.goTo(step, instant = cut)
            return
        }
        show(target, step, reverse = target < current.index, cut = cut)
    }

    /**
     * Back to the top: the first slide at its first click, its own frame count reset, so
     * the deck is in exactly the state it boots in. A cut rather than a handover — this is
     * starting over, not a move in the show.
     */
    fun home() {
        if (current.index == 0) replay() else goTo(0, cut = true)
    }

    /** Runs the current slide again from its first click and frame zero. */
    fun replay() {
        current = Playhead(current.index, 0)
    }

    private fun show(target: Int, step: Int, reverse: Boolean, cut: Boolean) {
        leaving = current
        transition = when {
            cut -> Cut
            // going back undoes the arrival of the slide being left, rather than
            // playing the arrival of the slide being returned to
            reverse -> current.slide.transition
            else -> slides[target].transition
        }
        reversed = reverse
        transitionAt = frame
        current = Playhead(target, step)
    }

    // --- what the driver draws ------------------------------------------------------ //

    /** A slide and where it stands: everything needed to draw one picture. */
    data class Shot(val slide: Slide, val stage: Stage)

    fun shot(bounds: Rectangle): Shot =
        Shot(current.slide, current.stage(bounds, enter = handover, exit = 0.0))

    /** The slide on its way out, while a handover is running. */
    fun leavingShot(bounds: Rectangle): Shot? =
        leaving?.let { Shot(it.slide, it.stage(bounds, enter = 1.0, exit = handover)) }

    fun composite(drawer: Drawer, from: ColorBuffer, to: ColorBuffer) =
        transition.compose(drawer, from, to, handover, reversed)

    // ---------------------------------------------------------------------------- //

    /**
     * Where the show stands inside one slide. A slide gets a fresh one every time it comes
     * up, so a slide never carries anything over from a previous visit.
     *
     * [position] is the eased step index the whole of [Stage] is built on. It runs from
     * wherever it *currently* is towards the new step, so clicking again mid-move picks up
     * from the frame on screen instead of snapping back.
     */
    private inner class Playhead(val index: Int, step: Int) {
        val slide = slides[index]
        private val startedAt = frame

        var step: Int = step
            private set

        private var from = step.toDouble()
        var changedAt = frame
            private set

        fun goTo(target: Int, instant: Boolean) {
            val clamped = target.coerceIn(0, slide.steps - 1)
            from = if (instant) clamped.toDouble() else position
            step = clamped
            changedAt = frame
        }

        val position: Double
            get() = from + (step - from) * easeInOutCubic(ramp(frame - changedAt, slide.stepFrames))

        /** Going forward this is the click arriving; going back, the one being undone. */
        val movingStep: Int get() = maxOf(step, Math.round(from).toInt())

        /** Which way the click being played is going; 0 once it has landed. */
        val direction: Int
            get() = when {
                frame - changedAt >= slide.stepFrames -> 0
                step > from -> 1
                step < from -> -1
                else -> 0
            }

        fun stage(bounds: Rectangle, enter: Double, exit: Double): Stage {
            val elapsed = (frame - startedAt).coerceAtLeast(0)
            val length = slide.loop
            return Stage(
                bounds = bounds,
                frame = elapsed,
                steps = slide.steps,
                step = step,
                position = position,
                enter = enter,
                exit = exit,
                loop = if (length > 0) (elapsed % length).toDouble() / length else 0.0,
                cycle = if (length > 0) elapsed / length else 0
            )
        }
    }
}
