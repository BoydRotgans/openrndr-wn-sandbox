package slideshow

import org.lwjgl.openal.AL
import org.lwjgl.openal.AL10.AL_BUFFER
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
class Speakers(private val voices: Int = 8) {

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

    /** A source of its own per looping sound, since a loop holds its voice for good. */
    private val held = mutableMapOf<String, Int>()

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

        var samples = 0L
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
            samples += pcm.frames
        }

        pool = IntArray(voices) { alGenSources() }
        println(
            "sound: %d cues, %.1fs, %d voices on %s".format(
                buffers.size, samples / 44100.0, voices, alcGetString(device, ALC_DEVICE_SPECIFIER)
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
     * so asking twice does not restart it.
     */
    fun play(sound: Sound?) {
        if (!ready || sound == null) return
        val buffer = buffers[sound.file.path] ?: return

        if (sound.loop) {
            val source = held.getOrPut(sound.file.path) {
                alGenSources().also {
                    alSourcei(it, AL_BUFFER, buffer)
                    alSourcei(it, AL_LOOPING, AL_TRUE)
                }
            }
            alSourcef(source, AL_GAIN, sound.gain.toFloat())
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

    /** Everything off — the loops included. */
    fun silence() {
        if (!ready) return
        pool.forEach { alSourceStop(it) }
        held.values.forEach { alSourceStop(it) }
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
    private class Pcm(val data: ByteBuffer, val format: Int, val rate: Int, val frames: Long)

    /**
     * The JDK's own reader, which is why nothing here parses RIFF chunks — and, more to the
     * point, why the 24-bit files these are need no conversion writing by hand. Ask for
     * 16-bit little-endian and `getAudioInputStream` inserts the converter.
     */
    private fun decode(file: java.io.File): Pcm {
        AudioSystem.getAudioInputStream(file).use { encoded ->
            val from = encoded.format
            val want = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED, from.sampleRate, 16,
                from.channels, from.channels * 2, from.sampleRate, false
            )
            require(AudioSystem.isConversionSupported(want, from)) {
                "${file.name}: no converter from ${from.sampleSizeInBits}-bit to 16-bit"
            }
            val bytes = AudioSystem.getAudioInputStream(want, encoded).use { it.readBytes() }
            val data = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                .put(bytes).flip() as ByteBuffer
            return Pcm(
                data = data,
                format = if (from.channels == 1) AL_FORMAT_MONO16 else AL_FORMAT_STEREO16,
                rate = from.sampleRate.toInt(),
                frames = (bytes.size / (from.channels * 2)).toLong()
            )
        }
    }
}
