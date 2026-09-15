import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.openrndr.math.Vector2
import java.io.File
import java.net.URI
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.tan

/**
 * The countries of Europe, collected once from Natural Earth into `data/collected/natural-earth`
 * so later runs need no network — the same arrangement as `collectMapData` for PDOK.
 *
 *     ./gradlew run -Popenrndr.application=CountriesKt
 *
 * **Admin-0 with the lakes cut out**, at `COUNTRIES_SCALE` (10m by default: it carries the
 * Wadden islands, which 50m draws as a handful of blobs). Only polygons touching [EUROPE_BOX]
 * are kept, polygon by polygon rather than country by country — France's admin-0 feature
 * includes Guyane and Réunion, and the Netherlands' the Caribbean islands.
 *
 * Coordinates stay **longitude and latitude**, rounded to 1e-4 degrees (about 11 m). The
 * projection is the drawer's business, see [webMercator].
 *
 * Countries are keyed by `ADM0_A3`, never `ISO_A3`: Natural Earth carries `-99` in ISO_A3 for
 * France and Norway, so the most obvious key silently loses two of the biggest pieces.
 */
fun collectCountries(
    scale: String = Env["COUNTRIES_SCALE"]?.takeIf { it.isNotBlank() } ?: "10m",
    refresh: Boolean = Env.boolean("COUNTRIES_REFRESH"),
    folder: File = File("data/collected/natural-earth")
): File {
    val file = File(folder, "countries-$scale.geojson")
    if (file.isFile && !refresh) return file

    val source = "$NATURAL_EARTH/ne_${scale}_admin_0_countries_lakes.geojson"
    println("collecting countries from $source")
    val body = URI(source).toURL().openStream().bufferedReader().use { it.readText() }
    val features = countryParser.parseToJsonElement(body).jsonObject["features"]!!.jsonArray

    val kept = features.mapNotNull { element ->
        val feature = element.jsonObject
        val geometry = feature["geometry"]?.jsonObject ?: return@mapNotNull null
        val coordinates = geometry["coordinates"]?.jsonArray ?: return@mapNotNull null
        val polygons = when (geometry["type"]?.jsonPrimitive?.content) {
            "Polygon" -> listOf(coordinates)
            "MultiPolygon" -> coordinates.map { it.jsonArray }
            else -> return@mapNotNull null
        }.filter { touchesEurope(it) }
        if (polygons.isEmpty()) return@mapNotNull null

        val properties = feature["properties"]!!.jsonObject
        buildJsonObject {
            put("type", "Feature")
            put("properties", buildJsonObject {
                for (key in listOf("ADM0_A3", "NAME", "NAME_NL")) properties[key]?.let { put(key, it) }
            })
            put("geometry", buildJsonObject {
                put("type", "MultiPolygon")
                put("coordinates", JsonArray(polygons.map { roundedDegrees(it) }))
            })
        }
    }

    folder.mkdirs()
    file.writeText(buildJsonObject {
        put("type", "FeatureCollection")
        put("features", JsonArray(kept))
    }.toString())
    File(folder, "manifest.json").writeText(buildJsonObject {
        put("source", source)
        put("scale", scale)
        put("box", buildJsonArray { EUROPE_BOX.forEach { add(JsonPrimitive(it)) } })
        put("countries", kept.size)
        put("collected", LocalDate.now().toString())
        put("licence", "Public domain — Made with Natural Earth")
    }.toString())
    println("wrote ${kept.size} countries to ${file.path} (${file.length() / 1024} KB)")
    return file
}

/** One country as collected: its code, its names, and its polygons as rings of lon/lat. */
class Country(
    val code: String,
    val name: String,
    val nameNl: String?,
    /** Polygons, each an outer ring followed by its holes. GeoJSON's closing point dropped. */
    val polygons: List<List<List<Vector2>>>
)

fun loadCountries(file: File): List<Country> =
    countryParser.parseToJsonElement(file.readText()).jsonObject["features"]!!.jsonArray.map { element ->
        val feature = element.jsonObject
        val properties = feature["properties"]!!.jsonObject
        fun text(key: String) = properties[key]?.jsonPrimitive?.content
        val polygons = feature["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map { polygon ->
            polygon.jsonArray.map { ring ->
                ring.jsonArray.map {
                    val pair = it.jsonArray
                    Vector2(pair[0].jsonPrimitive.double, pair[1].jsonPrimitive.double)
                }.dropLast(1)
            }
        }
        Country(text("ADM0_A3") ?: "?", text("NAME") ?: "?", text("NAME_NL"), polygons)
    }

/**
 * Longitude and latitude to kilometres on **Web Mercator** — EPSG:3857, the projection Google
 * Maps, OpenStreetMap and every slippy map draw in — with x east and y north.
 *
 * The units are the projection's, not the ground's: they are true only at the equator and a
 * kilometre on the ground is [mercatorStretch] of them at its latitude, 1.6 at Belgium's. So a
 * distance measured on the ground has to be scaled by that before it is laid on the map.
 * Latitude is clamped to Mercator's own ±85.05, where the square world map ends.
 */
fun webMercator(lonLat: Vector2): Vector2 {
    val phi = Math.toRadians(lonLat.y.coerceIn(-MERCATOR_LIMIT, MERCATOR_LIMIT))
    return Vector2(
        MERCATOR_KM * Math.toRadians(lonLat.x),
        MERCATOR_KM * ln(tan(PI / 4.0 + phi / 2.0))
    )
}

/** How many Web Mercator kilometres one kilometre on the ground is, at [latitude]. */
fun mercatorStretch(latitude: Double): Double =
    1.0 / cos(Math.toRadians(latitude.coerceIn(-MERCATOR_LIMIT, MERCATOR_LIMIT)))

private fun touchesEurope(polygon: JsonArray): Boolean {
    val outer = polygon.first().jsonArray
    var west = Double.MAX_VALUE; var east = -Double.MAX_VALUE
    var south = Double.MAX_VALUE; var north = -Double.MAX_VALUE
    for (point in outer) {
        val pair = point.jsonArray
        val x = pair[0].jsonPrimitive.double
        val y = pair[1].jsonPrimitive.double
        if (x < west) west = x; if (x > east) east = x
        if (y < south) south = y; if (y > north) north = y
    }
    return east >= EUROPE_BOX[0] && north >= EUROPE_BOX[1] && west <= EUROPE_BOX[2] && south <= EUROPE_BOX[3]
}

private fun roundedDegrees(polygon: JsonArray) = JsonArray(polygon.map { ring ->
    JsonArray(ring.jsonArray.map { point ->
        val pair = point.jsonArray
        JsonArray(listOf(
            JsonPrimitive((pair[0].jsonPrimitive.double * 1e4).roundToLong() / 1e4),
            JsonPrimitive((pair[1].jsonPrimitive.double * 1e4).roundToLong() / 1e4)
        ))
    })
})

fun main() {
    collectCountries(refresh = true)
}

private val countryParser = Json { ignoreUnknownKeys = true }

private const val NATURAL_EARTH = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson"

/** West, south, east, north in degrees: Iceland to the Urals, the Maghreb to the North Cape. */
private val EUROPE_BOX = doubleArrayOf(-32.0, 30.0, 60.0, 75.0)

/** The WGS84 semi-major axis, which is the sphere EPSG:3857 is drawn on. */
private const val MERCATOR_KM = 6378.137
private const val MERCATOR_LIMIT = 85.05112878
