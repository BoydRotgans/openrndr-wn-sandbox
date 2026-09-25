package slideshow

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL33C.*
import org.openrndr.Extension
import org.openrndr.Program
import org.openrndr.draw.BufferMultisample
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.Drawer
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.renderTarget
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Films the program the way `ScreenRecorder` does — the same frame clock, the same target, the
 * same ffmpeg arguments, so a frame comes out byte for byte as it did — but **never waits on the
 * frame it has just drawn.**
 *
 * `ScreenRecorder` reads each frame back with a plain `glGetTexImage` into memory and writes it
 * down ffmpeg's stdin before the next frame may start. The read makes the CPU wait for the GPU to
 * finish drawing, and the write makes the next frame wait for ffmpeg. Measured on a full run of the
 * show, the GPU stood idle a third of the time while both ffmpeg and the JVM sat under half a core:
 * the time was going on waiting, not on work.
 *
 * So the read goes into a **pixel buffer** instead, which the GPU fills when it gets there, with a
 * fence behind it; the frame is collected [DEPTH] draws later, when it is long finished, copied out
 * into one of a small pool of buffers, and handed to a **writer thread** that feeds ffmpeg while the
 * next frames are drawn. The GPU goes on to the next frame at once, and the pipe to ffmpeg never
 * holds up the draw loop unless the encoder falls a whole pool behind — which is backpressure, and
 * right.
 *
 * Everything else is `ScreenRecorder`'s own, read off its source: `program.clock` swapped for the
 * frame index in `beforeDraw` and put back in `afterDraw` (the note under demo01 in CLAUDE.md), the
 * frame drawn into a target at [contentScale] and then shown in the window, the frame that asks
 * to quit still recorded, and the file finished in `shutdown` so it is on disk when `application {}`
 * returns.
 */
