package slideshow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * The running order as a file: which slides play, in what order, under which chapters.
 *
 *     {
 *       "order": [
 *         "opening-scene",
 *         { "id": "welcome", "on": false },
 *         { "moment": "Aperitif", "slides": [ "yard" ] },
 *         { "chapter": "De wereld van bouwen", "slides": [
 *             "quote", "city", "tree",
 *             { "subchapter": "Motion", "slides": [ "loop" ] }
 *         ] },
 *         { "moment": "Exit", "slides": [ "closing-scene" ] }
 *       ],
 *       "archive": [ "plain", "shadow-type" ]
 *     }
 *
 * **This is an order, not a deck.** `Slideshow.kt` still says what every slide *is* — the
 * constructor and its arguments, which is the argument for a Kotlin show in the first place
 * (see [slideshow]). The file only says where each one goes, by the id the show gives it
 * ([Show.ids]), so a slide the Kotlin does not declare cannot be conjured here and a slide it
 * does declare needs nothing here to exist: one the file does not mention is appended at the
 * end in its declared place, with a note, and one the file names that the show no longer has
 * is skipped, with a note. `"on": false` leaves a slide out of the show without deleting it
 * from the file, the way a presentation editor skips a slide. The `archive` is the shelf: a
 * slide there is out of the order altogether — not played, not appended at the end either —
 * and kept so it can be put back later. An id in both plays; the order wins.
 *
 * A chapter is named by its title, which is how the file can move a slide from one chapter
 * to another: the slide takes that chapter's card. Chapters are numbered in the order they
 * first appear. A slide that composes for the pane beside a card — everything that is not a
 * backdrop or a scene — belongs inside a chapter; listed at the top level it keeps whatever
 * card was last shown.
 *
 * A **moment** is a part of the evening around the talk — the arrival, the aperitif, a course
 * between two chapters — and holds the walls that play in it. It is the chapter's counterpart
 * for what takes the whole wall: named, ordered and moved as one, but with no card, no number
 * and nothing in the Kotlin to find, since a moment is only a name for a stretch of the order.
 * So the evening reads as the draaiboek does — moment, chapter, moment, chapter — and which
 * wall plays in a course is a drag in the organizer rather than an edit to the show. A moment
 * belongs at the top level; a slide in one plays exactly as it would standing there alone.
 *
 * [Show.arranged] applies one; [Order.of] reads one off a show, which is what the organizer
 * starts from when there is no file yet. The organizer writes the file; the show reads it at
 * launch. It is json rather than yaml because the JDK and the project already read json and
 * neither reads yaml, and the file is small enough that the difference is punctuation.
 */
class Order(val entries: List<Entry>, val archive: List<String> = emptyList()) {

    sealed interface Entry

    /** A slide, by id. Off, it stays in the file but out of the show. */
    data class Ref(val id: String, val on: Boolean = true) : Entry

    /** A chapter, a subchapter inside one, or a moment of the evening, and what it holds. */
    data class Group(
        val title: String,
        val subchapter: Boolean,
        val entries: List<Entry>,
        /** A moment of the evening rather than a chapter: walls, no card. */
        val moment: Boolean = false
    ) : Entry

    /** Every slide the file names, in order, on or off. */
    fun refs(): List<Ref> = entries.flatMap { refsOf(it) }

    private fun refsOf(entry: Entry): List<Ref> = when (entry) {
        is Ref -> listOf(entry)
        is Group -> entry.entries.flatMap { refsOf(it) }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("order", JsonArray(entries.map { json(it) }))
        if (archive.isNotEmpty()) put("archive", JsonArray(archive.map { JsonPrimitive(it) }))
    }

    private fun json(entry: Entry): JsonElement = when (entry) {
        is Ref -> if (entry.on) JsonPrimitive(entry.id) else buildJsonObject { put("id", entry.id); put("on", false) }
        is Group -> buildJsonObject {
            put(if (entry.moment) "moment" else if (entry.subchapter) "subchapter" else "chapter", entry.title)
            put("slides", JsonArray(entry.entries.map { json(it) }))
        }
    }

