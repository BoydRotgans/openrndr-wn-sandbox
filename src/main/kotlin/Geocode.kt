import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.openrndr.math.Vector2
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Where an address landed: longitude and latitude, and what the service matched it to. */
class GeoHit(val lonLat: Vector2, val matched: String, val precision: String)

/**
 * An address to a point, through OpenStreetMap's Nominatim, cached in
 * `data/collected/geocode/addresses.json` so it is asked once per address and never again —
 * the `collectMapData` arrangement applied to a list of addresses. No key, no account.
 * `GEOCODE_REFRESH=true` asks again.
 *
 * **Addresses as people write them do not match as written**, so each is tried as a short
 * chain of simpler forms, most specific first, and the first that answers wins:
 *
 *  - only what follows the last comma: "Industriezone II, Nederwijk-Oost 279" finds nothing,
 *    "Nederwijk-Oost 279" finds the building;
 *  - a house number range as its first number: "15-17" as "15";
 *  - without the Dutch articles: "Bedrijvenpark de Coupure 15" finds nothing,
 *    "Bedrijvenpark Coupure 15" finds the building;
 *  - with and without the postcode and the place, since a `CEDEX` postcode or a deelgemeente
 *    the service files under its municipality each lose the match on their own;
 *  - and last the postcode and place alone, which puts it in the right town at least.
 *
 * `precision` says which it came to — `building` against `postcode` — and the load prints it,
 * because a factory placed at the middle of its town looks exactly like one placed right.
 *
 * Nominatim asks for one request a second at most and a real User-Agent; both are kept. A miss
 * is cached too, so a run without network or with a bad address does not stall on it again.
 */
fun geocode(street: String, postcode: String, place: String, country: String): GeoHit? {
    val key = listOf(street, postcode, place, country).joinToString(" | ")
    val cache = geocodeCache()
    if (key in cache && !Env.boolean("GEOCODE_REFRESH")) return cache[key]

    val code = COUNTRY_CODES[country.trim().lowercase()] ?: country.trim().lowercase().take(2)
    val postal = postcode.replace(Regex("^[A-Za-z]+-"), "").trim()

    val lastPart = street.substringAfterLast(',').trim()
    val firstNumber = lastPart.replace(Regex("(\\d+)\\s*-\\s*\\d+"), "$1")
    val noArticles = firstNumber.split(Regex("\\s+")).filterNot { it.lowercase() in ARTICLES }.joinToString(" ")
    val streets = listOf(firstNumber, noArticles).distinct()

    val attempts = streets.flatMap { s ->
        listOf(Attempt(s, postal, place), Attempt(s, null, place), Attempt(s, postal, null))
    } + listOf(Attempt(null, postal, place), Attempt(null, null, place))

    var hit: GeoHit? = null
    for (attempt in attempts) {
        hit = runCatching { nominatim(attempt, code) }
            .onFailure { println("geocode: ${it.message}") }
            .getOrNull()
        if (hit != null) break
    }
    cache[key] = hit
    writeGeocodeCache(cache)
    return hit
}

private class Attempt(val street: String?, val postcode: String?, val place: String?)

private fun nominatim(attempt: Attempt, countryCode: String): GeoHit? {
    // The service's own limit is one request a second.
    val wait = lastRequest + 1100 - System.currentTimeMillis()
    if (wait > 0) Thread.sleep(wait)
    lastRequest = System.currentTimeMillis()

    val params = buildMap {
        attempt.street?.let { put("street", it) }
        attempt.postcode?.let { put("postalcode", it) }
        attempt.place?.let { put("city", it) }
        put("countrycodes", countryCode)
        put("format", "jsonv2")
        put("limit", "1")
    }
    val target = NOMINATIM + "?" + params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    val connection = (URI(target).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "openrndr-wn-sandbox")
    }
    if (connection.responseCode !in 200..299) error("${connection.responseCode} from $target")
    val body = connection.inputStream.bufferedReader().use { it.readText() }
    val first = geocodeParser.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject ?: return null
    return GeoHit(
        Vector2(first["lon"]!!.jsonPrimitive.double, first["lat"]!!.jsonPrimitive.double),
        first["display_name"]?.jsonPrimitive?.content.orEmpty(),
        first["addresstype"]?.jsonPrimitive?.content ?: first["type"]?.jsonPrimitive?.content.orEmpty()
    )
}

private val cacheFile = File("data/collected/geocode/addresses.json")
private var cached: MutableMap<String, GeoHit?>? = null
private var lastRequest = 0L

private fun geocodeCache(): MutableMap<String, GeoHit?> = cached ?: run {
    val read = mutableMapOf<String, GeoHit?>()
    if (cacheFile.isFile) {
        geocodeParser.parseToJsonElement(cacheFile.readText()).jsonObject.forEach { (key, value) ->
            read[key] = (value as? JsonObject)?.let {
                GeoHit(
                    Vector2(it["lon"]!!.jsonPrimitive.double, it["lat"]!!.jsonPrimitive.double),
                    it["matched"]?.jsonPrimitive?.content.orEmpty(),
                    it["precision"]?.jsonPrimitive?.content.orEmpty()
                )
            }
        }
    }
    read.also { cached = it }
}

private fun writeGeocodeCache(cache: Map<String, GeoHit?>) {
    cacheFile.parentFile.mkdirs()
    cacheFile.writeText(buildJsonObject {
        cache.forEach { (key, hit) ->
            if (hit == null) put(key, JsonNull)
            else put(key, buildJsonObject {
                put("lon", hit.lonLat.x)
                put("lat", hit.lonLat.y)
                put("precision", hit.precision)
                put("matched", hit.matched)
            })
        }
    }.toString())
}

private val geocodeParser = Json { ignoreUnknownKeys = true }

private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"

private val ARTICLES = setOf("de", "het", "den", "der", "la", "le", "les")

/** The country names the address lists are written with, to the codes the service filters on. */
private val COUNTRY_CODES = mapOf(
    "belgië" to "be", "belgie" to "be", "belgium" to "be",
    "nederland" to "nl", "netherlands" to "nl",
    "frankrijk" to "fr", "france" to "fr",
    "duitsland" to "de", "germany" to "de",
    "luxemburg" to "lu", "luxembourg" to "lu",
    "denemarken" to "dk", "denmark" to "dk",
    "zweden" to "se", "sweden" to "se"
)
