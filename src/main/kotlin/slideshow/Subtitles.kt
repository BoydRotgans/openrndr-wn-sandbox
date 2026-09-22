package slideshow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.drawers.advanceWithSubscripts
import slideshow.drawers.setLine
import java.io.File

/**
 * What is said over each state of each slide, in Dutch — the speaker notes as subtitles.
 *
 *     { "subtitles": { "the-catalogue-city": { "A": "Onze samenleving…", "B": "Scholen, …" } } }
 *
 * **A state is named by the nameplate's letter**, A for the state a slide arrives on and B for
 * its first click — the same name the cue sheet, the feedback and a review copy call a state by,
 * so a line of subtitle and a line of sound design about one state are about the one thing. A
 * state with no line is silent: the subtitle is off the wall while it stands.
 *
 * **It is a file, for the reason the intents are**: a sentence carries no behaviour, so nothing is
 * lost keeping it out of the Kotlin and a great deal is gained by editing it in the organizer
 * beside the slide it belongs to. It is kept apart from `notes`, which in `Slideshow.kt` is build
 * direction as often as it is script. Unlike an intent it does reach the wall — in subtitle mode —
 * so a save is played at once rather than read by nobody.
 *
 * A value may be one string (state A) or an object of letters, and a letter's value one string or
 * a list of sentences, so a long passage stays readable when the file is edited by hand.
 */
class Subtitles(
    val lines: Map<String, Map<Int, String>> = emptyMap(),
    val note: String? = null,
    /**
     * Everything else in the file, carried through a save untouched: `voiceover`, the passage of
     * the voice-over script each slide's lines were placed from, and `drafts`, the slides whose
     * lines the script does not give. Only the organizer reads them; the wall never does.
     */
    val extra: Map<String, JsonElement> = emptyMap()
) {
    /** The line for [step] of the slide [id], or empty where nothing is said. */
    operator fun get(id: String, step: Int): String = lines[id]?.get(step).orEmpty()

    /** Slides with at least one line. */
    val size: Int get() = lines.count { (_, states) -> states.values.any { it.isNotBlank() } }

    fun toJson(): JsonObject = buildJsonObject {
        note?.let { put("note", it) }
        extra.forEach { (key, value) -> put(key, value) }
        put("subtitles", buildJsonObject {
            lines.forEach { (id, states) ->
                val said = states.filterValues { it.isNotBlank() }.toSortedMap()
                if (said.isNotEmpty()) put(id, buildJsonObject {
                    said.forEach { (step, text) -> put(letter(step), text.trim()) }
                })
            }
        })
    }

    fun write(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(PRETTY.encodeToString(JsonObject.serializer(), toJson()) + "\n")
    }

    companion object {
        private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }

        val EMPTY = Subtitles()

        fun read(file: File): Subtitles = parse(file.readText())

        /** The file at [path], or none — a missing or broken file is a show without subtitles, never a show that will not start. */
        fun readOrEmpty(path: String?): Subtitles {
            val file = File(path ?: return EMPTY)
            if (!file.isFile) return EMPTY
            return runCatching { read(file) }
                .onFailure { println("subtitles: could not read ${file.path} (${it.message})") }
                .getOrDefault(EMPTY)
        }

        fun parse(text: String): Subtitles {
            val root = Json.parseToJsonElement(text).jsonObject
            val body = root["subtitles"]?.jsonObject ?: JsonObject(emptyMap())
            return Subtitles(
                lines = body.mapValues { (_, value) ->
                    when (value) {
                        is JsonObject -> value.entries.mapNotNull { (key, said) ->
                            stepOf(key)?.let { it to sentences(said) }
                        }.toMap()
                        else -> mapOf(0 to sentences(value))
                    }.filterValues { it.isNotBlank() }
                }.filterValues { it.isNotEmpty() },
                note = root["note"]?.jsonPrimitive?.contentOrNull,
                extra = root.filterKeys { it != "note" && it != "subtitles" }
            )
        }

        /** One string, or a list of sentences run together. */
        private fun sentences(value: JsonElement): String = when (value) {
            is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }.joinToString(" ").trim()
            else -> value.jsonPrimitive.contentOrNull.orEmpty().trim()
        }

        /** The inverse of [letter]: A is 0, B is 1, Z is 25, AA is 26. Null for anything else. */
        fun stepOf(key: String): Int? {
            val k = key.trim().uppercase()
            if (k.isEmpty() || k.any { it !in 'A'..'Z' }) return null
            return k.fold(0) { n, c -> n * 26 + (c - 'A' + 1) } - 1
        }
    }
}

