package slideshow

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min

/**
 * The film's soundtrack, rendered from what the deck asked of the speakers rather than
 * recorded off them.
 *
 * **A filmed run cannot capture its own sound, and does not need to.** Under `ScreenRecorder`
 * the draw loop is on video time and the speakers play on wall time, so what comes out of
 * the machine while filming drifts from the picture the moment the encoder falls behind —
 * which at 3840 wide it always does. But every cue is fired on a *deck frame*, and the deck
 * frame is video time by construction; [Speakers] keeps a log of what it was asked and when,
 * and this renders that log into a wav of exactly the film's length, with each cue landing on
 * the sample its frame maps to. Frame-accurate whatever the encoder did, and rendered again
 * from the log alone if the levels change.
 *
 * **It is the speakers' own arithmetic, run offline.** The levelling is the same two-pass
 * decode ([level]), the fades are the same ramp of the frame, a sustained cue keeps one voice
 * and picks a fade up from wherever the gain had got to, a loop wraps and a one-shot rings out
 * — the driver's rules, replayed frame by frame over the log so the soundtrack is what the
 * room would have heard had the machine kept up.
 *
 * **The mix is held under full scale, not clipped.** Cues are levelled to -23 dBFS with a -6
 * dB ceiling each, and two landing together can pass 0; rather than clip, the whole track is
 * brought down by whatever the loudest moment needs and the report says by how much.
 *
 * `ffmpeg` on the path muxes the wav into the film; without it the wav stands beside the mp4
 * for a later [remix], which is also how to re-render after a level is changed.
 */
object Soundtrack {

    /** The output: 16-bit stereo at a film's rate. */
    const val RATE = 48000

    /** Below full scale by this much, so the mix never touches 0 dBFS. */
    private const val HEADROOM = 0.99f

    /** The wav, the log and the mix that stand beside a film. */
    fun logFile(video: File) = File(video.parentFile, video.nameWithoutExtension + ".cues")
    fun wavFile(video: File) = File(video.parentFile, video.nameWithoutExtension + ".wav")
    fun mixFile(video: File) = File(video.parentFile, video.nameWithoutExtension + "-mixed." + video.extension)

    /**
     * Everything after a filmed run: the log written beside the film, the wav rendered from
     * it, and — with ffmpeg on the path and [mix] on — the two muxed into one file.
     */
    fun export(log: List<Speakers.Cue>, frames: Int, video: File, mix: Boolean) {
        if (frames <= 0) return
        if (!video.isFile) {
            println("export: no film at ${video.path} — nothing to score")
            return
        }
        if (log.isEmpty()) {
            println("export: ${video.path} is silent — no cue was fired (is SLIDES_SOUND off?)")
            return
        }
        writeLog(log, frames, logFile(video))
        if (!render(log, frames, wavFile(video))) return
        if (mix) mux(video, wavFile(video), mixFile(video))
        else println("export: soundtrack at ${wavFile(video).path}; SLIDES_MIX is off, so the film stands unmixed")
    }

    /** [export] again from the log beside a film, for a re-render without filming again. */
    fun remix(video: File, mix: Boolean) {
        val read = readLog(logFile(video)) ?: run {
            println("export: no log at ${logFile(video).path} — film the run with SLIDES_RECORD first")
            return
        }
        export(read.first, read.second, video, mix)
    }

    // ------------------------------------------------------------------------------ //
    //  The log
    // ------------------------------------------------------------------------------ //

    /** The run as text: its length in frames, then a line a cue, in the order they were fired. */
    fun writeLog(log: List<Speakers.Cue>, frames: Int, file: File) {
        file.parentFile?.mkdirs()
        file.printWriter().use { out ->
            out.println("frames $frames fps $FPS")
            for (cue in log) {
                val s = cue.sound
                out.println(
                    "${cue.frame}\t${if (cue.release) "release" else "play"}\t${s.gain}\t${s.loop}\t${s.fadeIn}\t${s.fadeOut}\t${s.file.path}"
                )
            }
        }
        println("export: ${log.size} cues logged to ${file.path}")
    }

