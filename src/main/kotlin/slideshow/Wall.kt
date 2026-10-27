package slideshow

import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle

/**
 * The size of the wall the show composes at: the canvas, two projectors' halves side by side.
 *
 * Most drawers never ask: they lay out against `stage.bounds`. This is for the ones that have to know the frame
 * before they are handed a stage — a plan laid out in `load`, a field baked at a size, a buffer allocated once —
 * so they read the one number the canvas is made from rather than stating 1080 of their own.
 *
 * **Since 27 October 2026 a half is 1920 by 730**, not 1920 by 1080: the projectors still put out 1920 by 1080,
 * but only the bottom 730 rows land on the screen, so the show composes at 3840 by 730 and the projection lays
 * each half at the foot of its projector with a 350 bar of black above it (see [Projectors]). `SLIDES_WIDTH` and
 * `SLIDES_HEIGHT` set it, read by `Slideshow.kt` first thing, before any drawer that asks is made.
 */
object Wall {
    /** The canvas: both halves. */
    var width = 3840
        private set
    var height = 730
        private set

    /** One projector's half: the pane a slide composes for beside its chapter card. */
    val paneWidth: Int get() = width / 2
    val pane: Vector2 get() = Vector2(paneWidth.toDouble(), height.toDouble())
    val paneBounds: Rectangle get() = Rectangle(0.0, 0.0, paneWidth.toDouble(), height.toDouble())
    val size: Vector2 get() = Vector2(width.toDouble(), height.toDouble())

    /** Take the canvas's size, where one is given. */
    fun use(width: Int?, height: Int?): Vector2 {
        width?.takeIf { it > 0 }?.let { this.width = it }
        height?.takeIf { it > 0 }?.let { this.height = it }
        return size
    }
}
