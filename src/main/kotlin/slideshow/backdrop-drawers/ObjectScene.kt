// ============================================================================ //
//  No `package` declaration, deliberately, and for the same reason ObjectChapterPanel
//  has none: the scene is built out of the catalogue, and loadObjectSheet and
//  SheetObject live in the default package, which Kotlin cannot import into a named
//  one. The file still belongs in backdrop-drawers/, which is a folder rather than
//  a package — a backdrop that stands on nothing in the default package can be
//  `package slideshow.backdrops` like any other drawer.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.isolated
import org.openrndr.shape.Rectangle
import slideshow.Backdrop
import slideshow.Cut
import slideshow.Stage
import slideshow.Transition
import slideshow.easeInOutCubic
import slideshow.frames
import java.io.File
import kotlin.math.min

/**
 * The plain backdrop: catalogue elements standing large across the wall, each in one of the
 * house colours, and then holding.
 *
 * **This is the kind rather than the occasion.** It is any wall that wants a row of
 * components on it — the draaiboek's Uitloop is one, and the courses between the talk and
 * the exit will be others — so it stays deliberately simple and nothing tuned for one
 * particular moment belongs in it. The opening wall used to be one of these and is now
 * [OpeningScene], a drawer of its own: it is up for the better part of an hour while the
 * room fills, and it is the first thing anyone sees, so it grew an arrangement this class
 * should not have to carry — it *draws* the catalogue rather than standing it. The two now
 * share no code but the sheet loader, which is the point of the split: work on one cannot
 * land on the other.
 *
 * **Every element is one height**, laid on one line a fixed gap apart and centred — see
 * [standingRow] for why that is the rule and what binds it.
 *
 * **The colours are handed out along the row and come round again**, so the same scene with
 * the palette reversed is the closing one — which is exactly how the draaiboek draws the
 * exit against the arrival.
 *
 * The elements rise into place from below the wall, one [beat] apart in row order, off the
 * backdrop's own frame count — so replaying it or jumping into it plays the arrival again
 * and a filmed run is a watched one. After that it stands: a backdrop is up for a long time,
 * so nothing on it is small and nothing on it moves once the room has settled.
 */
class ObjectScene(
    override val name: String,
    /** The sheet the elements come off — `data/svg/subset.svg`, read by [loadObjectSheet]. */
    private val sheet: File,
    /** Which elements, by index into the sheet in reading order, left to right on the wall. */
    private val objects: List<Int>,
    /** A colour per element, in row order, coming round again when the row is longer. */
    private val palette: List<ColorRGBa>,
    /** The ground. */
    private val paper: ColorRGBa = ColorRGBa.fromHex("E8E8E8"),
    /** How much of the wall's height an element stands, before the width binds. */
    private val height: Double = 0.6,
    /** The gap between neighbours, in element heights. */
    private val gap: Double = 0.16,
    /** Clear wall either side of the row, as a fraction of its width. */
    private val margin: Double = 0.08,
    /** Seconds between one element's arrival and the next. */
    private val beat: Double = 0.4,
    /** Seconds one element takes to rise into place. */
    private val rise: Double = 1.1,
    /**
     * How the scene arrives. A cut, like the rest of the deck; a [slideshow.Fade] here is
     * composed on the whole wall, from the slide beside its card into the scene.
     */
    override val transition: Transition = Cut
) : Backdrop() {

    init {
        require(palette.isNotEmpty()) { "an ObjectScene needs at least one colour" }
    }

    override val background: ColorRGBa get() = paper

    private var elements: List<SheetObject> = emptyList()

    override fun load(program: Program) {
        if (!sheet.isFile) {
            println("no sheet at ${sheet.path} — \"$name\" stands empty; set SLIDES_BACKDROP_SHEET in .env")
            return
        }
        val all = loadObjectSheet(sheet)
        elements = objects.map { all[it.coerceIn(all.indices)] }
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val boxes = standingRow(stage.bounds, elements.map { it.aspect }, height, gap, margin)

        elements.forEachIndexed { i, element ->
            val box = boxes[i]
            val arrived = easeInOutCubic(stage.since(frames(i * beat), frames(rise)))

            drawer.isolated {
                fill = palette[i % palette.size]
                stroke = null

                // from below the wall's edge up into its place
                translate(0.0, (stage.height - box.y) * (1.0 - arrived))

                // the element's own box, in sheet units, fitted to the box the row gave it
                val fit = box.height / element.bounds.height
                translate(box.center)
                scale(fit, fit)
                translate(-element.bounds.center)
                shapes(element.shapes)
            }
        }
    }
}

// ------------------------------------------------------------------------------ //

/**
 * Where a row of elements stands on a wall: one height for all of them, a fixed gap apart,
 * the row centred.
 *
 * **A pure function of the wall and the shapes** — no time in it, no state. It is
 * `packBoxes` and `stateAt` again: hand it a space and a list of proportions and it hands
 * back a box each, so a scene that changes its picks or its wall keeps composing for free.
 *
 * It sits beside its drawer rather than in a file of its own, the way `stackRows` sits in
 * `StackUp.kt`. [OpeningScene] draws rather than stands and wants none of it; when a second
 * backdrop does want a row — the draaiboek's courses will — it is one move to lift out.
 *
 * Every element is **one height**, so the row reads as a set of components at one scale
 * rather than as a picture that happens to be made of them; they keep their own
 * proportions, so a doorway stays narrower than a slab. The height is [height] of the
 * wall's unless the strip would then overrun the margins, in which case the width binds and
 * every element comes down together — whichever limit binds is met exactly, so a row is as
 * large as its own content allows rather than as large as a number says.
 *
 * @param aspects width over height for each element, in the order they stand.
 * @param height how much of the wall's height an element takes, before the width binds.
 * @param gap between neighbours, in element heights.
 * @param margin clear wall either side of the row, as a fraction of its width.
 */
fun standingRow(
    wall: Rectangle,
    aspects: List<Double>,
    height: Double,
    gap: Double,
    margin: Double
): List<Rectangle> {
    if (aspects.isEmpty()) return emptyList()

    // the strip's length in element heights, gaps included
    val length = aspects.sum() + gap * (aspects.size - 1)

    // as tall as asked for, unless the strip would then overrun the margins
    val h = min(wall.height * height, wall.width * (1.0 - 2.0 * margin) / length)

    var x = wall.center.x - length * h / 2.0
    val y = wall.center.y - h / 2.0
    return aspects.map { aspect ->
        val box = Rectangle(x, y, aspect * h, h)
        x += (aspect + gap) * h
        box
    }
}
