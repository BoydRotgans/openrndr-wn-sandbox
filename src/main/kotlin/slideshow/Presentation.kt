package slideshow

import java.io.File

/**
 * The show as a process of its own, started and stopped from the organizer's page.
 *
 * **A process and not a thread**, for two reasons that both come from OPENRNDR. The window has
 * to own the JVM's main thread on macOS, and a program is one `application {}` a JVM — when the
 * window closes the JVM goes with it. A show on a thread of the launcher would take the page
 * down as it closed and could never be started a second time; as a child process it comes and
 * goes and the page stays up.
 *
 * The child runs its own [Remote] on [port], with the page's browser tab turned off, and the
 * launcher's [Remote] forwards to it while it is up. [port] stays null until the child has said
 * its organizer is listening — which is only after every slide is loaded — so [starting] is the
 * show loading and [live] is the show steerable.
 *
 * **Each start runs the code as it stands** (20 October 2026). [prepare], where the launcher hands one in, builds
 * the project and gives back the command to start on that build, off the request's thread; [building] is that
 * step. The launcher's own classes are a snapshot taken when it came up, so without it a slide changed since then
 * played as it was until the launcher was restarted — and nothing on the page said so.
 */
class Presentation(
    private val command: List<String>,
    private val environment: Map<String, String>,
    private val workingDir: File = File("."),
    private val onStopped: () -> Unit = {},
    /**
     * Run before each start: builds the code as it now stands and hands back the command to start the show with,
     * and a line for the page when it could not use that build. Null starts [command] as it is.
     */
    private val prepare: ((List<String>) -> Prepared)? = null,
    /**
     * What the show is started with in projection mode: the window undecorated, at 1:1 and
     * placed across the projectors. Asked for at each start, so a projector plugged in after
     * the launcher came up is found.
     */
    private val projectionEnvironment: () -> Map<String, String> = { emptyMap() },
    /** Whether the next start goes onto the projectors. The page's toggle. */
    @Volatile var projection: Boolean = false,
    /** Whether the show starts muted: the page's sound button, kept across starts. */
    @Volatile var muted: Boolean = false,
    /** Whether the clicker's special buttons work: the page's practice mode button, kept across starts. */
    @Volatile var practice: Boolean = false,
    /** Whether a forward click waits for the state to finish moving: the page's wait button, kept across starts. */
    @Volatile var waitForAnimation: Boolean = false,
    /** Whether the speaker's autocue is on: the page's autocue button, kept across starts. */
    @Volatile var autocue: Boolean = false,
    /** Whether the show starts in subtitle mode: the page's subtitles button, kept across starts. */
    @Volatile var subtitles: Boolean = false,
    /** Which subtitle track the show starts on: the page's voice-over selector, kept across starts. */
    @Volatile var subtitleTrack: SubtitleTrack = SubtitleTrack.DEFAULT,
    /** Whether the show starts with the voice spoken: the page's voice button, kept across starts. */
    @Volatile var voice: Boolean = false,
    /** Whether the presented run clicks on by itself or waits for a click: the page's autoplay button, kept across starts. */
    @Volatile var autoplay: Boolean = true,
    /** How fast the autocue reads a line where no voice times it, in characters a second: the page's pace slider, kept across starts. */
    @Volatile var autocueCps: Double = 13.0,
    /**
     * Whose lines are on the autocue besides Erik's, for the next start: the page's speakers, kept across starts and
     * handed over as `SLIDES_SPEAKERS`. It shapes the show as it is built — the clicks a speaker's lines are stepped
     * through — so it takes effect at the next start, not on a show already up. See `speakersInShow` in Slideshow.kt.
     */
    @Volatile var speakers: Set<String> = emptySet(),
    /** The mix the show starts at: the page's faders, kept across starts. See [Layer]. */
    val mix: MutableMap<Layer, Double> = java.util.concurrent.ConcurrentHashMap(Layer.entries.associateWith { 1.0 })
) {
    @Volatile
    private var process: Process? = null

    /** The child's organizer port, once it is listening. */
    @Volatile
    var port: Int? = null
        private set

    val running: Boolean get() = process?.isAlive == true
    val starting: Boolean get() = running && port == null
    val live: Boolean get() = running && port != null

    /** The code is being built for a start that was asked for; the show is started once it is done. */
    @Volatile
    var building: Boolean = false
        private set

    /** What [prepare] said about the last start, when the show could not start on the code as it stands. */
    @Volatile
    var buildWarning: String? = null
        private set

    /** Who spoke besides Erik in the show now running, as it was started; null with none running. */
    @Volatile
    var startedSpeakers: Set<String>? = null
        private set

    /** A stop asked for while the code was building: the start it was building for is not made. */
    @Volatile
    private var cancelled = false

    /** What a start runs on: the command, and a line for the page when it is not the code as it stands. */
    data class Prepared(val command: List<String>, val warning: String? = null)

    /** Starts the show, building the code first where there is a [prepare]. False when one is already running. */
    @Synchronized
    fun start(extra: Map<String, String> = emptyMap()): Boolean {
        if (running || building) return false
        port = null
        val prep = prepare ?: return launch(command, extra)
        building = true
        cancelled = false
        Thread({
            val prepared = runCatching { prep(command) }.getOrElse { Prepared(command, "could not build the code (${it.message}); started on the launcher's own") }
            synchronized(this) {
                building = false
                buildWarning = prepared.warning
                prepared.warning?.let { println("presentation: $it") }
                if (cancelled) println("presentation: stopped before it started") else launch(prepared.command, extra)
            }
        }, "presentation-build").apply { isDaemon = true }.start()
        return true
    }

    private fun launch(command: List<String>, extra: Map<String, String>): Boolean {
        val placed = if (projection) projectionEnvironment() else emptyMap()
        val speaking = speakers
        startedSpeakers = speaking
        val child = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .also { it.environment().putAll(environment + placed + ("SLIDES_MUTED" to "$muted") + ("SLIDES_PRACTICE" to "$practice") + ("SLIDES_WAIT_FOR_ANIMATION" to "$waitForAnimation") + ("SLIDES_AUTOCUE" to "$autocue") + ("SLIDES_SUBTITLE_MODE" to "$subtitles") + ("SLIDES_SUBTITLE_TRACK" to subtitleTrack.key) + ("SLIDES_VOICE_ON" to "$voice") + ("SLIDES_AUTOPLAY" to "$autoplay") + ("SLIDES_AUTOCUE_CPS" to "$autocueCps") + ("SLIDES_SPEAKERS" to speaking.joinToString(",").ifEmpty { "none" }) + mix.entries.associate { (l, g) -> "SLIDES_MIX_${l.name}" to "$g" } + extra) }
            .start()
        process = child
        println("presentation: starting" + if (projection) " on the projectors (${placed["SLIDES_WINDOW_X"]}, ${placed["SLIDES_WINDOW_Y"]})" else " in a window")
        Thread({
            // The child's output goes on in this terminal, and its organizer's address is how
            // the launcher learns it can forward.
            // Stopping closes the stream under the reader, which throws; that is the end of the output.
            runCatching {
                child.inputStream.bufferedReader().forEachLine { line ->
                    println(line)
                    if (port == null) READY.find(line)?.let { port = it.groupValues[1].toInt() }
                }
            }
            val code = child.waitFor()
            if (process === child) {
                process = null
                port = null
            }
            println("presentation: stopped ($code)")
            onStopped()
        }, "presentation").apply { isDaemon = true }.start()
        return true
    }

    /** Stops the show, and anything it started — OPENRNDR may have restarted itself as a child. */
    fun stop() {
        if (building) cancelled = true
        val child = process ?: return
        println("presentation: asked to stop")
        child.descendants().forEach { it.destroy() }
        child.destroy()
    }

    companion object {
        private val READY = Regex("""organizer: http://localhost:(\d+)/""")
    }
}
