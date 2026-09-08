package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.TextSettingMode
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadFont
import org.openrndr.draw.renderTarget
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Slide
import slideshow.Stage
import slideshow.easeInOutCubic
import slideshow.frames
import slideshow.present
import slideshow.slideshow
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.round

/** What it says. Its size is not stated — see [TypeDemo01.load], which solves for it. */
private const val SENTENCE = "De wereld van bouwen"

/** Line height, as a multiple of the size. */
private const val LEADING = 1.15

/** The space between plates, in a grid and between the words of a line. */
private const val GAP = 28.0

/**
 * How many plates tall a grid scene aims to be — the one number that decides a grid.
 *
 * A plate is cut to its word, so the word's own proportion does the rest: from a target height
 * comes the cell width it implies, from that the number of columns that fit, and from those the
 * rows. "De" is compact and comes out 6 across; "wereld" is four and a half times as wide as it
 * is tall and comes out 2. Both fill the frame, and neither was stated.
 */
private const val DENSITY = 5

/** Where the baseline sits inside a line, as a fraction of the leading — [TypeBlock]'s own. */
private const val BASELINE = 0.78

/**
 * The two grounds: the paper the sentence stands on, and the plates on it. The words are white
 * on both, and `b` replaces the pair with black.
 *
 * Black mode is the literal reading of it — **both** grounds, so the plates stop being visible
 * at all and what is left is white words being cut by edges that are not there. It is a
 * different picture rather than a dimmed one: the words are felt to be clipped rather than seen
 * to be. Giving [BLACK_PLATE] a value off black brings the plates back.
 */
private val PAPER = ColorRGBa.fromHex("3D5AE0")
private val PLATE = ColorRGBa.fromHex("FF7A00")
private val BLACK_PAPER = ColorRGBa.BLACK
private val BLACK_PLATE = ColorRGBa.BLACK

/**
 * The size the sentence is measured at to solve for the size it is set at. Nothing is drawn at
 * it: a glyph's box is linear in the point size, so one measurement answers for every size.
 */
private const val REFERENCE = 100.0

/** A hair under what fits, so a rounding in that arithmetic costs margin and never an overflow. */
private const val FIT = 0.98

/**
 * Which way a word crosses its plate.
 *
 * Named for the way it travels; [entry] is the side it comes in from, which is the opposite
 * one — a word that goes UP waits under the plate and leaves over the top. That is the whole
 * of a direction: one vector, and the same [offsetAt] curve read along it.
 *
 * The travel is the plate's own size along that axis, and every plate here is cut to its own
 * word — so the same beats carry "bouwen" across its width and "De" across its height, and the
 * words move at visibly different speeds. That is the geometry rather than a setting: a word
 * has to clear its own plate to be off it.
 */
enum class Direction(val entry: Vector2) {
    UP(Vector2(0.0, 1.0)),
    RIGHT(Vector2(-1.0, 0.0)),
    DOWN(Vector2(0.0, -1.0)),
    LEFT(Vector2(1.0, 0.0))
}

/**
 * The pattern: a quarter turn along a line and a half turn down the lines.
 *
 * The point of it is what it guarantees rather than what it looks like — **no word ever moves
 * the same way as its neighbour**. The word beside it is at right angles, the word under it is
 * its opposite, so every touching pair is in visible disagreement and the sentence never falls
 * into a block all going one way.
 *
 * It also cuts across the wave rather than lying along it: the words that move together are a
 * ring out from the middle of the sentence, and no ring can hold two neighbours going the same
 * way, so what opens out from the centre is words leaving in every direction at once.
 */
private fun quarterTurns(column: Int, row: Int): Direction =
    Direction.entries[(column + 2 * row).mod(Direction.entries.size)]

/**
 * How long the wave takes to reach the last word from the first.
 *
 * A scene runs one animation; a word's delay is read off where it stands, so this is the only
 * number the sweep needs. Together with [RISE] it says how long a scene takes to complete —
 * `WAVE + RISE` — which is what every window on the reveal has to be wide enough to carry.
 *
 * **It is the beat of the piece, not a softening of it.** A word takes [RISE] to cross its
 * plate and the wave is twice that, so what you see is type landing after type rather than a
 * field sliding in together — and at 21 frames across the whole field, a grid of two dozen fills
 * in about a third of a second. Long enough to be a run of hits; short enough to be one hit.
 */