class WallRecorder(
    private val output: File,
    private val frameRate: Int,
    private val contentScale: Double,
    private val maximumDuration: Double = Double.POSITIVE_INFINITY,
) : Extension {
    override var enabled: Boolean = true

    private lateinit var frame: RenderTarget
    private var resolved: ColorBuffer? = null
    private var texture = 0
    private var bytes = 0

    private val pbos = IntArray(DEPTH)
    private val fences = LongArray(DEPTH)
    private val pending = BooleanArray(DEPTH)
    private var slot = 0

    private val spare = ArrayBlockingQueue<ByteBuffer>(POOL)
    private val filled = ArrayBlockingQueue<ByteBuffer>(POOL + 1)
    private val end: ByteBuffer = ByteBuffer.allocate(0)
    private var writer: Thread? = null
    private var ffmpeg: Process? = null
    @Volatile private var failure: Throwable? = null

    private var frameIndex = 0L
    private var storedClock: (() -> Double)? = null
    private var stopped = false

    /** Frames handed to the encoder so far. */
    var written = 0L
        private set

    override fun setup(program: Program) {
        program.window.resizable = false
        val multisample = program.window.multisample.bufferEquivalent()
        frame = renderTarget(program.width, program.height, contentScale = contentScale, multisample = multisample) {
            colorBuffer()
            depthBuffer()
        }
        if (multisample != BufferMultisample.Disabled) {
            resolved = colorBuffer(program.width, program.height, contentScale = contentScale)
        }
        val source = resolved ?: frame.colorBuffer(0)
        val w = source.effectiveWidth
        val h = source.effectiveHeight
        require(w % 2 == 0 && h % 2 == 0) { "recorder: ${w}x$h will not encode as yuv420p; it needs even sides" }
        // OPENRNDR's GL colour buffer keeps its texture name in `texture`; the class is in the
        // runtime-only gl3 module, so it is asked for by name rather than by type.
        texture = source.javaClass.getMethod("getTexture").invoke(source) as Int
        bytes = w * h * 4

        for (i in 0 until DEPTH) {
            pbos[i] = glGenBuffers()
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pbos[i])
            glBufferData(GL_PIXEL_PACK_BUFFER, bytes.toLong(), GL_STREAM_READ)
        }
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        repeat(POOL) { spare.put(BufferUtils.createByteBuffer(bytes)) }

        output.absoluteFile.parentFile?.mkdirs()
        val log = File("build/recorder/${output.nameWithoutExtension}.log").also { it.parentFile.mkdirs() }
        // ScreenRecorder's own arguments: its preamble and H264Profile's defaults, word for word.
        val command = listOf(
            ffmpegPath(), "-y", "-f", "rawvideo", "-vcodec", "rawvideo",
            "-s", "${w}x$h", "-pix_fmt", "rgba", "-r", "$frameRate", "-i", "-",
            "-pix_fmt", "yuv420p",
            "-sws_flags", "spline+accurate_rnd+full_chroma_int",
            "-color_range", "1", "-colorspace", "1", "-color_primaries", "1", "-color_trc", "1",
            "-vf", "vflip,colorspace=bt709:iall=bt601-6-625:fast=1",
            "-vcodec", "libx264",
            output.path
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
        ffmpeg = process
        writer = Thread({
            val channel = Channels.newChannel(process.outputStream)
            try {
                while (true) {
                    val buffer = filled.take()
                    if (buffer === end) break
                    while (buffer.hasRemaining()) channel.write(buffer)
                    buffer.clear()
                    spare.put(buffer)
                }
            } catch (t: Throwable) {
                failure = t
            }
            runCatching { channel.close() }
        }, "wall-recorder").apply { isDaemon = true; start() }
        println("recorder: ${w}x$h at $frameRate fps, read back $DEPTH frames behind → ${output.path}")
    }

    override fun beforeDraw(drawer: Drawer, program: Program) {
        storedClock = program.clock
        program.clock = { frameIndex / frameRate.toDouble() }
        program.updateFrameSecondsFromClock()
        frame.bind()
        program.backgroundColor?.let { drawer.clear(it) }
    }

    override fun afterDraw(drawer: Drawer, program: Program) {
        frame.unbind()
        if (!stopped) {
            if (written / frameRate.toDouble() < maximumDuration) {
                resolved?.let { frame.colorBuffer(0).copyTo(it) }
                read()
            } else {
                program.application.exit()
                stop()
            }
        }
        drawer.isolated {
            drawer.defaults()
            drawer.image(resolved ?: frame.colorBuffer(0), 0.0, 0.0, frame.width.toDouble(), frame.height.toDouble())
        }
        frameIndex++
        storedClock?.let {
            program.clock = it
            program.updateFrameSecondsFromClock()
        }
    }

    override fun shutdown(program: Program) {
        stop()
        pbos.forEach { if (it != 0) glDeleteBuffers(it) }
        resolved?.destroy()
        frame.colorBuffer(0).destroy()
        frame.depthBuffer?.destroy()
        frame.destroy()
    }

    /** Asks for this frame into the next pixel buffer, collecting the one that buffer last held. */
    private fun read() {
        failure?.let { throw RuntimeException("recorder: ffmpeg stopped taking frames (${it.message}); see build/recorder", it) }
        collect(slot)
        glBindBuffer(GL_PIXEL_PACK_BUFFER, pbos[slot])
        val bound = glGetInteger(GL_TEXTURE_BINDING_2D)
        val alignment = glGetInteger(GL_PACK_ALIGNMENT)
        glBindTexture(GL_TEXTURE_2D, texture)
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        // What ColorBuffer.read asks for — the texture's own RGBA bytes — only into GPU memory,
        // so the call returns at once rather than waiting for the frame to be drawn.
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L)
        glPixelStorei(GL_PACK_ALIGNMENT, alignment)
        glBindTexture(GL_TEXTURE_2D, bound)
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        fences[slot] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        pending[slot] = true
        slot = (slot + 1) % DEPTH
        written++
    }

    /** The frame in buffer [s], copied out and queued for ffmpeg. Frames are collected oldest first. */
    private fun collect(s: Int) {
        if (!pending[s]) return
        val fence = fences[s]
        while (true) {
            val state = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L)
            if (state != GL_TIMEOUT_EXPIRED) break
        }
        glDeleteSync(fence)
        glBindBuffer(GL_PIXEL_PACK_BUFFER, pbos[s])
        val mapped = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0L, bytes.toLong(), GL_MAP_READ_BIT)
        val target = takeSpare()
        target.clear()
        if (mapped != null) target.put(mapped)
        target.flip()
        glUnmapBuffer(GL_PIXEL_PACK_BUFFER)
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        filled.put(target)
        pending[s] = false
    }

    /** A free buffer, waiting for the writer if the encoder is a whole pool behind. */
    private fun takeSpare(): ByteBuffer {
        while (true) {
            spare.poll(1, TimeUnit.SECONDS)?.let { return it }
            failure?.let { throw RuntimeException("recorder: ffmpeg stopped taking frames (${it.message}); see build/recorder", it) }
        }
    }

    /** Collects what is still in flight, closes the pipe and waits for ffmpeg to finish the file. */
    private fun stop() {
        if (stopped) return
        stopped = true
        for (k in 0 until DEPTH) collect((slot + k) % DEPTH)
        filled.put(end)
        writer?.join()
        ffmpeg?.let { process ->
            runCatching { process.outputStream.close() }
            val code = process.waitFor()
            if (code != 0) println("recorder: ffmpeg exited $code — see build/recorder/${output.nameWithoutExtension}.log")
        }
        failure?.let { println("recorder: the writer failed (${it.message})") }
        println("recorder: $written frames at $frameRate fps → ${output.path}")
    }

    companion object {
        /** Frames in flight between asking for a read and collecting it. */
        const val DEPTH = 3

        /** Frames that may wait for ffmpeg before the draw loop does. */
        const val POOL = 6

        /** ffmpeg on the path, or Homebrew's, which a GUI-launched JVM may not have on its path. */
        fun ffmpegPath(): String =
            listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg").firstOrNull { File(it).canExecute() } ?: "ffmpeg"
    }
}
