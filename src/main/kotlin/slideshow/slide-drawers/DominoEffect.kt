// ============================================================================ //
//  No `package` declaration: it stands on loadObjectSheet, in the default package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadFont
import org.openrndr.draw.parameter
import org.openrndr.draw.renderTarget
import org.openrndr.draw.shadeStyle
import org.openrndr.draw.font.loadFace
import org.openrndr.math.Vector2
import org.openrndr.math.transforms.buildTransform
import org.openrndr.shape.Rectangle
import org.openrndr.shape.Shape
import org.openrndr.shape.contour
import slideshow.Arrival
import slideshow.FPS
import slideshow.MosaicCells
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.drawers.LongShadowV3
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.advanceOf
import slideshow.drawers.setLine
import slideshow.drawers.setToFit
import slideshow.frames
import slideshow.linear
import slideshow.smoothstep
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** An element at the middle of a tree: which drawing on the sheet, in what. */
class Centre(val piece: Int, val colour: ColorRGBa)

/**
 * How the `grid` DUURZAAM is laid and timed — see [DominoEffect]. Seconds count from the start of
 * the click into the word.
 */
class WordGrid(
    /** Coarse cells across the pane: the chapter build's six. */
    val columns: Int = 6,
    /** Pane pixels kept clear either side of the word, and where its middle stands down the pane. */
    val margin: Double = 120.0,
    val middle: Double = 0.47,
    /** How much of a cell the letters must cover for the cell to be part of the word. */
    val inked: Double = 0.5,
    /**
     * The smallest side a cell on a letter's edge may split to, in pane pixels. Under the grid's own
     * 22 px floor, because at 22 a cell can be no narrower than 40 px and a Rockwell stem is ~50:
     * the letters came out as one or two blocks a stem and R and Z did not read.
     */
    val finest: Double = 10.0,
    /** How much of its box a mark must fill to stand in a letter. */
    val solid: Double = 0.9,
    /** A letter cell's height, as a share of a full tower. */
    val letter: Double = 0.55,
    /**
     * The ground cells' heights and roof tones, lowest to highest, darkest to lightest. Low and dark
     * so the word leads: the canvas is linear light, and at the chapter build's 0.25 to 0.85 — or
     * even 0.42 — the ground came out a light grey the white letters barely stood off.
     */
    val groundLow: Double = 0.04,
    val groundHigh: Double = 0.18,
    val groundDark: Double = 0.03,
    val groundLight: Double = 0.14,
    /** When the first domino stands, and how long the chain takes to reach the last cell. */
    val lead: Double = 0.25,
    val chain: Double = 1.4,
    /** When the first letter cell rises, how long the whole word takes to rise, and one cell's rise. */
    val riseAt: Double = 1.2,
    val spell: Double = 1.2,
    val rise: Double = 0.6,
    /**
     * Which way the word rises. `middle`: out from its centre, the way the first domino's chain runs
     * and the chapter build's title opens. `left`: left to right, a line of dominoes — which spells
     * DUUR on its way to DUURZAAM, and "duur" is "expensive".
     */
    val order: String = "middle",
    /** Seconds after the click that the ground sinks away and leaves the word alone; 0 keeps it. */
    val clear: Double = 0.0,
    /** The deal, fixed so the grid comes out the same every run. */
    val seed: Int = 11
) {
    /** One cell clicking up, the chapter build's `clickIn`. */
    val click get() = 0.32

    /** The whole click: the ground's chain and the word's rise, whichever ends later. */
    val seconds get() = max(lead + chain + click, riseAt + spell + rise) + 0.1
}

