package slideshow

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import slideshow.drawers.PlaceholderSlide
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * The organizer's back end: a small web server inside the running show.
 *
 * It serves the organizer page and the previews, takes the page's commands, and answers
 * where the show stands. **It never touches the deck itself.** The server answers on threads
 * of its own and the deck belongs to the draw loop, so a command from the page goes into
 * [commands] and the draw loop drains it on the next frame, and what the page is told comes
 * from a [snapshot] the draw loop writes once a frame. Nothing here reads a clock, for the
 * reason nothing in a slide does.
 *
 *     GET  /                     the organizer
 *     GET  /api/show             the catalogue, the order file, the modules, the frames
 *     GET  /api/state            where the show stands, polled
 *     POST /api/go               { "id": "...", "step": 0 }   jump the show to a slide
 *     POST /api/click            { "dir": 1 | -1 }            a click forward or back
 *     POST /api/replay
 *     POST /api/previews         { "ids": [...] }?            render previews again
 *     PUT  /api/order            an order file's contents      save the running order
 *     PUT  /api/modules          a modules file's contents     save the slides to build
 *     PUT  /api/intents          an intents file's contents    save the intended update per slide
 *     PUT  /api/feedback         a feedback file's contents    save the notes written against slides
 *     PUT  /api/midi             a midi file's contents        save which slides are wanted as MIDI
 *     POST /api/export                                         write every ticked slide's clip and score
 *     GET  /previews/<id>-<k>.png
 *     GET  /references/<frame>-wall.jpg | <frame>-pane.png
 *
 * **A save is played at once.** Writing the order file also queues an [Apply], and the draw
 * loop rearranges the deck in place — the same slide objects in the new order, the slide on
 * screen kept where it is with its frame count — so the page and the window share one state
 * and nothing waits for a launch. That is only possible because, with the organizer on, every
 * declared slide is loaded whether or not the order plays it: an archived slide dragged back
 * in has to be there to be shown.
 *
 * **Saving the modules file rebuilds the catalogue the same way.** [ApplyModules] stands the
 * new set of placeholders in the declared show — loading only the ones whose frames changed —
 * and the order is played again over it, so a module added on the page is on the wall as soon
 * as it is saved. See [Modules].
 *
 * **Previews are three frames a slide**, rendered by the draw loop into `build/previews/` at
 * a fraction of the canvas: the opening state, a middle one and the last, so a slide that
 * moves shows where it goes. See [previewShots] for how the three are chosen. They are
 * rendered on request, one a frame, so the show never stalls for them — for every declared
 * slide, since every one is loaded.
 */
