package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.ColorFormat
import org.openrndr.draw.ColorType
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.WrapMode
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadFont
import org.openrndr.draw.loadImage
import org.openrndr.draw.renderTarget
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.frames
import slideshow.seconds
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Long shadow type, v2 — a copy of [LongShadow] to take somewhere new, so the sketch
 * `LongShadowType_v2` can change freely without touching v1 or the show's chapter card.
 *
 * Long shadow type: a title seen from straight above as a plan of towers, each rising out of the
 * floor and throwing its shadow across the paper under a setting sun.
 *
 * **One implementation for the sketch and the show.** `LongShadowType.kt` drives it from `.env`
 * in a window of its own, and [LongShadowChapterPanel] stands it in the show as a chapter card;
 * both hand it a pane and a frame count and nothing else, so what is tuned in the one is what the
 * other draws.
 *
 * **A letter is a tower, and its shadow is as long as it is tall.** Each word — or each block of
 * ink, when the title is a picture — rises on its own beat; while it rises its shadow grows with
 * it. The sun turns slowly and sinks as it goes, so the shadows swing and lengthen like the end of
 * a day.
 *
 * **The shadow is a reach carried along the light, doubled.** The plan is drawn into a float mask
 * holding each pixel's height (red height times coverage, green coverage). A first pass turns
 * height into *reach*, the pane pixels that tower's shadow runs: height over the tangent of the
 * sun. Every pass after it takes what the pixel `a` back toward the sun has left, spends `a`
 * getting here, and keeps whichever is more; passes at 1, 2, 4 … and one filling out the remainder
 * cover every distance up to the longest shadow of the day. A pixel is in shadow wherever the reach
 * it ends with is not negative, so towers of different heights cast shadows of their own lengths
 * exactly, for about log2(longest) full-frame passes.
 *
 * Everything is a function of [draw]'s frame, so it scrubs, replays and films.
 */
