// ============================================================================ //
//  No `package` declaration, for the reason CityMapSlide has none: it stands on
//  collectCountries, webMercator, geocode and meshOf, which live in the default
//  package, and Kotlin cannot import from the default package into a named one.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.color.Linearity
import org.openrndr.draw.ColorType
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadFont
import org.openrndr.draw.renderTarget
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Vector2
import org.openrndr.math.Vector3
import org.openrndr.shape.Shape
import org.openrndr.shape.ShapeContour
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.advanceOf
import slideshow.drawers.setLine
import slideshow.drawers.wrapped
import slideshow.easeInOutCubic
import slideshow.frames
import slideshow.linear
import slideshow.smoothstep
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln

/** What one click of the map shows. */
sealed interface MapShot

/** A camera stated outright: a centre in degrees and how many km of ground the pane is high there. */
class MapFrame(val name: String, val lon: Double, val lat: Double, val span: Double) : MapShot

/**
 * A camera centred on **where the factories are**, [span] km of ground high: the median of their
 * longitudes and of their latitudes, so the one factory far from the rest — Nancy — does not pull
 * the frame off the cluster the way an average would. Worked out from the list, so adding a
 * factory moves the frame with it.
 */
class FactoryCluster(val name: String, val span: Double) : MapShot

/**
 * A camera on one factory — [factory] counts from 0 in the slide's list — its dot in the middle of
 * the pane with its name floating under it, with [span] km of
 * ground showing top to bottom.
 */
class FactoryVisit(val factory: Int, val span: Double = 175.0) : MapShot

/**
 * A beat on one factory's **name**: the camera holds where [FactoryVisit] put it, the map is
 * veiled and the name is set large over it. Given [parts], the name comes apart into the words it
 * was made of — the letters each word gave the name travel to their place in that word and turn
 * the dot's red, and the rest of each word fades in around them, [joiners] between the words.
 *
 * So a visit on Seveton followed by `FactoryName(i)` and then `FactoryName(i, parts)` is three
 * clicks: the factory, SEVETON, and SEM, VEERLE EN BETON with SE, VE and TON still red. Two name
 * shots in a row read as one card that changes — it does not fade out between them.
 */
class FactoryName(
    val factory: Int,
    val parts: List<NamePart> = emptyList(),
    /** What stands between the words, one fewer than [parts]; a space where short. */
    val joiners: List<String> = emptyList(),
    val span: Double = 175.0
) : MapShot

/** One word a name was made from, and the piece of it the name kept: "Sem" gave Seveton "Se". */
class NamePart(val word: String, val kept: String)

/**
 * The camera pulled out until every country in [codes] is in frame, and those countries tinted
 * [colour] while every other stays its grey; the factory dots stand on top as always.
 *
 * **The frame is read off the countries, not stated.** Their outlines are laid on the projection
 * and the camera centres on the box they make there, high enough for its height or for its width
 * at the pane's proportion, whichever binds, with [margin] of that to spare on each side. It is
 * measured in Web Mercator rather than in km of ground, because Mercator is what the pane shows:
 * Sweden at 60°N is stretched twice over, and a ground span would cut its top off.
 *
 * Codes are Natural Earth's `ADM0_A3` — `BEL NLD FRA LUX DNK SWE` — never `ISO_A3`, which is `-99`
 * for France. France's feature carries Corsica, which sets the frame's foot; its overseas parts
 * were left out at collection.
 *
 * [shades] are factors of [colour] dealt to the countries so that two that touch differ, the way
 * the greys are dealt; one shade, the default, tints them all one colour, so Benelux and France
 * read as one mass. Up to [MAX_SLOTS]. The tint comes in over the back of the click as the camera
 * lands, and the city names give way at the front of it, since at this scale they would sit on top
 * of one another.
 *
 * **Nothing is written on the map.** The countries are named in [list], a column down the pane's
 * left in [ink], set large; the camera frames the countries in what the column leaves, and the
 * column stands on the sea, any country it would lie over left off, so no name lies over land it
 * does not belong to. Labels
 * on the countries with hairlines to the small ones were the first version (meeting of 30
 * September) and read as clutter on a map that is only blue and grey.
 *
 * Given a [beat], **the countries light one after another** in [codes] order, that many seconds
 * apart, once the camera has landed, and each one's name joins the list as it lights. The click
 * is lengthened to carry it (`stepLength`), so clicking back puts them out in reverse.
 */
class MapCountries(
    val name: String,
    val codes: List<String>,
    val colour: ColorRGBa = slideshow.Palette.BLUE,
    val list: List<CountryLabel> = emptyList(),
    val beat: Double = 0.0,
    val shades: List<Double> = listOf(1.0),
    val ink: ColorRGBa = ColorRGBa.WHITE,
    val margin: Double = 0.06
) : MapShot

/**
 * A country in a [MapCountries] list: [name] in the bold face — empty takes the collected Dutch
 * name — and [note] under it in the text face, wrapped to the column the names make.
 */
class CountryLabel(
    val code: String,
    val name: String? = null,
    val note: String? = null
)

/**
 * A place named on the map for reference — a city near the factories, placed by its centre. Given
 * in degrees rather than geocoded: a city centre is common knowledge and does not move.
 */
class City(val name: String, val lon: Double, val lat: Double)

/**
 * A factory as it is written in the address list. [lonLat] places it outright and skips the
 * geocoder, for an address the service gets wrong. [note] is set under the address when the
 * camera is on it, a line each; empty leaves the room for the speaker.
 *
 * [hidden] is a place that is not a factory but is visited as one — the transport arm, say. Its
 * dot stands on the map like any other, so the visit picks it out rather than conjuring it, but
 * it counts for nothing else: not the cluster's middle, not a country's tint.
 */
class Factory(
    val name: String,
    val street: String,
    val postcode: String,
    val place: String,
    val country: String,
    val note: List<String> = emptyList(),
    val lonLat: Vector2? = null,
    val hidden: Boolean = false
)

/**
 * **DRAFT.** The countries as a pixel map, with the group's factories on it a red dot each: flat
 * greys and no borders. A country can be tinted by how many factories it holds; off by default.
 * The clicks are [shots] — an overview, then the camera in on one factory after another with its
 * name and address beside it, then out again — and a [MapCountries] shot further out still, to
 * every country the group builds in, those countries tinted.
 *
 * **Each cell is a vote.** The map is drawn aliased into a buffer four times finer than the grid,
 * and a second pass hands each cell whichever colour covers most of its sixteen samples. So a
 * cell is exactly one country's colour and a coastline is a staircase. Ties go to land, so an
 * island narrower than a cell survives. It is counted **once**, at the overview's scale, and a
 * zoom magnifies those cells rather than counting them again — see [freeze].
 *
 * **A factory is one pixel, and never shares one.** Its address falls in a cell; a factory that
 * finds its cell taken — two companies at one address, three in one Oudenaarde business park at
 * the overview's scale — takes the nearest free one, in list order. So the count on screen is the
 * count in the list at any zoom, and zoomed in the pixels stand where the buildings are.
 *
 * **A country's tint is its share of the factories**, against the country holding the most: that
 * one comes out [tintMax] of the way to [tint], and one with none stays its grey. Which country a
 * factory is in is read off the borders, not off the address's country line.
 *
 * **Neighbours never share a grey.** [colours] pins any country by `ADM0_A3`; the rest are given a
 * [palette] grey none of their neighbours has. Neighbours are countries with vertices within
 * about a degree of one another, so a strait counts: at a quarter of a degree Britain came out
 * Belgium's colour, 100 km across the water.
 *
 * Borders come from Natural Earth through [collectCountries], addresses through [geocode]; both
 * are fetched on first load and read from `data/collected` after that.
 */
