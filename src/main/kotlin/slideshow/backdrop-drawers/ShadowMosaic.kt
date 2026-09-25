package slideshow.backdrops

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.color.Linearity
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.ColorType
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.DepthTestPass
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.VertexElementType
import org.openrndr.draw.WrapMode
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadImage
import org.openrndr.draw.renderTarget
import org.openrndr.draw.shadeStyle
import org.openrndr.draw.vertexBuffer
import org.openrndr.draw.vertexFormat
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.math.Vector4
import org.openrndr.shape.Rectangle
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.contour
import slideshow.Arrival
import slideshow.Backdrop
import slideshow.Cut
import slideshow.MosaicCells
import slideshow.Sound
import slideshow.Stage
import slideshow.Transition
import slideshow.FPS
import slideshow.Want
import slideshow.seconds
import slideshow.voiced
import slideshow.linear
import slideshow.frames
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * Building and unbuilding, slowly, box by box. Each projector's 1920x1080 is divided into boxes of
 * different sizes; in each a circle grows from the box's own middle, never moving, and replaces the
 * dark marks with light ones until the box is full; it holds; then a dark circle grows from the same
 * middle and replaces them again. Every box runs that on its own delay, so the wall is always a few
 * boxes filling and emptying among ones standing light or dark.
 *
 * The look is after `input/Binpack06-2026-09-18-23.44.34.mp4`: a black ground, marks packed tight in
 * greys, shadows darkest where they leave a mark and fading along their length, and the dark state
 * the same kind of packing near black.
 *
 * **Two packings, one wall, and the circles replace rather than move.** The dark marks are the
 * project highlight's split at [columns] coarse cells across, six to a projector, and the light ones
 * a second split at [innerColumns]; a box is a run of whole coarse cells, so every mark of either
 * packing lies in exactly one box. A mark stands as far as its box's circle says — a light mark
 * comes up where it stands as the growing light circle covers its middle, a dark one goes down
 * there, and the other way round for the dark circle — each over [band] pixels.
 *
 * **The light is the chapter card's.** The marks go into a float plan (height, coverage, tone); a
 * pass turns height into reach and passes at 1, 2, 4 … pixels carry it along the light. It is a copy
 * of the passes in `LongShadowV3` at wall size, since that one is fixed to a 1920 pane, with one
 * change: the shadow fades by how much reach it has left, which is what makes it soft at its tip.
 *
 * Everything is a function of the frame. **It opens dark:** every box stands in its dark state for
 * [dark0] seconds, then each starts its first cycle at its own delay, so the wall comes up box by box
 * out of the dark over one cycle. From the last start on it is periodic in one box's cycle — grow,
 * hold, grow, hold — so a clip of one cycle taken after the opening loops.
 */