private val WAVE = frames(0.35)

/**
 * The exit: the only move with a length of its own, because it is the only one that is not the
 * reveal. Everything before it is one continuous uncovering; this is what takes the sentence
 * away again at the end.
 *
 * Frames rather than seconds, which is the deck's rule and not a preference: a slide is
 * scrubbed, paused, jumped into and recorded, and only a frame count gives the same picture
 * every time (see Timing.kt). It runs on [easeInOutCubic] — the deck's own ease, the one a click
 * travels on — so the sentence leaves at rest and never passes the place it is going.
 */
private val LEAVE = frames(0.25)

/**
 * **The whole piece is one reveal**, and this is how long it takes: 0 with nothing standing, 1
 * with the sentence complete.
 *
 * [WINDOWS] cuts it into the scenes. That is the difference between this and three animations
 * played in a row — there is one number here, and a scene is a *window on it* rather than a
 * thing with a life of its own. It is the same move as `stage.on(n)` in the deck, as `packBoxes`
 * in demo01 and `stateAt` in Decision: one number in, the whole picture out, no state kept.
 *
 * It also means what drives the number is a detail. It comes off the loop clock here; handed
 * `stage.position` instead, the very same piece would build under the arrow keys, three scenes
 * to a talk, and nothing in the drawing would change.
 */
private val REVEAL = frames(3.2)

/**
 * How much of the reveal is spent getting up to speed, and the same again slowing down.
 *
 * **The type never stops until it is home**, which is the whole shape of the piece: one move
 * from nothing to the finished sentence, with the scenes cut *under* it rather than each taking
 * a turn. So the curve cannot be an ease-in-out over the whole run — that is fastest in the
 * middle and would fling the second scene past in a third of the time the other two get. What is
 * wanted is the opposite: a steady rate through the cuts, with the ends rounded off so the piece
 * starts from rest and settles onto the sentence rather than stopping dead.
 *
 * [steady] is that curve. At 0.15 the middle 70% of the reveal runs at one speed, which is what
 * keeps the three scenes the lengths [WINDOWS] says they are.
 */
private const val RAMP = 0.15

/**
 * Where the cuts fall in the reveal: "De" for the first three tenths of it, "wereld" to six
 * tenths, the sentence the rest.
 *
 * **A window says when a scene holds the frame, and — because the reveal runs at a steady rate
 * through them — very nearly how far the type has come by the end of it.** Measured against
 * [steady] at [RAMP], the cuts land with the words 26% and 62% out of their plates: "De" never
 * more than a quarter uncovered, "wereld" picking that up and carrying it past half, the
 * sentence taking it the rest of the way. They are not exactly 0.3 and 0.6 because the ends of
 * the reveal are rounded off, and that is the right trade — the alternative is type that stops
 * dead on the sentence.
 *
 * **Nothing stands still until the sentence is whole.** A scene is a window on a move that is
 * already running: it cuts in as far out of its plates as the last one had got, keeps going at
 * the same rate, and is cut away mid-move. So the eye reads one thing being uncovered, with the
 * words and the layout changing under it, rather than three things arriving one after another.
 *
 * A window has to be at least `WAVE + RISE` long for its scene to finish arriving — 31 frames
 * of [REVEAL]'s 192, so 0.16 of it — and whatever it has beyond that is the scene standing
 * complete before the next one cuts in. The narrowest window here carries 57 frames: half a
 * second of type landing, then four tenths of it standing, then the cut. The [require] in the
 * class is that test, because a window too narrow does not fail, it just never quite arrives.
 */
private val WINDOWS = listOf(0.0, 0.3, 0.6, 1.0)

/**
 * The beat the sentence holds after the reveal has finished, before it leaves.
 *
 * The reason the run-up can be this quick. Everything before it is hits — a scene lands, stands
 * for a moment and is cut away — and an edit made only of hits has nowhere to arrive; this is
 * the arrival. It is nearly as long as an opening scene and its cut put together, which is what
 * makes the sentence read as the thing the other two were for.
 */
