package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.draw.parameter
import org.openrndr.draw.shadeStyle
import org.openrndr.extra.meshgenerators.boxMesh
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.frames
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * What one slab carries. Everything is optional, so a blank block leaves a slab empty and
 * is how you put a gap in the train.
 */
data class SwivelBlock(
    /** The line the slab is for, set large and wrapped to the face. */
    val text: String = "",
    /** A smaller line, sitting low on the face. */
    val note: String? = null,
    /** Items set small and ranged left, one to a line, instead of [text]. */
    val items: List<String> = emptyList(),
    /** This slab's front colour, where it differs from the train's. */
    val front: ColorRGBa? = null,
    /** This slab's side colour, where it differs from the train's. */
    val side: ColorRGBa? = null,
    /** Whether a click rests on this slab. One that does not is carried past within a click. */
    val stop: Boolean = true
) {
    val isEmpty: Boolean get() = text.isBlank() && note.isNullOrBlank() && items.isEmpty()
}

/**
 * A train of slabs travelling past the frame, swinging and stepping in depth, each
 * carrying a line of its own. `demos/Swivel02.kt` with something to say.
 *
 * The copy is [blocks] and nothing else: the slabs take them in turn and repeat, so a
 * deck can stand up another train of the same thing with a different list and change
 * nothing here. A slab is 220 units across, so a line that does not fit is wrapped to the
 * face rather than run off it.
 *
 * **The type is one size across the whole train**, not fitted to each slab. Fitting would
 * set "19 bedrijven" half again as large as "950 medewerkers" purely because it is
 * shorter, and a row of figures that are all the same kind of thing has to look like it —
 * so the size is stated and only the wrapping varies.
 *
 * **The loop closes for any number of blocks.** Travel, swing and the four-slab pattern of
 * turns and depths all have to come back together, and they only do if the distance
 * travelled in a turn is a whole number of each: [cycle] is the smallest that is, and the
 * turn is that many slab-widths long. Five blocks against a four-slab swing means twenty,
 * which at [pace] a slab is a long turn — but a seam that never shows is the point, and
 * nobody watches a whole one.
 *
 * **[clicked] makes it a figure a click instead of a train.** The slabs stand still with one
 * of them centred and close up, facing the room; a click carries the train one slab along so
 * the next block comes to the middle, and the slabs either side turn away and step back by how
 * far off centre they are. No loop, a state a block — so the same drawer that runs a train under
 * a chapter can read out the company's figures one at a time, and the figures slides of two
 * chapters became one slide this way on 16 September.
 *
 * **A slab's turn and depth are a sine of where it stands, never a clamped ramp**, and getting
 * that wrong is what made the clicked version read as broken. It was
 * `off = (i + travel).coerceIn(-1, 1)` with the depth on `abs(off)`: every slab more than one
 * place off centre sat at exactly the same extreme angle, so a slab entering the frame stood
 * dead still until it crossed the clamp and then started moving — and the depth had a corner in
 * it at the middle, so a slab passing the centre reversed rather than eased through. The sketch
 * this came from has neither: its `TURNS` and `DEPTHS` are `-45·cos(iπ/2)` and `90·sin(iπ/2)`
 * read off a four-slab pattern, one smooth wave down the train. The clicked train is that same
 * wave with its zero anchored on whichever slab is centred — every slab moving at every moment,
 * the middle one square to the room and nearest the eye — which is the whole of what "seamless"
 * asked for. They are drawn furthest first, so the one being read is the one on top.
 */
