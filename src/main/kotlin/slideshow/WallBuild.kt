package slideshow

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.Drawer

/**
 * How a backdrop takes the stage: every backdrop that [Slide.buildsIn], arrived at going forward,
 * comes up over the same [Settings.backdropBuild] seconds rather than being simply there — asked for
 * on 28 September, so each wall of the evening takes the room slowly and all of them alike.
 *
 * **The engine owns the when, the show owns the how.** The driver draws the arriving wall into a
 * buffer of its own every frame, still moving, and hands that picture here with how far the build
 * has got, 0 to 1; what the build looks like is the show's to say, and `Slideshow.kt` hands one to the
 * show with `wallBuild(...)` — [SweepWallBuild], one wide soft front uncovering the wall.
 *
 * It runs only on the live wall and a filmed run of it: a backdrop stepped back into lands built, as
 * every slide does going back, and stills, previews and exports draw the wall itself.
 */
interface WallBuild {
    /**
     * What the wall is uncovered from: black, the house ground the concrete overlay lifts to the wall's
     * grey, whatever the wall's own [Slide.background]. A wall that states none takes the default white,
     * and a light wall's paper is white too, so building from the background faded those in from a white
     * room.
     */
    val ground: ColorRGBa get() = ColorRGBa.BLACK

    /** Everything the build needs, before the first frame: the wall is [width] by [height]. */
    fun load(program: Program, width: Int, height: Int)

    /** [picture] — the wall, [width] by [height] — drawn as far as [progress] of its build, 0 to 1. */
    fun draw(drawer: Drawer, picture: ColorBuffer, width: Double, height: Double, progress: Double)
}