private val LAND = frames(1.2)

/** The black scene: the paper alone, before the sequence begins again. Short — it is a beat in
 * the edit, not a rest from it. */
private val BLACK_SCENE = frames(0.5)

/**
 * Four scenes on a loop, each a word to a render target and each word masking in and out of a
 * plate cut to it: the frame filled with "De", then with "wereld", then the sentence they come
 * from, then black.
 *
 * **A plate is the size of its word's ink, not of its line box**, which is what makes the same
 * three parts compose all three pictures: "van" is x-height and wide, "bouwen" carries an
 * ascender, "De" a capital, and each plate is exactly the box its own letters fill. A line box
 * would make them all one height with the short words floating in slack. [inkBounds] measures
 * where the glyphs actually land instead.
 *
 * That is the whole reason to render type to a target rather than draw it: a picture of a word
 * can be scaled, masked, sampled, tiled or fed to a shader as a texture, and none of that is
 * possible while it is a run of glyph quads. Here it is the masking — **the plates hold still
 * and the words move inside them**, coming in from one edge, holding, and leaving over the far
 * one. There is no clip to set and no mask to draw: a render target is already both, and the
 * letters are cut by the edges of the buffer they are drawn into.
 *
 * **Nothing about the layouts is stated in pixels.** A grid scene is [DENSITY] and the word's
 * own proportion, which between them give the columns, the rows and the size; the sentence
 * scene takes its breaking and its size from the deck's own [setToFit], the same search the
 * chapter cards use. Rewrite [sentence] and all four scenes follow — the grids are its own
 * first words.
 *
 * **The opening scenes cut; only the last one arrives.** A grid takes the reveal as far as its
 * window says, stands there for the rest of it, and is then simply gone — the next scene cuts in
 * on the very next frame, already as far out of its plates as the one before it had got. Nothing
 * is drawn to cover the cut and nothing needs to be: the two sides agree about the only thing
 * that carries across them, which is how far the type has come. The sentence, which is what all
 * of it is for, is the one that finishes the job and then leaves the way it came.
 */
