// ============================================================================ //
//  No `package` declaration: it reads .env through Env, which is in the default
//  package, the same reason Slideshow.kt has none.
// ============================================================================ //

import slideshow.Soundtrack
import java.io.File

/**
 * Scores a film that was already made: renders the soundtrack again from the cue log beside
 * it and mixes the two.
 *
 *     ./gradlew run -Popenrndr.application=MixSoundtrackKt
 *
 * `SLIDES_RECORD=true` does all of this by itself as the run ends, so this is for the other
 * cases: a level changed in `Slideshow.kt` and the film should not have to be made again, or
 * ffmpeg was not on the path when it was. It needs the `.cues` log the filmed run wrote beside
 * the film, and nothing else — no window, no device, no deck.
 *
 * **A second mix is not a second shoot.** `SLIDES_MIX_LAYERS` overrides a layer's level for this
 * render alone — `voice=0` is the same film with the voice-over left out — and `SLIDES_MIX_NAME`
 * puts it beside the film as `<film>-<name>.mp4` rather than over it:
 *
 *     SLIDES_VIDEO=video/wn-walkthrough-v2.mp4 SLIDES_MIX_LAYERS=voice=0 SLIDES_MIX_NAME=novoice \
 *         ./gradlew run -Popenrndr.application=MixSoundtrackKt
 */
fun main() {
    val video = File(Env["SLIDES_VIDEO"]?.takeIf { it.isNotBlank() } ?: "video/presentation.mp4")
    val mix = Env["SLIDES_MIX"]?.let { Env.boolean("SLIDES_MIX") } ?: true
    // `voice=0,music=0.5`: a layer a term, as the log names them.
    val over = (Env["SLIDES_MIX_LAYERS"] ?: "").split(',').mapNotNull { term ->
        val (key, gain) = term.split('=').let { it.getOrNull(0)?.trim() to it.getOrNull(1)?.trim()?.toDoubleOrNull() }
        slideshow.Layer.of(key)?.let { layer -> gain?.let { layer to it } }
    }.toMap()
    Soundtrack.remix(video, mix, over, Env["SLIDES_MIX_NAME"]?.takeIf { it.isNotBlank() })
}
