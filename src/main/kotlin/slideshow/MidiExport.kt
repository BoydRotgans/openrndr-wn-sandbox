package slideshow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 * One element arriving on a slide: the lane it lands in, how far up that lane, and when.
 *
 * It is the drawer's own schedule expressed in the least it takes to write a note down — a lane
 * becomes a track and a channel, [index] becomes a pitch, [start] and [length] become the time.
 * The drawer therefore says *when things arrive* and nothing about music, which is what keeps a
 * wall free of MIDI concerns it has no business knowing.
 */
data class Arrival(val lane: Int, val index: Int, val start: Int, val length: Int)

/**
 * A slide whose build can be written down as timing.
 *
 * **The schedule has to be the one the draw loop steps through**, not a second copy of the
 * arithmetic — that is the whole reason this is a property of the drawer rather than a table
 * kept beside it. It is also why nothing here needs a window: a build is a pure function of the
 * frame, so it can be written down without drawing a frame of it.
 */
interface MidiTimed {
    /** A name a lane, in order: what a host shows on the track. */
    val lanes: List<String>

    /**
     * Lay this slide out for the pane it composes for, before [arrivals] is asked for.
     *
     * A no-op where the schedule is the drawer's alone — the Plain wall's columns are its counts
     * and nothing else. A slide that *deals* its elements against the pane has no schedule until
     * it knows how big that pane is, and `Crowd` is one: its crowd, arrow and disc are grids over
     * the frame, so how many figures stand up on a click is not knowable until the frame is. It
     * is the fact `CityMapSlide` states for the same reason, asked for rather than assumed.
     */
    fun layOut(width: Int, height: Int) {}

    /**
     * Every element the slide stands, given the frame each of its clicks lands on.
     *
     * **A slide that builds on its own clock ignores [clicks]** — the Plain wall's columns rise
     * the moment the wall is up, whoever is watching — and one that builds on clicks cannot: its
     * timing is not a property of the drawer alone but of the pace the talk is given. So the pace
     * is handed in rather than guessed at, and [midiClicks] is the pace a filmed run really uses.
     */
    fun arrivals(clicks: List<Int> = emptyList()): List<Arrival>
}

/**
 * The frame each of [slide]'s clicks lands on when a run is filmed hands-off — its opening state
 * settles and is held [hold] seconds, then each click plays out and is held again.
 *
 * It is `autoCues` for one slide, and has to be: a MIDI file written against one pace and a clip
 * filmed at another agree at the first click and nowhere after it.
 */
fun midiClicks(slide: Slide, hold: Double): List<Int> {
    var at = slide.settle + frames(hold)
    return (1 until slide.steps).map { step ->
        val landed = at
        at += slide.stepLength(step) + frames(hold)
        landed
    }
}

/**
 * How many semitones a lane may rise through before its ordinals have to be squeezed.
 *
 * Three octaves, which is a run the ear still follows as a rise rather than as a scatter, and
 * comfortably inside the 79 a lane really has above the default base.
 */
const val PITCH_SPAN = 36

/**
 * The [i]-th of [n] things as a step up a lane: its own place where the list is short enough to
 * have one, and scaled into [span] where it is not.
 *
 * **A long list has to be squeezed somewhere**, and doing it here rather than in a drawer keeps
 * every wall's rise the same shape: the catalogue ring lands 115 pieces, which as 115 semitones
 * would run off the top of the keyboard. Several then share a pitch, and where two of them sound
 * at once `writeMidi` cuts the first short — see `shortenOverlaps`.
 */
fun pitchStep(i: Int, n: Int, span: Int = PITCH_SPAN): Int = if (n <= span) i else i * span / n

/**
 * How long a clip of [slide] has to run to carry its whole build, in frames: the pace a filmed
 * run gives it — every state settling and then held — or the last note and a hold after it,
 * whichever is longer.
 *
 * The two differ where a slide's build is not what its clicks are. The Plain wall stands its 89
 * elements up in 2.1s on a single state, so the filmed pace alone would cut the clip before the
 * score ends; the crowd's clicks are the build, so there the filmed pace is the longer.
 */
fun clipFrames(slide: Slide, hold: Double, clicks: List<Int>, lastNote: Int): Int {
    val tail = if (clicks.isEmpty()) slide.settle else slide.stepLength(slide.steps - 1)
    val filmed = (clicks.lastOrNull() ?: 0) + tail + frames(hold)
    return maxOf(filmed, lastNote + frames(hold))
}

/**
 * [slide]'s build as MIDI lanes: a track and a channel a lane, pitch [base] plus [Arrival.index],
 * and [offset] seconds added to everything so the file can be dropped onto a film's timeline
 * where the slide arrives. [clicks] is the frame each click lands on, for a slide whose build
 * waits on them.
 */
fun midiTracksOf(
    slide: MidiTimed, base: Int = 48, offset: Double = 0.0, clicks: List<Int> = emptyList()
): List<MidiTrack> {
    val byLane = slide.arrivals(clicks).groupBy { it.lane }
    return slide.lanes.indices.map { lane ->
        MidiTrack(
            name = slide.lanes[lane],
            notes = byLane[lane].orEmpty().map {
                Note(
                    at = offset + seconds(it.start),
                    length = seconds(it.length),
                    pitch = base + it.index,
                    channel = melodicChannels[lane % melodicChannels.size]
                )
            }
        )
    }
}

/**
 * Which slides are wanted as MIDI, as a set of ids — ticked in the organizer, kept beside the
 * show in `show-midi.json`.
 *
 * **It is a flag and not a schedule**, which is what lets it be a file at all: the argument under
 * *declaring the show* still stands — a json deck would need a registry — and this file conjures
 * nothing. It names slides the show already declares, and an id nothing answers to is carried
 * along and exports nothing. The timing itself is never in here; it is read off the drawer.
 *
 * Only a slide that is [MidiTimed] can be ticked, so the set is a subset of what the show can
 * actually write down; the organizer greys the rest.
 */
class MidiWanted(
    val ids: Set<String> = emptySet(),
    val note: String? = null
) {
    operator fun contains(id: String): Boolean = id in ids

    val size: Int get() = ids.size

    /** The same set with [id] ticked or cleared. */
    fun with(id: String, on: Boolean): MidiWanted =
        MidiWanted(if (on) ids + id else ids - id, note)

    fun toJson(): JsonObject = buildJsonObject {
        note?.let { put("note", it) }
        put("midi", JsonArray(ids.sorted().map { JsonPrimitive(it) }))
    }

    fun write(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(PRETTY.encodeToString(JsonObject.serializer(), toJson()) + "\n")
    }

    companion object {
        private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }

        val EMPTY = MidiWanted()

        fun read(file: File): MidiWanted = parse(file.readText())

        fun parse(text: String): MidiWanted {
            val root = Json.parseToJsonElement(text).jsonObject
            val body = root["midi"]?.jsonArray ?: JsonArray(emptyList())
            return MidiWanted(
                ids = body.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.toSet(),
                note = root["note"]?.jsonPrimitive?.contentOrNull
            )
        }
    }
}
