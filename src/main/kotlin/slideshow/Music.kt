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
 * which halves what four beds hold in memory — some 50 MB each rather than 100.
 *
 * The join is made once and kept; it is remade when a track is newer than it. No ffmpeg, or no
 * tracks, is an ordinary state: the [Sound] then names a file that is not there and the deck
 * runs silent for that wall, as it does for any missing cue.
 */
fun playlist(
    name: String, folder: File, tracks: List<String> = emptyList(),
    gain: Double = 0.45, fadeIn: Double = 6.0, fadeOut: Double = 2.5
): Sound {
    val out = File("build/music/$name.wav")
    val files = (folder.listFiles { f: File -> f.extension.lowercase() in setOf("mp3", "wav", "m4a", "aiff", "flac") } ?: emptyArray())
        .sortedBy { it.name }
    // The stated order first, matched on a substring of the file name; anything unnamed after, as sorted.
    val ordered = tracks.mapNotNull { t -> files.firstOrNull { it.name.contains(t, ignoreCase = true) } } +
            files.filter { f -> tracks.none { f.name.contains(it, ignoreCase = true) } }
    if (ordered.isEmpty()) {
        println("music: no tracks in ${folder.path} for \"$name\" — silent")
        return Sound(out, gain, loop = true, fadeIn = frames(fadeIn), fadeOut = frames(fadeOut))
    }
    val stale = !out.isFile || ordered.any { it.lastModified() > out.lastModified() }
    if (stale) {
        out.parentFile.mkdirs()
        val inputs = ordered.flatMap { listOf("-i", it.path) }
        val chain = ordered.indices.joinToString("") { "[$it:a]" } + "concat=n=${ordered.size}:v=0:a=1[a]"
        val command = listOf("ffmpeg", "-y", "-loglevel", "error") + inputs +
                listOf("-filter_complex", chain, "-map", "[a]", "-ac", "1", "-ar", "44100", "-c:a", "pcm_s16le", out.path)
        val code = runCatching { ProcessBuilder(command).inheritIO().start().waitFor() }.getOrElse { -1 }
        if (code != 0) println("music: ffmpeg could not join \"$name\" (exit $code) — silent")
        else println("music: \"$name\" is ${ordered.size} tracks: ${ordered.joinToString { it.nameWithoutExtension.removePrefix("ES_") }}")
    }
    return Sound(out, gain, loop = true, fadeIn = frames(fadeIn), fadeOut = frames(fadeOut))
}
