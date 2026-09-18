package slideshow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * What the show is told about itself: notes written against a slide while watching it, each
 * ticked off once it has been acted on, and a new one written under it.
 *
 * **It is a list a slide, not a field.** An intent ([Intents]) is one paragraph that is rewritten
 * as the plan changes; feedback accumulates — a note is made, it is addressed, it is ticked, and
 * the next note goes below it — so what a slide has been asked for and what was done about it
 * stays readable as a history rather than being overwritten. That is why a note keeps its date
 * and why a done note is kept rather than deleted.
 *
 * **It is saved the moment it is written**, unlike the order and the modules: a note made while
 * a wall is on screen should not wait on a save button, and nothing downstream depends on it —
 * the deck never reads it, so `PUT /api/feedback` queues no command and changes nothing on the
 * wall.
 *
 * The file is `{ "feedback": { "<slide id>": [ { "text": …, "done": …, "at": … } ] } }`, which
 * is as easy to read in an editor as in the organizer; `note` is the file's own header and is
 * kept across a save.
 */
class Feedback(
    val items: Map<String, List<Note>> = emptyMap(),
    val note: String? = null
) {
    /** One note: what was said, whether it has been acted on, and the day it was written. */
    class Note(val text: String, val done: Boolean = false, val at: String = "")

    operator fun get(id: String): List<Note> = items[id].orEmpty()

    /** Notes not yet ticked off, over the whole show. */
    val open: Int get() = items.values.sumOf { list -> list.count { !it.done } }
    val total: Int get() = items.values.sumOf { it.size }

    /** Every slide with something still open, in the order the file has them. */
    val openBySlide: List<Pair<String, Int>>
        get() = items.mapNotNull { (id, list) -> list.count { !it.done }.takeIf { it > 0 }?.let { id to it } }

    fun toJson(): JsonObject = buildJsonObject {
        note?.let { put("note", it) }
        put("feedback", buildJsonObject {
            items.forEach { (id, list) ->
                val kept = list.filter { it.text.isNotBlank() }
                if (kept.isNotEmpty()) put(id, JsonArray(kept.map { n ->
                    buildJsonObject {
                        put("text", n.text.trim())
                        put("done", n.done)
                        if (n.at.isNotBlank()) put("at", n.at)
                    }
                }))
            }
        })
    }

    fun write(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(PRETTY.encodeToString(JsonObject.serializer(), toJson()) + "\n")
    }

    companion object {
        private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }

        val EMPTY = Feedback()

        fun read(file: File): Feedback = parse(file.readText())

        fun parse(text: String): Feedback {
            val root = Json.parseToJsonElement(text).jsonObject
            val body = root["feedback"]?.jsonObject ?: JsonObject(emptyMap())
            return Feedback(
                items = body.mapValues { (_, value) ->
                    value.jsonArray.mapNotNull { element ->
                        // A note may be written as a bare string in the file, for a quick one by hand.
                        val o = element as? JsonObject
                        val body2 = o?.get("text")?.jsonPrimitive?.contentOrNull
                            ?: (element as? JsonPrimitive)?.contentOrNull
                        body2?.takeIf { it.isNotBlank() }?.let {
                            Note(
                                text = it.trim(),
                                done = o?.get("done")?.jsonPrimitive?.booleanOrNull ?: false,
                                at = o?.get("at")?.jsonPrimitive?.contentOrNull.orEmpty()
                            )
                        }
                    }
                }.filterValues { it.isNotEmpty() },
                note = root["note"]?.jsonPrimitive?.contentOrNull
            )
        }
    }
}
