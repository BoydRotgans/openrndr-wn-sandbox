package slideshow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Feedback on a subtitle line: notes written against one line of one track in the organizer's
 * Subtitles tab, to improve the line with later — each with the day, the line as it read when the
 * note was written, and a tick once it has been acted on.
 *
 * **It is not the slide's feedback.** A slide's feedback is about the wall; this is about the
 * words said over one state of it, and it is kept by track, since the default and the extended
 * line of a state are two different sentences. Nor is it the line: a note is never said, and
 * keeping it apart means a save of the subtitles never carries a note to the wall.
 *
 *     { "lines": { "<track>": { "<slide id>-<LETTER>": [ { "id", "text", "at", "line", "done"? } ] } } }
 *
 * **The page owns the shape**, as with [MeetingTasks]: the server keeps whatever it is sent as
 * long as `lines` is an object, under the same revision check. Saved the moment a note is
 * written or ticked; the deck never reads it.
 */
class SubtitleFeedback(val root: JsonObject = JsonObject(emptyMap())) {
    private val notes: List<JsonObject>
        get() = (root["lines"] as? JsonObject).orEmpty().values
            .flatMap { (it as? JsonObject).orEmpty().values }
            .flatMap { (it as? JsonArray).orEmpty() }
            .mapNotNull { it as? JsonObject }

    /** Notes not yet ticked, over both tracks. */
    val open: Int get() = notes.count { n -> n["done"]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } != true }
    val total: Int get() = notes.size

    fun toJson(): JsonObject = root

    /** This, with whatever [next] leaves out kept as it was. */
    fun mergedWith(next: SubtitleFeedback): SubtitleFeedback =
        SubtitleFeedback(JsonObject(root.filterKeys { it !in next.root } + next.root))

    fun write(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(PRETTY.encodeToString(JsonObject.serializer(), root) + "\n")
    }

    companion object {
        private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }

        val EMPTY = SubtitleFeedback()

        fun readOrEmpty(file: File): SubtitleFeedback =
            if (file.isFile) runCatching { parse(file.readText()) }.getOrDefault(EMPTY) else EMPTY

        fun parse(text: String): SubtitleFeedback {
            val root = Json.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalArgumentException("the subtitle feedback is not an object")
            require(root["lines"] == null || root["lines"] is JsonObject) { "lines is not an object" }
            return SubtitleFeedback(root)
        }
    }
}
