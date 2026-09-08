// ============================================================================ //
//  No `package` declaration, deliberately, and for the same reason CityMapSlide
//  has none: this card is built out of the catalogue, and loadObjectTemplates,
//  mosaicField and fieldBuffer all live in the default package, which Kotlin
//  cannot import into a named one. The file still belongs in slide-drawers/,
//  which is a folder rather than a package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadFont
import org.openrndr.draw.parameter
import org.openrndr.draw.renderTarget
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Cut
import slideshow.Section
import slideshow.Slide
import slideshow.Stage
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.Type
import slideshow.drawers.TypeBlock
import slideshow.drawers.advanceOf
import slideshow.drawers.setToFit
import slideshow.frames
import slideshow.easeInOutCubic
import slideshow.seconds
import java.io.File
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The chapter card, set in the catalogue: the title is drawn into a black and white plate
 * nobody sees, and a standing field of Willy Naessens elements reads that plate and stands
 * wherever the words are.
 *
 * **The type is never drawn on the card at all**, and neither is the plate. What reaches the
 * pane is components. So a chapter is made of the same thing the rest of the talk is, and the
 * lettering keeps every property of [setToFit] — set to the frame, broken where it likes,
 * restated by editing the show — without any of it having to be reworked into shapes.
 *
 * **The picture moves; the field does not.** The plate is repainted every frame and the
 * elements read it every frame, so the type can do anything — drift, tile, mirror, breathe —
 * while the field itself is baked once and drawn in **one call**. That is what [mosaicField]
 * and [MOSAIC_FIELD] are for: every cell of the quadtree is in the buffer at every level, and
 * each works out for itself, per frame, whether it is the cell that stands here — *am I wholly
 * inside the ink, and did every cell above me straddle its edge* — which is exactly the rule
 * the CPU version walks.
 *
 * Two things follow, and both matter:
 *
 * - **The grain still traces the letters as they move.** A stroke is filled with a few big
 *   elements and its edge with a run of small ones, and that re-decides itself as the words
 *   travel — so the field is not a screen the type shows through, it is the type, re-packed
 *   every frame.
 * - **The ground is cut out by the words wherever they are.** A cell outside the ink stands in
 *   [ground] and a cell inside it in [ink], off the same sample, so a letter always carries its
 *   own edge against the field rather than sitting on top of one packed without it.
 *
 * The elements arrive **one at a time, each growing from its own centre** over [pop], the lot
 * of them over [reveal] — still one draw call, because a cell's place in the arrival is baked
 * into the buffer with it.
 *
 * **All of it happens in `load`.** A field of some tens of thousands of cells is a tenth of a
 * second or so; on the click that brings the card up it would be a hitch, and a card comes up
 * at the top of every chapter.
 *
 * **It composes for a stated pane rather than reading `stage.bounds`**, for the same reason
 * `CityMapSlide` does: the field is built before anything knows how big a pane is. [draw] fits
 * what was built into whatever pane it gets, centred.
 *
 * If the sheet is missing — `data/` is not committed — it falls back to setting the title as
 * ordinary type rather than failing, so the show still runs on a fresh clone.
 */
