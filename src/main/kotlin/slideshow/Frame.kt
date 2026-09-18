package slideshow

import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle

/**
 * One frame, one margin — principle 3 of `style-guide/principles.md`.
 *
 * Built from a stage's bounds, it hands back the regions the guide lays type against, so a
 * drawer asks where the title line is rather than stating 0.05 or 0.085 of its own: the
 * [margin], the [titleBaseline], the [foot], the [body] between them, and the four [corners]
 * where facts sit on a wall. Type stays inside the margin; a picture may bleed past it.
 *
 * The debug grid ([GridOverlay]) rules exactly these lines over the wall, so what a drawer lays
 * out against and what the grid shows are one set of numbers.
 */
class Frame(val bounds: Rectangle) {
    val width: Double get() = bounds.width
    val height: Double get() = bounds.height

    /** The edge margin, as the guide gives it: a share of the width. */
    val margin: Double get() = bounds.width * MARGIN

    /** Where a centred title's baseline sits. */
    val titleBaseline: Double get() = bounds.y + bounds.height * TITLE_Y

    /** Where a title ranged left over a paragraph sits. */
    val titleLeft: Vector2 get() = Vector2(bounds.x + margin, bounds.y + bounds.height * TITLE_LEFT_Y)

    /** The foot line: the lowest a baseline goes. */
    val foot: Double get() = bounds.y + bounds.height * FOOT_Y

    /** The area under the title and above the foot, inside the margin. */
    val body: Rectangle
        get() = Rectangle(
            bounds.x + margin, bounds.y + bounds.height * BODY_TOP,
            bounds.width - 2 * margin, bounds.height * (FOOT_Y - BODY_TOP)
        )

    /** The whole frame inside the margin. */
    val inside: Rectangle
        get() = Rectangle(bounds.x + margin, bounds.y + margin, bounds.width - 2 * margin, bounds.height - 2 * margin)

    /** The four corners inside the margin: top left, top right, bottom left, bottom right. */
    val corners: List<Vector2>
        get() = listOf(
            Vector2(inside.x, inside.y), Vector2(inside.x + inside.width, inside.y),
            Vector2(inside.x, inside.y + inside.height), Vector2(inside.x + inside.width, inside.y + inside.height)
        )

    /** The measure a paragraph wraps to, as the guide gives it. */
    val paragraphMeasure: Double get() = bounds.width * PARAGRAPH

    companion object {
        const val MARGIN = 0.02
        const val TITLE_Y = 0.06
        const val TITLE_LEFT_Y = 0.055
        const val BODY_TOP = 0.12
        const val FOOT_Y = 0.94
        const val PARAGRAPH = 0.29
    }
}
