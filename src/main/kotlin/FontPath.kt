import java.io.File

/**
 * Turns a wanted font into a path `loadFont` will actually open.
 *
 * Three things get in the way of naming a system font directly, and all of them are the
 * reason this exists rather than a string in a sketch:
 *
 * - **The file may not be there.** A path off this machine is wrong on another one, and a
 *   missing font should not stop a sketch running — so an unusable one falls back to the
 *   bundled face and says so, rather than throwing.
 * - **A collection cannot be opened at all.** Most of this machine's fonts are `.ttc`
 *   holding four weights in one file, and stb_truetype fails outright on them — not with
 *   the wrong weight, with `failed to load font`. The wanted face has to be lifted out
 *   into a standalone font first, which is what [extractFontFace] does.
 * - **`loadFont` parses its argument as a URI**, which rejects a space — and nearly every
 *   font here lives at a path with one in it ("Georgia Bold.ttf"). Copying it somewhere
 *   plainer under `build/` is cheaper than giving up the font.
 *
 * `Objects.kt` has its own older copy of this for `OBJECTS_TEXT_FONT`; it predates this
 * file and could be moved onto it.
 */
fun usableFont(
    wanted: String,
    face: String? = null,
    fallback: String = "data/fonts/default.otf"
): String {
    val file = File(wanted)

    if (!file.isFile) {
        println("font ${file.path} not found — using $fallback")
        return fallback
    }

    if (isFontCollection(file)) {
        val plain = (face ?: file.nameWithoutExtension).replace(Regex("[^A-Za-z0-9]"), "-")
        return extractFontFace(file, face, File("build/fonts/$plain.ttf")).path
    }

    if (file.path.any { !it.isLetterOrDigit() && it !in "/._-" }) {
        val cached = File("build/fonts/${file.name.replace(Regex("[^A-Za-z0-9.]"), "-")}")
        cached.parentFile?.mkdirs()
        if (!cached.isFile || cached.lastModified() < file.lastModified()) {
            file.copyTo(cached, overwrite = true)
        }
        return cached.path
    }

    return file.path
}
