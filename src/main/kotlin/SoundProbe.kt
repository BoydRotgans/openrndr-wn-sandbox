import org.lwjgl.openal.AL
import org.lwjgl.openal.AL10.*
import org.lwjgl.openal.ALC
import org.lwjgl.openal.ALC10.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * Proves the machine can make a noise, and proves the path the deck will use:
 *
 *     ./gradlew run -Popenrndr.application=SoundProbeKt
 *
 * A wav decoded by the **JDK's own reader** (`javax.sound.sampled`, which handles the 24-bit
 * to 16-bit conversion these files need) into one **OpenAL buffer**, played by one source.
 * No new artifact: `openrndr-openal` already carries the LWJGL AL bindings and their natives,
 * and the JDK carries the wav reader — see the note beside `libs.lwjgl.openal` in
 * build.gradle.kts for why the binding still has to be named there.
 *
 * Worth keeping rather than deleting: run it on the projector machine before a show and it
 * says which audio device is being opened and whether anything comes out of it, which is the
 * one thing a silent deck cannot tell you.
 */
fun main() {
    val file = File("data/sounds/WN-0909/1-01.wav")
    require(file.isFile) { "no such file: ${file.path}" }

    // --- decode, with the JDK's own reader. 24-bit in, 16-bit little-endian out. ---
    val encoded = AudioSystem.getAudioInputStream(file)
    val from = encoded.format
    println("file:   ${from.sampleSizeInBits}-bit, ${from.channels}ch, ${from.sampleRate.toInt()}Hz, ${from.encoding}")

    val want = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED, from.sampleRate, 16,
        from.channels, from.channels * 2, from.sampleRate, false
    )
    require(AudioSystem.isConversionSupported(want, from)) { "no 24->16 converter" }

    val bytes = AudioSystem.getAudioInputStream(want, encoded).readBytes()
    val seconds = bytes.size.toDouble() / (want.sampleRate * want.channels * 2)
    println("decoded: ${bytes.size} bytes = %.2fs".format(seconds))

    val pcm = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).flip() as ByteBuffer

    // --- OpenAL: device, context, one buffer, one source. ---
    val device = alcOpenDevice(null as ByteBuffer?)
    require(device != 0L) { "no audio device" }
    val alc = ALC.createCapabilities(device)
    val context = alcCreateContext(device, null as IntBuffer?)
    alcMakeContextCurrent(context)
    AL.createCapabilities(alc)
    println("device: ${alcGetString(device, ALC_DEVICE_SPECIFIER)}")

    val buffer = alGenBuffers()
    alBufferData(buffer, if (want.channels == 2) AL_FORMAT_STEREO16 else AL_FORMAT_MONO16, pcm, want.sampleRate.toInt())
    require(alGetError() == AL_NO_ERROR) { "alBufferData failed" }

    val src = alGenSources()
    alSourcei(src, AL_BUFFER, buffer)
    alSourcef(src, AL_GAIN, 1.0f)

    println("playing...")
    alSourcePlay(src)
    while (alGetSourcei(src, AL_SOURCE_STATE) == AL_PLAYING) Thread.sleep(50)
    println("done. retriggering twice, 300ms apart, to prove a cue can fire again")
    repeat(2) { alSourcePlay(src); Thread.sleep(300) }
    Thread.sleep(1500)

    alDeleteSources(src); alDeleteBuffers(buffer)
    alcDestroyContext(context); alcCloseDevice(device)
    println("ok")
}
