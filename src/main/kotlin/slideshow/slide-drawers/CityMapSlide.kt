// ============================================================================ //
//  No `package` declaration, deliberately, and for the same reason Slideshow.kt
//  has none: the whole city pipeline — collectMapData, MapCamera, Mesh,
//  objectPlacements, gridMoves — lives in the default package, and Kotlin cannot
//  import from the default package into a named one. A drawer that stands on it
//  therefore has to sit in it too. The file still belongs in slide-drawers/,
//  which is a folder rather than a package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.isolated
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Cut
import slideshow.Mark
import slideshow.Slide
import slideshow.Stage
import slideshow.easeInOutCubic
import slideshow.frames
import slideshow.smoothstep
import java.io.File
import kotlin.math.min
import kotlin.math.pow

/**
 * `CityMap.kt` in the deck: the catalogue city pushed into on one click, and closed down
 * onto a single component on the next.
 *
 *     0   the whole town, every element already standing on the plan it was packed onto
 *     1   the camera pushes in, and the elements it comes to rest on lift off the plan
 *         into a grid
 *     2   the grid empties from the outside in until one element is left in the middle
 *
 * **What had to change coming across is the timing, and only the timing.** The sketch is a
 * film: a reveal on a clock, a flight over `CITY_DURATION`, the grid at `CITY_GRID_AT` for
 * `CITY_GRID_TIME`, the cull at `CITY_LAST_AT`. None of that survives a deck, because a
 * slide may be clicked backwards, jumped into and paused, and a timestamp can do none of
 * the three. Every one of those beats is a window on [Stage.position] here instead, so
 * clicking back closes the frame out again exactly the way clicking forward closed it in,
 * and the show can open on this slide fully built.
 *
 * The keys that say *what is drawn* are kept — `CITY_FABRIC`, `CITY_OBJECT_*`,
 * `CITY_INK`/`CITY_PAPER`, `CITY_ZOOM_FROM`/`_TO`, `CITY_GRID_MAX`, `CITY_LAST_*`, and the
 * `MAP_*` extent — so the slide comes up as whatever the sketch was last tuned to and the
 * two do not drift apart. The keys that say *when* are gone.
 *
 * **The reveal is gone with them.** Buildings arriving one at a time is a rate against a
 * clock, and a click is not a rate; the committed `.env` already ran this with
 * `CITY_BUILDINGS_ALWAYS=true`, which is the same picture. So the slide's opening state is
 * the finished plan, which is also what a slide's opening state has to be — something you
 * can hold on while you talk.
 *
 * The grid gathers over the back of the *first* click rather than on its own, so the click
 * is one move — the town closes in and orders itself as it arrives — and the slide comes to
 * rest on a settled grid you can talk over. The second click then only takes things away.
 */