class PixelMap(
    private val shots: List<MapShot> = listOf(BENELUX),
    private val factories: List<Factory> = emptyList(),
    /**
     * The group's other sites — offices, showrooms, depots — drawn as small dots in [site] under
     * the factories, so the map shows where the group is as well as where it makes things. They
     * are never visited and never named; a factory is the subject, a site is context.
     */
    private val offices: List<Factory> = emptyList(),
    /** Pixels a cell, on the pane. */
    private val cell: Double = 15.0,
    private val palette: List<ColorRGBa> = GREYS,
    private val colours: Map<String, ColorRGBa> = emptyMap(),
    /** What a country is tinted toward by its factories. */
    private val tint: ColorRGBa = slideshow.Palette.RED,
    /**
     * How far toward [tint] the country with the most factories goes. 0, the default, keeps the
     * map plain grey; 0.9 turned Belgium almost wholly red.
     */
    private val tintMax: Double = 0.0,
    /** The factory dots. */
    private val dot: ColorRGBa = slideshow.Palette.RED,
    /** The other sites' dots: blue, and half the size, so a plant and an office cannot be confused. */
    private val site: ColorRGBa = slideshow.Palette.BLUE,
    /** The bottom bar. */
    private val ink: ColorRGBa = ColorRGBa.fromHex("111111"),
    /** A selected factory's dot, and the colour it pulses toward while selected. */
    private val picked: ColorRGBa = slideshow.Palette.BLUE,
    private val pickedPulse: ColorRGBa = ColorRGBa.WHITE,
    /** A selected factory's name, on the bar. */
    private val barName: ColorRGBa = ColorRGBa.WHITE,
    /** A selected factory's address, on the bar. */
    private val barText: ColorRGBa = ColorRGBa.fromHex("9A9A9A"),
    /** Places named for reference, centred on the place the way Google Maps names a city. */
    private val cities: List<City> = emptyList(),
    /** The city names: a grey, so they sit under the red rather than beside it. */
    private val cityInk: ColorRGBa = ColorRGBa.fromHex("4D4D4D"),
    override val background: ColorRGBa = ColorRGBa.fromHex("F5F5F5"),
    /** What veils the map while a name stands on it, and how far. */
    private val veil: ColorRGBa = ColorRGBa.fromHex("F5F5F5"),
    private val veilAmount: Double = 0.8,
    /** A name set large; the letters it kept from its words turn [dot] as it comes apart. */
    private val nameInk: ColorRGBa = ColorRGBa.fromHex("111111"),
    /**
     * The black bar naming a visited factory along the foot of the pane. Off, a visit centres its
     * dot on the whole pane and a name stands in the middle of it.
     */
    private val footer: Boolean = true,
    /** Where a visited factory's dot stands across the pane, as a share of its width: the middle. */
    private val anchor: Double = 0.5,
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    /**
     * Below this share for its winner a cell mixes its two biggest colours rather than taking
     * one. 0 keeps every cell pure.
     */
    private val mixed: Double = 0.0,
    override val stepFrames: Int = frames(2.0),
    override val sound: Sound? = null,
    private val source: () -> File = { collectCountries() }
) : Slide() {

    override val name = "Pixel map"
    override val steps get() = shots.size

    /** A click onto a [MapCountries] build is the camera's move and then a beat a country. */
    override fun stepLength(step: Int): Int =
        (shots.getOrNull(step) as? MapCountries)?.takeIf { it.beat > 0.0 }
            ?.let { frames(lightsAt(it, it.codes.size - 1) + LIGHT) } ?: stepFrames

    /** Seconds into the click onto [shot] that its [rank]-th country starts to light. */
    private fun lightsAt(shot: MapCountries, rank: Int) = stepFrames / slideshow.FPS.toDouble() * LIGHT_FROM + rank * shot.beat
    override fun stepName(step: Int) = when (val shot = shots.getOrNull(step)) {
        is MapFrame -> shot.name
        is FactoryCluster -> shot.name
        is FactoryVisit -> factories.getOrNull(shot.factory)?.name
        is FactoryName -> factories.getOrNull(shot.factory)?.name?.let { if (shot.parts.isEmpty()) "$it, large" else "$it, apart" }
        is MapCountries -> shot.name
        null -> null
    }

    private var mesh: Mesh? = null
    private var fills: List<ColorRGBa> = emptyList()
    /** Each factory in longitude and latitude, or null where its address could not be placed. */
    private var places: List<Vector2?> = emptyList()
    /** The same on the projection, in Web Mercator km. */
    private var sites: List<Vector2?> = emptyList()
    /** The other sites on the projection, in Web Mercator km. */
    private var officeSpots: List<Vector2?> = emptyList()
    /** The middle of the factories, as longitude and latitude. See [FactoryCluster]. */
    private var cluster: Vector2 = Vector2(BENELUX.lon, BENELUX.lat)
    private lateinit var bold: FontImageMap
    private lateinit var text: FontImageMap
    /** The face a name is set large in, loaded big so it is not a magnified atlas. */
    private lateinit var hero: FontImageMap
    /** The face a [MapCountries] list is set in, loaded at the size it is set at. */
    private lateinit var listFace: FontImageMap
    /** The pixel map, counted once and then only moved and scaled. See [freeze]. */
    private var frozen: Frozen? = null
    /** Each [MapCountries] shot's countries as a box on the projection, west-south and east-north corners. */
    private var extents: Map<MapCountries, Pair<Vector2, Vector2>> = emptyMap()
    /**
     * Each [MapCountries] shot's slot a country, by country index: -1 where it is not tinted. A slot
     * is what the tint can fade on its own — the country's turn in a build, or its shade when the
     * countries light together. See [maskColour].
     */
    private var slotsOf: Map<MapCountries, IntArray> = emptyMap()
    /** Each [MapCountries] shot's shade a slot, as an index into its [MapCountries.shades]. */
    private var slotShades: Map<MapCountries, IntArray> = emptyMap()
    /** The collected Dutch name a country, by `ADM0_A3`, for a list entry that states none. */
    private var countryNames: Map<String, String> = emptyMap()
    /** Each country's middle on the projection, by country index; null for one too small to have one. */
    private var middles: List<Vector2?> = emptyList()

    private class Frozen(
        /** The pane it was counted for; another size counts it again. */
        val paneWidth: Int,
        val paneHeight: Int,
        /** One texel a cell, drawn magnified with nearest filtering. */
        val image: RenderTarget,
        /** The grid's top left corner, in Web Mercator km. */
        val corner: Vector2,
        val cellKm: Double,
        val cols: Int,
        val rows: Int,
        /** The middle of the cell each factory's dot stands in, in Web Mercator km. */
        val dots: List<Vector2?>,
        /** The other sites, snapped to their cells; two may share one, being context rather than a count. */
        val offices: List<Vector2?>,
        /**
         * Per [MapCountries] shot, the same grid saying which cells are its countries' and in which
         * shade — counted by the same vote over the same samples, so a tinted cell is exactly a
         * cell of that country in [image]. See [freeze].
         */
        val masks: Map<MapCountries, RenderTarget> = emptyMap()
    ) {
        fun destroy() {
            image.destroy()
            masks.values.forEach { it.destroy() }
        }
    }

    override fun load(program: Program) {
        bold = program.loadFont(boldPath, NAME_EM, TYPE_CHARACTERS, contentScale = 1.0)
        text = program.loadFont(textPath, TEXT_EM, TYPE_CHARACTERS, contentScale = 1.0)
        hero = program.loadFont(boldPath, HERO_EM, TYPE_CHARACTERS, contentScale = 1.0)
        listFace = program.loadFont(boldPath, LIST_EM, TYPE_CHARACTERS, contentScale = 1.0)

        val began = System.currentTimeMillis()
        val countries = runCatching { loadCountries(source()) }
            .onFailure { println("pixel map: no countries (${it.message}); drawing the sea alone") }
            .getOrDefault(emptyList())

        val shapes = countries.map { country ->
            country.polygons.mapNotNull { polygon ->
                val contours = polygon.mapNotNull { ring ->
                    val points = ring.map { webMercator(it) }
                        .fold(mutableListOf<Vector2>()) { kept, p ->
                            if (kept.isEmpty() || kept.last().squaredDistanceTo(p) > 1e-6) kept.add(p); kept
                        }
                    if (points.size < 3) null else ShapeContour.fromPoints(points, closed = true)
                }
                if (contours.isEmpty()) null else Shape(contours)
            }
        }
        if (countries.isNotEmpty()) mesh = meshOf(shapes)

        places = factories.map { factory ->
            val hit = if (factory.lonLat != null) null
            else geocode(factory.street, factory.postcode, factory.place, factory.country)
            val lonLat = factory.lonLat ?: hit?.lonLat
            println("  %-32s %s".format(factory.name, when {
                factory.lonLat != null -> "placed by hand"
                hit != null -> "${hit.precision}: ${hit.matched.take(60)}"
                else -> "NOT FOUND — not drawn"
            }))
            lonLat
        }
        sites = places.map { it?.let { lonLat -> webMercator(lonLat) } }
        officeSpots = offices.map { office ->
            val lonLat = office.lonLat ?: geocode(office.street, office.postcode, office.place, office.country)?.lonLat
            if (lonLat == null) println("  %-32s NOT FOUND — not drawn".format(office.name))
            lonLat?.let { webMercator(it) }
        }
        cluster = places.filterIndexed { k, _ -> !factories[k].hidden }.filterNotNull().takeIf { it.isNotEmpty() }
            ?.let { Vector2(median(it.map { p -> p.x }), median(it.map { p -> p.y })) }
            ?: Vector2(BENELUX.lon, BENELUX.lat)

        val counts = IntArray(countries.size)
        places.forEachIndexed { j, place ->
            if (place == null || factories[j].hidden) return@forEachIndexed
            val k = countries.indexOfFirst { country -> country.polygons.any { inside(place, it) } }
            if (k >= 0) counts[k]++
        }
        val most = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
        val neighbours = neighboursOf(countries)
        fills = greys(countries, neighbours).mapIndexed { k, grey -> grey.towards(tint, tintMax * counts[k] / most) }

        // The countries shots: the box each one frames and the slot each of its countries takes.
        val countryShots = shots.filterIsInstance<MapCountries>().distinct()
        extents = countryShots.mapNotNull { shot ->
            val lit = countries.filter { it.code in shot.codes }
            shot.codes.filter { code -> lit.none { it.code == code } }
                .forEach { println("pixel map: no country $it to frame in \"${shot.name}\"") }
            val points = lit.flatMap { country -> country.polygons.flatMap { polygon -> polygon.firstOrNull().orEmpty() } }
                .map { webMercator(it) }
            if (points.isEmpty()) null
            else shot to Pair(
                Vector2(points.minOf { it.x }, points.minOf { it.y }),
                Vector2(points.maxOf { it.x }, points.maxOf { it.y })
            )
        }.toMap()
        // Lit together, a country's slot is its shade; built one by one, it is its turn, and the
        // slot carries the shade the country was dealt.
        val dealtOf = countryShots.associateWith { shot ->
            val lit = countries.indices.filter { countries[it].code in shot.codes }
                .sortedBy { shot.codes.indexOf(countries[it].code) }
            lit to deal(lit, shot.shades.size.coerceIn(1, MAX_SLOTS), neighbours)
        }
        slotsOf = dealtOf.mapValues { (shot, pair) ->
            val (lit, dealt) = pair
            if (shot.beat > 0.0 && lit.size > MAX_SLOTS) println("pixel map: \"${shot.name}\" builds ${lit.size} countries; the last ${lit.size - MAX_SLOTS + 1} light together")
            IntArray(countries.size) { k ->
                when {
                    k !in dealt -> -1
                    shot.beat > 0.0 -> lit.indexOf(k).coerceAtMost(MAX_SLOTS - 1)
                    else -> dealt.getValue(k)
                }
            }
        }
        slotShades = dealtOf.mapValues { (shot, pair) ->
            val (lit, dealt) = pair
            IntArray(MAX_SLOTS) { slot -> if (shot.beat > 0.0) lit.getOrNull(slot)?.let { dealt[it] } ?: 0 else slot }
        }
        countryNames = countries.associate { it.code to (it.nameNl ?: it.name) }
        middles = countries.map { middleOf(it) }
        countryShots.flatMap { shot -> shot.list.map { it.code } }.toSet()
            .filter { code -> countries.none { it.code == code } }
            .forEach { println("pixel map: no country $it to list") }

        println("pixel map: %d countries, %d factories, %d other sites (%s) in %.1fs".format(
            countries.size, sites.filterIndexed { k, _ -> !factories[k].hidden }.count { it != null }, officeSpots.count { it != null },
            countries.indices.filter { counts[it] > 0 }.joinToString { "${countries[it].code} ${counts[it]}" },
            (System.currentTimeMillis() - began) / 1000.0))
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val mesh = mesh ?: return
        val w = stage.width
        val h = stage.height

        val frozen = frozen?.takeIf { it.paneWidth == w.toInt() && it.paneHeight == h.toInt() }
            ?: freeze(drawer, mesh, w, h).also { this.frozen?.destroy(); this.frozen = it }

        // --- the camera ----------------------------------------------------------------- //
        //
        // Between two shots, the centre travelling straight and the span in log space, so a
        // zoom reads as even. Straight off the deck's own eased position, not eased again.

        val p = stage.position.coerceIn(0.0, (shots.size - 1).toDouble())
        val i = floor(p).toInt().coerceAtMost((shots.size - 2).coerceAtLeast(0))
        val a = shots[i]
        val b = shots.getOrElse(i + 1) { a }

        // **A name is off the map before the camera leaves, and the camera is there before a name
        // arrives.** A click from a name card to somewhere else fades the name over its first
        // [HANDOFF] with the camera still, and moves the camera over the rest; into a name card
        // from elsewhere, the other way round. Where the camera does not move — a visit to its own
        // name — the name takes the whole click. Each window is eased on its own, off the click's
        // linear time, so neither starts with the speed the deck's ease had reached.
        val eased = p - i
        val raw = linear(eased)
        val moves = factoryOf(a) == null || factoryOf(a) != factoryOf(b)
        fun window(from: Double, to: Double) = easeInOutCubic(((raw - from) / (to - from)).coerceIn(0.0, 1.0))
        // The share of this click the camera's move takes: all of it, but on a click onto a
        // countries build, whose click runs on after the camera has landed while they light.
        val length = stepLength(i + 1).toDouble()
        val cameraShare = stepFrames / length
        val (t, nameT) = when {
            moves && a is FactoryName && b !is FactoryName -> window(HANDOFF, 1.0) to window(0.0, HANDOFF)
            moves && b is FactoryName && a !is FactoryName -> window(0.0, 1.0 - HANDOFF) to window(1.0 - HANDOFF, 1.0)
            cameraShare < 1.0 -> window(0.0, cameraShare) to window(0.0, cameraShare)
            else -> eased to eased
        }
        val centre = centreOf(a, w, h, frozen.dots).mix(centreOf(b, w, h, frozen.dots), t)
        val span = exp(ln(spanOf(a, w, h)) + (ln(spanOf(b, w, h)) - ln(spanOf(a, w, h))) * t)
        val pixelsPerKm = h / span
        fun screen(world: Vector2) =
            Vector2(w / 2.0 + (world.x - centre.x) * pixelsPerKm, h / 2.0 - (world.y - centre.y) * pixelsPerKm)

        // --- the map, and the dots on it, as one picture moved and scaled ---------------- //

        drawer.shadeStyle = null
        drawer.stroke = null
        val corner = screen(frozen.corner)
        val mapWidth = frozen.cols * frozen.cellKm * pixelsPerKm
        val mapHeight = frozen.rows * frozen.cellKm * pixelsPerKm
        drawer.image(frozen.image.colorBuffer(0), corner.x, corner.y, mapWidth, mapHeight)

        // How far a kind of shot is up at this position: all the way while the click is between two
        // of them, coming in over [from]..[to] of the camera's move into one, and going over the
        // mirror of that on a click out of one. Off the click's linear time, like the name's handoff.
        fun arrived(of: (MapShot) -> Boolean, from: Double, to: Double): Double {
            val inA = of(a)
            val inB = of(b)
            return when {
                inA && inB -> 1.0
                inB -> window(from * cameraShare, to * cameraShare)
                inA -> 1.0 - window(1.0 - to, 1.0 - from)
                else -> 0.0
            }
        }

        // How far the [rank]-th country of a countries shot is lit: at its turn in the click onto a
        // build, with the rest of the tint otherwise.
        fun lit(shot: MapCountries, rank: Int): Double {
            if (shot.beat <= 0.0 || a === shot || b !== shot) return arrived({ it === shot }, TINT_FROM, 1.0)
            val from = frames(lightsAt(shot, rank)) / length
            return window(from, from + frames(LIGHT) / length)
        }

        // --- the countries the group builds in ------------------------------------------ //
        //
        // Laid over the map as a second picture of the same cells: where the shot's mask says a
        // cell is one of its countries, the cell takes its slot's shade of the colour, each slot
        // faded in on its own. Every other cell lets the grey through untouched.

        val countryShots = listOf(a, b).filterIsInstance<MapCountries>().distinct()
        countryShots.forEach { shot ->
            val mask = frozen.masks[shot] ?: return@forEach
            val shades = slotShades[shot] ?: return@forEach
            val amounts = List(MAX_SLOTS) { slot -> lit(shot, slot) }
            // What the column would lie over is never seen: painted out at full from the first frame of
            // the click into the shot, which is before the camera can have brought it into frame. Fading it
            // out with the camera's move showed Greenland coming in and going again, and read as a question.
            val cleared = if (shot.list.isEmpty()) 0.0 else 1.0
            if (amounts.all { it <= 0.0 } && cleared <= 0.0) return@forEach
            drawer.shadeStyle = tinted.apply {
                parameter("ground", background.opacify(cleared))
                amounts.forEachIndexed { slot, amount ->
                    val shade = shot.shades.getOrElse(shades[slot]) { shot.shades.lastOrNull() ?: 1.0 }
                    parameter("slot$slot", shot.colour.shade(shade).opacify(amount))
                }
            }
            drawer.image(mask.colorBuffer(0), corner.x, corner.y, mapWidth, mapHeight)
            drawer.shadeStyle = null
        }

        // How selected each factory is, and how far the bar is up. The bar comes with the first
        // visit and goes with the last, and **stays up between two visits** — only its lettering
        // changes — so stepping from one factory to the next does not drop it and raise it again.
        // A name shot is a visit for all of this, so a factory stays selected under its name.
        //
        // How present something is at this position is the straight blend of the two shots either
        // side, so a run of shots on one factory holds it at 1 all the way through rather than
        // dipping between them; [LABEL_REACH] then keeps a label to the last third of its click.
        fun presence(at: Double = t, of: (MapShot) -> Double) = of(a) * (1.0 - at) + of(b) * at
        val radius = frozen.cellKm * pixelsPerKm * DOT
        val selected = DoubleArray(frozen.dots.size)
        val named = factories.indices.mapNotNull { f ->
            val u = presence { if (factoryOf(it) == f) 1.0 else 0.0 }
            val near = smoothstep(1.0 - (1.0 - u) * LABEL_REACH)
            if (near <= 0.0) return@mapNotNull null
            selected[f] = near
            factories[f] to near
        }
        val barUp = if (factoryOf(a) != null && factoryOf(b) != null) 1.0 else named.maxOfOrNull { it.second } ?: 0.0

        // --- the dots ------------------------------------------------------------------- //
        //
        // A round dot the size of its cell, a hair short of it so two in neighbouring cells still
        // read as two, and scaled with the cells so a close-up is a close-up of both: a dot keeps
        // its size against the map at every zoom.
        //
        // **The selected one turns blue, and its blue pulses.** As a factory is selected its dot
        // eases from the red to [picked], and while it is selected the colour breathes between that
        // and [pickedPulse] on a slow cosine — off the slide's own frame count, so a still or a clip
        // of the same frame is the same picture. Colour alone: the dot neither fades nor changes size.

        val breath = 0.5 - 0.5 * cos(2.0 * PI * stage.frame / BLINK_PERIOD)
        val blue = picked.towards(pickedPulse, breath)
        drawer.stroke = null

        // The other sites first, small and blue, so a factory standing on one covers it.
        drawer.fill = site
        frozen.offices.forEach { centre -> if (centre != null) drawer.circle(screen(centre), radius * OFFICE) }

        frozen.dots.forEachIndexed { j, centre ->
            if (centre == null) return@forEachIndexed
            // Every place stands on the map from the start, a hidden one too, so a visit only
            // ever picks out a dot that is already there — the transport arm used to appear
            // out of nothing as the camera arrived (review of 22 September).
            drawer.fill = if (selected[j] > 0.0) dot.towards(blue, selected[j]) else dot
            drawer.circle(screen(centre), radius)
        }

        // --- the cities ------------------------------------------------------------------ //
        //
        // Named the way Google Maps names a city: centred on the place with no marker, plain dark
        // grey Rockwell, and **on top of the dots**, so a factory in a city never takes letters
        // out of its name. Placed at their true position rather than a cell, and — unlike the
        // cells and dots — **one size on screen at every zoom**, so coming in the map grows and
        // the lettering does not.

        // **They give way to the countries.** Pulled out to a [MapCountries] shot the cities would
        // stand on top of one another, so they go over the front of that click and come back over
        // the back of the click out of it.
        val citySize = h * CITY
        val cityShown = 1.0 - arrived({ it is MapCountries }, 0.0, CITY_GO)
        if (cityShown > 0.0) {
            drawer.fill = cityInk.opacify(cityShown)
            cities.forEach { city ->
                val at = screen(webMercator(Vector2(city.lon, city.lat)))
                drawer.setLine(city.name, text, Vector2(at.x, at.y + citySize * CAP), citySize, TEXT_EM, align = 0.5)
            }
        }

        // --- the countries' names -------------------------------------------------------- //
        //
        // A column down the left, on the sea, each name rising into its place as its country lights.

        countryShots.forEach { shot ->
            val column = columnOf(shot, w, h) ?: return@forEach
            // An entry for a country the shot does not light arrives with the first.
            countryList(drawer, shot, column, w, h) { code -> lit(shot, shot.codes.indexOf(code).coerceIn(0, MAX_SLOTS - 1)) }
        }

        // --- a name, large ------------------------------------------------------------- //

        val shown = presence(nameT) { if (it is FactoryName) 1.0 else 0.0 }
        if (shown > 0.0) {
            // The card with words, where the click is between the whole name and the name apart:
            // it carries the mapping, and [apart] says how far along it the letters are.
            val card = listOf(a, b).filterIsInstance<FactoryName>().maxBy { it.parts.size }
            // Only a click between two name cards takes the name apart or puts it back; one to or
            // from anywhere else fades the card as it stands, rather than reassembling it on the way out.
            fun isApart(shot: MapShot) = if (shot is FactoryName && shot.parts.isNotEmpty()) 1.0 else 0.0
            val apart = if (a is FactoryName && b is FactoryName) presence(nameT, ::isApart) else isApart(card)
            val middle = Vector2(w / 2.0, (h - barOffset(h) * smoothstep(barUp)) / 2.0)
            drawer.fill = veil.opacify(veilAmount * smoothstep(shown))
            drawer.rectangle(0.0, 0.0, w, h)
            nameCard(drawer, card, middle, w, h, smoothstep(shown), apart)
        }

        // --- the bar -------------------------------------------------------------------- //

        if (footer && barUp > 0.0) bar(drawer, w, h, barUp, named)
    }

    /**
     * The name set large at [middle], and taken apart by [apart]. Every letter is placed on its own,
     * so a letter the name kept from a word can travel from its place in the name to its place in
     * that word: both lines are laid out whole at their own size, and a letter moves and scales
     * between its two positions. Letters only in the words fade in over the back half of the move;
     * a letter only in the name fades out over the front half.
     */
    private fun nameCard(drawer: Drawer, card: FactoryName, middle: Vector2, w: Double, h: Double, shown: Double, apart: Double) {
        val name = factories.getOrNull(card.factory)?.name?.uppercase() ?: return
        val words = card.parts.map { it.word.uppercase() }
        val phrase = buildString {
            words.forEachIndexed { k, word ->
                if (k > 0) append(card.joiners.getOrElse(k - 1) { " " }.uppercase())
                append(word)
            }
        }

        // Which letter of the phrase came from which letter of the name.
        val from = IntArray(phrase.length) { -1 }
        var cursor = 0
        var wordStart = 0
        card.parts.forEachIndexed { k, part ->
            if (k > 0) wordStart += card.joiners.getOrElse(k - 1) { " " }.length
            val kept = part.kept.uppercase()
            val inName = name.indexOf(kept, cursor)
            val inWord = words[k].indexOf(kept)
            if (inName >= 0 && inWord >= 0) {
                kept.indices.forEach { c -> from[wordStart + inWord + c] = inName + c }
                cursor = inName + kept.length
            }
            wordStart += words[k].length
        }
        val used = from.filter { it >= 0 }.toSet()

        // One size each, the largest that fits the pane: the name bigger, the phrase what it can be.
        fun widthAt(line: String, size: Double) = hero.advanceOf(line) * size / HERO_EM
        val nameSize = minOf(h * HERO, w * HERO_WIDE / (widthAt(name, 1.0).coerceAtLeast(1e-6)))
        val phraseSize = if (phrase.isEmpty()) nameSize
            else minOf(nameSize, w * PHRASE_WIDE / (widthAt(phrase, 1.0).coerceAtLeast(1e-6)))
        fun letters(line: String, size: Double): List<Vector2> {
            val left = middle.x - widthAt(line, size) / 2.0
            val baseline = middle.y + size * CAP_HEIGHT / 2.0
            return line.indices.map { i -> Vector2(left + widthAt(line.substring(0, i), size), baseline) }
        }
        val inName = letters(name, nameSize)
        val inPhrase = letters(phrase, phraseSize)
        val move = smoothstep(apart)
        val size = nameSize + (phraseSize - nameSize) * move
        // One rise for every letter, off the size the card stands at, so the card comes and goes whole.
        val rise = (1.0 - shown) * size * RISE
        name.indices.filter { it !in used }.forEach { i ->
            drawer.fill = nameInk.opacify(shown * (1.0 - smoothstep(apart * 2.0)))
            drawer.setLine(name[i].toString(), hero, inName[i] + Vector2(0.0, rise), nameSize, HERO_EM)
        }
        phrase.indices.forEach { i ->
            if (phrase[i] == ' ') return@forEach
            val j = from[i]
            if (j >= 0) {
                drawer.fill = nameInk.towards(dot, move).opacify(shown)
                drawer.setLine(phrase[i].toString(), hero, inName[j].mix(inPhrase[i], move) + Vector2(0.0, rise), size, HERO_EM)
            } else {
                val arrive = smoothstep(apart * 2.0 - 1.0)
                if (arrive <= 0.0) return@forEach
                drawer.fill = nameInk.opacify(shown * arrive)
                drawer.setLine(phrase[i].toString(), hero, inPhrase[i] + Vector2(0.0, rise + (1.0 - arrive) * phraseSize * RISE), phraseSize, HERO_EM)
            }
        }
    }

    /** A [MapCountries] list laid out on a pane: its entries, and the right edge of the room it takes. */
    private class Column(val entries: List<Entry>, val right: Double)

    /** One country in a [Column]: its name's baseline, and the note's lines with theirs. */
    private class Entry(val code: String, val name: String, val baseline: Double, val notes: List<Pair<String, Double>>)

    /**
     * Where a [MapCountries] shot's list stands on a [w] by [h] pane, or null for a shot with none.
     *
     * **One size for every name**, a list of things of one kind. The column is as wide as the
     * widest name, a note is wrapped to it, and the block is centred down the pane, ranged left on
     * the margin the programme's list stands on. Every position is fixed by the whole list, so an
     * entry arriving never moves the ones already there. The room it takes runs a gap past the
     * column, and the camera frames the countries in what is left — see [spanOf].
     */
    private fun columnOf(shot: MapCountries, w: Double, h: Double): Column? {
        if (shot.list.isEmpty()) return null
        val nameSize = h * LIST_NAME
        val noteSize = h * LIST_NOTE
        val left = w * LIST_LEFT
        val names = shot.list.map { it.name ?: countryNames[it.code] ?: it.code }
        val measure = names.maxOf { listFace.advanceOf(it) * nameSize / LIST_EM }
        val notes = shot.list.map { entry -> entry.note?.let { text.wrapped(it, measure * TEXT_EM / noteSize) }.orEmpty() }

        // Laid from a first baseline of 0, then moved down so the block's cap-top-to-last-baseline
        // stands in the middle of the pane.
        var at = 0.0
        val laid = shot.list.indices.map { k ->
            val baseline = at
            val lines = notes[k].mapIndexed { j, line -> line to baseline + nameSize * LIST_NOTE_DROP + j * noteSize * LIST_NOTE_LEAD }
            at = (lines.lastOrNull()?.second ?: baseline) + nameSize * LIST_ROW
            Entry(shot.list[k].code, names[k], baseline, lines)
        }
        val last = laid.last().let { it.notes.lastOrNull()?.second ?: it.baseline }
        val shift = (h - (last + nameSize * CAP_HEIGHT)) / 2.0 + nameSize * CAP_HEIGHT
        val widest = maxOf(measure, notes.flatten().maxOfOrNull { text.advanceOf(it) * noteSize / TEXT_EM } ?: 0.0)
        return Column(
            laid.map { e -> Entry(e.code, e.name, e.baseline + shift, e.notes.map { (line, y) -> line to y + shift }) },
            left + widest + h * LIST_GAP
        )
    }

    /** The list, each entry [shown] by its code: rising into place and coming up as its country lights. */
    private fun countryList(drawer: Drawer, shot: MapCountries, column: Column, w: Double, h: Double, shown: (String) -> Double) {
        val nameSize = h * LIST_NAME
        val noteSize = h * LIST_NOTE
        val left = w * LIST_LEFT
        column.entries.forEach { entry ->
            val up = shown(entry.code)
            if (up <= 0.0) return@forEach
            val rise = (1.0 - up) * nameSize * RISE
            drawer.fill = shot.ink.opacify(up)
            drawer.setLine(entry.name, listFace, Vector2(left, entry.baseline + rise), nameSize, LIST_EM)
            entry.notes.forEach { (line, y) -> drawer.setLine(line, text, Vector2(left, y + rise), noteSize, TEXT_EM) }
        }
    }

    /**
     * The bottom bar: a black band across the foot of the pane carrying the selected factory's name
     * in Rockwell Bold and its address on one line under it, ranged left on a margin. It slides up
     * from below by [up]; each factory's lettering fades and settles in by its own nearness, so
     * stepping between two visits crossfades the words on a bar that stays where it is.
     *
     * One height for every factory — tall enough for the one with the most note lines — so the bar
     * does not change size from one visit to the next.
     */
    private fun bar(drawer: Drawer, w: Double, h: Double, up: Double, named: List<Pair<Factory, Double>>) {
        val height = barHeight(h)
        val top = h - height * smoothstep(up)
        drawer.fill = ink
        drawer.rectangle(0.0, top, w, height + 1.0)

        val nameSize = h * NAME
        val textSize = h * TEXT
        val left = h * BAR_MARGIN
        named.forEach { (factory, near) ->
            val shown = smoothstep((near - 0.35) / 0.65)
            if (shown <= 0.0) return@forEach
            val rise = (1.0 - shown) * nameSize * RISE
            var baseline = top + h * BAR_PAD + nameSize * ASCENT + rise
            drawer.fill = barName.opacify(shown)
            drawer.setLine(factory.name, bold, Vector2(left, baseline), nameSize, NAME_EM)

            val address = listOf(factory.street, "${factory.postcode} ${factory.place}".trim(), factory.country)
                .filter { it.isNotBlank() }.joinToString(SEPARATOR)
            drawer.fill = barText.opacify(shown)
            for (line in listOf(address) + factory.note) {
                baseline += textSize * ADDRESS_LEAD
                drawer.setLine(line, text, Vector2(left, baseline), textSize, TEXT_EM)
            }
        }
    }

    /** How much of the pane's foot the bar takes: its height, or nothing with the [footer] off. */
    private fun barOffset(h: Double) = if (footer) barHeight(h) else 0.0

    /** The bar's height: its padding, the name, and the address plus the longest note under it. */
    private fun barHeight(h: Double): Double {
        val lines = 1 + (factories.maxOfOrNull { it.note.size } ?: 0)
        return h * BAR_PAD * 2.0 + h * NAME * ASCENT + h * TEXT * (ADDRESS_LEAD * lines + DESCENT)
    }

    /**
     * **The map is pixelated once, at the scale of the first shot that is not a visit, and never
     * again.** Every zoom after that is the same cells magnified: a close-up rather than a fresh
     * count, so nothing crawls while the camera moves. Re-counting every frame was the first
     * version, and the cells boiled across the whole frame for the length of every zoom.
     *
     * The grid covers [REACH] times the pane each way around that shot, so a pan or a wider shot
     * still finds map under it, and it is centred on the shot so the pane's own cells land on
     * whole pixels exactly as they would drawn directly. Each factory takes its cell here too,
     * nudged to the nearest free one when two share, so a dot is fixed to the map like the cells.
     *
     * **It also reaches every shot's frame.** A [MapCountries] shot pulls out far past three panes —
     * to Sweden's north, 2000 km above Brussels — so the grid is grown by whole cells on any side a
     * shot's frame runs past it. Whole cells, so the reference shot's cells still land on whole
     * pixels; and grown only where needed, so a show with no wide shot counts exactly as before.
     * The camera's path between two shots stays inside the box their frames make, the span being
     * interpolated in log space, so covering the shots covers every frame in between. The cells
     * keep the reference shot's size: at the countries' frame they are drawn a few pixels across,
     * the same cells seen from further off, rather than a second, coarser count that would have to
     * be dissolved into in the middle of a move. The reference is never a [MapCountries] shot, so
     * the overview keeps its grain wherever the wide shot stands in the list.
     *
     * Each [MapCountries] shot gets a mask counted the same way, over the same samples, from every
     * country drawn in a colour of its own — so the vote picks the same country in every cell as
     * it did for the greys — with whether it is tinted, and in which shade, carried in green.
     */
    private fun freeze(drawer: Drawer, mesh: Mesh, w: Double, h: Double): Frozen {
        val reference = shots.firstOrNull { factoryOf(it) == null && it !is MapCountries }
            ?: shots.firstOrNull { factoryOf(it) == null } ?: BENELUX
        val middle = centreOf(reference, w, h, emptyList())
        val cellKm = cell * spanOf(reference, w, h) / h
        val baseCols = ceil(w / cell).toInt() * REACH
        val baseRows = ceil(h / cell).toInt() * REACH
        val halfCols = baseCols / 2.0
        val halfRows = baseRows / 2.0

        // How many cells each side has to grow by for every shot's frame to be counted.
        var needWest = 0.0
        var needEast = 0.0
        var needNorth = 0.0
        var needSouth = 0.0
        for (shot in shots) {
            val c = centreOf(shot, w, h, emptyList())
            val half = Vector2(w / 2.0, h / 2.0) * (spanOf(shot, w, h) / h)
            needWest = maxOf(needWest, (middle.x - (c.x - half.x)) / cellKm - halfCols)
            needEast = maxOf(needEast, (c.x + half.x - middle.x) / cellKm - halfCols)
            needNorth = maxOf(needNorth, (c.y + half.y - middle.y) / cellKm - halfRows)
            needSouth = maxOf(needSouth, (middle.y - (c.y - half.y)) / cellKm - halfRows)
        }
        val room = (MAX_CELLS - maxOf(baseCols, baseRows)).coerceAtLeast(0) / 2
        fun grow(need: Double): Int {
            if (need <= 0.0) return 0
            val cells = ceil(need).toInt() + 1
            if (cells > room) println("pixel map: a shot reaches ${cells} cells past the grid; counting $room")
            return cells.coerceAtMost(room)
        }
        val west = grow(needWest)
        val east = grow(needEast)
        val north = grow(needNorth)
        val south = grow(needSouth)
        val cols = baseCols + west + east
        val rows = baseRows + north + south
        val corner = Vector2(middle.x - (halfCols + west) * cellKm, middle.y + (halfRows + north) * cellKm)
        // Where the reference shot's middle falls in the sample buffer: its centre when nothing grew.
        val origin = Vector2((halfCols + west) * SAMPLES, (halfRows + north) * SAMPLES)
        val perKm = SAMPLES / cellKm

        /**
         * The map counted into a grid of [cols] by [rows], one texel a cell: every country drawn
         * aliased in its colour of [colours] over [ground], then each cell taking the colour most
         * of its samples have.
         */
        fun count(colours: List<ColorRGBa>, ground: ColorRGBa, split: Double): RenderTarget {
            val fine = renderTarget(cols * SAMPLES, rows * SAMPLES) { colorBuffer(type = ColorType.UINT8) }
            val image = renderTarget(cols, rows) { colorBuffer(type = ColorType.UINT8) }.also {
                it.colorBuffer(0).filterMag = MagnifyingFilter.NEAREST
                it.colorBuffer(0).filterMin = MinifyingFilter.NEAREST
            }
            drawer.isolatedWithTarget(fine) {
                ortho(fine)
                clear(ground)
                stroke = null
                translate(origin.x, origin.y)
                scale(perKm, -perKm)
                translate(-middle)
                colours.forEachIndexed { k, colour -> mesh.drawRange(this, k, k + 1, colour) }
            }
            drawer.isolatedWithTarget(image) {
                ortho(image)
                clear(ground)
                stroke = null
                fill = ColorRGBa.WHITE
                shadeStyle = vote.apply {
                    parameter("fine", fine.colorBuffer(0))
                    parameter("sea", Vector3(ground.r, ground.g, ground.b))
                    parameter("mixed", split)
                }
                rectangle(0.0, 0.0, cols.toDouble(), rows.toDouble())
            }
            fine.destroy()
            return image
        }

        val image = count(fills, background, mixed)
        // **A country the list would lie over is left off.** A name may not stand on land it does
        // not belong to, and the column is otherwise sea at the countries' frame: only Greenland
        // reaches into it. So a country whose middle lands in the column is marked in the mask and
        // painted out with the ground. A rectangle of ground behind the column was the first way,
        // and it sliced Greenland at rest and Iceland while the camera pulled out past it.
        val masks = slotsOf.mapValues { (shot, slots) ->
            val gone = columnOf(shot, w, h)?.let { column ->
                val c = centreOf(shot, w, h, emptyList())
                val pixelsPerKm = h / spanOf(shot, w, h)
                fills.indices.filter { k -> middles.getOrNull(k)?.let { w / 2.0 + (it.x - c.x) * pixelsPerKm < column.right } == true }.toSet()
            }.orEmpty()
            count(fills.indices.map { k -> maskColour(k, slots.getOrElse(k) { -1 }, k in gone) }, ColorRGBa.BLACK, 0.0)
        }

        val taken = HashSet<Long>()
        fun key(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
        val dots = sites.map { site ->
            if (site == null) return@map null
            val fx = (site.x - corner.x) / cellKm
            val fy = (corner.y - site.y) / cellKm
            val home = Pair(floor(fx).toInt(), floor(fy).toInt())
            val free = if (key(home.first, home.second) !in taken) home else (1..MAX_RING).firstNotNullOfOrNull { r ->
                (-r..r).flatMap { dx -> (-r..r).map { dy -> Pair(home.first + dx, home.second + dy) } }
                    .filter { (x, y) -> maxOf(abs(x - home.first), abs(y - home.second)) == r && key(x, y) !in taken }
                    .minByOrNull { (x, y) -> (x + 0.5 - fx) * (x + 0.5 - fx) + (y + 0.5 - fy) * (y + 0.5 - fy) }
            } ?: home
            taken += key(free.first, free.second)
            Vector2(corner.x + (free.first + 0.5) * cellKm, corner.y - (free.second + 0.5) * cellKm)
        }
        // The other sites take the middle of their own cell and may share one.
        val officeDots = officeSpots.map { spot ->
            spot?.let {
                Vector2(corner.x + (floor((it.x - corner.x) / cellKm) + 0.5) * cellKm,
                        corner.y - (floor((corner.y - it.y) / cellKm) + 0.5) * cellKm)
            }
        }
        return Frozen(w.toInt(), h.toInt(), image, corner, cellKm, cols, rows, dots, officeDots, masks)
    }

    /** The factory a shot is on — a visit or a name — or null for a shot on the map at large. */
    private fun factoryOf(shot: MapShot): Int? = when (shot) {
        is FactoryVisit -> shot.factory
        is FactoryName -> shot.factory
        else -> null
    }?.takeIf { it in factories.indices }

    /** Where a factory stands, in degrees of latitude, for the stretch a shot on it is laid with. */
    private fun latitudeOf(factory: Int) = places.getOrNull(factory)?.y ?: BENELUX.lat

    /**
     * How high the pane is on the projection, in Web Mercator km. A span is stated in km of
     * ground, and Mercator stretches the ground by latitude, so it is laid on the map as that many
     * km times the stretch where the shot is looking. A [MapCountries] shot is the exception: it is
     * framed on the projection directly, off its countries' box, the height or the width at the
     * proportion of what its list leaves of the pane, whichever binds, and its margin either side.
     */
    private fun spanOf(shot: MapShot, w: Double, h: Double): Double = when (shot) {
        is MapFrame -> mercatorStretch(shot.lat) * shot.span
        is FactoryCluster -> mercatorStretch(cluster.y) * shot.span
        is FactoryVisit -> mercatorStretch(latitudeOf(shot.factory)) * shot.span
        is FactoryName -> mercatorStretch(latitudeOf(shot.factory)) * shot.span
        is MapCountries -> extents[shot]?.let { (low, high) ->
            val across = w - (columnOf(shot, w, h)?.right ?: 0.0)
            maxOf(high.y - low.y, (high.x - low.x) * h / across) * (1.0 + 2.0 * shot.margin)
        } ?: (mercatorStretch(BENELUX.lat) * BENELUX.span)
    }

    /**
     * Where a shot looks, in Web Mercator km. A visit stands its factory's dot at [anchor] across
     * the pane and in the middle of the map left **above the bar**, not of the whole pane; a
     * [MapCountries] shot stands the middle of its countries' box in the middle of what its list
     * leaves of the pane.
     */
    private fun centreOf(shot: MapShot, w: Double, h: Double, dots: List<Vector2?>): Vector2 = when (shot) {
        is MapFrame -> webMercator(Vector2(shot.lon, shot.lat))
        is FactoryCluster -> webMercator(cluster)
        is FactoryVisit, is FactoryName -> factoryOf(shot)
            ?.let { f -> dots.getOrNull(f) ?: sites.getOrNull(f) }
            ?.let { it + Vector2(w / 2.0 - anchor * w, -barOffset(h) / 2.0) * (spanOf(shot, w, h) / h) }
            ?: webMercator(Vector2(BENELUX.lon, BENELUX.lat))
        is MapCountries -> extents[shot]?.let { (low, high) ->
            val left = columnOf(shot, w, h)?.right ?: 0.0
            (low + high) * 0.5 - Vector2(left / 2.0 * spanOf(shot, w, h) / h, 0.0)
        } ?: webMercator(Vector2(BENELUX.lon, BENELUX.lat))
    }

    /**
     * A grey a country: those in [colours] as stated, the rest the palette grey none of their
     * neighbours has, most-connected first and, among the free ones, the least used so far.
     */
    private fun greys(countries: List<Country>, neighbours: List<Set<Int>>): List<ColorRGBa> {
        val assigned = arrayOfNulls<ColorRGBa>(countries.size)
        countries.forEachIndexed { k, country -> assigned[k] = colours[country.code] }
        val used = palette.associateWith { c -> assigned.count { it == c } }.toMutableMap()

        countries.indices.filter { assigned[it] == null }
            .sortedByDescending { neighbours[it].size }
            .forEach { k ->
                val near = neighbours[k].mapNotNull { assigned[it] }.toSet()
                val pick = palette.filter { it !in near }.minByOrNull { used[it] ?: 0 }
                    ?: palette.minBy { c -> neighbours[k].count { assigned[it] == c } }
                assigned[k] = pick
                used[pick] = (used[pick] ?: 0) + 1
            }
        return assigned.map { it!! }
    }

    /**
     * The tinted half of the map: where a [MapCountries] mask says a cell is one of its countries,
     * its slot's colour, as far in as the slot's alpha says; where it marks a country left off under
     * a list, the ground, as far in as `ground`'s alpha says; everywhere else nothing at
     * all. The slot rides in the mask's green — see [maskColour] — and is read back to the nearest step.
     */
    private val tinted by lazy {
        shadeStyle {
            fragmentTransform = """
                float g = x_fill.g;
                float lit = step($MASK_LIT, g);
                float gone = step(${MASK_GONE / 2.0}, g) * (1.0 - step(${(MASK_GONE + MASK_UNLIT) / 2.0}, g));
                int slot = int(clamp(floor((g - $MASK_FIRST) / $MASK_STEP + 0.5), 0.0, ${MAX_SLOTS - 1}.0));
                vec4 slots[$MAX_SLOTS] = vec4[$MAX_SLOTS](${(0 until MAX_SLOTS).joinToString { "p_slot$it" }});
                vec4 c = lit > 0.5 ? slots[slot] : vec4(p_ground.rgb, p_ground.a * gone);
                x_fill = c;
            """.trimIndent()
        }
    }

    private val vote by lazy {
        shadeStyle {
            fragmentPreamble = """
                const int S = $SAMPLES;
            """.trimIndent()
            fragmentTransform = """
                ivec2 cellAt = ivec2(gl_FragCoord.xy);
                ivec2 base = cellAt * S;
                vec3 s[S * S];
                for (int j = 0; j < S; j++)
                    for (int i = 0; i < S; i++)
                        s[j * S + i] = texelFetch(p_fine, base + ivec2(i, j), 0).rgb;

                // The colour covering most of the cell; a tie goes to land.
                int best = 0; float bestCount = 0.0; float bestScore = -1.0;
                for (int a = 0; a < S * S; a++) {
                    float n = 0.0;
                    for (int b = 0; b < S * S; b++) n += step(distance(s[a], s[b]), 0.004);
                    float score = n + (distance(s[a], p_sea) < 0.004 ? 0.0 : 0.5);
                    if (score > bestScore) { bestScore = score; bestCount = n; best = a; }
                }
                vec3 colour = s[best];

                // The runner-up, for a cell too split to call.
                if (p_mixed > 0.0 && bestCount / float(S * S) < p_mixed) {
                    int second = best; float secondCount = 0.0;
                    for (int a = 0; a < S * S; a++) {
                        if (distance(s[a], s[best]) < 0.004) continue;
                        float n = 0.0;
                        for (int b = 0; b < S * S; b++) n += step(distance(s[a], s[b]), 0.004);
                        if (n > secondCount) { secondCount = n; second = a; }
                    }
                    colour = mix(s[second], s[best], bestCount / (bestCount + secondCount));
                }

                x_fill = vec4(colour, 1.0);
            """.trimIndent()
        }
    }
}

/** Benelux with Nancy still in frame at the foot. */
val BENELUX = MapFrame("Benelux", 4.9, 50.9, 640.0)

/** Belgium filling the frame with a band of the Netherlands above it; Nancy falls out of frame. */
val BELGIUM = MapFrame("België", 4.7, 50.65, 300.0)


/** Five greys far enough apart to tell neighbours by, lightest first. */
val GREYS: List<ColorRGBa> = listOf("DCDCDC", "C9C9C9", "B6B6B6", "A3A3A3", "909090").map { ColorRGBa.fromHex(it) }

/**
 * The colours of the first reference, read off it by eye: France blue, Belgium red, the
 * Netherlands mint, Germany ochre, Luxembourg pink. Hand to `colours` and `palette` to get
 * that picture back.
 */
val REFERENCE_COLOURS: Map<String, ColorRGBa> = linkedMapOf(
    "FRA" to ColorRGBa.fromHex("554BF5"),
    "BEL" to ColorRGBa.fromHex("D45D46"),
    "NLD" to ColorRGBa.fromHex("A0F7B0"),
    "DEU" to ColorRGBa.fromHex("E6C75A"),
    "LUX" to ColorRGBa.fromHex("D45DA8")
)

/** Even-odd over every ring of a polygon, so a point in a hole is outside. */
private fun inside(point: Vector2, polygon: List<List<Vector2>>): Boolean {
    var odd = false
    for (ring in polygon) {
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.y > point.y) != (b.y > point.y) &&
                point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x) odd = !odd
            j = i
        }
    }
    return odd
}

