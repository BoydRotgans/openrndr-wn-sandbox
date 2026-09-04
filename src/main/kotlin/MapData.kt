import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import kotlin.math.roundToLong

/**
 * Collects Dutch map data — building contours with their registered function, plus
 * roads and water — from the national open registers, and caches it as GeoJSON.
 *
 *     ./gradlew run -Popenrndr.application=MapDataKt
 *
 * Everything comes from PDOK, the government's open data service. No key, no account,
 * no rate limit worth worrying about:
 *
 *   - BAG  (Basisregistratie Adressen en Gebouwen) via WFS 2.0 — `pand` is the building
 *     footprint, and it carries `gebruiksdoel`, the registered function, already joined
 *     on from the units inside it. That is the whole ask in one layer.
 *   - BGT  (Basisregistratie Grootschalige Topografie) via OGC API Features — `wegdeel`
 *     and `waterdeel` give roads and water as *surfaces*, not centre lines.
 *
 * Coordinates are kept in EPSG:28992 (RD New), the Dutch national grid. It is already
 * in metres with a y axis pointing north, so a contour needs no projection maths to be
 * drawn — only a flip and a scale. Distances are true; 1 unit is 1 metre on the ground.
 *
 * Fetched once into data/collected/<place>, so later runs need no network. Set
 * MAP_REFRESH=true to pull again.
 */

// ---------------------------------------------------------------------------------
// what gets collected
// ---------------------------------------------------------------------------------

/**
 * A layer to pull. [keep] is the set of properties worth carrying: the registers ship a
 * lot of bookkeeping (codespace urls, void-reason flags, registration timestamps) that
 * would triple the file size and say nothing about the shape.
 */
private data class Layer(
    val name: String,
    val service: Service,
    val typeName: String,
    val keep: Set<String>
)

private enum class Service { BAG_WFS, BGT_OGC, BRT_OGC }

/** Buildings are always BAG: nothing else has the real footprint and its function. */
private val BUILDINGS = Layer(
    "buildings", Service.BAG_WFS, "bag:pand",
    setOf(
        "identificatie", "gebruiksdoel", "bouwjaar", "status",
        "oppervlakte_min", "oppervlakte_max", "aantal_verblijfsobjecten"
    )
)

/**
 * The ground, at 1:500. BGT is drawn to the kerbstone and is the right thing for a few
 * square kilometres.
 */
private val DETAIL_GROUND = listOf(
    Layer(
        "roads", Service.BGT_OGC, "wegdeel",
        setOf("lokaal_id", "functie", "plus_functie", "fysiek_voorkomen", "relatieve_hoogteligging")
    ),
    Layer(
        "water", Service.BGT_OGC, "waterdeel",
        setOf("lokaal_id", "type", "plus_type", "relatieve_hoogteligging")
    ),
    Layer(
        "nature", Service.BGT_OGC, "begroeidterreindeel",
        setOf("lokaal_id", "fysiek_voorkomen", "plus_fysiek_voorkomen", "relatieve_hoogteligging")
    )
)

/**
 * The ground, at 1:10 000. Past a few kilometres BGT is the wrong register: it carries six
 * times the vertices for detail that is far below a pixel, and half of what it returns is
 * retired versions that have to be thrown away again. TOP10NL is the same country
 * generalised for this scale, and it keeps no history to filter.
 */
private val WIDE_GROUND = listOf(
    Layer("roads", Service.BRT_OGC, "wegdeel_vlak",
        setOf("hoofdverkeersgebruik", "typeInfrastructuur", "fysiekVoorkomen", "naam")),
    Layer("water", Service.BRT_OGC, "waterdeel_vlak",
        setOf("type", "naam", "breedteklasse")),
    Layer("nature", Service.BRT_OGC, "terrein_vlak",
        setOf("typeLandgebruik", "naam"))
)

/**
 * Which register the ground comes from. BGT is drawn to the kerbstone and is right for a
 * few square kilometres; past that its detail is far below a pixel and it becomes the
 * bottleneck, so TOP10NL takes over. `auto` switches at 6 km across.
 */