class ObjectChapterPanel(
    private val section: Section,
    private val fontPath: String = "data/fonts/default.otf",
    /** Break the chapter over exactly this many lines, rather than over however many let it be biggest. */
    private val lines: Int? = null,
    /** The catalogue the marks come from. */
    private val sheet: String = Env["SLIDES_CARD_SHEET"] ?: Env["CITY_OBJECT_SHEET"] ?: "data/svg/subset.svg",
    /** The cell every mark stands in, in pane pixels. */
    private val coarse: Double = Env["SLIDES_CARD_COARSE"]?.toDoubleOrNull() ?: 32.0,
    /**
     * How far a cell may quarter itself where it straddles the edge of a letter.
     *
     * **Equal to [coarse] by default, so every element on the card is one size** — the ground
     * included, which is the point: a field at one size reads as a set of components, where a
     * range of sizes reads as a picture made out of them. Set it below [coarse] and the packing
     * comes back: a stroke fills with big elements and its edge traces with small ones, and the
     * size of a mark then tells you where in the letter it is.
     *
     * It is also what the card costs, since every level is in the buffer: halving it quadruples
     * the cells.
     */
    private val finest: Double = Env["SLIDES_CARD_FINEST"]?.toDoubleOrNull() ?: coarse,
    /**
     * The cell's wide-to-high.
     *
     * **Left unset it is read off the sheet** — the median of its objects' own proportions —
     * rather than assumed, the same way the object sheets' grid is read off the file rather
     * than stated. It has to be: `subset.svg` runs 1.85 and `objects-iso.svg` 1.06, and an
     * element fitted into a cell of another proportion letterboxes, so naming a sheet without
     * this would quietly halve the ink. Set it to hold a shape against whatever the sheet says.
     */
    private val shape: Double? = Env["SLIDES_CARD_SHAPE"]?.toDoubleOrNull(),
    /** How much of its cell an element takes, so the rest of it is the air around the mark. */
    private val fill: Double = Env["SLIDES_CARD_FILL"]?.toDoubleOrNull() ?: 0.9,
    /**
     * Every element drawn at one height, rather than each fitted into its cell by its own
     * proportion — see [mosaicField]. On a field of one cell size the fitted version reads as
     * holes: a cell holding a 3.3:1 beam carries a third of the ink of one holding a panel, so
     * the words come apart. At one height every mark tells, and the widest run over their
     * neighbours, which is what one size costs.
     */
    private val uniform: Boolean = Env.boolean("SLIDES_CARD_UNIFORM", true),
    /**
     * What the elements standing outside the words are drawn in — the field the type moves
     * through. `SLIDES_CARD_GROUND=none` leaves the card bare and the words alone on it.
     */
    private val ground: ColorRGBa? = groundColour(Env["SLIDES_CARD_GROUND"]),
    /** What the words are picked out in, and what the card stands on. */
    private val ink: ColorRGBa = ColorRGBa.fromHex(Env["SLIDES_CARD_INK"] ?: "#FFFFFF"),
    override val background: ColorRGBa = ColorRGBa.fromHex(Env["SLIDES_CARD_PAPER"] ?: "#000000"),
    /**
     * How big an element standing outside the words is, against one standing inside them.
     *
     * **The size is what carries the letters**, not the colour: a mark swells as a word reaches
     * it and settles back as the word goes past, so the field breathes with the type instead of
     * simply changing tone under it. 1 leaves every mark the same size and hands the letters
     * back to the colour alone.
     */
    private val shrink: Double = Env["SLIDES_CARD_SHRINK"]?.toDoubleOrNull() ?: 0.3,
    /**
     * How much of a cell has to be inked for it to stand in [ink] rather than in [ground].
     *
     * It is what a coarse grid is steered with. A mark can only be as big as the stroke it has
     * to fill: at 18 pane pixels a stroke of this type carries two or three marks across and
     * the letters read, at 34 barely one, and past that no cell is ever wholly inside a stroke
     * and the words come apart into a scatter. Lowering this lights cells the letters only
     * clip, which puts the weight back — at the cost of the words growing into their own
     * counters, so it is a trade rather than a fix.
     */
    private val threshold: Double = Env["SLIDES_CARD_THRESHOLD"]?.toDoubleOrNull() ?: 0.32,
    /** Frames the elements take to arrive, one after another. */
    private val reveal: Int = frames(Env["SLIDES_CARD_REVEAL"]?.toDoubleOrNull() ?: 1.2),
    /** Frames one element takes to grow from nothing to its size. */
    private val pop: Int = frames(Env["SLIDES_CARD_POP"]?.toDoubleOrNull() ?: 0.3),
    /**
     * Seconds the drift takes to come all the way back around, so a clip of that length loops.
     * 0 holds the title still, which is the card as a title card.
     */
    private val period: Int = frames(Env["SLIDES_CARD_PERIOD"]?.toDoubleOrNull() ?: 40.0),
    /**
     * Seconds a scatter holds before the words are dealt again. 0 sets the sentence as a block.
     *
     * **The words do not travel between places, they are simply somewhere else.** A word
     * sliding from one spot to the next draws the eye along the path and turns the card into a
     * movement; appearing reads as a setting rather than as a move, and the field re-packs
     * under it in one frame either way.
     */
    private val beat: Int = frames(Env["SLIDES_CARD_BEAT"]?.toDoubleOrNull() ?: 2.0),
    /**
     * Seconds one word takes to reach its new place.
     *
     * Short against the [beat], and that is the whole feel of it: the card is a still
     * arrangement that **readjusts** now and then, rather than an animation that happens to
     * pause. A move long enough to watch turns the card into a thing that is moving.
     */
    private val move: Int = frames(Env["SLIDES_CARD_MOVE"]?.toDoubleOrNull() ?: 0.4),
    /**
     * How far a word may hang off the frame, as a fraction of its own size.
     *
     * **This is what lets the type be bigger than the card.** Held inside the frame, a word can
     * only be as large as the frame's narrowest way of seating four of them; allowed to run
     * over the edge it can be set two or three times that and is *cropped* — which is what the
     * reference does, and what gives letters read as shapes rather than as a word centred in a
     * box. 0 keeps every word whole.
     *
     * The words still never overlap each other: it is the frame they are allowed past, not one
     * another. And **never more than half of a word is off the frame**, on either axis — held
     * to [HALF] whatever is asked for, because past halfway a word stops being a cropped word
     * and becomes a mark at the edge that happens to be made of letters.
     */
    private val bleed: Double = (Env["SLIDES_CARD_BLEED"]?.toDoubleOrNull() ?: 0.0)
        .coerceIn(0.0, KineticType.HALF),
    /**
     * The height of the window the type is seen through, as a fraction of the card.
     *
     * **Nothing outside it reaches the plate at all.** The window travels from below the frame
     * to above it and back over a period, so a word is uncovered as the window passes and gone
     * again behind it — the field standing at its resting size wherever the window is not, so
     * what moves across the card is a band of type rising out of a still field.
     *
     * It is a clip on the *picture*, not on the elements: they never move, and each one is
     * simply reading a plate that has type on it for the moment the window is over it. 0 shows
     * the whole card at once.
     */
    private val window: Double = Env["SLIDES_CARD_WINDOW"]?.toDoubleOrNull() ?: 0.0,
    /**
     * Set the sentence a word at a time and take it away again a word at a time.
     *
     * The words stand in the places the opening deal gave them and never move; what happens is
     * that they *arrive*, in the order they are read, and later leave in the same order — so
     * the card reads the chapter out and then clears itself. Off, the arrangement holds and the
     * words readjust instead, one at a time — see [arrangement].
     */
    private val build: Boolean = Env.boolean("SLIDES_CARD_BUILD", true),
    /** Beats the whole sentence stands before it starts going away again. */
    private val hold: Int = Env["SLIDES_CARD_HOLD"]?.toIntOrNull() ?: 2,
    /**
     * How far a word wanders from where it stands, as a fraction of a line's height.
     *
     * A slow circle, each word from its own phase. 0 with a [beat] running: the words are dealt
     * to their places and hold there, which is what makes each new deal read as a change.
     */
    private val shift: Double = Env["SLIDES_CARD_SHIFT"]?.toDoubleOrNull() ?: 0.0,
    /**
     * Repeat the title across and down until it fills the frame, the way the reference does.
     *
     * Off: the sentence is set once, where it was set, and only the wander moves it. Repeated
     * copies that each wander on their own circle read as the *type* changing size — the gaps
     * between them open and close and the eye takes that for a zoom — which is the one thing
     * this card should not do.
     */
    private val tile: Boolean = Env.boolean("SLIDES_CARD_TILE"),
    /**
     * Whole turns of the strip in one [period], for a tiled card — 1 sends the title travelling
     * a full cycle of its lines. Nothing to travel through when it is set once.
     */
    private val turns: Int = Env["SLIDES_CARD_TURNS"]?.toIntOrNull() ?: 0,
    /**
     * Flip every other row, so the rows meet foot to foot.
     *
     * **Off, and off by default, though the reference does it.** There the repeated word is a
     * texture and a flipped copy reads as a reflection; here the rows are the chapter's own
     * words, and a word standing on its head reads as a mistake rather than as a device — the
     * eye tries to read it and cannot. The mirror is worth having on a one-word title, so it
     * stays a key rather than being taken out.
     */
    private val mirror: Boolean = Env.boolean("SLIDES_CARD_MIRROR"),
    private val paneWidth: Int = 1920,
    private val paneHeight: Int = 1080
) : Slide(), MosaicCard {
    override val name get() = section.chapter.ifBlank { "Panel" }
    override val transition = Cut

    private lateinit var face: FontImageMap

    /** The title set to the pane: the lines it broke into and the scale they are drawn at. */
    private var block: TypeBlock? = null

    /** The standing field — every cell of the quadtree, at every level. */
    private var cells: VertexBuffer? = null
    private var cellCount = 0

    /** The black and white picture the field reads, repainted every frame. */
    private var plate: RenderTarget? = null

    /**
     * Frames the card takes to come back to itself. Named `turn` because [Slide.loop] is the
     * deck's own, and a card does not use that one.
     *
     * A building card sets its own: a beat a word in, [hold] beats standing, a beat a word out.
     * Stating a period as well would only be a second number to keep in step, and every one of
     * them would be wrong for a chapter of another length. Anything else — a card that holds
     * still, or one whose words readjust — runs on [period].
     */
    private var turn = 1

    /**
     * Draw the plate itself rather than the field that reads it — the debug view.
     *
     * A `var` because [CardStudio] toggles it with `b`: the two are the same card at two
     * stages, and switching between them is how you see whether a fault is in the *picture* or
     * in the packing that reads it. Every box the layout stands on is drawn over it, which is
     * the other half of the answer — a word in the wrong place and a word correctly placed in
     * a box that is itself wrong look identical until the boxes are visible.
     */
    override var plainly = Env.boolean("SLIDES_CARD_DEBUG")

    /** What the layout put where, this frame, for the debug view to outline. */
    private var boxes: List<Rectangle> = emptyList()

    /** The window's own band this frame, when there is one. */
    private var band: Rectangle? = null

    /**
     * The sentence as separate words, and when each of them is there.
     *
     * The arrangement and the build are [KineticType]'s, not this card's: the same thing set as
     * plain type is a sketch of its own, and having the two share it is what keeps a change to
     * how the words fall a change to both. This card only paints what it says into the plate.
     */
    private var kinetic: KineticType? = null

    /** The sheet the marks came off, and what its objects are shaped like. */
    private var catalogue = ""

    /** [shape], or the sheet's own median once it has been read. */
    private var cell = 1.9

    /** The catalogue itself, kept so the field can be rebuilt at another size — see [rescale]. */
    private var templates: List<ObjectTemplate> = emptyList()
    private var seed = 0

    /** The mark size in force: [coarse] and [finest], until the studio's j/k moves them. */
    private var markSize = 0.0
    private var markFinest = 0.0

    /**
     * Rows the strip takes to come back to itself: the lines come round every `n` rows and the
     * mirroring, when there is any, every two — so it is their lcm, and plain `n` without it.
     */
    private val cycle: Int get() =
        lcm((block?.lines?.size ?: 1).coerceAtLeast(1), if (mirror) 2 else 1)

    override fun load(program: Program) {
        Type.load(program)
        // Loaded once, large, and scaled *down* to fit: a glyph atlas enlarged past its own
        // size goes soft, and the card is the biggest type in the show.
        face = program.loadFont(fontPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        if (section.chapter.isBlank()) return
        block = face.setToFit(section.chapter, plateBox(), SIZE, LEADING, lines)

        val file = File(sheet)
        if (!file.isFile) {
            println("no object sheet at ${file.path}; setting \"${section.chapter}\" as type")
            return
        }
        templates = loadObjectTemplates(file)
        if (templates.isEmpty()) return

        // Seeded off the chapter, so a card is the same field every run and two chapters are
        // not the same field as each other.
        seed = section.chapter.hashCode()
        val median = templates.map { it.aspect }.sorted()[templates.size / 2]
        cell = shape ?: median
        markSize = coarse
        markFinest = finest.coerceAtMost(coarse)
        kinetic = KineticType(
            section.chapter, face, SIZE, LEADING, plateBox(),
            beat = beat, move = move, hold = hold, bleed = bleed, gap = GAP, lines = lines
        )
        turn = if (build && beat > 0) kinetic!!.turn else period.coerceAtLeast(1)
        catalogue = "%s, %d objects, median %.2f".format(file.name, templates.size, median)
        stand()
        // The plate the field reads. **On the cell's aspect, not the pane's**: a cell is then
        // square in texels, so a square mip average is exactly the cell's own box and coverage
        // costs one `textureLod` — see [MOSAIC_FIELD].
        val across = (paneWidth / TEXEL).toInt()
        val down = (paneHeight * cell / TEXEL).toInt()

        // **Asked for with a mip chain, and it has to be asked for**: a colour buffer is one
        // level by default, and `textureLod` on a one-level texture does not fail — it hands
        // back level 0. Every cell then reads a *point sample* at its own centre, which is
        // always 0 or 1, so nothing ever straddles an edge, nothing subdivides, and the card
        // comes out as blocks of the coarsest cell wherever a centre happened to land in ink.
        // It renders, and it is unreadable.
        val picture = colorBuffer(across, down, levels = mips(across, down))
        picture.filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        picture.filterMag = MagnifyingFilter.LINEAR
        plate = renderTarget(across, down) { colorBuffer(picture) }
        println("chapter card \"${section.chapter}\": $recipe")
    }

    /** The field at the mark size in force. The plate is unaffected — it is the picture. */
    private fun stand() {
        cells?.destroy()
        // Named from `seed` on: `mosaicField` grew a `gap` between `fill` and `seed`, and this
        // card does not use it — its clearance is [fill]'s alone, as it always was.
        val field = mosaicField(
            paneWidth, paneHeight, templates, markSize, markFinest, cell, fill,
            seed = seed, uniform = uniform
        )
        cellCount = field.size
        cells = fieldBuffer(field, templates)
    }

    /**
     * The marks a step bigger or smaller, and the field rebuilt around them — the studio's
     * `j` and `k`.
     *
     * It is a rebuild rather than a scale, and has to be: the cells *are* the field, so a
     * bigger mark means fewer of them and a different packing of the same words, not the same
     * picture drawn larger. A tenth of a second, which is fine on a keypress in the studio and
     * is why nothing in the show ever calls it.
     */
    override fun rescale(by: Double) {
        if (templates.isEmpty()) return
        val uniform = markFinest >= markSize
        markSize = (markSize * by).coerceIn(8.0, 256.0)
        markFinest = if (uniform) markSize else (markFinest * by).coerceIn(4.0, markSize)
        stand()
        println("chapter card \"${section.chapter}\": $recipe")
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        if (section.chapter.isBlank()) return

        val mesh = cells
        val mask = plate
        val set = block
        if (mesh == null || mask == null || set == null) {
            // no sheet: the title as ordinary type
            if (set != null) {
                drawer.fill = ink
                val box = Rectangle(INSET, INSET, stage.width - 2 * INSET, stage.height - 2 * INSET - FOOT)
                face.setToFit(section.chapter, box, SIZE, LEADING, lines).draw(drawer, box.center)
            }
            subchapter(drawer, stage)
            return
        }

        // The picture first, then the field that reads it. The mip chain is what carries
        // coverage, so it is rebuilt every time the picture changes.
        paint(drawer, mask, set, stage)
        mask.colorBuffer(0).generateMipmaps()

        composed(drawer, stage) {
            if (plainly) plate(drawer, mask) else {
                drawer.fill = ink
                drawer.shadeStyle = standing(stage, mask)
                drawer.vertexBuffer(mesh, DrawPrimitive.TRIANGLES)
                drawer.shadeStyle = null
            }
        }
        subchapter(drawer, stage)
    }

    /**
     * The black and white picture, laid out in the pane's own coordinates.
     *
     * Held still it is the title set to the frame — the card as a title card. Drifting it is
     * [strip]. Either way it is ordinary type drawn into a buffer, which is the point of doing
     * it this way round: the motion is type moving, with none of it having to be rebuilt as
     * geometry, and the field picks up whatever it finds.
     */
    private fun paint(drawer: Drawer, mask: RenderTarget, set: TypeBlock, stage: Stage) {
        val across = mask.width / paneWidth.toDouble()
        val down = mask.height / paneHeight.toDouble()
        drawer.isolatedWithTarget(mask) {
            drawer.ortho(mask)
            drawer.clear(ColorRGBa.BLACK)
            drawer.fill = ColorRGBa.WHITE
            drawer.stroke = null
            drawer.shadeStyle = null
            // The plate is taller than the pane in proportion — see [load] — so laying out in
            // pane pixels means one scale here and nothing else anywhere having to know.
            // The window, in the plate's own pixels: a clip is in the target's coordinates and
            // is not touched by the scale below, which is what makes it a window on the picture
            // rather than something the layout has to know about.
            if (window > 0.0 && period > 0) {
                val height = window * paneHeight
                val t = stage.frame.mod(turn).toDouble() / turn
                // Up and back over the period, and a cosine rather than a sawtooth: it turns
                // at either end instead of snapping back, and it closes the loop.
                val u = 0.5 - 0.5 * cos(2.0 * PI * t)
                val top = paneHeight - u * (paneHeight + height)
                band = Rectangle(0.0, top, paneWidth.toDouble(), height)
                drawer.drawStyle.clip = Rectangle(
                    0.0, top * down, mask.width.toDouble(), height * down
                )
            }
            drawer.scale(across, down)
            if (period <= 0) set.draw(drawer, plateBox().center) else strip(drawer, set, stage)
        }
    }

    /**
     * The drift: the lines of the title laid one under another and repeated, the whole field
     * breathing and travelling slowly upward.
     *
     * What the reference does, and what is taken from it:
     *
     *  - **A line is a stamp.** Tiled ([tile]) it is repeated across, never re-set — so a short
     *    line tiles three times where a long one tiles once, and the repeat count is a
     *    consequence of the words rather than a number anyone picked. A word space follows each
     *    copy, or "van" three times across reads as one long word. Off, which is the default,
     *    the sentence is simply set once where it belongs.
     *  - **The size never animates.** The reference zooms and everything follows from it; a
     *    chapter's own words swelling reads as a zoom rather than as a composition, so what
     *    moves here is *where each word stands* — every copy on its own slow circle, off its
     *    own phase, at [shift] of a line's height. Nothing accelerates and nothing turns
     *    around, which is what keeps it easy.
     *  - **Every other row is mirrored** — [mirror], and off here, for the reason given there:
     *    the reference repeats one word as a texture, where these rows are words to be read.
     *  - **Ink is boolean.** Copies that overlap merge into one silhouette; nothing fades.
     *
     * Like `packBoxes` in demo01 and `stateAt` in Decision, it is a pure function of one
     * number — where the drift has got to — so nothing is integrated and a frame is the same
     * picture whenever it is asked for. That number is periodic in [period]: the lines come
     * round every `lines` rows and the mirroring every two, so after their lcm the picture is
     * exactly itself again and the breath is back where it started.
     */
    private fun strip(drawer: Drawer, set: TypeBlock, stage: Stage) {
        val t = stage.frame.mod(turn).toDouble() / turn
        val row = LEADING * SIZE * set.scale
        // Where the title's first line stands on a card held still, so a card that does not
        // wander is exactly the card as it was set.
        val top = plateBox().center.y - set.height / 2.0
        val wander = shift * row
        drawer.fontMap = face

        /** One line, at its own place in its own circle. */
        fun word(
            line: String, x: Double, y: Double, seat: Int, copy: Int, flipped: Boolean,
            at: Double = set.scale
        ) {
            // Its own slow circle, from its own phase: no two words are quite in line or in
            // step. One turn to the period, so the card still loops.
            val turn = 2.0 * PI * (t + phase(seat, copy))
            drawer.isolated {
                drawer.translate(x + wander * cos(turn), y + wander * sin(turn))
                if (flipped) {
                    // flipped about its own box, so its letters stand on the boundary the line
                    // above them stands on
                    drawer.scale(1.0, -1.0)
                    drawer.translate(0.0, -row)
                }
                drawer.scale(at)
                drawer.text(line, 0.0, BASELINE * LEADING * SIZE)
            }
        }

        if (!tile) {
            if (build && beat > 0) {
                // **The places are the opening deal's and they do not change.** What moves is
                // which words are there: each arrives on its own beat and leaves on its own,
                // in the order the sentence is read, so the card sets the chapter out and then
                // takes it away. A word is eased in by being *drawn fainter* — the plate is
                // what the field reads, so a half-drawn word is a half-grown mark under it,
                // and the elements swell into the letters rather than the letters fading on.
                val type = kinetic!!
                val now = stage.frame.mod(turn)
                boxes = type.at(now).map { it.box }
                type.draw(drawer, now)
                drawer.fill = ColorRGBa.WHITE
            } else if (beat > 0) {
                val laid = arrangement(set, stage.frame.mod(period))
                boxes = laid.map { it.second }
                // Every word is one size — the one the opening arrangement came out at, held
                // for the life of the card. A size that changed with each move would read as
                // the type breathing rather than as the words finding a new place.
                val scale = laid.firstOrNull()?.let {
                    it.second.height / (LEADING * SIZE)
                } ?: set.scale
                laid.forEach { (text, at) ->
                    word(text, at.corner.x, at.corner.y, text.hashCode(), 0, false, scale)
                }
            } else {
                set.lines.forEachIndexed { i, line ->
                    val width = face.advanceOf(line) * set.scale
                    word(line, (paneWidth - width) / 2.0, top + i * row, i, 0, false)
                }
            }
            return
        }

        val travel = t * cycle * turns
        // The rows that reach the frame, and no others. Travel is counted in *rows* rather than
        // in pixels, so the strip stays aligned to itself.
        val first = floor(travel + (-row - top) / row).toInt()
        val last = ceil(travel + (paneHeight - top) / row).toInt()
        val space = face.advanceOf(" ") * set.scale

        for (k in first..last) {
            val line = set.lines[k.mod(set.lines.size)]
            val width = face.advanceOf(line) * set.scale
            val step = width + space
            val copies = ceil(paneWidth / step).toInt() + 1
            // Centred on the ink rather than on the steps, so the space after the last copy is
            // not counted as part of what is being centred.
            val left = (paneWidth - (copies * step - space)) / 2.0
            val y = top + (k - travel) * row
            val flipped = mirror && k.mod(2) == 1
            for (copy in 0 until copies) word(line, left + copy * step, y, k, copy, flipped)
        }
    }

    /** How far a word is in or out, [move] frames after its beat comes up. */
    private fun ramp(into: Int): Double =
        easeInOutCubic((into.toDouble() / move.coerceAtLeast(1)).coerceIn(0.0, 1.0))

    /**
     * Where every word stands at [now] frames into the period.
     *
     * **One word readjusts at a time.** Every [beat] the next word in turn eases to somewhere
     * new over [move], and the others hold exactly where they are — which is what gives a still
     * arrangement that shifts now and then rather than a field in motion, and it is also what
     * makes *never overlapping* cheap to guarantee: there is only ever one box moving, so its
     * new place and the whole box it sweeps on the way there are tested against three that are
     * standing still.
     *
     * It is worked out from the frame each time rather than kept, so the card can be scrubbed,
     * paused, recorded or jumped into and shows the same picture at the same frame. The events
     * are few — a period holds a dozen or so — and each is a placement search, which is nothing
     * per frame.
     *
     * **The last round sends every word home**, so the arrangement at the end of a period is
     * the one it opened on and a clip of [period] loops.
     */
    private fun arrangement(set: TypeBlock, now: Int): List<Pair<String, Rectangle>> {
        val home = kinetic?.home.orEmpty()
        if (home.isEmpty()) return home
        val count = home.size
        val events = (period / beat).coerceAtLeast(count)
        val at = home.map { it.second }.toMutableList()

        val played = (now / beat).coerceAtMost(events - 1)
        for (event in 0..played) {
            val seat = event % count
            // Home on the last round: whichever word this is, it goes back where it started.
            val target = if (event >= events - count) home[seat].second
            else free(at, seat, event, set)

            if (event == played) {
                // The one in flight. Eased, and eased once — this is a move rather than a count,
                // so it keeps the ease rather than undoing it (the city's cull is the other way
                // round). A move that has finished simply sits at 1.
                val into = (now - event * beat).toDouble() / move.coerceAtLeast(1)
                val k = easeInOutCubic(into.coerceIn(0.0, 1.0))
                at[seat] = Rectangle(
                    at[seat].corner * (1.0 - k) + target.corner * k,
                    target.width, target.height
                )
            } else {
                at[seat] = target
            }
        }
        return home.mapIndexed { i, (text, _) -> text to at[i] }
    }

    /**
     * Somewhere new for the word in [seat] to stand: clear of every other word by [GAP], and
     * clear of them **all the way there** — the box that contains both ends of the move is what
     * is tested, which is conservative and cannot let the type cross another word.
     *
     * **A move is along one axis, never across.** A round slides the words sideways and the
     * next lifts them, so a word keeps either its line or its column each time it goes. A
     * diagonal reads as a thing being carried from one place to another; an axis move reads as
     * the composition re-setting itself, which is what a card of type should look like when it
     * readjusts. It is also what makes the swept box exact rather than conservative — the box
     * that contains both ends *is* the path.
     *
     * Nothing found, it stays where it is. A card that holds a word still for one turn is a
     * card nobody notices; a word passing through another is the one thing this may not do.
     */
    private fun free(at: List<Rectangle>, seat: Int, event: Int, set: TypeBlock): Rectangle {
        val here = at[seat]
        val box = kinetic!!.room(here.width, here.height)
        val margin = GAP * here.height
        val others = at.filterIndexed { i, _ -> i != seat }
        val random = Random(event * 7919 + section.chapter.hashCode())

        fun clear(x: Double, y: Double): Rectangle? {
            val candidate = Rectangle(x, y, here.width, here.height)
            // the whole sweep, not just where it lands
            val swept = Rectangle(
                minOf(here.corner.x, x) - margin, minOf(here.corner.y, y) - margin,
                Math.abs(x - here.corner.x) + here.width + 2 * margin,
                Math.abs(y - here.corner.y) + here.height + 2 * margin
            )
            return if (others.none { it.intersects(swept) }) candidate else null
        }

        val free = box.width - here.width
        val fall = box.height - here.height
        if (free <= 0.0 || fall <= 0.0) return here

        // Sideways this round, or up and down the next.
        val sideways = (event / at.size) % 2 == 0
        repeat(KineticType.TRIES) {
            val found = if (sideways)
                clear(box.corner.x + random.nextDouble() * free, here.corner.y)
            else
                clear(here.corner.x, box.corner.y + random.nextDouble() * fall)
            if (found != null) return found
        }
        return here
    }

    /**
     * Where one copy of one line is in its circle, 0..1 — deterministic, so a frame is the same
     * picture whenever it is asked for, and scattered, so no two neighbours move together.
     */
    private fun phase(row: Int, copy: Int): Double {
        val v = sin(row * 12.9898 + copy * 78.233) * 43758.5453
        return v - floor(v)
    }

    /**
     * The field, reading the plate: which cell stands here, at what size, in which colour.
     *
     * Every number the rule needs is a uniform and the rule itself is [MOSAIC_FIELD] — the same
     * thresholds [objectMosaic] uses on the CPU, so a card held still is the card that was
     * packed before any of this was live.
     */
    /**
     * The debug view: the plate as it is, and every box the layout stands on drawn over it.
     *
     * The plate is put *back to the pane's own proportion* — it is rendered on the cell's
     * aspect, so shown at its own shape the type would be stretched and every measurement taken
     * off it wrong by that factor.
     *
     * The two views are the same card at two stages, which is what makes the pair worth having:
     * a fault in the picture and a fault in the packing that reads it look alike until the
     * picture can be seen on its own. And a word in the wrong place looks exactly like a word
     * correctly placed in a box that is itself wrong, until the boxes are drawn.
     */
    private fun plate(drawer: Drawer, mask: RenderTarget) {
        val card = Rectangle(0.0, 0.0, paneWidth.toDouble(), paneHeight.toDouble())
        drawer.image(
            mask.colorBuffer(0),
            Rectangle(0.0, 0.0, mask.width.toDouble(), mask.height.toDouble()),
            card
        )

        drawer.fill = null
        drawer.strokeWeight = 3.0

        // the buffer's own edge, and the measure the type is set to inside it
        drawer.stroke = ColorRGBa.fromHex("FF3B30")
        drawer.rectangle(card)
        drawer.stroke = ColorRGBa.fromHex("FF9500")
        drawer.rectangle(plateBox())

        // how far a word may hang off, which is what explains a crop
        boxes.firstOrNull()?.let {
            if (bleed > 0.0) {
                drawer.stroke = ColorRGBa.fromHex("FFCC00").opacify(0.7)
                kinetic?.let { type -> drawer.rectangle(type.room(it.width, it.height)) }
            }
        }

        // the window the type is seen through
        band?.let {
            drawer.stroke = ColorRGBa.fromHex("34C759")
            drawer.rectangle(it)
        }

        // and every word, in the box the layout gave it
        drawer.stroke = ColorRGBa.fromHex("0A84FF")
        boxes.forEach { drawer.rectangle(it) }
        drawer.strokeWeight = 1.0
        drawer.stroke = null
    }

    private fun standing(stage: Stage, mask: RenderTarget) = shadeStyle {
        vertexTransform = MOSAIC_FIELD
        fragmentTransform = MOSAIC_FIELD_COLOUR
        parameter("mask", mask.colorBuffer(0))
        parameter("pane", Vector2(paneWidth.toDouble(), paneHeight.toDouble()))
        parameter("shape", cell)
        parameter("texel", TEXEL)
        // **[markFinest], not [finest]**: the field is rebuilt when j or k moves the mark size,
        // and a shader still holding the size the card was built with fails in a way that looks
        // like a limit of the grid rather than a bug. Every cell is then too big to count as
        // the finest level, so nothing straddling a letter's edge is allowed to stand, and the
        // words come out as a *hole* in the ground with the field itself perfectly intact.
        parameter("finest", markFinest)
        parameter("solid", SOLID)
        parameter("empty", EMPTY)
        parameter("threshold", threshold)
        parameter("shrink", shrink)
        parameter("ink", ink)
        // With no ground the cells outside the words are drawn in the paper and vanish into
        // it — cheaper than a second pass, and the same picture.
        parameter("ground", ground ?: background)
        // The count runs linearly — it is a count rather than a move, and an eased ramp crowds
        // the arrivals into the middle of it, the same argument as the city's cull. What is
        // eased is each element's own growth, inside the style.
        // The staged sweep is the picture card's; this one arrives in its baked random order,
        // which `sweep = 0` selects — the other three are then unused.
        parameter("sweep", 0.0)
        parameter("stage", 0.5)
        parameter("delay", 0.0)
        parameter("jitter", 0.0)
        parameter("arrived", stage.since(0, reveal + pop))
        parameter("ramp", (pop.toDouble() / (reveal + pop).coerceAtLeast(1)).coerceIn(0.01, 1.0))
    }

    /**
     * The pane the card was composed for, fitted into the pane it actually got, centred — and
     * clipped to it. On the show's own 1920x1080 card pane the fit is 1:1; the clip is what
     * keeps a field that runs to the edge of the card inside the card's own frame rather than
     * into whatever else the pane holds.
     */
    private fun composed(drawer: Drawer, stage: Stage, draw: () -> Unit) {
        val fit = min(stage.width / paneWidth, stage.height / paneHeight)
        val corner = stage.center - Vector2(paneWidth * fit, paneHeight * fit) / 2.0
        drawer.stroke = null
        drawer.isolated {
            drawer.drawStyle.clip = Rectangle(corner, paneWidth * fit, paneHeight * fit)
            drawer.translate(corner)
            drawer.scale(fit, fit)
            draw()
            drawer.shadeStyle = null
        }
    }

    /**
     * Where this sits in the show, kept quiet and out of the way of the chapter. A chapter
     * whose slides hang straight off it has no subchapter and shows none.
     */
    private fun subchapter(drawer: Drawer, stage: Stage) {
        if (section.subchapter.isBlank()) return
        drawer.shadeStyle = null
        drawer.fontMap = Type.panelSub
        drawer.fill = ink.opacify(0.45)
        val label = "${section.number}   ${section.subchapter}"
        drawer.text(label, (stage.width - Type.panelSub.advanceOf(label)) / 2.0, stage.height - MARGIN)
    }

    /**
     * What this card is made of, in one line. Printed at load, and read by [CardStudio] — one
     * description of the settings rather than the studio keeping a second copy of the defaults
     * to show.
     */
    override val recipe: String get() = ("%s   %d cells over %d lines   coarse %.0f → finest %.0f   " +
            "cell %.2f%s   fill %.2f%s   %s").format(
        catalogue, cellCount, block?.lines?.size ?: 0, markSize, markFinest,
        cell, if (shape == null) " (off the sheet)" else "",
        fill, if (uniform) " one size" else " fitted",
        if (period > 0) "drift %.0fs   shift %.2f%s%s".format(
            seconds(period), shift,
            if (tile) "   tiled x$turns"
            else if (build && beat > 0) "   builds %.1fs a word, %.1fs in, loop %.1fs%s".format(
                seconds(beat), seconds(move), seconds(turn),
                if (window > 0.0) "   window %.2f".format(window) else "")
            else if (beat > 0) "   readjusts %.1fs / %.1fs   bleed %.2f%s".format(
                seconds(beat), seconds(move), bleed,
                if (window > 0.0) "   window %.2f".format(window) else "")
            else "   once",
            if (tile && mirror) "   mirrored" else "")
        else "still"
    )

    /** The measure the title is set to, on the pane's own scale. */
    private fun plateBox() = Rectangle(
        INSET, INSET,
        paneWidth - 2 * INSET,
        paneHeight - 2 * INSET - FOOT
    )

    private companion object {
        /** The size the atlas is baked at. Everything is scaled off it, never past it. */
        const val SIZE = 190.0

        /**
         * Line to line, as a multiple of the size. Tight, because the type is large and the
         * block is what fills the card: every notch of leading given back is type the height
         * constraint can spend instead, and at this size the lines still clear.
         */
        const val LEADING = 0.95

        /**
         * Where the baseline sits inside a line, as a fraction of the leading — [TypeBlock]'s
         * own default, restated here because the strip sets a line at a time rather than
         * through the block.
         */
        const val BASELINE = 0.78

        /**
         * The card's own margin, tighter than the slides' [MARGIN]: there is one thing on it,
         * and holding it to the same inset as a slide's furniture leaves the type smaller than
         * the frame can carry.
         */
        const val INSET = 70.0

        /** Clearance between two words, as a fraction of a line's height. */
        const val GAP = 0.35



        /** Room kept at the foot of the card for the subchapter line. */
        const val FOOT = 104.0

        /**
         * Where the subchapter line sits above the foot. Its own constant rather than the
         * folder's shared [slideshow.drawers.MARGIN]: this file is in the default package, so
         * an unqualified name here is ambiguous against the sketches' own.
         */
        const val MARGIN = 120.0

        /**
         * Pane pixels to one texel of the plate.
         *
         * The plate only has to resolve a *cell*, since that is the only question asked of it,
         * and the finest cell is 16 pane pixels — four texels at this scale, which is an
         * average worth having. Finer costs a bigger picture to draw for nothing.
         */
        const val TEXEL = 4.0

        /** How much ink a cell needs to count as wholly inside — [objectMosaic]'s own value. */
        const val SOLID = 0.88

    }
}

/**
 * `SLIDES_CARD_GROUND` as a colour: a hex value, `none` for a bare card, unset for the default.
 * The word rather than an empty value, because [Env] reads blank as unset and unset has to keep
 * meaning "the ground the card was designed with" — the same arrangement as `DECISION_SHEET`.
 */
private fun groundColour(value: String?): ColorRGBa? = when {
    value == null -> ColorRGBa.fromHex("343434")
    value.equals("none", ignoreCase = true) -> null
    else -> ColorRGBa.fromHex(value)
}

/** Levels in a full mip chain for a [width] x [height] picture — down to a single texel. */
private fun mips(width: Int, height: Int): Int =
    floor(log2(max(width, height).toDouble())).toInt() + 1

/** The smallest number of rows both the lines and the mirroring come back to themselves in. */
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
