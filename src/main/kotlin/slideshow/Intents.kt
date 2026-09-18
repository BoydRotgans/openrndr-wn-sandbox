package slideshow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * The intended update per slide: what a refactor means to make of it, kept beside the show and
 * shown in the organizer under the speaker notes.
 *
 * **It is deliberately not `notes`.** A slide's notes are what is *said over it* — the spoken
 * script, in Dutch, which the narrative plan wants written there so it travels with the slide it
 * belongs to. An intent is what the slide should *become*: which of the three treatments it is,
 * which principles it adopts, what changes. Keeping the two apart means neither has to be edited
 * around the other, and an intent that has been carried out can be cleared without touching a word
 * of what the speaker says.
 *
 * **It is a file rather than Kotlin, for the reason the order is.** An intent carries no behaviour,
 * only a paragraph a person reads, so nothing is lost by keeping it out of the show — and a great
 * deal is gained, because it can then be edited in the organizer beside the slide it describes and
 * saved without a rebuild. A slide the show does not declare cannot be conjured by naming it here;
 * an id nothing answers to is simply carried along and shown to nobody.
 *
 * The value may be written as one string or as a list of paragraphs, the way a module's brief is,
 * so a long intent stays readable when the file is edited by hand. `note` is the file's own header
 * and is preserved across a save.
 */
class Intents(
    val intents: Map<String, String> = emptyMap(),
    val note: String? = null
) {
    /** The intent for [id], or empty where there is none. */
    operator fun get(id: String): String = intents[id].orEmpty()

    val size: Int get() = intents.count { it.value.isNotBlank() }

    fun toJson(): JsonObject = buildJsonObject {
        note?.let { put("note", it) }
        put("intents", buildJsonObject {
            intents.forEach { (id, text) ->
                if (text.isNotBlank()) put(id, JsonPrimitive(text.trim()))
            }
        })
    }

    fun write(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(PRETTY.encodeToString(JsonObject.serializer(), toJson()) + "\n")
    }

    companion object {
        private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }

        val EMPTY = Intents()

        fun read(file: File): Intents = parse(file.readText())

        fun parse(text: String): Intents {
            val root = Json.parseToJsonElement(text).jsonObject
            val body = root["intents"]?.jsonObject ?: JsonObject(emptyMap())
            return Intents(
                intents = body.mapValues { (_, value) -> paragraph(value) }.filterValues { it.isNotBlank() },
                note = root["note"]?.jsonPrimitive?.contentOrNull
            )
        }

        /** One string, or a list of paragraphs joined by a blank line. */
        private fun paragraph(value: JsonElement): String = when (value) {
            is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }.joinToString("\n\n").trim()
            else -> value.jsonPrimitive.contentOrNull.orEmpty().trim()
        }
    }
}
