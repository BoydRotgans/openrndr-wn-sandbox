package slideshow

import org.lwjgl.openal.AL
import org.lwjgl.openal.AL10.AL_BUFFER
import org.lwjgl.openal.AL10.AL_FALSE
import org.lwjgl.openal.AL10.AL_FORMAT_MONO16
import org.lwjgl.openal.AL10.AL_FORMAT_STEREO16
import org.lwjgl.openal.AL10.AL_GAIN
import org.lwjgl.openal.AL10.AL_LOOPING
import org.lwjgl.openal.AL10.AL_NO_ERROR
import org.lwjgl.openal.AL10.AL_PLAYING
import org.lwjgl.openal.AL10.AL_SOURCE_STATE
import org.lwjgl.openal.AL10.AL_TRUE
import org.lwjgl.openal.AL10.alBufferData
import org.lwjgl.openal.AL10.alDeleteBuffers
import org.lwjgl.openal.AL10.alDeleteSources
import org.lwjgl.openal.AL10.alGenBuffers
import org.lwjgl.openal.AL10.alGenSources
import org.lwjgl.openal.AL10.alGetError
import org.lwjgl.openal.AL10.alGetSourcei
import org.lwjgl.openal.AL10.alSourcePlay
import org.lwjgl.openal.AL10.alSourceStop
import org.lwjgl.openal.AL10.alSourcef
import org.lwjgl.openal.AL10.alSourcei
import org.lwjgl.openal.ALC
import org.lwjgl.openal.ALC10.ALC_DEVICE_SPECIFIER
import org.lwjgl.openal.ALC10.alcCloseDevice
import org.lwjgl.openal.ALC10.alcCreateContext
import org.lwjgl.openal.ALC10.alcDestroyContext
import org.lwjgl.openal.ALC10.alcGetString
import org.lwjgl.openal.ALC10.alcMakeContextCurrent
import org.lwjgl.openal.ALC10.alcOpenDevice
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The deck's sound: every cue decoded once at load, fired by the driver.
 *
 * **A cue is a buffer, not a stream.** The obvious thing to reach for is
 * `openrndr-openal`'s own [org.openrndr.openal.AudioQueueSource], and it is the wrong shape
 * for this: it has no decoder at all — it takes raw PCM — and every `play()` spawns a daemon
 * thread whose loop never exits. It is built for streaming synthesis. The community's other
 * answer, the `VorbisTrack` in the discourse audio-player example, is a *streaming* player
 * with seek and position, which is right for scrubbing a soundtrack and is 400 lines of
 * thread-per-track to fire a five-second sting.
 *
 * A cue is neither. It is short, it is known before the show starts, and it wants to be
 * instant. So each file is decoded whole at load into one AL buffer and triggered with one
 * call — no thread, no streaming, nothing to keep in step.
 *
 * **The JDK is the decoder.** `javax.sound.sampled` reads wav and converts as it goes, which
 * this cue sheet needs: it is 24-bit and OpenAL takes 16. That is the whole reason there is
 * no decoding library here.
 *
 * **Nothing about this may stop the show.** No audio device, no file, a machine with the
 * sound off — every one of them lands the deck in silence and leaves it running, the same
 * way a missing sheet falls back to plain type. A talk that will not start because it cannot
 * find a wav is worse than a talk with no sound in it.
 *
 * The driver is the only caller, for the reason a slide never reads a clock: only [present]
 * knows whether a card is arriving forward, being stepped back into, or being jumped past
 * on the way to a still.
 */
