package slideshow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openrndr.color.ColorRGBa
import java.io.File

/**
 * The house red and the house blue as one setting for the whole project, kept in a file and moved
 * from the organizer (28 September: "an interface where I can set the blue colour in real time and
 * store it; do the same for the red ... see it as a global colour").
 *
 * **Every red and every blue in the project is one of these two.** [Palette.RED] and [Palette.BLUE]
 * are read from here, so are `wnRed` and `wnBlue` in `Slideshow.kt`, every drawer that used to state
 * `FF0000` or `023F88` as a default, and any `.env` value written `@red` or `@blue` (see `Env`).
 *
 * **Stored, and exact at every start.** `show-colours.json` (`SLIDES_COLOURS`) holds the two; a run
 * reads it the first time a colour is asked for and builds every slide in it, so a slide, a course
 * wall and a sketch all come up in the stored colours to the last bit. [redHex] and [blueHex] are
 * that pair, and do not change while the run lasts — a drawer bakes its colours into vertex buffers
 * at load, and nothing could change them under it.
 *
 * **Live, as a preview laid over the frame.** Moving a colour in the organizer sets [shownRedHex] or
 * [shownBlueHex], and the show maps the colours it was built in onto those in one pass over the
 * finished frame ([BrandPreview]) — at once, on every slide, however it drew its colours. The pass
 * is exact on flat colour and on its shades and edges against black, white or grey, which is nearly
 * the whole show; it can shift a photograph's pixel that happens to share the hue. The next start
 * builds in the saved colours and the pass is off again.
 */
object Brand {
    const val DEFAULT_RED = "FF0000"
    const val DEFAULT_BLUE = "023F88"

    private var path = "show-colours.json"
    private var read = false
    private var builtRed = DEFAULT_RED
    private var builtBlue = DEFAULT_BLUE

    /**
     * Keep the colours in [file] rather than `show-colours.json`. Has to come before the colours are
     * first read: `Slideshow.kt` calls it first thing, and `Env` before it expands a token.
     */
    @Synchronized
    fun use(file: String?) {
        val asked = file?.takeIf { it.isNotBlank() } ?: return
        if (asked == path) return
        if (read) { println("colours: asked for $asked after reading $path; this run keeps $path"); return }
        path = asked
    }

    /** The file the colours are kept in. */
    val file: File get() = File(path)

    @Synchronized
    private fun ensure() {
        if (read) return
        read = true
        readFile()?.let { (r, b) -> builtRed = r ?: DEFAULT_RED; builtBlue = b ?: DEFAULT_BLUE }
        if (builtRed != DEFAULT_RED || builtBlue != DEFAULT_BLUE) println("colours: red #$builtRed, blue #$builtBlue, off $path")
    }

    /** The red this run is built in, as six hex digits. */
    val redHex: String get() { ensure(); return builtRed }

    /** The blue this run is built in, as six hex digits. */
    val blueHex: String get() { ensure(); return builtBlue }

    val red: ColorRGBa get() = ColorRGBa.fromHex(redHex)
    val blue: ColorRGBa get() = ColorRGBa.fromHex(blueHex)

    /** The red the organizer is showing: null while it is the one the run was built in. */
    @Volatile var shownRedHex: String? = null
        private set

    /** The blue the organizer is showing: null while it is the one the run was built in. */
    @Volatile var shownBlueHex: String? = null
        private set

    /** Whether a colour is being shown other than the one the run was built in, so the preview has work. */
    val previewing: Boolean get() = (shownRedHex ?: redHex) != redHex || (shownBlueHex ?: blueHex) != blueHex

    /** Show [red] and [blue] from now on; either may be null to leave it. Anything not a colour is ignored. */
    fun show(red: String?, blue: String?) {
        normalise(red)?.let { shownRedHex = it }
        normalise(blue)?.let { shownBlueHex = it }
    }

    /**
     * Write [red] and [blue] into the file for the next start, keeping whichever is null as the file
     * has it — so a page that moved only the blue cannot put back a red another page just saved.
     */
    @Synchronized
    fun save(red: String?, blue: String?) {
        val (r0, b0) = readFile() ?: (null to null)
        val r = normalise(red) ?: r0 ?: redHex
        val b = normalise(blue) ?: b0 ?: blueHex
        file.writeText(
            """
            |{
            |  "about": "The house red and blue, six hex digits each. Every red and blue in the project is one of these: Palette.RED and Palette.BLUE, wnRed and wnBlue, the drawers' defaults, and any .env value written @red or @blue. Read once at the start of a run; the organizer's colours move them live and save them here. SLIDES_COLOURS names the file.",
            |  "red": "$r",
            |  "blue": "$b"
            |}
            |""".trimMargin()
        )
        savedCache = null
        println("colours: saved red #$r, blue #$b to $path — every slide takes them exactly at the next start")
    }

    /** What the file holds now, which after a save is ahead of what this run was built in. */
    fun saved(): Pair<String, String> {
        val stat = if (file.isFile) "${file.lastModified()}-${file.length()}" else "none"
        savedCache?.let { (at, pair) -> if (at == stat) return pair }
        val (r, b) = readFile() ?: (null to null)
        return ((r ?: redHex) to (b ?: blueHex)).also { savedCache = stat to it }
    }

    @Volatile private var savedCache: Pair<String, Pair<String, String>>? = null

    private fun readFile(): Pair<String?, String?>? {
        if (!file.isFile) return null
        return runCatching {
            val o = Json.parseToJsonElement(file.readText()).jsonObject
            normalise(o["red"]?.jsonPrimitive?.contentOrNull) to normalise(o["blue"]?.jsonPrimitive?.contentOrNull)
        }.getOrElse { println("colours: could not read $path (${it.message}); using the house colours"); null }
    }

    /** Six upper-case hex digits from `#abc`, `abc`, `#aabbcc` or `aabbcc`; null for anything else. */
    fun normalise(hex: String?): String? {
        val h = hex?.trim()?.removePrefix("#")?.uppercase() ?: return null
        return when {
            h.length == 6 && h.all { it in "0123456789ABCDEF" } -> h
            h.length == 3 && h.all { it in "0123456789ABCDEF" } -> h.map { "$it$it" }.joinToString("")
            else -> null
        }
    }

    /** [value] with every `@red` and `@blue` written as this run's hex digits — how `.env` follows the setting. */
    fun expand(value: String): String =
        if ('@' !in value) value
        else value.replace("@red", redHex, ignoreCase = true).replace("@blue", blueHex, ignoreCase = true)
}
