package slideshow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Every sketch in the project, for the organizer's Sketches tab: read off the source tree rather
 * than listed by hand, so a new sketch appears the moment its file has a `main`.
 *
 * A sketch is any `.kt` file under `src/main/kotlin` with a top-level `fun main(`. What the tab
 * shows is all read out of the file: its name, the class Gradle runs it by (the file's name plus
 * `Kt`, or the package's), the first paragraph of the KDoc above `main` as its summary, the `.env`
 * prefix its keys share, and whether it draws — a sketch that calls `sketchPreview` has a picture
 * (see `SketchPreview.kt`), as does a course wall run through `runCourse`; one that does neither is a
 * tool, and the tab says so.
 *
 * **A sketch can carry variants**, declared in its own file one a line above its KDoc:
 *
 *     // sketch-default: frame
 *     // sketch-variant: door panel | | GRID_PIECE=WAND_27 | the grid of the panel with a doorway
 *     // sketch-variant: v2, on the mosaic grid | CourseFlow2Kt | | the same loop on the mosaic's grid
 *
 * — a name, the main class when it is another file's (empty for this one's), the `.env` values that
 * make it, and a line on what it is; `sketch-default` names the sketch's own run. A file that is
 * only another sketch's variant says `// sketch-variant-of: CourseFlow` and gets no card of its own.
 * A variant's picture is `sketch-previews/<Sketch>--<variant>.png`, the variant's name made a slug,
 * which is what `tools/sketch_previews.sh` writes.
 */
object Sketches {
    private val root = File("src/main/kotlin")

    fun json(): JsonObject = buildJsonObject { put("sketches", list()) }

    private fun list(): JsonElement = JsonArray(scan().map { s ->
        buildJsonObject {
            put("name", s.name)
            put("file", s.file)
            put("mainClass", s.mainClass)
            put("command", "./gradlew run -Popenrndr.application=${s.mainClass}")
            put("summary", s.summary)
            s.prefix?.let { put("env", it) }
            put("tool", !s.draws)
            putPreview(s.name)
            s.default?.let { put("defaultName", it) }
            if (s.variants.isNotEmpty()) put("variants", JsonArray(s.variants.map { v ->
                buildJsonObject {
                    put("name", v.name)
                    put("mainClass", v.mainClass ?: s.mainClass)
                    put("command", listOfNotNull(v.env.ifBlank { null }, "./gradlew run -Popenrndr.application=${v.mainClass ?: s.mainClass}").joinToString(" "))
                    put("summary", v.summary)
                    putPreview("${s.name}--${slug(v.name)}")
                }
            }))
        }
    })

    /** A picture's address, when and how wide it was made, if it has been. */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putPreview(name: String) {
        val preview = File(PREVIEWS, "$name.png")
        if (!preview.isFile) return
        put("preview", "/sketch-previews/$name.png")
        put("previewTime", preview.lastModified())
        aspectOf(preview)?.let { put("previewAspect", it) }
    }

    /** A png's width over its height, off its header: the IHDR's two big-endian ints at byte 16. */
    private fun aspectOf(png: File): Double? = runCatching {
        val b = ByteArray(24)
        png.inputStream().use { it.read(b) }
        fun int(at: Int) = ((b[at].toInt() and 255) shl 24) or ((b[at + 1].toInt() and 255) shl 16) or
            ((b[at + 2].toInt() and 255) shl 8) or (b[at + 3].toInt() and 255)
        int(16).toDouble() / int(20)
    }.getOrNull()