/**
 * Which countries touch, by index: those whose vertices fall in the same or adjacent half-degree
 * square, so a strait counts. Read by the greys and by the shades of a [MapCountries] tint alike.
 */
private fun neighboursOf(countries: List<Country>): List<Set<Int>> {
    val squares = HashMap<Long, MutableSet<Int>>()
    countries.forEachIndexed { k, country ->
        country.polygons.forEach { polygon -> polygon.forEach { ring -> ring.forEach { v ->
            val key = (floor(v.x * 2.0).toLong() shl 32) xor (floor(v.y * 2.0).toLong() and 0xffffffffL)
            squares.getOrPut(key) { HashSet() }.add(k)
        } } }
    }
    val neighbours = List(countries.size) { HashSet<Int>() }
    for ((key, here) in squares) {
        val x = key shr 32
        val y = (key and 0xffffffffL).toInt().toLong()
        for (dx in -1L..1L) for (dy in -1L..1L) {
            val there = squares[((x + dx) shl 32) xor ((y + dy) and 0xffffffffL)] ?: continue
            for (m in here) for (n in there) if (m != n) neighbours[m].add(n)
        }
    }
    return neighbours
}

/**
 * A shade of [tones] for each country in [lit], so that two that touch differ where there are
 * shades enough: the greys' rule, most-connected first among the lit ones and, among the free
 * shades, the least used. With one shade every country takes it.
 */
