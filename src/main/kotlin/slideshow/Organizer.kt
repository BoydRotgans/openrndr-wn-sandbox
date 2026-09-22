// No package: it reads `show` and `withEnv` out of Slideshow.kt, which is in the default
// package for the reason given there, and Kotlin cannot import from the default package.

import slideshow.Presentation
import slideshow.References
import slideshow.Remote
import slideshow.arrangedFromFile
import slideshow.withModules
import java.io.File
import java.lang.management.ManagementFactory
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch

/**
 * The organizer on its own, with no show window: the page to arrange the running order, write
 * notes and browse the sketches, and a button on it that starts the presentation.
 *
 *     ./gradlew run -Popenrndr.application=OrganizerKt
 *
 * The show is started as a process of its own — see [Presentation] for why not a thread — on
 * the classes this launcher was started with, so a change to a slide needs the launcher
 * restarted to be picked up. While the show is up the page steers it through here; when its
 * window closes the page stays, reading the files the show saved.
 */
fun main() {
    // Building the show touches AWT (the shadow wall's masks), and this JVM never opens a window.
    System.setProperty("java.awt.headless", "true")

    val declared = show.withEnv().withModules().arrangedFromFile()
    val settings = declared.settings
    val references = References.read(File(settings.references ?: "export/references"), quiet = true)

    lateinit var remote: Remote
    val presentation = Presentation(
        command = showCommand(),
        environment = mapOf(
            "SLIDES_ORGANIZER" to "true",
            "SLIDES_ORGANIZER_PORT" to "${freePort()}",
            "SLIDES_ORGANIZER_OPEN" to "false",
            // A run that films or writes stills turns its organizer off, and would never come up here.
            "SLIDES_RECORD" to "false",
            "SLIDES_STILLS" to "false"
        ),
        onStopped = { remote.reload() },
        projectionEnvironment = { projectionEnvironment(settings.width) },
        projection = Env.boolean("SLIDES_PROJECTION"),
        muted = settings.muted,
        subtitles = settings.subtitleMode,
        subtitleTrack = settings.subtitleTrack,
        voice = settings.voiceOn
    ).also { p -> settings.levels.forEach { (layer, gain) -> p.mix[layer] = gain } }
    remote = Remote(
        declared, File(settings.order ?: "show-order.json"), settings.organizerPort, File("build/previews"),
        File(settings.modules ?: "show-modules.json"), references,
        File(settings.intents ?: "show-intents.json"),
        File(settings.feedback ?: "show-feedback.json"),
        File(settings.midi ?: "show-midi.json"),
        subtitlesFile = File(settings.subtitles ?: "show-subtitles.json"),
        subtitlesExtendedFile = File(settings.subtitlesExtended ?: "show-subtitles-extended.json"),
        voiceDir = settings.voice?.let { File(it) },
        open = settings.organizerOpen,
        presentation = presentation
    )
    if (!remote.start()) return
    Runtime.getRuntime().addShutdownHook(Thread { presentation.stop() })
    println("organizer: the presentation is started from the page")
    CountDownLatch(1).await()
}

/**
 * The show's own main, run on this JVM's java, classpath and flags — so it starts in seconds
 * rather than through a second Gradle run, which would also fight this one for the build.
 */
private fun showCommand(): List<String> {
    val java = ProcessHandle.current().info().command().orElse("java")
    val flags = ManagementFactory.getRuntimeMXBean().inputArguments
        .filterNot { it.startsWith("-agentlib") || it.startsWith("-javaagent") }
    val mac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
    // On the first thread from the start, so OPENRNDR need not restart itself to get there.
    val firstThread = if (mac && "-XstartOnFirstThread" !in flags) listOf("-XstartOnFirstThread") else emptyList()
    return listOf(java) + flags + firstThread + listOf("-cp", System.getProperty("java.class.path"), "SlideshowKt")
}

/**
 * The show on the wall: undecorated, at 1:1 and placed across the projectors — the keys the
 * show already reads for that, see `SLIDES_UNDECORATED` in `.env.example`.
 *
 * `SLIDES_PROJECTION_X/_Y` say where, in screen points. With X left empty the projectors are
 * taken to be the rightmost displays, top aligned: the right edge of the whole desktop less the
 * canvas width, read off Finder, which is where a wall plugged in beside a laptop ends up.
 */
private fun projectionEnvironment(canvasWidth: Int): Map<String, String> {
    val x = Env["SLIDES_PROJECTION_X"]?.toIntOrNull() ?: desktopRight()?.let { it - canvasWidth } ?: 0
    val y = Env["SLIDES_PROJECTION_Y"]?.toIntOrNull() ?: 0
    return mapOf(
        "SLIDES_UNDECORATED" to "true",
        "SLIDES_WINDOW_SCALE" to "1.0",
        "SLIDES_WINDOW_X" to "$x",
        "SLIDES_WINDOW_Y" to "$y"
    )
}

/** The right edge of every display together, in screen points; null off a Mac or when Finder will not say. */
private fun desktopRight(): Int? = runCatching {
    val bounds = ProcessBuilder("osascript", "-e", "tell application \"Finder\" to get bounds of window of desktop")
        .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
    bounds.split(",").map { it.trim().toInt() }[2]
}.getOrNull()

private fun freePort(): Int = ServerSocket(0).use { it.localPort }
