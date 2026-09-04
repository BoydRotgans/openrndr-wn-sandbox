import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.renderTarget
import org.openrndr.extra.composition.composition
import org.openrndr.extra.composition.findShapes
import org.openrndr.extra.svg.loadSVG
import java.io.File

/**
 * Renders every exported Figma frame to a png and quits, so a re-export can be judged
 * without stepping through 76 slides by hand.
 *
 *     FIGMA_PAGE=SHEETS ./gradlew run -Popenrndr.application=FigmaStillsKt
 *
 * Unlike FigmaProbe this needs a graphical context: frames carrying raster images make
 * the svg loader allocate colour buffers, which is exactly the 21 frames the headless
 * probe could not read. Rendering is also the only way to see what the loader silently
 * drops — orx-svg warns "Unknown property: fill-rule" on every evenodd path, and whether
 * that matters is a question about pixels, not about the file.
 *
 * FIGMA_STILLS_SCALE sizes the output; 0.5 puts a 3908x1080 frame at 1954x540.
 */
fun main() = application {
    configure { width = 900; height = 260 }

    program {
        val frames = fetchFigmaFrames()
        val scale = Env["FIGMA_STILLS_SCALE"]?.toDoubleOrNull() ?: 0.5
        val outDir = File("screenshots/figma").apply { mkdirs() }

        // Only two frame sizes occur, so a target is reused rather than rebuilt per frame.
        val targets = mutableMapOf<Pair<Int, Int>, RenderTarget>()

        extend {
            for ((index, frame) in frames.withIndex()) {
                val composition = loadSVG(frame.svg)
                val size = composition.bounds.dimensions * scale
                val key = size.x.toInt() to size.y.toInt()
                val rt = targets.getOrPut(key) {
                    renderTarget(key.first, key.second) {
                        colorBuffer(); depthBuffer(DepthFormat.DEPTH24_STENCIL8)
                    }
                }

                drawer.isolatedWithTarget(rt) {
                    drawer.ortho(rt)
                    // magenta, so anything the frame does not paint over is unmistakable
                    drawer.clear(ColorRGBa.fromHex("#FF00FF"))
                    drawer.scale(scale)
                    drawer.composition(composition)
                }

                val out = File(outDir, "%02d-%s.png".format(index, frame.name.replace(Regex("[^A-Za-z0-9._-]+"), "_")))
                rt.colorBuffer(0).saveToFile(out, async = false)
                println("%2d/%d  %-13s %d shapes -> %s".format(index + 1, frames.size, frame.name, composition.findShapes().size, out.path))
            }
            println("\n${frames.size} stills in ${outDir.path}")
            application.exit()
        }
    }
}