private fun deal(lit: List<Int>, tones: Int, neighbours: List<Set<Int>>): Map<Int, Int> {
    val dealt = HashMap<Int, Int>()
    val used = IntArray(tones)
    val among = lit.toSet()
    lit.sortedByDescending { k -> neighbours[k].count { it in among } }.forEach { k ->
        val near = neighbours[k].mapNotNull { dealt[it] }.toSet()
        val pick = (0 until tones).filter { it !in near }.minByOrNull { used[it] }
            ?: (0 until tones).minBy { tone -> neighbours[k].count { dealt[it] == tone } }
        dealt[k] = pick
        used[pick]++
    }
    return dealt
}

/**
 * Where a country is, for the test that leaves it off under a list: the centroid of its largest
 * piece of land on the projection, so France is its mainland rather than a point between it and Corsica.
 */
private fun middleOf(country: Country): Vector2? {
    var best: Vector2? = null
    var bestArea = 0.0
    for (polygon in country.polygons) {
        val ring = polygon.firstOrNull()?.map { webMercator(it) } ?: continue
        if (ring.size < 3) continue
        val o = ring[0]
        var twice = 0.0
        var cx = 0.0
        var cy = 0.0
        for (i in ring.indices) {
            val p = ring[i] - o
            val q = ring[(i + 1) % ring.size] - o
            val cross = p.x * q.y - q.x * p.y
            twice += cross
            cx += (p.x + q.x) * cross
            cy += (p.y + q.y) * cross
        }
        if (abs(twice) > abs(bestArea)) {
            bestArea = twice
            best = o + Vector2(cx / (3.0 * twice), cy / (3.0 * twice))
        }
    }
    return best
}

