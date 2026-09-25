// ============================================================================ //
//  No `package` declaration: it stands on loadMarkTemplates and ObjectTemplate,
//  in the default package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.shape.Rectangle
import slideshow.Arrival
import slideshow.MosaicCells
import slideshow.Palette
import slideshow.Scale
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.frames
import slideshow.linear
import slideshow.pitchStep
import slideshow.smoothstep
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The same building twice, and the two ways it can come down: how it went, and how it can go now.
 *
 * **Two lanes on the grid, one building in each.** The building is modules of
 * [MosaicCells.SHAPE] split by [MosaicCells.split] (from depth [splitFrom], so the elements stay
 * large enough to follow), every leaf the catalogue mark nearest its proportion, stretched to fill
 * it less the joint — the highlight's grid. Both lanes are dealt from one seed, so the two
 * buildings are the same building element for element and differ only in colour: blue above, the
 * way it was built, red below, WN's demountable elements. The comparison is the point, so nothing
 * but the colour may differ before anything happens.
 *
 * **Click 1 is how it went: demolished.** From the top down each element cracks into the grid's
 * finer squares and they fall, into a low heap of rubble on the lot where it stood. Quickly, and
 * all of it: nothing keeps its shape.
 *
 * **Click 2 is how it can go now: dismantled.** Element by element, from the top down, each is
 * lifted whole, carried and lowered into the same building beside it, which goes up from the ground
 * as the old one comes down. The red modules take the new building's places in the order they come
 * off, bottom up, and each module's elements are mirrored inside it, so what leaves first lands at
 * the foot and nothing is set down on air. Slowly: every element is handled.
 *
 * The end state holds both results in one frame — a heap where the blue building stood, the red
 * building standing again — and the two outcomes are named under them.
 *
 * Everything is a pure function of each click's linear time, so clicking back builds up what was
 * taken down, and of the slide's frame count for the opening, where both buildings are set up
 * element by element from the ground.
 */
