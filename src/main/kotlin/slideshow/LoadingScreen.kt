package slideshow

import org.lwjgl.glfw.GLFW.glfwGetCurrentContext
import org.lwjgl.glfw.GLFW.glfwPollEvents
import org.lwjgl.glfw.GLFW.glfwSwapBuffers
import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.shape.Rectangle
import slideshow.drawers.advanceOf

/**
 * What the wall shows while the show loads, before its first frame: "Loading" and a line filling
 * under it, in the middle of each projector, so the room and the operator can see it is on its way
 * rather than a black wall that might be a fault (asked for on 12 October 2026).
 *
 * The show loads every slide and decodes the sound in the program's setup, which OpenRNDR runs after
 * it has shown the window and cleared it once but before the draw loop has drawn anything — eight
 * seconds and more of a black window. Nothing is drawn there unless it is put on screen by hand, so
 * [show] draws straight into the window and swaps it, and polls the window's events so macOS does
 * not take a show that is loading for one that has hung. It is called between slides as they load,
 * which is what moves the line.
 *
 * [halves] are the two projectors in window points. A film, a still or a bench never has one: what
 * is on the window before the first frame is not in them.
 */
class LoadingScreen(private val program: Program, private val halves: List<Rectangle>, fontPath: String) {

    private val size = halves.minOf { it.height } * 0.045
    private val face: FontImageMap? = runCatching { program.loadFont(fontPath, size) }.getOrNull()
        ?: runCatching { program.loadFont("data/fonts/default.otf", size) }.getOrNull()

    /** Puts the loading screen on the window, the line [done] of the way full, 0 to 1. */
    fun show(done: Double) {
        val drawer = program.drawer
        val window = glfwGetCurrentContext().takeIf { it != 0L } ?: return
        drawer.isolated {
            drawer.defaults()
            drawer.ortho()
            drawer.clear(ColorRGBa.BLACK)
            for (half in halves) {
                val c = half.center
                val bar = half.height * 0.16
                face?.let { f ->
                    drawer.fontMap = f
                    drawer.fill = ColorRGBa.WHITE.shade(0.75)
                    drawer.text(TEXT, c.x - f.advanceOf(TEXT) / 2.0, c.y)
                }
                drawer.stroke = null
                drawer.fill = ColorRGBa.WHITE.opacify(0.2)
                drawer.rectangle(c.x - bar / 2.0, c.y + size * 0.6, bar, 2.0)
                drawer.fill = ColorRGBa.WHITE.shade(0.75)
                drawer.rectangle(c.x - bar / 2.0, c.y + size * 0.6, bar * done.coerceIn(0.0, 1.0), 2.0)
            }
        }
        glfwSwapBuffers(window)
        glfwPollEvents()
    }

    private companion object {
        const val TEXT = "Loading"
    }
}