/**
 * A country's colour in a [MapCountries] mask: unique to the country in red and blue, so the vote
 * splits every cell exactly as it does for the greys, and its [slot] in green — [MASK_UNLIT] where
 * it is not tinted, [MASK_FIRST] up in steps of [MASK_STEP] where it is, [MASK_GONE] where it is
 * [gone] under a list. Stated linear so it is
 * stored as written; the sea under it is black, which no country is.
 */
private fun maskColour(country: Int, slot: Int, gone: Boolean = false) = ColorRGBa(
    0.25 + 0.05 * (country % 16),
    when {
        slot >= 0 -> MASK_FIRST + MASK_STEP * slot.coerceAtMost(MAX_SLOTS - 1)
        gone -> MASK_GONE
        else -> MASK_UNLIT
    },
    0.25 + 0.05 * ((country / 16) % 16),
    1.0,
    Linearity.LINEAR
)

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
}

/** [t] of the way to [other], stated sRGB so a fill arrives as written. */
private fun ColorRGBa.towards(other: ColorRGBa, t: Double) = ColorRGBa(
    r + (other.r - r) * t, g + (other.g - g) * t, b + (other.b - b) * t, 1.0, Linearity.SRGB
)

/** How many panes the frozen grid spans each way; odd, so the pane's own cells stay centred. */
private const val REACH = 3

