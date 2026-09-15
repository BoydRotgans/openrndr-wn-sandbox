package slideshow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import slideshow.drawers.PlaceholderSlide
import slideshow.drawers.Type
import java.io.File

/**
 * The slides still to be built, as a file the organizer edits.
 *
 *     {
 *       "source": "export/wn-speaker-notes.pptx",
 *       "modules": [
 *         { "id": "recyclage", "title": "Recyclage",
 *           "chapter": "Beton: ruggengraat en transitie",
 *           "frames": ["3-21", "3-22", "3-23", "3-24", "3-25"],
 *           "brief": ["What the frames show.", "What to build.", "What to reuse."] }
 *       ],
 *       "covered": { "the-catalogue-city": ["1-02", "1-03", "1-04"] }
 *     }
 *
 * A **module** is a slide that does not exist yet: a title, the frames of the client's deck
 * it stands for, and a brief saying what to build. Every one plays in the show as a
 * [PlaceholderSlide] — pink, a click a frame, the frames themselves on it — so the talk can be
 * clicked through whole while the gaps are still gaps, and the organizer shows what each gap
 * is. **This is the one place a slide can be conjured from a file**, and it is allowed because
 * a placeholder is one class with one job: the file carries nothing a placeholder needs beyond
 * what is listed above, and the day a drawer replaces it the module is deleted from here and
 * the drawer declared in `Slideshow.kt` like any other slide.
 *
 * `covered` is the other half of the comparison: which frames of the client's deck each
 * *implemented* slide stands for, by the slide's id. Between the two every frame is either
 * covered, in a module, or unplaced — which the organizer lists.
 *
 * A module's `chapter` is where it stands when the order file does not say: at the end of that
 * chapter, in the order the modules are listed. The order file places it like any other slide.
 * `wide` makes the placeholder take the whole wall, for a moment the client drew that way and
 * the show means to keep that way. The brief is written as a list of paragraphs so the file
 * reads in a diff; a plain string is accepted too.
 */
data class Module(
    val id: String,
    val title: String,
    val chapter: String,
    val frames: List<String>,
    val brief: String,
    val wide: Boolean = false
)

