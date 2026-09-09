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
 */
fun main() {
    val video = File(Env["SLIDES_VIDEO"]?.takeIf { it.isNotBlank() } ?: "video/presentation.mp4")
    val mix = Env["SLIDES_MIX"]?.let { Env.boolean("SLIDES_MIX") } ?: true
    Soundtrack.remix(video, mix)
}
