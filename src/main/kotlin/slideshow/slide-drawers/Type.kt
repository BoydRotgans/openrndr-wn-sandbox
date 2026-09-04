package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.loadFont
import slideshow.Stage

/**
 * What the slides in this folder share: the lettering and the margin they lay out against.
 *
 * The folder is `slide-drawers` and the package is `slideshow.drawers`, which do not match
 * because a package name cannot hold a hyphen. Kotlin does not require them to match, and
 * the folder name is the one that has to read well in a file tree.
 */
object Type {

    private const val FILE = "data/fonts/default.otf"

    lateinit var display: FontImageMap
        private set
    lateinit var caption: FontImageMap
        private set

    /** The chapter panel's own sizes: a small number, a large chapter, a mid subchapter. */
    lateinit var panelNumber: FontImageMap
        private set
    lateinit var panelChapter: FontImageMap
        private set
    lateinit var panelSub: FontImageMap
        private set

    /**
     * Loaded at contentScale 1, which is the canvas's own scale — slides are drawn into a
     * canvas-sized buffer and only then fitted to the window, so a glyph atlas built for
     * the window's retina scale would be sized against the wrong thing.
     */
    fun load(program: Program) {
        if (::display.isInitialized) return
        display = program.loadFont(FILE, 108.0, contentScale = 1.0)
        caption = program.loadFont(FILE, 22.0, contentScale = 1.0)
        panelNumber = program.loadFont(FILE, 32.0, contentScale = 1.0)
        panelChapter = program.loadFont(FILE, 96.0, contentScale = 1.0)
        panelSub = program.loadFont(FILE, 44.0, contentScale = 1.0)
    }
}

/** Every slide here carries its number in the same corner, and nothing else. */
fun Drawer.caption(stage: Stage, ink: ColorRGBa, number: Int) {
    fontMap = Type.caption
    fill = ink.opacify(0.7)
    text("%02d".format(number), MARGIN, stage.height - MARGIN)
}

/** The margin every slide here lays out against. */
const val MARGIN = 120.0
