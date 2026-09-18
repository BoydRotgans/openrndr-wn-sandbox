package slideshow

import java.io.File
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage
import kotlin.math.roundToLong

/**
 * Notes in and a `.mid` file out — enough to write the timing of a wall down as something a
 * DAW can open, and nothing more.
 *
 * **The JDK is the encoder.** `javax.sound.midi` writes standard MIDI files, the same way
 * `javax.sound.sampled` decodes the cue sheet's wavs for [Speakers], so no library was added
 * for this. It is also why nothing here plays: a [Sequencer] is a device with a clock, and
 * what is wanted is a file.
 *
 * **Times are seconds, not beats**, and stay exact whatever [bpm] is set to. A wall's build is
 * a frame count at [FPS] and answers to nothing musical, so quantising it to a grid would be
 * inventing timing rather than exporting it; the tempo only decides the bars a DAW rules over
 * the notes once they are in. Ticks land on the nearest of [ppq] per quarter — at 480 and
 * 120 bpm that is a millisecond, which is under a frame.
 */
data class Note(
    /** Seconds from the start of the file. */
    val at: Double,
    /** Seconds it is held. */
    val length: Double,
    val pitch: Int,
    val velocity: Int = 100,
    val channel: Int = 0
)

/** One lane of the file: a name a DAW shows on the track, and what is on it. */
data class MidiTrack(val name: String, val notes: List<Note>)

/**
 * The channels a melodic lane may take: all sixteen **but the tenth**, which General MIDI
 * reserves for percussion — a lane that lands there is a drum kit on any GM device whatever
 * it was meant to be, which is the sort of thing that is heard rather than read.
 *
 * Fifteen of them. A host that assigns its own instrument per track never notices; one that
 * routes by channel does, and that is who this is for.
 */
val melodicChannels: List<Int> = (0..15).filter { it != 9 }

/**
 * Writes [tracks] to [file] as a standard MIDI file, and returns it.
 *
 * Track 0 carries the tempo alone and every lane follows it, which is the type 1 convention —
 * a DAW reads the tempo map off the first track and shows the rest as named lanes. It is
 * always type 1, one lane or fifteen: type 0 is a single track by definition, so a tempo map
 * of its own is not available there.
 */
fun writeMidi(file: File, tracks: List<MidiTrack>, bpm: Double = 120.0, ppq: Int = 480): File {
    val sequence = Sequence(Sequence.PPQ, ppq)
    val ticksPerSecond = ppq * bpm / 60.0
    fun tick(seconds: Double) = (seconds * ticksPerSecond).roundToLong().coerceAtLeast(0L)

    val tempo = sequence.createTrack()
    // Microseconds a quarter note, big-endian in three bytes — the FF 51 03 meta event.
    val us = (60_000_000.0 / bpm).roundToLong()
    tempo.add(
        MidiEvent(
            MetaMessage(0x51, byteArrayOf((us shr 16).toByte(), (us shr 8).toByte(), us.toByte()), 3), 0L
        )
    )

    for (lane in tracks) {
        val track = sequence.createTrack()
        // Latin-1 rather than ASCII: a track name is bytes to a host, and encoding it as
        // ASCII turns anything outside it into a literal '?' in the file rather than failing.
        val name = lane.name.toByteArray(Charsets.ISO_8859_1)
        track.add(MidiEvent(MetaMessage(0x03, name, name.size), 0L))
        for (note in shortenOverlaps(lane.notes)) {
            val on = tick(note.at)
            // At least one tick, so a note that is written and released on the same tick is
            // still a note rather than a pair of events some hosts drop.
            val off = maxOf(on + 1, tick(note.at + note.length))
            val pitch = note.pitch.coerceIn(0, 127)
            val channel = note.channel.coerceIn(0, 15)
            track.add(MidiEvent(ShortMessage(ShortMessage.NOTE_ON, channel, pitch, note.velocity.coerceIn(1, 127)), on))
            track.add(MidiEvent(ShortMessage(ShortMessage.NOTE_OFF, channel, pitch, 0), off))
        }
    }

    file.absoluteFile.parentFile?.mkdirs()
    
    MidiSystem.write(sequence, 1, file)
    return file
}

/**
 * The same notes, with any that would overlap a note already sounding at its own pitch on its own
 * channel cut short to end where the next begins.
 *
 * **Two notes of one pitch on one channel are not two notes**: there is only note-on and note-off,
 * so the first off silences both and what a host plays is neither what was written nor an error it
 * can report. A caller whose lanes crowd several things onto a pitch — a field of several hundred
 * arrivals banded into two octaves — would otherwise get a file that looks right in a piano roll
 * and plays wrong. Nothing is dropped and nothing moves; only a tail is taken off.
 */
private fun shortenOverlaps(notes: List<Note>): List<Note> {
    val sorted = notes.sortedWith(compareBy({ it.channel }, { it.pitch }, { it.at }))
    val out = ArrayList<Note>(sorted.size)
    for (note in sorted) {
        val last = out.lastOrNull()
        if (last != null && last.channel == note.channel && last.pitch == note.pitch &&
            last.at + last.length > note.at
        ) out[out.size - 1] = last.copy(length = (note.at - last.at).coerceAtLeast(0.0))
        out += note
    }
    return out
}
