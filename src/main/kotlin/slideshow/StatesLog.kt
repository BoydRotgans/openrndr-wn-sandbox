package slideshow

import java.io.File

/**
 * Which state of which slide the wall showed, frame by frame, written beside a filmed run.
 *
 * The cue log ([Soundtrack.writeLog]) says what was *heard* and when; this says what was *on
 * screen* — one line per state the deck reached, on the frame the click that landed there
 * started — which is what a review copy needs to be cut into its slides. `frames N fps 60` on
 * the first line, then a tab-separated line a state: frame, slide id, step, the nameplate's
 * letter, the slide's title in the running order, and the chapter or moment it plays in with
 * which of the two it is. The id and letter are the nameplate's own label, so a comment
 * written against `the-catalogue-city-B` in the review site lands on the state the order
 * file, the organizer and the feedback file call by that name.
 *
 * Two writers, one format. `present` records the deck's real moves while filming, so a
 * filmed run leaves the log beside its cues; [ReleaseStatesKt] derives the same list from the
 * auto cues without a window, for a film made before the log existed. `tools/release_build.py`
 * reads either.
 */
data class StateMark(val frame: Int, val index: Int, val step: Int)

object StatesLog {
    fun file(video: File) = File(video.parentFile, video.nameWithoutExtension + ".states")

    fun write(marks: List<StateMark>, frames: Int, show: Show, file: File) {
        val ids = show.slideIds
        file.parentFile?.mkdirs()
        file.printWriter().use { out ->
            out.println("frames $frames fps $FPS")
            for (mark in marks) {
                val slide = show.slides.getOrNull(mark.index) ?: continue
                val place = show.outline[mark.index]
                val title = place?.title ?: slide.name
                val (chapter, kind) = when {
                    place == null -> "" to ""
                    place.moment.isNotBlank() -> place.moment to "moment"
                    place.chapterTitle.isNotBlank() -> place.chapterTitle to "chapter"
                    else -> "" to ""
                }
                out.println(
                    listOf(
                        mark.frame, ids.getOrElse(mark.index) { slide.name }, mark.step, letter(mark.step),
                        title, chapter, kind, slide.kind
                    ).joinToString("\t") { it.toString().replace('\t', ' ') }
                )
            }
        }
        println("export: ${marks.size} states logged to ${file.path}")
    }
}