private fun groundLayers(extentWidth: Double, mode: String?): List<Layer> = when (mode?.lowercase()) {
    "bgt", "detail" -> DETAIL_GROUND
    "brt", "wide", "top10nl" -> WIDE_GROUND
    else -> if (extentWidth > 6000.0) WIDE_GROUND else DETAIL_GROUND
}

/** Buildings that are not there to be drawn: never built, or already gone. */
private val ABSENT_BUILDING_STATUS = setOf("Pand gesloopt", "Niet gerealiseerd pand")

private const val BAG_WFS = "https://service.pdok.nl/lv/bag/wfs/v2_0"
private const val BGT_OGC = "https://api.pdok.nl/lv/bgt/ogc/v1_0"
private const val BRT_OGC = "https://api.pdok.nl/brt/top10nl/ogc/v1_0"
private const val LOCATIESERVER = "https://api.pdok.nl/bzk/locatieserver/search/v3_1/free"
private const val RD = "EPSG:28992"
private const val RD_URI = "http://www.opengis.net/def/crs/EPSG/0/28992"

/** Both services cap a page at 1000 features however many you ask for. */
private const val PAGE = 1000

/** The manifest is meant to be read by a person. */
private val json = Json { prettyPrint = true; prettyPrintIndent = "    " }

/**
 * The layers are not. Pretty-printing GeoJSON puts every single coordinate on its own
 * line: the road surfaces alone came to 315 MB that way, which is minutes of parsing
 * before a sketch can draw anything.
 */
private val compact = Json { prettyPrint = false }
private val parser = Json { ignoreUnknownKeys = true }

// ---------------------------------------------------------------------------------
// manifest
// ---------------------------------------------------------------------------------

@Serializable
data class MapLayer(val name: String, val source: String, val path: String, val features: Int) {
    val file: File get() = File(path)
}

@Serializable
data class MapArea(
    val place: String,
    val centre: List<Double>,
    val radius: Double,
    /** minX, minY, maxX, maxY in RD New metres. */
    val bbox: List<Double>,
    val crs: String,
    val attribution: String,
    val layers: List<MapLayer>
) {
    fun layer(name: String): MapLayer =
        layers.find { it.name == name } ?: error("no layer '$name'. Collected: ${layers.joinToString { it.name }}")

    val width: Double get() = bbox[2] - bbox[0]
    val height: Double get() = bbox[3] - bbox[1]

    /** Which registers this was built from, for a caption. */
    fun registers(): String = layers.map { it.source.substringBefore(" ") }
        .map { if (it == "BRT_OGC") "TOP10NL" else it.removeSuffix("_WFS").removeSuffix("_OGC") }
        .distinct().joinToString(" + ")
}

// ---------------------------------------------------------------------------------
// fetching
// ---------------------------------------------------------------------------------

/**
 * Building contours, roads and water around [centreQuery], cached under data/collected.
 *
 * [centreQuery] is geocoded, and is deliberately not just the city name: geocoding a
 * woonplaats returns the centroid of the whole municipal area, which for 's-Hertogenbosch
 * lands 2.3km north of the old town in the post-war suburbs. Naming a street or square
 * ("Markt, 's-Hertogenbosch") frames what you actually mean by the centre. An explicit
 * "x,y" in RD metres is taken as given.
 */
