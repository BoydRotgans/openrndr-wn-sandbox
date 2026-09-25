// ============================================================================ //
//  No `package` declaration: it reads .env through Env and the show through
//  Slideshow.kt, both of which are in the default package.
// ============================================================================ //

import slideshow.Pace
import slideshow.Pieces
import slideshow.SubtitleTrack
import slideshow.Subtitles
import slideshow.VoiceTrack
import slideshow.arrangedFromFile
import slideshow.autoCues
import slideshow.drawers.Type
import slideshow.seconds
import slideshow.startIndex
import slideshow.untilIndex
import slideshow.withModules
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Films the show in pieces, several at once, and joins them into the film one run would have made:
 * the same `SLIDES_*` settings a filmed run takes, and three of its own.
 *
 *     SLIDES_SUBTITLE_MODE=true SLIDES_CUES=auto SLIDES_VOICE_ON=true SLIDES_FPS=30 \
 *         SLIDES_VIDEO=video/wn-experience.mp4 FILM_PIECES=4 FILM_PARALLEL=2 \
 *         ./gradlew run -Popenrndr.application=FilmKt
 *
 * `FILM_PIECES` is how many pieces, cut at the walls between the chapters as near an even share of
 * the film as they allow (`FILM_SPLIT=14,24,34` names the slides the pieces open on instead, counting
 * from 1 as `SLIDES_START` does); `FILM_PARALLEL` how many are filmed at once. Each piece is the show
 * on this JVM's own java and classpath, as the organizer starts one — a process of its own, since the
 * window has to own its JVM's first thread — muted, so four shows filming at once make no noise.
 * What comes out is the film, its `.cues`, `.states`, `.timing`, the soundtrack and the mix, exactly
 * as a single `SLIDES_RECORD` run leaves them; the pieces are deleted once joined unless
 * `FILM_KEEP_PIECES` is set. See [Pieces] for why the join is exact.
 */
fun main() {
    Type.file = textFont
    val show = show.withEnv().withModules().arrangedFromFile()
    val settings = show.settings
    val slides = show.slides
    val ids = show.slideIds
    val start = startIndex(slides, settings.start)
    val until = untilIndex(slides, settings.until, start)
    val subtitles = if (settings.subtitleMode || settings.voiceOn) Subtitles.readOrEmpty(
        if (settings.subtitleTrack == SubtitleTrack.EXTENDED) settings.subtitlesExtended ?: "show-subtitles-extended.json"
        else settings.subtitles ?: "show-subtitles.json"
    ) else null
    val voice = if (settings.voiceOn) VoiceTrack.read(settings.voice, settings.subtitleTrack, settings.voiceGain) else null
    val holds = autoCues(slides, ids, start, until, settings, subtitles, Pace(settings.subtitleCps), voice)
    val perSlide = MutableList(slides.size) { 0.0 }
    var k = 0
    for (s in start..until) repeat(slides[s].steps) { perSlide[s] += seconds(holds[k++]) }

    val count = Env["FILM_PIECES"]?.toIntOrNull() ?: 4
    val parallel = (Env["FILM_PARALLEL"]?.toIntOrNull() ?: 2).coerceAtLeast(1)
    val stated = Env["FILM_SPLIT"]?.split(',')?.mapNotNull { it.trim().toIntOrNull()?.minus(1) }
        ?.filter { it in start + 1..until }?.distinct()?.sorted()?.takeIf { it.isNotEmpty() }
    val cuts = stated ?: Pieces.cuts(slides, perSlide, count, start, until)
    val bounds = listOf(start) + cuts + (until + 1)

    val video = File(settings.video)
    val dir = File(video.parentFile, video.nameWithoutExtension + ".pieces").also { it.mkdirs() }
    data class Piece(val n: Int, val first: Int, val last: Int, val file: File) {
        val seconds: Double get() = (first..last).sumOf { perSlide[it] }
        val log: File get() = File(file.parentFile, file.nameWithoutExtension + ".log")
    }
    val pieces = bounds.zipWithNext().mapIndexed { i, (a, b) ->
        Piece(i + 1, a, b - 1, File(dir, "${video.nameWithoutExtension}-p${i + 1}.${video.extension}"))
    }
    println("film: %d pieces, %d at a time, %d fps → %s".format(pieces.size, parallel, settings.fps, video.path))
    pieces.forEach { p ->
        println("  p%d  slides %2d–%2d  %-34s %5.0fs".format(p.n, p.first + 1, p.last + 1,
            "${ids.getOrElse(p.first) { "" }} … ${ids.getOrElse(p.last) { "" }}", p.seconds))
    }
    if (!settings.cuesAuto) println("film: SLIDES_CUES is not auto — every piece is filmed on the auto cues, which is what a hands-off film is")

    val command = showCommand()
    val began = System.currentTimeMillis()
    val pool = Executors.newFixedThreadPool(parallel)
    val results = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    // Longest first, so the last piece to finish is a short one.
    pieces.sortedByDescending { it.seconds }.forEach { p ->
        pool.submit {
            val t0 = System.currentTimeMillis()
            println("film: p${p.n} starting")
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(p.log)
                .also {
                    it.environment().putAll(
                        mapOf(
                            "SLIDES_START" to "${p.first + 1}", "SLIDES_UNTIL" to "${p.last + 1}",
                            "SLIDES_VIDEO" to p.file.path, "SLIDES_RECORD" to "true", "SLIDES_CUES" to "auto",
                            "SLIDES_MUTED" to "true", "SLIDES_MIX" to "false", "SLIDES_ORGANIZER" to "false",
                            "SLIDES_STILLS" to "false", "SLIDES_BENCH" to "false", "SLIDES_DURATION" to ""
                        )
                    )
                }
                .start()
            val code = process.waitFor()
            results[p.n] = code
            println("film: p%d %s in %.1f min".format(p.n, if (code == 0) "done" else "FAILED ($code), see ${p.log.path}",
                (System.currentTimeMillis() - t0) / 60000.0))
        }
    }
    pool.shutdown()
    pool.awaitTermination(7, TimeUnit.DAYS)
    val filmed = (System.currentTimeMillis() - began) / 60000.0
    if (pieces.any { results[it.n] != 0 || !it.file.isFile }) {
        println("film: not joined — a piece failed; the pieces are in ${dir.path}")
        return
    }
    println("film: all pieces filmed in %.1f min; joining".format(filmed))
    if (Pieces.join(pieces.map { it.file }, video, settings.fps, settings.mix)) {
        if (!Env.boolean("FILM_KEEP_PIECES")) pieces.forEach { it.file.delete() }
        println("film: done in %.1f min".format((System.currentTimeMillis() - began) / 60000.0))
    }
}

/**
 * The show's own main on this JVM's java, classpath and flags, the organizer's way of starting one:
 * seconds rather than a second Gradle run, which would also fight this one for the build.
 */
private fun showCommand(): List<String> {
    val java = ProcessHandle.current().info().command().orElse("java")
    val flags = ManagementFactory.getRuntimeMXBean().inputArguments
        .filterNot { it.startsWith("-agentlib") || it.startsWith("-javaagent") }
    val mac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
    val firstThread = if (mac && "-XstartOnFirstThread" !in flags) listOf("-XstartOnFirstThread") else emptyList()
    return listOf(java) + flags + firstThread + listOf("-cp", System.getProperty("java.class.path"), "SlideshowKt")
}
