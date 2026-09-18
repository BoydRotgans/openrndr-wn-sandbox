package slideshow

import java.io.File
import java.nio.ByteBuffer

/**
 * One mp4 being written a frame at a time, straight into ffmpeg.
 *
 * **It is a pipe rather than a folder of pngs**, which is the whole reason it exists: a 25 second
 * clip of the slide pane is 1500 frames, and written out as pngs first that is some gigabytes
 * encoded twice and read back again. Raw frames down ffmpeg's stdin cost one buffer, reused.
 *
 * **It is not [org.openrndr.ffmpeg.ScreenRecorder], and could not be.** That films *the program* —
 * one file per `application {}`, which is why filming four chapter cards is four runs (see the
 * note under CardStudio). An export of several slides in one session has to drive the encoder
 * itself, and then the frames need not be drawn at the rate they play at either: the show renders
 * them as fast as it can spare, and the clip still comes out at [fps].
 *
 * **The picture comes off the GPU upside down.** OpenGL's first row is the bottom one, so the
 * frames are flipped on the way in rather than in the renderer — one `vflip` in the filter chain,
 * which costs nothing and keeps the readback a straight copy.
 *
 * No ffmpeg on the path is an ordinary state: [ok] is false, nothing is written, and the export
 * says so rather than failing the show.
 */
class Clip(val file: File, width: Int, height: Int, fps: Int = FPS) {

    private val process: Process? = runCatching {
        file.absoluteFile.parentFile?.mkdirs()
        ProcessBuilder(
            "ffmpeg", "-y", "-loglevel", "error",
            "-f", "rawvideo", "-pix_fmt", "rgba", "-s", "${width}x$height", "-r", "$fps",
            "-i", "-",
            "-vf", "vflip",
            "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18",
            file.path
        ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    }.getOrElse {
        println("clip: no ffmpeg on the path (${it.message}) — ${file.name} not written")
        null
    }

    val ok: Boolean get() = process != null

    private var written = 0

    /** One frame, as the bytes [org.openrndr.draw.ColorBuffer.read] handed back. */
    fun frame(bytes: ByteBuffer) {
        val out = process?.outputStream ?: return
        bytes.rewind()
        val row = ByteArray(bytes.remaining())
        bytes.get(row)
        runCatching { out.write(row); written++ }
            .onFailure { println("clip: ${file.name} stopped after $written frames (${it.message})") }
    }

    /** Closes the pipe and waits for the file to be finished — it is not on disk until then. */
    fun close(): Int {
        val p = process ?: return 0
        runCatching { p.outputStream.close() }
        runCatching { p.waitFor() }
        return written
    }
}