fun collectMapData(
    place: String = Env["MAP_PLACE"] ?: "'s-Hertogenbosch",
    centreQuery: String = Env["MAP_CENTRE"] ?: "Markt, 's-Hertogenbosch",
    radius: Double = Env["MAP_RADIUS"]?.toDoubleOrNull() ?: 1000.0,
    /**
     * Metres of country to keep around the *whole* built-up area, instead of a radius
     * around a point. Set it and the extent becomes the city's own outline grown by this
     * much, which is the only way to promise a margin on every side: the city is not
     * square and not centred on its market square, so a radius that clears the far edge
     * overshoots the near one.
     */
    margin: Double? = Env["MAP_MARGIN"]?.toDoubleOrNull()?.takeIf { it > 0 },
    refresh: Boolean = Env.boolean("MAP_REFRESH")
): MapArea {
    val dir = File("data/collected", slug(place, radius, margin))
    val manifestFile = File(dir, "manifest.json")

    if (!refresh && manifestFile.isFile) {
        runCatching { parser.decodeFromString(MapArea.serializer(), manifestFile.readText()) }
            .getOrNull()
            ?.takeIf { area -> area.layers.all { it.file.isFile } }
            ?.let { return it }
    }

    dir.mkdirs()

    val bbox: DoubleArray
    val centre: DoubleArray
    if (margin != null) {
        val city = woonplaatsBbox(place)
        bbox = doubleArrayOf(city[0] - margin, city[1] - margin, city[2] + margin, city[3] + margin)
        centre = doubleArrayOf((bbox[0] + bbox[2]) / 2, (bbox[1] + bbox[3]) / 2)
        println("%s is %.1f x %.1f km; with a %.0f m margin that is %.1f x %.1f km"
            .format(place, (city[2] - city[0]) / 1000, (city[3] - city[1]) / 1000, margin,
                (bbox[2] - bbox[0]) / 1000, (bbox[3] - bbox[1]) / 1000))
    } else {
        centre = resolveCentre(centreQuery)
        bbox = doubleArrayOf(centre[0] - radius, centre[1] - radius, centre[0] + radius, centre[1] + radius)
        println("collecting %s around %.0f, %.0f RD - %.1f x %.1f km"
            .format(place, centre[0], centre[1], (bbox[2] - bbox[0]) / 1000, (bbox[3] - bbox[1]) / 1000))
    }

    val layers = listOf(BUILDINGS) + groundLayers(bbox[2] - bbox[0], Env["MAP_DETAIL"])
    println("ground from ${if (layers[1].service == Service.BRT_OGC) "TOP10NL (1:10 000)" else "BGT (1:500)"}")

    val collected = layers.map { layer ->
        val features = when (layer.service) {
            Service.BAG_WFS -> fetchBagWfs(layer, bbox)
            Service.BGT_OGC -> fetchOgcFeatures(layer, bbox, BGT_OGC, dropRetired = true)
            Service.BRT_OGC -> fetchOgcFeatures(layer, bbox, BRT_OGC, dropRetired = false)
        }
        val target = File(dir, "${layer.name}.geojson")
        target.writeText(compact.encodeToString(JsonObject.serializer(), featureCollection(features)))
        println("  ${layer.name}: ${features.size} features -> ${target.path}")
        MapLayer(layer.name, "${layer.service} ${layer.typeName}", target.path, features.size)
    }

    val area = MapArea(
        place = place,
        centre = centre.toList(),
        radius = radius,
        bbox = bbox.toList(),
        crs = RD,
        attribution = "BAG and BGT via PDOK, Kadaster. Public domain (CC0).",
        layers = collected
    )
    manifestFile.writeText(json.encodeToString(MapArea.serializer(), area))
    return area
}

/**
 * The bounding box of the woonplaats itself — the official outline of the built-up area,
 * not a guess from a point.
 */
private fun woonplaatsBbox(place: String): DoubleArray {
    val filter = """<fes:Filter xmlns:fes="http://www.opengis.net/fes/2.0">""" +
            "<fes:PropertyIsEqualTo><fes:ValueReference>woonplaats</fes:ValueReference>" +
            "<fes:Literal>$place</fes:Literal></fes:PropertyIsEqualTo></fes:Filter>"

    val body = httpGet(url(BAG_WFS, mapOf(
        "service" to "WFS", "version" to "2.0.0", "request" to "GetFeature",
        "typeNames" to "bag:woonplaats", "outputFormat" to "application/json",
        "srsName" to RD, "filter" to filter
    )))

    val features = parser.parseToJsonElement(body).jsonObject["features"]?.jsonArray
    val geometry = features?.firstOrNull()?.jsonObject?.get("geometry")
        ?: error("no woonplaats named '$place'. The name must match BAG exactly, apostrophe and all.")

    var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
    fun walk(element: JsonElement) {
        val array = element.jsonArray
        val first = array.firstOrNull()
        if (first is JsonPrimitive) {
            val x = array[0].jsonPrimitive.double; val y = array[1].jsonPrimitive.double
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        } else array.forEach { walk(it) }
    }
    walk(geometry.jsonObject["coordinates"]!!)
    return doubleArrayOf(minX, minY, maxX, maxY)
}