class LongShadowV2(
    /** A font file `loadFont` can open — for the show, the deck's own bold. */
    private val fontPath: String,
    val ink: ColorRGBa = ColorRGBa.fromHex("FFFFFF"),
    val paper: ColorRGBa = ColorRGBa.fromHex("3D5AE0"),
    val shade: ColorRGBa = ColorRGBa.fromHex("1E3A72"),
    /** Pane pixels kept clear round the title, a forced line count, and the line height. */
    private val margin: Double = 160.0,
    private val lines: Int? = null,
    private val leading: Double = 1.0,
    /** Seconds between words starting to rise, and how long one takes to stand full height. */
    private val beat: Double = 0.2,
    private val rise: Double = 1.6,
    /** How tall a tower stands in pane pixels, which is what its shadow is measured against. */
    private val tower: Double = 260.0,
    /** Where the shadow falls at the start (45 is down and to the right), and degrees a second it turns. */
    private val angle: Double = 45.0,
    private val turn: Double = 1.5,
    /** The sun's height in degrees, sinking from [high] to [low] over [sunset] seconds, then held. */
    private val high: Double = 40.0,
    private val low: Double = 12.0,
    private val sunset: Double = 40.0,
    /** With a picture: seconds from the first block rising to the last, and how far the order is shuffled. */
    private val spread: Double = 2.4,
    private val scatter: Double = 0.35,
    /** Concrete multiplied over the card, or null for none — the show lays its own over every frame. */
    private val concrete: File? = null,
    private val concreteScale: Double = 1.0,
    private val concreteMix: Double = 1.0,
    /** How hard the grain marks the roofs, taken against the stone's own average so white stays white. */
    private val roofMix: Double = 1.0,
    /** Real pixels to one pane pixel in the buffers: 2 for a sketch filmed at 4K detail, 1 in the show. */
    private val detail: Double = 1.0,
    /** Whether lower towers are packed round the title — circles and slabs filling the frame. */
    private val field: Boolean = false,
    /** Their heights as a share of the title's, lowest and highest. */
    private val fieldLow: Double = 0.1,
    private val fieldHigh: Double = 0.9,
    /** A random step added to each tower's height, either way, so neighbours stand apart and shade one another. */
    private val fieldJitter: Double = 0.2,
    /** Their roofs' tones, from paper (0) to ink (1). */
    private val fieldDark: Double = 0.25,
    private val fieldLight: Double = 0.85,
    /**
     * The module the field is built on, in pane pixels: every tower is a whole number of these
     * across and down, one of [fieldSizes] (`w` by `h` in modules), so the field keeps to a set of
     * sizes and none is smaller than one module. [fieldGap] between neighbours and [fieldClear]
     * kept round the title.
     */
    private val fieldUnit: Double = 40.0,
    private val fieldSizes: List<Pair<Int, Int>> = listOf(4 to 4, 3 to 3, 4 to 2, 2 to 4, 3 to 2, 2 to 3, 2 to 2, 2 to 1, 1 to 2, 1 to 1),
    /** How readily a larger size takes a place it fits, before the smaller ones fill what is left. */
    private val fieldTake: Double = 0.4,
    /** Whether the squares the title takes are filled again at a finer grain, between lines and words. */
    private val fieldFillGaps: Boolean = false,
    /**
     * The field as a city plan: [fieldAvenues] streets right across the frame, [fieldStreets]
     * shorter ones running between them, each a module wide and left as open ground, and
     * [fieldGardens] open plots at ground level in [fieldGardenTone].
     */
    private val fieldAvenues: Int = 2,
    private val fieldStreets: Int = 6,
    private val fieldGardens: Int = 6,
    private val fieldGardenTone: Double = 0.12,
    private val fieldGap: Double = 4.0,
    private val fieldClear: Double = 18.0,
    /**
     * With a picture, the least of its own box a piece must fill to be stood in the field: whole
     * letters like the stencil N are mostly air and read as letters, where bars, blocks and
     * half-rounds read as parts.
     */
    private val fieldSolid: Double = 0.7,
    /** The share of square pieces that stand a circle rather than a slab. */
    private val fieldRound: Double = 0.5,
    /** Seconds after the card comes up that they start rising, and how long until the last one has. */
    private val fieldDelay: Double = 0.2,
    private val fieldSpread: Double = 3.0,
    private val fieldSeed: Int = 11,
    /**
     * When the field stands, against the title. `after`: the title rises first and the field comes
     * up round it, then — if [fieldLeave] is more than 0 — sinks away that many seconds after it is up.
     * `before`: the field rises first on its own, and the title comes in at [titleDelay] seconds while
     * the field sinks away round it, rippling outward from the type.
     */
    private val fieldOrder: String = "after",
    private val titleDelay: Double = 3.5,
    private val fieldLeave: Double = 0.0,
    /** How long one tower takes to sink away and darken into the ground. */
    private val fieldLeaveTime: Double = 1.4,
    /**
     * `fieldOrder = "build"`: the title's own pieces open as stacks of material round the frame and
     * are carried one by one into their places. How many stacks; one layer's height as a share of
     * the title's; a stacked piece's roof tone; how long the stacks take to be laid, layer by layer;
     * when the build starts; how long from the first piece lifted to the last; and one piece's carry.
     */
    private val yardStacks: Int = 7,
    private val yardLayer: Double = 0.16,
    private val yardTone: Double = 0.78,
    private val yardSpread: Double = 2.4,
    private val buildAt: Double = 3.2,
    private val buildSpread: Double = 5.5,
    private val carry: Double = 1.4,
    private val yardSeed: Int = 9,
    /** With build: how long a piece takes to click down out of the yard, and to click up into place. */
    private val clickOut: Double = 0.18,
    private val clickIn: Double = 0.32,
    /** With build from type: the band the title is set in, as a share of the frame's height, and the grid the yard packs on, px. */
    private val titleBand: Double = 0.56,
    private val yardUnit: Double = 12.0,
    /**
     * With build from type: the letters are not cut into their own shapes but built of minimal
     * blocks and circles packed into them on a grid of [blockUnit] pixels — a block's size one of
     * [blockSizes] in cells, square ones a circle [blockRound] of the time, [blockGap] between them.
     */
    private val buildOfBlocks: Boolean = true,
    private val blockUnit: Double = 14.0,
    private val blockSizes: List<Pair<Int, Int>> = listOf(4 to 4, 3 to 3, 4 to 2, 2 to 4, 3 to 2, 2 to 3, 2 to 2, 3 to 1, 1 to 3, 2 to 1, 1 to 2, 1 to 1),
    private val blockRound: Double = 0.3,
    private val blockGap: Double = 3.0,
    /** With blocks: how many blocks a stack holds, and one block's height in a stack, as a share of the title's. */
    private val stackMax: Int = 8,
    private val stackLayer: Double = 0.07,
    /**
     * With blocks: the stacks stand on one grid over the whole window, [gridColumns] cells across and
     * as many down as keep them square, ruled on the ground in lines [gridLine] of the way to ink.
     */
    private val gridColumns: Int = 29,
    private val gridLine: Double = 0.14,
    /**
     * Last of all the grid goes: [gridFadeAfter] seconds after the last block has seated, its lines
     * fade out over [gridFadeTime] — the one thing in the piece that fades, since it is the ground's
     * ruling rather than anything built — and the title stands on plain concrete.
     */
    /** How strongly the grid shows in a shadow, against on the open ground. */
    private val gridInShadow: Double = 0.55,
    /** false draws the piece with no shadows at all: the blocks and the title flat on the ground. */
    private val shadowOn: Boolean = true,
    private val gridFadeAfter: Double = 0.8,
    private val gridFadeTime: Double = 2.0,
) {
    private lateinit var font: FontImageMap
    private lateinit var mask: RenderTarget
    private lateinit var ping: RenderTarget
    private lateinit var pong: RenderTarget
    private lateinit var card: RenderTarget
    private var stone: ColorBuffer? = null
    private val plates = mutableMapOf<String, Plate?>()
    private val fields = mutableMapOf<String, List<Tower>>()
    private lateinit var probe: RenderTarget

    /** One tower of the field: a circle or a slab, its height, its roof's tone and when it rises. */
    private class Tower(
        val box: Rectangle, val round: Boolean, val height: Double, val tone: Double, val start: Double,
        /** Which of the title's own pieces stands here, or -1 for a plain slab or circle. */
        val piece: Int = -1,
        /** Whether that piece is turned a quarter to fit its cell. */
        val turned: Boolean = false
    )

    private val low2 = low.coerceAtLeast(2.0)
    /** The longest shadow the day can throw, which is what the passes have to cover. */
    private val longest = tower / tan(Math.toRadians(min(high, low2)))

    /** Everything that takes a GL context: the face, the buffers, the stone. */
    fun load(program: Program) {
        font = program.loadFont(fontPath, EM, characterSet = TYPE_CHARACTERS, contentScale = detail)
        fun field() = renderTarget(WIDE.toInt(), HIGH.toInt(), contentScale = detail) {
            colorBuffer(type = ColorType.FLOAT32)
        }
        mask = field(); ping = field(); pong = field()
        // Where the title stands, at a quarter of the pane: all the packing has to ask it.
        probe = renderTarget((WIDE / PROBE).toInt(), (HIGH / PROBE).toInt()) { colorBuffer(type = ColorType.FLOAT32) }
        card = renderTarget(WIDE.toInt(), HIGH.toInt(), contentScale = detail) { colorBuffer() }
        stone = concrete?.let { file ->
            if (!file.isFile) { println("long shadow v2: no concrete at ${file.path}"); null }
            else loadImage(file).also {
                it.wrapU = WrapMode.REPEAT
                it.wrapV = WrapMode.REPEAT
                // Mipmapped so the shader can read the stone's own average off the last level.
                it.generateMipmaps()
                it.filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
            }
        }
    }

    /**
     * [text] set in the face — big, in a band of [titleBand] of the frame's height, full width but for
     * the margin — drawn once into a picture and cut into its pieces like any drawn title, so the
     * build can work with any chapter's words. In Rockwell every letter is one connected piece, so
     * here a piece is a letter. The picture is kept under `build/long-shadow-v2/`.
     */
    fun textPlate(drawer: Drawer, text: String): Plate? = plates.getOrPut("text:$text") {
        val band = HIGH * titleBand
        val area = Rectangle(margin, (HIGH - band) / 2.0, WIDE - 2 * margin, band)
        val rt = renderTarget(WIDE.toInt(), HIGH.toInt()) { colorBuffer() }
        drawer.isolatedWithTarget(rt) {
            drawer.ortho(rt)
            drawer.clear(ColorRGBa.BLACK)
            plan(drawer, text, Int.MAX_VALUE / 4, area) { ColorRGBa.WHITE }
        }
        val file = File("build/long-shadow-v2/${text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}.png")
        file.parentFile.mkdirs()
        rt.colorBuffer(0).saveToFile(file, async = false)
        if (buildOfBlocks) {
            blockPlate(rt.colorBuffer(0), file).also { println("long shadow v2: \"$text\" built of ${it.pieces} blocks") }
        } else {
            rt.destroy()
            loadPlate(file, 0.0).also { println("long shadow v2: \"$text\" set in the face, ${it.pieces} pieces") }
        }
    }

    /**
     * The title in [labels] — white type on black, drawn at the pane's size — built of minimal shapes.
     *
     * The picture is read onto a grid of [blockUnit] pixels, a cell ink when its middle is. The ink
     * cells are then packed with blocks from [blockSizes], largest first, each tried at every ink
     * place in a shuffled order and taken where every cell under it is ink and free; single cells
     * fill what is left, so every cell of every letter is built. A square block is a circle
     * [blockRound] of the time. The build order is a construction's: letter by letter across the
     * title, and within a letter from the ground up.
     */
    private fun blockPlate(labels: ColorBuffer, file: File): Plate {
        val img = ImageIO.read(file)
        val u = blockUnit
        val cols = (WIDE / u).toInt()
        val rows = (HIGH / u).toInt()
        val ink = BooleanArray(cols * rows) { k ->
            val x = ((k % cols + 0.5) * u).toInt().coerceIn(0, img.width - 1)
            val y = ((k / cols + 0.5) * u).toInt().coerceIn(0, img.height - 1)
            ((img.getRGB(x, y) shr 8) and 255) > 128
        }
        val free = ink.copyOf()
        val random = kotlin.random.Random(yardSeed + 1)
        val boxes = mutableListOf<Rectangle>()
        val kinds = mutableListOf<Int>()
        for ((w, h) in blockSizes.sortedByDescending { it.first * it.second }) {
            val spots = (0 until cols * rows).filter { free[it] }.shuffled(random)
            for (k in spots) {
                val x = k % cols; val y = k / cols
                if (x + w > cols || y + h > rows) continue
                var fits = true
                loop@ for (yy in y until y + h) for (xx in x until x + w) if (!free[yy * cols + xx]) { fits = false; break@loop }
                if (!fits) continue
                if (w * h > 1 && random.nextDouble() > 0.55) continue       // leave room for the smaller sizes
                for (yy in y until y + h) for (xx in x until x + w) free[yy * cols + xx] = false
                boxes += Rectangle(x * u + blockGap / 2, y * u + blockGap / 2, w * u - blockGap, h * u - blockGap)
                kinds += if (w == h && w > 1 && random.nextDouble() < blockRound) 1 else 0
            }
        }
        for (k in 0 until cols * rows) if (free[k]) {
            free[k] = false
            boxes += Rectangle((k % cols) * u + blockGap / 2, (k / cols) * u + blockGap / 2, u - blockGap, u - blockGap)
            kinds += 0
        }
        // Letter by letter: the columns of ink separated by empty columns are the letters (near
        // enough in a set title); within one, from the bottom up.
        val inkCols = (0 until cols).map { c -> (0 until rows).any { ink[it * cols + c] } }
        val letterOf = IntArray(cols); var letter = 0
        for (c in 0 until cols) { if (c > 0 && inkCols[c] && !inkCols[c - 1]) letter++; letterOf[c] = letter }
        val line = boxes.map { if (it.center.y < (boxes.minOf { b -> b.y } + boxes.maxOf { b -> b.y + b.height }) / 2) 0 else 1 }
        val rank = boxes.indices.sortedWith(compareBy({ line[it] }, { letterOf[(boxes[it].center.x / u).toInt().coerceIn(0, cols - 1)] }, { -boxes[it].center.y }, { boxes[it].center.x }))
        val order = DoubleArray(boxes.size)
        rank.forEachIndexed { r, i -> order[i] = r.toDouble() / maxOf(1, boxes.size - 1) }
        val x0 = boxes.minOf { it.x }; val y0 = boxes.minOf { it.y }
        val bounds = Rectangle(x0, y0, boxes.maxOf { it.x + it.width } - x0, boxes.maxOf { it.y + it.height } - y0)
        return Plate(labels, boxes.size, bounds, emptyList(), boxes.map { 1.0 }, boxes, order.toList(), kinds)
    }

    /** [file] read into blocks, once, or null when it is not there. */
    fun plate(file: File): Plate? = plates.getOrPut(file.path) {
        if (!file.isFile) null
        else loadPlate(file, scatter).also { println("long shadow v2: ${file.path}, ${it.pieces} blocks") }
    }

    /**
     * The card at [frame] frames since it came up, drawn into [bounds] of whatever the drawer is
     * drawing into. The title is [plate]'s blocks when one is given, [text] set in the face otherwise.
     */
    fun draw(drawer: Drawer, bounds: Rectangle, text: String, frame: Int, plate0: Plate? = null) {
        val plate = plate0 ?: if (fieldOrder == "build") textPlate(drawer, text) else null
        // The sun: turning at a steady rate, sinking on an ease that slows toward the horizon,
        // then holding low while it goes on turning.
        val theta = Math.toRadians(angle + turn * seconds(frame))
        val direction = Vector2(cos(theta), sin(theta))
        val dusk = (seconds(frame) / sunset).coerceIn(0.0, 1.0)
        val elevation = high + (low2 - high) * (1.0 - (1.0 - dusk) * (1.0 - dusk))
        val reach = tower / tan(Math.toRadians(elevation))

        drawer.isolated {
            // The plan: red height times coverage, green coverage, blue the roof's tone times
            // coverage. The field goes down first so the title stands over anything it touches.
            drawer.isolatedWithTarget(mask) {
                drawer.ortho(mask)
                drawer.clear(ColorRGBa.TRANSPARENT)
                // The field's clock starts when the last of the title has started to rise. Towers and
                // title rise on the same curve and no tower is as tall as the title, so from then on
                // every tower is lower than every letter at every frame: the type is always highest.
                if (fieldOrder == "build" && plate != null && plate.pieceBoxes.isNotEmpty()) {
                    build(drawer, plate, seconds(frame))
                } else {
                if (field) towers(drawer, fieldFor(drawer, text, plate), fieldFrame(frame, text, plate), plate)
                if (plate != null) drawPlate(drawer, plate, titleFrame(frame))
                else plan(drawer, text, titleFrame(frame)) { h -> ColorRGBa(h, 1.0, 1.0, 1.0) }
                }
            }
            val field = shadow(drawer, direction, reach)

            // Roofs and ground in one pass, off the plan and the shadow together, so a shadow can
            // land on a lower roof as well as on the ground.
            drawer.isolatedWithTarget(card) {
                drawer.ortho(card)
                drawer.clear(paper)
                lay.parameter("field", field)
                lay.parameter("plan", mask.colorBuffer(0))
                lay.parameter("reach", reach)
                lay.parameter("paper", paper)
                lay.parameter("ink", ink)
                lay.parameter("shade", shade)
                val (cw, ch) = gridCell()
                lay.parameter("cell", Vector2(cw, ch))
                lay.parameter("pane", Vector2(WIDE, HIGH))
                // The fade *ends* as the last block seats (plus [gridFadeAfter], 0 by default), so the
                // grid is gone the moment the type is done rather than lingering after it.
                val done = buildAt + buildSpread + clickOut + clickIn + gridFadeAfter
                val fade = ((seconds(frame) - (done - gridFadeTime)) / gridFadeTime.coerceAtLeast(0.01)).coerceIn(0.0, 1.0)
                val left = 1.0 - fade * fade * (3.0 - 2.0 * fade)
                lay.parameter("gridInShadow", gridInShadow)
                lay.parameter("shadowOn", if (shadowOn) 1.0 else 0.0)
                lay.parameter("gridLine", if (fieldOrder == "build" && buildOfBlocks) gridLine * left else 0.0)
                drawer.shadeStyle = lay
                drawer.image(field, 0.0, 0.0, WIDE, HIGH)
                drawer.shadeStyle = null
            }

            val s = stone
            if (s != null) {
                grain.parameter("source", card.colorBuffer(0))
                grain.parameter("roofs", mask.colorBuffer(0))
                grain.parameter("stone", s)
                grain.parameter("tile", Vector2(s.width * concreteScale, s.height * concreteScale))
                drawer.shadeStyle = grain
            }
            drawer.image(card.colorBuffer(0), bounds.x, bounds.y, bounds.width, bounds.height)
            drawer.shadeStyle = null
        }
    }

    /**
     * The plan at [frame]. [paint] is handed each word's rise, 0 on the floor to 1 standing, and
     * gives back the colour to draw it in — the height mask and the roofs are the same words drawn
     * two ways.
     */
    private fun plan(drawer: Drawer, text: String, frame: Int, area: Rectangle? = null, paint: (Double) -> ColorRGBa) {
        val box = area ?: Rectangle(margin, margin, WIDE - 2 * margin, HIGH - 2 * margin)
        val block = font.setToFit(text, box, EM, leading, lines)
        val line = leading * EM * block.scale
        val top = box.center.y - block.height / 2.0
        val beatFrames = frames(beat)
        val riseFrames = frames(rise).coerceAtLeast(1)

        drawer.fontMap = font
        drawer.stroke = null
        var n = 0
        block.lines.forEachIndexed { row, words ->
            var x = box.x
            val y = top + (row + 0.78) * line
            for (word in words.split(" ")) {
                val t = ((frame - n * beatFrames).toDouble() / riseFrames).coerceIn(0.0, 1.0)
                if (t > 0.0) {
                    // Out of the floor fast, settling slow: a tower going up, not a fade.
                    drawer.fill = paint(1.0 - (1.0 - t) * (1.0 - t) * (1.0 - t))
                    drawer.isolated {
                        drawer.translate(x, y)
                        drawer.scale(block.scale)
                        drawer.text(word, 0.0, 0.0)
                    }
                }
                x += font.advanceOf("$word ") * block.scale
                n++
            }
        }
    }

    /** Seconds after the card comes up that the last of the title starts to rise. */
    private fun titleLastStart(text: String, plate: Plate?): Double {
        if (plate != null) return spread
        val box = Rectangle(margin, margin, WIDE - 2 * margin, HIGH - 2 * margin)
        val words = font.setToFit(text, box, EM, leading, lines).lines.sumOf { it.split(" ").size }
        return (words - 1).coerceAtLeast(0) * beat
    }

    /** The box the set title stands in, in pane pixels. */
    private fun titleBox(text: String): Rectangle {
        val box = Rectangle(margin, margin, WIDE - 2 * margin, HIGH - 2 * margin)
        val block = font.setToFit(text, box, EM, leading, lines)
        val widest = block.lines.maxOf { font.advanceOf(it) } * block.scale
        return Rectangle(box.x, box.center.y - block.height / 2.0, widest, block.height)
    }

    // ---- the yard: the title's pieces as stacked material, carried into place ------------------ //

    /** Where a piece waits in the yard: its centre on the pane, its stacked height, and when it is laid. */
    private class Spot(val centre: Vector2, val height: Double, val laid: Double)

    private val yards = mutableMapOf<Plate, List<Spot>>()

    /**
     * The yard: every piece of the title bin-packed onto a grid round it, once and kept — square, as it
     * stands in the title, never turned. The grid is [yardUnit] pixels; a piece takes the cells its box
     * covers plus one of clearance, and the cells the title will take are closed to it. Pieces go in
     * largest first, each to the top or bottom band that has less in it, and within the band to the
     * first free place scanning outward from the title and from the middle across — so the yard packs
     * tight against the type and spreads toward the edges, and may run a little off them. Each piece
     * stands one to three [yardLayer]s high, a stack of that many, so the yard throws shadows of
     * several lengths.
     */
    private fun yardFor(plate: Plate): List<Spot> = yards.getOrPut(plate) {
        val random = kotlin.random.Random(yardSeed)
        val n = plate.pieceBoxes.size
        val homes = plate.pieceBoxes.map { placed(plate, it) }
        val title = placed(plate, plate.bounds ?: Rectangle(0.0, 0.0, WIDE, HIGH))
        val u = yardUnit
        val over = 3                                      // cells the grid runs past each edge
        val cols = (WIDE / u).toInt() + 2 * over
        val rows = (HIGH / u).toInt() + 2 * over
        fun cx(i: Int) = (i - over) * u
        fun cy(j: Int) = (j - over) * u
        val taken = BooleanArray(cols * rows) { k ->
            val cell = Rectangle(cx(k % cols), cy(k / cols), u, u)
            cell.intersects(title.offsetEdges(u * 2.0))
        }
        val midX = WIDE / 2.0
        // Rows ordered outward from the title, above and below; columns from the middle out.
        val above = (0 until rows).filter { cy(it) + u <= title.y }.sortedByDescending { it }
        val below = (0 until rows).filter { cy(it) >= title.y + title.height }.sortedBy { it }
        val colOrder = (0 until cols).sortedBy { kotlin.math.abs(cx(it) + u / 2 - midX) }
        val used = doubleArrayOf(0.0, 0.0)
        val spots = arrayOfNulls<Spot>(n)
        for (i in (0 until n).sortedByDescending { homes[it].width * homes[it].height }) {
            val w = kotlin.math.ceil(homes[i].width / u).toInt() + 1
            val h = kotlin.math.ceil(homes[i].height / u).toInt() + 1
            val band = if (used[0] <= used[1]) 0 else 1
            var at: Pair<Int, Int>? = null
            search@ for (b in listOf(band, 1 - band)) {
                for (r in (if (b == 0) above else below)) {
                    // Above, a piece hangs up from its row so it packs against the title's top edge.
                    val top = if (b == 0) r - h + 1 else r
                    if (top < 0 || top + h > rows) continue
                    for (c in colOrder) {
                        val left = c - w / 2
                        if (left < 0 || left + w > cols) continue
                        var free = true
                        loop@ for (yy in top until top + h) for (xx in left until left + w) if (taken[yy * cols + xx]) { free = false; break@loop }
                        if (free) { at = left to top; used[b] += (w * h).toDouble(); break@search }
                    }
                }
            }
            val (left, top) = at ?: ((cols / 2) to 0)
            for (yy in top until minOf(rows, top + h)) for (xx in left until minOf(cols, left + w)) taken[yy * cols + xx] = true
            val centre = Vector2(cx(left) + w * u / 2.0, cy(top) + h * u / 2.0)
            val layers = 1 + random.nextInt(3)
            val out = kotlin.math.abs(centre.y - title.center.y) / (HIGH / 2.0)
            spots[i] = Spot(centre, layers * yardLayer, out * yardSpread + random.nextDouble() * 0.25)
        }
        println("long shadow v2: ${n} pieces packed in the yard")
        spots.map { it!! }
    }

    /**
     * The yard and the build at [time] seconds. A piece in the yard is laid by rising out of the floor
     * to its stack's height. At its beat — its place in the title's reading order across [buildSpread]
     * — it clicks out: drops straight down into the floor over [clickOut] and is gone; and clicks in
     * where it belongs, rising out of the floor in its place over [clickIn] with a small overshoot, like
     * a part seating. No travel, no turn, no fade: a piece is in the yard, or in the title.
     */
    private fun build(drawer: Drawer, plate: Plate, time: Double) {
        if (plate.pieceKinds.isNotEmpty()) { stackBuild(drawer, plate, time); return }
        val spots = yardFor(plate)
        val homes = plate.pieceBoxes.map { placed(plate, it) }
        drawer.stroke = null
        data class Now(val i: Int, val centre: Vector2, val h: Double, val tone: Double)
        val now = spots.indices.mapNotNull { i ->
            val spot = spots[i]
            val beat = buildAt + (plate.pieceOrder.getOrNull(i) ?: 0.0) * buildSpread
            if (time < beat) {
                val lay = ((time - spot.laid) / rise).coerceIn(0.0, 1.0)
                val h = spot.height * (1.0 - (1.0 - lay) * (1.0 - lay) * (1.0 - lay))
                return@mapNotNull if (h <= HIDE_BELOW * spot.height) null else Now(i, spot.centre, h, yardTone)
            }
            val out = ((time - beat) / clickOut).coerceIn(0.0, 1.0)
            if (out < 1.0) {
                val h = spot.height * (1.0 - out * out)      // accelerating down: a click, not a sink
                return@mapNotNull if (h <= HIDE_BELOW * spot.height) null else Now(i, spot.centre, h, yardTone)
            }
            val t = ((time - beat - clickOut) / clickIn).coerceIn(0.0, 1.0)
            if (t <= 0.0) return@mapNotNull null
            // Up past its height and back: back-out easing, so it lands with a seat.
            val s = 1.70158
            val e = 1.0 + (s + 1.0) * (t - 1.0) * (t - 1.0) * (t - 1.0) + s * (t - 1.0) * (t - 1.0)
            val h = e.coerceAtLeast(0.0)
            if (h <= HIDE_BELOW) return@mapNotNull null
            Now(i, homes[i].center, h, 1.0)
        }.sortedBy { it.h }            // the higher drawn last, so it covers what it stands over

        for (p in now) {
            val w = homes[p.i].width; val h = homes[p.i].height
            val square = (plate.pieceSolidity.getOrNull(p.i) ?: 0.0) >= SQUARE_CUT
            val round = plate.pieceKinds.getOrNull(p.i) == 1
            drawer.isolated {
                drawer.translate(p.centre)
                if (round) {
                    drawer.fill = ColorRGBa(p.h, 1.0, p.tone, 1.0)
                    drawer.circle(0.0, 0.0, w / 2.0)
                } else if (square) {
                    drawer.fill = ColorRGBa(p.h, 1.0, p.tone, 1.0)
                    drawer.rectangle(-w / 2.0, -h / 2.0, w, h)
                } else {
                    piece.parameter("h", p.h)
                    piece.parameter("tone", p.tone)
                    drawer.shadeStyle = piece
                    drawer.image(plate.pieceShapes[p.i], -w / 2.0, -h / 2.0, w, h)
                    drawer.shadeStyle = null
                }
            }
        }
    }

    // ---- stacks: the blocks stacked in a perfect grid before they build the type ----------------- //

    /** One stack: where it stands, what it is a stack of (the box of one block, and whether round), and its blocks. */
    private class Stack(val centre: Vector2, val w: Double, val h: Double, val round: Boolean, val members: List<Int>, val inField: Boolean = false)

    private val stackSets = mutableMapOf<Plate, List<Stack>>()

    /**
     * The blocks sorted into stacks of like blocks — same size, same shape — of at most [stackMax]
     * each, and the stacks stood in one perfectly regular grid: every slot the same size, the largest
     * block plus a margin, the grid centred in rows above and below the title, nearest rows first.
     * How many stacks is counted, not stated: each kind of block needs as many stacks as its count
     * divided by [stackMax], rounded up.
     */
    private fun stacksFor(plate: Plate): List<Stack> = stackSets.getOrPut(plate) {
        val boxes = plate.pieceBoxes
        val n = boxes.size
        val (cw, ch) = gridCell()
        val cols = gridColumns
        val rows = (HIGH / ch).roundToInt()
        val title = (plate.bounds ?: Rectangle(0.0, 0.0, WIDE, HIGH)).offsetEdges(blockUnit)
        val cells = (0 until rows).flatMap { r -> (0 until cols).map { c -> c to r } }
        fun centre(c: Pair<Int, Int>) = Vector2((c.first + 0.5) * cw, (c.second + 0.5) * ch)
        // The whole window carries stacks, the type's own field included: as few blocks a stack as
        // lets them spread over every cell, but at least two so a stack is a stack.
        val per = maxOf(2, (n + cells.size - 1) / cells.size)
        val count = (n + per - 1) / per
        // Which cells: an even scatter rather than every nth cell — stepping through the cells in
        // reading order lined the stacks up in stripes. Each cell is ranked by the R2 low-discrepancy
        // sequence (the plastic number's two fractions), which spreads any count evenly over the
        // grid with no rows, columns or clumps, and the first `count` are taken.
        val a1 = 0.7548776662466927; val a2 = 0.5698402909980532
        val places = cells.sortedBy { (c, r) -> ((c * a1 + r * a2) % 1.0) }.take(count)
            .sortedWith(compareBy({ it.second }, { it.first }))
        val (field, outer) = places.partition { Rectangle(it.first * cw, it.second * ch, cw, ch).intersects(title) }

        // A stack standing in the type's field holds the blocks whose places are nearest it, so when
        // it goes first its blocks have only a short way to go — and the field is cleared by putting
        // its own material where it belongs.
        val left = (0 until n).toMutableSet()
        val stacks = mutableListOf<Stack>()
        for (c in field) {
            val at = centre(c)
            val members = left.sortedBy { (boxes[it].center - at).squaredLength }.take(per)
            left -= members.toSet()
            if (members.isEmpty()) continue
            val b = boxes[members.first()]
            stacks += Stack(at, b.width, b.height, plate.pieceKinds[members.first()] == 1, members, inField = true)
        }
        // The rest like with like, as many to a stack as the outer cells need.
        val key = { i: Int -> Triple((boxes[i].width / blockUnit).roundToInt(), (boxes[i].height / blockUnit).roundToInt(), plate.pieceKinds[i]) }
        val kinds = left.groupBy(key).entries.sortedWith(compareBy({ -it.key.first * it.key.second }, { it.key.third }))
        var outerPer = per
        while (kinds.sumOf { (it.value.size + outerPer - 1) / outerPer } > outer.size && outerPer < n) outerPer++
        val lots = kinds.flatMap { (_, m) -> m.chunked(outerPer) }
        lots.forEachIndexed { k, members ->
            val c = outer[(k.toLong() * outer.size / lots.size).toInt()]
            val b = boxes[members.first()]
            stacks += Stack(centre(c), b.width, b.height, plate.pieceKinds[members.first()] == 1, members)
        }
        println("long shadow v2: $n blocks on a ${cols}x$rows grid — ${field.size} stacks in the type's field, ${lots.size} outside")
        stacks
    }

    /** One cell of the window's grid: [gridColumns] across, and square as near as the height allows. */
    private fun gridCell(): Pair<Double, Double> {
        val cw = WIDE / gridColumns
        val rows = (HIGH / cw).roundToInt().coerceAtLeast(1)
        return cw to HIGH / rows
    }

    /**
     * The stacks and the build at [time] seconds. First the stacks are filled, a block at a time,
     * row by row and stack by stack over [yardSpread], each block clicking down onto its stack. Then,
     * from [buildAt], every block in turn in the construction order across [buildSpread] clicks off
     * the top of its stack — the stack a layer lower — and seats in its place in the title. A stack
     * is drawn as its top block standing as high as the blocks still in it; nothing moves sideways,
     * nothing turns, nothing fades.
     */
    private fun stackBuild(drawer: Drawer, plate: Plate, time: Double) {
        val stacks = stacksFor(plate)
        val boxes = plate.pieceBoxes
        val n = boxes.size
        drawer.stroke = null
        fun seat(t: Double): Double {        // 0 to 1 with a small overshoot: a part seating
            if (t <= 0.0) return 0.0
            if (t >= 1.0) return 1.0
            val s = 1.70158
            return 1.0 + (s + 1.0) * (t - 1.0) * (t - 1.0) * (t - 1.0) + s * (t - 1.0) * (t - 1.0)
        }
        // The stacks are revealed together: every block arrives somewhere in [yardSpread], scattered
        // off its own index rather than dealt in order, the lower blocks of a stack a touch earlier —
        // so the whole grid rises at once, and only the build goes one by one.
        val arrive = DoubleArray(n)
        for (st in stacks) st.members.forEachIndexed { layer, i ->
            val jitter = ((i * 0.618034 + 0.137) % 1.0)
            arrive[i] = yardSpread * (0.75 * jitter + 0.25 * layer.toDouble() / maxOf(1, st.members.size))
        }
        // The blocks stacked in the type's field are built first — each to its own place, near it —
        // so the field clears before anything else lands in it; then the rest, each in the build order.
        val fieldBlocks = stacks.filter { it.inField }.flatMap { it.members }.toSet()
        val sequence = (0 until n).sortedWith(compareBy({ if (it in fieldBlocks) 0 else 1 }, { plate.pieceOrder.getOrNull(it) ?: 0.0 }))
        val beat = DoubleArray(n)
        sequence.forEachIndexed { r, i -> beat[i] = buildAt + buildSpread * r.toDouble() / maxOf(1, n - 1) }

        data class Draw(val centre: Vector2, val w: Double, val h: Double, val round: Boolean, val height: Double, val tone: Double)
        val draws = mutableListOf<Draw>()
        for (st in stacks) {
            // How many blocks the stack holds now: each arrives with a seat and leaves with a click.
            var count = 0.0
            for (i in st.members) {
                val inn = seat((time - arrive[i]) / clickIn)
                val out = ((time - beat[i]) / clickOut).coerceIn(0.0, 1.0)
                count += inn - out * out
            }
            val height = count * stackLayer
            if (height > HIDE_BELOW * stackLayer) draws += Draw(st.centre, st.w, st.h, st.round, height, yardTone)
        }
        for (i in 0 until n) {
            val h = seat((time - beat[i] - clickOut) / clickIn)
            if (h > HIDE_BELOW) draws += Draw(boxes[i].center, boxes[i].width, boxes[i].height, plate.pieceKinds[i] == 1, h, 1.0)
        }
        for (d in draws.sortedBy { it.height }) {
            drawer.fill = ColorRGBa(d.height, 1.0, d.tone, 1.0)
            if (d.round) drawer.circle(d.centre, d.w / 2.0)
            else drawer.rectangle(Rectangle.fromCenter(d.centre, d.w, d.h))
        }
    }

    /** The picture's ink box carried from its own pixels into the pane, where [drawPlate] fits it. */
    private fun placed(plate: Plate, ink: Rectangle): Rectangle {
        val fit = min(WIDE / plate.labels.width, HIGH / plate.labels.height)
        val x0 = WIDE / 2 - plate.labels.width * fit / 2
        val y0 = HIGH / 2 - plate.labels.height * fit / 2
        return Rectangle(x0 + ink.x * fit, y0 + ink.y * fit, ink.width * fit, ink.height * fit)
    }

    /**
     * The towers round the title, packed once and kept.
     *
     * **A module grid, filled from the largest size down.** The frame is a grid of [fieldUnit]
     * squares — 48 by 27 at 40 — and a square is taken by the title when it comes within
     * [fieldClear] of the ink. The sizes in [fieldSizes] are then laid largest first: every place a
     * size fits on free squares is tried in a shuffled order and taken [fieldTake] of the time, so
     * the big pieces are a scattering rather than a wall of them and the smaller sizes get room. The
     * last size is 1 by 1 and takes *every* free square left, so nothing is left open: the only
     * ground between towers is the one even [fieldGap], and at the title's edge at most one module.
     * Only square pieces may stand a circle, [fieldRound] of the time.
     *
     * They rise in a ripple out from the title: a tower's start is its distance from the title's
     * middle, so the field comes up after the type and spreads to the edges.
     */
    private fun fieldFor(drawer: Drawer, text: String, plate: Plate?): List<Tower> =
        fields.getOrPut(if (plate != null) "plate:${plate.labels}" else "text:$text") {
            val title = occupancy(drawer, text, plate)
            val random = Random(fieldSeed)
            val across = (WIDE / fieldUnit).toInt().coerceAtLeast(1)
            val down = (HIGH / fieldUnit).toInt().coerceAtLeast(1)
            // The module stretched a hair so the grid meets both edges of the frame exactly.
            val uw = WIDE / across
            val uh = HIGH / down
            val taken = BooleanArray(across * down) { i ->
                val cell = Rectangle((i % across) * uw, (i / across) * uh, uw, uh)
                title.touches(cell.offsetEdges(fieldClear - fieldGap / 2.0))
            }
            // Which squares the title took, kept apart from the ones towers take: the second pass
            // fills only these.
            val blocked = taken.copyOf()
            val placed = mutableListOf<Tower>()
            val centre = title.centre
            val farthest = listOf(Vector2(0.0, 0.0), Vector2(WIDE, 0.0), Vector2(0.0, HIGH), Vector2(WIDE, HIGH))
                .maxOf { (it - centre).length }

            // ---- the plan before the buildings: streets and gardens ------------------------ //
            //
            // An old town rather than a grid. A street is a walk across the modules: it goes a
            // module at a time, now and then turning left or right — never twice in a row, and never
            // back on itself — and it ends where it meets a street already laid, the frame's edge, or
            // the clear ground round the title. Avenues turn less and run further; streets start off
            // an avenue or the edge, so the plan grows as a network, and the blocks it leaves are
            // irregular, as a town built over time is. The squares a street takes stay open ground.
            val street = BooleanArray(across * down)
            val dirs = listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)
            fun inside(x: Int, y: Int) = x in 0 until across && y in 0 until down

            fun walk(x0: Int, y0: Int, d0: Int, turnChance: Double, most: Int): Int {
                var x = x0; var y = y0; var d = d0; var since = 0; var laid = 0
                while (laid < most && inside(x, y) && !blocked[y * across + x]) {
                    val i = y * across + x
                    if (street[i] && laid > 2) break         // joined the network
                    street[i] = true; taken[i] = true; laid++; since++
                    if (since > 2 && random.nextDouble() < turnChance) {
                        d = (d + if (random.nextBoolean()) 1 else 3) % 4
                        since = 0
                    }
                    x += dirs[d].first; y += dirs[d].second
                }
                return laid
            }

            fun edgeStart(): Triple<Int, Int, Int> = when (random.nextInt(4)) {
                0 -> Triple(0, random.nextInt(down), 0)
                1 -> Triple(random.nextInt(across), 0, 1)
                2 -> Triple(across - 1, random.nextInt(down), 2)
                else -> Triple(random.nextInt(across), down - 1, 3)
            }

            for (k in 0 until fieldAvenues) {
                val (x, y, d) = edgeStart()
                walk(x, y, d, 0.06, across + down)
            }
            for (n in 0 until fieldStreets) {
                // Off a street already laid, heading sideways from it, or in from the edge.
                val onStreet = (0 until across * down).filter { street[it] }
                for (tryAt in 0 until 30) {
                    val (x, y, d) = if (onStreet.isNotEmpty() && random.nextDouble() < 0.75) {
                        val at = onStreet[random.nextInt(onStreet.size)]
                        Triple(at % across, at / across, random.nextInt(4))
                    } else edgeStart()
                    val sx = x + dirs[d].first; val sy = y + dirs[d].second
                    if (!inside(sx, sy) || street[sy * across + sx]) continue
                    if (walk(sx, sy, d, 0.14, 8 + random.nextInt(14)) >= 4) break
                }
            }

            // Gardens: plots grown a module at a time from a seed, so they come out as irregular
            // shapes rather than rectangles, and drawn as one piece — no gap between their own
            // modules — flat on the ground in a tone of their own. Shadows lie across them and they
            // throw none.
            for (n in 0 until fieldGardens) {
                for (tryAt in 0 until 60) {
                    val seed0 = random.nextInt(across * down)
                    if (taken[seed0]) continue
                    val goal = 4 + random.nextInt(8)
                    val plot = mutableListOf(seed0)
                    val inPlot = mutableSetOf(seed0)
                    while (plot.size < goal) {
                        val edge = plot.flatMap { c -> dirs.map { (dx, dy) -> (c % across + dx) to (c / across + dy) } }
                            .filter { (x, y) -> inside(x, y) && !taken[y * across + x] && (y * across + x) !in inPlot }
                        if (edge.isEmpty()) break
                        val (x, y) = edge[random.nextInt(edge.size)]
                        plot += y * across + x; inPlot += y * across + x
                    }
                    if (plot.size < 3) continue
                    val out = (Vector2((seed0 % across + 0.5) * uw, (seed0 / across + 0.5) * uh) - centre).length / farthest
                    val start = fieldDelay + fieldSpread * (out + 0.08 * random.nextDouble())
                    for (c in plot) {
                        taken[c] = true
                        val x = c % across; val y = c / across
                        val g = fieldGap / 2.0
                        // Inset only on the plot's outside edges, so its modules read as one plot.
                        val l = if ((x - 1 + y * across) in inPlot && x > 0) 0.0 else g
                        val r = if ((x + 1 + y * across) in inPlot && x < across - 1) 0.0 else g
                        val t = if (((y - 1) * across + x) in inPlot) 0.0 else g
                        val b = if (((y + 1) * across + x) in inPlot) 0.0 else g
                        val cell = Rectangle(x * uw + l, y * uh + t, uw - l - r, uh - t - b)
                        placed += Tower(cell, false, height = 0.0, tone = fieldGardenTone, start = start)
                    }
                    break
                }
            }

            fun free(x: Int, y: Int, w: Int, h: Int): Boolean {
                if (x + w > across || y + h > down) return false
                for (yy in y until y + h) for (xx in x until x + w) if (taken[yy * across + xx]) return false
                return true
            }

            // With a picture, the field is made of the title's own pieces: each cell takes one whose
            // proportion is close to its own, as it stands or turned a quarter, picked among the
            // nearest few so the same piece does not stand in every cell of a size.
            val pieces = plate?.pieceShapes.orEmpty()
            val usable = pieces.indices.filter { (plate?.pieceSolidity?.getOrNull(it) ?: 0.0) >= fieldSolid }
            val solidity = plate?.pieceSolidity.orEmpty()
            fun pick(cell: Rectangle): Pair<Int, Boolean> {
                if (usable.isEmpty()) return -1 to false
                // A bar is a rectangle whatever it is stretched to, so it fills its cell exactly;
                // a curved piece may only stand where its own proportion nearly matches, since
                // it is stretched to the cell too and a half-round must stay round.
                val want0 = kotlin.math.ln(cell.width / cell.height)
                val fits = usable.flatMap { i ->
                    val a = kotlin.math.ln(pieces[i].width.toDouble() / pieces[i].height)
                    listOf(i to false, i to true).filter { (_, turned) ->
                        solidity.getOrElse(i) { 1.0 } >= SQUARE_CUT || kotlin.math.abs((if (turned) -a else a) - want0) < 0.12
                    }
                }
                if (fits.isEmpty()) return -1 to false
                // Proportion first, then size: a piece near its cell's size is scaled least.
                val want = kotlin.math.ln(cell.width / cell.height)
                val span = kotlin.math.ln(maxOf(cell.width, cell.height))
                val ranked = fits.map { (i, turned) ->
                    val pw = pieces[i].width.toDouble(); val ph = pieces[i].height.toDouble()
                    val a = kotlin.math.ln(pw / ph) * if (turned) -1.0 else 1.0
                    val size = 0.35 * kotlin.math.abs(kotlin.math.ln(maxOf(pw, ph)) - span)
                    Triple(i, turned, kotlin.math.abs(a - want) + size)
                }.sortedBy { it.third }.take(3)
                val chosen = ranked[random.nextInt(ranked.size)]
                return chosen.first to chosen.second
            }

            fun stand(x: Int, y: Int, w: Int, h: Int) {
                for (yy in y until y + h) for (xx in x until x + w) taken[yy * across + xx] = true
                val inset = Rectangle(x * uw, y * uh, w * uw, h * uh).offsetEdges(-fieldGap / 2.0)
                val (piece, turned) = pick(inset)
                val round = piece < 0 && w == h && random.nextDouble() < fieldRound
                val out = (inset.center - centre).length / farthest
                placed += Tower(inset, round, piece = piece, turned = turned,
                    height = (fieldLow + (fieldHigh - fieldLow) * random.nextDouble() +
                        fieldJitter * (random.nextDouble() * 2.0 - 1.0)).coerceIn(0.05, TALLEST_TOWER),
                    tone = fieldDark + (fieldLight - fieldDark) * random.nextDouble(),
                    start = fieldDelay + fieldSpread * (out + 0.08 * random.nextDouble()))
            }

            val sizes = fieldSizes.sortedByDescending { it.first * it.second }
            val spots = (0 until across * down).toMutableList()
            for ((k, size) in sizes.withIndex()) {
                val (w, h) = size
                val last = k == sizes.lastIndex
                spots.shuffle(random)
                for (i in spots) {
                    val x = i % across
                    val y = i / across
                    if (!free(x, y, w, h)) continue
                    if (!last && random.nextDouble() >= fieldTake) continue
                    stand(x, y, w, h)
                }
            }
            // Whatever the list of sizes, no square is left open.
            for (i in 0 until across * down) if (!taken[i]) stand(i % across, i / across, 1, 1)

            // The squares given up to the title, filled at the probe's grain. A module that comes
            // within reach of a letter is lost whole, so the band between two lines and the gaps
            // between words stood empty but for a row of scraps. Run down each module column
            // through those squares, and a clear run is a bar of exactly its own height; then run
            // along each module row for what is still clear, and that is a bar of its own width.
            // Nothing under `least` stands, so no sliver; the clearance round the title holds.
            val least = maxOf(fieldUnit * 0.35, 12.0)
            val extra = mutableListOf<Rectangle>()
            fun clearOfAll(r: Rectangle) = !title.touches(r.offsetEdges(fieldClear - fieldGap / 2.0)) &&
                extra.none { it.intersects(r) }
            fun bar(r: Rectangle) {
                extra += r
                val inset = r.offsetEdges(-fieldGap / 2.0)
                val out = (inset.center - centre).length / farthest
                placed += Tower(inset, false,
                    height = (fieldLow + (fieldHigh - fieldLow) * random.nextDouble() +
                        fieldJitter * (random.nextDouble() * 2.0 - 1.0)).coerceIn(0.05, TALLEST_TOWER),
                    tone = fieldDark + (fieldLight - fieldDark) * random.nextDouble(),
                    start = fieldDelay + fieldSpread * (out + 0.08 * random.nextDouble()))
            }
            fun runs(from: Double, to: Double, clear: (Double, Double) -> Boolean, place: (Double, Double) -> Unit) {
                var start = -1.0
                var at = from
                while (at < to) {
                    val step = minOf(PROBE, to - at)
                    if (clear(at, step)) { if (start < 0) start = at }
                    else { if (start >= 0 && at - start >= least) place(start, at); start = -1.0 }
                    at += step
                }
                if (start >= 0 && to - start >= least) place(start, to)
            }
            if (fieldFillGaps) for (cx in 0 until across) {
                var cy = 0
                while (cy < down) {
                    if (!blocked[cy * across + cx]) { cy++; continue }
                    val first = cy
                    while (cy < down && blocked[cy * across + cx]) cy++
                    runs(first * uh, cy * uh, { y, step -> clearOfAll(Rectangle(cx * uw, y, uw, step)) }) { a, b ->
                        bar(Rectangle(cx * uw, a, uw, b - a))
                    }
                }
            }
            if (fieldFillGaps) for (cy in 0 until down) {
                var cx = 0
                while (cx < across) {
                    if (!blocked[cy * across + cx]) { cx++; continue }
                    val first = cx
                    while (cx < across && blocked[cy * across + cx]) cx++
                    runs(first * uw, cx * uw, { x, step -> clearOfAll(Rectangle(x, cy * uh, step, uh)) }) { a, b ->
                        bar(Rectangle(a, cy * uh, b - a, uh))
                    }
                }
            }

            println("long shadow v2: ${placed.size} towers on a ${across}x$down grid round the title, of ${usable.size} of the title's pieces")
            placed
        }

    /** Where the title stands, as a summed-area table over the probe, for asking any box at once. */
    private class Occupancy(val w: Int, val h: Int, val sums: IntArray, val centre: Vector2) {
        fun touches(box: Rectangle): Boolean {
            val x0 = (box.x / PROBE).toInt().coerceIn(0, w); val x1 = ((box.x + box.width) / PROBE).toInt().plus(1).coerceIn(0, w)
            val y0 = (box.y / PROBE).toInt().coerceIn(0, h); val y1 = ((box.y + box.height) / PROBE).toInt().plus(1).coerceIn(0, h)
            if (x1 <= x0 || y1 <= y0) return false
            val s = sums[y1 * (w + 1) + x1] - sums[y0 * (w + 1) + x1] - sums[y1 * (w + 1) + x0] + sums[y0 * (w + 1) + x0]
            return s > 0
        }
    }

    /**
     * The whole title, standing, drawn into the probe and read back. Type and picture go through
     * the same drawing the card uses, so the field keeps clear of exactly what is on the card.
     * Which way up the read comes back is settled against the title's own box rather than assumed.
     */
    private fun occupancy(drawer: Drawer, text: String, plate: Plate?): Occupancy {
        drawer.isolatedWithTarget(probe) {
            drawer.ortho(probe)
            drawer.clear(ColorRGBa.TRANSPARENT)
            drawer.scale(1.0 / PROBE)
            if (plate != null) drawPlate(drawer, plate, Int.MAX_VALUE / 4)
            else plan(drawer, text, Int.MAX_VALUE / 4) { ColorRGBa(1.0, 1.0, 1.0, 1.0) }
        }
        val w = probe.width; val h = probe.height
        val shadow = probe.colorBuffer(0).shadow
        shadow.download()
        val raw = BooleanArray(w * h) { shadow[it % w, it / w].g > 0.02 }
        shadow.destroy()
        // The read's rows may run bottom up; the title's own box says which.
        val expected = if (plate != null) plate.bounds?.let { placed(plate, it) } else titleBox(text)
        var sy = 0.0; var n = 0
        for (i in raw.indices) if (raw[i]) { sy += i / w; n++ }
        val flip = expected != null && n > 0 &&
            kotlin.math.abs((h - 1 - sy / n) * PROBE - expected.center.y) < kotlin.math.abs((sy / n) * PROBE - expected.center.y)
        val sums = IntArray((w + 1) * (h + 1))
        var cx = 0.0; var cy = 0.0
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                val yy = if (flip) h - 1 - y else y
                val on = raw[yy * w + x]
                if (on) { row++; cx += x; cy += y }
                sums[(y + 1) * (w + 1) + x + 1] = sums[y * (w + 1) + x + 1] + row
            }
        }
        val centre = if (n > 0) Vector2((cx / n + 0.5) * PROBE, (cy / n + 0.5) * PROBE) else Vector2(WIDE / 2, HIGH / 2)
        return Occupancy(w, h, sums, centre)
    }

    /** The title's own clock: held back by [titleDelay] when the field comes first. */
    private fun titleFrame(frame: Int) = if (fieldOrder == "before") frame - frames(titleDelay) else frame

    /**
     * The field's clock. After the title, it starts when the last of the title has started to rise,
     * so the type stands highest at every frame; before it, it starts with the card.
     */
    private fun fieldFrame(frame: Int, text: String, plate: Plate?) =
        if (fieldOrder == "before") frame else frame - frames(titleLastStart(text, plate))

    /**
     * When tower [t] starts to leave, in the field's clock, or null if it stays. Before the title,
     * it goes as the title comes in, in the same ripple it arrived in — outward from the type, so the
     * blocks nearest the letters make way first. After, it goes [fieldLeave] seconds after it is up.
     */
    private fun leaveAt(t: Tower): Double? = when {
        fieldOrder == "before" -> titleDelay + (t.start - fieldDelay)
        fieldLeave > 0.0 -> t.start + rise + fieldLeave
        else -> null
    }

    /** The field into the plan, each tower at its own rise, and sinking away again at its own leave. */
    private fun towers(drawer: Drawer, towers: List<Tower>, frame: Int, plate: Plate?) {
        drawer.stroke = null
        val riseFrames = frames(rise).coerceAtLeast(1)
        val leaveFrames = frames(fieldLeaveTime).coerceAtLeast(1)
        for (t in towers) {
            val r = ((frame - frames(t.start)).toDouble() / riseFrames).coerceIn(0.0, 1.0)
            if (r <= 0.0) continue
            // Leaving is rising undone, and only that: the tower gets shorter — its shadow drawing
            // in with it — keeping its roof's tone, and the moment it is flat with the floor it is
            // simply not drawn. No transparency anywhere: a block is there, at some height, or gone.
            val gone = leaveAt(t)?.let { at -> ((frame - frames(at)).toDouble() / leaveFrames).coerceIn(0.0, 1.0) } ?: 0.0
            val stay = 1.0 - gone * gone * (3.0 - 2.0 * gone)
            if (stay <= HIDE_BELOW) continue
            val up = (1.0 - (1.0 - r) * (1.0 - r) * (1.0 - r)) * stay
            val toneNow = t.tone
            val shape = plate?.pieceShapes?.getOrNull(t.piece)
            val square = (plate?.pieceSolidity?.getOrNull(t.piece) ?: 0.0) >= SQUARE_CUT
            if (shape != null && square) {
                // A piece that is a rectangle in the title is drawn as one here. Stretched from its
                // small cut-out, its one-pixel soft edge grew with it, and re-cut sharp that came
                // out as rounded corners — a box the title never had.
                drawer.fill = ColorRGBa(t.height * up, 1.0, toneNow, 1.0)
                drawer.rectangle(t.box)
                continue
            }
            if (shape != null) {
                // The piece fills its cell, turned when it was picked so: the pieces stand back to
                // back with only the gap between them, as the stencil's own do. Picking kept the
                // stretch to rectangles and to curved pieces of nearly the cell's proportion.
                val w = if (t.turned) t.box.height else t.box.width
                val h = if (t.turned) t.box.width else t.box.height
                piece.parameter("h", t.height * up)
                piece.parameter("tone", toneNow)
                drawer.shadeStyle = piece
                drawer.isolated {
                    drawer.translate(t.box.center)
                    if (t.turned) drawer.rotate(90.0)
                    drawer.image(shape, -w / 2.0, -h / 2.0, w, h)
                }
                drawer.shadeStyle = null
                continue
            }
            drawer.fill = ColorRGBa(t.height * up, 1.0, toneNow, 1.0)
            if (t.round) drawer.circle(t.box.center, t.box.width / 2.0)
            else drawer.rectangle(t.box)
        }
    }

    // One of the title's pieces into the plan: its own coverage, this tower's height and tone.
    // Alpha is the coverage; the driver multiplies it in, so the plan stays premultiplied.
    private val piece = shadeStyle {
        // The edge is re-cut at half coverage over a pixel's width, so a piece scaled up from a
        // small cut-out keeps a clean outline rather than the soft edge of a stretched bitmap.
        fragmentTransform = """
            float c = x_fill.a;
            float d = max(fwidth(c), 0.0001);
            c = smoothstep(0.5 - d, 0.5 + d, c);
            x_fill = vec4(p_h, 1.0, p_tone, c);
        """
    }

    // The picture's blocks into the height mask, or as roofs: red of the plate is the block's place
    // in the order times coverage, green the coverage, so a block's own beat survives the filtering
    // at its edge. Same rise as the words.
    private val blocks = shadeStyle {
        fragmentTransform = """
            vec4 l = texture(p_plate, va_texCoord0);
            float cov = l.g;
            float order = cov > 0.001 ? l.r / cov : 0.0;
            float t = clamp((p_time - order * p_spread) / p_rise, 0.0, 1.0);
            float h = 1.0 - (1.0 - t) * (1.0 - t) * (1.0 - t);
            float up = t > 0.0 ? 1.0 : 0.0;
            // Alpha is the coverage and the driver multiplies it in on the way out, so the plan ends
            // up premultiplied like the type: a soft edge leaves the tower under it standing.
            x_fill = vec4(h, 1.0, 1.0, cov * up);
        """
    }

    private fun drawPlate(drawer: Drawer, plate: Plate, frame: Int) {
        blocks.parameter("plate", plate.labels)
        blocks.parameter("time", seconds(frame))
        blocks.parameter("spread", spread)
        blocks.parameter("rise", rise)
        val fit = min(WIDE / plate.labels.width, HIGH / plate.labels.height)
        val shown = Rectangle.fromCenter(Vector2(WIDE / 2, HIGH / 2),
            plate.labels.width * fit, plate.labels.height * fit)
        drawer.shadeStyle = blocks
        drawer.image(plate.labels, shown.corner.x, shown.corner.y, shown.width, shown.height)
        drawer.shadeStyle = null
    }

    // The mask into reach: red the pane pixels of shadow this pixel throws, -1 where no tower
    // stands; green the coverage, carried along so the shadow's sides stay antialiased.
    private val raise = shadeStyle {
        fragmentTransform = """
            vec4 m = texture(p_source, va_texCoord0);
            float h = m.g > 0.001 ? m.r / m.g : 0.0;
            x_fill = vec4(m.g > 0.001 ? h * p_reach : -1.0, m.g, 0.0, 1.0);
        """
    }

    // One pass: what the pixel `step` back toward the sun has left, less the step.
    private val cast = shadeStyle {
        fragmentTransform = """
            vec2 uv = va_texCoord0;
            vec4 here = texture(p_source, uv);
            vec2 back = uv - p_offset / p_pane;
            vec4 there = (back.x < 0.0 || back.y < 0.0 || back.x > 1.0 || back.y > 1.0)
                    ? vec4(-1.0, 0.0, 0.0, 1.0) : texture(p_source, back);
            float left = there.r - p_step;
            x_fill = vec4(max(here.r, left), max(here.g, left >= 0.0 ? there.g : 0.0), 0.0, 1.0);
        """
        parameter("pane", Vector2(WIDE, HIGH))
    }

    private fun pass(drawer: Drawer, into: RenderTarget, from: ColorBuffer) {
        drawer.isolatedWithTarget(into) {
            drawer.ortho(into)
            drawer.clear(ColorRGBa.TRANSPARENT)
            drawer.image(from, 0.0, 0.0, WIDE, HIGH)
        }
    }

    /** The reach field under a sun whose shadows run along [direction], [reach] per full tower. */
    private fun shadow(drawer: Drawer, direction: Vector2, reach: Double): ColorBuffer {
        raise.parameter("source", mask.colorBuffer(0))
        raise.parameter("reach", reach)
        drawer.shadeStyle = raise
        pass(drawer, ping, mask.colorBuffer(0))

        val steps = mutableListOf<Double>()
        var covered = 0.0
        var step = 1.0
        while (covered + step <= longest) {
            steps += step; covered += step; step *= 2.0
        }
        if (longest > covered) steps += longest - covered

        var from = ping.colorBuffer(0)
        drawer.shadeStyle = cast
        steps.forEachIndexed { i, a ->
            val to = if (i % 2 == 0) pong else ping
            // Render targets are drawn y-down and read y-up, so the y of the offset turns.
            cast.parameter("offset", Vector2(direction.x * a, -direction.y * a))
            cast.parameter("step", a)
            cast.parameter("source", from)
            pass(drawer, to, from)
            from = to.colorBuffer(0)
        }
        drawer.shadeStyle = null
        return from
    }

    // The reach field laid on the paper as shade.
    //
    // A pixel is in shadow when some tower's shadow still has more height left over it than the
    // pixel stands at itself: the reach field holds the most ground distance any shadow has to
    // go here, and a roof h high is under it when that is more than h * reach. On the ground h is
    // 0, which is the plain test; on a roof it is what lets a taller tower shade a lower one,
    // while a roof's own reach — exactly its height — leaves it lit. The pixel of margin keeps a
    // roof from shading itself on the rounding.
    private val lay = shadeStyle {
        fragmentTransform = """
            vec4 f = texture(p_field, va_texCoord0);
            vec4 m = texture(p_plan, va_texCoord0);
            float cov = m.g;
            float h = cov > 0.001 ? m.r / cov : 0.0;
            float tone = cov > 0.001 ? m.b / cov : 0.0;
            vec3 roof = mix(p_paper.rgb, p_ink.rgb, clamp(tone, 0.0, 1.0));
            float ground = f.g * smoothstep(-0.5, 0.5, f.r) * p_shadowOn;
            float over = f.g * smoothstep(0.5, 1.5, f.r - h * p_reach) * p_shadowOn;
            // The window's grid ruled on the ground: a line a pixel wide at every cell edge.
            vec2 px = va_texCoord0 * p_pane;
            px.y = p_pane.y - px.y;
            vec2 g = abs(fract(px / p_cell + 0.5) - 0.5) * p_cell;
            float onLine = 1.0 - smoothstep(0.5, 1.2, min(g.x, g.y));
            // The line is laid over the shadow too, softer there, so the grid runs on through it
            // rather than stopping at its edge.
            vec3 floorTone = mix(p_paper.rgb, p_shade.rgb, ground);
            floorTone = mix(floorTone, p_ink.rgb, onLine * p_gridLine * mix(1.0, p_gridInShadow, ground));
            vec3 roofTone = mix(roof, p_shade.rgb, over);
            x_fill = vec4(mix(floorTone, roofTone, clamp(cov, 0.0, 1.0)), 1.0);
        """
    }

    // The stone multiplied over the finished card. On the roofs it is taken against its own
    // average first, so the average reads as white and only what is darker — the pits and pour
    // marks — shows: the type stays white and is still concrete.
    private val grain = shadeStyle {
        fragmentTransform = """
            vec3 tone = texture(p_source, va_texCoord0).rgb;
            vec2 at = vec2(va_texCoord0.x, 1.0 - va_texCoord0.y) * p_pane / p_tile;
            vec3 grain = texture(p_stone, at).rgb;
            vec3 ground = mix(tone, grain * tone, p_mix);
            vec3 mean = textureLod(p_stone, vec2(0.5), 20.0).rgb;
            vec3 marks = min(grain / max(mean, vec3(0.01)), vec3(1.0));
            vec3 top = tone * mix(vec3(1.0), marks, p_roofMix);
            float roof = clamp(texture(p_roofs, va_texCoord0).g, 0.0, 1.0);
            x_fill = vec4(clamp(mix(ground, top, roof), 0.0, 1.0), 1.0);
        """
        parameter("pane", Vector2(WIDE, HIGH))
        parameter("mix", concreteMix)
        parameter("roofMix", roofMix)
    }

    /** A picture of a title read into blocks: [labels] holds each block's place in the order. */
    class Plate(
        val labels: ColorBuffer, val pieces: Int, val bounds: Rectangle? = null,
        /** Every piece of ink cut out on its own, coverage in alpha, for the field to stand again. */
        val pieceShapes: List<ColorBuffer> = emptyList(),
        /** How much of its own box each piece fills: 1 for a bar, far less for a stencil N. */
        val pieceSolidity: List<Double> = emptyList(),
        /** Each piece's box in the picture's pixels, and its place in the reading order, 0 to 1. */
        val pieceBoxes: List<Rectangle> = emptyList(),
        val pieceOrder: List<Double> = emptyList(),
        /** For a title built of blocks: 0 a block, 1 a circle. Empty for a picture's own pieces. */
        val pieceKinds: List<Int> = emptyList()
    )

    private companion object {
        /** The pane the card composes for; [draw] fits it into whatever bounds it is given. */
        const val WIDE = 1920.0
        const val HIGH = 1080.0
        /** A piece filling this much of its box counts as a rectangle and may be stretched freely. */
        const val SQUARE_CUT = 0.93
        /** A leaving tower this share of its height or less is hidden rather than drawn nearly flat. */
        const val HIDE_BELOW = 0.04
        /** The tallest a tower of the field may stand, as a share of the title: always under it. */
        const val TALLEST_TOWER = 0.95
        /** Pane pixels to one pixel of the probe the packing reads the title's outline off. */
        const val PROBE = 4.0
        /** The size the face is baked at; the fit scales it to the pane. */
        const val EM = 160.0
    }
}

