package slideshow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.pow

/**
 * The gain each sound-design cue was given in the audio timeline review, per state, in dB against
 * the file as delivered — `show-gains.json`, written by the review site (its export, or
 * `pnpm pull-gains` in `review/`) and read here, so the next film is mixed the way the review asked.
 *
 * **Keyed by state, not by file.** The review keeps a status, a thread and a gain on a *slot* — the
 * state a cue is fired on — and so does this: a re-cut file keeps the gain its state was given, and
 * a stem heard on several states, like the highlights', can sit at a different level on each.
 *
 * **Applied where a cue is fired**, in [present]'s `cueAt`, which is the one place that knows both
 * the sound and the state it is sounding for. So it reaches the sheet's cues, the slides' own and
 * their click marks alike, the speakers play it, and the cue log records the gain as played — which
 * is what the next release's audio timeline then reads back as the gain that film was made with.
 */
class ReviewGains(val design: Map<String, Double>) {

    /** [sound] as the review wants it heard on [state] (`<slide-id>-<LETTER>`). Only sound design is reviewed. */
    fun at(sound: Sound?, state: String): Sound? {
        if (sound == null || sound.layer != Layer.DESIGN) return sound
        val db = design[state] ?: return sound
        return if (db == 0.0) sound else sound.copy(gain = sound.gain * 10.0.pow(db / 20.0))
    }

    companion object {
        /** The file, or null where there is none; a file that will not read is said so and played without. */
        fun read(file: File): ReviewGains? {
            if (!file.isFile) return null
            return runCatching {
                val root = Json.parseToJsonElement(file.readText()) as JsonObject
                val design = (root["design"] as? JsonObject).orEmpty().mapNotNull { (state, entry) ->
                    val db = (entry as? JsonObject)?.get("db")?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    state to db
                }.toMap()
                val changed = design.count { it.value != 0.0 }
                println("gains: ${file.path} — $changed of ${design.size} reviewed sound design states off 0 dB")
                ReviewGains(design)
            }.getOrElse {
                println("gains: ${file.path} would not read (${it.message}) — played as delivered")
                null
            }
        }
    }
}