class TypeDemo01(
    private val sentence: String = SENTENCE,

    /**
     * The words that get a scene to themselves first, in order — the sentence's own opening
     * words by default, so the piece introduces its material and then says it.
     */
    private val grids: List<String> = sentence.split(" ").filter { it.isNotBlank() }.take(2),

    /**
     * How many lines to set the sentence over, or null for however many let the type be biggest.
     *
     * The two are not the same, and this file inherits that argument from [setToFit]: "De wereld
     * van bouwen" maximised comes out over *two* lines, because a third costs more height than
     * the shorter measure wins back in width.
     */
    private val lines: Int? = null,

    /**
     * A word's direction, from where it sits. [quarterTurns] is the default; a version of the
     * piece all going one way is `TypeDemo01(pattern = { _, _ -> Direction.LEFT })`.
     */
    private val pattern: (column: Int, row: Int) -> Direction = ::quarterTurns,

    /**
     * The frame the type is solved against.
     *
     * Stated rather than read off the stage for the same reason [CityMapSlide] states it: the
     * sizes, the breaks and every plate have to be decided in [load], which runs before anything
     * knows how big a pane is, and deciding them on the first frame instead would put three font
     * loads and thirty-odd buffers on the click that brings the slide up. [draw] then centres a
     * scene in whatever pane it gets, so a pane of this shape composes exactly and a larger one
     * simply has more air.
     */
    private val pane: Rectangle = Rectangle(0.0, 0.0, 1920.0, 1080.0),

    /**
     * Black mode, on `b` — and stated here as well as toggled, because a filmed run has nobody
     * to press the key. `TypeDemo01(black = true)` is the black version of the piece.
     *
     * A slide is a pure function of its [Stage] and this does not change that: the boolean is
     * not in the animation — what is drawn at a phase is what was always drawn at that phase —
     * it only says which two colours it is drawn in. The engine's own `d` is the same kind of
     * switch, and [background] is read once a frame by the driver, so a getter is all it takes.
     *
     * The key is registered in [load] rather than in the engine, because `b` belongs to this
     * sketch and not to every deck. It takes no timestamp, which is the thing a key handler here
     * must not do — under `ScreenRecorder` a handler sees wall time while the draw loop sees
     * video time (the note under demo01 in CLAUDE.md).
     */
    private var black: Boolean = false
) : Slide() {
    override val background get() = if (black) BLACK_PAPER else PAPER

    /**
     * The reveal, the sentence holding, the sentence leaving, then black — and round again.
     *
     * The leaving is `WAVE + LEAVE` because the wave runs through the exit too: the word that
     * arrived last is also the last to go.
     */
    override val loop = REVEAL + LAND + WAVE + LEAVE + BLACK_SCENE

    init {
        require(WINDOWS.size == grids.size + 2) {
            "WINDOWS needs a boundary either side of every scene: ${grids.size + 2} for this one"
        }
        require(WAVE < REVEAL) {
            "the wave has to fit inside the reveal, or the word it reaches last is still short " +
                    "of home when the sentence is supposed to be whole"
        }
    }

    private lateinit var scenes: List<Scene>

    override fun load(program: Program) {
        program.keyboard.keyDown.listen { if (it.name == "b") black = !black }
        scenes = grids.map { gridScene(program, it) } + sentenceScene(program)
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        // One number, and everything below is a window on it. Past 1 the reveal is complete and
        // the sentence is standing; the frames after that are it leaving, and then the black.
        val elapsed = stage.loop * loop
        val reveal = (elapsed / REVEAL).coerceAtMost(1.0)
        val index = WINDOWS.indexOfFirst { reveal < it }.let { if (it < 0) scenes.size - 1 else it - 1 }
        val dark = elapsed >= REVEAL + LAND + WAVE + LEAVE

        // The scene puts its own paper down rather than the slide's `background` doing it: that
        // is read by the driver once a frame with no stage to take a phase from, so it cannot
        // know which scene is up. One rectangle either way.
        drawer.fill = if (black || dark) BLACK_PAPER else PAPER
        drawer.stroke = null
        drawer.rectangle(stage.bounds)
        if (dark) return

        val scene = scenes[index]

        // Rounded, and it matters: a plate is a buffer of exact pixels and blitting it at a
        // fractional position resamples the lot — every plate edge and every letter softened by
        // a filter, on a piece whose whole argument is that the box is cut to the ink. The
        // sentence block is 1554.12 wide in a 1920 frame, so centring it lands on .94 and
        // nothing is ever crisp. Whole pixels here cost half a pixel of centring and buy a 1:1
        // blit; the words still move subpixel *inside* their plates, which is where smooth
        // motion is wanted and where no resampling happens.
        val origin = Vector2(
            round(stage.center.x - scene.block.x / 2.0),
            round(stage.center.y - scene.block.y / 2.0)
        )

        for (word in scene.words) {
            val offset = offsetAt(elapsed, word.lead)

            // Nothing of the word is on the plate, so the plate is not there either — which is
            // what lets a scene change without a jump. It also means the empty beat is empty
            // paper rather than a bare plate: keeping the plate up through it is this one
            // condition, at the cost of the layouts visibly cutting between scenes.
            if (offset <= -1.0 || offset >= 1.0) continue

            val image = word.plate.colorBuffer(0)

            // The curve is a number; the direction is what turns it into a place. A word travels
            // its own plate along its own axis, so it is wholly off at 1 and -1 whichever way it
            // goes — and "bouwen" crossing its width covers four times what "De" does.
            val entry = word.direction.entry
            paint(drawer, word, Vector2(entry.x * image.width, entry.y * image.height) * offset)

            // White, because the fill tints an image and the driver hands `draw` a black one —
            // the plate and the word on it would both come out black without this. It is the
            // identity tint: what reaches the slide is the buffer's own colours.
            drawer.fill = ColorRGBa.WHITE
            drawer.image(image, origin.x + round(word.at.x), origin.y + round(word.at.y))
        }
    }

    /**
     * A scene that fills the frame with one word.
     *
     * One number goes in — a plate a [DENSITY]-th of the box tall — and the word's own
     * proportion decides everything else: the cell that height implies, how many of those fit
     * across, and then how many rows fit down at whatever height the columns actually leave. So
     * a compact word comes out in a dense grid and a long one in a few wide bands, and both fill
     * the frame without either being stated.
     */
    private fun gridScene(program: Program, word: String): Scene {
        val box = pane.offsetEdges(-MARGIN)
        val letters = word.toSet()
        val shape = program.loadFont(Type.file, REFERENCE, letters, contentScale = 1.0).inkBounds(word)
        val aspect = shape.width / shape.height

        val columns = round((box.width + GAP) / (box.height / DENSITY * aspect + GAP))
            .toInt().coerceAtLeast(1)
        val cell = (box.width - (columns - 1) * GAP) / columns
        val rows = floor((box.height + GAP) / (cell / aspect + GAP)).toInt().coerceAtLeast(1)

        val font = program.loadFont(
            Type.file, REFERENCE * (cell / shape.width) * FIT, letters, contentScale = 1.0
        )
        val ink = font.inkBounds(word)

        return assemble((0 until rows).flatMap { row ->
            (0 until columns).map { column ->
                Cell(
                    word, font, ink,
                    Vector2(column * (ink.width + GAP), row * (ink.height + GAP)), column, row
                )
            }
        })
    }

    /**
     * The sentence, set.
     *
     * The breaking and the size come from the deck's own [setToFit] — the same search the
     * chapter cards use, so it breaks here where it breaks there — and the words are then placed
     * on those lines by their own ink. **The words on a line sit on one baseline**, which is why
     * a plate's y comes from the ink box's own corner: that corner is measured from the pen, so
     * adding it to the baseline is what puts a capital and an x-height word on the same line
     * rather than aligning their boxes.
     */
    private fun sentenceScene(program: Program): Scene {
        val letters = sentence.toSet()
        val measure = program.loadFont(Type.file, REFERENCE, letters, contentScale = 1.0)
        val fitted = measure.setToFit(sentence, pane.offsetEdges(-MARGIN), REFERENCE, LEADING, lines)
        val size = REFERENCE * fitted.scale * FIT
        val font = program.loadFont(Type.file, size, letters, contentScale = 1.0)

        val space = font.advanceOf(" ")
        val line = LEADING * size
        val cells = mutableListOf<Cell>()

        fitted.lines.forEachIndexed { row, text ->
            val inks = text.split(" ").filter { it.isNotBlank() }.map { it to font.inkBounds(it) }
            val width = inks.sumOf { it.second.width } + space * (inks.size - 1).coerceAtLeast(0)
            var x = -width / 2.0                       // lines are centred on the block's axis
            val baseline = row * line + BASELINE * line

            inks.forEachIndexed { column, (word, ink) ->
                cells += Cell(word, font, ink, Vector2(x, baseline + ink.corner.y), column, row)
                x += ink.width + space
            }
        }
        return assemble(cells)
    }

    /**
     * Placed words into a scene: a plate cut to each one's ink, the block they cover, and the
     * delay each waits out before it crosses.
     *
     * **The block is measured by the ink**, not by the nominal cells or line boxes — with no
     * descender anywhere in this sentence the boxes would leave a strip of nothing along the
     * bottom, and the whole thing would sit high in the frame.
     *
     * A word's delay is how far it stands from the middle of the block, 0 there and 1 for the
     * farthest — each axis normalised by the block before the distance is taken, so a scene much
     * wider than it is tall still opens as a ring rather than as a band. The set is then divided
     * by its own largest, which is what keeps the sweep exactly [WAVE] long however the words
     * happen to fall.
     */
    private fun assemble(cells: List<Cell>): Scene {
        val left = cells.minOf { it.at.x }
        val top = cells.minOf { it.at.y }
        val block = Vector2(
            cells.maxOf { it.at.x + it.ink.width } - left,
            cells.maxOf { it.at.y + it.ink.height } - top
        )

        val from = cells.map {
            val centre = Vector2(
                (it.at.x - left + it.ink.width / 2.0) / block.x,
                (it.at.y - top + it.ink.height / 2.0) / block.y
            )
            hypot(centre.x - 0.5, centre.y - 0.5)
        }
        val furthest = (from.maxOrNull() ?: 0.0).coerceAtLeast(1e-6)

        return Scene(
            block = block,
            words = cells.mapIndexed { i, cell ->
                Word(
                    text = cell.text,
                    font = cell.font,
                    plate = renderTarget(
                        ceil(cell.ink.width).toInt(), ceil(cell.ink.height).toInt()
                    ) { colorBuffer() },
                    // `text` places the *pen*, not the ink: the baseline, at the start of the
                    // first glyph's advance. Minus the ink's own corner is what lands the
                    // top-left of the letters on the plate's origin.
                    home = Vector2(-cell.ink.corner.x, -cell.ink.corner.y),
                    at = Vector2(cell.at.x - left, cell.at.y - top),
                    direction = pattern(cell.column, cell.row),
                    lead = from[i] / furthest
                )
            }
        )
    }

    /** One plate, with its word [offset] pixels off home — towards the side it comes in from. */
    private fun paint(drawer: Drawer, word: Word, offset: Vector2) {
        drawer.isolatedWithTarget(word.plate) {
            ortho(word.plate)
            clear(if (black) BLACK_PLATE else PLATE)
            fontMap = word.font
            fill = ColorRGBa.WHITE

            // Subpixel, because the word is moving and PIXEL floors every glyph to a whole
            // pixel — which quantises a slow settle into 1px steps. It costs nothing at rest:
            // home is a whole pixel anyway, so the atlas still samples 1:1 and stays crisp.
            drawStyle.textSetting = TextSettingMode.SUBPIXEL

            text(word.text, word.home.x + offset.x, word.home.y + offset.y)
        }
    }
}