class CityMapSlide(
    /**
     * Seconds a click takes. Both clicks share it, which is what a slide has; the push
     * wants to be slow enough to talk over and the cull reads well at the same length.
     */
    private val pace: Double = 12.0,
    /** Where the push starts and ends. Below 1 the extent no longer fills the frame. */
    private val zoomFrom: Double = Env["CITY_ZOOM_FROM"]?.toDoubleOrNull() ?: 1.0,
    private val zoomTo: Double = Env["CITY_ZOOM_TO"]?.toDoubleOrNull() ?: 7.0,
    /** How many elements take part in the grid. Past a few hundred it is a texture. */
    private val gridLimit: Int = Env["CITY_GRID_MAX"]?.toIntOrNull() ?: 120,
    /** How much of a cell an element may fill, leaving the rest as the gap around it. */
    private val gridFill: Double = Env["CITY_GRID_FILL"]?.toDoubleOrNull() ?: 0.8,
    /** far (outside in), near, file or random — see [cullOrder]. */
    private val lastOrder: String = Env["CITY_LAST_ORDER"] ?: "far",
    /** How much the survivor grows as the rest go. 1 leaves it at the size the plan gave it. */
    private val lastScale: Double = Env["CITY_LAST_SCALE"]?.toDoubleOrNull() ?: 4.0,
    private val ink: ColorRGBa = ColorRGBa.fromHex(Env["CITY_INK"] ?: "#FFFFFF"),
    override val background: ColorRGBa = ColorRGBa.fromHex(Env["CITY_PAPER"] ?: "#000000"),
    /**
     * The pane this composition is built for, and the one place this drawer does not lay
     * out against `stage.bounds`.
     *
     * It cannot: which elements join the grid is decided by *the frame the push comes to
     * rest on*, and the mesh is then built with those elements held out of it — all of
     * which happens in [load], which is not told the pane. Left to the first frame instead
     * it would be a second or so of packing and uploading at the moment the slide is
     * clicked to, which is the one thing `load` exists to prevent.
     *
     * So the shape is stated, and [draw] fits what was built into whatever pane it is
     * handed — the same fit `present` uses to put the canvas in the window, and for the
     * same reason: a pane of another shape should letterbox rather than distort.
     */
    private val paneWidth: Int = 1920,
    private val paneHeight: Int = 1080
) : Slide() {
    override val name = "City"
    override val steps = 3
    override val stepFrames = frames(pace)

    /**
     * A hard cut, not the deck's default fade.
     *
     * The quote before it is type on grey and this is a plan on black: crossing them over
     * dissolves one picture into an unrelated other, which reads as a wipe between two
     * images rather than as the talk moving on. The cut says the subject changed. It is the
     * same reasoning as the chapter card's — a heading is simply *there*.
     */
    override val transition = Cut

    /** The overlay times a click but cannot see what it does, so the slide says. */
    override fun stepName(step: Int) = when (step) {
        1 -> "push in"
        2 -> "close on one"
        else -> null
    }

    private lateinit var area: MapArea
    private lateinit var camera: MapCamera

    /** The city, less whatever is held out for the grid. One run of triangles per building. */
    private var fabric = Mesh(null, IntArray(0))
    private var ground: List<Pair<Mesh, ColorRGBa>> = emptyList()

    /** The elements that end the shot in a grid, one part each because they move alone. */
    private var moves: List<GridMove> = emptyList()
    private var movers = Mesh(null, IntArray(0))
    private var ranks = IntArray(0)

    /** The middle of the frame the push rests on, which is where the last element ends. */
    private var closingCentre = Vector2.ZERO

    /**
     * The one element left standing at the end, handed on so the next slide can open on
     * this slide's own last frame and the cut between them is invisible.
     *
     * Read it in the *next* slide's [load], never before: it is worked out in this one's,
     * and `present` loads slides in the order they are declared. Null until then, and null
     * for good on the footprints fabric, where there are no elements to close on.
     */
    var closingMark: Mark? = null
        private set

    /**
     * Both ends of the push, floored so the edge of the collected data never comes into
     * shot as a straight line of background. Only the wider end can show it, and which end
     * that is depends on the direction, so both are floored and the question is not asked.
     */
    private var pushFrom = 1.0
    private var pushTo = 1.0

    /**
     * Collecting, packing and triangulating the whole city — seconds of work, and all of it
     * here rather than on the click that brings the slide up.
     *
     * `collectMapData` reads `data/collected` and only goes to PDOK when that is empty or
     * `MAP_REFRESH` is set, so this is disk and CPU rather than network on an ordinary run.
     */
    override fun load(program: Program) {
        area = collectMapData()

        val focus = Env["CITY_FOCUS"]?.let { query ->
            Regex("""\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*""").matchEntire(query)?.let {
                Vector2(it.groupValues[1].toDouble(), it.groupValues[2].toDouble())
            } ?: resolvePlace(query)
        }

        camera = MapCamera(
            area, paneWidth, paneHeight,
            cover = Env["CITY_FIT"]?.lowercase() != "contain",
            homeZoom = zoomFrom,
            focusPoint = focus
        )
        pushFrom = maxOf(zoomFrom, camera.zoomFillingFrame())
        pushTo = maxOf(zoomTo, camera.zoomFillingFrame())

        val buildings = area.features("buildings")

        if ((Env["CITY_FABRIC"] ?: "footprints").lowercase() == "objects") {
            val sheet = File(Env["CITY_OBJECT_SHEET"] ?: "data/svg/objects-iso.svg")
            val templates = loadObjectTemplates(sheet)
            val placements = objectPlacements(
                buildings, templates,
                objectSize = Env["CITY_OBJECT_SIZE"]?.toDoubleOrNull() ?: 8.0,
                gap = Env["CITY_OBJECT_GAP"]?.toDoubleOrNull() ?: 1.0,
                maxPerBuilding = Env["CITY_OBJECT_MAX"]?.toIntOrNull() ?: 48
            )

            // The frame the push comes to rest on, so the elements that take part are
            // exactly the ones on screen at the end and none arrive from off frame.
            val closing = camera.worldFrame(pushTo)
            closingCentre = closing.center
            moves = gridMoves(placements, templates, closing, gridLimit, gridFill)
            ranks = cullOrder(moves, closing, lastOrder)
            movers = meshOfTriangles(moves.map { it.placement.triangles(templates) }, area.origin)

            // Held *out* of the fabric rather than drawn over it: at rest they sit exactly
            // where the packing put them, so before the grid begins the picture is the same
            // one either way and there is no ghost left behind as they leave.
            // What the very last frame of this slide holds, in the units a pane draws in.
            // Worked out here rather than measured off the screen: the survivor ends on the
            // camera's own focus, which is the middle of the pane, at `move.scale *
            // lastScale` of the size the plan gave it.
            val survivor = ranks.indexOfFirst { it < 0 }
            if (survivor >= 0) {
                val move = moves[survivor]
                val k = move.scale * lastScale
                closingMark = Mark(
                    // y up in a world, y down in a pane
                    triangles = templates[move.placement.template].triangles.map { Vector2(it.x, -it.y) },
                    height = camera.pixelsPerMetre(pushTo) * k * move.placement.height,
                    ink = ink,
                    paper = background
                )
            }

            val taken = moves.mapTo(HashSet()) { it.placement }
            fabric = meshOfTriangles(
                placements.map { building ->
                    building.filter { it !in taken }.flatMap { it.triangles(templates) }
                },
                area.origin
            )
        } else {
            fabric = meshOf(buildings.map { it.shapes() }, area.origin)
        }

        // The ground the figure stands in, at full strength — the sketch fades it up on a
        // clock, and there is no clock here. "none" rather than an empty value, because Env
        // reads a blank as unset and would hand back the default.
        val wanted = (Env["CITY_GROUND_LAYERS"] ?: "nature,water,roads")
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() && it != "none" }
        ground = listOf("nature" to NATURE, "water" to WATER, "roads" to ROAD)
            .filter { it.first in wanted }
            .map { area.meshOfLayer(it.first) to it.second }

        println("city slide: ${fabric.parts} plans, ${moves.size} elements in the closing grid")
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        // Geometric, so the push reads at an even speed rather than racing at the start and
        // crawling at the end. The centre never moves: the town opens out around a fixed
        // point rather than the camera chasing anything across it.
        //
        // `on(1)` and nothing else — **the deck has already eased it.** `position` is
        // `easeInOutCubic` of the click's own ramp, so a slide that eases it again is
        // easing twice: measured off a 12s click that way, the camera had finished moving
        // by 8.5s and the last three and a half seconds were a still frame. Whatever
        // travels here is drawn straight off `position` and lets the deck do the easing.
        camera.zoom = pushFrom * (pushTo / pushFrom).pow(stage.on(1))

        // ...and whatever is *counted* rather than travelled undoes it instead, which is
        // what [linear] is for. The grid gathers over the back of the click at an even
        // rate, with a smoothstep of its own so it does not start and stop with an edge —
        // it is keyed off an interval of the click rather than off the whole of it, and a
        // remap like that has a corner in it wherever it begins.
        val settle = smoothstep((linear(stage.on(1)) - GATHERS_AT) / (1.0 - GATHERS_AT))

        // The closing beat runs over the first [CULL] of its click rather than the whole
        // of it: the field empties briskly and the one left standing comes forward with
        // it, and the rest of the click is the slide holding on that. A click is one
        // length for the whole slide, so a beat that wants to be shorter than the push
        // says so as a fraction of it.
        val cull = (linear(stage.on(2)) / CULL).coerceAtMost(1.0)

        // Evenly: an eased count crowds the removals into the middle and reads as a swell,
        // where an even rate reads as a count down to one. The survivor's own move is
        // travel rather than a count, so it takes an ease of its own.
        val emptying = cull
        val closingIn = smoothstep(cull)

        // The composition was built for [paneWidth] x [paneHeight]; fit it into the pane it
        // actually got, centred. On the show's own 1920x1080 slide pane this is 1:1.
        val fit = min(stage.width / paneWidth, stage.height / paneHeight)
        val corner = stage.center - Vector2(paneWidth * fit, paneHeight * fit) / 2.0

        drawer.stroke = null
        drawer.isolated {
            // A bbox query returns every feature that *touches* the box, so a canal or a
            // rail surface can reach kilometres past it and streak across the paper. The
            // clip is a scissor in target pixels and knows nothing of the transforms below,
            // so it is carried through the fit by hand.
            val clip = camera.clip()
            drawer.drawStyle.clip = Rectangle(
                corner.x + clip.corner.x * fit, corner.y + clip.corner.y * fit,
                clip.width * fit, clip.height * fit
            )

            drawer.translate(corner)
            drawer.scale(fit, fit)
            camera.apply(drawer)

            ground.forEach { (mesh, colour) -> mesh.draw(drawer, colour) }
            fabric.draw(drawer, ink)

            // The grid. A call each is affordable where it would not be for the city,
            // because this is the elements in one frame — a hundred or so, capped by
            // gridLimit — rather than every element on the map.
            // How many have been taken away by now. A whole number and a hard test: an
            // element is simply not there once its number has come up, one after another
            // at an even rate. Fading them out was tried and is worse — a dozen elements
            // part-way out at any moment reads as the whole field dimming, where an
            // instant removal reads as a count.
            val gone = (emptying * (moves.size - 1)).toInt()
            moves.forEachIndexed { index, move ->
                val rank = if (index < ranks.size) ranks[index] else -1
                if (rank in 0 until gone) return@forEachIndexed

                var at = move.home * (1.0 - settle) + move.slot * settle

                // The survivor takes the middle of the frame as the others go. Its slot is
                // only the *nearest* cell centre to it, and with an even number of rows the
                // middle of the frame falls on a cell edge — so left in its slot the last
                // element sits half a cell off centre, which is 90px of a 1080px frame.
                if (rank < 0 && ranks.isNotEmpty())
                    at = at * (1.0 - closingIn) + closingCentre * closingIn

                var k = 1.0 + (move.scale - 1.0) * settle
                if (rank < 0 && ranks.isNotEmpty() && lastScale != 1.0)
                    k *= 1.0 + (lastScale - 1.0) * closingIn

                // v -> at + k(v - home), in the mesh's own coordinates, which are relative
                // to the extent's centre (see meshOfTriangles). A translation does not care
                // — a delta is a delta — but a scale is about a *point*, and taking that
                // point in RD metres instead throws the element out by origin * (k - 1),
                // which is a hundred and fifty kilometres.
                drawer.isolated {
                    drawer.translate(at - area.origin)
                    drawer.scale(k, k)
                    drawer.translate(area.origin - move.home)
                    movers.drawRange(drawer, index, index + 1, ink)
                }
            }
        }
    }

    /**
     * The click's own even ramp, recovered from the eased [Stage.position] the slide is
     * handed — the exact inverse of the deck's [easeInOutCubic], so `linear(on(n))` is the
     * fraction of the click that has actually elapsed.
     *
     * A slide only ever sees the eased number, which is right for anything that moves and
     * wrong for anything that is counted. This is how the second kind asks for the other.
     */
    private fun linear(eased: Double): Double {
        val y = eased.coerceIn(0.0, 1.0)
        return if (y < 0.5) Math.cbrt(y / 4.0) else 1.0 - Math.cbrt(2.0 * (1.0 - y)) / 2.0
    }

    private companion object {
        /** How far into the first click the elements start lifting off the plan. */
        const val GATHERS_AT = 0.45

        /** How much of the second click the grid takes to empty, survivor and all. */
        const val CULL = 0.25


        // Quieter than they would be on a map: here the ground is only what the figure
        // stands in. Unused while CITY_GROUND_LAYERS is none, which is how it is committed.
        val WATER = ColorRGBa.fromHex("#0A0A0A")
        val ROAD = ColorRGBa.fromHex("#D8D4CC")
        val NATURE = ColorRGBa.fromHex("#ECEDE6")
    }
}