class Speakers(
    private val voices: Int = 8,
    /**
     * Bring every cue to one loudness as it is decoded.
     *
     * On, because this sheet needs it: the cues are stems at wildly different levels and one
     * gain cannot serve them. Off plays each file exactly as delivered, which is what you want
     * when the sheet has been mixed already.
     */
    private val levelled: Boolean = true
) {

    /** The loudness every cue is brought to, and the peak it may not pass, in dBFS. */
    private val targetRms = 10.0.pow(-23.0 / 20.0)
    private val ceiling = 10.0.pow(-6.0 / 20.0)

    private var device = 0L
    private var context = 0L

    /** False until a device is open, and false for ever if opening one failed. */
    var ready = false
        private set

    /** One AL buffer per *file*, so four cards on one cue decode it once. */
    private val buffers = mutableMapOf<String, Int>()

    /** The one-shot voices, taken round-robin so two cues can overlap. */
    private var pool = IntArray(0)
    private var next = 0

    /**
     * A source of its own per **sustained** cue — a loop, or anything with a fade out.
     *
     * The pool cannot serve those: a voice taken round-robin is reused by the next cue, so
     * there would be nothing to find again when the slide is left and the fade has to run.
     */
    private val held = mutableMapOf<String, Int>()

    /** Where each held source's gain stands, so a fade can start from wherever it got to. */
    private val level = mutableMapOf<String, Double>()

    /** The ramps running on held sources. */
    private val fading = mutableMapOf<String, Fade>()

    /** The frame the driver last ticked to — the deck's own, never a wall clock. */
    private var frame = 0

    /**
     * A gain ramp on a held source, as a *function of the frame* rather than something
     * integrated per tick: ask for frame 412 and you get frame 412. The same reason the rest
     * of the deck is written this way, and it means a dropped frame does not shorten a fade.
     */
    private class Fade(
        val from: Double, val to: Double, val at: Int, val length: Int,
        /** Stop the source once it reaches silence, rather than leaving it running at zero. */
        val stopAtEnd: Boolean
    ) {
        fun levelAt(frame: Int) = from + (to - from) * ramp(frame - at, length)
        fun doneAt(frame: Int) = frame - at >= length
    }

    // ---------------------------------------------------------------------------- //

    /**
     * Decodes every distinct cue and opens the device. Called once, before the first frame.
     *
     * Everything here is what would otherwise land on a click, which is the same deal
     * [Slide.load] makes: the show boots slower so nothing ever waits. The whole `WN-0909`
     * sheet is about 31 MB decoded, so holding all of it is cheaper than reading any of it
     * late.
     */
    fun load(sounds: List<Sound>) {
        val wanted = sounds.distinctBy { it.file.path }
        if (wanted.isEmpty()) return

        val present = wanted.filter { it.present }
        wanted.filterNot { it.present }.forEach { println("sound: no such file ${it.file.path} — silent") }
        if (present.isEmpty()) return

        if (!openDevice()) return

        var length = 0.0
        var bytes = 0L
        for (sound in present) {
            val pcm = runCatching { decode(sound.file) }
                .onFailure { println("sound: ${sound.file.name} would not decode (${it.message}) — silent") }
                .getOrNull() ?: continue
            val buffer = alGenBuffers()
            alBufferData(buffer, pcm.format, pcm.data, pcm.rate)
            if (alGetError() != AL_NO_ERROR) {
                alDeleteBuffers(buffer)
                println("sound: ${sound.file.name} would not buffer — silent")
                continue
            }
            buffers[sound.file.path] = buffer
            // per file, because the sheet is 44.1k and the ambience bed is 48k
            length += pcm.frames.toDouble() / pcm.rate
            bytes += pcm.data.capacity().toLong()
            if (levelled) println(
                "  %-28s %5.1f dB -> %+5.1f dB".format(
                    sound.file.name, 20.0 * log10(pcm.rms.coerceAtLeast(1e-9)),
                    20.0 * log10(pcm.boost.coerceAtLeast(1e-9))
                )
            )
        }

        pool = IntArray(voices) { alGenSources() }
        println(
            "sound: %d cues, %.1fs, %.0f MB, %d voices on %s".format(
                buffers.size, length, bytes / 1e6, voices, alcGetString(device, ALC_DEVICE_SPECIFIER)
            )
        )
    }

    /**
     * Fires a cue. Null, an unloaded one and a silent deck are all no-ops, so the driver can
     * call it unconditionally.
     *
     * A one-shot takes the next voice in the pool and interrupts whatever was on it — with
     * eight of them and cues a few seconds long, that only ever reclaims something already
     * finished. A loop keeps a source to itself and is left alone if it is already running,
     * so asking twice does not restart it — it only picks the fade back up from wherever the
     * gain had got to, which is what makes leaving a slide and coming straight back sound
     * like one continuous bed rather than two.
     */
    fun play(sound: Sound?) {
        if (!ready || sound == null) return
        val buffer = buffers[sound.file.path] ?: return

        if (sound.sustained) {
            val key = sound.file.path
            val source = held.getOrPut(key) {
                alGenSources().also {
                    alSourcei(it, AL_BUFFER, buffer)
                    alSourcei(it, AL_LOOPING, if (sound.loop) AL_TRUE else AL_FALSE)
                }
            }
            val from = level[key] ?: 0.0
            if (sound.fadeIn > 0) {
                fading[key] = Fade(from, sound.gain, frame, sound.fadeIn, stopAtEnd = false)
            } else {
                fading.remove(key)
                level[key] = sound.gain
            }
            // Come up from where it stands, so the first frame of a fade is not a full-gain blip.
            alSourcef(source, AL_GAIN, (level[key] ?: from).toFloat())
            if (alGetSourcei(source, AL_SOURCE_STATE) != AL_PLAYING) alSourcePlay(source)
            return
        }

        if (pool.isEmpty()) return
        val source = pool[next]
        next = (next + 1) % pool.size
        // A source will not take a new buffer while it is playing, so stop it first.
        alSourceStop(source)
        alSourcei(source, AL_BUFFER, buffer)
        alSourcef(source, AL_GAIN, sound.gain.toFloat())
        alSourcePlay(source)
    }

    /**
     * Lets a sustained cue go: out over its own [Sound.fadeOut] and stopped once it is silent.
     *
     * The counterpart to [play], called as the slide that declared the cue is left. A sting
     * declares no fade, is never held, and needs none of this — it is over when it is over.
     */
    fun release(sound: Sound?) {
        if (!ready || sound == null) return
        val key = sound.file.path
        val source = held[key] ?: return
        val from = level[key] ?: sound.gain

        if (sound.fadeOut <= 0) {
            fading.remove(key)
            level[key] = 0.0
            alSourceStop(source)
            return
        }
        fading[key] = Fade(from, 0.0, frame, sound.fadeOut, stopAtEnd = true)
    }

    /**
     * Advances the fades, once a frame, from the driver's own frame count.
     *
     * This is the only thing here that moves on its own, and it is handed the frame rather
     * than reading a clock — so a fade is the same length whether the show is watched, filmed
     * or stepped, and pausing the deck holds it where it stands.
     */
    fun tick(frame: Int) {
        if (!ready) return
        this.frame = frame
        if (fading.isEmpty()) return

        val finished = mutableListOf<String>()
        for ((key, fade) in fading) {
            val source = held[key] ?: continue
            val now = fade.levelAt(frame)
            level[key] = now
            alSourcef(source, AL_GAIN, now.toFloat())
            if (fade.doneAt(frame)) {
                if (fade.stopAtEnd) alSourceStop(source)
                finished += key
            }
        }
        finished.forEach { fading.remove(it) }
    }

    /** Everything off at once — the loops included, with no fade. */
    fun silence() {
        if (!ready) return
        fading.clear()
        pool.forEach { alSourceStop(it) }
        held.forEach { (key, source) -> alSourceStop(source); level[key] = 0.0 }
    }

    fun close() {
        if (!ready) return
        ready = false
        silence()
        pool.forEach { alDeleteSources(it) }
        held.values.forEach { alDeleteSources(it) }
        buffers.values.forEach { alDeleteBuffers(it) }
        alcDestroyContext(context)
        alcCloseDevice(device)
    }

    // ---------------------------------------------------------------------------- //

    private fun openDevice(): Boolean {
        val opened = runCatching {
            device = alcOpenDevice(null as ByteBuffer?)
            if (device == 0L) return@runCatching false
            val caps = ALC.createCapabilities(device)
            context = alcCreateContext(device, null as IntBuffer?)
            if (context == 0L) return@runCatching false
            alcMakeContextCurrent(context)
            AL.createCapabilities(caps)
            true
        }.getOrElse {
            println("sound: no audio (${it.message}) — the show runs silent")
            false
        }
        if (!opened) println("sound: no audio device — the show runs silent")
        ready = opened
        if (opened) Runtime.getRuntime().addShutdownHook(Thread { runCatching { close() } })
        return opened
    }

    /** PCM as OpenAL wants it: 16-bit, little-endian, in a direct buffer. */
    private class Pcm(
        val data: ByteBuffer, val format: Int, val rate: Int, val frames: Long,
        /** What levelling did to it, for the load report. */
        val boost: Double, val rms: Double
    )

    /**
     * Reads a cue, **levels it**, and hands back 16-bit PCM.
     *
     * The JDK is the decoder, which is why nothing here parses RIFF chunks and why the two
     * formats in this project — 24-bit and 32-bit float — both work without a converter
     * written by hand.
     *
     * **The sheet arrives unlevelled and has to be levelled here.** Measured, the cues run from
     * -36.8 dB mean (`1-01`) to -59.6 dB (`1-09B`), and their peaks span 29 dB. Played at one
     * gain the loud ones are right and the quiet ones are inaudible — the globe's `1-07` sits 16
     * dB under the chapter sting and simply could not be heard. These are stems rather than a
     * mix, so levelling them is the mix.
     *
     * **It is done in float, before the 16-bit conversion, and that is the whole reason for two
     * passes.** `1-09B` peaks at -40 dB, so bringing it up is a boost of about 34 dB. Applied
     * after quantising, that lifts 16-bit quantisation noise with it and the sting arrives with
     * an audible hiss behind it; applied to the float samples the source's own noise floor is
     * what gets lifted, and on 24-bit material that stays far below hearing. One pass measures,
     * the other scales — the file is read twice rather than held twice, which matters when the
     * ambience bed alone is 114 MB as float.
     *
     * **RMS decides the boost and the peak caps it.** Matching loudness is what the ear wants,
     * but a peaky cue would clip long before its average got there: `1-09` averages -46.8 dB and
     * peaks at -11, so it is held at the ceiling and stays quieter than the rest, which is right
     * — it is an impact, not a tone.
     */
    private fun decode(file: java.io.File): Pcm {
        val source = AudioSystem.getAudioInputStream(file).use { it.format }
        val float = AudioFormat(
            AudioFormat.Encoding.PCM_FLOAT, source.sampleRate, 32,
            source.channels, source.channels * 4, source.sampleRate, false
        )
        require(AudioSystem.isConversionSupported(float, source)) {
            "${file.name}: no converter from ${source.encoding}/${source.sampleSizeInBits} to float"
        }

        // pass one — what is actually on the file
        var square = 0.0
        var peak = 0.0
        var count = 0L
        samples(file, float) { v ->
            square += v.toDouble() * v
            val size = abs(v.toDouble())
            if (size > peak) peak = size
            count++
        }
        val rms = if (count > 0) sqrt(square / count) else 0.0
        val boost = when {
            !levelled || rms <= 0.0 -> 1.0
            peak <= 0.0 -> 1.0
            else -> min(targetRms / rms, ceiling / peak)
        }

        // pass two — scale in float, then quantise
        val data = ByteBuffer.allocateDirect((count * 2).toInt()).order(ByteOrder.nativeOrder())
        val shorts = data.asShortBuffer()
        samples(file, float) { v ->
            val scaled = (v * boost).coerceIn(-1.0, 1.0) * Short.MAX_VALUE
            shorts.put(scaled.toInt().toShort())
        }

        return Pcm(
            data = data,
            format = if (source.channels == 1) AL_FORMAT_MONO16 else AL_FORMAT_STEREO16,
            rate = source.sampleRate.toInt(),
            frames = count / source.channels,
            boost = boost,
            rms = rms
        )
    }

    /** Every sample of [file] as a float, in [want]'s format, without holding the file. */
    private inline fun samples(file: java.io.File, want: AudioFormat, sink: (Float) -> Unit) {
        AudioSystem.getAudioInputStream(file).use { encoded ->
            AudioSystem.getAudioInputStream(want, encoded).use { stream ->
                val bytes = ByteArray(1 shl 16)
                val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                while (true) {
                    // AudioInputStream reads whole frames, so this never splits a float
                    val read = stream.read(bytes)
                    if (read <= 0) break
                    for (i in 0 until read / 4) sink(view.getFloat(i * 4))
                }
            }
        }
    }
}