    fun readLog(file: File): Pair<List<Speakers.Cue>, Int>? {
        if (!file.isFile) return null
        val lines = file.readLines().filter { it.isNotBlank() }
        val head = lines.firstOrNull()?.split(" ") ?: return null
        val frames = head.getOrNull(1)?.toIntOrNull() ?: return null
        val cues = lines.drop(1).mapNotNull { line ->
            val t = line.split("\t")
            if (t.size < 7) return@mapNotNull null
            Speakers.Cue(
                frame = t[0].toIntOrNull() ?: return@mapNotNull null,
                sound = Sound(File(t[6]), t[2].toDouble(), t[3].toBoolean(), t[4].toInt(), t[5].toInt()),
                release = t[1] == "release"
            )
        }
        return cues to frames
    }

    // ------------------------------------------------------------------------------ //
    //  Rendering
    // ------------------------------------------------------------------------------ //

    /** A cue decoded whole: interleaved, levelled, at the file's own rate. */
    private class Track(val samples: FloatArray, val channels: Int, val rate: Int) {
        val length: Int get() = samples.size / channels
        /** How many deck frames it runs for, played once. */
        val frames: Int get() = ceil(length.toDouble() / rate * FPS).toInt()
    }

    /** A gain ramp as a function of the frame — [Speakers]' own, so a fade is the same length here. */
    private class Ramp(val from: Double, val to: Double, val at: Int, val length: Int, val stopAtEnd: Boolean) {
        fun levelAt(frame: Int) = from + (to - from) * ramp(frame - at, length)
        fun doneAt(frame: Int) = frame - at >= length
    }

    /**
     * One stretch of one track sounding: from deck frame [start] to [end], looping or not, at
     * a gain per frame ([gains], indexed by absolute frame) or one gain throughout ([flat]).
     */
    private class Segment(
        val track: Track, val start: Int, val end: Int, val loop: Boolean,
        val gains: DoubleArray?, val flat: Double
    )