/**
 * Which of the two subtitle files the show reads: the **default** track is the voice-over script
 * as delivered, placed on the states (`show-subtitles.json`); the **extended** track is the full
 * presentation as it would be given, a line for every state, developed from it
 * (`show-subtitles-extended.json`). Both are [Subtitles]; only the file differs.
 *
 * **The extended track presents.** With subtitles on, the deck runs itself on it: every state is
 * held until its line has been said and a beat after — the rule a hands-off run holds a state for
 * — and then clicks on, so what is on the wall is the talk being given rather than a deck being
 * clicked. The default track leaves the deck to the arrows, as it always did.
 */
enum class SubtitleTrack {
    DEFAULT, EXTENDED;

    val key: String get() = name.lowercase()

    companion object {
        /** `default` or `extended`, any case; anything else is the default track. */
        fun of(name: String?): SubtitleTrack = entries.firstOrNull { it.key == name?.trim()?.lowercase() } ?: DEFAULT
    }
}

/**
 * A rendered line as it was really said: every word with the frames it starts and ends on, read
 * back off the wav by the renderer's own Whisper pass and kept in the voice manifest's `words`.
 *
 * **It is what puts a subtitle word on the wall on the frame it is spoken.** [Pace] places cards
 * by counting characters, which cannot know where the voice draws breath: measured against a
 * filmed run, a three-sentence line had its last card come up 0.7s after the speaker had started
 * it and stand 1.45s after they had finished. With the speech in hand there is nothing to
 * estimate — a card comes up on its first word, each word arrives as it is said, and the card
 * goes when the last one is done.
 *
 * **Asked and heard are matched by letter, not word for word.** Whisper writes what it hears, so
 * it splits a compound, writes a number as digits and now and then drops a word; a word-for-word
 * pairing would then slip for the rest of the line. Both sides are reduced to letters, and a
 * position in the asked text maps to the same *proportion* through the heard letters — so a local
 * mismatch stays local, and the times still come from real words rather than from a constant pace.
 */
class Speech(private val words: List<Word>) {

    /** One word as it was said: [at] and [end] in frames from the state being reached. */
    class Word(val at: Int, val end: Int, val letters: Int, val before: Int)

    /** Letters heard in all, which is what a position in the asked line is scaled against. */
    val letters: Int = words.sumOf { it.letters }

    /** The frame the last word ends on. */
    val end: Int = words.lastOrNull()?.end ?: 0

    val any: Boolean get() = words.isNotEmpty() && letters > 0

    /** The word being said at [letter] of an asked line [of] letters long. */
    private fun wordAt(letter: Int, of: Int): Word? {
        if (!any || of <= 0) return null
        val i = (letter.toDouble() * letters / of).toInt().coerceIn(0, letters - 1)
        return words.firstOrNull { i < it.before + it.letters } ?: words.last()
    }

    /** The frame the word carrying [letter] of an asked line of [of] letters starts on. */
    fun startAt(letter: Int, of: Int): Int? = wordAt(letter, of)?.at

    /** The frame that word ends on. */
    fun endAt(letter: Int, of: Int): Int? = wordAt(letter, of)?.end