/** One scene of the sequence: the words it stands up, and the block they cover. How long it
 * holds the frame is not its own business — that is its window on the reveal, in [WINDOWS]. */
private class Scene(val words: List<Word>, val block: Vector2)

/** A word placed, before it has a plate — what [TypeDemo01.assemble] needs to cut one. */
private class Cell(
    val text: String,
    val font: FontImageMap,
    val ink: Rectangle,
    val at: Vector2,
    val column: Int,
    val row: Int
)

/** One word standing in a scene: its own plate, where that plate sits, and how it crosses. */
private class Word(
    val text: String,
    val font: FontImageMap,
    val plate: RenderTarget,
    /** The pen that lands the word's ink in the corner of its plate: where it rests. */
    val home: Vector2,
    /** The plate's top-left, relative to the block's own. */
    val at: Vector2,
    val direction: Direction,
    /** 0 for the word the wave opens on, 1 for the last it reaches. */
    val lead: Double
)

/**
 * Where one word stands, [elapsed] frames into the sequence: 1 waiting outside the edge it comes
 * in from, 0 home, -1 clear away past the far one. Which edges those are is the plate's
 * [Direction] and nothing to do with the curve.
 *
 * **One move, not one per scene.** A word is uncovering from the first frame of the piece to the
 * last frame of the reveal, at a rate that does not change while the scenes cut under it — which
 * is why nothing here knows which scene it is in. It only leaves once the reveal is finished and
 * [LAND] has been held.
 *
 * Its [lead] of the wave delays both, and the reveal is measured over `REVEAL - WAVE` so that
 * the word the wave reaches last still lands exactly as the reveal completes: the stagger comes
 * out of the run rather than being added to the end of it.
 */
