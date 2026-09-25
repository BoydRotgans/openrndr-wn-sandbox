// ============================================================================ //
//  No `package` declaration, for the reason ObjectScene has none: it stands on
//  loadObjectSheet and SheetObject, which are in the default package. The file
//  belongs to backdrop-drawers/, which is a folder rather than a package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.ShadeStyle
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import org.openrndr.shape.triangulate
import slideshow.Arrival
import slideshow.Backdrop
import slideshow.Cut
import slideshow.Fade
import slideshow.ramp
import slideshow.Stage
import slideshow.Transition
import slideshow.Want
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.advanceOf
import slideshow.easeInOutCubic
import slideshow.frames
import slideshow.voiced
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The catalogue on a conveyor: precast pieces laid along a belt in flat colour, named, going
 * slowly by.
 *
 * The light wall of the evening, against [OpeningScene]'s black one — the room is in by now and
 * this is what stands behind the welcome and the first course. It is a **kind** of wall rather
 * than one occasion, and the show stands two of them up with different palettes: the drawing is
 * the same idea and the colour is what tells one moment from the next.
 *
 * **The belt is one strip, not a screen of separate objects.** Every piece is laid end to end a
 * fixed gap apart, the whole strip is offset by the clock, and what falls inside the frame is
 * drawn. So it never repeats within a pass — 112 pieces at this size is far more belt than the
 * frame can hold — and it crosses the two projectors as one continuous run rather than as two
 * halves doing the same thing.
 *
 * **Everything is a pure function of [Stage.frame]**: the offset is a multiplication and a
 * remainder, so the belt can be scrubbed, paused or filmed and stands in the same place at the
 * same frame — the rule every drawer in this deck is written to.
 *
 * **A piece is drawn at the belt's height, unless that would make it enormously long.** The
 * catalogue holds a hairline — `DRST_M24_1500` is 63 times wider than it is tall — and at belt
 * height that is a single piece thirty thousand pixels long, which would take a quarter of an
 * hour to pass and read as a bar rather than as a component. Anything wider than [widest] of the
 * frame is scaled down whole instead, keeping its proportions and losing height. That is the
 * asset being honest about itself rather than a fault in the layout.
 */