/** A factory dot's radius, as a share of the cell; another site's, as a share of that. */
private const val DOT = 0.45
private const val OFFICE = 0.5

/** Samples a cell, along each side. */
private const val SAMPLES = 4

/** How far a factory may be moved off a taken cell looking for its own, in cells. */
private const val MAX_RING = 6

/** The name and the address, as shares of the pane's height; the ems the faces load at. */
private const val NAME = 0.052
private const val TEXT = 0.026
private const val NAME_EM = 64.0
private const val TEXT_EM = 32.0

/** Cap height as a share of the size, for centring a line on a point. */
private const val CAP = 0.35

/**
 * The bar's lettering: how far below the padding the name's baseline sits and how far below that
 * each address or note line does, as shares of their sizes; the descent left under the last line;
 * the padding above and below and the left margin, as shares of the pane's height; what separates
 * the parts of an address set on one line.
 */
private const val ASCENT = 0.75
private const val ADDRESS_LEAD = 1.5
private const val DESCENT = 0.3
private const val BAR_PAD = 0.035
private const val BAR_MARGIN = 0.05
private const val SEPARATOR = "  ·  "


/**
 * City names as a share of the pane's height, at every zoom.
 * The overview is where two names come closest: at 0.02 Brugge and Antwerpen, 80 km apart, ran into
 * one another, and at 0.017 they were a hair apart.
 */
