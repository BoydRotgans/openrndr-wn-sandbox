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
    /** The x264 quality to film at; null leaves ffmpeg's default, as ScreenRecorder does. */
    private val crf: Int? = null,
) : Extension {
    override var enabled: Boolean = true

    private lateinit var frame: RenderTarget
    private var resolved: ColorBuffer? = null
    private var texture = 0
    private lateinit var pipe: FramePipe

    private var frameIndex = 0L
    private var storedClock: (() -> Double)? = null

    /** Frames handed to the encoder so far. */
    val written: Long get() = if (::pipe.isInitialized) pipe.written else 0L

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
        texture = FramePipe.textureOf(source)
        pipe = FramePipe(output, w, h, FramePipe.h264(crf), frameRate, "recorder")
        println("recorder: ${w}x$h at $frameRate fps, read back ${FramePipe.DEPTH} frames behind → ${output.path}")
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
        if (!pipe.stopped) {
            if (pipe.written / frameRate.toDouble() < maximumDuration) {
                resolved?.let { frame.colorBuffer(0).copyTo(it) }
                pipe.push(texture)
            } else {
                program.application.exit()
                pipe.stop()
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
        pipe.stop()
        pipe.destroy()
        resolved?.destroy()
        frame.colorBuffer(0).destroy()
        frame.depthBuffer?.destroy()
        frame.destroy()
    }

    companion object {
        /** ffmpeg on the path, or Homebrew's, which a GUI-launched JVM may not have on its path. */
        fun ffmpegPath(): String =
            listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg").firstOrNull { File(it).canExecute() } ?: "ffmpeg"
    }
}

/**
 * The recorder's way of getting frames off the GPU, for any texture of [width] by [height]: each read goes into
 * a **pixel buffer** the GPU fills when it gets there, with a fence behind it, and is collected [DEPTH] pushes
 * later, when it is long finished — copied into one of a small pool of buffers and handed to a **writer thread**
 * that feeds ffmpeg while the next frames are drawn. The draw loop only waits when the encoder falls a whole pool
 * behind, which is backpressure, and right.
 *
 * ffmpeg is given the raw frames at [frameRate] and then [encode], its arguments from the pixel format on, and
 * writes [output]; its log is `build/recorder/<name>.log`. [WallRecorder] films the wall through one, and the
 * autocue's film is another ([Settings.recordAutocue]), pushed on the same draws so the two are frame for frame.
 */
class FramePipe(
    private val output: File,
    width: Int, height: Int,
    encode: List<String>,
    private val frameRate: Int,
    /** Who is speaking in the log lines. */
    private val label: String,
) {
    private val bytes = width * height * 4
    private val pbos = IntArray(DEPTH)
    private val fences = LongArray(DEPTH)
    private val pending = BooleanArray(DEPTH)
    private var slot = 0

    private val spare = ArrayBlockingQueue<ByteBuffer>(POOL)
    private val filled = ArrayBlockingQueue<ByteBuffer>(POOL + 1)
    private val end: ByteBuffer = ByteBuffer.allocate(0)
    private val writer: Thread
    private val ffmpeg: Process
    @Volatile private var failure: Throwable? = null

    /** Frames handed to the encoder so far. */
    var written = 0L
        private set

    var stopped = false
        private set

    init {
        for (i in 0 until DEPTH) {
            pbos[i] = glGenBuffers()
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pbos[i])
            glBufferData(GL_PIXEL_PACK_BUFFER, bytes.toLong(), GL_STREAM_READ)
        }
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        repeat(POOL) { spare.put(BufferUtils.createByteBuffer(bytes)) }

        output.absoluteFile.parentFile?.mkdirs()
        val log = File("build/recorder/${output.nameWithoutExtension}.log").also { it.parentFile.mkdirs() }
        val command = listOf(
            WallRecorder.ffmpegPath(), "-y", "-f", "rawvideo", "-vcodec", "rawvideo",
            "-s", "${width}x$height", "-pix_fmt", "rgba", "-r", "$frameRate", "-i", "-"
        ) + encode + output.path
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
        }, "$label-writer").apply { isDaemon = true; start() }
    }

    /** Asks for [texture]'s pixels into the next pixel buffer, collecting the frame that buffer last held. */
    fun push(texture: Int) {
        if (stopped) return
        failure?.let { throw RuntimeException("$label: ffmpeg stopped taking frames (${it.message}); see build/recorder", it) }
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
            failure?.let { throw RuntimeException("$label: ffmpeg stopped taking frames (${it.message}); see build/recorder", it) }
        }
    }

    /** Collects what is still in flight, closes the pipe and waits for ffmpeg to finish the file. */
    fun stop() {
        if (stopped) return
        stopped = true
        for (k in 0 until DEPTH) collect((slot + k) % DEPTH)
        filled.put(end)
        writer.join()
        runCatching { ffmpeg.outputStream.close() }
        val code = ffmpeg.waitFor()
        if (code != 0) println("$label: ffmpeg exited $code — see build/recorder/${output.nameWithoutExtension}.log")
        failure?.let { println("$label: the writer failed (${it.message})") }
        println("$label: $written frames at $frameRate fps → ${output.path}")
    }

    /** The pixel buffers back to the GPU; after [stop]. */
    fun destroy() {
        pbos.forEachIndexed { i, it -> if (it != 0) { glDeleteBuffers(it); pbos[i] = 0 } }
    }

    companion object {
        /** Frames in flight between asking for a read and collecting it. */
        const val DEPTH = 3

        /** Frames that may wait for ffmpeg before the draw loop does. */
        const val POOL = 6

        /**
         * OPENRNDR's GL colour buffer keeps its texture name in `texture`; the class is in the runtime-only gl3
         * module, so it is asked for by name rather than by type.
         */
        fun textureOf(buffer: ColorBuffer): Int = buffer.javaClass.getMethod("getTexture").invoke(buffer) as Int

        /** ScreenRecorder's own arguments from the pixel format on: H264Profile's defaults, word for word, at [crf] or x264's own. */
        fun h264(crf: Int?): List<String> = listOf(
            "-pix_fmt", "yuv420p",
            "-sws_flags", "spline+accurate_rnd+full_chroma_int",
            "-color_range", "1", "-colorspace", "1", "-color_primaries", "1", "-color_trc", "1",
            "-vf", "vflip,colorspace=bt709:iall=bt601-6-625:fast=1",
            "-vcodec", "libx264",
            *(crf?.let { arrayOf("-crf", "$it") } ?: emptyArray()),
        )
    }
}