/**
 * The domino effect: the decision tree, then nine of them, then twenty-five, then one word.
 *
 *     0  one tree, the pane's own — the fans of labels either side of an element
 *     1  nine, three by three, the camera a third of the way back
 *     2  twenty-five, five by five, the labels now too small to read — which is the point
 *     3  the field gives way to DUURZAAM, set to the pane
 *
 * **The multiplication is a zoom out, not a reshuffle**, which is what it was asked to be on
 * 16 September. Every tree stands at `(row, column)` on a lattice measured from the middle of
 * the field, and the only thing a click changes is how many trees the field is divided into:
 * the lattice contracts about its own centre, every tree keeps its place in it, and the ones
 * that were beyond the edge come into shot. So the tree the slide opens on never moves — it
 * only shrinks, in the middle, while the ring around it arrives. It was each tree travelling
 * from its cell in one grid to its cell in the next until then, which reads as a set of
 * drawings being re-dealt rather than as the camera pulling back.
 *
 * **The grids are odd for that reason and no other.** A pure zoom about the centre needs
 * something *at* the centre to hold still, and an even grid has a corner there — one, four,
 * sixteen cannot be a zoom at all, because the single tree has to move into a quadrant. One,
 * nine, twenty-five is the same idea with a middle, and says more: the field ends 25 trees deep
 * rather than 16.
 *
 * **The zoom runs in log space**, the case study's rule: read linearly a scale races at one end
 * of the move and crawls at the other, and what the eye reads as even is an even *ratio*.
 *
 * The fan is the tree slide's picture — d3's `linkHorizontal` from the element's edge to each
 * label, the labels ranged away from the middle — drawn here at any scale, which is what a
 * parallel drawing allows: a scale and a shift per cell and nothing else.
 *
 * **The word is packed out of the catalogue rather than set as type.** It is set into a plate
 * nobody sees, and [objectMosaic] walks a quadtree over that plate: a square wholly inside a
 * letter stands one element at its own size, a square that straddles the edge quarters and asks
 * again, down to [FINEST] — so a stroke is filled with big pieces and traced with small ones, and
 * the size of a mark says where in the letter it is. The ground cells are thrown away, which is
 * the one thing this does differently from the chapter card: the card packs the whole plate and
 * lets colour tell ink from ground, where here the word has to stand alone on black.
 *
 * **The marks arrive one at a time, and the whole word is still one draw call.** Every vertex
 * carries its element's centre and its place in the order, so [MOSAIC_ARRIVAL] grows each one
 * from its own middle as its number comes up — a few thousand elements without a few thousand
 * draw calls, and nothing to keep between frames. The count runs off the same number the field
 * fades on, so clicking back packs the word away again rather than cutting it.
 *
 * **The plate is painted in [load] at a stated pane size**, the rule `CityMapSlide` keeps and for
 * its reason: the packing is a tenth of a second of quadtree and it may not land on the click
 * that plays it, and `load` is not told how big a pane will be. `draw` fits what was packed into
 * whatever pane it gets.
 *
 * ### The word on the chapter build's grid
 *
 * `wordStyle = "grid"` builds DUURZAAM the way the chapter openings build their field, and is what
 * the 25 September prototype is. Everything packed above is the `packed` style, kept beside it.
 *
 * - **The grid is the chapter build's own**: [WordGrid.columns] coarse cells across on the marks'
 *   1.85 proportion, split by [MosaicCells.split], each leaf standing a catalogue mark stretched to
 *   it less the joint. The one thing added is that a cell straddling a letter's edge *must* split,
 *   down to the grid's own 22 px floor, so the cells follow the word while the rest stop where
 *   chance leaves them — the letters are traced by the grid rather than laid over it.
 * - **The word is the cells it covers**, not type. A cell the letters cover by [WordGrid.inked] or
 *   more is part of the word: it rises to [WordGrid.letter] of a tower and its roof goes white.
 *   Everything is then stood, shadowed, laid and grained by [LongShadowV3.drawStanding] — the chapter
 *   build's sun and passes — so the word throws the long shadow the chapter titles do.
 * - **Two dominoes.** The grid clicks up cell to cell, outward from the first tree's element — each
 *   cell a hop after the one that knocks it, found by walking the grid's own adjacency, so the chain
 *   crosses a big cell in one hop and a run of small ones in many. Then the word rises out of it, out
 *   from its middle as the chain ran. Left to right ([WordGrid.order]) is the other way a line of
 *   dominoes goes, and spells DUUR — "expensive" — on the way.
 * - **Each tree is knocked out as the chain reaches the cell under it**, and the title the same way:
 *   the trees leave where the grid arrives rather than on a timer, which left an empty black pane
 *   between the two.
 * - **The click is a pure function of the position while it plays** — its elapsed time is
 *   `linear(position - 2)` of its length, so clicking back sinks the word in reverse and a jump
 *   into the middle of it composes. Once it has landed the sun goes on turning off the frame count.
 */
