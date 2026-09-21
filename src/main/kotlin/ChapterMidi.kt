// ============================================================================ //
//  No `package` declaration: it reads .env through Env and the show through
//  Slideshow.kt, both of which are in the default package.
// ============================================================================ //

import org.openrndr.application
import org.openrndr.draw.ColorFormat
import org.openrndr.draw.ColorType
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.renderTarget
import org.openrndr.shape.Rectangle
import slideshow.Clip
import slideshow.FPS
import slideshow.Stage
import slideshow.drawers.LongShadowV3ChapterPanel
import slideshow.frames
import slideshow.melodicChannels
import slideshow.midiTracksOf
import slideshow.seconds
import slideshow.writeMidi
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes each long shadow type v3 chapter card's build as a MIDI file: a note for every block each
 * time it animates, in or out, with the regular blocks and the letter blocks kept apart.
 *
 *     ./gradlew run -Popenrndr.application=ChapterMidiKt
 *
 * **The schedule is the card's own.** The cards are read off the show — `show.panels`, the very
 * objects the talk stands beside its slides — and each hands over the plan its draw loop steps
 * through, so the file and the picture cannot disagree. No window is opened: the build is a pure
 * function of the frame.
 *
 * One track and one channel a lane — elements in (only when the field arrives rather than already
 * standing), elements out over the title, elements out beside it, letters in, and the title settling
 * — with the elements pitched by size low down and the letters by reading order two octaves up.
 * Times are seconds from the chapter opening; `CHAPTER_MIDI_AT` shifts them along.
 *
 * **The video goes beside it**, `card-<chapter>.mp4`: the card rendered frame by frame off its own
 * frame count — the clock the notes were written against — into ffmpeg through [Clip], so frame 0
 * of the film is 0 s of the file. Rendered, not filmed, so it takes as long as it takes to draw and
 * never drops a frame. It runs to the last note and `CHAPTER_MIDI_HOLD` seconds after it. The card is
 * filmed as the sketch renders it — its own concrete worked into the effect, at twice the detail.
 */
fun main() = application {
    configure {
        width = 640
        height = 360
        title = "chapter midi"
    }
    program {
        exportChapterCards(this)
        application.exit()
    }
}

private fun exportChapterCards(program: org.openrndr.Program) {
    val cards = show.panels.withIndex().filter { it.value is LongShadowV3ChapterPanel }
    if (cards.isEmpty()) {
        println("chapter midi: no long shadow v3 card in the show — SLIDES_CARD_V3_CHAPTERS names none")
        return
    }
    val folder = File(Env["CHAPTER_MIDI_DIR"]?.takeIf { it.isNotBlank() } ?: "midi")
    val bpm = Env["CHAPTER_MIDI_BPM"]?.toDoubleOrNull() ?: 120.0
    // 24 puts the elements on 24–83 and the letters on 84–127, the top of the MIDI range.
    val base = Env["CHAPTER_MIDI_NOTE"]?.toIntOrNull() ?: 24
    val offset = Env["CHAPTER_MIDI_AT"]?.toDoubleOrNull() ?: 0.0
    val hold = Env["CHAPTER_MIDI_HOLD"]?.toDoubleOrNull() ?: 2.0
    val video = Env["CHAPTER_MIDI_VIDEO"]?.lowercase() !in setOf("false", "0", "no")

    for ((_, card) in cards) {
        card as LongShadowV3ChapterPanel
        val tracks = midiTracksOf(card, base, offset)
        if (tracks.isEmpty()) continue
        val slug = card.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val file = writeMidi(File(folder, "card-$slug.mid"), tracks, bpm)
        val notes = tracks.sumOf { it.notes.size }
        val last = card.arrivals().maxOf { it.start + it.length }
        println("chapter midi: ${card.name} — $notes notes on ${tracks.size} tracks, %.2fs to %.2fs → %s"
            .format(offset, offset + seconds(last), file.path))
        tracks.forEachIndexed { k, t ->
            val pitches = t.notes.map { it.pitch }
            println("  ch %2d  %-32s %4d notes  pitch %s".format(
                melodicChannels[k % melodicChannels.size] + 1, t.name, t.notes.size,
                if (pitches.isEmpty()) "-" else "${pitches.min()}–${pitches.max()}"))
        }
        // Filmed as the sketch renders it: the same card, its own concrete in the effect, at twice
        // the detail — the edges and the shadows' soft ends come out as the sketch's do.
        if (video) film(program, longShadowV3Card(card.section, detail = 2.0, overlaid = false), File(folder, "card-$slug.mp4"), last + frames(hold), frames(offset))
    }
}

/**
 * [card] rendered into [file], [length] frames of it, with [lead] frames of its opening state first
 * so the film starts where the file does when `CHAPTER_MIDI_AT` shifts the notes along.
 */
private fun film(program: org.openrndr.Program, card: LongShadowV3ChapterPanel, file: File, length: Int, lead: Int) {
    val w = 1920; val h = 1080
    // The show's own export target: the default colour buffer, which is sRGB. A plain 8-bit one is
    // linear, and the card's colours came out of it as if decoded twice — the grey ground at 9 of
    // 255 instead of 58.
    val target = renderTarget(w, h) {
        colorBuffer()
        depthBuffer(DepthFormat.DEPTH24_STENCIL8)
    }
    val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
    val clip = Clip(file, w, h, FPS)
    if (!clip.ok) return
    card.load(program)
    val bounds = Rectangle(0.0, 0.0, w.toDouble(), h.toDouble())
    val drawer = program.drawer
    for (i in 0 until lead + length) {
        val frame = (i - lead).coerceAtLeast(0)
        drawer.isolatedWithTarget(target) {
            drawer.ortho(target)
            drawer.clear(card.background)
            card.draw(drawer, Stage(bounds, frame, steps = 1, step = 0, position = 0.0, enter = 1.0, exit = 0.0, loop = 0.0, cycle = 0))
        }
        pixels.rewind()
        target.colorBuffer(0).read(pixels, ColorFormat.RGBa, ColorType.UINT8)
        clip.frame(pixels)
    }
    val written = clip.close()
    target.destroy()
    println("chapter midi: ${card.name} — $written frames, %.2fs at $FPS fps → %s".format(seconds(written), file.path))
}
