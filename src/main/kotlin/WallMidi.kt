// ============================================================================ //
//  No `package` declaration: it reads .env through Env and the show through
//  Slideshow.kt, both of which are in the default package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.application
import org.openrndr.draw.ColorFormat
import org.openrndr.draw.ColorType
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.renderTarget
import org.openrndr.shape.Rectangle
import slideshow.Clip
import slideshow.FPS
import slideshow.Slide
import slideshow.Stage
import slideshow.clipFrames
import slideshow.melodicChannels
import slideshow.midiTracksOf
import slideshow.seconds
import slideshow.writeMidi
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes one wall's build as a MIDI file with a clip beside it — `midi/<slide-id>.mid` and
 * `.mp4` — for a wall that builds on its own clock rather than on clicks.
 *
 *     WALL_MIDI_SLIDE=shadow-mosaic ./gradlew run -Popenrndr.application=WallMidiKt
 *
 * **It is the organizer's export for a wall with no end.** That one scores a slide over the pace a
 * filmed run gives it, which is the right length for anything built out of clicks; a wall that
 * fills, holds and empties again for ever has no such length, and says instead how much of itself
 * is written down — `ShadowMosaic(midiWindow = 60.0)`, read here as [Slide.midiFrames]. **The film
 * is exactly as long as the file**, always, because it is the same number: a clip and a score that
 * disagree about where they end are worse than either on its own.
 *
 * The slide is the show's own — `show.slides`, the very object the evening plays — so what is
 * scored is what is projected, and the wall is drawn at the size it composes for: the whole canvas
 * for a wall, the pane beside the chapter card for a slide. Nothing is clicked: the export is the
 * slide's opening state and its own clock, which is what such a wall is.
 *
 * **On a slide with clicks that is state A**, and the files say so — `midi/the-catalogue-city-A.mid`
 * is the town coming up, the nameplate's own label for it. With no window of its own the clip runs as
 * long as a filmed run holds that state, or the last note and a hold after it, whichever is longer:
 * [clipFrames], the organizer's rule, with no clicks.
 *
 * `WALL_MIDI_VIDEO=false` writes the score alone, `_AT` shifts the notes along a film's timeline,
 * and `_NOTE` and `_BPM` are the pitch the register starts at and the bars a host rules over it.
 */
fun main() = application {
    configure {
        width = 640
        height = 360
        title = "wall midi"
    }
    program {
        exportWall(this)
        application.exit()
    }
}

private fun exportWall(program: Program) {
    // `withEnv()` and not `show.settings`: the canvas the walls compose for lives in `.env`, and
    // the raw show carries only what Slideshow.kt declares — the studio's note, for its reason.
    val catalogue = show.withEnv()
    val settings = catalogue.settings
    val id = Env["WALL_MIDI_SLIDE"]?.takeIf { it.isNotBlank() } ?: "shadow-mosaic"
    val index = catalogue.slideIds.indexOf(id)
    if (index < 0) {
        println("wall midi: no slide `$id` in the show — ${catalogue.slideIds.joinToString(", ")}")
        return
    }
    val slide = catalogue.slides[index]
    // A slide with clicks is scored on its opening state alone, so the files carry its letter.
    val name = if (slide.steps > 1) "$id-A" else id
    val folder = File(Env["WALL_MIDI_DIR"]?.takeIf { it.isNotBlank() } ?: "midi")
    val bpm = Env["WALL_MIDI_BPM"]?.toDoubleOrNull() ?: 120.0
    val base = Env["WALL_MIDI_NOTE"]?.toIntOrNull() ?: 36
    val offset = Env["WALL_MIDI_AT"]?.toDoubleOrNull() ?: 0.0
    val video = Env["WALL_MIDI_VIDEO"]?.lowercase() !in setOf("false", "0", "no")

    // The pane the wall composes for: the canvas whole for a wall, and the canvas less the chapter
    // card and its gutter for a slide — read off the show rather than stated, the studio's rule.
    val w = if (slide.wide) settings.width else settings.width - (settings.panelWidth?.let { it + settings.panelGap } ?: 0)
    val h = settings.height
    slide.layOut(w, h)
    slide.load(program)

    val tracks = midiTracksOf(slide, base, offset)
    if (tracks.isEmpty() || tracks.all { it.notes.isEmpty() }) {
        println("wall midi: $id wrote no notes — is it a wall that builds on its own clock?")
        return
    }
    val file = writeMidi(File(folder, "$name.mid"), tracks, bpm)
    val notes = tracks.sumOf { it.notes.size }
    val last = slide.arrivals().maxOf { it.start + it.length }
    println("wall midi: %s — %d notes on %d tracks, %.2fs to %.2fs → %s"
        .format(name, notes, tracks.size, offset, offset + seconds(last), file.path))
    tracks.forEachIndexed { k, t ->
        val pitches = t.notes.map { it.pitch }
        println("  ch %2d  %-24s %5d notes  pitch %s".format(
            melodicChannels[k % melodicChannels.size] + 1, t.name, t.notes.size,
            if (pitches.isEmpty()) "-" else "${pitches.min()}–${pitches.max()}"))
    }

    // The film is the score's own window — the wall says how much of itself it wrote down — with
    // the offset's frames of its opening ahead of it, so frame 0 of the clip is 0s of the file.
    val length = clipFrames(slide, settings.hold, emptyList(), last)
    if (video) film(program, slide, File(folder, "$name.mp4"), length, seconds = offset, w = w, h = h)
}

/** [slide] rendered into [file], [length] frames of its own clock, after [seconds] of lead. */
private fun film(program: Program, slide: Slide, file: File, length: Int, seconds: Double, w: Int, h: Int) {
    // The default colour buffer, which is sRGB: an explicit UINT8 one is linear and the picture
    // comes out of it decoded twice — the chapter card's note, measured there.
    val target = renderTarget(w, h) {
        colorBuffer()
        depthBuffer(DepthFormat.DEPTH24_STENCIL8)
    }
    val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
    val clip = Clip(file, w, h, FPS)
    if (!clip.ok) return
    val lead = (seconds * FPS).toInt().coerceAtLeast(0)
    val bounds = Rectangle(0.0, 0.0, w.toDouble(), h.toDouble())
    val drawer = program.drawer
    for (i in 0 until lead + length) {
        val frame = (i - lead).coerceAtLeast(0)
        val loop = if (slide.loop > 0) (frame % slide.loop) / slide.loop.toDouble() else 0.0
        drawer.isolatedWithTarget(target) {
            drawer.ortho(target)
            drawer.clear(slide.background)
            slide.draw(drawer, Stage(
                bounds, frame, steps = slide.steps, step = 0, position = 0.0,
                enter = 1.0, exit = 0.0, loop = loop, cycle = if (slide.loop > 0) frame / slide.loop else 0
            ))
        }
        pixels.rewind()
        target.colorBuffer(0).read(pixels, ColorFormat.RGBa, ColorType.UINT8)
        clip.frame(pixels)
    }
    val written = clip.close()
    target.destroy()
    println("wall midi: ${slide.name} — $written frames, %.2fs at $FPS fps → %s".format(seconds(written), file.path))
}