    fun write(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(PRETTY.encodeToString(JsonObject.serializer(), toJson()) + "\n")
    }

    companion object {
        private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }

        fun read(file: File): Order = parse(file.readText())

        fun parse(text: String): Order {
            val root = Json.parseToJsonElement(text).jsonObject
            val list = root["order"]?.jsonArray ?: throw IllegalArgumentException("an order file needs an \"order\" list")
            val archive = root["archive"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            return Order(list.map { entry(it) }, archive)
        }

        private fun entry(element: JsonElement): Entry {
            if (element is JsonPrimitive) return Ref(element.content)
            val obj = element.jsonObject
            obj["id"]?.let { return Ref(it.jsonPrimitive.content, obj["on"]?.jsonPrimitive?.booleanOrNull ?: true) }
            val chapter = obj["chapter"]?.jsonPrimitive?.content
            val subchapter = obj["subchapter"]?.jsonPrimitive?.content
            val moment = obj["moment"]?.jsonPrimitive?.content
            val title = chapter ?: subchapter ?: moment
                ?: throw IllegalArgumentException("an order entry needs an id, a chapter, a subchapter or a moment: $obj")
            val slides = obj["slides"]?.jsonArray ?: JsonArray(emptyList())
            return Group(title, subchapter = chapter == null && moment == null, entries = slides.map { entry(it) },
                moment = chapter == null && moment != null)
        }

        /** The order a show is declared in, read off its outline. */
        fun of(show: Show): Order {
            val ids = show.slideIds
            val out = mutableListOf<Entry>()
            var chapter: MutableList<Entry>? = null
            var chapterTitle = ""
            var sub: MutableList<Entry>? = null
            var subTitle = ""

            show.slides.indices.forEach { i ->
                val place = show.outline[i]
                val ref = Ref(ids[i])
                if (place == null || place.chapterTitle.isBlank()) {
                    chapter = null; sub = null
                    out += ref
                    return@forEach
                }
                if (chapter == null || place.chapterTitle != chapterTitle) {
                    chapter = mutableListOf<Entry>().also { out += Group(place.chapterTitle, false, it) }
                    chapterTitle = place.chapterTitle
                    sub = null
                }
                if (place.subchapterTitle.isBlank()) {
                    sub = null
                    chapter!! += ref
                } else {
                    if (sub == null || place.subchapterTitle != subTitle) {
                        sub = mutableListOf<Entry>().also { chapter!! += Group(place.subchapterTitle, true, it) }
                        subTitle = place.subchapterTitle
                    }
                    sub!! += ref
                }
            }
            return Order(out)
        }
    }
}

/** The show in the order [Settings.order] names, where that file exists; as declared otherwise. */
fun Show.arrangedFromFile(): Show {
    val file = File(settings.order ?: return this)
    if (!file.isFile) return this
    val order = runCatching { Order.read(file) }.getOrElse {
        println("order: could not read ${file.path} (${it.message}); playing the show as declared")
        return this
    }
    println("order: ${file.path}")
    return arranged(order)
}

/**
 * This show played in [order]: the same slides and the same cards, arranged as the file says.
 *
 * The cards are the ones the builder made — a section's card is found from the declared show,
 * by the chapter's title — so moving a slide into a chapter stands it beside that chapter's
 * card, including a card the chapter made for itself. Chapters and subchapters are renumbered
 * in the order they now come; the card itself keeps the number it was built with, which is what
 * keeps a chapter's picture with its chapter. [Show.source] carries the show this was made
 * from, so the organizer can still show what is declared beside what is arranged.
 */