    companion object {
        /** Lower case letters only — the same reduction on both sides of the match. */
        fun letters(text: String): String =
            text.lowercase().filter { it in 'a'..'z' || it in "àáâäéèêëïîóòôöúùûüçñ" }

        /** The manifest's `words`: `[[start, end, word], …]` in seconds. Null where there are none. */
        fun of(element: JsonElement?): Speech? {
            val array = element as? JsonArray ?: return null
            var before = 0
            val words = array.mapNotNull { entry ->
                val w = entry as? JsonArray ?: return@mapNotNull null
                val at = w.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val end = w.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val count = letters(w.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty()).length
                if (count == 0) null
                else Word(frames(at), frames(end), count, before).also { before += count }
            }
            return if (words.isEmpty()) null else Speech(words)
        }
    }
}

/**
 * How a line is cut into cards and how long each stands — the pace, stated once.
 *
 * **A card is what a person reads in one look**: at most [LINES] lines of at most [LINE_CHARS]
 * characters, the broadcast rule. A passage is cut at its sentences first, a sentence too long for
 * a card at its commas, colons and dashes, and a clause still too long between words, as evenly as
 * it will go. It is counted in characters rather than measured in the face, so the timing is a
 * pure function of the words: the auto cues can ask how long a state needs before any font is
 * loaded, and the organizer shows the same cards the wall will.
 *
 * **A card stands as long as it takes to say.** [cps] characters a second is the pace of someone
 * speaking to a room — slower than the 17 a subtitler allows for reading, because these stand for
 * a speaker, and a line that is gone before it could have been said reads as a machine. No card
 * stands under [MIN_SECONDS], so a two-word line is still a line and not a flicker, and there is a
 * breath of [GAP_SECONDS] between cards so two in a row read as two.
 */
class Pace(val cps: Double = DEFAULT_CPS) {

    /**
     * One card: its lines, when it comes up after the state is reached, and how long it stands,
     * in frames. [wordAt] is when each of its words arrives, in frames from the card coming up,
     * where the voice's own word times are known — otherwise it is null and the rule below
     * estimates them.
     */
    data class Card(val lines: List<String>, val at: Int, val length: Int, val wordAt: List<Int>? = null) {
        val text: String get() = lines.joinToString(" ")
        val end: Int get() = at + length
    }

    /**
     * [text] as cards, timed from the moment its state is reached. With [fit], the frames the
     * spoken voice for this state ends on, the cards are **stretched** as one so the last word on
     * the wall lands with the last word said.
     *
     * **It only ever stretches.** Fitting both ways was tried and reads as hurried: a voice that
     * says a line quicker than someone reads it would squeeze the cards under the rule below, and
     * past the [MIN_SECONDS] floor with it, so a short card could flash by in half a second. So the
     * slower of the two governs — the voice where it is slower, the reading rule where it is — and
     * the state is held until the cards are done rather than until the voice stops (`standFrames`).
     */
    fun cards(text: String, fit: Int? = null, speech: Speech? = null): List<Card> {
        val pieces = chunks(text)
        // The voice's own words, where they are known: nothing is estimated, and a card is up for
        // exactly as long as its words are being said. See Speech.
        if (speech != null && speech.any) return spoken(pieces, speech)

        var at = frames(LEAD_SECONDS)
        val cards = pieces.map { chunk ->
            val length = frames(maxOf(MIN_SECONDS, chunk.length / cps))
            Card(wrap(chunk), at, length).also { at += length + frames(GAP_SECONDS) }
        }
        val last = cards.lastOrNull()?.end ?: return cards
        val lead = frames(LEAD_SECONDS)
        if (fit == null || fit <= lead || last <= lead) return cards
        val f = maxOf(1.0, (fit - lead).toDouble() / (last - lead))
        if (f == 1.0) return cards
        return cards.map { Card(it.lines, lead + ((it.at - lead) * f).toInt(), (it.length * f).toInt().coerceAtLeast(1)) }
    }

