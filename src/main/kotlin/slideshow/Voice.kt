package slideshow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.sound.sampled.AudioSystem

/**
 * The voice: a subtitle track rendered as speech, one wav per state, played on the wall under the
 * cards and mixed into a filmed run — see `tools/voiceover_render.py`, which writes it.
 *
 * `data/sounds/voice/<track>/<slide-id>-<LETTER>.wav` is the layout: the state named the way the
 * cue sheet, the nameplate and the organizer name it, so a rendered line lands on the state whose
 * subtitle it is by its name alone. `manifest.json` beside the files carries each one's length,
 * which is what the show holds a state for: where a voice exists, the state stands until the
 * voice has been said and a beat after, in place of the characters-a-second estimate.
 *
 * **It is a layer of its own, not a cue sheet.** A sheet under `SLIDES_CUE_SHEETS` owns a slide
 * and would silence the sound design on it; the voice plays beside the design, through the same
 * [Speakers], as a sustained cue with a short fade so a click away cuts it cleanly rather than
 * letting a sentence run on under the next state. Being played through the speakers is also
 * what puts it in the cue log, and so in the soundtrack a filmed run renders.
 *
 * Missing folder, missing manifest, missing file: each is a state with no voice, and the text
 * rule stands for it — so a half-rendered track runs, and a checkout without `data/` runs.
 */
class VoiceTrack private constructor(
    val dir: File,
    private val sounds: Map<Pair<String, Int>, Sound>,
    private val lengths: Map<Pair<String, Int>, Int>,
    /** Every word of a state's line as it was really said — see [Speech] and [Pace.cards]. */
    private val speeches: Map<Pair<String, Int>, Speech> = emptyMap(),
    /** States (`id-LETTER`) whose best render still failed the renderer's read-back check. */
    val flagged: Set<String> = emptySet()
) {
    /** The voice for [step] of [id], or null where none was rendered. */
    fun sound(id: String, step: Int): Sound? = sounds[id to step]

    /** Frames the voice for [step] of [id] runs, or null where none was rendered. */
    fun frames(id: String, step: Int): Int? = lengths[id to step]

    /**
     * The words of [step] of [id] with the frames each was said on, or null where the render
     * predates the word times (the manifest's `words`) — then the text rule stands for it.
     */
    fun speech(id: String, step: Int): Speech? = speeches[id to step]

    val all: List<Sound> get() = sounds.values.toList()
    val size: Int get() = sounds.size

    /** Seconds per state key (`id-LETTER`), for the organizer. */
    fun seconds(): Map<String, Double> = lengths.entries.associate { (k, v) -> "${k.first}-${letter(k.second)}" to seconds(v) }

    companion object {
        /** Frames the voice is left to fade out over when its state is left mid-sentence. */
        val CUT: Int get() = frames(0.15)

        /**
         * The track's folder under [root], or null where there is none. Lengths come off the
         * manifest, and off the file itself for a wav the manifest does not know.
         */
        fun read(root: String?, track: SubtitleTrack, gain: Double = 1.0): VoiceTrack? {
            val dir = File(root ?: return null, track.key)
            val files = dir.listFiles { f -> f.isFile && f.extension.equals("wav", true) }?.toList() ?: return null
            if (files.isEmpty()) return null
            val entries = runCatching {
                Json.parseToJsonElement(File(dir, "manifest.json").readText()).jsonObject
            }.getOrNull()
            val manifest = entries?.mapValues { (_, v) -> v.jsonObject["seconds"]?.jsonPrimitive?.doubleOrNull ?: 0.0 }.orEmpty()
            val spoken = entries?.mapValues { (_, v) -> Speech.of(v.jsonObject["words"]) }.orEmpty()
            val flagged = entries?.filterValues { v -> v.jsonObject["check"]?.jsonPrimitive?.booleanOrNull == true }?.keys.orEmpty()
            val sounds = mutableMapOf<Pair<String, Int>, Sound>()
            val lengths = mutableMapOf<Pair<String, Int>, Int>()
            val speeches = mutableMapOf<Pair<String, Int>, Speech>()
            for (file in files) {
                val key = file.nameWithoutExtension
                val cut = key.lastIndexOf('-').takeIf { it > 0 } ?: continue
                val step = Subtitles.stepOf(key.substring(cut + 1)) ?: continue
                val id = key.substring(0, cut)
                val seconds = manifest[key] ?: wavSeconds(file) ?: continue
                sounds[id to step] = Sound(file, gain = gain, fadeOut = CUT, layer = Layer.VOICE)
                lengths[id to step] = frames(seconds)
                spoken[key]?.let { speeches[id to step] = it }
            }
            return VoiceTrack(dir, sounds, lengths, speeches, flagged)
        }

        private fun wavSeconds(file: File): Double? = runCatching {
            val format = AudioSystem.getAudioFileFormat(file)
            format.frameLength.toDouble() / format.format.frameRate
        }.getOrNull()
    }
}
