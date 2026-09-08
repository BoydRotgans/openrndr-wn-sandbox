import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pulls a single face out of a TrueType collection.
 *
 * Nearly every font macOS ships with is a `.ttc` — one file holding a whole family, so
 * `Rockwell.ttc` is Rockwell, Rockwell Italic, Rockwell Bold and Rockwell Bold Italic in a
 * row. OPENRNDR loads fonts through stb_truetype, which will not open a collection at all:
 * it fails outright with `failed to load font`, not with the wrong weight. So there is no
 * way to ask for a bold from one of these files, and asking for the file gets nothing.
 *
 * A collection is not a special format inside, though. Every face in it is an ordinary sfnt
 * table directory that happens to share the file, and the tables it points at are laid out
 * as normal. Writing one face out on its own is therefore a matter of copying the tables it
 * names and rebuilding its directory with the offsets moved — no glyph data is touched, and
 * no font library is needed to do it.
 */

private fun buffer(file: File): ByteBuffer =
    ByteBuffer.wrap(file.readBytes()).order(ByteOrder.BIG_ENDIAN)

private fun ByteBuffer.u16(at: Int) = getShort(at).toInt() and 0xFFFF
private fun ByteBuffer.u32(at: Int) = getInt(at).toLong() and 0xFFFFFFFFL

/** True when this really is a collection rather than a single font. */
fun isFontCollection(file: File): Boolean =
    file.length() > 12 && buffer(file).let { String(ByteArray(4) { i -> it.get(i) }) == "ttcf" }

private fun faceOffsets(b: ByteBuffer): List<Int> {
    val count = b.u32(8).toInt()
    return (0 until count).map { b.u32(12 + 4 * it).toInt() }
}

/** The full name (name ID 4) of the face whose table directory starts at [offset]. */
private fun faceName(b: ByteBuffer, offset: Int): String? {
    val tables = b.u16(offset + 4)
    for (t in 0 until tables) {
        val record = offset + 12 + 16 * t
        if (String(ByteArray(4) { b.get(record + it) }) != "name") continue
        val nameTable = b.u32(record + 8).toInt()
        val count = b.u16(nameTable + 2)
        val storage = nameTable + b.u16(nameTable + 4)
        var fallback: String? = null
        for (r in 0 until count) {
            val rec = nameTable + 6 + 12 * r
            val platform = b.u16(rec)
            val nameId = b.u16(rec + 6)
            if (nameId != 4) continue
            val length = b.u16(rec + 8)
            val at = storage + b.u16(rec + 10)
            val raw = ByteArray(length) { b.get(at + it) }
            val text = if (platform == 3) String(raw, Charsets.UTF_16BE) else String(raw, Charsets.ISO_8859_1)
            if (platform == 3) return text
            fallback = text
        }
        return fallback
    }
    return null
}

/**
 * Which face of a collection [wanted] names: an exact name first, then a loose one.
 *
 * **A loose match must not slide into an italic when none was asked for.** Rockwell's
 * collection holds `Rockwell-Bold` and `Rockwell Bold Italic`, and "Rockwell Bold" is
 * contained in the second before it is contained in the first — so the obvious `contains`
 * hands back the italic, which is a thing you notice on a slide and not in a path. Naming the
 * italic still finds it, because then the ask says so.
 */
private fun pickFace(names: List<String?>, wanted: String?): Int {
    val w = wanted?.trim()?.takeIf { it.isNotBlank() } ?: return 0

    val exact = names.indexOfFirst { it != null && it.equals(w, true) }
    if (exact >= 0) return exact

    val italic = w.contains("italic", true)
    val upright = names.indexOfFirst {
        it != null && it.contains(w, true) && (italic || !it.contains("italic", true))
    }
    if (upright >= 0) return upright

    return names.indexOfFirst { it != null && it.contains(w, true) }.coerceAtLeast(0)
}

/** Every face in [file], in the order the collection lists them. */
fun fontCollectionFaces(file: File): List<String> {
    val b = buffer(file)
    return faceOffsets(b).mapIndexed { i, o -> faceName(b, o) ?: "face $i" }
}

/**
 * Writes the face of [file] that [wanted] names to [into], as a standalone font, and returns
 * it. Falls back to the first face when nothing matches.
 *
 * **The cache is checked by name, not by date**, and it has to be. [into] is named after the
 * *wanted* string flattened to something a filesystem will take, and two different requests
 * flatten to the same name — "Rockwell Bold" and "Rockwell-Bold" both give `Rockwell-Bold.ttf`
 * and resolve to different faces of the collection. On a date check the second request
 * silently gets whatever the first one left there, and the only symptom is a deck set in the
 * wrong weight. Comparing the face already written against the face now asked for costs one
 * name table and cannot go wrong that way.
 */
fun extractFontFace(file: File, wanted: String?, into: File): File {
    val b = buffer(file)
    val offsets = faceOffsets(b)
    val names = offsets.map { faceName(b, it) }
    val chosen = pickFace(names, wanted)
    val face = offsets[chosen]

    val already = if (into.isFile && into.lastModified() >= file.lastModified())
        runCatching { faceName(buffer(into), 0) }.getOrNull() else null
    if (already != null && already == names[chosen]) return into

    // The tables this face names, in the order it names them.
    val tables = b.u16(face + 4)
    data class Table(val tag: ByteArray, val checksum: Int, val offset: Int, val length: Int)
    val entries = (0 until tables).map { t ->
        val rec = face + 12 + 16 * t
        Table(
            tag = ByteArray(4) { b.get(rec + it) },
            checksum = b.getInt(rec + 4),
            offset = b.u32(rec + 8).toInt(),
            length = b.u32(rec + 12).toInt()
        )
    }

    // A directory of the same size, then the table data one after another. Only the offsets
    // change: everything a face refers to is copied across byte for byte.
    val header = 12 + 16 * entries.size
    val padded = entries.map { (it.length + 3) / 4 * 4 }
    val out = ByteBuffer.allocate(header + padded.sum()).order(ByteOrder.BIG_ENDIAN)

    val selector = (31 - Integer.numberOfLeadingZeros(entries.size)).coerceAtLeast(0)
    out.putInt(b.getInt(face))                    // the face's own sfnt version
    out.putShort(entries.size.toShort())
    out.putShort((16 shl selector).toShort())
    out.putShort(selector.toShort())
    out.putShort((16 * entries.size - (16 shl selector)).toShort())

    var at = header
    for ((i, e) in entries.withIndex()) {
        out.put(e.tag)
        out.putInt(e.checksum)
        out.putInt(at)
        out.putInt(e.length)
        at += padded[i]
    }
    at = header
    for ((i, e) in entries.withIndex()) {
        out.position(at)
        for (k in 0 until e.length) out.put(b.get(e.offset + k))
        at += padded[i]
    }

    into.parentFile?.mkdirs()
    into.writeBytes(out.array())
    return into
}