private const val CITY = 0.015

/**
 * A name set large: at most this share of the pane's height and of its width, the words it comes
 * apart into at most [PHRASE_WIDE] of the width; the em its face loads at; its cap height as a
 * share of the size, for centring it on the map left above the bar.
 */
private const val HERO = 0.26
private const val HERO_WIDE = 0.7
private const val PHRASE_WIDE = 0.86
private const val HERO_EM = 256.0
private const val CAP_HEIGHT = 0.7

/**
 * The share of a click between a name card and another place that the name takes to go (or come),
 * the camera holding still meanwhile; the camera has the rest.
 */
private const val HANDOFF = 0.35

/** How far below its place the lettering starts rising from, as a share of the name's size. */
private const val RISE = 0.35

/** One breath of a selected dot's colour pulse, in frames. */
private val BLINK_PERIOD = frames(1.6)

/** How sharply a factory's lettering comes and goes around its click: 3 is the last third of it. */
private const val LABEL_REACH = 3.0

/**
 * A [MapCountries] click, as shares of the camera's move: the tint comes in from [TINT_FROM] of it
 * to its end, while the cities are gone by [CITY_GO]; a click out of one plays the same windows
 * mirrored.
 */
private const val TINT_FROM = 0.4
private const val CITY_GO = 0.35