    /**
     * [pieces] laid on [speech]: each card comes up as its first word is said and goes a beat
     * after its last, and every word inside it arrives on the frame it is spoken.
     *
     * A card is measured by where its letters fall in the whole line, so the match holds even
     * where the voice heard a word differently — see [Speech]. A card never goes before the next
     * one comes up, and never stands under [MIN_SECONDS] for a word said in an instant.
     */
    private fun spoken(pieces: List<String>, speech: Speech): List<Card> {
        val whole = Speech.letters(pieces.joinToString(" "))
        var before = 0
        val bounds = pieces.map { piece ->
            val start = before
            before += Speech.letters(piece).length
            start to before
        }
        return pieces.mapIndexed { i, piece ->
            val (from, to) = bounds[i]
            val at = (speech.startAt(from, whole.length) ?: 0) - frames(PRE_ROLL)
            val last = speech.endAt(to - 1, whole.length) ?: (at + frames(MIN_SECONDS))
            val next = if (i + 1 < pieces.size) speech.startAt(bounds[i + 1].first, whole.length) else null
            val end = minOf(last + frames(HANG), next?.minus(frames(GAP_SECONDS)) ?: Int.MAX_VALUE)
            val open = at.coerceAtLeast(0)
            val words = wrap(piece).flatMap { it.split(' ') }.filter { it.isNotEmpty() }
            // Each word on the frame it is said, measured from the card coming up.
            var seen = from
            val times = words.map { word ->
                val letter = seen
                seen += Speech.letters(word).length
                ((speech.startAt(letter, whole.length) ?: open) - open).coerceAtLeast(0)
            }
            Card(wrap(piece), open, maxOf(end - open, frames(MIN_SECONDS)), times)
        }
    }

    /**
     * How far into the card each word arrives, in frames from the card coming up — word by word,
     * as it would be said. A word waits for the characters before it, so a long word takes as long
     * to arrive as it takes to say, and the last one lands [SPOKEN] of the way through the card:
     * the whole line then stands a moment to be read before the card goes.
     */
    fun wordTimes(card: Card): List<Int> {
        card.wordAt?.let { return it }
        val words = card.lines.flatMap { it.split(' ') }.filter { it.isNotEmpty() }
        val total = words.sumOf { it.length + 1 }.coerceAtLeast(1)
        var before = 0
        return words.map { word ->
            (card.length * SPOKEN * before / total).toInt().also { before += word.length + 1 }
        }
    }

    /** Frames from the state being reached to its last card going: what a hands-off run must hold for it. */
    fun length(text: String, fit: Int? = null, speech: Speech? = null): Int =
        cards(text, fit, speech).lastOrNull()?.end ?: 0

    /** The card standing [elapsed] frames after the state was reached, or null between and after them. */
    fun at(text: String, elapsed: Int, fit: Int? = null, speech: Speech? = null): Card? =
        cards(text, fit, speech).firstOrNull { elapsed >= it.at && elapsed < it.end }

    /** Frames from the state being reached to a voice of [voiceFrames] ending: the lead, then the speech. */
    fun spoken(voiceFrames: Int): Int = frames(LEAD_SECONDS) + voiceFrames

