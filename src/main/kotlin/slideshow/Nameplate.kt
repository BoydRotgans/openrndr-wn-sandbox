package slideshow

import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.shape.Rectangle
import slideshow.drawers.advanceOf

/**
 * The slide's name and its click, in a box on top of the wall — so a filmed run says which
 * slide is which without anyone having to count.
 *
 * **It is drawn into the canvas, not onto the window, and that is the whole difference from
 * [DebugOverlay].** The overlay and the grid are laid on the window on top of the finished
 * frame, precisely so they never land in a still, a preview or a clip; this one has to land in
 * the clip, because a review copy that names its slides is what it is for. So it is composited
 * into the canvas with everything else, at canvas pixels, and scales with the wall rather than
 * with the screen.
 *
 * **The name is the slide's id followed by a letter a click**, `panels-turning-A`,
 * `panels-turning-B` and so on. The id is what the order file, the organizer and the feedback
 * all name a slide by, so a note written against `panels-turning-C` on a review copy lands on
 * exactly the state it was written about. The letter counts from A at the slide's opening state,
 * which is the state a slide arrives on rather than a click anyone made.
 *
 * It is off unless asked for: `SLIDES_NAMEPLATE=true`, or [Settings.nameplate].
 */
/**
 * The size the plate's face is loaded at, in **canvas** pixels.
 *
 * Stated here rather than at the call site because the box is measured against it: loaded at one
 * size and boxed at another, the plate would come out with the type hanging off its end. It is
 * generous — a 3840 canvas watched on a laptop is scaled by about a third, and a label nobody can
 * read at review size is a label that does not exist.
 */
const val NAMEPLATE_SIZE = 46.0

class Nameplate(private val face: FontImageMap?) {

    /**
     * The plate for [id] at [step], in the top left of [bounds].
     *
     * Top left rather than centred, because the wall is two projectors meeting in the middle and
     * a box across that seam is the one place nothing here is allowed to go. It sits over the
     * chapter card, which is the corner a slide never composes into anyway.
     */
    fun draw(drawer: Drawer, bounds: Rectangle, id: String, step: Int) {
        val font = face ?: return
        val text = "$id-${letter(step)}"

        val box = Rectangle(
            bounds.corner.x + INSET, bounds.corner.y + INSET,
            font.advanceOf(text) + 2 * PAD, SIZE + 2 * PAD
        )

        drawer.isolated {
            drawer.stroke = null
            drawer.fill = ColorRGBa.BLACK.opacify(GROUND)
            drawer.rectangle(box)

            drawer.fill = null
            drawer.stroke = ColorRGBa.WHITE.opacify(EDGE)
            drawer.strokeWeight = 1.0
            drawer.rectangle(box)

            drawer.stroke = null
            drawer.fill = ColorRGBa.WHITE
            drawer.fontMap = font
            drawer.text(text, box.corner.x + PAD, box.corner.y + PAD + SIZE * BASELINE)
        }
    }

    private companion object {
        const val SIZE = NAMEPLATE_SIZE

        const val INSET = 30.0
        const val PAD = 18.0
        const val BASELINE = 0.76

        /**
         * The box is very nearly opaque, and it has to be.
         *
         * At 0.72 it was a compromise — enough of the slide's own corner showing through to say
         * what was under it — and on a black wall that reads perfectly. On a *white* one it does
         * not: black at 0.72 over white is a mid grey, and white type on mid grey is the one
         * combination here that cannot be read at review size. Filmed, `shadows-A` came out faint
         * on the light walls, which are exactly the frames hardest to tell apart by eye.
         */
        const val GROUND = 0.92
        const val EDGE = 0.55
    }
}

/**
 * A click's letter: A for the state a slide arrives on, B for the first click, and on.
 *
 * Past Z it doubles — AA, AB — rather than running out or wrapping, because a wrap would give
 * two states of one slide the same name, which is the one thing a label like this may not do.
 * Nothing in the show is near it; the arithmetic costs three lines and cannot be wrong later.
 */
fun letter(step: Int): String {
    var n = step.coerceAtLeast(0)
    val out = StringBuilder()
    while (true) {
        out.insert(0, ('A' + n % 26))
        n = n / 26 - 1
        if (n < 0) break
    }
    return out.toString()
}