    /**
     * Renders [log] into a stereo wav of [frames] deck frames. False, and says why, if
     * nothing could be rendered.
     */
    fun render(log: List<Speakers.Cue>, frames: Int, out: File, levelled: Boolean = true): Boolean {
        if (log.isEmpty() || frames <= 0) return false

        val tracks = HashMap<String, Track?>()
        fun track(sound: Sound): Track? = tracks.getOrPut(sound.file.path) {
            if (!sound.present) {
                println("soundtrack: no such file ${sound.file.path} — left out")
                return@getOrPut null
            }
            runCatching {
                val read = level(sound.file, levelled)
                val samples = FloatArray((read.frames * read.channels).toInt())
                var i = 0
                read.each { if (i < samples.size) samples[i++] = it }
                Track(samples, read.channels, read.rate)
            }.onFailure { println("soundtrack: ${sound.file.name} would not decode (${it.message}) — left out") }
                .getOrNull()
        }

        // --- the driver's rules, replayed over the log ------------------------------ //

        val segments = mutableListOf<Segment>()

        /** A sustained cue's one voice, in the state [Speakers] keeps for its held sources. */
        class Voice(val track: Track, val sound: Sound) {
            var level: Double? = null
            var fade: Ramp? = null
            var since = -1
            var gains: DoubleArray? = null
            val playing: Boolean get() = since >= 0

            fun start(frame: Int) {
                if (playing) return
                since = frame
                gains = DoubleArray(frames)
            }

            fun stop(frame: Int) {
                if (!playing) return
                segments += Segment(track, since, frame, sound.loop, gains, 0.0)
                since = -1
                gains = null
            }
        }

        val voices = HashMap<String, Voice>()
        val byFrame = log.groupBy { it.frame }

        for (frame in 0 until frames) {
            // tick first, as the driver does: the ramps advance before anything fires
            for (voice in voices.values) {
                val fade = voice.fade ?: continue
                voice.level = fade.levelAt(frame)
                if (fade.doneAt(frame)) {
                    if (fade.stopAtEnd) voice.stop(frame)
                    voice.fade = null
                }
            }
            // a source that does not loop runs out by itself, and a later play restarts it
            for (voice in voices.values) {
                if (voice.playing && !voice.sound.loop && frame - voice.since >= voice.track.frames) voice.stop(frame)
            }

            for (cue in byFrame[frame].orEmpty()) {
                val sound = cue.sound
                val track = track(sound) ?: continue

                if (!sound.sustained) {
                    // a one-shot rings out; releasing one means nothing
                    if (!cue.release) segments += Segment(track, frame, min(frames, frame + track.frames), false, null, sound.gain)
                    continue
                }

                if (cue.release) {
                    val voice = voices[sound.file.path] ?: continue
                    val from = voice.level ?: sound.gain
                    if (sound.fadeOut <= 0) {
                        voice.fade = null
                        voice.level = 0.0
                        voice.stop(frame)
                    } else {
                        voice.fade = Ramp(from, 0.0, frame, sound.fadeOut, stopAtEnd = true)
                    }
                } else {
                    val voice = voices.getOrPut(sound.file.path) { Voice(track, sound) }
                    val from = voice.level ?: 0.0
                    if (sound.fadeIn > 0) {
                        voice.fade = Ramp(from, sound.gain, frame, sound.fadeIn, stopAtEnd = false)
                    } else {
                        voice.fade = null
                        voice.level = sound.gain
                    }
                    voice.start(frame)
                }
            }

            for (voice in voices.values) if (voice.playing) voice.gains!![frame] = voice.level ?: 0.0
        }
        for (voice in voices.values) voice.stop(frames)

        if (segments.isEmpty()) {
            println("soundtrack: nothing to render — no cue in the log could be decoded")
            return false
        }

        // --- the mix ------------------------------------------------------------------ //

        val total = (frames.toLong() * RATE / FPS).toInt()
        val mix = FloatArray(total * 2)

        for (s in segments) {
            val first = (s.start.toLong() * RATE / FPS).toInt()
            val last = min(total, (s.end.toLong() * RATE / FPS).toInt())
            val step = s.track.rate.toDouble() / RATE
            val length = s.track.length
            val channels = s.track.channels
            val samples = s.track.samples

            for (n in first until last) {
                var p = (n - first) * step
                if (s.loop) p %= length else if (p >= length - 1) break
                val i0 = floor(p).toInt()
                val i1 = if (i0 + 1 < length) i0 + 1 else if (s.loop) 0 else i0
                val t = (p - i0).toFloat()

                val gain = if (s.gains == null) s.flat else {
                    val f = s.start + (n - first).toDouble() * FPS / RATE
                    val f0 = floor(f).toInt().coerceIn(s.start, s.end - 1)
                    val f1 = (f0 + 1).coerceAtMost(s.end - 1)
                    val g0 = s.gains[f0]
                    val g1 = s.gains[f1]
                    g0 + (g1 - g0) * (f - f0)
                }
                if (gain <= 0.0) continue

                val l = samples[i0 * channels] * (1f - t) + samples[i1 * channels] * t
                val r = if (channels > 1) samples[i0 * channels + 1] * (1f - t) + samples[i1 * channels + 1] * t else l
                mix[2 * n] += (l * gain).toFloat()
                mix[2 * n + 1] += (r * gain).toFloat()
            }
        }

        // held under full scale rather than clipped
        var peak = 0f
        for (v in mix) if (abs(v) > peak) peak = abs(v)
        val trim = if (peak > HEADROOM) HEADROOM / peak else 1f
        if (trim < 1f) println("soundtrack: peak %.2f dBFS, whole mix brought down %.1f dB".format(20 * log10(peak), -20 * log10(trim)))

        val bytes = ByteBuffer.allocate(total * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in mix) bytes.putShort(((v * trim).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        val format = AudioFormat(RATE.toFloat(), 16, 2, true, false)
        out.parentFile?.mkdirs()
        AudioInputStream(ByteArrayInputStream(bytes.array()), format, total.toLong()).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, out)
        }
        println("soundtrack: %d cues, %d voices, %.1fs to %s".format(log.size, segments.size, total.toDouble() / RATE, out.path))
        return true
    }

    // ------------------------------------------------------------------------------ //
    //  The mix
    // ------------------------------------------------------------------------------ //

    /**
     * Muxes [audio] into [video] as [out] with ffmpeg: the picture copied untouched, the
     * sound as AAC. False if ffmpeg is not on the path or fails, and says so.
     */
    fun mux(video: File, audio: File, out: File): Boolean {
        val command = listOf(
            "ffmpeg", "-y", "-loglevel", "error",
            "-i", video.path, "-i", audio.path,
            "-map", "0:v:0", "-map", "1:a:0",
            "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
            "-shortest", out.path
        )
        return runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val said = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code == 0 && out.isFile) {
                println("export: mixed to ${out.path}")
                true
            } else {
                println("export: ffmpeg failed ($code) — ${said.trim().lines().lastOrNull().orEmpty()}; the wav stands beside the film")
                false
            }
        }.getOrElse {
            println("export: no ffmpeg on the path (${it.message}) — the wav stands beside the film for MixSoundtrackKt")
            false
        }
    }
}