class Swivel02Slide(
    private val blocks: List<SwivelBlock> = emptyList(),
    /** A block a click, centred and close up, rather than a looping train. */
    private val clicked: Boolean = false,
    /** Faces turned towards the front — the ones the copy is set on. */
    private val front: ColorRGBa = ColorRGBa.fromHex("ED1C24"),
    /** Faces turned aside, so a slab has an edge without a line drawn on it. */
    private val side: ColorRGBa = ColorRGBa.fromHex("3D5AE0"),
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    /** World units across the pane. The sketch framed this scene 1440 wide. */
    private val across: Double = 1440.0,
    /** Seconds one slab-width of travel takes, which is also one swing. */
    private val pace: Double = 10.0,
    private val fontPath: String = "data/fonts/default.otf",
    /** The cue as the train arrives. Stated in `Slideshow.kt`. */
    override val sound: Sound? = null
) : Slide() {
    override val name = "Swivel02"

    /** Slab-widths in a turn: the least that closes the copy, the turns and the depths at once. */
    private val cycle = lcm(TURNS.size, blocks.size.coerceAtLeast(1))

    override val loop = if (clicked) 0 else frames(pace * cycle)
    /**
     * The slabs a click rests on, by index in [blocks]. A slab left out still stands in the
     * train; the click either side of it simply travels the extra slab-width.
     */
    private val stops = blocks.indices.filter { blocks[it].stop }.ifEmpty { listOf(0) }

    /** Which slab the train is centred on at a continuous step, read between the stops. */
    private fun centred(position: Double): Double {
        val last = stops.size - 1
        val k = floor(position).toInt().coerceIn(0, last)
        if (k == last) return stops[last] + (position - last)
        return stops[k] + (stops[k + 1] - stops[k]) * (position - k)
    }

    override val steps get() = if (clicked) stops.size else 1
    override val stepFrames = frames(if (clicked) 1.0 else 0.45)
    override fun stepName(step: Int) = if (clicked) blocks.getOrNull(stops.getOrElse(step) { -1 })?.let { it.text.ifBlank { it.items.firstOrNull() ?: "" } } else null

    private lateinit var slab: VertexBuffer
    private lateinit var face: FontImageMap

    override fun load(program: Program) {
        slab = boxMesh(WIDTH, HEIGHT, DEPTH)
        face = program.loadFont(fontPath, ATLAS, TYPE_CHARACTERS, contentScale = 1.0)
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val halfWidth = across / 2.0
        val halfHeight = halfWidth * stage.height / stage.width
        drawer.ortho(-halfWidth, halfWidth, -halfHeight, halfHeight, -1000.0, 1000.0)
        drawer.lookAt(EYE, Vector3.ZERO)

        // One swing to a slab-width, which is what lets any cycle close: at the end of a
        // turn the train has moved a whole number of slabs and the swing is back where it
        // started, so the last frame is the frame before the first.
        // Clicked, the train stands at the block the deck is on: slab i sits at (i - position)
        // slab-widths, so block p is centred at position p and the next comes in from the side.
        val travel = if (clicked) -centred(stage.position) else stage.loop * cycle
        val swing = cos(travel * 2.0 * PI)

        val slabStyle = shadeStyle {
            fragmentTransform = """
                if (va_normal.z < va_normal.x && va_normal.z < va_normal.y) {
                    x_fill = p_front;
                } else {
                    x_fill = p_side;
                }
            """.trimIndent()
            parameter("front", front)
            parameter("side", side)
        }

        // The slabs are drawn around wherever the train has got to, not from a fixed
        // range of indices. A fixed range only survives a short turn: with five blocks the
        // cycle is twenty slab-widths, and by the end of it a fixed -10..10 has travelled
        // entirely off the right of the frame and left an empty stage behind it.
        val reach = ((halfWidth + WIDTH) / SPAN).toInt() + 2
        val from = floor(-travel).toInt() - reach
        val to = ceil(-travel).toInt() + reach

        // Clicked, the slab nearest the middle stands nearest the eye, so the train is painted
        // from the back forwards: furthest off centre first, the one being read last. Drawn in
        // index order the slab to its right would paint over it.
        val order = if (clicked) (from..to).sortedByDescending { abs(it + travel) } else (from..to).toList()

        for (i in order) drawer.isolated {
            val turn: Double
            val depth: Double
            if (clicked) {
                // How far off centre, in slabs, and the sketch's own wave read off it: the
                // centred slab is square to the room and at the front, and every slab either
                // side turns and steps back along one continuous sine. No clamp, no corner.
                val off = i + travel
                turn = -TURN_AWAY * sin(off * PI / 2.0)
                depth = STEP_BACK * (1.0 - cos(off * PI / 2.0))
            } else {
                turn = swing * TURNS[i.mod(TURNS.size)]
                depth = swing * DEPTHS[i.mod(DEPTHS.size)]
            }
            drawer.translate((i + travel) * SPAN, 0.0, depth)
            drawer.rotate(Vector3.UNIT_Y, turn)

            // a slab may carry its own colours, so one train can hold two runs apart
            val block = blocks.getOrNull(i.mod(blocks.size.coerceAtLeast(1)))
            slabStyle.parameter("front", block?.front ?: front)
            slabStyle.parameter("side", block?.side ?: side)
            drawer.fill = front
            drawer.stroke = null
            drawer.shadeStyle = slabStyle
            drawer.vertexBuffer(slab, DrawPrimitive.TRIANGLES)

            // the shade style paints whatever comes next by facing, type included
            drawer.shadeStyle = null
            if (blocks.isNotEmpty()) copy(drawer, blocks[i.mod(blocks.size)])
        }
    }

    /**
     * Sets one block on the face that points at the camera.
     *
     * Two flips before anything is drawn, and both are needed. The scene is under an ortho
     * with +Y up while type is laid out +Y down, so Y has to turn or every line is upside
     * down. And the camera stands at negative Z looking back, which puts local +X to the
     * *left* of the screen — the same reason Swivel01's rightmost panel appears on the
     * left — so X has to turn too or the words come out back to front. After both, the
     * face is an ordinary little canvas with its origin in the middle.
     */
    private fun copy(drawer: Drawer, block: SwivelBlock) {
        if (block.isEmpty) return

        drawer.fill = ink
        drawer.isolated {
            drawer.translate(0.0, 0.0, -DEPTH / 2.0 - 0.5)
            drawer.scale(-1.0, -1.0, 1.0)
            // The camera looks down on the train, so a face is foreshortened to cos(pitch) of
            // its height and type set square on it comes out squat and wide. Set taller by the
            // same factor, it reads at its own proportions on screen.
            drawer.scale(1.0, UPRIGHT, 1.0)

            if (block.items.isNotEmpty()) items(drawer, block.items)

            // The headline and its note are one stack, centred on the face with one gap
            // between them, so a long headline pushes the note down rather than into it.
            val headline = block.text.takeIf { it.isNotBlank() }?.let { fitted(it, HEADLINE) }
                ?.let { (lines, scale) -> TypeBlock(lines, scale, ATLAS, LEADING, face) }
            val note = block.note?.takeIf { it.isNotBlank() }?.let { fitted(it, NOTE) }
                ?.let { (lines, scale) -> TypeBlock(lines, scale, ATLAS, LEADING, face) }
            val gap = if (headline != null && note != null) NOTE_GAP else 0.0
            val total = (headline?.height ?: 0.0) + gap + (note?.height ?: 0.0)
            var y = -total / 2.0
            headline?.let { it.draw(drawer, Vector2(0.0, y + it.height / 2.0)); y += it.height + gap }
            note?.let { it.draw(drawer, Vector2(0.0, y + it.height / 2.0)) }
        }
    }

    /**
     * [text] wrapped to the face, and set smaller than [size] if it still will not fit.
     *
     * Wrapping alone is not enough, because it can only break at a space: "Betonproductie" is
     * one word and wider than a slab at the stated size, so it hangs off both edges — and in
     * a scene with depth the slab in front of it clips the overhang, which reads as the word
     * having lost its first letter rather than as type being too big. Changing the face is
     * what surfaced it: the same copy fitted in the sans it was set in before.
     */
    private fun fitted(text: String, size: Double): Pair<List<String>, Double> {
        val stated = size / ATLAS
        val lines = face.wrapped(text, INNER / stated)
        val widest = lines.maxOf { face.advanceOf(it) }.coerceAtLeast(1.0)
        return lines to min(stated, INNER / widest)
    }

    /**
     * A list, ranged left rather than centred — a column of names reads down its own edge.
     *
     * The face has to be set here as well as in [TypeBlock]: `text()` draws with whatever
     * `fontMap` the drawer was last left holding, so a line drawn without setting one
     * silently comes out in another slide's face and another slide's size.
     */
    private fun items(drawer: Drawer, items: List<String>) {
        val widest = items.maxOf { face.advanceOf(it) }.coerceAtLeast(1.0)
        val scale = min(ITEM / ATLAS, INNER / widest)
        val step = ITEM_LEADING * ATLAS
        val top = -(items.size * step) / 2.0
        val left = -INNER / 2.0 / scale

        drawer.fontMap = face
        drawer.isolated {
            drawer.scale(scale)
            items.forEachIndexed { j, item ->
                drawer.text(item, left, top + (j + BASELINE) * step)
            }
        }
    }

    private companion object {
        const val WIDTH = 220.0
        const val HEIGHT = 280.0
        const val DEPTH = 100.0

        /** Slab to slab, so a slab-width of travel is this and the train keeps its spacing. */
        const val SPAN = 230.0

        /** How much of a face the copy may use. */
        const val INNER = WIDTH - 2 * 24.0

        /** The atlas the face type is baked at; the sizes below are world units. */
        const val ATLAS = 96.0
        const val HEADLINE = 34.0
        const val NOTE = 19.0
        const val ITEM = 19.0

        /** Where the camera stands; it looks down on the train at the angle this makes. */
        val EYE = Vector3(0.0, 300.0, -300.0)

        /** How much taller type is set than it should read, to undo the camera's look down. */
        val UPRIGHT = EYE.length / kotlin.math.hypot(EYE.x, EYE.z)

        /** Headline to note, in world units on the face. */
        const val NOTE_GAP = 14.0

        const val LEADING = 1.16
        const val ITEM_LEADING = 1.34
        const val BASELINE = 0.78

        /** Every fourth slab swings and steps the same way, so the train has a beat to it. */
        val TURNS = listOf(-45.0, 0.0, 45.0, 0.0)
        val DEPTHS = listOf(0.0, 90.0, 0.0, -90.0)

        /**
         * Clicked: the amplitude of the wave — how far a slab one place off centre turns away, in
         * degrees, and how far behind the centred one it stands, in world units. The wave's own
         * period is four slabs, which is the sketch's beat.
         */
        const val TURN_AWAY = 40.0
        const val STEP_BACK = 90.0
    }
}

private fun lcm(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) {
        val t = y
        y = x % y
        x = t
    }
    return a / x * b
}