class ShadowMosaic(
    /** Each mark's triangles about its centre at height 1, y up, and its proportion. Read in [load]. */
    private val marks: () -> List<Pair<List<Vector2>, Double>>,
    /** The wall it composes for, in pixels; [draw] fits it into whatever it is given. */
    private val wallWidth: Double = 3840.0,
    private val wallHeight: Double = 1080.0,
    /** Coarse cells across for the dim field, and for the finer packing the light stands. */
    private val columns: Int = 12,
    private val innerColumns: Int = 24,
    /**
     * The grid the boxes are cut on, in coarse cells across; null for [columns]. The dark and light
     * packings must each divide it exactly (8 and 16 under 4, say) so no mark straddles two boxes;
     * the close-up cuts big boxes and packs them finer than that, or a dark box is one slab.
     */
    private val boxColumns: Int? = null,
    /** The width of one projector: the boxes are dealt per projector and never cross the seam. */
    private val pane: Double = 1920.0,
    /** How readily a box splits again at each depth, on the grid of coarse cells. */
    private val boxSplit: List<Double> = listOf(1.0, 0.85, 0.6, 0.35),
    /** Seconds a circle takes to fill its box, and a box stands full, light and then dark. */
    private val grow: Double = 10.0,
    private val hold: Double = 6.0,
    /**
     * Seconds the wall stands wholly dark when it comes up, before the first box starts to fill.
     * After that each box starts its first cycle at its own delay, so the wall is revealed box by
     * box out of the dark over one cycle, and runs on from there.
     */
    private val dark0: Double = 0.0,
    /**
     * Seconds into its own clock the wall comes up, so the first boxes are already on their way
     * when it arrives rather than a circle starting from a point on a dark wall.
     */
    private val lead: Double = 0.0,
    /** The ring over which a mark goes down or comes up as the rim passes it, in pixels. */
    private val band: Double = 36.0,
    /** The tallest a mark stands, in wall pixels, and its heights as a share of that. */
    private val tower: Double = 100.0,
    private val low: Double = 0.2,
    private val highest: Double = 1.0,
    /**
     * Roof tones, paper (0) to ink (1), and the share of the light the field outside a circle has.
     * The canvas is linear light, so these are small: 0.1 of the light displays as a quarter grey,
     * which is what the first version's field came out as, where the reference's is nearly black.
     */
    private val dark: Double = 0.06,
    private val light: Double = 0.85,
    private val dim: Double = 0.03,
    /** Which way the shadows fall (135 is down and to the left) and the sun's height in degrees. */
    private val angle: Double = 135.0,
    private val elevation: Double = 22.0,
    /** How much of its length a shadow takes to fade from full to nothing: 0 hard, 1 over all of it. */
    private val soft: Double = 0.6,
    /** The joint between marks, in pixels: the reference packs them tight, so under the highlight's 3. */
    private val gap: Double = 1.0,
    val ink: ColorRGBa = ColorRGBa.WHITE,
    val paper: ColorRGBa = ColorRGBa.BLACK,
    val shade: ColorRGBa = ColorRGBa.BLACK,
    /**
     * The palette, each a ramp a mark's own tone picks from: the dark state from [fieldLow] to
     * [fieldHigh], the light state from [litLow] to [litHigh], and [accents] of the light marks from
     * [accentLow] to [accentHigh]. Left null they are the grey wall: the dark state paper to [dim] of
     * the way to ink, the light state paper to ink, and no accents.
     */
    private val fieldLow: ColorRGBa? = null,
    private val fieldHigh: ColorRGBa? = null,
    private val litLow: ColorRGBa? = null,
    private val litHigh: ColorRGBa? = null,
    private val accentLow: ColorRGBa? = null,
    private val accentHigh: ColorRGBa? = null,
    private val accents: Double = 0.0,
    /** How dark a shadow gets at its darkest, 1 for [shade] itself. Under 1 on a light ground. */
    private val shadowStrength: Double = 1.0,
    /** Stone worked into the roofs against its own average, as the chapter card does; null for none. */
    private val concrete: File? = null,
    private val roofMix: Double = 0.6,
    private val seed: Int = 21,
    /**
     * Handover: a box builds up out of bare ground and later builds down to it again, rather than
     * turning from dark to light, and the boxes work in pairs half a cycle apart — so as one spot is
     * built, another is taken down. The dark packing is not drawn.
     */
    private val handover: Boolean = false,
    /** Of the light marks, the share drawn from the dark state's ramp instead: blue among the white. */
    private val blues: Double = 0.0,
    /** Seconds a box stands empty after it is built down, before it builds again; null for [hold]. */
    private val emptyHold: Double? = null,
    /**
     * Ageing: a blue mark greys from the moment its box is built to the moment it is taken down,
     * from the blue ramp to [oldLow]–[oldHigh]. Null leaves the blue as it is.
     */
    private val oldLow: ColorRGBa? = null,
    private val oldHigh: ColorRGBa? = null,
    /**
     * The red units, which always stand together as buildings. [clusters] is the round of
     * arrangements they go through — `[[6], [3, 3], [6], [2, 2, 2]]` is one building of six, then
     * two of three, one again, then three of two — each unit a catalogue mark in the red ramp on a
     * plot of the light grid, touching its neighbours, never ageing. An arrangement stands, and then
     * over the last part of [stateLength] its units are carried one by one, [stagger] seconds apart,
     * to their places in the next: each lifts [unitLift] of the tallest above its height, carries
     * over [move] seconds and sets down. So a building is taken apart in one place and put together
     * in another, the one thing on the wall that travels, which is the point. Empty for none.
     */
    private val clusters: List<List<Int>> = emptyList(),
    private val stateLength: Double = 24.0,
    /**
     * Small red elements, one plot of a finer grid ([smallColumns] across) each. They arrive one at a
     * time, [smallEvery] seconds apart from [smallFirst], each built up out of the floor, so there is
     * more red on the wall the longer it stands; and each is reused, lifted and carried to the next of
     * its [smallSites] plots every [smallHop] seconds. Where one stands among the blue, the blue there
     * gives way, and comes back when it moves on — so the red takes the town over, a plot at a time.
     */
    private val smallUnits: Int = 0,
    private val smallColumns: Int = 32,
    private val smallSites: Int = 3,
    private val smallHop: Double = 16.0,
    private val smallMove: Double = 3.0,
    private val smallEvery: Double = 12.0,
    private val smallFirst: Double = 8.0,
    private val stagger: Double = 1.5,
    private val move: Double = 4.0,
    private val unitLift: Double = 0.8,
    /**
     * A city plan laid on the ground: straight roads, curved ones and rectangular parks, where nothing
     * is ever built — the marks there are left out. [roadWidth] in pixels; the roads and the parks
     * take [roadTone] and [parkTone].
     */
    private val city: Boolean = false,
    private val roadWidth: Double = 70.0,
    private val roadTone: ColorRGBa = ColorRGBa.fromHex("E2E6EE"),
    private val parkTone: ColorRGBa = ColorRGBa.fromHex("DCE5F3"),
    override val name: String = "Shadow mosaic",
    override val transition: Transition = Cut,
    override val sound: Sound? = null,
    /**
     * Whether it takes the whole wall, as a backdrop does, or composes for the pane beside a
     * chapter card as a slide — [wallWidth] then being the pane's 1920.
     */
    private val wall: Boolean = true,
    /** States a slide steps through; a wall has one. */
    private val clicks: Int = 1,
    override val stepFrames: Int = frames(0.45),
    /**
     * The click that moves the red units, or null for the wall's own clock. Given, the first
     * arrangement comes up and holds until that click, and the click carries the units across to
     * the second — so what it shows is a building taken apart and put together again elsewhere,
     * and clicking back undoes it. The blue goes on on the wall's own time either way.
     */
    private val unitsOnClick: Int? = null,
    /**
     * Seconds of itself the wall writes down as MIDI, and so the length of the clip beside it.
     *
     * A wall that fills, holds and empties again for ever has no end to score to, so it states how
     * much of itself is written — see [slideshow.MidiTimed.midiFrames]. It is counted from the frame
     * the wall comes up, [lead] included, so the score and a clip of it are the same run.
     */
    private val midiWindow: Double = 60.0
) : Backdrop() {

    override val wide get() = wall
    override val kind get() = if (wall) "backdrop" else "slide"
    override val steps get() = clicks

    private val period = 2.0 * grow + hold + (emptyHold ?: hold)

    /** How many semitones the score spreads the wall's height over: four octaves. */
    private val SCORE_NOTES = 48
    private val reach = tower / tan(Math.toRadians(elevation.coerceIn(2.0, 89.0)))

    private lateinit var plan: RenderTarget
    private lateinit var ping: RenderTarget
    private lateinit var pong: RenderTarget
    private lateinit var card: RenderTarget
    private var stone: ColorBuffer? = null
    private var field: VertexBuffer? = null
    private var inner: VertexBuffer? = null
    private var unitMarks: List<VertexBuffer> = emptyList()
    private var smallMarks: List<VertexBuffer> = emptyList()
    private var ground: RenderTarget? = null

    // ---- the city plan: roads and parks, fractions of the wall ------------------------------ //

    private class Road(val path: ShapeContour, val width: Double) {
        /** The path sampled every few pixels, which is what a mark is tested against. */
        val points: List<Vector2> = path.equidistantPositions((path.length / 6.0).toInt().coerceAtLeast(2))
    }

    private val W get() = wallWidth
    private val H get() = wallHeight

    /**
     * The plan, stated rather than dealt, so it reads as a town and not as noise: an avenue across
     * the wall, a street down each projector, one road curving from the foot to the head of the
     * wall, and two parks — plain plots of open ground, square to the streets.
     */
    private val parks: List<Rectangle> = if (!city) emptyList() else listOf(
        Rectangle(0.02 * W, 0.08 * H, 0.17 * W, 0.44 * H),
        Rectangle(0.82 * W, 0.74 * H, 0.14 * W, 0.26 * H)
    )
    private val roads: List<Road> = if (!city) emptyList() else listOf(
        Road(contour { moveTo(0.0, 0.64 * H); lineTo(W, 0.64 * H) }, roadWidth),
        Road(contour { moveTo(0.29 * W, -10.0); lineTo(0.29 * W, 0.64 * H) }, roadWidth * 0.8),
        Road(contour { moveTo(0.76 * W, -10.0); lineTo(0.76 * W, H + 10.0) }, roadWidth * 0.8),
        Road(contour {
            moveTo(0.37 * W, H + 10.0)
            curveTo(Vector2(0.43 * W, 0.40 * H), Vector2(0.53 * W, 0.62 * H), Vector2(0.60 * W, -10.0))
        }, roadWidth),
        Road(contour {
            moveTo(0.76 * W, 0.40 * H)
            curveTo(Vector2(0.84 * W, 0.40 * H), Vector2(0.95 * W, 0.20 * H), Vector2(W + 10.0, 0.08 * H))
        }, roadWidth * 0.7)
    )

    /** Whether a mark's cell reaches a road, a park or a red unit's plot: then it is never built. */
    private fun blocked(box: Rectangle): Boolean {
        val margin = 6.0
        if (sites.any { it.intersects(box.offsetEdges(margin)) }) return true
        if (!city) return false
        if (parks.any { it.intersects(box.offsetEdges(margin)) }) return true
        return roads.any { r ->
            val grown = box.offsetEdges(r.width / 2.0 + margin)
            r.points.any { grown.contains(it) }
        }
    }

    /** As many units as the fullest arrangement holds; an arrangement with fewer leaves the rest off the wall. */
    private val units: Int = clusters.maxOfOrNull { it.sum() } ?: 0

    /**
     * Where every unit stands in every arrangement: `slots[state][unit]`, null where the arrangement
     * has fewer units than that — the unit is not on the wall then. Each building is a block of
     * whole cells of the light grid, three across at most, clear of roads and parks. A block keeps a
     * cell's clearance from the other buildings of its own arrangement, so two never read as one, and
     * stays off every cell of the arrangements either side of it, so no unit is ever set down where
     * another still stands. Arrangements further apart may use the same ground, which is what lets a
     * round of seven arrangements fit on one wall.
     */
    private val slots: List<List<Rectangle?>> = if (units <= 0) emptyList() else run {
        val area = Rectangle(0.0, 0.0, wallWidth, wallHeight)
        val rows = MosaicCells.rows(area, innerColumns)
        val cw = wallWidth / innerColumns
        val ch = wallHeight / rows
        val random = Random(seed * 71)
        fun cell(x: Int, y: Int) = Rectangle(x * cw, y * ch, cw, ch)
        val ground = Array(innerColumns) { x -> BooleanArray(rows) { y ->
            val r = cell(x, y)
            parks.none { it.intersects(r.offsetEdges(6.0)) } &&
                roads.none { road -> road.points.any { r.offsetEdges(road.width / 2.0 + 6.0).contains(it) } }
        } }
        val states = clusters.size
        val own = Array(states) { Array(innerColumns) { BooleanArray(rows) } }       // its blocks
        val near = Array(states) { Array(innerColumns) { BooleanArray(rows) } }      // and a cell round them
        // The biggest buildings are placed first, while there is room for them; placed smallest
        // first, a building of six found none.
        val placed = HashMap<Pair<Int, Int>, List<Rectangle>>()
        clusters.flatMapIndexed { si, state -> state.mapIndexed { ci, n -> Triple(si, ci, n) } }
            .sortedByDescending { it.third }
            .forEach { (si, ci, n) ->
                val w = minOf(n, 3); val h = (n + w - 1) / w
                val before = (si - 1 + states) % states; val after = (si + 1) % states
                val spots = (0..innerColumns - w).flatMap { x -> (0..rows - h).map { y -> x to y } }.shuffled(random)
                val (x0, y0) = spots.firstOrNull { (x, y) ->
                    (x until x + w).all { xx -> (y until y + h).all { yy ->
                        ground[xx][yy] && !near[si][xx][yy] && !own[before][xx][yy] && !own[after][xx][yy]
                    } }
                } ?: (0 to 0).also { println("shadow mosaic: no room for a building of $n") }
                for (xx in x0 until x0 + w) for (yy in y0 until y0 + h) own[si][xx][yy] = true
                for (xx in (x0 - 1)..(x0 + w)) for (yy in (y0 - 1)..(y0 + h))
                    if (xx in 0 until innerColumns && yy in 0 until rows) near[si][xx][yy] = true
                placed[si to ci] = (0 until n).map { k -> cell(x0 + k % w, y0 + k / w).offsetEdges(-0.03 * minOf(cw, ch)) }
            }
        clusters.mapIndexed { si, state ->
            val here: List<Rectangle?> = state.indices.flatMap { ci -> placed.getValue(si to ci) }
            here + List(units - here.size) { null }
        }
    }

    /** Every plot any unit stands on in any arrangement: the building is never packed there. */
    private val sites: List<Rectangle> = slots.flatten().filterNotNull()

    /**
     * The small elements' plots, `smallSlots[unit][k]`: cells of the finer grid clear of roads, parks
     * and the big buildings' footprints, every one its own. They may stand among the blue: the blue
     * gives way under them while they stand there.
     */
    private val smallSlots: List<List<Rectangle>> = if (smallUnits <= 0) emptyList() else run {
        val area = Rectangle(0.0, 0.0, wallWidth, wallHeight)
        val rows = MosaicCells.rows(area, smallColumns)
        val cw = wallWidth / smallColumns
        val ch = wallHeight / rows
        val random = Random(seed * 97)
        val cells = (0 until rows).flatMap { y -> (0 until smallColumns).map { x -> Rectangle(x * cw, y * ch, cw, ch) } }
            .filter { r ->
                sites.none { it.intersects(r) } && parks.none { it.intersects(r.offsetEdges(6.0)) } &&
                    roads.none { road -> road.points.any { r.offsetEdges(road.width / 2.0 + 6.0).contains(it) } }
            }.shuffled(random)
        if (cells.size < smallUnits * smallSites) println("shadow mosaic: only ${cells.size} small plots")
        List(smallUnits) { j -> List(smallSites) { k -> cells[(j * smallSites + k) % cells.size].offsetEdges(-0.06 * minOf(cw, ch)) } }
    }

    /** A box: its rectangle, and the delay it runs its cycle on, a share of the cycle. */
    private class Box(val rect: Rectangle, val delay: Double)

    /**
     * The boxes, dealt once from the seed: each projector's grid of coarse cells cut in two along its
     * longer side at a random whole cell, and again, [boxSplit] of the time at each depth — so the
     * boxes are runs of whole cells in several sizes and no mark straddles two.
     */
    private val boxes: List<Box> = run {
        val random = Random(seed * 17)
        val across = boxColumns ?: columns
        val rows = MosaicCells.rows(Rectangle(0.0, 0.0, wallWidth, wallHeight), across)
        val cw = wallWidth / across
        val ch = wallHeight / rows
        val perPane = (pane / cw).toInt().coerceAtLeast(1)
        val out = mutableListOf<Rectangle>()
        fun cut(x: Int, y: Int, w: Int, h: Int, depth: Int) {
            val chance = boxSplit.getOrElse(depth) { 0.0 }
            if (w * h > 1 && random.nextDouble() < chance) {
                if (w >= h && w > 1) {
                    val k = 1 + random.nextInt(w - 1)
                    cut(x, y, k, h, depth + 1); cut(x + k, y, w - k, h, depth + 1); return
                }
                if (h > 1) {
                    val k = 1 + random.nextInt(h - 1)
                    cut(x, y, w, k, depth + 1); cut(x, y + k, w, h - k, depth + 1); return
                }
            }
            out += Rectangle(x * cw, y * ch, w * cw, h * ch)
        }
        for (p in 0 until (across + perPane - 1) / perPane) cut(p * perPane, 0, minOf(perPane, across - p * perPane), rows, 0)
        val dealt = out.take(MAX_BOXES).map { Box(it, random.nextDouble()) }
        val paired = if (!handover) dealt else {
            // In pairs half a cycle apart: as one builds, its partner builds down.
            val order = dealt.indices.shuffled(Random(seed * 29))
            val delay = dealt.map { it.delay }.toMutableList()
            order.chunked(2).filter { it.size == 2 }.forEach { (a, b) -> delay[b] = (delay[a] + 0.5) % 1.0 }
            dealt.mapIndexed { i, b -> Box(b.rect, delay[i]) }
        }
        // Turned round the cycle so the earliest box starts the moment the dark hold ends: the build
        // is under way as the wall comes up rather than a random share of a cycle later. A rotation,
        // so the boxes keep their spacing and the pairs stay half a cycle apart.
        val first = paired.minOfOrNull { it.delay } ?: 0.0
        paired.map { Box(it.rect, it.delay - first) }
    }

    /** Which box a point stands in. */
    private fun boxOf(at: Vector2): Int = boxes.indexOfFirst { it.rect.contains(at) }.coerceAtLeast(0)


    // ---- the wall's build as MIDI ----------------------------------------------------------- //
    //
    //  **Every mark that moves is a note**, on the very timing the shader stands it by: a mark is
    //  not scheduled at all, it simply stands as far as its box's circle has reached it, so the
    //  score is that rule read backwards. The rim grows as `R(1 - (1 - u)^3)` over [grow], so the
    //  second it arrives at a mark [d] away is `grow * (1 - cbrt(1 - d/R))` exactly — and the note
    //  lasts as long as the rim takes to cross the mark's own [band], which is how long the mark
    //  takes to rise or go down.
    //
    //  The red units and the small red elements, where a wall has them, are not in the file: they
    //  are carried rather than raised, and this scores what is built.

    /** A mark as the score reads it: where it stands, and whose circle reaches it. */
    private class Placed(val centre: Vector2, val box: Int)
    private var darkMarks: List<Placed> = emptyList()
    private var lightMarks: List<Placed> = emptyList()

    /** A wall with no end of its own says how much of itself is written down. */
    override val midiFrames: Int get() = frames(midiWindow)

    override val lanes: List<String> get() = listOf("light up", "dark down", "light down", "dark up")

    override fun arrivals(clicks: List<Int>): List<Arrival> {
        if (lightMarks.isEmpty() && darkMarks.isEmpty()) return super.arrivals(clicks)
        val window = frames(midiWindow)
        val wants = mutableListOf<Want>()

        /** The second within a fill at which the rim reaches [d], on a box of radius [radius]. */
        fun rimAt(d: Double, radius: Double): Double =
            grow * (1.0 - Math.cbrt(1.0 - (d / radius).coerceIn(0.0, 1.0)))

        /** Wall seconds as a frame of the clip, or null where it falls outside the window. */
        fun frameAt(at: Double): Int? = ((at - lead) * FPS).toInt().takeIf { it in 0..window }

        fun score(marks: List<Placed>, rising: Int, falling: Int) = marks.forEach { m ->
            val b = boxes.getOrNull(m.box) ?: return@forEach
            val radius = b.rect.center.distanceTo(b.rect.corner) + band
            val d = m.centre.distanceTo(b.rect.center)
            val crossing = rimAt(d + band / 2.0, radius) - rimAt(d - band / 2.0, radius)
            val length = frames(crossing).coerceAtLeast(1)
            // Top of the wall highest, so a circle opening in a box reads as a spread rather than
            // as a run: every mark it reaches at once is a different pitch.
            val pitch = ((1.0 - m.centre.y / wallHeight) * (SCORE_NOTES - 1)).toInt().coerceIn(0, SCORE_NOTES - 1)
            val first = dark0 + b.delay * period + rimAt(d - band / 2.0, radius)
            var cycle = 0
            while (first + cycle * period - lead <= midiWindow + period) {
                val fill = first + cycle * period
                // A mark still rising as the window closes is cut off with it: the file ends where
                // the clip does, rather than sounding over a picture that has stopped.
                fun want(lane: Int, at: Int) =
                    Want(lane, pitch, at, minOf(length, window - at).coerceAtLeast(1), 0, SCORE_NOTES - 1)
                frameAt(fill)?.let { wants += want(rising, it) }
                frameAt(fill + grow + hold)?.let { wants += want(falling, it) }
                cycle++
            }
        }
        // The two packings are one event seen twice: where the light circle raises a light mark it
        // takes the dark one that stood there down, and the dark circle does the reverse.
        score(lightMarks, 0, 2)
        score(darkMarks, 1, 3)
        return voiced(wants)
    }

    override fun load(program: Program) {
        fun buffer() = renderTarget(wallWidth.toInt(), wallHeight.toInt()) { colorBuffer(type = ColorType.FLOAT32) }
        plan = renderTarget(wallWidth.toInt(), wallHeight.toInt()) {
            colorBuffer(type = ColorType.FLOAT32)
            depthBuffer()
        }
        ping = buffer(); pong = buffer()
        card = renderTarget(wallWidth.toInt(), wallHeight.toInt()) { colorBuffer() }
        stone = concrete?.let { file ->
            if (!file.isFile) { println("shadow mosaic: no concrete at ${file.path}"); null }
            else loadImage(file).also {
                it.wrapU = WrapMode.REPEAT; it.wrapV = WrapMode.REPEAT
                it.generateMipmaps(); it.filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
            }
        }
        val templates = marks()
        if (templates.isEmpty()) println("shadow mosaic: no marks, plain slabs instead")
        val wall = Rectangle(0.0, 0.0, wallWidth, wallHeight)
        println("shadow mosaic: ${boxes.size} boxes, a cycle of ${period}s")
        field = pack(wall, columns, Random(seed), templates)
            .also { darkMarks = it.second; println("shadow mosaic: ${it.second.size} dark marks") }.first
        inner = pack(wall, innerColumns, Random(seed + 1), templates, accents, blues)
            .also { lightMarks = it.second; println("shadow mosaic: ${it.second.size} light marks") }.first
        if (units > 0) unitMarks = unitBuffers(templates)
        if (smallUnits > 0) smallMarks = smallBuffers(templates)
        if (city) {
            ground = renderTarget(wallWidth.toInt(), wallHeight.toInt()) {
                colorBuffer(type = ColorType.FLOAT16)
                depthBuffer(DepthFormat.DEPTH24_STENCIL8)       // stroked contours need a stencil
            }.also { g ->
                // The plan on the ground, drawn once: paper, the parks, the roads over them.
                program.drawer.isolatedWithTarget(g) {
                    ortho(g)
                    clear(paper)
                    stroke = null
                    fill = parkTone
                    parks.forEach { rectangle(it) }
                    fill = null
                    stroke = roadTone
                    roads.forEach { r -> strokeWeight = r.width; contour(r.path) }
                }
            }
            println("shadow mosaic: a city plan of ${roads.size} roads and ${parks.size} parks")
        }
    }

    /**
     * A unit, built about the origin at a plot's size and carried as one: its plot split into a mix
     * of elements — whole, in halves, in quarters or finer, by the highlight's own split — each the
     * catalogue mark nearest its proportion at a height of its own. So a red building is a mix of big
     * and small WN elements rather than a row of one slab. Red, never ageing.
     */
    private fun unitBuffers(templates: List<Pair<List<Vector2>, Double>>): List<VertexBuffer> {
        val random = Random(seed * 83)
        val plot = slots.first().first { it != null }!!
        val square = listOf(
            Vector2(-0.5, -0.5), Vector2(0.5, -0.5), Vector2(0.5, 0.5),
            Vector2(-0.5, -0.5), Vector2(0.5, 0.5), Vector2(-0.5, 0.5)
        )
        val format = vertexFormat {
            position(3)
            attribute("leaf", VertexElementType.VECTOR4_FLOAT32)
            attribute("origin", VertexElementType.VECTOR2_FLOAT32)
        }
        return List(units) {
            class Piece(val box: Rectangle, val triangles: List<Vector2>, val sx: Double, val leaf: Vector4)
            val pieces = mutableListOf<Piece>()
            // How deep this unit is split: whole, once, or twice — so the units run from one element
            // to about eight. The highlight's own split went to fifteen slivers in a plot.
            val depth = listOf(0, 1, 1, 2, 2)[random.nextInt(5)]
            val area = Rectangle(-plot.width / 2.0, -plot.height / 2.0, plot.width, plot.height)
            fun add(r: Rectangle) {
                val box = r.offsetEdges(-gap / 2.0)
                val nearest = templates.indices.sortedBy { abs(ln(templates[it].second / (box.width / box.height))) }.take(3)
                val t = nearest.getOrNull(random.nextInt(nearest.size.coerceAtLeast(1)))?.let { templates[it] }
                val sx = if (t != null) box.width / t.second else box.width
                pieces += Piece(box, t?.first ?: square, sx, Vector4(0.7 + 0.3 * random.nextDouble(), 0.7 + 0.25 * random.nextDouble(), 0.0, 1.0))
            }
            fun split(r: Rectangle, d: Int) {
                if (d >= depth || (d > 0 && random.nextDouble() < 0.4) || minOf(r.width, r.height) < 60.0) { add(r); return }
                val hw = r.width / 2.0; val hh = r.height / 2.0
                val parts = when {
                    random.nextDouble() < 0.4 -> listOf(Rectangle(r.x, r.y, hw, hh), Rectangle(r.x + hw, r.y, hw, hh),
                        Rectangle(r.x, r.y + hh, hw, hh), Rectangle(r.x + hw, r.y + hh, hw, hh))
                    r.width >= r.height -> listOf(Rectangle(r.x, r.y, hw, r.height), Rectangle(r.x + hw, r.y, hw, r.height))
                    else -> listOf(Rectangle(r.x, r.y, r.width, hh), Rectangle(r.x, r.y + hh, r.width, hh))
                }
                parts.forEach { split(it, d + 1) }
            }
            split(area, 0)
            val count = pieces.sumOf { it.triangles.size }
            vertexBuffer(format, count.coerceAtLeast(3)).also { vb ->
                vb.put {
                    pieces.forEach { p ->
                        p.triangles.forEach { v ->
                            write(Vector3(p.box.center.x + v.x * p.sx, p.box.center.y - v.y * p.box.height, 0.0))
                            write(p.leaf); write(Vector2.ZERO)
                        }
                    }
                    repeat((3 - count).coerceAtLeast(0)) { write(Vector3.ZERO); write(Vector4.ZERO); write(Vector2.ZERO) }
                }
            }.also { _ -> println("shadow mosaic: a red unit of ${pieces.size} elements") }
        }.also { println("shadow mosaic: $units red units in ${clusters.size} arrangements") }
    }

    /** A small element about the origin at a small plot's size: the catalogue mark nearest its shape. */
    private fun smallBuffers(templates: List<Pair<List<Vector2>, Double>>): List<VertexBuffer> {
        val random = Random(seed * 101)
        val plot = smallSlots.first().first()
        val square = listOf(
            Vector2(-0.5, -0.5), Vector2(0.5, -0.5), Vector2(0.5, 0.5),
            Vector2(-0.5, -0.5), Vector2(0.5, 0.5), Vector2(-0.5, 0.5)
        )
        val nearest = templates.indices.sortedBy { abs(ln(templates[it].second / (plot.width / plot.height))) }.take(4)
        val format = vertexFormat {
            position(3)
            attribute("leaf", VertexElementType.VECTOR4_FLOAT32)
            attribute("origin", VertexElementType.VECTOR2_FLOAT32)
        }
        return List(smallUnits) {
            val t = nearest.getOrNull(random.nextInt(nearest.size.coerceAtLeast(1)))?.let { templates[it] }
            val triangles = t?.first ?: square
            val sx = if (t != null) plot.width / t.second else plot.width
            val leaf = Vector4(0.6 + 0.3 * random.nextDouble(), 0.7 + 0.25 * random.nextDouble(), 0.0, 1.0)
            vertexBuffer(format, triangles.size.coerceAtLeast(3)).also { vb ->
                vb.put {
                    triangles.forEach { v -> write(Vector3(v.x * sx, -v.y * plot.height, 0.0)); write(leaf); write(Vector2.ZERO) }
                    repeat((3 - triangles.size).coerceAtLeast(0)) { write(Vector3.ZERO); write(Vector4.ZERO); write(Vector2.ZERO) }
                }
            }
        }.also { println("shadow mosaic: $smallUnits small red elements, one every ${smallEvery}s from ${smallFirst}s") }
    }

    /**
     * Small element [j] at [time]: where it stands, how far up, and how much of its plot it holds —
     * of the plot it is leaving and of the one it is coming to, 0 to 1 each — or null before it has
     * arrived. It is built up out of the floor on its first plot, stands, and at the end of every
     * [smallHop] is lifted, carried and set down on the next. Its plot is held from the moment it is
     * there to the moment it is lifted, which is what the blue under it gives way to.
     */
    private fun smallAt(j: Int, time: Double): Triple<Vector2, Double, Vector2>? {
        val tau = time - (dark0 + smallFirst + j * smallEvery)
        if (tau < 0.0) return null
        fun smoother(u: Double) = u.coerceIn(0.0, 1.0).let { it * it * it * (it * (it * 6.0 - 15.0) + 10.0) }
        val c = tau.mod(smallSites * smallHop)
        val n = floor(c / smallHop).toInt()
        val w = c - n * smallHop
        val from = smallSlots[j][n].center
        val to = smallSlots[j][(n + 1) % smallSites].center
        val arrive = if (tau < smallHop) smoother(tau / 1.5) else 1.0
        val u = ((w - (smallHop - smallMove)) / smallMove).coerceIn(0.0, 1.0)
        val up = smoother(u / 0.25) - smoother((u - 0.75) / 0.25)
        val at = from + (to - from) * smoother((u - 0.25) / 0.5)
        val holdsFrom = arrive * (1.0 - smoother(u / 0.25))
        val holdsTo = smoother((u - 0.75) / 0.25)
        return Triple(at, arrive * (1.0 + unitLift * up), Vector2(holdsFrom, holdsTo))
    }

    /** Every small plot held at [time], as `x, y, held, 0`: the blue under a held plot gives way. */
    private fun claimsAt(time: Double): Array<Vector4> {
        val out = Array(MAX_CLAIMS) { Vector4.ZERO }
        var k = 0
        for (j in 0 until smallUnits) {
            val (_, _, held) = smallAt(j, time) ?: continue
            val tau = time - (dark0 + smallFirst + j * smallEvery)
            val n = floor(tau.mod(smallSites * smallHop) / smallHop).toInt()
            if (k < MAX_CLAIMS) out[k++] = smallSlots[j][n].center.let { Vector4(it.x, it.y, held.x, 0.0) }
            if (k < MAX_CLAIMS) out[k++] = smallSlots[j][(n + 1) % smallSites].center.let { Vector4(it.x, it.y, held.y, 0.0) }
        }
        return out
    }

    /** When the red units' first arrangement stands whole, every unit up and none yet leaving. */
    private val unitsStanding: Double get() = dark0 + 3.0 + stateLength - ((units - 1) * stagger + move) - 0.05

    /** When they stand whole in the second arrangement, every move of the first round done. */
    private val unitsMoved: Double get() = dark0 + 3.0 + stateLength + 0.4 * (units - 1) + 0.3

    /**
     * Unit [i] at [time]: where it stands and how far up, or null while it is not on the wall. The
     * first arrangement's units come up a little after the wall opens, a beat apart. Each
     * arrangement stands, and at the end of its [stateLength] the units are moved one by one to the
     * next — unit `i` setting off `i · stagger` into the move — lifted over the first quarter of
     * [move], carried over the middle half, set down over the last. A unit the next arrangement has
     * and this one has not is new, and is built up out of the floor where it will stand, the way
     * anything is added to the wall; one this arrangement has and the next has not is taken down
     * into the floor where it stands. Flying them in and off from beyond the head of the wall was
     * tried first and read as the elements appearing out of a corner. Periodic in the round.
     */
    private fun unitAt(i: Int, time: Double): Pair<Vector2, Double>? {
        val start = dark0 + 3.0 + 0.4 * i
        val tau = time - start
        if (tau < 0.0) return null
        val states = slots.size
        val c = tau.mod(states * stateLength)
        val n = floor(c / stateLength).toInt()
        val w = c - n * stateLength
        val here = slots[n][i]
        val next = slots[(n + 1) % states][i]
        fun smoother(u: Double) = u.coerceIn(0.0, 1.0).let { it * it * it * (it * (it * 6.0 - 15.0) + 10.0) }
        val leave = stateLength - ((units - 1) * stagger + move)
        val u = ((w - leave - i * stagger) / move).coerceIn(0.0, 1.0)
        val from = here?.center ?: next?.center ?: return null
        val to = next?.center ?: from
        // The very first arrival, before any move: the first arrangement coming up where it stands.
        val arrive = if (here != null && tau < stateLength) smoother(tau / 1.5) else 1.0
        if (here == null && u <= 0.0) return null                  // not built yet
        if (next == null && u >= 1.0) return null                  // taken down
        if (here == null) return to to smoother(u)                 // built up out of the floor
        if (next == null) return from to arrive * (1.0 - smoother(u))  // taken down into it
        val up = smoother(u / 0.25) - smoother((u - 0.75) / 0.25)
        val at = from + (to - from) * smoother((u - 0.25) / 0.5)
        return at to arrive * (1.0 + unitLift * up)
    }

    /**
     * The highlight's split over [area], a mark a leaf, into one buffer: each vertex carries its
     * mark's height, tone and box, and the mark's centre, which its box's circle is measured to. The
     * count of marks comes back with it.
     */
    private fun pack(
        area: Rectangle, columns: Int, random: Random, templates: List<Pair<List<Vector2>, Double>>,
        accentShare: Double = 0.0, blueShare: Double = 0.0
    ): Pair<VertexBuffer, List<Placed>> {
        val square = listOf(
            Vector2(-0.5, -0.5), Vector2(0.5, -0.5), Vector2(0.5, 0.5),
            Vector2(-0.5, -0.5), Vector2(0.5, 0.5), Vector2(-0.5, 0.5)
        )
        class Leaf(val box: Rectangle, val mark: Int, val leaf: Vector4)
        val leaves = mutableListOf<Leaf>()
        val rows = MosaicCells.rows(area, columns)
        val cw = area.width / columns
        val ch = area.height / rows
        for (row in 0 until rows) for (column in 0 until columns) {
            MosaicCells.split(Rectangle(area.x + column * cw, area.y + row * ch, cw, ch), random) { r ->
                val box = r.offsetEdges(-gap / 2.0)
                val aspect = box.width / box.height
                val nearest = templates.indices.sortedBy { abs(ln(templates[it].second / aspect)) }.take(3)
                val mark = if (nearest.isEmpty()) -1 else nearest[random.nextInt(nearest.size)]
                // Mostly mid greys with a few near white, as the reference has them.
                val t = random.nextDouble()
                val tone = dark + (light - dark) * t * t
                val height = low + (highest - low) * random.nextDouble()
                // Drawn only when there are accents, so the grey wall's stream of randoms is untouched:
                // 1 is the red ramp, 2 the dark state's blue.
                val accent = if (accentShare <= 0.0 && blueShare <= 0.0) 0.0 else random.nextDouble().let {
                    if (it < accentShare) 1.0 else if (it < accentShare + blueShare) 2.0 else 0.0
                }
                if (!blocked(r)) leaves += Leaf(box, mark, Vector4(height, tone, boxOf(box.center).toDouble(), accent))
            }
        }
        val count = leaves.sumOf { (templates.getOrNull(it.mark)?.first ?: square).size }
        val format = vertexFormat {
            position(3)
            attribute("leaf", VertexElementType.VECTOR4_FLOAT32)
            attribute("origin", VertexElementType.VECTOR2_FLOAT32)
        }
        val buffer = vertexBuffer(format, count.coerceAtLeast(3)).also { vb ->
            vb.put {
                leaves.forEach { l ->
                    val t = templates.getOrNull(l.mark)
                    val triangles = t?.first ?: square
                    // A mark is height 1 and its proportion wide, y up: stretched to fill its cell.
                    val sx = if (t != null) l.box.width / t.second else l.box.width
                    val sy = l.box.height
                    triangles.forEach { p ->
                        write(Vector3(l.box.center.x + p.x * sx, l.box.center.y - p.y * sy, 0.0))
                        write(l.leaf)
                        write(l.box.center)
                    }
                }
                repeat((3 - count).coerceAtLeast(0)) { write(Vector3.ZERO); write(Vector4.ZERO); write(Vector2.ZERO) }
            }
        }
        return buffer to leaves.map { Placed(it.box.center, it.leaf.z.toInt()) }
    }

    /**
     * Every box as its shader reads it: its middle, the radius that fills it — to its far corner and
     * the band past it — and the second it first starts to fill.
     */
    private val boxUniforms: Array<Vector4> = Array(MAX_BOXES) { i ->
        boxes.getOrNull(i)?.let { b ->
            Vector4(b.rect.center.x, b.rect.center.y, b.rect.center.distanceTo(b.rect.corner) + band, dark0 + b.delay * period)
        } ?: Vector4.ZERO
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val fieldBuffer = field ?: return
        val innerBuffer = inner ?: return
        // Not wrapped: before a box's first start it stands dark, so the opening is a reveal.
        val time = seconds(stage.frame) + lead
        val unitTime = unitsOnClick?.let { k ->
            val base = minOf(time, unitsStanding)
            base + linear(stage.on(k)) * (unitsMoved - base)
        } ?: time
        val theta = Math.toRadians(angle)
        val direction = Vector2(cos(theta), sin(theta))

        drawer.isolated {
            drawer.isolatedWithTarget(plan) {
                drawer.ortho(plan)
                drawer.clear(ColorRGBa.TRANSPARENT)
                drawer.depthWrite = true
                drawer.depthTestPass = DepthTestPass.LESS_OR_EQUAL
                drawer.fill = ColorRGBa.WHITE
                drawer.stroke = null
                plant.parameter("boxes", boxUniforms)
                plant.parameter("claims", claimsAt(time))
                plant.parameter("useClaims", if (smallUnits > 0) 1 else 0)
                plant.parameter("claimHalf", smallSlots.firstOrNull()?.firstOrNull()?.let {
                    Vector2(it.width / 2.0 + 30.0, it.height / 2.0 + 30.0) } ?: Vector2.ZERO)
                plant.parameter("time", time)
                plant.parameter("grow", grow)
                plant.parameter("hold", hold)
                plant.parameter("period", period)
                plant.parameter("band", band)
                drawer.shadeStyle = plant
                plant.parameter("at", Vector2.ZERO)
                plant.parameter("lift", 1.0)
                if (!handover) {
                    plant.parameter("lit", 0)
                    drawer.vertexBuffer(fieldBuffer, DrawPrimitive.TRIANGLES)
                }
                plant.parameter("lit", 1)
                plant.parameter("at", Vector2.ZERO)
                plant.parameter("lift", 1.0)
                drawer.vertexBuffer(innerBuffer, DrawPrimitive.TRIANGLES)
                // The red units, each at its own place and height: one draw a unit.
                smallMarks.forEachIndexed { j, buffer ->
                    val (at, h, _) = smallAt(j, time) ?: return@forEachIndexed
                    plant.parameter("lit", 3)
                    plant.parameter("at", at)
                    plant.parameter("lift", h)
                    drawer.vertexBuffer(buffer, DrawPrimitive.TRIANGLES)
                }
                unitMarks.forEachIndexed { i, buffer ->
                    val (at, h) = unitAt(i, unitTime) ?: return@forEachIndexed
                    plant.parameter("lit", 3)
                    plant.parameter("at", at)
                    plant.parameter("lift", h)
                    drawer.vertexBuffer(buffer, DrawPrimitive.TRIANGLES)
                }
                drawer.shadeStyle = null
            }
            val reached = shadow(drawer, direction)

            drawer.isolatedWithTarget(card) {
                drawer.ortho(card)
                drawer.clear(paper)
                lay.parameter("field", reached)
                lay.parameter("plan", plan.colorBuffer(0))
                lay.parameter("reach", reach)
                lay.parameter("soft", soft.coerceIn(0.01, 1.0))
                lay.parameter("paper", paper)
                lay.parameter("ink", ink)
                lay.parameter("shade", shade)
                lay.parameter("strength", shadowStrength)
                lay.parameter("useGround", if (ground != null) 1.0 else 0.0)
                lay.parameter("ground", ground?.colorBuffer(0) ?: plan.colorBuffer(0))
                // The grey wall's ramps, when no palette is given, are exactly what it drew before:
                // the dark state paper to [dim] of the way to ink, the light state paper to ink.
                // The grey wall's shader blended the raw values, which the canvas takes as linear light,
                // so the ramp's end is stated as linear. Marked sRGB it is linearised on the way up and
                // the dark field came out at a quarter of its brightness.
                fun toward(k: Double) = ColorRGBa(paper.r + (ink.r - paper.r) * k, paper.g + (ink.g - paper.g) * k,
                    paper.b + (ink.b - paper.b) * k, 1.0, Linearity.LINEAR)
                lay.parameter("fieldLow", fieldLow ?: paper)
                lay.parameter("fieldHigh", fieldHigh ?: toward(dim))
                lay.parameter("litLow", litLow ?: paper)
                lay.parameter("litHigh", litHigh ?: ink)
                lay.parameter("accentLow", accentLow ?: paper)
                lay.parameter("accentHigh", accentHigh ?: ink)
                lay.parameter("oldLow", oldLow ?: fieldLow ?: paper)
                lay.parameter("oldHigh", oldHigh ?: fieldHigh ?: toward(dim))
                drawer.shadeStyle = lay
                drawer.image(reached, 0.0, 0.0, wallWidth, wallHeight)
                drawer.shadeStyle = null
            }

            val s = stone
            if (s != null) {
                grain.parameter("source", card.colorBuffer(0))
                grain.parameter("roofs", plan.colorBuffer(0))
                grain.parameter("stone", s)
                grain.parameter("tile", Vector2(s.width.toDouble(), s.height.toDouble()))
                grain.parameter("pane", Vector2(wallWidth, wallHeight))
                grain.parameter("roofMix", roofMix)
                drawer.shadeStyle = grain
            }
            drawer.image(card.colorBuffer(0), stage.bounds.x, stage.bounds.y, stage.bounds.width, stage.bounds.height)
            drawer.shadeStyle = null
        }
    }

    // A mark stands as far as its box's cycle says, in both stages: the vertex puts its height in
    // depth so the taller of two lapping marks is the roof, the fragment writes it into the plan.
    // `light` is how much of the box the light marks hold at this mark: growing with the light
    // circle, full through the hold, giving way under the dark circle, none through the second hold.
    // A circle grows on an ease-out: it sets off at once and slows as it settles into the corners.
    // It was a smootherstep, which barely moves at the start — three seconds into a thirty-second
    // fill the circle had not reached 1% of its reach, and the wall read as stuck as it came up
    // (review of 22 September).
    private val plant = shadeStyle {
        val height = """
            vec4 b = p_boxes[int(va_leaf.z + 0.5)];
            // A unit's vertices are about the origin; its middle is where it stands.
            vec2 origin = va_origin + p_at;
            float t = p_time - b.w;
            // Before its first start the box stands dark, as it does at the end of every cycle.
            t = t < 0.0 ? p_period - 0.001 : mod(t, p_period);
            float d = distance(origin, b.xy);
            float light;
            if (t < p_grow + p_hold) {
                float u = clamp(t / p_grow, 0.0, 1.0);
                float r = b.z * (1.0 - (1.0 - u) * (1.0 - u) * (1.0 - u));
                light = smoothstep(0.0, 1.0, (r - d) / p_band + 0.5);
            } else {
                float u = clamp((t - p_grow - p_hold) / p_grow, 0.0, 1.0);
                float r = b.z * (1.0 - (1.0 - u) * (1.0 - u) * (1.0 - u));
                light = 1.0 - smoothstep(0.0, 1.0, (r - d) / p_band + 0.5);
            }
            float h = va_leaf.x * (p_lit == 3 ? p_lift : p_lit == 1 ? light : 1.0 - light);
            // A building mark gives way under a small red element's plot while it is held.
            if (p_lit == 1 && p_useClaims == 1) {
                float held = 0.0;
                for (int k = 0; k < 80; k++) {
                    vec4 cl = p_claims[k];
                    if (cl.z > 0.001) {
                        vec2 dd = abs(origin - cl.xy);
                        if (dd.x < p_claimHalf.x && dd.y < p_claimHalf.y) held = max(held, cl.z);
                    }
                }
                h *= 1.0 - held;
            }
            // The tone, with which ramp it reads from packed above it: 2 for a light mark, 4 more
            // for a red one, 8 more for a blue one, and for a blue one 16 a step of its age out of
            // 31 — from being built to being taken down. The lay pass takes them apart again.
            float age = clamp((t - p_grow) / max(p_hold, 0.001), 0.0, 1.0);
            float tone = min(va_leaf.y, 0.999) + (p_lit >= 1 ? 2.0 + 4.0 * va_leaf.w : 0.0)
                       + (p_lit == 1 && va_leaf.w > 1.5 ? 16.0 * floor(age * 31.0 + 0.5) : 0.0);
        """
        // Depth is the height, over a range that holds a unit carried high as well as the tallest mark.
        vertexTransform = height + """
            x_position.xy += p_at;
            x_position.z = clamp(h, 0.0, 2.0) * 0.45;
        """
        fragmentTransform = height + """
            if (h < 0.01) discard;
            x_fill = vec4(h, 1.0, tone, 1.0);
        """
    }

    // The plan into reach: red the pixels of shadow this pixel throws, -1 where nothing stands.
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
    }

    private fun pass(drawer: Drawer, into: RenderTarget, from: ColorBuffer) {
        drawer.isolatedWithTarget(into) {
            drawer.ortho(into)
            drawer.clear(ColorRGBa.TRANSPARENT)
            drawer.image(from, 0.0, 0.0, wallWidth, wallHeight)
        }
    }

    private fun shadow(drawer: Drawer, direction: Vector2): ColorBuffer {
        raise.parameter("source", plan.colorBuffer(0))
        raise.parameter("reach", reach)
        drawer.shadeStyle = raise
        pass(drawer, ping, plan.colorBuffer(0))

        val longest = reach * (1.2 + if (units > 0) unitLift else 0.0)
        val steps = mutableListOf<Double>()
        var covered = 0.0
        var step = 1.0
        while (covered + step <= longest) { steps += step; covered += step; step *= 2.0 }
        if (longest > covered) steps += longest - covered

        var from = ping.colorBuffer(0)
        drawer.shadeStyle = cast
        cast.parameter("pane", Vector2(wallWidth, wallHeight))
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

    // Roofs and ground off the plan and the reach together. A shadow is as dark as the reach it has
    // left against the longest a mark throws, over [soft] of that — full where it leaves its mark,
    // fading to nothing at its tip — and on a roof it counts only what reaches past the roof's own
    // height, so a taller mark shades a lower one and a roof never shades itself.
    private val lay = shadeStyle {
        fragmentTransform = """
            vec4 f = texture(p_field, va_texCoord0);
            vec4 m = texture(p_plan, va_texCoord0);
            float cov = m.g;
            float h = cov > 0.001 ? m.r / cov : 0.0;
            float v = cov > 0.001 ? m.b / cov : 0.0;
            float aged = floor(v / 16.0) / 31.0; v -= 16.0 * floor(v / 16.0);
            float accent = floor(v / 4.0); v -= 4.0 * accent;
            float lit = step(1.5, v); v -= 2.0 * lit;
            float tone = clamp(v, 0.0, 1.0);
            vec3 roof = lit > 0.5 && accent > 1.5
                        ? mix(mix(p_fieldLow.rgb, p_fieldHigh.rgb, tone), mix(p_oldLow.rgb, p_oldHigh.rgb, tone), aged)
                      : lit < 0.5 ? mix(p_fieldLow.rgb, p_fieldHigh.rgb, tone)
                      : accent > 0.5 ? mix(p_accentLow.rgb, p_accentHigh.rgb, tone)
                      : mix(p_litLow.rgb, p_litHigh.rgb, tone);
            vec3 base = p_useGround > 0.5 ? texture(p_ground, va_texCoord0).rgb : p_paper.rgb;
            float fade = p_reach * p_soft;
            // Square-rooted, so a shadow stays dark most of its way and lets go near the tip.
            float ground = p_strength * f.g * sqrt(clamp(f.r / fade, 0.0, 1.0));
            float over = p_strength * f.g * sqrt(clamp((f.r - h * p_reach) / fade, 0.0, 1.0));
            vec3 floorTone = mix(base, p_shade.rgb, ground);
            vec3 roofTone = mix(roof, p_shade.rgb, over);
            x_fill = vec4(mix(floorTone, roofTone, clamp(cov, 0.0, 1.0)), 1.0);
        """
    }

    // Stone on the roofs only, taken against its own average so white stays white; the ground is
    // left to the show's own concrete overlay.
    private val grain = shadeStyle {
        fragmentTransform = """
            vec3 tone = texture(p_source, va_texCoord0).rgb;
            vec2 at = vec2(va_texCoord0.x, 1.0 - va_texCoord0.y) * p_pane / p_tile;
            vec3 grain = texture(p_stone, at).rgb;
            vec3 mean = textureLod(p_stone, vec2(0.5), 20.0).rgb;
            vec3 marks = min(grain / max(mean, vec3(0.01)), vec3(1.0));
            vec3 top = tone * mix(vec3(1.0), marks, p_roofMix);
            float roof = clamp(texture(p_roofs, va_texCoord0).g, 0.0, 1.0);
            x_fill = vec4(clamp(mix(tone, top, roof), 0.0, 1.0), 1.0);
        """
    }

    private companion object {
        const val MAX_BOXES = 48
        const val MAX_CLAIMS = 80
    }
}
