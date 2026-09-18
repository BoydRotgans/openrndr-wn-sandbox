// ============================================================================ //
//  No `package` declaration: it reads .env through Env and the show through
//  Slideshow.kt, both of which are in the default package.
// ============================================================================ //

import slideshow.backdrops.PlainScene
import slideshow.melodicChannels
import slideshow.midiClicks
import slideshow.midiTracksOf
import slideshow.seconds
import slideshow.writeMidi
import java.io.File

/**
 * Writes the Plain wall's build as a MIDI file: one note per element, on the frame that element
 * starts to arrive.
 *
 *     ./gradlew run -Popenrndr.application=PlainMidiKt
 *
 * **The schedule is the wall's own.** It is read off the [PlainScene] standing in the running
 * order — `PlainScene.reveals`, the very list the draw loop steps through — rather than worked
 * out again here, so the file and the picture cannot disagree. Nothing is sampled from a run
 * and no window is opened: the build is a pure function of the frame, so the timing can be
 * written down without drawing a single frame of it.
 *
 * **The piano roll is the chart.** Pitch is the row an element lands on — [PLAIN_MIDI_NOTE] for
 * the piece on the floor, a semitone a row up the stack — and there is a track per column, named
 * after the svg that column stands. So a bar rises as a run of semitones, the columns come in a
 * beat apart down the file, and what a DAW draws is the wall on its side.
 *
 * **A column is a channel as well as a track**, off [melodicChannels], so a host that routes by
 * channel separates the columns as readily as one that reads the track names — and none of them
 * lands on the GM percussion channel. Fifteen columns take the fifteen channels exactly; a
 * sixteenth would share with the first, which is the honest thing a file can do about a limit
 * that is sixteen wide.
 *
 * **Times are seconds from the frame the wall comes up**, exact rather than quantised: the build
 * is a frame count and answers to nothing musical, so the tempo only rules the bars a host draws
 * over it. `PLAIN_MIDI_AT` shifts the whole file along, for dropping it onto the timeline of a
 * film where the wall arrives some way in.
 */
fun main() {
    val scene = show.slides.filterIsInstance<PlainScene>().firstOrNull()
    if (scene == null) {
        println("plain midi: no PlainScene in the running order — nothing to write")
        return
    }

    val file = File(Env["PLAIN_MIDI"]?.takeIf { it.isNotBlank() } ?: "midi/plain.mid")
    val bpm = Env["PLAIN_MIDI_BPM"]?.toDoubleOrNull() ?: 120.0
    val base = Env["PLAIN_MIDI_NOTE"]?.toIntOrNull() ?: 48
    val offset = Env["PLAIN_MIDI_AT"]?.toDoubleOrNull() ?: 0.0

    // The mapping is `midiTracksOf`, which every MIDI export here goes through — the organizer's
    // button included — so a wall exported from the page and one exported from this main are the
    // same file. A column's name is the piece it stands, which the wall reads off its own folder.
    val tracks = midiTracksOf(scene, base, offset, midiClicks(scene, 0.0))

    writeMidi(file, tracks, bpm)

    val last = scene.reveals.maxOf { it.start + it.length }
    val channels = tracks.indices.map { melodicChannels[it % melodicChannels.size] + 1 }
    println(
        "plain midi: ${scene.reveals.size} notes on ${tracks.size} tracks, %.2fs to %.2fs, at %.0f bpm → %s"
            .format(offset, offset + seconds(last), bpm, file.path)
    )
    println("plain midi: a channel a column, ${channels.first()}–${channels.last()}, the GM drum channel left out")
}
