package slideshow

import java.io.File

/**
 * A film made in pieces, several at once, and joined into the one film a single run would have
 * made.
 *
 * **It can be, because the deck is a pure function of its frame.** A piece is the show opened on a
 * slide with `SLIDES_START` and run hands-off to `SLIDES_UNTIL`, which stops one hold after its
 * last click — exactly where the next click would have fallen. So the next piece, opened on the
 * slide that click would have brought up, starts on the frame the first one left off, and nothing
 * on screen can tell a join from a click. Pieces are cut at the walls between the chapters
 * ([cuts]), where nothing carries across: no card is on the wall, and a wall's own clock starts
 * when it arrives either way.
 *
 * **The film is joined without being encoded again** — every piece comes out of the same recorder
 * with the same settings, so ffmpeg's concat takes them as they are.
 *
 * **The logs are joined with the frames moved along**, the cue log and the state log alike, each
 * by the length of the pieces before it as they came out of the recorder. One thing a join has to
 * add: a single run lets go of the sustained sounds of the slide it leaves on the click that leaves
 * it, and a piece ends before that click. So every sustained sound still held at the end of a piece
 * is released on the first frame of the next — unless that piece opens on the same file, which a
 * single run would have kept playing rather than dipped (the rule in the driver's own release).
 * The soundtrack is then rendered once, over the whole joined log.
 */
object Pieces {

    /**
     * Where to cut [slides] into [count] pieces, as the index each piece after the first opens on.
     *
     * Only at a wall that stands on its own — a backdrop, never a slide beside its card or a scene
     * that carries one — and as near as the walls allow to an even share of the film, which
     * [seconds] gives a slide at a time.
     */
    fun cuts(slides: List<Slide>, seconds: List<Double>, count: Int, from: Int = 0, until: Int = slides.lastIndex): List<Int> {
        if (count <= 1) return emptyList()
        val candidates = (from + 1..until).filter { slides[it].wide && !slides[it].carriesCard }
        val total = (from..until).sumOf { seconds[it] }
        val startsAt = HashMap<Int, Double>()
        var t = 0.0
        for (i in from..until) { startsAt[i] = t; t += seconds[i] }
        val chosen = mutableListOf<Int>()
        for (k in 1 until count) {
            val target = total * k / count
            val best = candidates.filter { c -> chosen.all { c > it } }
                .minByOrNull { kotlin.math.abs(startsAt[it]!! - target) } ?: break
            chosen += best
        }
        return chosen.distinct().sorted()
    }

    /** How many frames a film holds, off its packets — exact, and quick. */
    fun frameCount(video: File): Int? = runCatching {
        ProcessBuilder(
            WallRecorder.ffmpegPath().replace("ffmpeg", "ffprobe"), "-v", "error", "-select_streams", "v:0", "-count_packets",
            "-show_entries", "stream=nb_read_packets", "-of", "csv=p=0", video.path
        ).redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim().toInt()
    }.getOrNull()