private fun offsetAt(elapsed: Double, lead: Double): Double =
    if (elapsed >= REVEAL + LAND) -easeInOutCubic(span(elapsed - REVEAL - LAND - lead * WAVE, LEAVE))
    else 1.0 - steady(span(elapsed - lead * WAVE, REVEAL - WAVE))

/**
 * A ramp that gets up to speed over the first [RAMP] of itself, holds that speed, and slows to
 * rest over the last [RAMP]: 0 at 0, 1 at 1, and no standstill anywhere between.
 *
 * The integral of a trapezoid — constant acceleration, then constant speed, then constant
 * deceleration — normalised so the whole distance is covered. That middle stretch is the point:
 * an ease-in-out is a curve with no constant part at all, so the scene sitting over its middle
 * would be flung past while the scenes at its ends crawled. Here every scene is uncovered at the
 * same rate, and only the two ends of the *piece* are eased.
 */
private fun steady(t: Double, ramp: Double = RAMP): Double {
    val u = t.coerceIn(0.0, 1.0)
    val distance = when {
        u < ramp -> u * u / (2.0 * ramp)
        u <= 1.0 - ramp -> ramp / 2.0 + (u - ramp)
        else -> (1.0 - ramp) - (1.0 - u) * (1.0 - u) / (2.0 * ramp)
    }
    return distance / (1.0 - ramp)
}