class Modules(
    val modules: List<Module> = emptyList(),
    val covered: Map<String, List<String>> = emptyMap(),
    val source: String? = null
) {
    fun toJson(): JsonObject = buildJsonObject {
        source?.let { put("source", it) }
        put("modules", JsonArray(modules.map { m ->
            buildJsonObject {
                put("id", m.id)
                put("title", m.title)
                put("chapter", m.chapter)
                if (m.wide) put("wide", true)
                put("frames", JsonArray(m.frames.map { JsonPrimitive(it) }))
                put("brief", JsonArray(paragraphs(m.brief).map { JsonPrimitive(it) }))
            }
        }))
        put("covered", buildJsonObject {
            covered.forEach { (id, frames) -> put(id, JsonArray(frames.map { JsonPrimitive(it) })) }
        })
    }

    fun write(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(PRETTY.encodeToString(JsonObject.serializer(), toJson()) + "\n")
    }

    companion object {
        private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }

        val EMPTY = Modules()

        fun read(file: File): Modules = parse(file.readText())

        fun parse(text: String): Modules {
            val root = Json.parseToJsonElement(text).jsonObject
            val modules = root["modules"]?.jsonArray.orEmpty().map { element ->
                val o = element.jsonObject
                val id = o["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("a module needs an id: $o")
                Module(
                    id = slugOf(id),
                    title = o["title"]?.jsonPrimitive?.content ?: id,
                    chapter = o["chapter"]?.jsonPrimitive?.content ?: "",
                    frames = o["frames"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                    brief = brief(o["brief"]),
                    wide = o["wide"]?.jsonPrimitive?.booleanOrNull ?: false
                )
            }
            val covered = root["covered"]?.jsonObject?.mapValues { (_, v) ->
                v.jsonArray.map { it.jsonPrimitive.content }
            }.orEmpty()
            return Modules(modules, covered, root["source"]?.jsonPrimitive?.content)
        }

        /** A brief as one string, whether the file wrote it as one or as paragraphs. */
        private fun brief(element: JsonElement?): String = when (element) {
            null -> ""
            is JsonArray -> element.joinToString("\n\n") { it.jsonPrimitive.content.trim() }
            else -> element.jsonPrimitive.content.trim()
        }

        /** A brief as paragraphs, parted at blank lines, so the file reads in a diff. */
        fun paragraphs(brief: String): List<String> =
            brief.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    }
}

// ------------------------------------------------------------------------------ //

/**
 * One frame of the client's deck: its name (`2-08`), where it sits, what kind of frame it is —
 * drawn beside its chapter card at the wall's size, drawn as a plain 16:9 slide with no card,
 * or painted across the whole wall — its speaker note, and the pictures cut from it. See
 * `tools/reference_frames.py`, which writes them and `frames.json` beside them.
 */
data class ReferenceFrame(
    val name: String,
    val chapter: Int,
    val index: Int,
    val slide: Int,
    val wide: Boolean,
    /** Drawn as a single 1920x1080 slide — no card and no wall around it — rather than at the wall's size. */
    val paneOnly: Boolean,
    val note: String,
    /** The whole frame, small: what the organizer shows. */
    val wall: File?,
    /** The slide's own pane at 1920x1080, for a frame that had one beside its card. */
    val pane: File?
) {
    /** The picture a placeholder stands on: the pane where the frame had one, else the wall. */
    val image: File? get() = pane ?: wall
}

class References(val dir: File, val frames: List<ReferenceFrame>) {
    private val byName = frames.associateBy { it.name }

    operator fun get(name: String): ReferenceFrame? = byName[name]

    companion object {
        val NONE = References(File("export/references"), emptyList())

        /**
         * Reads `frames.json` in [dir]. No file there is not a fault — the folder is client
         * material and not committed — so it reads as no frames and says how to get them.
         */
        fun read(dir: File, quiet: Boolean = false): References {
            val index = File(dir, "frames.json")
            if (!index.isFile) {
                if (!quiet) println("references: no ${index.path} — run tools/reference_frames.py to cut the client's deck into frames")
                return References(dir, emptyList())
            }
            val root = runCatching { Json.parseToJsonElement(index.readText()).jsonObject }.getOrElse {
                println("references: could not read ${index.path} (${it.message})")
                return References(dir, emptyList())
            }
            val frames = root["frames"]?.jsonArray.orEmpty().mapNotNull { element ->
                val o = element.jsonObject
                val name = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                fun picture(key: String) = o[key]?.jsonPrimitive?.content?.let { File(dir, it) }?.takeIf { it.isFile }
                ReferenceFrame(
                    name = name,
                    chapter = o["chapter"]?.jsonPrimitive?.intOrNull ?: 0,
                    index = o["index"]?.jsonPrimitive?.intOrNull ?: 0,
                    slide = o["slide"]?.jsonPrimitive?.intOrNull ?: 0,
                    wide = o["wide"]?.jsonPrimitive?.booleanOrNull ?: false,
                    paneOnly = o["pane_only"]?.jsonPrimitive?.booleanOrNull ?: false,
                    note = o["note"]?.jsonPrimitive?.content ?: "",
                    wall = picture("wall"),
                    pane = picture("pane")
                )
            }
            return References(dir, frames)
        }
    }
}

// ------------------------------------------------------------------------------ //

/**
 * This show with the modules standing in it as placeholders — each at the end of the chapter
 * it names, in the order listed — and any placeholder it already carried taken out first, so
 * applying a new modules file is a replacement rather than a pile-up.
 *
 * A placeholder whose frames are unchanged is **kept rather than rebuilt**: its pictures are
 * already on the card, and keeping the object is what lets the deck stay on it when the file
 * is saved while it is on screen. Only its title and brief are updated.
 */
fun Show.withModules(modules: Modules, references: References): Show {
    val ids = slideIds
    val previous = slides.filterIsInstance<PlaceholderSlide>().associateBy { it.module.id }

    val outSlides = mutableListOf<Slide>()
    val outPlaces = mutableListOf<Placement>()
    val outPanel = mutableListOf<Int>()
    val outIds = mutableListOf<String>()
    slides.indices.forEach { i ->
        if (slides[i] is PlaceholderSlide) return@forEach
        outSlides += slides[i]
        outPlaces += outline[i] ?: Placement(0, "", 0, "", null, null)
        outPanel += panelOf.getOrElse(i) { -1 }
        outIds += ids[i]
    }

    modules.modules.forEach { module ->
        val slide = previous[module.id]
            ?.takeIf { it.module.frames == module.frames && it.module.wide == module.wide }
            ?.also { it.module = module }
            ?: PlaceholderSlide(module, module.frames.mapNotNull { name ->
                references[name] ?: run {
                    println("modules: \"${module.id}\" names a frame the references do not have: $name")
                    null
                }
            }, Type.file)

        // After the last slide of its chapter — which, once one module is in, is the module
        // before it, so a chapter's modules come in the order the file lists them.
        val at = if (module.chapter.isBlank()) null
        else outPlaces.indices.lastOrNull { outPlaces[it].chapterTitle == module.chapter }
        if (at == null && module.chapter.isNotBlank()) {
            println("modules: no chapter called \"${module.chapter}\" for \"${module.id}\"; it stands at the end")
        }
        val placement = if (at != null) {
            val p = outPlaces[at]
            Placement(p.chapter, p.chapterTitle, 0, "", module.title, note(module))
        } else Placement(0, "", 0, "", module.title, note(module))

        // Its chapter's card, found from a slide of that chapter that has one.
        val panel = if (slide.wide || at == null) -1
        else (at downTo 0).firstOrNull { outPlaces[it].chapterTitle == module.chapter && outPanel[it] >= 0 }
            ?.let { outPanel[it] } ?: -1

        var id = module.id
        var n = 2
        while (id in outIds) id = "${module.id}-${n++}"
        if (id != module.id) println("modules: \"${module.id}\" is also a slide of the show; the module is #$id")

        val insert = if (at != null) at + 1 else outSlides.size
        outSlides.add(insert, slide)
        outPlaces.add(insert, placement)
        outPanel.add(insert, panel)
        outIds.add(insert, id)
    }

    return Show(outSlides, Outline(outPlaces), settings, panels, outPanel, outIds)
}

private fun note(module: Module) =
    "Module, still to be built — " + when (module.frames.size) {
        0 -> "no reference frames."
        1 -> "one reference frame, ${module.frames[0]}."
        else -> "${module.frames.size} reference frames, ${module.frames.first()} to ${module.frames.last()}."
    }

/** The modules file [Settings.modules] names, stood in this show; the show as it is when there is none. */
fun Show.withModules(): Show {
    val file = File(settings.modules ?: return this)
    if (!file.isFile) {
        println("modules: no ${file.path}; nothing to build")
        return this
    }
    val modules = runCatching { Modules.read(file) }.getOrElse {
        println("modules: could not read ${file.path} (${it.message}); none stood in")
        return this
    }
    val references = References.read(File(settings.references ?: "export/references"))
    println("modules: ${modules.modules.size} still to build, off ${file.path}")
    return withModules(modules, references)
}
