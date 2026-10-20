// No package: it reads `show` and `withEnv` out of Slideshow.kt, which is in the default
// package for the reason given there, and Kotlin cannot import from the default package.

import slideshow.SubtitleTrack
import slideshow.subtitlesFile
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
 * the code as it stands: each start builds the project and runs the show on that build
 * ([buildForShow]), so a change to a slide is on the wall at the next ▶ start. A change to
 * the launcher itself (this file, [Remote]) still needs it restarted. While the show is up the
 * page steers it through here; when its window closes the page stays, reading the files the
 * show saved.
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
        prepare = if (Env.boolean("SLIDES_ORGANIZER_REBUILD", true)) ::buildForShow else null,
        projectionEnvironment = { projectionEnvironment(settings.width, settings.height) },
        projection = Env.boolean("SLIDES_PROJECTION"),
        muted = settings.muted,
        practice = settings.practice,
        waitForAnimation = settings.waitForAnimation,
        autocue = settings.autocue,
        subtitles = settings.subtitleMode,
        subtitleTrack = settings.subtitleTrack,
        voice = settings.voiceOn,
        autoplay = settings.autoplay,
        autocueCps = settings.autocueCps,
        speakers = settings.speakers
    ).also { p -> settings.levels.forEach { (layer, gain) -> p.mix[layer] = gain } }
    remote = Remote(
        declared, File(settings.order ?: "show-order.json"), settings.organizerPort, File("build/previews"),
        File(settings.modules ?: "show-modules.json"), references,
        File(settings.intents ?: "show-intents.json"),
        File(settings.feedback ?: "show-feedback.json"),
        File(settings.midi ?: "show-midi.json"),
        subtitlesFile = File(settings.subtitles ?: "show-subtitles.json"),
        subtitlesExtendedFile = File(settings.subtitlesFile(SubtitleTrack.EXTENDED)),
        subtitlesAlignedFile = File(settings.subtitlesFile(SubtitleTrack.ALIGNED)),
        subtitlesErikFile = File(settings.subtitlesFile(SubtitleTrack.ERIK)),
        meetingFile = File(settings.meeting ?: "show-meeting-tasks.json"),
        draaiboekFile = settings.draaiboek?.let { File(it) },
        draaiboekTab = settings.draaiboekTab,
        subtitleFeedbackFile = File(settings.subtitleFeedback ?: "show-subtitle-feedback.json"),
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
 * Where, in screen points: the desktop place set in the Projection tab (see [slideshow.Projectors]),
 * else `SLIDES_PROJECTION_X/_Y`. With neither: with three screens besides the laptop's own, the first
 * two, the third being the speaker's autocue; with fewer, the rightmost two, which is where a wall
 * plugged in beside a laptop ends up ([slideshow.Projectors.wallScreens]). Where macOS will not list
 * its displays, the right edge of the whole desktop less the two projectors' width, read off Finder.
 */
private fun projectionEnvironment(canvasWidth: Int, canvasHeight: Int): Map<String, String> {
    val setup = slideshow.Projectors.read(slideshow.Projectors.plain(canvasWidth, canvasHeight))
    val envX = Env["SLIDES_PROJECTION_X"]?.toIntOrNull()
    val auto = if (setup.desktopX == null && envX == null) slideshow.Projectors.autoPlace(setup) else null
    val x = setup.desktopX ?: envX ?: auto?.first ?: desktopRight()?.let { it - setup.width } ?: 0
    val y = setup.desktopY ?: Env["SLIDES_PROJECTION_Y"]?.toIntOrNull() ?: auto?.second ?: 0
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

/**
 * Builds the code as it stands and hands back [base] with its class folders swapped for a copy of that build, so the
 * show starts on today's code rather than on the launcher's snapshot from when it came up.
 *
 * **A copy, not Gradle's own output**: a build made while the show runs would rewrite the class files under it, and a
 * JVM loads classes as they are first used, so a slide reached later would load the new class beside the old ones it
 * was built against. `build/show-cp/` is written only while no show runs, since a start is refused while one does.
 *
 * **A build that fails does not stop the show.** It starts on the last copy that built, and the page is told why, the
 * first compile error included; with no copy yet, on the launcher's own classes. Run on Gradle's own output (`./gradlew
 * run`), the launcher's classes are what a build would write, so it builds nothing and starts as it always did.
 */
private fun buildForShow(base: List<String>): Presentation.Prepared {
    val root = File(".").absoluteFile.normalize()
    val at = base.indexOf("-cp")
    if (at < 0) return Presentation.Prepared(base)
    val entries = base[at + 1].split(File.pathSeparator)
    val gradleOutput = File(root, "build/classes").path
    val gradlew = File(root, "gradlew")
    if (!gradlew.canExecute() || entries.any { File(it).absoluteFile.normalize().path.startsWith(gradleOutput) })
        return Presentation.Prepared(base)

    val target = File(root, "build/show-cp")
    target.mkdirs()
    val log = File(target, "build.log")
    println("presentation: building the code as it stands")
    val started = System.nanoTime()
    val build = ProcessBuilder(gradlew.path, "classes", "-q", "--console=plain")
        .directory(root).redirectErrorStream(true).redirectOutput(log).start()
    val done = build.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)
    if (!done) build.destroyForcibly()
    val folders = listOf("kotlin" to "build/classes/kotlin/main", "java" to "build/classes/java/main", "resources" to "build/resources/main")
    // The class folders swapped for the copy's, the jars as they were.
    fun onCopy(): List<String> {
        val classpath = folders.map { (name, _) -> File(target, name).path } + entries.filterNot { File(it).isDirectory }
        return base.toMutableList().also { it[at + 1] = classpath.joinToString(File.pathSeparator) }
    }
    if (!done || build.exitValue() != 0) {
        val error = log.takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith("e: ") }
            ?.replace(Regex("""^e: file://\S*?/src/main/kotlin/"""), "")
        val why = if (!done) "the build took over ten minutes" else error ?: "see build/show-cp/build.log"
        val copied = File(target, "kotlin").isDirectory
        return Presentation.Prepared(
            if (copied) onCopy() else base,
            "the code does not build (${why.take(300)}), so the show starts on " +
                    (if (copied) "the last code that built" else "the launcher's own classes")
        )
    }
    for ((name, from) in folders) {
        val copy = File(target, name)
        copy.deleteRecursively()
        val source = File(root, from)
        if (source.isDirectory) source.copyRecursively(copy, overwrite = true) else copy.mkdirs()
    }
    println("presentation: built and copied in %.1f s".format((System.nanoTime() - started) / 1e9))
    return Presentation.Prepared(onCopy())
}