    /**
     * Joins [pieces] — films made at [fps], each with its `.cues` and `.states` beside it — into
     * [out], with its own logs beside it and the soundtrack rendered and mixed. False, and says why,
     * if a piece is missing or will not join.
     */
    fun join(pieces: List<File>, out: File, fps: Int, mix: Boolean = true): Boolean {
        val stride = FPS / fps
        require(stride * fps == FPS) { "pieces: a film at $fps fps does not step the deck a whole frame at a time" }

        // Each piece's length in deck frames, off the film itself: what the recorder wrote, which
        // is one draw past the last frame the log counts — the draw that asked to quit.
        val lengths = pieces.map { piece ->
            val n = frameCount(piece) ?: run { println("pieces: cannot read ${piece.path}"); return false }
            n * stride
        }
        val offsets = lengths.runningFold(0) { a, b -> a + b }
        val total = offsets.last()

        // --- the film ------------------------------------------------------------------ //
        out.absoluteFile.parentFile?.mkdirs()
        val list = File(out.parentFile, out.nameWithoutExtension + ".pieces.txt")
        list.writeText(pieces.joinToString("\n") { "file '${it.absolutePath.replace("'", "'\\''")}'" } + "\n")
        val concat = ProcessBuilder(
            WallRecorder.ffmpegPath(), "-y", "-loglevel", "error", "-f", "concat", "-safe", "0",
            "-i", list.path, "-c", "copy", out.path
        ).inheritIO().start().waitFor()
        list.delete()
        if (concat != 0) { println("pieces: ffmpeg could not join the pieces"); return false }

        // --- the cue log --------------------------------------------------------------- //
        val runs = pieces.map { Soundtrack.readLog(Soundtrack.logFile(it)) }
        val cues = mutableListOf<Speakers.Cue>()
        var added = 0
        for ((k, run) in runs.withIndex()) {
            val at = offsets[k]
            run?.cues?.forEach { cues += Speakers.Cue(it.frame + at, it.sound, it.release) }
            // What this piece still holds as it ends, and is let go on the next piece's first frame.
            val next = runs.getOrNull(k + 1) ?: continue
            val held = LinkedHashMap<String, Speakers.Cue>()
            run?.cues?.forEach { cue ->
                if (!cue.sound.sustained) return@forEach
                if (cue.release) held.remove(cue.sound.file.path) else held[cue.sound.file.path] = cue
            }
            val opensOn = next.cues.filter { it.frame == 0 && !it.release }.mapTo(HashSet()) { it.sound.file.path }
            held.values.filter { it.sound.file.path !in opensOn }.forEach {
                cues += Speakers.Cue(offsets[k + 1], it.sound, release = true)
                added++
            }
        }
        // in frame order, keeping the order they were fired in within a frame
        val ordered = cues.withIndex().sortedWith(compareBy({ it.value.frame }, { it.index })).map { it.value }
        val levels = runs.firstNotNullOfOrNull { it }?.levels.orEmpty()
        Soundtrack.writeLog(ordered, total, Soundtrack.logFile(out), levels)

        // --- the state log ------------------------------------------------------------- //
        val states = mutableListOf<String>()
        for ((k, piece) in pieces.withIndex()) {
            val file = StatesLog.file(piece)
            if (!file.isFile) { println("pieces: no state log beside ${piece.path}"); continue }
            file.readLines().drop(1).filter { it.isNotBlank() }.forEach { line ->
                val t = line.split("\t", limit = 2)
                states += "${t[0].toInt() + offsets[k]}\t${t.getOrElse(1) { "" }}"
            }
        }
        StatesLog.file(out).printWriter().use { w ->
            w.println("frames $total fps $FPS")
            states.forEach { w.println(it) }
        }

        // --- where the time went -------------------------------------------------------- //
        val timing = LinkedHashMap<String, Pair<Int, Double>>()
        pieces.forEach { piece ->
            val file = DrawTimes.file(piece)
            if (file.isFile) file.readLines().drop(1).forEach { line ->
                val t = line.split("\t")
                if (t.size >= 3) timing.merge(t[0], t[1].toInt() to t[2].toDouble()) { a, b -> (a.first + b.first) to (a.second + b.second) }
            }
        }
        if (timing.isNotEmpty()) DrawTimes.file(out).printWriter().use { w ->
            w.println("slide\tdraws\tseconds\tms_per_draw")
            timing.forEach { (id, v) -> w.println("%s\t%d\t%.1f\t%.1f".format(id, v.first, v.second, v.second * 1000 / v.first.coerceAtLeast(1))) }
        }

        println(
            "pieces: %d joined into %s — %.1fs, %d cues (%d let go at a join), %d states".format(
                pieces.size, out.path, total / FPS.toDouble(), ordered.size, added, states.size
            )
        )
        // The soundtrack, over the whole joined log, at the mix the pieces were played at.
        Soundtrack.remix(out, mix)
        return true
    }
}