class DominoEffect(
    private val title: String,
    private val left: List<String>,
    private val right: List<String>,
    private val sheet: File,
    /** The elements the cells take in turn. */
    private val centres: List<Centre>,
    private val word: String = "DUURZAAM",
    /**
     * The pane the word is packed at. Stated because [load] is not told the pane — see the note
     * above — and [draw] fits the packing into whatever it is actually given.
     */
    private val paneWidth: Int = 1920,
    private val paneHeight: Int = 1080,
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val stepFrames: Int = frames(1.2),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList(),
    /** `grid`: the word on the chapter build's grid, see the note above. `packed`: the 18 September word. */
    private val wordStyle: String = "packed",
    /** The chapter build's effect the grid word is stood by; without one the word is packed. */
    private val effect: LongShadowV3? = null,
    private val grid: WordGrid = WordGrid()
) : Slide() {

    /** Whether the word is built on the chapter build's grid. Known before [load], for the lanes. */
    private val gridWord = wordStyle == "grid" && effect != null

    override val name = "Domino"
    override val steps get() = 4
    override fun stepName(step: Int): String? = when (step) {
        1 -> "${GRIDS[1] * GRIDS[1]}"
        2 -> "${GRIDS[2] * GRIDS[2]}"
        3 -> word
        else -> null
    }

    private lateinit var bold: FontImageMap
    private lateinit var text: FontImageMap
    private var pieces: List<SheetObject> = emptyList()

    /** The word, packed. Null where there is no sheet, and it is set as plain type instead. */
    private var packed: VertexBuffer? = null
    private var marks = 0

    /**
     * The word's click is longer than the others, and it has to be.
     *
     * Every other click here is a zoom; this one both empties the field and packs a few thousand
     * elements into a word, one after another. At the deck's own [stepFrames] the whole arrival
     * would run in a third of a second, which is a cut with a blur on it.
     */
    override fun stepLength(step: Int): Int = if (step == 3) frames(if (gridWord) grid.seconds else WORD_CLICK) else stepFrames

    /**
     * The field on one lane and the word on another, which is what the slide really is: three
     * states of trees multiplying, and then a thousand-odd elements landing as DUURZAAM.
     *
     * **The trees are three events, not many.** A click brings a whole ring of them up together —
     * `alpha = smoothstep(t)` for every newcomer at once — so there is nothing per tree to write
     * down, and saying otherwise would invent a texture the slide does not have.
     *
     * **The word is the opposite**, and it is where the notes are. `MOSAIC_ARRIVAL` grows element
     * *i* once `arrived * (marks + ramp)` passes it, so its start is exact; it is banded into
     * [BANDS] for the reason `Crowd`'s figures are, since [marks] runs past a thousand. Two things
     * have to be undone to get the frame: the word only begins at `1 - FADE` of the click, and
     * `stage.position` is *eased*, so a fraction of the click is `linear()` of it — the city
     * cull's distinction, and the difference between a run that rises evenly and one that
     * crowds into the middle.
     */
    override val lanes: List<String> get() =
        if (gridWord) listOf("the field", "the ground", "the word") else listOf("the field", "the word")

    override fun arrivals(clicks: List<Int>): List<Arrival> {
        val field = listOf(Arrival(lane = 0, index = 0, start = 0, length = stepFrames)) +
                clicks.take(2).mapIndexed { k, at -> Arrival(lane = 0, index = k + 1, start = at, length = stepLength(k + 1)) }
        val at = clicks.getOrNull(2) ?: return field
        if (gridWord) return field + gridArrivals(at)
        val span = stepLength(3)
        if (marks <= 0) return field + Arrival(lane = 1, index = 0, start = at, length = span)

        val ramp = (marks * RAMP).coerceAtLeast(1.0)
        val n = min(BANDS, marks)
        /** The frame the word's progress reaches [worded], undoing the fade window and the ease. */
        fun frameOf(worded: Double): Int {
            val eased = (1.0 - FADE) + FADE * worded.coerceIn(0.0, 1.0)
            return at + (linear(eased) * span).toInt()
        }
        val word = (0 until n).map { b ->
            val i = (b.toDouble() / n) * marks
            val start = frameOf(i / (marks + ramp))
            val end = frameOf((i + ramp) / (marks + ramp))
            Arrival(lane = 1, index = b, start = start, length = (end - start).coerceAtLeast(1))
        }
        return field + word
    }

    override fun load(program: Program) {
        bold = program.loadFont(boldPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        text = program.loadFont(textPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        pieces = if (sheet.isFile) loadObjectSheet(sheet) else emptyList<SheetObject>().also { println("domino: no sheet at ${sheet.path}") }
        if (gridWord) {
            effect!!.load(program)
            cells = layGrid()
        } else packWord(program)
    }

    // ---- the word on the chapter build's grid ----------------------------------------------- //

    /** A cell of the grid: where it stands, what, how high and in what tone, and when it moves. */
    private class GridCell(
        val box: Rectangle, val mark: Int, val letter: Boolean, val low: Double, val tone: Double,
        /** When it clicks up to [low], and — a letter cell — when it starts to rise out of the ground. */
        val stands: Double, val rises: Double,
        /** Its place in the chain, 0 at the first domino and 1 at the last. */
        val hop: Double
    )

    private var cells: List<GridCell> = emptyList()

    /**
     * The pane dealt on the chapter build's grid, the cells that straddle a letter's edge split on
     * down to the grid's floor, and the two dominoes worked out: once, here, since the grid is fixed.
     */
    private fun layGrid(): List<GridCell> {
        val w = paneWidth.toDouble()
        val h = paneHeight.toDouble()
        val shapes = wordShapes(w, h)
        if (shapes.isEmpty()) return emptyList()
        val cover = coverage(shapes, paneWidth, paneHeight)
        val aspects = effect?.markAspects.orEmpty()
        val solidity = effect?.markSolidity.orEmpty()
        // A letter cell takes a whole piece: a doorway panel in a letter is a hole in the letter.
        val whole = aspects.indices.filter { (solidity.getOrNull(it) ?: 1.0) >= grid.solid }.ifEmpty { aspects.indices.toList() }
        val random = Random(grid.seed)

        class Leaf(val r: Rectangle, val box: Rectangle, val letter: Boolean, val mark: Int, val low: Double, val tone: Double, val jitter: Double)
        val leaves = mutableListOf<Leaf>()
        val rows = MosaicCells.rows(Rectangle(0.0, 0.0, w, h), grid.columns)
        val cw = w / grid.columns
        val ch = h / rows
        val straddles = { r: Rectangle -> cover.of(r).let { it > EDGE && it < 1.0 - EDGE } }
        for (row in 0 until rows) for (column in 0 until grid.columns) {
            MosaicCells.split(Rectangle(column * cw, row * ch, cw, ch), random, must = straddles, finest = grid.finest) { r ->
                // The joint thins on a cell under the grid's own floor, so a letter's edge traced in
                // small cells reads as an edge rather than a dotted line.
                val joint = MosaicCells.GAP * min(1.0, min(r.width, r.height) / MosaicCells.THINNEST)
                val box = r.offsetEdges(-joint / 2.0)
                val aspect = box.width / box.height
                val letter = cover.of(r) >= grid.inked
                val pool = if (letter) whole else aspects.indices.toList()
                val nearest = pool.sortedBy { abs(ln(aspects[it] / aspect)) }.take(3)
                leaves += Leaf(r, box, letter,
                    mark = if (nearest.isEmpty()) -1 else nearest[random.nextInt(nearest.size)],
                    low = grid.groundLow + (grid.groundHigh - grid.groundLow) * random.nextDouble(),
                    tone = grid.groundDark + (grid.groundLight - grid.groundDark) * random.nextDouble(),
                    jitter = random.nextDouble())
            }
        }

        // The chain: hops across the grid's own adjacency from the cell under the first tree's element,
        // which stood at the middle of the trees' field.
        val n = leaves.size
        val next = Array(n) { mutableListOf<Int>() }
        for (i in 0 until n) for (j in i + 1 until n) if (touching(leaves[i].r, leaves[j].r)) { next[i] += j; next[j] += i }
        val first = Vector2(w / 2.0, h * FIELD_TOP + (h - h * FIELD_TOP) / 2.0)
        val start = leaves.indices.minByOrNull { if (first in leaves[it].r) -1.0 else leaves[it].r.center.distanceTo(first) } ?: 0
        val hops = IntArray(n) { -1 }
        hops[start] = 0
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            for (j in next[i]) if (hops[j] < 0) { hops[j] = hops[i] + 1; queue += j }
        }
        val most = hops.maxOrNull()?.coerceAtLeast(1) ?: 1
        val hopTime = grid.chain / most

        // The word rises across its own ink, out from its middle or from its left edge.
        val ink = shapes.map { it.bounds }.let { b ->
            Rectangle(b.minOf { it.x }, b.minOf { it.y }, b.maxOf { it.x + it.width } - b.minOf { it.x }, b.maxOf { it.y + it.height } - b.minOf { it.y })
        }
        fun along(x: Double) = (if (grid.order == "left") (x - ink.x) / ink.width
                                else abs(x - ink.center.x) / (ink.width / 2.0)).coerceIn(0.0, 1.0)
        val laid = leaves.mapIndexed { i, leaf ->
            val hop = hops[i].coerceAtLeast(0).toDouble() / most
            GridCell(
                box = leaf.box, mark = leaf.mark, letter = leaf.letter,
                low = leaf.low, tone = leaf.tone,
                stands = grid.lead + grid.chain * hop + hopTime * 0.8 * leaf.jitter,
                rises = grid.riseAt + grid.spell * along(leaf.r.center.x),
                hop = hop
            )
        }
        println("domino: \"$word\" on the chapter build's grid — ${laid.size} cells, ${laid.count { it.letter }} of them the word, $most hops from the first domino to the last")
        return laid
    }

    /**
     * [word] as outlines in the bold face, as wide as the pane less [WordGrid.margin] allows and no
     * taller than [WORD_HIGH] of it, its ink centred across the pane and at [WordGrid.middle] down it.
     */
    private fun wordShapes(w: Double, h: Double): List<Shape> {
        val face = loadFace(boldPath)
        val unit = 100.0
        val raw = mutableListOf<Shape>()
        var x = 0.0
        word.forEachIndexed { k, c ->
            if (k > 0) x += face.kernAdvance(unit, word[k - 1], c)
            val glyph = face.glyphForCharacter(c)
            val shape = glyph.shape(unit)
            if (!shape.empty) raw += shape.transform(buildTransform { translate(x, 0.0) })
            x += glyph.advanceWidth(unit)
        }
        if (raw.isEmpty()) return emptyList()
        val boxes = raw.map { it.bounds }
        val ink = Rectangle(boxes.minOf { it.x }, boxes.minOf { it.y },
            boxes.maxOf { it.x + it.width } - boxes.minOf { it.x }, boxes.maxOf { it.y + it.height } - boxes.minOf { it.y })
        val k = min((w - 2.0 * grid.margin) / ink.width, h * WORD_HIGH / ink.height)
        val centre = Vector2(w / 2.0, h * grid.middle)
        return raw.map { it.transform(buildTransform { translate(centre); scale(k); translate(-ink.center) }) }
    }

    /** How much of any box the word covers, off a summed-area table of it at one pane pixel. */
    private class Coverage(val w: Int, val h: Int, val sums: IntArray) {
        fun of(r: Rectangle): Double {
            val x0 = r.x.roundToInt().coerceIn(0, w)
            val x1 = (r.x + r.width).roundToInt().coerceIn(0, w)
            val y0 = r.y.roundToInt().coerceIn(0, h)
            val y1 = (r.y + r.height).roundToInt().coerceIn(0, h)
            if (x1 <= x0 || y1 <= y0) return 0.0
            val s = sums[y1 * (w + 1) + x1] - sums[y0 * (w + 1) + x1] - sums[y1 * (w + 1) + x0] + sums[y0 * (w + 1) + x0]
            return s.toDouble() / ((x1 - x0) * (y1 - y0))
        }
    }

    /**
     * The word rasterised on the CPU, a scanline a pixel row: every outline as a closed polyline, and
     * a pixel inside where its row has crossed an odd number of them to its left. No GL, so no
     * question of which way up a read-back comes, and the same every run.
     */
    private fun coverage(shapes: List<Shape>, w: Int, h: Int): Coverage {
        val rings = shapes.flatMap { it.contours }.map { c -> c.equidistantPositions((c.length / 1.5).toInt().coerceAtLeast(12)) }
        val sums = IntArray((w + 1) * (h + 1))
        val xs = DoubleArray(4096)
        val inside = BooleanArray(w)
        for (y in 0 until h) {
            val py = y + 0.5
            var n = 0
            for (ring in rings) for (k in ring.indices) {
                val a = ring[k]
                val b = ring[(k + 1) % ring.size]
                if ((a.y <= py) != (b.y <= py) && n < xs.size) xs[n++] = a.x + (py - a.y) * (b.x - a.x) / (b.y - a.y)
            }
            xs.sort(0, n)
            inside.fill(false)
            var k = 0
            while (k + 1 < n) {
                val from = (xs[k] - 0.5).let { kotlin.math.ceil(it).toInt() }.coerceIn(0, w)
                val to = (xs[k + 1] - 0.5).let { kotlin.math.ceil(it).toInt() }.coerceIn(0, w)
                for (x in from until to) inside[x] = true
                k += 2
            }
            var row = 0
            for (x in 0 until w) {
                if (inside[x]) row++
                sums[(y + 1) * (w + 1) + x + 1] = sums[y * (w + 1) + x + 1] + row
            }
        }
        return Coverage(w, h, sums)
    }

    /** Whether two leaves of the grid share a stretch of edge — the grid tiles, so they meet exactly. */
    private fun touching(a: Rectangle, b: Rectangle): Boolean {
        val e = 0.5
        val across = min(a.y + a.height, b.y + b.height) - max(a.y, b.y) > e &&
            (abs(a.x + a.width - b.x) < e || abs(b.x + b.width - a.x) < e)
        val down = min(a.x + a.width, b.x + b.width) - max(a.x, b.x) > e &&
            (abs(a.y + a.height - b.y) < e || abs(b.y + b.height - a.y) < e)
        return across || down
    }

    /**
     * The frame count the click into the word began at, or [Int.MIN_VALUE] where it is not known —
     * the only thing this slide keeps between frames, and only once the click has landed.
     */
    private var wordSince = Int.MIN_VALUE

    /**
     * Seconds since the click into the word began. While the click plays this is read off the
     * position — `linear` undoes the deck's ease — so it runs backwards on a click back and is right
     * straight after a jump. Once landed it runs on from where the click began; landed on with no
     * click seen (a cut, a still), the click is taken as just over.
     */
    private fun wordSeconds(stage: Stage): Double {
        val p = stage.position
        val length = stepLength(3)
        if (p <= 2.0) { wordSince = Int.MIN_VALUE; return 0.0 }
        if (p < 3.0) {
            val elapsed = linear(p - 2.0) * length
            wordSince = stage.frame - elapsed.roundToInt()
            return elapsed / FPS
        }
        if (wordSince == Int.MIN_VALUE) wordSince = stage.frame - length
        return (stage.frame - wordSince).toDouble() / FPS
    }

    /** How much of whatever stands over [at] is left at [time]: gone over [KNOCK] from when the chain stands the cell under it. */
    private fun knocked(at: Vector2, time: Double): Double {
        val under = cells.firstOrNull { at in it.box } ?: cells.minByOrNull { it.box.center.distanceTo(at) } ?: return 1.0
        return 1.0 - smoothstep((time - under.stands) / KNOCK)
    }

    /** 0 to 1 with a small overshoot: a part seating, the chapter build's click. */
    private fun seat(t: Double): Double {
        if (t <= 0.0) return 0.0
        if (t >= 1.0) return 1.0
        val s = 1.70158
        return 1.0 + (s + 1.0) * (t - 1.0) * (t - 1.0) * (t - 1.0) + s * (t - 1.0) * (t - 1.0)
    }

    /** The grid at [time] seconds into the click, stood by the chapter build's effect. */
    private fun drawGrid(drawer: Drawer, stage: Stage, time: Double) {
        val standing = cells.map { c ->
            var h = c.low * seat((time - c.stands) / grid.click)
            var tone = c.tone
            if (c.letter) {
                val r = ((time - c.rises) / grid.rise).coerceIn(0.0, 1.0)
                val up = 1.0 - (1.0 - r) * (1.0 - r) * (1.0 - r)
                h += (grid.letter - c.low) * up
                tone += (1.0 - tone) * up
            } else if (grid.clear > 0.0) {
                // The ground going the way it came, from the first domino out: less high until it is
                // in the floor, nothing fading.
                val t = ((time - grid.seconds - grid.clear - grid.chain * c.hop) / LEAVE).coerceIn(0.0, 1.0)
                h *= 1.0 - t * t * (3.0 - 2.0 * t)
            }
            LongShadowV3.Standing(c.box, c.mark, h, tone)
        }
        effect!!.drawStanding(drawer, stage.bounds, frames(time), standing)
    }

    /**
     * The grid on two lanes, banded as the packed word's marks are: the ground clicking up along the
     * chain, and the word rising out of it. Exact, since while the click plays its time is the frame
     * less the frame it began on.
     */
    private fun gridArrivals(at: Int): List<Arrival> {
        fun lane(lane: Int, starts: List<Double>, length: Double): List<Arrival> {
            val sorted = starts.sorted()
            val n = min(BANDS, sorted.size)
            return (0 until n).map { b ->
                val from = sorted[b * sorted.size / n]
                val to = sorted[((b + 1) * sorted.size / n - 1).coerceAtLeast(b * sorted.size / n)] + length
                Arrival(lane = lane, index = b, start = at + frames(from), length = frames(to - from).coerceAtLeast(1))
            }
        }
        return lane(1, cells.map { it.stands }, grid.click) + lane(2, cells.filter { it.letter }.map { it.rises }, grid.rise)
    }

    /**
     * The word set into a plate nobody sees, read back as a field of catalogue elements.
     *
     * Only the cells standing *in* the ink are kept. The card packs the ground as well, because
     * there the ground is a grey field the letters are cut out of; here the word has to arrive on
     * a black pane with nothing else on it, so the ground's cells are simply not built.
     */
    private fun packWord(program: Program) {
        val templates = if (sheet.isFile) loadObjectTemplates(sheet) else emptyList()
        if (templates.isEmpty()) return

        val target = renderTarget(paneWidth, paneHeight) { colorBuffer() }
        val drawer = program.drawer
        drawer.isolatedWithTarget(target) {
            drawer.ortho(target)
            // White is ink and black is ground, which is what the coverage test reads.
            drawer.clear(ColorRGBa.BLACK)
            drawer.stroke = null
            drawer.fill = ColorRGBa.WHITE
            val box = wordBox(paneWidth.toDouble(), paneHeight.toDouble())
            bold.setToFit(word, box, SIZE, 1.0, 1).draw(drawer, box.center)
        }

        val cells = objectMosaic(
            target.colorBuffer(0), templates,
            coarse = COARSE, finest = FINEST, shape = CELL, fill = FILL,
            solid = SOLID, threshold = THRESHOLD, seed = SEED
        ).filter { it.filled }

        target.colorBuffer(0).destroy()
        target.destroy()

        marks = cells.size
        packed = mosaicBuffer(cells, templates)
        println("domino: \"$word\" packed into $marks elements off ${sheet.path}")
    }

    /** Where the word is set, in a pane of [w] by [h]. One definition, read by the plate and the fallback. */
    private fun wordBox(w: Double, h: Double) =
        Rectangle(w * WORD_INSET, h * WORD_TOP, w * (1.0 - 2.0 * WORD_INSET), h * (WORD_BOTTOM - WORD_TOP))

    /** How many trees across the field carries at whole state [s] — always odd. See the note above. */
    private fun grid(s: Int) = GRIDS[s.coerceIn(0, GRIDS.size - 1)]

    /** How far out from the middle the lattice reaches at state [s], in trees. */
    private fun ringAt(s: Int) = grid(s) / 2

    override fun draw(drawer: Drawer, stage: Stage) {
        val w = stage.width
        val h = stage.height

        // The grid word goes down first and the trees over it. Each tree is knocked out as the chain
        // reaches the cell under it — the first under the tree the slide opened on — and the title
        // as it reaches the title's.
        val gridded = gridWord && cells.isNotEmpty()
        val time = if (gridded) wordSeconds(stage) else 0.0
        if (gridded && stage.position > 2.0) drawGrid(drawer, stage, time)
        fun standing(at: Vector2) = if (gridded) knocked(at, time) else 1.0
        val titled = standing(Vector2(w / 2.0, h * TITLE_Y))

        drawer.stroke = null
        drawer.fill = ink.opacify(titled)
        if (titled > 0.0) drawer.setLine(title, bold, Vector2(w / 2.0, h * TITLE_Y), h * TITLE, SIZE, align = 0.5)

        val p = stage.position.coerceIn(0.0, 3.0)
        val a = floor(p).toInt()
        val b = min(a + 1, 3)
        val t = p - a
        val opening = stage.step == 0 && p < 1e-6
        val grown0 = if (opening) smoothstep(stage.since(0, stepFrames)) else 1.0

        // The field, until the word takes over.
        val field = if (gridded) (if (time >= grid.lead + grid.chain + KNOCK * 2.0) 0.0 else 1.0)
            else if (a >= 3) 0.0 else if (b == 3) (1.0 - t / FADE).coerceIn(0.0, 1.0) else 1.0
        if (field > 0.0) {
            // The grid is the field under the title, not the pane: on the whole pane the top
            // row's labels ran under the title.
            val top = h * FIELD_TOP
            val fh = h - top
            val middle = Vector2(w / 2.0, top + fh / 2.0)

            // How many trees the field is divided into, right now. Everything else follows from
            // it: the lattice pitch, the scale a tree is drawn at, and how far the ring reaches.
            val na = grid(min(a, 2)).toDouble()
            val nb = grid(min(b, 2)).toDouble()
            val n = exp(ln(na) + (ln(nb) - ln(na)) * t)
            val pitch = Vector2(w / n, fh / n)
            val scale = fh / h / n

            val here = ringAt(min(a, 2))
            val coming = ringAt(min(b, 2))
            for (r in -coming..coming) for (c in -coming..coming) {
                // Everything already standing holds its place in the lattice and only shrinks;
                // the ring beyond it is off the pane until the zoom brings it in, and fades up
                // as it comes rather than appearing at the edge.
                val standing = max(abs(r), abs(c)) <= here
                val alpha = if (standing) 1.0 else smoothstep(t)
                if (alpha <= 0.0) continue
                val centre = middle + Vector2(c * pitch.x, r * pitch.y)
                val which = centres.getOrNull(
                    ((r + REACH) * (2 * REACH + 1) + (c + REACH)) % centres.size.coerceAtLeast(1)
                )
                val g0 = if (r == 0 && c == 0) grown0 else 1.0
                tree(drawer, centre, scale * g0, alpha * field * g0 * standing(centre), which, w, h)
            }
        }

        // The word, packing itself in from the last of the click.
        val worded = if (a >= 3) 1.0 else if (b == 3) ((t - (1.0 - FADE)) / FADE).coerceIn(0.0, 1.0) else 0.0
        if (worded > 0.0 && !gridded) word(drawer, worded, w, h)
    }

    /**
     * The word: the packed field where there is one, and plain type where there is not — the same
     * honest fallback the chapter card makes when its sheet is missing.
     *
     * The field was packed against a stated pane, so it is *fitted* into this one rather than
     * laid out against it: one scale about the middle, which is all a parallel drawing ever needs.
     */
    private fun word(drawer: Drawer, arrived: Double, w: Double, h: Double) {
        val mesh = packed
        if (mesh == null) {
            val box = wordBox(w, h)
            drawer.fill = ink.opacify(arrived)
            bold.setToFit(word, box, SIZE, 1.0, 1).draw(drawer, box.center)
            return
        }

        val fit = min(w / paneWidth, h / paneHeight)
        // The count runs a ramp's worth past the last mark, or the one that arrives last is still
        // half grown when the click lands — the same trap the city's cull has with its fade.
        val ramp = (marks * RAMP).coerceAtLeast(1.0)
        drawer.stroke = null
        drawer.fill = ink
        drawer.isolated {
            drawer.translate(w / 2.0, h / 2.0)
            drawer.scale(fit, fit)
            drawer.translate(-paneWidth / 2.0, -paneHeight / 2.0)
            drawer.shadeStyle = shadeStyle {
                vertexTransform = MOSAIC_ARRIVAL
                parameter("arrived", arrived * (marks + ramp))
                parameter("ramp", ramp)
            }
            drawer.vertexBuffer(mesh, DrawPrimitive.TRIANGLES)
            drawer.shadeStyle = null
        }
    }

    /** One tree at [scale] of the pane, centred on [centre], the pane being [w] by [h]. */
    private fun tree(drawer: Drawer, centre: Vector2, scale: Double, alpha: Double, which: Centre?, w: Double, h: Double) {
        if (scale <= 0.0 || alpha <= 0.0) return
        val rows = maxOf(left.size, right.size, 1)
        val pitch = (h - 2.0 * h * INSET) / rows
        val widest = (left + right).maxOfOrNull { text.advanceOf(it) } ?: 1.0
        val type = min(pitch * LINE, w * COLUMN * SIZE / widest)      // pane pixels
        val column = widest * type / SIZE
        val tip = w / 2.0 - w * INSET - column - w * LABEL_GAP
        val root = h * ROOT
        val hub = root * 0.6 + w * HUB_GAP

        drawer.isolated {
            drawer.translate(centre)
            drawer.scale(scale)
            drawer.strokeWeight = LINK / scale.coerceAtLeast(0.25)
            for (side in listOf(-1, 1)) {
                val branch = if (side < 0) left else right
                val top = -branch.size * pitch / 2.0
                branch.forEachIndexed { i, label ->
                    val at = Vector2(side * tip, top + (i + 0.5) * pitch)
                    val from = Vector2(side * hub, 0.0)
                    val mid = (from.x + at.x) / 2.0
                    drawer.fill = null
                    drawer.stroke = ink.opacify(alpha)
                    drawer.contour(contour { moveTo(from); curveTo(Vector2(mid, from.y), Vector2(mid, at.y), at) })
                    drawer.stroke = null
                    drawer.fill = ink.opacify(alpha)
                    drawer.setLine(label, text, Vector2(at.x + side * w * LABEL_GAP, at.y + type * 0.34), type, SIZE, align = if (side < 0) 1.0 else 0.0)
                }
            }
            // The element in the middle, off the sheet, in its own colour.
            val piece = which?.let { pieces.getOrNull(it.piece) }
            drawer.stroke = null
            if (piece != null && which != null) {
                drawer.fill = which.colour.opacify(alpha)
                drawer.isolated { piece.drawFitted(drawer, Rectangle(-root / 2.0, -root / 2.0, root, root)) }
            } else {
                drawer.fill = ink.opacify(alpha)
                drawer.rectangle(Rectangle(-root / 2.0, -root / 2.0, root, root))
            }
        }
    }

    private companion object {
        /**
         * Trees across the field at each state. Odd, so a zoom has something at its centre to
         * hold still — see the note above. Adding a fourth here adds a state.
         */
        val GRIDS = listOf(1, 3, 5)

        /** How far the widest lattice reaches from the middle, in trees. */
        val REACH = GRIDS.max() / 2

        const val SIZE = 200.0
        const val TITLE = 0.036
        const val TITLE_Y = 0.06
        const val INSET = 0.05
        const val FIELD_TOP = 0.1
        const val LINE = 0.66
        const val COLUMN = 0.28
        const val LABEL_GAP = 0.008
        const val HUB_GAP = 0.01
        const val ROOT = 0.1
        const val LINK = 1.6
        const val FADE = 0.45
        const val WORD_INSET = 0.04
        const val WORD_TOP = 0.3
        const val WORD_BOTTOM = 0.7

        /** Seconds the last click takes — the field emptying and the word packing itself in. */
        const val WORD_CLICK = 2.6

        /**
         * The word's packing: the coarsest square, the finest, and the cell's own proportion.
         *
         * [CELL] is the subset's median piece, 1.85 wide to high — a square cell letterboxes the
         * median element and halves the ink, the note under the chapter card. [COARSE] and
         * [FINEST] are a range rather than one size because a letter needs both: DUURZAAM set to
         * the pane has strokes around 60px, so a coarse mark carries the middle of a stroke and
         * the fine ones trace its edge.
         */
        const val COARSE = 36.0
        const val FINEST = 8.0
        const val CELL = 1.85
        const val FILL = 0.9

        /**
         * How much ink counts as wholly inside a letter, and as worth keeping at [FINEST].
         *
         * [SOLID] is the card's own 0.62 rather than the strict default: a stroke this narrow has
         * few cells that are *wholly* inside it, so a strict test sends nearly every cell in the
         * word down to the finest level and the letters come out as one fine grain with no big
         * marks in them at all — a picture of type rather than type made of components.
         */
        const val SOLID = 0.62
        const val THRESHOLD = 0.3

        /** Which element lands where — fixed, so the word packs the same way every run. */
        const val SEED = 7

        /** Of the whole count, how many marks are part way in at any moment. */
        const val RAMP = 0.18

        /** Notes the word's arrival is banded into — `Crowd`'s reason, and its number. */
        const val BANDS = 24

        /** The grid word: how tall it may stand as a share of the pane, and the coverage a cell must not be at either end of to count as straddling an edge. */
        const val WORD_HIGH = 0.42
        const val EDGE = 0.02

        /** Seconds a tree takes to go once the chain has reached the cell under it, and one ground cell's sinking when the ground clears. */
        const val KNOCK = 0.25
        const val LEAVE = 0.6
    }
}