fun Show.arranged(order: Order): Show {
    val ids = slideIds
    val at = ids.withIndex().associate { (i, id) -> id to i }

    // The declared sections: a card and a placement to copy for each chapter / subchapter.
    data class Key(val chapter: String, val subchapter: String)
    val sectionPanel = mutableMapOf<Key, Int>()
    val chapterPanel = mutableMapOf<String, Int>()
    slides.indices.forEach { i ->
        val p = outline[i] ?: return@forEach
        if (p.chapterTitle.isBlank()) return@forEach
        val panel = panelOf.getOrElse(i) { -1 }
        if (panel >= 0) {
            sectionPanel.putIfAbsent(Key(p.chapterTitle, p.subchapterTitle), panel)
            chapterPanel.putIfAbsent(p.chapterTitle, panel)
        }
    }

    val outSlides = mutableListOf<Slide>()
    val outPlaces = mutableListOf<Placement>()
    val outPanel = mutableListOf<Int>()
    val outIds = mutableListOf<String>()
    val chapterNumber = mutableMapOf<String, Int>()
    val subNumber = mutableMapOf<Key, Int>()
    val subCount = mutableMapOf<String, Int>()
    val seen = mutableSetOf<String>()
    var lastPanel = -1

    fun place(ref: Order.Ref, chapter: String, subchapter: String, moment: String = "") {
        if (!seen.add(ref.id)) {
            println("order: \"${ref.id}\" is listed twice; the second is skipped")
            return
        }
        if (!ref.on) return
        val i = at[ref.id] ?: run {
            println("order: no slide called \"${ref.id}\" in the show; skipped")
            return
        }
        val slide = slides[i]
        val declared = outline[i]
        val placement: Placement
        val panel: Int
        if (chapter.isBlank()) {
            placement = Placement(0, "", 0, "", declared?.title, declared?.notes, moment)
            panel = -1
        } else {
            val n = chapterNumber.getOrPut(chapter) { chapterNumber.size + 1 }
            val key = Key(chapter, subchapter)
            val s = if (subchapter.isBlank()) 0 else subNumber.getOrPut(key) {
                val next = (subCount[chapter] ?: 0) + 1
                subCount[chapter] = next
                next
            }
            placement = Placement(n, chapter, s, subchapter, declared?.title, declared?.notes)
            panel = sectionPanel[key] ?: chapterPanel[chapter] ?: -1
            if (panel < 0 && !slide.wide) println("order: no card for \"$chapter\"; \"${ref.id}\" keeps the card before it")
        }
        outSlides += slide
        outPlaces += placement
        outIds += ref.id
        outPanel += when {
            slide.wide -> -1
            panel >= 0 -> panel
            else -> lastPanel
        }
        if (!slide.wide && panel >= 0) lastPanel = panel
    }

    fun walk(entries: List<Order.Entry>, chapter: String, subchapter: String, moment: String) {
        for (entry in entries) when (entry) {
            is Order.Ref -> place(entry, chapter, subchapter, moment)
            is Order.Group -> when {
                // a moment names a stretch of the top level; inside a chapter it is only its slides
                entry.moment -> if (chapter.isBlank()) walk(entry.entries, "", "", entry.title)
                                else walk(entry.entries, chapter, subchapter, "")
                entry.subchapter && chapter.isNotBlank() -> walk(entry.entries, chapter, entry.title, "")
                else -> walk(entry.entries, entry.title, "", "")
            }
        }
    }
    walk(order.entries, "", "", "")

    // The archive is out of the show and out of the appending below; listed as well, it plays.
    order.archive.forEach { id -> if (seen.add(id)) println("order: \"$id\" is archived") }

    // Anything the file does not mention still plays, at the end, where it was declared.
    ids.forEachIndexed { i, id ->
        if (id !in seen) {
            val p = outline[i]
            println("order: \"$id\" is not in the order file; appended")
            place(Order.Ref(id), p?.chapterTitle.orEmpty(), p?.subchapterTitle.orEmpty())
        }
    }

    require(outSlides.isNotEmpty()) { "the order file leaves no slide on: ${settings.order}" }
    return Show(outSlides, Outline(outPlaces), settings, panels, outPanel, outIds, source = this)
}