    companion object {
        const val DEFAULT_CPS = 15.0
        const val LINE_CHARS = 42
        const val LINES = 2
        const val CARD_CHARS = LINE_CHARS * LINES
        const val MIN_SECONDS = 1.6
        const val GAP_SECONDS = 0.25
        /** The speaker draws breath as the click lands before saying the line. */
        const val LEAD_SECONDS = 0.4
        /** How far through a card its last word arrives, where the voice's own times are not known. */
        const val SPOKEN = 0.8
        /** A card is up this long before its first word is said, so the eye is there for it. */
        const val PRE_ROLL = 0.12
        /** And stands this long after its last word, to be read out. */
        const val HANG = 0.45

        /** [text] cut into pieces no longer than a card. */
        fun chunks(text: String): List<String> {
            val flat = text.replace(Regex("\\s+"), " ").trim()
            if (flat.isEmpty()) return emptyList()
            return sentences(flat).flatMap { sentence ->
                if (sentence.length <= CARD_CHARS) listOf(sentence)
                else packed(clauses(sentence)).flatMap { if (it.length <= CARD_CHARS) listOf(it) else evenly(it, CARD_CHARS) }
            }
        }

        /** At a full stop, question or exclamation mark followed by a space — so 1,2 and 2.750 stay whole. */
        private fun sentences(text: String): List<String> =
            text.split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

        /** At a comma, colon, semicolon or a spaced dash, the mark kept on the piece before it. */
        private fun clauses(sentence: String): List<String> =
            sentence.split(Regex("(?<=[,:;])\\s+|\\s+(?=[–—]\\s)")).map { it.trim() }.filter { it.isNotEmpty() }

        /**
         * Clauses run together into as few cards as fit, as even in length as they will go —
         * the partition is searched rather than filled greedily, which left the last card
         * whatever was over: "montage en beheer." alone for a second and a half. A clause
         * longer than a card is its own piece, cut between words afterwards.
         */
        private fun packed(clauses: List<String>): List<String> {
            val n = clauses.size
            fun joined(from: Int, until: Int) = clauses.subList(from, until).joinToString(" ")
            // best[i]: the fewest cards for the first i clauses, then the least spread in length
            val cards = IntArray(n + 1) { Int.MAX_VALUE }
            val spread = DoubleArray(n + 1) { Double.MAX_VALUE }
            val cut = IntArray(n + 1)
            cards[0] = 0; spread[0] = 0.0
            val mean = (clauses.sumOf { it.length + 1 } - 1).toDouble() /
                    ((clauses.sumOf { it.length + 1 } - 1 + CARD_CHARS - 1) / CARD_CHARS).coerceAtLeast(1)
            for (i in 1..n) for (k in 0 until i) {
                if (cards[k] == Int.MAX_VALUE) continue
                val piece = joined(k, i)
                if (piece.length > CARD_CHARS && i - k > 1) continue
                val c = cards[k] + 1
                val d = spread[k] + (piece.length - mean).let { it * it }
                if (c < cards[i] || (c == cards[i] && d < spread[i])) { cards[i] = c; spread[i] = d; cut[i] = k }
            }
            val out = ArrayDeque<String>()
            var i = n
            while (i > 0) { out.addFirst(joined(cut[i], i)); i = cut[i] }
            return out.toList()
        }

        /** [text] cut between words into the fewest pieces under [limit], as even as they go. */
        private fun evenly(text: String, limit: Int): List<String> {
            val words = text.split(' ')
            val count = (text.length + limit - 1) / limit
            val target = text.length.toDouble() / count
            val out = mutableListOf<String>()
            var line = ""
            for (word in words) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && (next.length > limit || (out.size < count - 1 && next.length > target + 4))) {
                    out += line; line = word
                } else line = next
            }
            if (line.isNotEmpty()) out += line
            return out
        }

        /** A card as one line, or two broken where they come out nearest equal. */
        fun wrap(card: String): List<String> {
            if (card.length <= LINE_CHARS) return listOf(card)
            val spaces = card.indices.filter { card[it] == ' ' }
            val at = spaces.minByOrNull { maxOf(it, card.length - it - 1) } ?: return listOf(card)
            return listOf(card.substring(0, at), card.substring(at + 1))
        }
    }
}

/**
 * Subtitles on the wall: the card standing now, in marker green inside a green box on a dark band at
 * the foot of the slide pane — deliberately *not* the house style, so it reads as a review layer.
 *
 * **Drawn into the canvas, like the [Nameplate]**, so a filmed run in subtitle mode carries them —
 * a film with subtitles is what the mode is for. And **always inside one projector**: the wall is
 * two 1920 panes meeting at x = 1920 and anything that has to be read stays in one of them. It is
 * the slide's pane, beside the chapter card, even under a backdrop that takes the whole wall, so
 * the eye finds the words in one place all evening.
 *
 * Set in the talk's text face through [setLine], so CO₂ is set rather than dropped, at the slide
 * title's size from [Scale] — the size a room reads a line at a glance.
 */
