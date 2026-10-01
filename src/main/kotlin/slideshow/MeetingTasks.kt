package slideshow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * What a meeting asked of each slide: the transcript of a walkthrough read as tasks, one list a
 * slide, each task agreed, adjusted or dropped in the organizer's meeting section, and new ones
 * added under them.
 *
 * **It is not the feedback.** A feedback note is written while a wall is watched and ticked once it
 * has been acted on; a meeting task is somebody else's reading of what was said, which has to be
 * agreed before anyone builds it — so a task carries a status (`open`, `approved`, `dropped`), the
 * wording it was first read as when it has been adjusted, and the moment in the recording it came
 * from. Kept apart, the feedback stays the user's own notes and the meeting can be gone through
 * as one decision after another.
 *
 * **The page owns the shape.** The server keeps whatever the page sends, as long as it is an
 * object with `slides` an object, so a field added on the page needs nothing here. The file:
 *
 *     { "note": …, "meetings": { "<meeting id>": { "title": …, "transcript": … } },
 *       "slides": { "<slide id>": { "said": [ { "text": …, "at": … } ],
 *                                   "tasks": [ { "id": …, "kind": …, "text": …, "status": … } ] } } }
 *
 * Like the feedback it is saved the moment something is decided and the deck never reads it.
 */
class MeetingTasks(val root: JsonObject = JsonObject(emptyMap())) {
    private val slides: Map<String, JsonObject>
        get() = (root["slides"] as? JsonObject).orEmpty().mapNotNull { (id, v) -> (v as? JsonObject)?.let { id to it } }.toMap()

    private fun tasksOf(slide: JsonObject): List<JsonObject> =
        (slide["tasks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun statusOf(task: JsonObject): String =
        (task["status"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }) ?: "open"

    /** Tasks not yet agreed or dropped, over the whole show. */
    val open: Int get() = slides.values.sumOf { s -> tasksOf(s).count { statusOf(it) == "open" } }
    val total: Int get() = slides.values.sumOf { tasksOf(it).size }

    fun toJson(): JsonObject = root

    /** This, with whatever [next] leaves out kept as it was — the header and the meetings are the file's. */
    fun mergedWith(next: MeetingTasks): MeetingTasks =
        MeetingTasks(JsonObject(root.filterKeys { it !in next.root } + next.root))

    fun write(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(PRETTY.encodeToString(JsonObject.serializer(), root) + "\n")
    }

    companion object {
        private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }

        val EMPTY = MeetingTasks()

        fun read(file: File): MeetingTasks = parse(file.readText())

        fun readOrEmpty(file: File): MeetingTasks =
            if (file.isFile) runCatching { read(file) }.getOrDefault(EMPTY) else EMPTY

        fun parse(text: String): MeetingTasks {
            val root = Json.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalArgumentException("the meeting tasks are not an object")
            require(root["slides"] == null || root["slides"] is JsonObject) { "slides is not an object" }
            return MeetingTasks(root)
        }
    }
}