/**
 * How far [elapsed] is into a move of [length] frames — counting the last frame as the end of
 * the move, rather than dividing by the length and leaving it one frame short.
 *
 * It used to matter: with a curve that finished steeply, dividing by the length left the last
 * drawn frame 14% short of clear — a band of letters taken off by the wrap rather than by the
 * move. [easeInOutCubic] ends at rest, so the shortfall is now a fraction of a pixel and nothing
 * turns on it. It stays because it is still what the arithmetic should say, and it costs a
 * subtraction.
 */
private fun span(elapsed: Double, length: Int): Double =
    if (length <= 1) 1.0 else (elapsed / (length - 1)).coerceIn(0.0, 1.0)

/**
 * The box the ink of [text] occupies, relative to the pen that draws it.
 *
 * This is the drawer's own arithmetic read back: the pen advances by each glyph's
 * `advanceWidth` plus the kerning against the one before it, and each glyph's bitmap is
 * laid down at the pen shifted by its own `xBitmapShift`/`yBitmapShift` — so the union of
 * those bitmaps is the ink. Neither [FontImageMap.characterWidth] nor the face's `height`
 * answers this: the first is one glyph's atlas box with no advance or kerning in it, and
 * the second is the line box, which is the same for every word set in the face.
 */
fun FontImageMap.inkBounds(text: String): Rectangle {
    var pen = 0.0
    var left = Double.POSITIVE_INFINITY
    var top = Double.POSITIVE_INFINITY
    var right = Double.NEGATIVE_INFINITY
    var bottom = Double.NEGATIVE_INFINITY

    text.forEachIndexed { i, c ->
        val metrics = glyphMetrics[c] ?: return@forEachIndexed
        if (i > 0) pen += kerning(text[i - 1], c)

        // A blank has metrics but no bitmap: it advances the pen and contributes no ink.
        map[c]?.let { box ->
            val x = pen + metrics.xBitmapShift / contentScale
            val y = metrics.yBitmapShift / contentScale
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x + box.width / contentScale)
            bottom = maxOf(bottom, y + box.height / contentScale)
        }
        pen += metrics.advanceWidth
    }
    return if (left.isFinite()) Rectangle(left, top, right - left, bottom - top)
    else Rectangle(0.0, 0.0, 0.0, 0.0)
}

/**
 * Runs this slide by itself:
 *
 *     ./gradlew run -Popenrndr.application=slideshow.drawers.TypeDemo01Kt
 *
 * `b` toggles black mode; the deck's own keys (`d` for the overlay, `esc` to quit) work here
 * as they do in the show, because this *is* the show, with one slide in it.
 *
 * To film a loop, wrap the show — the `SLIDES_*` keys cannot reach here, since [Env] is in the
 * default package and this file is not:
 *
 *     present(show.copy(settings = show.settings.copy(record = true, duration = 5.6)))
 *
 * with `TypeDemo01(black = true)` for the black version, because a filmed run has nobody to
 * press `b`. `ScreenRecorder` puts the clip in `video/` at the canvas size.
 *
 * A one-slide [slideshow] rather than an `application {}` of its own, so what is on screen
 * here is what the deck will show — and the engine takes a `Show` value with no `.env`
 * anywhere near it, which is exactly what a second launcher is for. It is also the light
 * way in: the committed show spends some ten seconds collecting and packing the city in
 * `load`, and this loads one font.
 *
 * To put it in the talk, add `slide(TypeDemo01())` to a chapter in `Slideshow.kt`.
 */
fun main() = present(
    slideshow {
        canvas(1920, 1080)
        window(0.6)
        title("typedemo01")
        slide(TypeDemo01())
    }
)