class SubtitleOverlay(private val face: FontImageMap?, private val em: Double) {

    /**
     * [card] as it stands [elapsed] frames after it came up: the words said so far, each fading in
     * over [WORD_FADE] as it arrives. The card is laid out whole from its first frame — its lines,
     * its box — so nothing reflows or grows as the words arrive; they fill a place already made.
     */
    fun draw(drawer: Drawer, pane: Rectangle, card: Pace.Card, elapsed: Int, pace: Pace) {
        val font = face ?: return
        val size = Scale.title(pane.height)
        // Set smaller only if a line would run out of the pane — never at 42 characters in this face.
        val widest = card.lines.maxOf { font.advanceWithSubscripts(it) } * size / em
        val room = pane.width - 2 * MARGIN
        val set = if (widest > room) size * room / widest else size
        val lineWidth = minOf(widest, room)
        val lead = set * LEADING

        val block = lead * (card.lines.size - 1) + set * CAP
        val baseFirst = pane.y + pane.height - FOOT - PAD - block + set * CAP
        val box = Rectangle(
            pane.center.x - lineWidth / 2 - PAD * 1.4, baseFirst - set * CAP - PAD,
            lineWidth + PAD * 2.8, block + PAD * 2 + set * DESCENT
        )
        drawer.isolated {
            drawer.shadeStyle = null
            drawer.stroke = null
            drawer.fill = ColorRGBa.BLACK.opacify(GROUND)
            drawer.rectangle(box)
            // Outlined in the marker green, so nobody takes it for part of the design.
            drawer.fill = null
            drawer.stroke = MARKER
            drawer.strokeWeight = EDGE
            drawer.rectangle(box)
            drawer.stroke = null
            drawer.fill = MARKER
            val times = pace.wordTimes(card)
            var w = 0
            card.lines.forEachIndexed { i, line ->
                // Ranged off the line's own left edge as the whole line would be centred, so a word
                // lands exactly where it stands once the line is complete.
                val scale = set / em
                val left = pane.center.x - font.advanceWithSubscripts(line) * scale / 2
                val y = baseFirst + i * lead
                var prefix = ""
                for (word in line.split(' ').filter { it.isNotEmpty() }) {
                    val shown = ((elapsed - times.getOrElse(w) { 0 }).toDouble() / WORD_FADE).coerceIn(0.0, 1.0)
                    w++
                    if (shown <= 0.0) break
                    val x = left + font.advanceWithSubscripts(if (prefix.isEmpty()) "" else "$prefix ") * scale
                    drawer.fill = MARKER.opacify(shown)
                    drawer.setLine(word, font, Vector2(x, y), set, em, align = 0.0)
                    prefix = if (prefix.isEmpty()) word else "$prefix $word"
                }
                if (w < times.size && elapsed < times[w]) return@isolated
            }
        }
    }

    companion object {
        /** The size the face is loaded at; it is drawn scaled to [Scale.title] of the pane. */
        const val EM = 48.0
        private const val LEADING = 1.3
        private const val CAP = 0.7
        private const val DESCENT = 0.22
        private const val PAD = 18.0
        private const val MARGIN = 120.0
        /** How far the band stands off the foot of the pane. */
        private const val FOOT = 64.0
        /** Opaque: over a white rung a see-through band let the slide's own text show through the line. */
        private const val GROUND = 1.0
        /**
         * Bright green, and a box drawn round it: a subtitle is a review layer laid over the wall,
         * not part of the show, and it has to look like one — nothing in the house palette is near
         * this green, so a film carrying it can never be mistaken for the finished picture.
         */
        val MARKER: ColorRGBa = ColorRGBa.fromHex("00FF40")
        private const val EDGE = 3.0
        /** Frames a word takes to fade in as it is said. */
        private const val WORD_FADE = 6
    }
}