/** A place geocoded to RD metres, for callers outside this file. */
fun resolvePlace(query: String): org.openrndr.math.Vector2 =
    resolveCentre(query).let { org.openrndr.math.Vector2(it[0], it[1]) }

/** A geocoded place, or an explicit "x,y" in RD metres. */
private fun resolveCentre(query: String): DoubleArray {
    Regex("""\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*""").matchEntire(query)?.let { m ->
        return doubleArrayOf(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
    }

    val body = httpGet(url(LOCATIESERVER, mapOf("q" to query, "rows" to "1")))
    val docs = parser.parseToJsonElement(body).jsonObject["response"]?.jsonObject?.get("docs")?.jsonArray
    val first = docs?.firstOrNull()?.jsonObject ?: error("nothing found for '$query'")
    val point = first["centroide_rd"]?.jsonPrimitive?.content
        ?: error("'$query' resolved to a result with no RD coordinate")

    // "POINT(149231.237 411174.664)"
    val m = Regex("""POINT\(([-\d.]+) ([-\d.]+)\)""").find(point)
        ?: error("could not read a coordinate from '$point'")
    println("centre '${first["weergavenaam"]?.jsonPrimitive?.content}' -> $point")
    return doubleArrayOf(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
}

/**
 * BAG over WFS 2.0: tiled, then paged with startIndex inside each tile.
 *
 * Two limits shape this. The server refuses a `startIndex` above 50 000 outright — it
 * says so, and points at the bulk extracts for anything larger — so a query that matches
 * more than that cannot be walked to the end however patient you are. The extent is
 * therefore split into quarters until every tile is under the limit, and each tile paged
 * on its own. A bbox query returns anything that *touches* the box, so a building on a
 * tile edge comes back in both; the identifier set below is what makes that harmless.
 *
 * And `sortBy` is not decoration: WFS may return an unsorted result in any order it likes
 * per request, and pages of an unstable order silently overlap and skip. Sorted,
 * consecutive pages of 500 came back with zero overlap.
 */
private const val MAX_START_INDEX = 45_000

private fun fetchBagWfs(layer: Layer, bbox: DoubleArray): List<JsonObject> {
    val features = mutableListOf<JsonObject>()
    val seen = mutableSetOf<String>()
    var dropped = 0
    var duplicates = 0
    var tiles = 0

    fun page(box: DoubleArray) {
        tiles++
        var startIndex = 0
        while (true) {
            val body = httpGet(url(BAG_WFS, mapOf(
                "service" to "WFS",
                "version" to "2.0.0",
                "request" to "GetFeature",
                "typeNames" to layer.typeName,
                "outputFormat" to "application/json",
                "srsName" to RD,
                "sortBy" to "identificatie",
                "count" to "$PAGE",
                "startIndex" to "$startIndex",
                "bbox" to "${box.joinToString(",")},urn:ogc:def:crs:EPSG::28992"
            )))

            val chunk = parser.parseToJsonElement(body).jsonObject["features"]?.jsonArray ?: break
            if (chunk.isEmpty()) break

            for (element in chunk) {
                val feature = element.jsonObject
                val properties = feature["properties"]?.jsonObject ?: continue
                val id = properties["identificatie"]?.jsonPrimitive?.content
                if (id != null && !seen.add(id)) { duplicates++; continue }
                if (properties["status"]?.jsonPrimitive?.content in ABSENT_BUILDING_STATUS) {
                    dropped++
                    continue
                }
                features += trimmed(feature, layer.keep)
            }

            if (chunk.size < PAGE) break
            startIndex += PAGE
            print("\r  ${layer.name}: ${features.size}...")
        }
    }

    fun collect(box: DoubleArray, depth: Int) {
        val matched = countMatched(layer, box)
        if (matched == 0) return
        if (matched <= MAX_START_INDEX || depth >= 6) {
            page(box)
        } else {
            val midX = (box[0] + box[2]) / 2
            val midY = (box[1] + box[3]) / 2
            collect(doubleArrayOf(box[0], box[1], midX, midY), depth + 1)
            collect(doubleArrayOf(midX, box[1], box[2], midY), depth + 1)
            collect(doubleArrayOf(box[0], midY, midX, box[3]), depth + 1)
            collect(doubleArrayOf(midX, midY, box[2], box[3]), depth + 1)
        }
    }

    collect(bbox, 0)
    println("\r  ${layer.name}: ${features.size} features from $tiles tile(s)" +
            (if (dropped > 0) ", dropped $dropped demolished or unbuilt" else "") +
            (if (duplicates > 0) ", $duplicates duplicates across tile edges" else ""))
    return features
}

/** How many features a query would match, without fetching any of them. */
private fun countMatched(layer: Layer, bbox: DoubleArray): Int {
    val body = httpGet(url(BAG_WFS, mapOf(
        "service" to "WFS", "version" to "2.0.0", "request" to "GetFeature",
        "typeNames" to layer.typeName, "resultType" to "hits",
        "bbox" to "${bbox.joinToString(",")},urn:ogc:def:crs:EPSG::28992"
    )))
    return Regex("""numberMatched="(\d+)"""").find(body)?.groupValues?.get(1)?.toInt() ?: 0
}

/**
 * BGT over OGC API Features, paged by following the `next` link.
 *
 * The register is historical: every version of a surface ever recorded comes back, and a
 * retired one is marked with eind_registratie. Over the Den Bosch centre that is more than
 * half of what is returned — 530 of 1000 road surfaces, 287 of 357 water surfaces — so
 * keeping them would draw the city as it was and as it is at the same time, on top of each
 * other. The API has no CQL support to filter it away server side, so it is done here.
 */
private fun fetchOgcFeatures(
    layer: Layer,
    bbox: DoubleArray,
    base: String,
    dropRetired: Boolean
): List<JsonObject> {
    val features = mutableListOf<JsonObject>()
    var retired = 0

    var next: String? = url("$base/collections/${layer.typeName}/items", mapOf(
        "bbox" to bbox.joinToString(","),
        "bbox-crs" to RD_URI,
        "crs" to RD_URI,
        "limit" to "$PAGE",
        "f" to "json"
    ))

    while (next != null) {
        val document = parser.parseToJsonElement(httpGet(next)).jsonObject
        val page = document["features"]?.jsonArray ?: break

        for (element in page) {
            val feature = element.jsonObject
            val properties = feature["properties"]?.jsonObject ?: continue
            if (dropRetired && properties["eind_registratie"].let { it != null && it != JsonNull }) {
                retired++
                continue
            }
            features += trimmed(feature, layer.keep)
        }

        next = document["links"]?.jsonArray
            ?.map { it.jsonObject }
            ?.find { it["rel"]?.jsonPrimitive?.content == "next" }
            ?.get("href")?.jsonPrimitive?.content
        print("\r  ${layer.name}: ${features.size}...")
    }
    if (retired > 0) println("\r  ${layer.name}: skipped $retired retired versions")
    else println("\r  ${layer.name}: ${features.size} features")
    return features
}

/** The feature with only [keep] properties, and no bbox echo. */
private fun trimmed(feature: JsonObject, keep: Set<String>): JsonObject {
    val properties = feature["properties"]?.jsonObject ?: JsonObject(emptyMap())
    return buildJsonObject {
        put("type", "Feature")
        put("properties", JsonObject(properties.filterKeys { it in keep }.filterValues { it != JsonNull }))
        feature["geometry"]?.let { put("geometry", rounded(it)) }
    }
}

/**
 * Coordinates rounded to the centimetre.
 *
 * The services hand back up to eleven decimal places — sub-nanometre — for registers whose
 * real positional accuracy is about 0.2 m. Every digit past the second is noise that costs
 * only file size, and dropping it stays far below the point where any of this is true.
 */
private fun rounded(element: JsonElement): JsonElement = when (element) {
    is JsonArray -> JsonArray(element.map { rounded(it) })
    is JsonPrimitive ->
        if (element.isString) element
        else element.doubleOrNull?.let { JsonPrimitive((it * 100).roundToLong() / 100.0) } ?: element
    else -> element
}

private fun featureCollection(features: List<JsonObject>) = buildJsonObject {
    put("type", "FeatureCollection")
    put("name", "pdok")
    put("crs", buildJsonObject {
        put("type", "name")
        put("properties", buildJsonObject { put("name", "urn:ogc:def:crs:EPSG::28992") })
    })
    put("features", JsonArray(features))
}

// ---------------------------------------------------------------------------------
// plumbing
// ---------------------------------------------------------------------------------

private fun url(base: String, params: Map<String, String>) = params.entries.joinToString(
    separator = "&",
    prefix = if ("?" in base) "$base&" else "$base?"
) { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

private fun httpGet(target: String): String {
    val connection = (URI(target).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 30_000
        readTimeout = 120_000
        setRequestProperty("Accept-Encoding", "gzip")
        setRequestProperty("User-Agent", "openrndr-wn-sandbox")
    }
    if (connection.responseCode !in 200..299) {
        val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(400).orEmpty()
        error("${connection.responseCode} from $target\n$detail")
    }
    val stream = if (connection.contentEncoding == "gzip") GZIPInputStream(connection.inputStream)
    else connection.inputStream
    return stream.bufferedReader().use { it.readText() }
}

private fun slug(place: String, radius: Double, margin: Double?): String {
    val name = place.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    return if (margin != null) "$name-m${margin.toInt()}" else "$name-r${radius.toInt()}"
}

// ---------------------------------------------------------------------------------
// reading it back
// ---------------------------------------------------------------------------------

/** The features of [layer], as parsed GeoJSON. */
fun MapArea.features(layer: String): List<JsonObject> =
    parser.parseToJsonElement(layer(layer).file.readText())
        .jsonObject["features"]!!.jsonArray.map { it.jsonObject }

/** A property of a feature, or null. */
fun JsonObject.property(name: String): String? =
    this["properties"]?.jsonObject?.get(name)?.jsonPrimitive?.content

/** The BAG function of a building as its separate parts: "winkelfunctie,woonfunctie". */
fun JsonObject.functions(): List<String> =
    property("gebruiksdoel")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

/**
 * Every ring of a feature's geometry, outer and inner alike, in RD metres.
 *
 * Polygon and MultiPolygon are both flattened to rings: for drawing a footprint the
 * distinction rarely matters, and a caller that needs holes can tell them apart by
 * winding or by area.
 */
fun JsonObject.rings(): List<List<DoubleArray>> {
    val geometry = this["geometry"]?.jsonObject ?: return emptyList()
    val coordinates = geometry["coordinates"]?.jsonArray ?: return emptyList()
    return when (geometry["type"]?.jsonPrimitive?.content) {
        "Polygon" -> coordinates.map { ring(it) }
        "MultiPolygon" -> coordinates.flatMap { polygon -> polygon.jsonArray.map { ring(it) } }
        else -> emptyList()
    }
}

private fun ring(element: JsonElement) = element.jsonArray.map { point ->
    val pair = point.jsonArray
    doubleArrayOf(pair[0].jsonPrimitive.double, pair[1].jsonPrimitive.double)
}

fun main() {
    val area = collectMapData()
    println()
    println("${area.place}: ${"%.1f x %.1f km".format(area.width / 1000, area.height / 1000)} in ${area.crs}")
    area.layers.forEach { println("  ${it.name.padEnd(10)} ${it.features.toString().padStart(6)}  ${it.source}") }

    val buildings = area.features("buildings")
    val byFunction = buildings.flatMap { it.functions() }.groupingBy { it }.eachCount()
    val withoutFunction = buildings.count { it.functions().isEmpty() }

    println()
    println("building function:")
    byFunction.entries.sortedByDescending { it.value }
        .forEach { (function, count) -> println("  ${count.toString().padStart(6)}  $function") }
    println("  ${withoutFunction.toString().padStart(6)}  (none - sheds, garages, outbuildings with no registered unit)")
}