/**
 * A countries build: the first country starts to light at [LIGHT_FROM] of the camera's move, as
 * it lands, and each takes [LIGHT] seconds to come up.
 */
private const val LIGHT_FROM = 0.85
private const val LIGHT = 0.5

/**
 * The countries' list: a name and a note as shares of the pane's height, a name larger than a
 * slide's title so it reads across the room, a note at the body size; the em the list's face loads
 * at; the left edge as a share of the width (the programme's list's margin); from a name's baseline
 * to its note's first, and from one note line to the next, as shares of their sizes; from the last
 * baseline of an entry to the next name's, as a share of the name; and the room left between the
 * column and the map, as a share of the height.
 */
private const val LIST_NAME = 0.06
private const val LIST_NOTE = 0.0287
private const val LIST_EM = 72.0
private const val LIST_LEFT = 0.05
private const val LIST_NOTE_DROP = 0.72
private const val LIST_NOTE_LEAD = 1.25
private const val LIST_ROW = 1.4
private const val LIST_GAP = 0.06

/** Slots a [MapCountries] tint can fade apart — shades, or a build's countries — as many as its mask's green carries. */
private const val MAX_SLOTS = 8

/**
 * A mask's green: a country left off under a list, an untinted country, the first slot and
 * the step to the next, and the threshold between untinted and tinted. Stored in eight bits as 64,
 * 128, then 143 to 255 in steps of 16; the sea is 0.
 */
private const val MASK_GONE = 0.25
private const val MASK_UNLIT = 0.5
private const val MASK_FIRST = 0.5625
private const val MASK_STEP = 0.0625
private const val MASK_LIT = 0.53125

/** The most cells the grid may have along a side, however far a shot reaches; four times that is the sample buffer. */
private const val MAX_CELLS = 1500
