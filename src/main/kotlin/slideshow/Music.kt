package slideshow

import java.io.File

/**
 * A course's tracks as one bed: the files of [folder], in the order [tracks] names them, joined
 * end to end into a single wav under `build/music/` and returned as a looping [Sound].
 *
 * **Joined rather than sequenced, because the engine plays buffers.** [Speakers] decodes whole
 * files at load and fires them with one call; a playlist that started the next track when the
 * last ended would need the driver to watch the source, which nothing here does. One long file
 * looping is the same music with no machinery. **It goes through ffmpeg because the tracks are
 * mp3** and the JDK decodes wav only; the join is also where they come down to mono at 44.1 kHz,
 * which halves what a bed holds in memory — some 5 MB a minute rather than 10. The tracks are
 * joined as delivered, at their own levels, like every sound here.
 *
 * **A playlist picks up where it stopped** ([Sound.resume]): Main plays under three courses, and
 * starting it from the top each time would play its first tracks to the room three times over.
 *
 * The join is made once and kept beside a list of what went into it (`<name>.txt`), and is made
 * again when that list changes — a track added, taken out, reordered or re-delivered — rather than
 * only when a track is newer than the join, which a re-delivered folder of older files would pass.
 * No ffmpeg, or no tracks, is an ordinary state: the [Sound] then names a file that is not there
 * and the deck runs silent for that wall, as it does for any missing cue.
 */
fun playlist(
    name: String, folder: File, tracks: List<String> = emptyList(),
    gain: Double = 0.45, fadeIn: Double = 6.0, fadeOut: Double = 2.5
): Sound {
    val out = File("build/music/$name.wav")
    val sound = Sound(out, gain, loop = true, fadeIn = frames(fadeIn), fadeOut = frames(fadeOut), layer = Layer.MUSIC, resume = true)
    val files = (folder.listFiles { f: File -> f.extension.lowercase() in setOf("mp3", "wav", "m4a", "aiff", "flac") } ?: emptyArray())
        .sortedBy { it.name.lowercase() }
    // The stated order first, matched on a substring of the file name; anything unnamed after, as sorted.
    val ordered = tracks.mapNotNull { t -> files.firstOrNull { it.name.contains(t, ignoreCase = true) } }.distinct() +
            files.filter { f -> tracks.none { f.name.contains(it, ignoreCase = true) } }
    tracks.filter { t -> files.none { it.name.contains(t, ignoreCase = true) } }
        .forEach { println("music: \"$it\" is on the tracklist for \"$name\" and not in ${folder.path}") }
    if (ordered.isEmpty()) {
        println("music: no tracks in ${folder.path} for \"$name\" — silent")
        return sound
    }
    val made = File(out.parentFile, "$name.txt")
    val recipe = ordered.joinToString("\n") { "${it.path}\t${it.length()}\t${it.lastModified()}" }
    val stale = !out.isFile || !made.isFile || made.readText() != recipe
    if (stale) {
        out.parentFile.mkdirs()
        made.delete()
        val inputs = ordered.flatMap { listOf("-i", it.path) }
        val chain = ordered.indices.joinToString("") { "[$it:a]" } + "concat=n=${ordered.size}:v=0:a=1[a]"
        val command = listOf("ffmpeg", "-y", "-loglevel", "error") + inputs +
                listOf("-filter_complex", chain, "-map", "[a]", "-ac", "1", "-ar", "44100", "-c:a", "pcm_s16le", out.path)
        val code = runCatching { ProcessBuilder(command).inheritIO().start().waitFor() }.getOrElse { -1 }
        if (code != 0) println("music: ffmpeg could not join \"$name\" (exit $code) — silent")
        else {
            made.writeText(recipe)
            println("music: \"$name\" is ${ordered.size} tracks: ${ordered.joinToString { it.nameWithoutExtension.removePrefix("ES_") }}")
        }
    }
    return sound
}

/**
 * The order the playlists are delivered in: the sound designers' tracklist, a section a course —
 * a line `Starter;` and then a track a line, indented — keyed by the course's name. Anything above
 * the first section (their mark, a heading) and the rules between sections are passed over. An
 * empty map where there is no file, so a folder without one plays its tracks as sorted.
 */