class ConveyorScene(
    override val name: String,
    /** The sheet, read by [loadObjectSheet]. `objects-front.svg` is the elevations. */
    private val sheet: File,
    /**
     * The register, lined up with the sheet by [alignedTo].
     *
     * It carries the **real size in millimetres**, which is what lets the belt draw the pieces
     * against one another rather than all at one height — see [smallest]. Without it every piece
     * is drawn at the row's height, and none of them can be named.
     */
    private val details: File? = null,
    /**
     * The shortest piece the belt will carry, in millimetres. Below it, nothing is drawn.
     *
     * **This is the price of true scale, and it is a real one.** The catalogue runs from a 1mm
     * shim to a 14.3m wall — a ratio of 14 259 — so with the tallest filling a row the shortest
     * is a fortieth of a pixel and *fifty of the 112 pieces are under seven pixels tall*. Flooring
     * the small ones does not rescue it either: a floor generous enough to see puts 72 of the 112
     * **at** the floor, so most of the belt is no longer to scale and the idea has been given up
     * to keep the pieces.
     *
     * So the belt carries the components instead of the fittings. At a metre it holds 45 pieces
     * across a 13x range and the smallest still reads at some 27 pixels — walls, beams, columns
     * and floors, which is what a conveyor in a precast plant would be carrying anyway. The
     * washers and anchor shoes are real catalogue items and they are simply too small to stand
     * beside a 14m wall at the same scale.
     *
     * 0 puts everything back on the belt, and with it the sub-pixel pieces.
     */
    private val smallest: Double = 1000.0,
    /** The colours the pieces are handed, in turn and round again. */
    private val palette: List<ColorRGBa>,
    /** The belt, and by default the whole wall — see [height]. */
    private val belt: ColorRGBa = ColorRGBa.BLACK,
    /**
     * What shows above and below the belt, where the belt is not the whole frame. It defaults
     * to the belt's own colour, so nothing shows.
     */
    private val paper: ColorRGBa = belt,
    /** The names, set on the pieces. */
    private val ink: ColorRGBa = ColorRGBa.WHITE,
    /**
     * How dark a piece's shadow falls on the one it laps over. 0 draws none.
     *
     * **It costs nothing on the ground because the ground is black.** The shadow is black too,
     * so it is invisible where it falls on the wall and only tells where it lands on another
     * piece — which is precisely the overlap it is there to describe. No mask, no second buffer,
     * no test for what is underneath: draw it and it appears only where there is something to
     * fall on.
     */
    private val shadow: Double = 0.5,
    /**
     * How far the shadow is thrown, as a fraction of a piece's height.
     *
     * Thrown **left**, against the stacking rather than with the travel: pieces are drawn in the
     * order they sit along the row, so the one on top is always the one to the right, and its
     * shadow has to fall to the left to land on its neighbour. It is the drawing order that
     * decides this, not which way the row happens to be running.
     */
    private val thrown: Double = 0.05,
    /** Whether the pieces carry their names at all. */
    private val labels: Boolean = false,
    /**
     * A concrete texture the whole wall is projected onto, fixed to the frame. Null draws flat.
     *
     * The same wall the opening scene stands on — see [concreteWall]. It does not travel with the
     * belt: the stone is nailed to the frame and the pieces pass over it, so a stack reads as
     * stock lit by a projector rather than as slabs with a pattern printed on them.
     */
    private val concrete: File? = null,
    /** How big one tile of that texture is drawn, against its own pixels. */
    private val concreteScale: Double = 1.0,
    /** How much of the texture reaches the pieces: 1 is all of it, 0 is flat colour. */
    private val concreteMix: Double = 1.0,
    /**
     * How many belts are stacked up the frame, each running against the one above it.
     *
     * Two is the default and is what makes it read as machinery rather than as a list going by:
     * a single belt is a queue, where two crossing in opposite directions is a plant. They share
     * one strip of pieces and start a share of it apart, so no two rows carry the same piece at
     * the same moment.
     */
    private val rows: Int = 2,
    /**
     * How much of the frame's height the belt takes. **1 is the whole of it, and the default.**
     *
     * A band with the wall showing above and below reads on paper — the draaiboek draws it that
     * way, as one row among several — but across a 3840x1080 wall it is a letterbox: two pale
     * strips and a grey stripe, and the eye takes the strips for a fault rather than for a
     * margin. Full height, the belt simply *is* the wall and the pieces travel across it.
     */
    private val height: Double = 1.0,
    /**
     * How tall a piece is drawn, against the share of the frame one row gets.
     *
     * **Exactly 1**: the rows meet, filling the height with no rule between them and no margin at
     * the top or the foot. What separates one row from the next is then the pieces' own edges —
     * their notches and steps — rather than a line of wall, which is what makes the whole frame
     * read as stock rather than as belts with the frame showing past them.
     *
     * Under 1 leaves a black rule between the rows; above it they ride over one another, which is
     * the one overlap this wall does not want. Pieces overlap along the row instead — see [gap] —
     * which is a different thing: a row is a run of stock lapping past itself, and the rows are
     * still rows.
     */
    private val piece: Double = 1.0,
    /**
     * The space between pieces along a row, as a fraction of a row's height.
     *
     * **Negative, so the pieces lap over one another.** It is the one place this wall overlaps:
     * across the row, never up it. Positive opens the belt out into separate components going
     * past on black, which is the other picture this drawer can make.
     */
    private val gap: Double = -0.14,
    /**
     * The most pieces that may be stacked in one place, one behind the next.
     *
     * **This is where the depth comes from.** Laid at one spacing the row is a line of things; in
     * runs of two to [stack], each set back a sliver from the one before, it becomes stock leaning
     * against itself — and the shadow each throws on the one behind does the rest. The run length
     * is hashed from where the run starts, so the same frame always draws the same wall.
     */
    private val stack: Int = 6,
    /**
     * How far a stacked piece is set back from the one in front, as a fraction of a row's height.
     *
     * A *sliver*: enough to see an edge and a shadow, not enough to read as spacing. Measured
     * against the row rather than the piece so a 3:1 slab and a square panel are set back by the
     * same amount and the stack keeps one rhythm.
     */
    private val stackStep: Double = 0.17,
    /** How wide a piece may get before it is scaled down whole, as a fraction of the frame. */
    private val widest: Double = 0.42,
    /**
     * How much of its own box a piece must fill before it is given a name.
     *
     * The names are set *on* the pieces, in white, which only works where there is a piece
     * under them. A fair part of the catalogue is hollow in elevation — a rail is a ring of
     * section — and a name set on one lies across the belt showing through its middle, which
     * is worse than leaving it unnamed.
     */
    private val named: Double = 0.45,
    /**
     * Seconds one piece takes to move up a place.
     *
     * **The belt indexes rather than runs.** Only one piece on a row is ever moving: it advances
     * a stride, opening a gap behind it, and the piece behind then closes that gap, and so on
     * down the row. A wave of those is one move each, after which every piece has advanced the
     * same stride and the row stands exactly as it did — so it loops, and the whole of it is a
     * function of the frame with nothing carried between them.
     *
     * A constant scroll was what this did first, and it reads as a picture being slid past. The
     * stepping reads as *machinery*: something is placing these, one at a time.
     */
    private val move: Double = 1.8,
    /** Seconds the row stands still between one piece's move and the next. */
    private val rest: Double = 1.0,
    /**
     * How much of that rest a piece may take at random before it moves, 0..1.
     *
     * **Without it the wall is clockwork.** Every row runs the same schedule off the same frame
     * count, so all five step at the same instant and stop at the same instant, and a thing that
     * heavy moving in perfect unison reads as a mechanism rather than as stock being handled.
     * Each row is given a phase of its own and each piece a delay of its own, both hashed from
     * where they are — so it is scattered but not random: the same frame always draws the same
     * wall.
     *
     * The delay is bounded by the rest, so a piece still finishes inside its own slot and every
     * piece still moves exactly once a wave. That is what keeps the row coming round to itself.
     */
    private val scatter: Double = 1.0,
    /**
     * How many places around the row are stepping at once.
     *
     * **One cascade is not enough, and the reason is spatial rather than mechanical.** A single
     * gap travelling the row is what an indexing conveyor really does, but the row is far longer
     * than the frame — fifteen pieces of which about five are on screen — so the moving piece is
     * out of shot two thirds of the time and the wall simply sits there. Several cascades, spaced
     * a share of the row apart, keep one of them in view: on screen it still reads as one piece
     * moving and then the one behind it, because the others are a screen's width away.
     */
    private val gaps: Int = 3,
    /** Which way the first row runs; rows alternate from there. */
    private val reversed: Boolean = false,
    private val fontPath: String = "data/fonts/default.otf",
    private val fontSize: Double = 34.0,
    /**
     * Seconds a row takes to come in, and to go out again.
     *
     * **Each row enters from the side it travels from and leaves towards the side it travels
     * to**, so the arrival is the belt already running rather than a picture being placed: a row
     * that runs left comes in from the right, and carries on out to the left when the scene is
     * done with.
     */
    private val entry: Double = 1.1,
    /** Seconds between one row arriving and the next, so they do not land as a block. */
    private val entryStep: Double = 0.14,
    /**
     * How the scene arrives — and, because the deck gives a handover the *arriving* slide's
     * transition, how long whatever it replaces has to leave in.
     *
     * A [slideshow.Fade] rather than a [Cut] for exactly that reason: with a cut there is no
     * handover at all, `Stage.exit` never ramps, and a scene has no time in which to take itself
     * off. The fade itself is barely seen — the rows are travelling while it runs.
     */
    // A cut: the belt is a new subject after the programme, and a fade is for two states of one thing.
    override val transition: Transition = Cut,
    /** What plays while the belt is up — a course's bed, when the belt stands in one. */
    override val sound: slideshow.Sound? = null,
    /**
     * Seconds of itself the belt writes down as MIDI, and so the length of the clip beside it.
     *
     * The belt indexes for ever and never ends, so it states how much of itself is written — see
     * [slideshow.MidiTimed.midiFrames]. Counted from the frame the wall comes up, so the rows
     * coming in are the head of the file.
     */
    private val midiWindow: Double = 60.0
) : Backdrop() {

    override val background: ColorRGBa get() = paper

    /** One piece on the belt: its outline, its proportion, and what the register calls it. */
    private class Item(val element: SheetObject, val detail: PieceDetail?) {
        val aspect = element.aspect.coerceIn(0.02, 200.0)

        /**
         * How much of its own box the piece actually fills — measured off the drawing, so it
         * is exact. It decides whether a name can be set on the piece at all: a good part of
         * the catalogue is hollow in elevation, and a rail like `HALFEN_38/17_L=15` is a ring
         * of section with nothing behind it.
         */
        val solidity: Double = run {
            val area = element.shapes.sumOf { shape ->
                triangulate(shape).chunked(3).sumOf { t ->
                    if (t.size < 3) 0.0 else kotlin.math.abs(
                        (t[1].x - t[0].x) * (t[2].y - t[0].y) -
                        (t[2].x - t[0].x) * (t[1].y - t[0].y)
                    ) / 2.0
                }
            }
            val box = element.bounds.width * element.bounds.height
            if (box > 0.0) (area / box).coerceIn(0.0, 1.0) else 0.0
        }
    }

    private var items: List<Item> = emptyList()
    private var face: FontImageMap? = null
    private var stoneStyle: ShadeStyle? = null

    init {
        require(palette.isNotEmpty()) { "a ConveyorScene needs at least one colour" }
    }

    override fun load(program: Program) {
        if (!sheet.isFile) {
            println("no sheet at ${sheet.path} — \"$name\" stands empty")
            return
        }
        val drawings = loadObjectSheet(sheet)
        val register = details?.let { loadPieceDetails(it) }?.alignedTo(drawings.size)
        if (details != null && register == null) {
            println("${details.name} does not line up with ${sheet.name} — \"$name\" runs unnamed")
        }
        val all = drawings.mapIndexed { i, element -> Item(element, register?.getOrNull(i)) }
        // Only the pieces big enough for a true scale to mean anything — see `smallest`. With
        // no register there are no real sizes to go on, so everything stays and is drawn at one
        // height.
        items = if (register == null) all
        else all.filter { (it.detail?.heightMm ?: 0.0) >= smallest }
        if (register != null && items.size < all.size) {
            println("\"$name\": ${all.size - items.size} pieces under ${smallest.toInt()}mm left off the belt")
        }
        stoneStyle = concreteWall(concrete, concreteScale, concreteMix, name)
        face = runCatching {
            program.loadFont(fontPath, fontSize, characterSet = TYPE_CHARACTERS, contentScale = 1.0)
        }.getOrNull()

        println(("\"$name\": ${sheet.name}, ${items.size} pieces, $rows belts, " +
                "a piece every %.2fs, %s").format(
            move + rest, if (register != null) "to scale" else "one size, no register"))
    }

    /**
     * The belt laid out on [field]: how big every piece is drawn and where it sits along the strip.
     *
     * **One layout, read by the picture and by the score alike**, so the file cannot drift from
     * what is on the wall — `MidiTimed`'s rule. It is a function of the field and nothing else.
     */
    private inner class Layout(val field: Rectangle) {
        val rowHeight = field.height / rows
        val tall = rowHeight * piece
        // **No margin at the top or the foot: the rows are spread across the whole height.** The
        // first sits on the top edge and the last on the bottom one, and what is left over is
        // shared between them — so with [piece] above 1 they overlap and the wall is stock all
        // the way up rather than a set of belts with the frame showing past them.
        val down = if (rows > 1) (field.height - tall) / (rows - 1) else 0.0

        // Where every piece sits along the strip, and how big it is drawn. A piece keeps the
        // row's height unless that would make it longer than `widest` of the frame, in which
        // case the whole thing comes down — proportions kept, height given up. One strip serves
        // every row; what differs is where each row is looking at it.
        // **Pieces are drawn against one another, not each to the row.** The tallest fills the
        // row and every other piece takes the share of it its real height deserves, so a column
        // shoe stands beside a wall at the size it really is. Without a register there is nothing
        // to scale by and they all take the row.
        val size: List<Pair<Double, Double>>
        val starts = DoubleArray(items.size)
        val strip: Double

        init {
            val pitch = rowHeight * gap
            val cap = field.width * widest
            val tallest = items.maxOfOrNull { it.detail?.heightMm ?: 0.0 } ?: 0.0
            size = items.map { item ->
                val real = item.detail?.heightMm ?: 0.0
                val h0 = if (tallest > 0.0 && real > 0.0) tall * (real / tallest) else tall
                val w = item.aspect * h0
                if (w <= cap) w to h0 else cap to cap / item.aspect
            }
            // Where every piece begins along the strip. Runs of them stack a sliver apart, then
            // the run ends and the next begins a normal pitch along — so the row is groups of
            // stock rather than a line of separate things.
            var along = 0.0
            var at = 0
            while (at < items.size) {
                val run = 1 + noise(at * 977) % stack.coerceAtLeast(1)
                val end = min(items.size, at + run)
                for (k in at until end) {
                    starts[k] = along
                    along += if (k < end - 1) tall * stackStep else size[k].first + pitch
                }
                at = end
            }
            strip = along
        }

        val repeats = if (strip > 0.0) ceil(field.width / strip).toInt().coerceAtLeast(0) else 0

        // One stride is the average pitch, so a wave of moves advances the row by exactly the
        // spacing it already has and it comes round to itself.
        val stride = strip / items.size

        fun bottom(row: Int) = field.corner.y + row * down + tall

        /**
         * How far [row] stands off the frame at [frame], in pixels: all of the frame before it
         * comes in, none once it is up.
         *
         * Coming in, and going out again. A row enters from the side it travels *from* and leaves
         * towards the side it travels *to*, so both are the belt running rather than a picture
         * being moved. The rows are staggered, or they land as one block.
         */
        fun slide(row: Int, frame: Int, exit: Double): Double {
            val came = easeInOutCubic(ramp(frame - row * frames(entryStep), frames(entry)))
            val going = easeInOutCubic(exit)
            val from = if (way(row) > 0.0) 1.0 else -1.0
            return from * (1.0 - came - going) * field.width
        }

        /** Where piece [i] of [row] begins along the strip at [frame], before the copies. */
        fun home(row: Int, i: Int, frame: Int): Double {
            // Alternate rows run against each other, and each starts a share of the strip
            // further along, so no two rows carry the same piece at the same moment.
            val lead = row * strip / rows
            return wrap(starts[i] - way(row) * (travel(row, i, frame) * stride) + lead, strip)
        }
    }

    /** Frames one piece takes to move up a place. */
    private val moveFrames get() = frames(move).coerceAtLeast(1)
    /** A piece's move and the rest after it. */
    private val slot get() = moveFrames + frames(rest)
    // Pieces `period` apart step together, so every piece still moves exactly once a wave and the
    // row comes round to itself.
    private val period get() = ceil(items.size.toDouble() / gaps.coerceAtLeast(1)).toInt().coerceAtLeast(1)
    private val wave get() = slot * period

    /** Which way [row] runs: 1 is to the left, and rows alternate. */
    private fun way(row: Int) = if ((row % 2 == 0) != reversed) 1.0 else -1.0

    /** [frame] as [row] counts it: each row runs the same wave on a phase of its own. */
    private fun local(row: Int, frame: Int) = frame + noise(row * 7919) % wave

    /**
     * The frame within its row's wave on which piece [i] starts to move.
     *
     * **The wave runs with the motion, not against it**, and that is what makes a row's direction
     * readable. Stepping the leading piece first is what a real queue must do — it is the only one
     * with room ahead — but then the disturbance sweeps backwards while the pieces go forwards, and
     * the eye follows the disturbance: a row travelling left reads as moving right. These pieces
     * lap over one another, so there is no queue to respect, and the trailing piece can go first.
     */
    private fun begin(row: Int, i: Int): Int {
        val place = (if (way(row) > 0.0) items.size - 1 - i else i) % period
        // Its own slot, plus a delay of its own inside that slot's rest.
        val slack = ((slot - moveFrames) * scatter).coerceAtLeast(0.0)
        return place * slot + (noise(row * 131 + i * 17) % 1000 / 1000.0 * slack).toInt()
    }

    /** How many strides piece [i] of [row] has travelled by [frame]. */
    private fun travel(row: Int, i: Int, frame: Int): Double {
        val local = local(row, frame)
        val waves = local / wave
        val within = local % wave
        val begin = begin(row, i)
        val advance = when {
            within >= begin + moveFrames -> 1.0
            within >= begin -> easeInOutCubic((within - begin).toDouble() / moveFrames)
            else -> 0.0
        }
        return waves + advance
    }

    /** The wall the score is laid out on, as [layOut] tells it; null until then. */
    private var pane: Rectangle? = null

    /** The field the belt stands in on [bounds] — the whole of it, at the default [height]. */
    private fun fieldOf(bounds: Rectangle) = Rectangle(
        bounds.corner.x,
        bounds.corner.y + (bounds.height - bounds.height * height) / 2.0,
        bounds.width, bounds.height * height
    )

    override fun layOut(width: Int, height: Int) {
        pane = Rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
    }

    /** A belt that never ends says how much of itself is written down. */
    override val midiFrames: Int get() = frames(midiWindow)

    override val lanes: List<String> get() = listOf("belts in") + (0 until rows).map { row ->
        "belt ${row + 1}, running ${if (way(row) > 0.0) "left" else "right"}"
    }

    /**
     * The build as notes: each belt coming in, and then **every move a piece makes on the wall**.
     *
     * The moves are read off [begin] — the frame in its row's wave a piece sets off on, the very
     * number the draw loop steps it by — so a note sounds exactly as its piece starts to move and
     * lasts as long as the move does. A move made entirely off the frame is not a note: the row is
     * far longer than the wall, and a cascade out of shot is nothing anyone sees.
     *
     * **Pitch is where on the wall a piece moves, left lowest.** A cascade runs with its row, so a
     * belt running left is heard falling and one running right rising: the direction of each belt
     * is in the file as it is on the wall. The belts coming in are pitched by height, top highest.
     */
    override fun arrivals(clicks: List<Int>): List<Arrival> {
        val wall = pane
        if (wall == null || items.isEmpty() || rows < 1) return super.arrivals(clicks)
        val layout = Layout(fieldOf(wall))
        if (layout.strip <= 0.0) return super.arrivals(clicks)
        val field = layout.field
        val window = frames(midiWindow)
        val top = SCORE_NOTES - 1
        val wants = mutableListOf<Want>()

        for (row in 0 until rows) {
            val at = row * frames(entryStep)
            val middle = (layout.bottom(row) - layout.tall / 2.0 - field.corner.y) / field.height
            if (at <= window) wants += Want(
                0, ((1.0 - middle) * top).roundToInt().coerceIn(0, top), at,
                min(frames(entry), window - at).coerceAtLeast(1), 0, top
            )
        }

        for (row in 0 until rows) for (i in items.indices) {
            // The frames on which `local(row, f) % wave` is this piece's begin: once a wave.
            var start = Math.floorMod(begin(row, i) - noise(row * 7919) % wave, wave)
            while (start <= window) {
                seenAt(layout, row, i, start)?.let { across ->
                    wants += Want(
                        1 + row, (across * top).roundToInt().coerceIn(0, top), start,
                        // A move still under way as the window closes is cut off with it.
                        min(moveFrames, window - start).coerceAtLeast(1), 0, top
                    )
                }
                start += wave
            }
        }
        return voiced(wants)
    }

    /**
     * How far across the wall piece [i] of [row] is seen moving from [start], 0 at the left and 1
     * at the right, or null where the whole move is out of shot — off the frame, or outside the
     * row's own window while the row is still coming in.
     */
    private fun seenAt(layout: Layout, row: Int, i: Int, start: Int): Double? {
        val field = layout.field
        val w = layout.size[i].first
        for (k in (0 until moveFrames step SAMPLE) + moveFrames) {
            val frame = start + k
            val slide = layout.slide(row, frame, 0.0)
            val left = max(field.corner.x, field.corner.x + slide)
            val right = min(field.corner.x + field.width, field.corner.x + slide + field.width)
            val home = layout.home(row, i, frame)
            for (copy in -1..layout.repeats) {
                val x = field.corner.x + home + copy * layout.strip + slide
                val a = max(x, left)
                val b = min(x + w, right)
                if (b - a > 1.0) return ((a + b) / 2.0 - field.corner.x) / field.width
            }
        }
        return null
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        if (items.isEmpty() || rows < 1) return

        val field = fieldOf(stage.bounds)
        val layout = Layout(field)
        val tall = layout.tall
        val size = layout.size
        val strip = layout.strip
        if (strip <= 0.0) return
        val repeats = layout.repeats

        // The wall, and everything drawn from here is projected onto it: the pieces, their
        // shadows and the lettering alike. Set once for the whole frame rather than per piece.
        drawer.shadeStyle = stoneStyle

        drawer.isolated {
            fill = belt
            stroke = null
            rectangle(field)
        }

        for (row in 0 until rows) {
            val slide = layout.slide(row, stage.frame, stage.exit)
            val bottom = layout.bottom(row)

            // **The row is clipped to a window that travels with it**, and it has to be: the strip
            // wraps, so it tiles the plane, and simply translating it can never empty the frame —
            // another copy slides in behind. The window is what gives the row an edge to arrive
            // and leave by, and because the content is offset by the same amount the two move as
            // one slab of belt rather than as a wipe over a stationary picture.
            drawer.drawStyle.clip = Rectangle(
                field.corner.x + slide, bottom - tall, field.width, tall
            )

            items.forEachIndexed { i, item ->
                val (w, h) = size[i]
                val home = layout.home(row, i, stage.frame)
                // Enough copies of the strip to cover the frame whatever it is: with rows this
                // many and the pieces lapping, a strip can be shorter than the frame and a piece
                // then has to appear more than once across it.
                for (copy in -1..repeats) {
                    val x = field.corner.x + home + copy * strip + slide
                    if (x > field.corner.x + field.width || x + w < field.corner.x) continue

                    val box = Rectangle(x, bottom - h, w, h)
                    val bounds = item.element.bounds

                    /** The piece's outline, drawn at [at] in [paint]. */
                    fun cast(at: Vector2, paint: ColorRGBa) = drawer.isolated {
                        fill = paint
                        stroke = null
                        // the piece's own box, in sheet units, fitted to the box the belt gives it
                        translate(at)
                        scale(w / bounds.width, h / bounds.height)
                        translate(-bounds.center)
                        shapes(item.element.shapes)
                    }

                    // Its shadow first, so it lies under this piece and over the one it laps.
                    if (shadow > 0.0) {
                        cast(
                            box.center + Vector2(-h * thrown, h * thrown * 0.45),
                            ColorRGBa.BLACK.opacify(shadow)
                        )
                    }
                    cast(box.center, palette[i % palette.size])
                    if (labels) name(drawer, box, item)
                }
            }
        }

        drawer.drawStyle.clip = null
        drawer.shadeStyle = null
    }

    /**
     * The piece's name, set inside its top-left corner.
     *
     * **Dropped rather than shrunk or clipped** wherever it will not sit properly: the type is
     * one size along the whole belt — a row of components all labelled the same way — and a name
     * set smaller on a narrow piece would read as a different kind of thing rather than as the
     * same catalogue. The belt carries pieces from a 5cm anchor to a 24m beam, so some are simply
     * too small to carry a word, and some are too *hollow* — see [named]. A name half off its own
     * edge, or lying across the belt through the middle of a section, is worse than no name.
     */
    private fun name(drawer: Drawer, box: Rectangle, item: Item) {
        val map = face ?: return
        val text = item.detail?.name?.uppercase() ?: return
        if (item.solidity < named) return

        // **Measured against [fontSize], never against the face's own `height`.** A FontImageMap
        // reports the atlas's metrics rather than the size it was asked for — the same trap as
        // `FontImageMap.size`, which is an em scale and not a point size — so an inset taken from
        // it came out a few pixels and jammed the name against the piece's top edge.
        val pad = fontSize * 0.9
        if (map.advanceOf(text) + pad * 2.0 > box.width) return
        if (box.height < fontSize * 3.2) return

        drawer.isolated {
            fontMap = map
            fill = ink
            stroke = null
            // Ranged from the top-left corner and sitting on its own cap height, so the name is
            // inside the piece rather than on its edge however tall the piece happens to be.
            drawer.text(text, box.corner.x + pad, box.corner.y + pad + fontSize * CAP)
        }
    }

    /** [value] brought into 0 until [span], for a belt that comes round again. */
    private fun wrap(value: Double, span: Double): Double {
        val m = value % span
        return if (m < 0.0) m + span else m
    }

    /** A cheap integer hash, so the scatter is a function of where a piece is and never of history. */
    private fun noise(n: Int): Int {
        var x = n * 1664525 + 1013904223
        x = x xor (x ushr 15)
        x *= 0x27d4eb2d
        x = x xor (x ushr 15)
        return x and 0x7fffffff
    }

    private companion object {
        /** How far a cap sits below the line's top, as a fraction of the size asked for. */
        const val CAP = 0.72

        /** The notes a lane spreads over: four octaves, the shadow mosaic's register. */
        const val SCORE_NOTES = 48

        /** Frames between the looks taken along a move to see whether it is in shot. */
        const val SAMPLE = 6
    }
}
