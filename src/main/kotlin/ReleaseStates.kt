//  No `package` declaration: it reads .env through Env and the show through Slideshow.kt,
//  both of which are in the default package.

import slideshow.Pace
import slideshow.drawers.Type
import slideshow.StateMark
import slideshow.StatesLog
import slideshow.SubtitleTrack
import slideshow.Subtitles
import slideshow.VoiceTrack
import slideshow.arrangedFromFile
import slideshow.autoCues
import slideshow.startIndex
import slideshow.untilIndex
import slideshow.withModules
import java.io.File

/**
 * Writes the state log ([StatesLog]) a filmed run would have left beside its film, without
 * filming: the auto cues are a pure function of the deck, so the frame every state starts on
 * can be worked out from the same settings the run was made with.
 *
 *     SLIDES_SUBTITLE_MODE=true SLIDES_VIDEO=video/wn-experience-subtitles-full.mp4 \
 *         ./gradlew run -Popenrndr.application=ReleaseStatesKt
 *
 * For a film made before `present` logged its states. It is only right where the deck, the
 * order, the subtitles and the holds are as they were when the film was made — the last line
 * it prints compares its own length with the film's, off `ffprobe` when there is one, which is
 * the check. A run filmed from now on writes the log itself and needs none of this.
 */
fun main() {
    Type.file = textFont
    val show = show.withEnv().withModules().arrangedFromFile()
    val settings = show.settings
    val slides = show.slides
    val ids = show.slideIds
    val start = startIndex(slides, settings.start)
    val until = untilIndex(slides, settings.until, start)
    // The track the film was made on: the extended track's lines are longer, so its holds are too.
    val subtitles = if (settings.subtitleMode || settings.voiceOn) Subtitles.readOrEmpty(
        if (settings.subtitleTrack == SubtitleTrack.EXTENDED) settings.subtitlesExtended ?: "show-subtitles-extended.json"
        else settings.subtitles ?: "show-subtitles.json"
    ) else null
    val voice = if (settings.voiceOn) VoiceTrack.read(settings.voice, settings.subtitleTrack, settings.voiceGain) else null
    val holds = autoCues(slides, ids, start, until, settings, subtitles, Pace(settings.subtitleCps), voice)

    val marks = mutableListOf<StateMark>()
    var frame = 0
    var k = 0
    for (s in start..until) for (step in 0 until slides[s].steps) {
        marks += StateMark(frame, s, step)
        frame += holds[k++]
    }
    val video = File(Env["RELEASE_VIDEO"]?.takeIf { it.isNotBlank() } ?: settings.video)
    StatesLog.write(marks, frame, show, StatesLog.file(video))

    val filmed = runCatching {
        ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", video.path)
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim().toDoubleOrNull()
    }.getOrNull()
    println(
        "states: %d states, %.1fs by the cues%s".format(
            marks.size, frame / 60.0,
            if (filmed != null) " against %.1fs of film at %s".format(filmed, video.path) else " (no film at ${video.path} to compare against)"
        )
    )
}
