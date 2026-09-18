package slideshow

import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle

/**
 * A ruled grid over the whole wall, with the lines every layout stands on named: the debug
 * mode the opening wall's own grid became.
 *
 * The opening scene rules a field over its pane behind the pieces it draws, with small titles
 * at the corners, and it is the one picture in the show whose layout can be read off the wall.
 * This is that grid lifted out and laid over *any* slide, so a title's baseline, a margin, the
 * seam between the projectors and the cell a caption sits in can be checked against the same
 * ruler on every slide — and so a slide may later keep the grid as a layer of its own, since it
 * is now one thing rather than one wall's.
 *
 * **It is drawn on the window, like the debug overlay, never into the canvas.** So it scales with
 * the screen rather than the composition, never lands in a still or a preview, and is there in a
 * filmed run only when it was switched on for one. `g` toggles it, and so does the organizer,
 * beside the concrete.
 *
 * What it rules, in canvas pixels: a cell every [CELL], a heavier line every [MAJOR] cells; the
 * seam between the two projectors; each pane's margin at [MARGIN] of its width; the title line
 * at [TITLE_Y] of the height and the foot at [FOOT_Y], which is where the guide puts them. The
 * labels are the coordinates, so a position can be read off rather than measured.
 */
class GridOverlay(private val font: FontImageMap?) {

    fun draw(drawer: Drawer, shown: Rectangle, canvas: Rectangle, panelWidth: Int?) {
        val k = shown.width / canvas.width
        fun x(cx: Double) = shown.corner.x + cx * k
        fun y(cy: Double) = shown.corner.y + cy * k
        val w = canvas.width
        val h = canvas.height

        drawer.isolated {
            drawer.drawStyle.clip = shown
            drawer.fill = null
            drawer.strokeWeight = 1.0

            // The cells, a heavier line every MAJOR.
            var i = 0
            var cx = 0.0
            while (cx <= w) {
                drawer.stroke = if (i % MAJOR == 0) LINE_MAJOR else LINE
                drawer.lineSegment(x(cx), y(0.0), x(cx), y(h))
                cx += CELL; i++
            }
            i = 0
            var cy = 0.0
            while (cy <= h) {
                drawer.stroke = if (i % MAJOR == 0) LINE_MAJOR else LINE
                drawer.lineSegment(x(0.0), y(cy), x(w), y(cy))
                cy += CELL; i++
            }

            // The panes: the seam, and each pane's margin, title line and foot.
            val panes = if (panelWidth != null && panelWidth > 0 && panelWidth < w) listOf(0.0 to panelWidth.toDouble(), panelWidth.toDouble() to w - panelWidth)
                        else listOf(0.0 to w)
            panes.forEach { (left, width) ->
                if (left > 0.0) {
                    drawer.stroke = SEAM
                    drawer.strokeWeight = 2.0
                    drawer.lineSegment(x(left), y(0.0), x(left), y(h))
                    drawer.strokeWeight = 1.0
                }
                val m = width * MARGIN
                drawer.stroke = GUIDE
                drawer.rectangle(x(left + m), y(m), (width - 2 * m) * k, (h - 2 * m) * k)
                drawer.lineSegment(x(left), y(h * TITLE_Y), x(left + width), y(h * TITLE_Y))
                drawer.lineSegment(x(left), y(h * FOOT_Y), x(left + width), y(h * FOOT_Y))
                drawer.lineSegment(x(left), y(h / 2.0), x(left + width), y(h / 2.0))
                drawer.lineSegment(x(left + width / 2.0), y(0.0), x(left + width / 2.0), y(h))

                font?.let { f ->
                    drawer.fontMap = f
                    drawer.stroke = null
                    drawer.fill = LABEL
                    drawer.text("title  ${"%.3f".format(TITLE_Y)} h", x(left + m) + 4.0, y(h * TITLE_Y) - 4.0)
                    drawer.text("foot  ${"%.2f".format(FOOT_Y)} h", x(left + m) + 4.0, y(h * FOOT_Y) - 4.0)
                    drawer.text("margin  ${"%.2f".format(MARGIN)} w", x(left + m) + 4.0, y(m) + 14.0)
                    drawer.text("pane ${if (left > 0.0) "R" else "L"}  ${width.toInt()} x ${h.toInt()}", x(left + width / 2.0) + 4.0, y(h / 2.0) - 4.0)
                }
            }

            // The coordinates along the top and down the left, one a major cell.
            font?.let { f ->
                drawer.fontMap = f
                drawer.fill = LABEL
                var gx = 0.0
                while (gx <= w) {
                    drawer.text(gx.toInt().toString(), x(gx) + 3.0, y(0.0) + 12.0)
                    gx += CELL * MAJOR
                }
                var gy = CELL * MAJOR
                while (gy <= h) {
                    drawer.text(gy.toInt().toString(), x(0.0) + 3.0, y(gy) - 3.0)
                    gy += CELL * MAJOR
                }
            }
        }
    }

    companion object {
        /** A cell in canvas pixels, and how many cells to a heavier line. 96 is the opening wall's own pitch. */
        const val CELL = 96.0
        const val MAJOR = 4

        /** The lines the guide lays type against: [Frame]'s own, so the grid and the drawers agree. */
        const val MARGIN = Frame.MARGIN
        const val TITLE_Y = Frame.TITLE_Y
        const val FOOT_Y = Frame.FOOT_Y

        val LINE = ColorRGBa(1.0, 1.0, 1.0, 0.13)
        val LINE_MAJOR = ColorRGBa(1.0, 1.0, 1.0, 0.3)
        val GUIDE = ColorRGBa(1.0, 0.85, 0.2, 0.7)
        val SEAM = ColorRGBa(1.0, 0.25, 0.25, 0.85)
        val LABEL = ColorRGBa(1.0, 0.85, 0.2, 0.9)
    }
}