/**
 * [file] read into blocks of ink, each with its own place in the order the blocks rise in.
 *
 * **Ink is whichever the picture has less of**, so black-on-white and white-on-black both read as
 * letters on paper. **A block is a connected run of ink**, flood-filled on anything more than a
 * trace, so a letter's antialiased edge stays with its letter. Anything touching the picture's edge
 * is dropped — Figma leaves a 1px grey line round every export, which would otherwise rise as one
 * enormous tower framing the card — and so is anything under 30 pixels.
 *
 * **The order reads the picture as a page**: lines top to bottom, blocks left to right, then
 * [scatter] of a shuffle from a fixed seed blended in, so a picture always rises the same way. It
 * is stored multiplied by coverage, so filtering at a block's edge does not drag its beat toward
 * the paper's.
 */
/** How much ink makes a pixel part of a piece for the flood fill; lighter pixels are edge. */
private const val CORE = 0.5f

private fun loadPlate(file: File, scatter: Double): LongShadowV2.Plate {
    val img = ImageIO.read(file)
    val w = img.width
    val h = img.height
    val lum = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val argb = img.getRGB(x, y)
        val a = ((argb ushr 24) and 255) / 255f
        val r = ((argb shr 16) and 255) / 255f
        val g = ((argb shr 8) and 255) / 255f
        val b = (argb and 255) / 255f
        // Composited over white, so a transparent png reads as paper.
        lum[y * w + x] = a * (0.2126f * r + 0.7152f * g + 0.0722f * b) + (1f - a)
    }
    val dark = lum.average() > 0.5
    val raw = FloatArray(w * h) { if (dark) 1f - lum[it] else lum[it] }
    // The picture's own solid ink is full coverage. These titles are drawn in a dark grey rather
    // than black, so the solidest ink read 0.83: every piece measured three quarters full, not
    // one counted as a rectangle, and the title itself went down a shade translucent.
    val full = raw.filter { it > 0.02f }.sorted().let { if (it.isEmpty()) 1f else it[(it.size * 0.99).toInt().coerceAtMost(it.size - 1)] }
        .coerceAtLeast(0.05f)
    val cov = FloatArray(w * h) { (raw[it] / full).coerceIn(0f, 1f) }

    val label = IntArray(w * h) { -1 }
    class Block(var count: Int = 0, var sx: Double = 0.0, var sy: Double = 0.0,
                var bottom: Int = 0, var edge: Boolean = false,
                var left: Int = Int.MAX_VALUE, var right: Int = -1, var top: Int = Int.MAX_VALUE,
                var coreBox: IntArray? = null)
    val found = mutableListOf<Block>()
    val stack = IntArray(w * h)
    for (start in 0 until w * h) {
        if (label[start] >= 0 || cov[start] < CORE) continue
        val id = found.size
        val block = Block()
        found += block
        var top = 0
        stack[top++] = start
        label[start] = id
        while (top > 0) {
            val p = stack[--top]
            val x = p % w
            val y = p / w
            block.count++; block.sx += x; block.sy += y
            block.bottom = maxOf(block.bottom, y)
            block.top = minOf(block.top, y); block.left = minOf(block.left, x); block.right = maxOf(block.right, x)
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) block.edge = true
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val q = ny * w + nx
                if (label[q] < 0 && cov[q] >= CORE) { label[q] = id; stack[top++] = q }
            }
        }
    }

    // Each piece's solid core, boxed before the edge is added: what its shape is measured on.
    for (b in found) b.coreBox = intArrayOf(b.left, b.top, b.right, b.bottom)

    // The soft edge back onto the pieces. Connected on solid ink only, a stencil's pieces stand
    // apart across its hairline gaps, which are grey rather than white once antialiased — joined
    // on any trace of ink they merged, and 20 letters came out as 64 lumps. Each pass hands a
    // still unclaimed pixel of edge to a piece it touches, so every piece keeps its own soft edge
    // and the gaps stay open between them.
    repeat(3) {
        val before = label.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val p = y * w + x
            if (before[p] >= 0 || cov[p] <= 0.02f) continue
            val n = listOf(if (x > 0) before[p - 1] else -1, if (x < w - 1) before[p + 1] else -1,
                if (y > 0) before[p - w] else -1, if (y < h - 1) before[p + w] else -1).firstOrNull { it >= 0 } ?: continue
            label[p] = n
            val b = found[n]
            b.left = minOf(b.left, x); b.right = maxOf(b.right, x); b.top = minOf(b.top, y); b.bottom = maxOf(b.bottom, y)
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) b.edge = true
        }
    }

    val kept = found.indices.filter { !found[it].edge && found[it].count >= 30 }
    // Lines: a block starts a new line when its centre is below the bottom of the line so far.
    val line = IntArray(found.size)
    var current = 0
    var bottom = -1
    for (k in kept.sortedBy { found[it].sy / found[it].count }) {
        val cy = found[k].sy / found[k].count
        if (bottom >= 0 && cy > bottom) { current++; bottom = found[k].bottom }
        bottom = maxOf(bottom, found[k].bottom)
        line[k] = current
    }
    val reading = kept.sortedWith(compareBy({ line[it] }, { found[it].sx / found[it].count }))
    val random = Random(7)
    val n = reading.size
    val order = FloatArray(found.size)
    reading.mapIndexed { i, k -> k to (i.toDouble() / maxOf(1, n - 1)) * (1 - scatter) + random.nextDouble() * scatter }
        .sortedBy { it.second }
        .forEachIndexed { i, (k, _) -> order[k] = (i.toDouble() / maxOf(1, n - 1)).toFloat() }
    val keep = BooleanArray(found.size).also { a -> kept.forEach { a[it] = true } }

    val buffer = ByteBuffer.allocateDirect(w * h * 4 * 4).order(ByteOrder.nativeOrder())
    // Written bottom row first, which is the order a texture's rows are uploaded in.
    for (y in h - 1 downTo 0) for (x in 0 until w) {
        val p = y * w + x
        val id = label[p]
        val c = if (id >= 0 && keep[id]) cov[p] else 0f
        buffer.putFloat(if (c > 0f) order[id] * c else 0f)
        buffer.putFloat(c)
        buffer.putFloat(0f)
        buffer.putFloat(1f)
    }
    buffer.rewind()
    val labels = colorBuffer(w, h, format = ColorFormat.RGBa, type = ColorType.FLOAT32)
    labels.write(buffer)
    // The ink's own box in the picture's pixels, which is what the field keeps clear of.
    var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
    for (y in 0 until h) for (x in 0 until w) {
        val id = label[y * w + x]
        if (id >= 0 && keep[id]) { x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y) }
    }
    val bounds = if (x1 >= x0) Rectangle(x0.toDouble(), y0.toDouble(), (x1 - x0 + 1).toDouble(), (y1 - y0 + 1).toDouble()) else null
    // Each piece on its own: its box plus a pixel of margin, its own coverage only, so a neighbour
    // that falls inside the box is not carried along. Rows bottom first, as the plate's are.
    val shapes = kept.map { k ->
        val b = found[k]
        val bw = b.right - b.left + 1
        val bh = b.bottom - b.top + 1
        val bytes = ByteBuffer.allocateDirect(bw * bh * 4)
        for (yy in bh - 1 downTo 0) for (xx in 0 until bw) {
            val x = b.left + xx
            val y = b.top + yy
            val c = if (x in 0 until w && y in 0 until h && label[y * w + x] == k) cov[y * w + x] else 0f
            val v = (c.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            bytes.put(v); bytes.put(v); bytes.put(v); bytes.put(v)
        }
        bytes.rewind()
        colorBuffer(bw, bh).also { it.write(bytes) }
    }
    // How much of its box a piece fills, measured on its solid core against the core's own box.
    // The edge is soft over a couple of pixels here, so a box taken round the edge is loose on
    // every piece and a straight bar measured 0.88 — under the rectangle cut, drawn stretched
    // from its bitmap with the corners rounded off. On the core a bar is ~1 and a half-round 0.79.
    val solidity = kept.map { k ->
        val b = found[k]
        val c = b.coreBox ?: intArrayOf(b.left, b.top, b.right, b.bottom)
        b.count.toDouble() / ((c[2] - c[0] + 1) * (c[3] - c[1] + 1)).coerceAtLeast(1)
    }
    val boxes = kept.map { k -> val b = found[k]; Rectangle(b.left.toDouble(), b.top.toDouble(), (b.right - b.left + 1).toDouble(), (b.bottom - b.top + 1).toDouble()) }
    val readOrder = kept.map { k -> order[k].toDouble() }
    return LongShadowV2.Plate(labels, n, bounds, shapes, solidity, boxes, readOrder)
}