class DemolishOrDismantle(
    private val marks: File = File("data/svg/subset_svg"),
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    private val thenTitle: String = "Vroeger",
    private val thenVerb: String = "slopen",
    private val nowTitle: String = "Nu",
    private val nowVerb: String = "demonteren",
    private val rubbleLabel: String = "Puin",
    private val reuseLabel: String = "Hergebruik",
    private val red: ColorRGBa = Palette.RED,
    private val blue: ColorRGBa = Palette.BLUE,
    private val grit: ColorRGBa = ColorRGBa.fromHex("7C8088"),
    private val ground: ColorRGBa = ColorRGBa.fromHex("5A5A5A"),
    private val columns: Int = 3,
    private val rows: Int = 3,
    /** The depth the grid's split starts at: higher leaves fewer, larger elements. */
    private val splitFrom: Int = 3,
    private val seed: Int = 5,
    /** Seconds the demolition's click lasts, and the dismantling's. */
    private val demolish: Double = 4.0,
    private val dismantle: Double = 10.0,
    override val name: String = "Demontage",
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {

    override val steps get() = 3
    override val stepFrames get() = frames(dismantle)
    override fun stepLength(step: Int): Int = if (step == 1) frames(demolish) else frames(dismantle)
    override val settle get() = frames(OPEN_SPREAD + OPEN_EACH)
    override fun stepName(step: Int): String? = when (step) { 1 -> "demolish"; 2 -> "dismantle"; else -> null }

    /** An element as it stands: its mark, its box, and when it is set up as the slide opens. */
    private class Piece(val mark: VertexBuffer?, val box: Rectangle, val arrives: Double)

    /** A square of rubble: where it is in its element, where it lies in the heap, and when it goes. */
    private class Bit(val from: Rectangle, val to: Rectangle, val delay: Double, val fall: Double)

    private class Demolished(val piece: Piece, val cracks: Double, val bits: List<Bit>)
    private class Dismantled(val piece: Piece, val to: Rectangle, val leaves: Double)

    private var demolished: List<Demolished> = emptyList()
    private var dismantled: List<Dismantled> = emptyList()
    private var titleFont: FontImageMap? = null
    private var labelFont: FontImageMap? = null
    private var verbFont: FontImageMap? = null

    private val buildingW get() = columns * MODULE_W
    private val buildingH get() = rows * MODULE_H

    override fun load(program: Program) {
        val templates = loadMarkTemplates(marks)
        if (templates.isEmpty()) println("demolish or dismantle: no marks at ${marks.path}, drawing boxes")
        titleFont = runCatching { program.loadFont(boldPath, Scale.title(H), contentScale = 1.0) }.getOrNull()
        labelFont = runCatching { program.loadFont(boldPath, Scale.label(H), contentScale = 1.0) }.getOrNull()
        verbFont = runCatching { program.loadFont(textPath, Scale.label(H), contentScale = 1.0) }.getOrNull()
        build(templates)
        println("demolish or dismantle: ${demolished.size} elements a building, " +
                "${demolished.sumOf { it.bits.size }} squares of rubble")
    }

    private fun build(templates: List<ObjectTemplate>) {
        // One deal of the grid for both buildings, laid out about the building's own corner.
        fun module(c: Int, r: Int) = Rectangle(c * MODULE_W, r * MODULE_H, MODULE_W, MODULE_H)
        val split = Random(seed)
        val pick = Random(seed * 31)
        class Leaf(val box: Rectangle, val c: Int, val r: Int, val mark: VertexBuffer?)
        // A deal that leaves a sliver is dealt again, off the same stream, and a module that keeps
        // dealing them stays whole: carried through the air, a sliver reads as a crumb of rubble.
        fun dealt(m: Rectangle): List<Rectangle> {
            repeat(6) {
                val out = mutableListOf<Rectangle>()
                MosaicCells.split(m, split, splitFrom) { out += it }
                if (out.all { min(it.width, it.height) >= MIN_SIDE }) return out
            }
            return listOf(m)
        }
        val leaves = (0 until rows).flatMap { r -> (0 until columns).flatMap { c ->
            dealt(module(c, r)).map { Leaf(it, c, r, markFor(it, templates, pick)) }
        } }

        // Taken down from the top, a row at a time and right to left, in both lanes.
        val order = leaves.sortedWith(compareBy<Leaf> { it.r }.thenByDescending { it.c }
            .thenBy { it.box.y }.thenByDescending { it.box.x })
        // Set up from the ground, a row at a time from the left.
        val rise = leaves.sortedWith(compareByDescending<Leaf> { it.box.y + it.box.height }.thenBy { it.box.x })
        val arrives = rise.withIndex().associate { (k, l) -> l to OPEN_SPREAD * k / max(1, leaves.size - 1) }

        // ---- above: demolished into a heap on its own lot ----
        val thenAt = Vector2(OLD_X, THEN_GROUND - buildingH)
        val cracked = order.mapIndexed { k, leaf ->
            val box = leaf.box.movedBy(thenAt)
            // Top down, a row of modules at a time, a little ragged.
            val cracks = DEMOLISH_LEAD + DEMOLISH_SPREAD * leaf.r / max(1, rows - 1) +
                0.25 * (k % 3) / 3.0
            val nx = max(1, (box.width / PITCH).roundToInt())
            val ny = max(1, (box.height / PITCH).roundToInt())
            val cw = box.width / nx
            val ch = box.height / ny
            Triple(leaf, cracks, (0 until ny).flatMap { j -> (0 until nx).map { i ->
                Rectangle(box.x + i * cw + GAP / 2, box.y + j * ch + GAP / 2, cw - GAP, ch - GAP)
            } })
        }
        // The heap: a low mound centred on the lot, [HEAP_ROWS] high, each row narrower by
        // [HEAP_SLOPE] squares a side. It fills from the ground up in the order the rubble comes
        // down, and within a row by where it fell from, so left stays left.
        val total = cracked.sumOf { it.third.size }
        val base = ceil((total + HEAP_SLOPE * HEAP_ROWS * (HEAP_ROWS - 1.0)) / HEAP_ROWS).toInt()
        val rowSizes = mutableListOf<Int>()
        run { var left = total; var r = 0
            while (left > 0) { val n = min(left, max(1, base - 2 * HEAP_SLOPE * r)); rowSizes += n; left -= n; r++ } }
        val middle = OLD_X + buildingW / 2.0
        fun slot(r: Int, i: Int, n: Int) = Rectangle(middle - n * HEAP_PITCH / 2.0 + i * HEAP_PITCH + 1.0,
            THEN_GROUND - (r + 1) * HEAP_PITCH + 1.0, HEAP_PITCH - 2.0, HEAP_PITCH - 2.0)
        class Loose(val element: Int, val box: Rectangle, val leaves: Double)
        val jitter = Random(seed * 53)
        val loose = cracked.flatMapIndexed { e, (_, cracks, bits) ->
            bits.sortedByDescending { it.y }.map { Loose(e, it, cracks + 0.12 * jitter.nextDouble()) }
        }
        // Each square lands a little smaller than its slot and not quite in it, so the heap has
        // lost the order the building had — the one thing the red elements keep.
        val rubble = Random(seed * 71)
        fun loosened(r: Rectangle): Rectangle {
            val k = 0.62 + 0.38 * rubble.nextDouble()
            val w = r.width * k
            return Rectangle(r.x + (r.width - w) * rubble.nextDouble(), r.y + (r.height - w) * rubble.nextDouble(), w, w)
        }
        val landing = HashMap<Loose, Rectangle>()
        var at = 0
        rowSizes.forEachIndexed { r, n ->
            loose.subList(at, at + n).sortedBy { it.box.center.x }.forEachIndexed { i, b -> landing[b] = loosened(slot(r, i, n)) }
            at += n
        }
        demolished = cracked.mapIndexed { e, (leaf, cracks, _) ->
            val bits = loose.filter { it.element == e }.map { b ->
                val to = landing.getValue(b)
                Bit(b.box, to, b.leaves - cracks + CRACK * 0.5, 0.35 + 0.45 * sqrt(max(0.0, to.y - b.box.y) / 400.0))
            }
            Demolished(Piece(leaf.mark, leaf.box.movedBy(thenAt), arrives.getValue(leaf)), cracks, bits)
        }

        // ---- below: dismantled and built again beside it ----
        val nowAt = Vector2(OLD_X, NOW_GROUND - buildingH)
        val modules = order.map { it.c to it.r }.distinct()
        val newPlace = modules.withIndex().associate { (k, cr) ->
            cr to Rectangle(NEW_X + (k % columns) * MODULE_W, NOW_GROUND - (k / columns + 1) * MODULE_H, MODULE_W, MODULE_H)
        }
        val n = order.size
        val stagger = if (n > 1) (dismantle - LEAD - TAIL - CARRY) / (n - 1) else 0.0
        dismantled = order.mapIndexed { k, leaf ->
            val m = module(leaf.c, leaf.r)
            val place = newPlace.getValue(leaf.c to leaf.r)
            // Mirrored top to bottom inside the module: what leaves first lands at the foot.
            val to = Rectangle(place.x + (leaf.box.x - m.x), place.y + (m.y + m.height - leaf.box.y - leaf.box.height),
                leaf.box.width, leaf.box.height)
            Dismantled(Piece(leaf.mark, leaf.box.movedBy(nowAt), arrives.getValue(leaf)), to, LEAD + k * stagger)
        }
    }

    /** The catalogue mark nearest the box's proportion, stretched to fill it less the joint, about its centre. */
    private fun markFor(box: Rectangle, templates: List<ObjectTemplate>, random: Random): VertexBuffer? {
        if (templates.isEmpty()) return null
        val w = box.width - GAP
        val h = box.height - GAP
        val nearest = templates.indices.sortedBy { abs(ln(templates[it].aspect / (w / h))) }.take(3)
        val t = templates[nearest[random.nextInt(nearest.size)]]
        return vertexBuffer(vertexFormat { position(3) }, t.triangles.size).also { vb ->
            vb.put { t.triangles.forEach { v -> write(Vector3(v.x * w / t.aspect, -v.y * h, 0.0)) } }
        }
    }

    /** Where a carried element's box is [u] of the way: lifted clear, across, and lowered. */
    private fun carried(d: Dismantled, u: Double): Rectangle {
        val from = d.piece.box
        val apex = min(from.y, d.to.y) - CLEAR
        val up = smootherstep(u / 0.28)
        val down = smootherstep((u - 0.72) / 0.28)
        val across = smootherstep((u - 0.18) / 0.64)
        return Rectangle(from.x + (d.to.x - from.x) * across,
            from.y + (apex - from.y) * up + (d.to.y - apex) * down, from.width, from.height)
    }

    /** Where a square of rubble is [tau] seconds after its element cracked, and how far it has fallen. */
    private fun fallen(b: Bit, tau: Double): Pair<Rectangle, Double> {
        val v = ((tau - b.delay) / b.fall).coerceIn(0.0, 1.0)
        // Down and out together, both accelerating: it pours onto the heap rather than being
        // thrown sideways first, which read as an explosion.
        val g = v * v
        return Rectangle(b.from.x + (b.to.x - b.from.x) * g, b.from.y + (b.to.y - b.from.y) * g,
            b.from.width + (b.to.width - b.from.width) * g, b.from.height + (b.to.height - b.from.height) * g) to v
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        if (demolished.isEmpty()) return
        val t1 = linear(stage.on(1)) * demolish
        val t2 = linear(stage.on(2)) * dismantle
        val opening = stage.step == 0 && t1 <= 0.0
        val fit = min(stage.width / W, stage.height / H)

        fun arrived(p: Piece) = if (opening) smoothstep(stage.since(frames(p.arrives), frames(OPEN_EACH))) else 1.0
        fun mark(p: Piece, box: Rectangle, colour: ColorRGBa) {
            val vb = p.mark
            drawer.fill = colour
            drawer.stroke = null
            if (vb == null) drawer.rectangle(box.offsetEdges(-GAP / 2))
            else drawer.isolated { drawer.translate(box.center); drawer.vertexBuffer(vb, DrawPrimitive.TRIANGLES) }
        }

        drawer.isolated {
            drawer.translate(stage.bounds.x + (stage.width - W * fit) / 2.0, stage.bounds.y + (stage.height - H * fit) / 2.0)
            drawer.scale(fit)

            drawer.stroke = ground
            drawer.strokeWeight = 2.0
            drawer.lineSegment(MARGIN, THEN_GROUND + 1.0, W - MARGIN, THEN_GROUND + 1.0)
            drawer.lineSegment(MARGIN, NOW_GROUND + 1.0, W - MARGIN, NOW_GROUND + 1.0)

            // ---- how it went ----
            // The rubble first, so it drops behind what still stands.
            drawer.rectangles {
                stroke = ColorRGBa.TRANSPARENT
                strokeWeight = 0.0
                demolished.forEach { d ->
                    val tau = t1 - d.cracks
                    if (tau <= 0.0) return@forEach
                    val shown = (tau / CRACK).coerceIn(0.0, 1.0)
                    d.bits.forEach { b ->
                        val (box, v) = fallen(b, tau)
                        fill = blue.mix(grit, smoothstep(v)).opacify(shown)
                        rectangle(box)
                    }
                }
            }
            demolished.forEach { d ->
                val tau = t1 - d.cracks
                val p = arrived(d.piece)
                if (p <= 0.0 || tau >= CRACK) return@forEach
                val a = p * (1.0 - (tau / CRACK).coerceIn(0.0, 1.0))
                mark(d.piece, d.piece.box.movedBy(Vector2(0.0, -OPEN_DROP * (1.0 - p))), blue.opacify(a))
            }

            // ---- how it can go now ----
            val flying = mutableListOf<Pair<Dismantled, Double>>()
            dismantled.forEach { d ->
                val p = arrived(d.piece)
                if (p <= 0.0) return@forEach
                val u = ((t2 - d.leaves) / CARRY).coerceIn(0.0, 1.0)
                when {
                    u <= 0.0 -> mark(d.piece, d.piece.box.movedBy(Vector2(0.0, -OPEN_DROP * (1.0 - p))), red.opacify(p))
                    u >= 1.0 -> mark(d.piece, d.to, red)
                    else -> flying += d to u
                }
            }
            flying.forEach { (d, u) -> mark(d.piece, carried(d, u), red) }

            // ---- the words ----
            val openType = if (opening) smoothstep(stage.since(0, frames(OPEN_SPREAD))) else 1.0
            fun text(font: FontImageMap?, s: String, x: Double, y: Double, colour: ColorRGBa, a: Double) {
                if (font == null || a <= 0.0) return
                drawer.fontMap = font
                drawer.stroke = null
                drawer.fill = colour.opacify(a)
                drawer.text(s, x, y)
            }
            val titleSize = Scale.title(H)
            val labelSize = Scale.label(H)
            fun lane(title: String, verb: String, top: Double) {
                text(titleFont, title, MARGIN, top + titleSize * CAP, Palette.WHITE, openType)
                text(verbFont, verb, MARGIN, top + titleSize * CAP + labelSize * 1.5, Palette.GREY, openType)
            }
            lane(thenTitle, thenVerb, THEN_GROUND - buildingH)
            lane(nowTitle, nowVerb, NOW_GROUND - buildingH)
            val rubbleAt = demolished.maxOf { d -> d.cracks + d.bits.maxOf { it.delay + it.fall } } - 0.3
            val reuseAt = dismantled.minOf { it.leaves } + CARRY
            text(labelFont, rubbleLabel, OLD_X, THEN_GROUND + OUTCOME, Palette.WHITE, smoothstep((t1 - rubbleAt) / 0.6))
            text(labelFont, reuseLabel, NEW_X, NOW_GROUND + OUTCOME, Palette.WHITE, smoothstep((t2 - reuseAt) / 0.6))
        }
    }

    // ---- the score ---------------------------------------------------------------------------- //

    override val lanes: List<String> get() = listOf("set up", "demolished", "dismantled")

    /** A note an element as the buildings are set up, one as each cracks, and one as each is carried. */
    override fun arrivals(clicks: List<Int>): List<Arrival> {
        if (demolished.isEmpty()) return super.arrivals(clicks)
        val n = demolished.size
        val up = dismantled.mapIndexed { i, d -> Arrival(0, pitchStep(i, n), frames(d.piece.arrives), frames(OPEN_EACH)) }
        val down = clicks.getOrNull(0)?.let { c ->
            demolished.mapIndexed { i, d ->
                Arrival(1, pitchStep(i, n), c + frames(d.cracks), frames(d.bits.maxOf { it.delay + it.fall }))
            }
        }.orEmpty()
        val apart = clicks.getOrNull(1)?.let { c ->
            dismantled.mapIndexed { i, d -> Arrival(2, pitchStep(i, n), c + frames(d.leaves), frames(CARRY)) }
        }.orEmpty()
        return up + down + apart
    }

    private fun smootherstep(u: Double) = u.coerceIn(0.0, 1.0).let { it * it * it * (it * (it * 6.0 - 15.0) + 10.0) }

    private companion object {
        /** Laid out at the pane's own size and fitted into whatever pane it is given. */
        const val W = 1920.0
        const val H = 1080.0
        const val MARGIN = W * 0.02
        /** Rockwell's cap height against its size, near enough. */
        const val CAP = 0.7

        const val MODULE_W = 180.0
        const val MODULE_H = MODULE_W / MosaicCells.SHAPE
        const val OLD_X = 300.0
        const val NEW_X = 1200.0
        /** The two lanes' ground lines, and how far under one an outcome's name stands. */
        const val THEN_GROUND = 452.0
        const val NOW_GROUND = 930.0
        const val OUTCOME = 46.0
        const val GAP = MosaicCells.GAP
        /** The narrowest an element may be. */
        const val MIN_SIDE = 40.0

        /** The rubble: cut on [PITCH], heaped on [HEAP_PITCH], a mound [HEAP_ROWS] high. */
        const val PITCH = 20.0
        const val HEAP_PITCH = 14.0
        const val HEAP_ROWS = 8
        const val HEAP_SLOPE = 2

        /** The opening: both buildings set up from [OPEN_DROP] above, element by element. */
        const val OPEN_SPREAD = 1.0
        const val OPEN_EACH = 0.35
        const val OPEN_DROP = 24.0

        /** The demolition: top down over [DEMOLISH_SPREAD] from [DEMOLISH_LEAD]. */
        const val DEMOLISH_LEAD = 0.3
        const val DEMOLISH_SPREAD = 1.6
        const val CRACK = 0.2

        /** The dismantling: taken off from [LEAD] on, the last set down [TAIL] before the click ends. */
        const val LEAD = 0.4
        const val TAIL = 0.6
        const val CARRY = 1.8
        /** How far over the higher of its two places a carried element is lifted. */
        const val CLEAR = 70.0
    }
}