class Remote(
    show: Show,
    private val orderFile: File,
    private val port: Int,
    val previewDir: File,
    private val modulesFile: File = File("show-modules.json"),
    private val references: References = References.NONE,
    private val intentsFile: File = File("show-intents.json"),
    private val feedbackFile: File = File("show-feedback.json"),
    private val midiFile: File = File("show-midi.json"),
    private val midiDir: File = File("midi")
) {
    sealed interface Command
    data class Go(val id: String, val step: Int) : Command
    object Forward : Command
    object Backward : Command
    object Replay : Command
    /** Render previews again — for [ids], or for every slide when null. */
    data class Previews(val ids: List<String>?) : Command
    /** Play this order from now on: the deck is rearranged in place, on the slide it is on. */
    data class Apply(val order: Order) : Command
    /** Stand these modules in the catalogue from now on, and play the order again over it. */
    data class ApplyModules(val modules: Modules) : Command
    /** The concrete over the frame on or off; null flips it. */
    data class Concrete(val on: Boolean?) : Command
    /** The ruled grid over the wall on or off; null flips it. See [GridOverlay]. */
    data class Grid(val on: Boolean?) : Command
    /**
     * Write these slides' builds out as MIDI files.
     *
     * It is a command rather than something the server does on its own thread for the reason
     * every other one is: the slides belong to the draw loop. Nothing here needs a frame — a
     * build is a pure function of the frame and the schedule is read off the drawer — but the
     * rule is what keeps the two sides from ever having to think about it.
     */
    data class ExportMidi(val ids: List<String>) : Command
    /**
     * Write every ticked slide out whole: its score *and* a clip of it, one pair a slide.
     *
     * It is the other half of the tick. [ExportMidi] keeps the files that cost nothing in step
     * with the toggle; this is the run that takes minutes and is asked for when it is wanted.
     */
    data class Export(val ids: List<String>) : Command

    /** Where the show stands, written by the draw loop once a frame. */
    data class Snapshot(
        val index: Int, val id: String, val step: Int, val steps: Int, val frame: Int,
        val previewsLeft: Int, val previewStamp: Long,
        val concrete: Boolean = false, val hasConcrete: Boolean = false,
        val grid: Boolean = false
    )

    val commands = ConcurrentLinkedQueue<Command>()

    @Volatile
    var snapshot = Snapshot(0, "", 0, 1, 0, 0, 0L)

    /** When a preview was last written, so the page knows to fetch it again. */
    @Volatile
    var previewStamp = 0L

    /** The show as it plays: another arrangement of the same slides once an order is applied. */
    @Volatile
    var show: Show = show
        private set

    /** The slides as the running show has them. */
    val ids: List<String> get() = show.slideIds

    /** The slides as declared — every one, on or off, the placeholders included — which is what the page lists. */
    @Volatile
    var catalogue: Show = show.source ?: show
        private set

    /** The modules file as last read or saved. */
    @Volatile
    var modules: Modules = if (modulesFile.isFile) runCatching { Modules.read(modulesFile) }.getOrDefault(Modules.EMPTY) else Modules.EMPTY
        private set

    /**
     * The intended update per slide, as last read or saved. It carries no behaviour, so a save
     * queues no command: the page is the only thing that reads it and the deck never sees it.
     */
    @Volatile
    var intents: Intents = if (intentsFile.isFile) runCatching { Intents.read(intentsFile) }.getOrDefault(Intents.EMPTY) else Intents.EMPTY
        private set

    /**
     * Which slides are wanted as MIDI, as last read or saved. Unlike the intents this one does
     * carry a consequence — a save writes the files — so it queues an [ExportMidi]; and like the
     * feedback it is saved the moment it is ticked, a toggle being a whole edit in one click.
     */
    @Volatile
    var midi: MidiWanted = (if (midiFile.isFile) runCatching { MidiWanted.read(midiFile) }.getOrDefault(MidiWanted.EMPTY) else MidiWanted.EMPTY)
        private set

    /** Where a slide's MIDI is written, and what it is called. */
    fun midiFileOf(id: String): File = File(midiDir, "$id.mid")

    /** Its clip, beside it and named for it, so a pair is a pair by its name. */
    fun clipFileOf(id: String): File = File(midiDir, "$id.mp4")

    /** How far the export has got, written by the draw loop for the page to poll. */
    @Volatile var exporting: String = ""
    @Volatile var exportDone: Int = 0
    @Volatile var exportTotal: Int = 0
    @Volatile var exportFrame: Int = 0
    @Volatile var exportFrames: Int = 0

    /**
     * The notes written against slides, as last read or saved. Like the intents it carries no
     * behaviour, so a save queues no command; unlike them it is saved the moment it is written.
     */
    @Volatile
    var feedback: Feedback = (if (feedbackFile.isFile) runCatching { Feedback.read(feedbackFile) }.getOrDefault(Feedback.EMPTY) else Feedback.EMPTY)
        .also { if (it.total > 0) println("feedback: ${it.open} open of ${it.total} notes, off ${feedbackFile.path}") }
        private set

    /** The draw loop's word that [next] is now what plays. */
    fun arranged(next: Show) {
        show = next
    }

    /** The draw loop's word that [next] is now the catalogue — the modules stood in afresh. */
    fun catalogued(next: Show) {
        catalogue = next
    }

    /** The order to play: the file's where there is one, else the catalogue as declared. */
    fun currentOrder(): Order =
        if (orderFile.isFile) runCatching { Order.read(orderFile) }.getOrElse { Order.of(catalogue) }
        else Order.of(catalogue)

    private var server: HttpServer? = null

    val url: String get() = "http://localhost:$boundPort/"

    /** The port actually listened on: [port], or the first free one above it. */
    var boundPort: Int = port
        private set

    /** Starts serving. False when no port could be had, in which case there is no organizer. */
    fun start(): Boolean {
        // The port asked for, or the next free one within ten of it — another local server
        // on 8765 should not cost the organizer, only tell you where it went instead.
        val http = (port until port + 10).firstNotNullOfOrNull { candidate ->
            runCatching { HttpServer.create(InetSocketAddress("127.0.0.1", candidate), 8) }.getOrNull()
                ?.also { boundPort = candidate }
        } ?: run {
            println("organizer: no free port between $port and ${port + 9}; running without it")
            return false
        }
        if (boundPort != port) println("organizer: port $port is taken; using $boundPort")
        http.executor = Executors.newCachedThreadPool { r -> Thread(r, "organizer").apply { isDaemon = true } }
        http.createContext("/") { exchange -> serve(exchange) }
        http.start()
        server = http
        println("organizer: $url")
        // On a Mac the page is opened for you; elsewhere the address above is printed.
        if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) {
            runCatching { ProcessBuilder("open", url).start() }
        }
        return true
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    // ------------------------------------------------------------------------------ //

    private fun serve(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            when {
                path == "/" || path == "/index.html" -> exchange.send(200, page(), "text/html; charset=utf-8")
                path == "/api/show" && method == "GET" -> exchange.json(200, showJson())
                path == "/api/state" && method == "GET" -> exchange.json(200, stateJson())
                path == "/api/go" && method == "POST" -> {
                    val body = exchange.body()
                    val id = body["id"]?.jsonPrimitive?.contentOrNull
                    if (id == null) exchange.json(400, error("go needs an id"))
                    else {
                        commands += Go(id, body["step"]?.jsonPrimitive?.intOrNull ?: 0)
                        exchange.json(202, ok())
                    }
                }
                path == "/api/click" && method == "POST" -> {
                    val dir = exchange.body()["dir"]?.jsonPrimitive?.intOrNull ?: 1
                    commands += if (dir < 0) Backward else Forward
                    exchange.json(202, ok())
                }
                path == "/api/replay" && method == "POST" -> { commands += Replay; exchange.json(202, ok()) }
                path == "/api/concrete" && method == "POST" -> {
                    commands += Concrete(exchange.body()["on"]?.jsonPrimitive?.booleanOrNull)
                    exchange.json(202, ok())
                }
                path == "/api/grid" && method == "POST" -> {
                    commands += Grid(exchange.body()["on"]?.jsonPrimitive?.booleanOrNull)
                    exchange.json(202, ok())
                }
                path == "/api/previews" && method == "POST" -> {
                    val ids = runCatching { exchange.body()["ids"]?.jsonArray?.map { it.jsonPrimitive.content } }.getOrNull()
                    commands += Previews(ids)
                    exchange.json(202, ok())
                }
                path == "/api/order" && method == "PUT" -> {
                    val text = exchange.requestBody.readBytes().decodeToString()
                    val order = runCatching { Order.parse(text) }.getOrElse {
                        exchange.json(400, error("not an order: ${it.message}"))
                        return
                    }
                    order.write(orderFile)
                    commands += Apply(order)
                    println("organizer: saved ${orderFile.path} (${order.refs().count { it.on }} slides on)")
                    exchange.json(200, buildJsonObject { put("saved", orderFile.path); put("applied", true) })
                }
                path == "/api/modules" && method == "PUT" -> {
                    val text = exchange.requestBody.readBytes().decodeToString()
                    val next = runCatching { Modules.parse(text) }.getOrElse {
                        exchange.json(400, error("not a modules file: ${it.message}"))
                        return
                    }
                    val kept = Modules(next.modules, next.covered, next.source ?: modules.source)
                    kept.write(modulesFile)
                    modules = kept
                    commands += ApplyModules(kept)
                    println("organizer: saved ${modulesFile.path} (${kept.modules.size} modules to build)")
                    exchange.json(200, buildJsonObject { put("saved", modulesFile.path); put("applied", true) })
                }
                path == "/api/intents" && method == "PUT" -> {
                    val text = exchange.requestBody.readBytes().decodeToString()
                    val next = runCatching { Intents.parse(text) }.getOrElse {
                        exchange.json(400, error("not an intents file: ${it.message}"))
                        return
                    }
                    // The file's own header is the show's, not the page's, so it is kept.
                    val kept = Intents(next.intents, next.note ?: intents.note)
                    kept.write(intentsFile)
                    intents = kept
                    println("organizer: saved ${intentsFile.path} (${kept.size} slides with an intent)")
                    exchange.json(200, buildJsonObject { put("saved", intentsFile.path); put("applied", true) })
                }
                path == "/api/feedback" && method == "PUT" -> {
                    val text = exchange.requestBody.readBytes().decodeToString()
                    val next = runCatching { Feedback.parse(text) }.getOrElse {
                        exchange.json(400, error("not a feedback file: ${it.message}"))
                        return
                    }
                    val kept = Feedback(next.items, next.note ?: feedback.note)
                    kept.write(feedbackFile)
                    feedback = kept
                    println("organizer: saved ${feedbackFile.path} (${kept.open} open of ${kept.total} notes)")
                    exchange.json(200, buildJsonObject { put("saved", feedbackFile.path); put("open", kept.open) })
                }
                path == "/api/midi" && method == "PUT" -> {
                    val text = exchange.requestBody.readBytes().decodeToString()
                    val next = runCatching { MidiWanted.parse(text) }.getOrElse {
                        exchange.json(400, error("not a midi file: ${it.message}"))
                        return
                    }
                    // Every slide can be written down — the floor is its own states, see Slide —
                    // so what is dropped here is only an id the show does not have, which would
                    // otherwise sit in the file promising an export that never appears.
                    val known = catalogue.slideIds.toSet()
                    val kept = MidiWanted(next.ids.filter { it in known }.toSet(), next.note ?: midi.note)
                    kept.write(midiFile)
                    midi = kept
                    commands += ExportMidi(kept.ids.toList())
                    println("organizer: saved ${midiFile.path} (${kept.size} slides wanted as MIDI)")
                    exchange.json(200, buildJsonObject {
                        put("saved", midiFile.path)
                        put("wanted", kept.size)
                        put("files", JsonArray(kept.ids.sorted().map { JsonPrimitive(midiFileOf(it).path) }))
                    })
                }
                path == "/api/export" && method == "POST" -> {
                    if (midi.ids.isEmpty()) exchange.json(400, error("nothing is ticked for export"))
                    else {
                        commands += Export(midi.ids.toList())
                        exchange.json(200, buildJsonObject { put("exporting", midi.size) })
                    }
                }
                path == "/api/sketches" && method == "GET" -> exchange.json(200, Sketches.json())
                path.startsWith("/sketch-previews/") && method == "GET" -> {
                    val file = Sketches.preview(path.removePrefix("/sketch-previews/"))
                    if (file == null) exchange.send(404, ByteArray(0), "text/plain")
                    else exchange.send(200, file.readBytes(), "image/png", cache = false)
                }
                path.startsWith("/previews/") && method == "GET" -> {
                    val name = path.removePrefix("/previews/")
                    val file = File(previewDir, name)
                    if (!PREVIEW_NAME.matches(name) || !file.isFile) exchange.send(404, ByteArray(0), "text/plain")
                    else exchange.send(200, file.readBytes(), "image/png", cache = false)
                }
                path.startsWith("/references/") && method == "GET" -> {
                    val name = path.removePrefix("/references/")
                    val file = File(references.dir, name)
                    if (!REFERENCE_NAME.matches(name) || !file.isFile) exchange.send(404, ByteArray(0), "text/plain")
                    else exchange.send(200, file.readBytes(), if (name.endsWith(".png")) "image/png" else "image/jpeg", cache = true)
                }
                else -> exchange.send(404, "not found".toByteArray(), "text/plain")
            }
        } catch (e: Exception) {
            runCatching { exchange.json(500, error(e.message ?: e.toString())) }
        }
    }

    /**
     * The page, off disk while the source tree is there — so it can be edited and reloaded
     * without a rebuild — and out of the jar otherwise.
     */
    private fun page(): ByteArray {
        val onDisk = File("src/main/resources/organizer/index.html")
        if (onDisk.isFile) return onDisk.readBytes()
        return Remote::class.java.getResourceAsStream("/organizer/index.html")?.readBytes()
            ?: "<p>organizer page missing: src/main/resources/organizer/index.html</p>".toByteArray()
    }

    private fun showJson(): JsonObject {
        val declared = Order.of(catalogue)
        val order = if (orderFile.isFile) runCatching { Order.read(orderFile) }.getOrDefault(declared) else declared
        val catalogueIds = catalogue.slideIds
        return buildJsonObject {
            put("canvas", buildJsonObject { put("width", show.settings.width); put("height", show.settings.height) })
            put("orderFile", orderFile.path)
            put("hasOrderFile", orderFile.isFile)
            put("modulesFile", modulesFile.path)
            put("hasModulesFile", modulesFile.isFile)
            put("running", JsonArray(ids.map { JsonPrimitive(it) }))
            put("catalogue", JsonArray(catalogueIds.mapIndexed { i, id -> slideJson(catalogue, i, id) }))
            put("order", order.toJson())
            put("declared", declared.toJson())
            put("modules", modules.toJson())
            put("intentsFile", intentsFile.path)
            put("intents", intents.toJson())
            put("feedbackFile", feedbackFile.path)
            put("feedback", feedback.toJson())
            put("midiFile", midiFile.path)
            put("midiDir", midiDir.path)
            put("midi", midi.toJson())
            put("frames", JsonArray(references.frames.map { frameJson(it) }))
            put("previewStamp", previewStamp)
        }
    }

    private fun slideJson(of: Show, i: Int, id: String): JsonObject {
        val slide = of.slides[i]
        val place = of.outline[i]
        val module = (slide as? PlaceholderSlide)?.module
        return buildJsonObject {
            put("id", id)
            put("name", slide.name)
            put("cls", slide::class.simpleName ?: "")
            put("title", place?.title ?: slide.name)
            put("kind", slide.kind)
            put("wide", slide.wide)
            put("module", module != null)
            // What this slide's score is written on: a lane a column for the Plain wall, a lane
            // a click for the crowd, and for everything else one lane of its own states. Every
            // slide has some, which is why the organizer's midi button is never refused.
            put("midiLanes", JsonArray(slide.lanes.map { JsonPrimitive(it) }))
            put("steps", slide.steps)
            put("stepSeconds", seconds(slide.stepFrames))
            put("loopSeconds", seconds(slide.loop))
            put("settleSeconds", seconds(slide.settle))
            put("transition", slide.transition::class.simpleName?.lowercase() ?: "")
            put("notes", place?.notes ?: "")
            put("chapter", place?.chapterTitle ?: "")
            put("subchapter", place?.subchapterTitle ?: "")
            put("moment", place?.moment ?: "")
            put("frames", JsonArray((module?.frames ?: modules.covered[id].orEmpty()).map { JsonPrimitive(it) }))
            put("shots", JsonArray(previewShots(slide).map { JsonPrimitive(it.label) }))
        }
    }

    private fun frameJson(frame: ReferenceFrame): JsonObject = buildJsonObject {
        put("name", frame.name)
        put("chapter", frame.chapter)
        put("index", frame.index)
        put("slide", frame.slide)
        put("wide", frame.wide)
        put("paneOnly", frame.paneOnly)
        put("note", frame.note)
        frame.wall?.let { put("wall", "/references/${it.name}") }
        frame.pane?.let { put("pane", "/references/${it.name}") }
    }

    private fun stateJson(): JsonObject {
        val s = snapshot
        return buildJsonObject {
            put("index", s.index); put("id", s.id); put("step", s.step); put("steps", s.steps)
            put("frame", s.frame); put("previewsLeft", s.previewsLeft); put("previewStamp", s.previewStamp)
            put("concrete", s.concrete); put("hasConcrete", s.hasConcrete); put("grid", s.grid)
            put("exporting", exporting); put("exportDone", exportDone); put("exportTotal", exportTotal)
            put("exportFrame", exportFrame); put("exportFrames", exportFrames)
        }
    }

    private fun ok() = buildJsonObject { put("ok", true) }
    private fun error(message: String) = buildJsonObject { put("error", message) }

    private fun HttpExchange.body(): JsonObject =
        runCatching { Json.parseToJsonElement(requestBody.readBytes().decodeToString()).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))

    private fun HttpExchange.json(code: Int, body: JsonObject) =
        send(code, Json.encodeToString(JsonObject.serializer(), body).toByteArray(), "application/json; charset=utf-8")

    private fun HttpExchange.send(code: Int, body: ByteArray, type: String, cache: Boolean = false) {
        responseHeaders.add("Content-Type", type)
        responseHeaders.add("Cache-Control", if (cache) "max-age=3600" else "no-store")
        sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        responseBody.use { if (body.isNotEmpty()) it.write(body) }
    }

    companion object {
        private val PREVIEW_NAME = Regex("^[a-z0-9-]+-[0-2]\\.png$")
        private val REFERENCE_NAME = Regex("^[0-9]+-[0-9]+-(wall\\.jpg|pane\\.png)$")

        /** One of the three frames a preview shows: the click it stands on, the frame, and its caption. */
        data class PreviewShot(val step: Int, val frame: Int, val label: String)

        /**
         * The three frames that stand for a slide.
         *
         * A slide with clicks is shown at its first, its middle and its last click, each
         * settled — a still of a click is taken once it has landed, for the reason the deck's
         * contact sheet waits. A slide that loops is shown a sixth, a half and five sixths of
         * the way round, rather than at its very start, which on a wall that draws itself is an
         * empty wall. A slide with neither is shown half way through its own opening, settled,
         * and eight seconds on — the same picture twice for a slide that then holds still, and
         * for a wall that never stops moving, such as the belt, how far it has gone.
         */
        fun previewShots(slide: Slide): List<PreviewShot> = when {
            slide.steps > 1 -> {
                val last = slide.steps - 1
                val mid = last / 2
                listOf(0, mid, last).map { step ->
                    PreviewShot(step, PREVIEW_HOLD + slide.settle + (1..step).sumOf { slide.stepLength(it) },
                        if (step == 0) "opening" else "click $step")
                }
            }
            slide.loop > 0 -> listOf(1, 3, 5).map { sixth ->
                val frame = slide.loop * sixth / 6
                PreviewShot(0, frame, clock(frame))
            }
            else -> {
                val settled = slide.settle + PREVIEW_HOLD
                listOf(settled / 2, settled, settled + frames(8.0)).map { frame ->
                    PreviewShot(0, frame, clock(frame))
                }
            }
        }

        /** A frame count as a caption: seconds under a minute, minutes and seconds above. */
        fun clock(frame: Int): String {
            val s = seconds(frame)
            return if (s < 60.0) "%.1fs".format(s) else "%d:%02d".format((s / 60).toInt(), (s % 60).toInt())
        }

        /** Frames a click is given to land before its preview is taken — the contact sheet's own wait. */
        const val PREVIEW_HOLD = 95

        /** Pixels across a preview; the height follows the canvas. */
        const val PREVIEW_WIDTH = 768
    }
}
