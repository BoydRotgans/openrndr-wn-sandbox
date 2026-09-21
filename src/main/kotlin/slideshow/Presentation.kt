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
 */
class Presentation(
    private val command: List<String>,
    private val environment: Map<String, String>,
    private val workingDir: File = File("."),
    private val onStopped: () -> Unit = {},
    /**
     * What the show is started with in projection mode: the window undecorated, at 1:1 and
     * placed across the projectors. Asked for at each start, so a projector plugged in after
     * the launcher came up is found.
     */
    private val projectionEnvironment: () -> Map<String, String> = { emptyMap() },
    /** Whether the next start goes onto the projectors. The page's toggle. */
    @Volatile var projection: Boolean = false,
    /** Whether the show starts muted: the page's sound button, kept across starts. */
    @Volatile var muted: Boolean = false
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

    /** Starts the show. False when one is already running. */
    @Synchronized
    fun start(extra: Map<String, String> = emptyMap()): Boolean {
        if (running) return false
        port = null
        val placed = if (projection) projectionEnvironment() else emptyMap()
        val child = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .also { it.environment().putAll(environment + placed + ("SLIDES_MUTED" to "$muted") + extra) }
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
        val child = process ?: return
        child.descendants().forEach { it.destroy() }
        child.destroy()
    }

    companion object {
        private val READY = Regex("""organizer: http://localhost:(\d+)/""")
    }
}
