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
 * (see `SketchPreview.kt`); one that does not is a tool, and the tab says so.
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
            val preview = File(PREVIEWS, "${s.name}.png")
            if (preview.isFile) {
                put("preview", "/sketch-previews/${s.name}.png")
                put("previewTime", preview.lastModified())
            }
        }
    })

    /** A thumbnail, by name — only a plain file name, never a path. */
    fun preview(name: String): File? =
        if (NAME.matches(name)) File(PREVIEWS, name).takeIf { it.isFile } else null

    private data class Sketch(
        val name: String, val file: String, val mainClass: String,
        val summary: String, val prefix: String?, val draws: Boolean
    )

    private fun scan(): List<Sketch> = root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .mapNotNull { f ->
            val text = f.readText()
            val main = Regex("^fun main\\(", RegexOption.MULTILINE).find(text) ?: return@mapNotNull null
            val pkg = Regex("^package ([\\w.]+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
            val cls = f.nameWithoutExtension + "Kt"
            Sketch(
                name = f.nameWithoutExtension,
                file = f.relativeTo(File(".")).path,
                mainClass = if (pkg != null) "$pkg.$cls" else cls,
                summary = summaryBefore(text, main.range.first),
                prefix = Regex("Env\\[\"([A-Z0-9]+)_").find(text)?.groupValues?.get(1)?.let { "${it}_" },
                draws = Regex("^\\s*sketchPreview\\(\"", RegexOption.MULTILINE).containsMatchIn(text) || f.nameWithoutExtension == "Slideshow"
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