fun tracklist(file: File): Map<String, List<String>> {
    if (!file.isFile) return emptyMap()
    val courses = linkedMapOf<String, MutableList<String>>()
    var course: MutableList<String>? = null
    for (line in file.readLines()) {
        val text = line.trim()
        when {
            text.endsWith(";") && !line.first().isWhitespace() ->
                course = courses.getOrPut(text.removeSuffix(";").trim()) { mutableListOf() }
            course != null && text.isNotEmpty() && line.first().isWhitespace() -> course += text
        }
    }
    return courses
}

/**
 * A course's folder among the delivered playlists: the one named [course], or named after it with
 * a note — the release of 1 October delivered `Aperitif (_)`. Null where there is none.
 */
fun courseFolder(root: File, course: String): File? =
    root.listFiles { f: File -> f.isDirectory }?.sortedBy { it.name }
        ?.let { dirs -> dirs.firstOrNull { it.name.equals(course, ignoreCase = true) } ?: dirs.firstOrNull { it.name.startsWith("$course ", ignoreCase = true) } }

/**
 * One course's playlist out of the folder of playlists [root]: its folder (see [courseFolder]), in the
 * order its `*tracklist*.txt` gives, joined under `build/music/<name>.wav`.
 */
fun coursePlaylist(root: File, course: String, name: String = course.lowercase(), gain: Double = 0.45): Sound {
    val list = root.listFiles { f: File -> f.isFile && f.name.contains("tracklist", ignoreCase = true) && f.extension == "txt" }
        ?.firstOrNull()?.let(::tracklist).orEmpty()
    val tracks = list.entries.firstOrNull { it.key.equals(course, ignoreCase = true) }?.value.orEmpty()
    return playlist(name, courseFolder(root, course) ?: File(root, course), tracks, gain)
}

/**
 * One cue cut out of stems that were delivered on a **shared timeline**: every file in [stems]
 * mixed at its own level, and the stretch from [from] to [to] seconds kept, into `build/sounds/`.
 *
 * The project highlight's sounds come this way — four wavs of 13.4 s, each silent but for its own
 * part, scored against a filmed highlight: the opening's two at 0 s and the click's two at 6.30 s,
 * which is the frame the auto cues click on (2.8 s of opening and a 3.5 s hold). The engine plays
 * one cue a state, so the stems of a state are summed here; and cutting at the frame the state
 * starts on is what lands each stem where the designer put it. Summed, never normalised — the
 * rule every cue here keeps.
 *
 * Made once and remade when a stem is newer. A missing stem or no ffmpeg leaves a [Sound] naming
 * a file that is not there, and the state runs silent, as for any missing cue.
 */
fun stemCue(name: String, stems: List<File>, from: Double, to: Double, gain: Double = 1.0): Sound {
    val out = File("build/sounds/$name.wav")
    val found = stems.filter { it.isFile }
    if (found.size < stems.size) println("sound: ${stems.filterNot { it.isFile }.joinToString { it.path }} missing for \"$name\"")
    val stale = found.isNotEmpty() && (!out.isFile || found.any { it.lastModified() > out.lastModified() })
    if (stale) {
        out.parentFile.mkdirs()
        val inputs = found.flatMap { listOf("-i", it.path) }
        val chain = found.indices.joinToString("") { "[$it:a]" } +
                "amix=inputs=${found.size}:normalize=0,atrim=start=$from:end=$to,asetpts=PTS-STARTPTS[a]"
        val command = listOf("ffmpeg", "-y", "-loglevel", "error") + inputs +
                listOf("-filter_complex", chain, "-map", "[a]", "-c:a", "pcm_s24le", out.path)
        val code = runCatching { ProcessBuilder(command).inheritIO().start().waitFor() }.getOrElse { -1 }
        if (code != 0) println("sound: ffmpeg could not mix \"$name\" (exit $code) — silent")
    }
    return Sound(out, gain)
}
