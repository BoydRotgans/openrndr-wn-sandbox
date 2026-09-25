import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.BufferMultisample
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.Drawer
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.renderTarget
import slideshow.Backdrop
import slideshow.FPS
import slideshow.SketchWall
import slideshow.Sketches
import slideshow.Stage
import slideshow.frames

/**
 * One course wall of the sandbox: its key (what the studio, the saved views and the clips call it),
 * its title, its sketch — the file, and so its card on the organizer's Sketches tab — the name it goes
 * by in the show, how long it takes to come up, and how to build it.
 */
class CourseWall(
    val key: String,
    val title: String,
    val sketch: String,
    val name: String,
    /** Seconds until the wall has built itself up: when its preview is taken and how long it settles. */
    val settle: Double,
    val make: Program.() -> (Drawer, Double) -> Unit
)

/**
 * Every course wall, in the order the studio numbers them. The studio and the show both read this
 * list, so a wall added here is a course in the one and a wall the organizer can place in the other.
 */
object CourseWalls {
    val all = listOf(
        CourseWall("course-1-aperitif", "Aperitif · flat, line", "CourseAperitif", "Aperitif", 40.0) { aperitifCourse() },
        CourseWall("course-2-opening", "Opening course · flat, fill", "CourseOpening", "Opening course", 40.0) { openingCourse() },
        CourseWall("course-3-first", "First course · flat, fill + shadow", "CourseFirst", "First course", 40.0) { firstCourse() },
        CourseWall("course-4-second", "Second course · isometric, fill + shadow", "CourseSecond", "Second course", 40.0) { secondCourse() },
        CourseWall("course-5-dessert", "Dessert · isometric, WN colours", "CourseDessert", "Dessert", 40.0) { dessertCourse() },
        CourseWall("course-v2-stack", "v2 · beams, stepped", "CourseStack", "Stack", 10.0) { stackCourse(beams) },
        CourseWall("course-v2-row", "v2 · panels in a row", "CourseRow", "Row", 10.0) { stackCourse(panels) },
        CourseWall("course-v2-climb", "v2 · building upward, for ever", "CourseClimb", "Climb", 60.0) { stackCourse(storeys) },
        CourseWall("course-v3-grid", "v3 · a grid, each turned a step further", "CourseGrid", "Grid", 8.0) { gridCourse() },
        CourseWall("course-v4-flow", "v4 · assembling and taking apart, flat", "CourseFlow", "Flow", 20.0) { flowCourse() },
        CourseWall("course-v4-flow-v2", "v4 flow v2 · on the shadow mosaic's grid, closer", "CourseFlow2", "Flow v2", 20.0) { flowMosaicCourse() },
        CourseWall("course-v5-assemble", "v5 · assembling along a street, in the city", "CourseAssemble", "Assemble", 30.0) { assembleCourse() },
        CourseWall("course-v6-kit", "v6 · the kit: a cube, then a grid", "CourseKit", "Kit", 4.0) { kitCourse() }
    )

    /** The wall a main class runs, for a variant that names another file's. */
    fun byMain(mainClass: String) = all.firstOrNull { "${it.sketch}Kt" == mainClass }
}

/**
 * Every sketch and every variant of it as a wall for the show, titled `Sketch: Grid, door panel` —
 * so its id is `sketch-grid-door-panel` — kept on the shelf of the order file until the organizer's
 * picker puts one in a moment. A wall that is only another's variant (the panels in a row, flow v2)
 * stands under the sketch whose card lists it rather than on its own.
 */
fun courseWallBackdrops(): List<Pair<CourseBackdrop, String>> {
    val variants = CourseWalls.all.associateWith { Sketches.variantsOf(it.sketch) }
    val underAnother = variants.values.flatten().mapNotNull { it.mainClass?.let(CourseWalls::byMain) }.toSet()
    return CourseWalls.all.filter { it !in underAnother }.flatMap { card ->
        listOf(CourseBackdrop(card, card, null) to "Sketch: ${card.name}") +
            variants[card].orEmpty().map { v ->
                val wall = v.mainClass?.let(CourseWalls::byMain) ?: card
                CourseBackdrop(wall, card, v) to "Sketch: ${card.name}, ${v.name}"
            }
    }
}

/**
 * A course wall standing in the show as a backdrop — one of the sandbox's sketches, or a variant of
 * one, on the whole wall — so it can be placed in a moment of the evening from the organizer rather
 * than filmed and copied over.
 *
 * **The wall is the sketch's own code, run as the studio runs it**: built in [load], drawn every frame
 * as a function of the second, which is the slide's frame count over the deck's rate — so it starts
 * from nothing each time it comes up, the way the course walls do. A variant is the sketch's code with
 * its `.env` values laid over the file for the build and every frame (see `Env.with`), and a saved view
 * held the way `COURSE_VIEW` holds it in a clip.
 *
 * **It draws into one multisampled target shared by every course wall**, the size of the wall, and
 * hands the finished picture to the slide: the walls are drawn for 8x multisampling, which the deck's
 * own buffers have not got, and one target does for all of them because only one draws at a time.
 */
class CourseBackdrop(
    private val wall: CourseWall,
    card: CourseWall,
    private val variant: Sketches.Variant?
) : Backdrop(), SketchWall {
    override val name get() = "Sketch"
    override val sketch = if (variant == null) card.sketch else "${card.sketch}--${variant.slug}"
    override val variantName = variant?.name ?: Sketches.defaultOf(card.sketch)
    override val settle get() = frames(wall.settle)
    override val background get() = ColorRGBa.BLACK
    private val values = variant?.values.orEmpty()
    private var frame: ((Drawer, Double) -> Unit)? = null

    override fun load(program: Program) {
        val started = System.currentTimeMillis()
        frame = Env.with(values) { wall.make(program) }
        println("course wall: $sketch built in ${System.currentTimeMillis() - started} ms")
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val draw = frame ?: return
        val w = stage.bounds.width.toInt()
        val h = stage.bounds.height.toInt()
        val (target, resolved) = shared(w, h)
        Env.with(values) {
            // Nothing steers a wall in the show; a variant that holds a saved view holds it.
            CourseControl.pointer = null
            CourseControl.fixed = CourseViews.asked(wall.key)
            drawer.isolatedWithTarget(target) {
                ortho(target)
                clear(ColorRGBa.BLACK)
                draw(this, stage.frame.toDouble() / FPS)
            }
            CourseControl.fixed = null
        }
        target.colorBuffer(0).copyTo(resolved)
        drawer.isolated {
            drawer.shadeStyle = null
            drawer.image(resolved, stage.bounds.x, stage.bounds.y, stage.bounds.width, stage.bounds.height)
        }
    }

    companion object {
        private var target: RenderTarget? = null
        private var resolved: ColorBuffer? = null

        private fun shared(w: Int, h: Int): Pair<RenderTarget, ColorBuffer> {
            val t = target?.takeIf { it.width == w && it.height == h }
                ?: renderTarget(w, h, multisample = BufferMultisample.SampleCount(8)) { colorBuffer(); depthBuffer() }.also {
                    target?.destroy(); target = it
                }
            val r = resolved?.takeIf { it.width == w && it.height == h }
                ?: colorBuffer(w, h).also { resolved?.destroy(); resolved = it }
            return t to r
        }
    }
}
