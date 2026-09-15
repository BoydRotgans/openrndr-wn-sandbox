// ============================================================================ //
//  No `package` declaration: it stands on loadObjectSheet and packTrain, in the
//  default package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.isolated
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.frames
import slideshow.smoothstep
import java.io.File
import kotlin.random.Random

/**
 * The catalogue's silhouettes, every one once, packed dense across the pane at one height in
 * the house red — and, on the click, dealt out again in another order.
 *
 * **The layout is demo02's strip**: `packTrain` lays the aspects end to end and wraps the strip
 * across as many lines as fill the pane, so every line but the last is full edge to edge and
 * every piece keeps its proportions. A piece that reaches the end of a line is cut and continues
 * at the start of the next, which the drawer does by drawing it twice — once where it is and
 * once a line width left and a line down — clipped to the field.
 *
 * **The reshuffle is two trains and a lerp.** The second state is the same strip laid in a
 * seeded permutation; every piece has a box in each, and the click moves it from the one to the
 * other on the deck's own ease. The two trains are worked out once, at load.
 */
class HundredElements(
    private val sheet: File,
    private val boldPath: String = "data/fonts/default.otf",
    private val ink: ColorRGBa = ColorRGBa.fromHex("FF0000"),
    private val seed: Int = 3,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val stepFrames: Int = frames(1.4),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val name = "Hundred"
    override val steps get() = 2
    override val settle get() = pieces.size * ARRIVE_STEP / 2 + frames(ARRIVE)
    override fun stepName(step: Int): String? = if (step == 1) "reshuffle" else null

    private var pieces: List<SheetObject> = emptyList()
    private var order: List<Int> = emptyList()
    private var first: Train? = null
    private var second: Train? = null
    private var field = Rectangle(0.0, 0.0, 1.0, 1.0)

    override fun load(program: Program) {
        if (!sheet.isFile) { println("hundred elements: no sheet at ${sheet.path}"); return }
        pieces = loadObjectSheet(sheet)
        order = pieces.indices.shuffled(Random(seed))
        println("hundred elements: ${pieces.size} pieces off ${sheet.path}")
    }

    /** The two trains for a field of this size, laid the first time the field is seen. */
    private fun trains(area: Rectangle): Pair<Train, Train> {
        if (area != field || first == null) {
            field = area
            first = packTrain(area, pieces.map { it.aspect }, GAP)
            second = packTrain(area, order.map { pieces[it].aspect }, GAP)
        }
        return first!! to second!!
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        if (pieces.isEmpty()) return
        val w = stage.width
        val h = stage.height
        val area = Rectangle(w * MARGIN, h * MARGIN_Y, w * (1.0 - 2.0 * MARGIN), h * (1.0 - 2.0 * MARGIN_Y))
        val (a, b) = trains(area)
        val t = stage.on(1)
        val opening = stage.step == 0 && stage.position < 1e-6

        // Where each piece's box is in the second train: the position its index was dealt to.
        val dealt = IntArray(pieces.size)
        order.forEachIndexed { at, piece -> dealt[piece] = at }

        drawer.stroke = null
        drawer.drawStyle.clip = area
        pieces.forEachIndexed { i, piece ->
            val from = a.boxes[i]
            val to = b.boxes[dealt[i]]
            val box = Rectangle(
                from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t,
                from.width + (to.width - from.width) * t, from.height + (to.height - from.height) * t
            )
            val shown = if (opening) smoothstep(stage.since(i * ARRIVE_STEP / 2, frames(ARRIVE))) else 1.0
            if (shown <= 0.0) return@forEachIndexed
            drawer.fill = ink.opacify(shown)
            drawer.isolated { piece.drawFitted(drawer, box) }
            // The part that ran off the end of its line lands at the start of the next.
            if (box.x + box.width > area.x + area.width) {
                val pitch = a.linePitch + (b.linePitch - a.linePitch) * t
                drawer.isolated { piece.drawFitted(drawer, box.movedBy(Vector2(-area.width, pitch))) }
            }
        }
        drawer.drawStyle.clip = null
    }

    private companion object {
        const val MARGIN = 0.02
        const val MARGIN_Y = 0.04
        const val GAP = 0.06
        const val ARRIVE_STEP = 1
        const val ARRIVE = 0.4
    }
}