    /** A variant's name as it goes into a file name: lower case, anything else a single dash. */
    fun slug(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** A thumbnail, by name — only a plain file name, never a path. */
    fun preview(name: String): File? =
        if (NAME.matches(name)) File(PREVIEWS, name).takeIf { it.isFile } else null

    /** One variant of a sketch: its name, the main class when it is another file's, its `.env` values and a line on it. */
    data class Variant(val name: String, val mainClass: String?, val env: String, val summary: String) {
        /** The values as a map, for running it in this process rather than on the command line. */
        val values: Map<String, String> get() = env.split(Regex("\\s+")).filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val slug: String get() = slug(name)
    }

    /**
     * The variants a sketch's file declares, read off the source — so the show can stand every one of
     * them as a wall and the tab and the show cannot disagree about what they are. Empty when the
     * source is not there to read.
     */
    fun variantsOf(sketch: String): List<Variant> = sourceOf(sketch)?.let { variants(it.readText()) }.orEmpty()

    /** What a sketch's own run is called, from its `sketch-default` line. */
    fun defaultOf(sketch: String): String? = sourceOf(sketch)?.let { default(it.readText()) }

    private fun sourceOf(sketch: String): File? =
        root.walkTopDown().firstOrNull { it.isFile && it.name == "$sketch.kt" }

    private fun default(text: String) = Regex("^// sketch-default: *(.+)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()

    private fun variants(text: String) = Regex("^// sketch-variant: *(.+)$", RegexOption.MULTILINE).findAll(text).mapNotNull { m ->
        val parts = m.groupValues[1].split("|").map { it.trim() }
        if (parts.size < 4 || parts[0].isEmpty()) null
        else Variant(parts[0], parts[1].ifEmpty { null }, parts[2], parts.drop(3).joinToString(" | "))
    }.toList()

    private data class Sketch(
        val name: String, val file: String, val mainClass: String,
        val summary: String, val prefix: String?, val draws: Boolean,
        val default: String?, val variants: List<Variant>
    )

    private fun scan(): List<Sketch> = root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .mapNotNull { f ->
            val text = f.readText()
            val main = Regex("^fun main\\(", RegexOption.MULTILINE).find(text) ?: return@mapNotNull null
            if (Regex("^// sketch-variant-of:", RegexOption.MULTILINE).containsMatchIn(text)) return@mapNotNull null
            val pkg = Regex("^package ([\\w.]+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
            val cls = f.nameWithoutExtension + "Kt"
            Sketch(
                name = f.nameWithoutExtension,
                file = f.relativeTo(File(".")).path,
                mainClass = if (pkg != null) "$pkg.$cls" else cls,
                summary = summaryBefore(text, main.range.first),
                prefix = Regex("Env\\[\"([A-Z0-9]+)_").find(text)?.groupValues?.get(1)?.let { "${it}_" },
                draws = Regex("^\\s*sketchPreview\\(\"", RegexOption.MULTILINE).containsMatchIn(text) ||
                    Regex("^fun main\\(\\) = runCourse\\(", RegexOption.MULTILINE).containsMatchIn(text) || f.nameWithoutExtension == "Slideshow",
                default = default(text),
                variants = variants(text)
            )
        }
        .sortedBy { it.name.lowercase() }
        .toList()

    /** The first paragraph of the KDoc that ends just before [at], as plain text. */
    private fun summaryBefore(text: String, at: Int): String {
        val end = text.lastIndexOf("*/", at)
        if (end < 0) return ""
        // Only annotations may stand between the comment and `main`, or it is not main's comment.
        val between = text.substring(end + 2, at).lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (between.any { !it.startsWith("@") }) return ""
        val start = text.lastIndexOf("/**", end)
        if (start < 0) return ""
        val lines = text.substring(start + 3, end).lines().map { it.trim().removePrefix("*").trim() }
        val paragraph = lines.dropWhile { it.isEmpty() }.takeWhile { it.isNotEmpty() && !it.startsWith("```") }
        return paragraph.joinToString(" ").replace("**", "").replace("`", "").trim()
    }

    private val PREVIEWS = File("sketch-previews")
    private val NAME = Regex("[A-Za-z0-9_-]+\\.png")
}

/**
 * A wall the show stands for one of the Sketches tab's sketches — or one of its variants — so the
 * organizer can show the sketch's own picture beside it and offer it in a moment's picker. [sketch]
 * is the picture's name in `sketch-previews/`: `CourseGrid`, or `CourseGrid--door-panel`.
 */
interface SketchWall {
    val sketch: String
    /** What this one is called on the sketch's card: its variant's name, or the sketch's own run's. */
    val variantName: String?
}
