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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import slideshow.drawers.PlaceholderSlide
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
 *                                ?rev=                         … refused (409) if the file moved since,
 *                                                              or if no rev is sent; ?force=1 writes anyway
 *     PUT  /api/modules          a modules file's contents     save the slides to build
 *     PUT  /api/intents          an intents file's contents    save the intended update per slide
 *     PUT  /api/feedback         a feedback file's contents    save the notes written against slides
 *     PUT  /api/midi             a midi file's contents        save which slides are wanted as MIDI
 *     PUT  /api/subtitles        a subtitles file's contents   save what is said over each state
 *                                ?track=extended               … of the extended track
 *     POST /api/subtitle-mode    { "on": true }?               subtitles on the wall on or off
 *     POST /api/subtitle-track   { "track": "extended" }       which track is read; extended presents
 *     POST /api/voice-mode       { "on": true }?               the rendered voice spoken or not
 *     POST /api/mix              { "layer": "voice", "gain": 0.3 }  one track of the sound's level
 *     GET  /api/voice                                          the states rendered as speech, per track
 *     POST /api/subtitle-cards   { "text": "..." }             the cards a line comes out as, timed
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
 * **A save never writes over a file the page did not read.** Every launch of the organizer opens
 * a tab of its own and the old ones keep working, so two pages holding two copies of the order
 * are the ordinary case, not a corner: the one saved second wrote its older copy over the first,
 * and placements made in one tab were gone. So each file the page writes has a revision ([revs]),
 * handed to the page with the show and on every poll; a PUT carries the revision it was read at
 * as `?rev=`, and one that no longer matches is refused with a 409 rather than written. A page
 * with nothing unsaved reads the files again when a revision moves under it.
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
    private val midiDir: File = File("midi"),
    private val subtitlesFile: File = File("show-subtitles.json"),
    /** The extended track's file — see [SubtitleTrack]. */
    private val subtitlesExtendedFile: File = File("show-subtitles-extended.json"),
    /** Where the tracks' voices are rendered, `<track>/<id>-<LETTER>.wav` — see [VoiceTrack]. Null has none. */
    private val voiceDir: File? = null,
    /** Open the page in a browser once it is up (on a Mac). */
    private val open: Boolean = true,
    /**
     * Set when this is the launcher's server rather than the show's: there is no draw loop behind
     * it, and the show is a [Presentation] started from the page. See [forward].
     */
    private val presentation: Presentation? = null
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
    /** The show's sound muted or not; null flips it. See [Speakers.muted]. */
    data class Mute(val on: Boolean?) : Command
    /** Subtitle mode on or off; null flips it. See [Subtitles]. */
    data class Subtitle(val on: Boolean?) : Command
    /** Say these lines from now on — a save from the page, played at once — on [track]. */
    data class ApplySubtitles(val subtitles: Subtitles, val track: SubtitleTrack = SubtitleTrack.DEFAULT) : Command
    /** Read this track from now on. See [SubtitleTrack]: the extended one runs the deck. */
    data class Track(val track: SubtitleTrack) : Command
    /** The voice spoken or not; null flips it. See [VoiceTrack]. */
    data class Voice(val on: Boolean?) : Command
    /** One track of the sound at this gain. See [Layer]. */
    data class Mix(val layer: Layer, val gain: Double) : Command
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
        val grid: Boolean = false,
        val muted: Boolean = false, val hasSound: Boolean = false,
        val subtitles: Boolean = false,
        val subtitleTrack: SubtitleTrack = SubtitleTrack.DEFAULT,
        val voice: Boolean = false,
        val mix: Map<Layer, Double> = emptyMap()
    )

    val commands = ConcurrentLinkedQueue<Command>()

    @Volatile
    var snapshot = Snapshot(0, "", 0, 1, 0, 0, 0L)

    /** When a preview was last written, so the page knows to fetch it again. */
    @Volatile
    var previewStamp = if (presentation != null) newestPreview() else 0L

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

    /**
     * What is said over each state, as last read or saved. Unlike the intents it reaches the wall
     * in subtitle mode, so a save queues an [ApplySubtitles].
     */
    @Volatile
    var subtitles: Subtitles = Subtitles.readOrEmpty(subtitlesFile.path)
        private set

    /** The extended track, as last read or saved. */
    @Volatile
    var subtitlesExtended: Subtitles = Subtitles.readOrEmpty(subtitlesExtendedFile.path)
        private set

    /** The pace the show cuts and times its cards at; the page asks for cards at the same one. */
    private val pace = Pace(show.settings.subtitleCps)

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

    /** The files the page writes, by the name it gives them. */
    private val pageFiles
        get() = mapOf(
            "order" to orderFile, "modules" to modulesFile, "intents" to intentsFile,
            "subtitles" to subtitlesFile, "subtitlesExtended" to subtitlesExtendedFile,
            "feedback" to feedbackFile, "midi" to midiFile
        )

    /**
     * A file's revision: a checksum of what it holds, or `none` while there is no file. What it
     * holds rather than when it was written, because the save button writes all five of its files
     * whether or not they changed — by date, one tab's save flagged every file in every other tab.
     * Read again only when the file's date or length moves, since the page polls several times a
     * second.
     */
    private fun revOf(file: File): String {
        if (!file.isFile) return "none"
        val stat = "${file.lastModified()}-${file.length()}"
        revCache[file.path]?.let { (at, rev) -> if (at == stat) return rev }
        val bytes = file.readBytes()
        val rev = "${java.util.zip.CRC32().apply { update(bytes) }.value.toString(36)}-${bytes.size.toString(36)}"
        revCache[file.path] = stat to rev
        return rev
    }

    private val revCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    /** Every file the page writes, at its current revision. */
    private fun revs(): JsonObject = buildJsonObject { pageFiles.forEach { (key, file) -> put(key, revOf(file)) } }

    /**
     * Answers 409 and returns true when the page is saving over a copy of [key] it did not read:
     * another tab saved it since, or it was changed on disk. `force=1` is the page's "save over
     * them", asked for by name. A request with neither is a page loaded before this rule — which is
     * to say one of the old tabs the rule is for — and it is refused too, asking for a reload.
     */
    private fun refused(exchange: HttpExchange, key: String): Boolean {
        if (exchange.query("force") == "1") return false
        val file = pageFiles.getValue(key)
        val asked = exchange.query("rev")?.takeIf { it.isNotBlank() } ?: run {
            println("organizer: refused to save ${file.path} from a page older than the organizer")
            exchange.json(409, buildJsonObject {
                put("error", "this organizer page is older than the organizer serving it, so it cannot tell whether ${file.path} changed since — reload the page. Nothing was written.")
                put("file", key)
            })
            return true
        }
        val now = revOf(file)
        if (asked == now) return false
        println("organizer: refused to save ${file.path} over a newer copy (another organizer tab, or an edit on disk)")
        exchange.json(409, buildJsonObject {
            put("error", "${file.path} changed since this page read it — another organizer tab saved it, or it was edited on disk. Nothing was written.")
            put("file", key)
            put("rev", now)
        })
        return true
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
        if (open && System.getProperty("os.name").orEmpty().lowercase().contains("mac")) {
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
            val p = presentation
            if (p != null && path.startsWith("/api/")) {
                if (path == "/api/presentation") return presentationRoute(exchange, p)
                val port = p.port
                val body = exchange.bytes()
                // The launcher keeps the mute, so the next show starts as this one was left — and
                // with no show up, the button still sets how the next one starts.
                if (path == "/api/mute" && method == "POST") {
                    val on = runCatching { Json.parseToJsonElement(body.decodeToString()).jsonObject["on"]?.jsonPrimitive?.booleanOrNull }.getOrNull()
                    p.muted = on ?: !p.muted
                    if (!p.live) return exchange.json(200, presentationJson(p))
                }
                if (path == "/api/subtitle-mode" && method == "POST") {
                    val on = runCatching { Json.parseToJsonElement(body.decodeToString()).jsonObject["on"]?.jsonPrimitive?.booleanOrNull }.getOrNull()
                    p.subtitles = on ?: !p.subtitles
                    if (!p.live) return exchange.json(200, presentationJson(p))
                }
                if (path == "/api/mix" && method == "POST") {
                    runCatching { Json.parseToJsonElement(body.decodeToString()).jsonObject }.getOrNull()?.let { o ->
                        val layer = Layer.of(o["layer"]?.jsonPrimitive?.contentOrNull)
                        val gain = o["gain"]?.jsonPrimitive?.doubleOrNull
                        if (layer != null && gain != null) p.mix[layer] = gain.coerceIn(0.0, MAX_MIX)
                    }
                    if (!p.live) return exchange.json(200, presentationJson(p))
                }
                if (path == "/api/voice-mode" && method == "POST") {
                    val on = runCatching { Json.parseToJsonElement(body.decodeToString()).jsonObject["on"]?.jsonPrimitive?.booleanOrNull }.getOrNull()
                    p.voice = on ?: !p.voice
                    if (!p.live) return exchange.json(200, presentationJson(p))
                }
                if (path == "/api/subtitle-track" && method == "POST") {
                    val track = runCatching { Json.parseToJsonElement(body.decodeToString()).jsonObject["track"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    p.subtitleTrack = SubtitleTrack.of(track)
                    if (!p.live) return exchange.json(200, presentationJson(p))
                }
                if (port != null && p.live && path != "/api/sketches") return forward(exchange, port, p, body)
                if (path in LIVE_ONLY) return exchange.json(409, error("the presentation is not running — start it first"))
            }
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
                path == "/api/mute" && method == "POST" -> {
                    commands += Mute(exchange.body()["on"]?.jsonPrimitive?.booleanOrNull)
                    exchange.json(202, ok())
                }
                path == "/api/subtitle-mode" && method == "POST" -> {
                    commands += Subtitle(exchange.body()["on"]?.jsonPrimitive?.booleanOrNull)
                    exchange.json(202, ok())
                }
                path == "/api/mix" && method == "POST" -> {
                    val b = exchange.body()
                    val layer = Layer.of(b["layer"]?.jsonPrimitive?.contentOrNull)
                    val gain = b["gain"]?.jsonPrimitive?.doubleOrNull
                    if (layer == null || gain == null) exchange.json(400, error("mix needs a layer (voice, design, music) and a gain"))
                    else { commands += Mix(layer, gain); exchange.json(202, ok()) }
                }
                path == "/api/voice-mode" && method == "POST" -> {
                    commands += Voice(exchange.body()["on"]?.jsonPrimitive?.booleanOrNull)
                    exchange.json(202, ok())
                }
                path == "/api/subtitle-track" && method == "POST" -> {
                    commands += Track(SubtitleTrack.of(exchange.body()["track"]?.jsonPrimitive?.contentOrNull))
                    exchange.json(202, ok())
                }
                path == "/api/subtitle-cards" && method == "POST" -> {
                    // The wall's own cutting and timing, so the page never carries a second copy of it.
                    val text = exchange.body()["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val cards = pace.cards(text)
                    exchange.json(200, buildJsonObject {
                        put("seconds", seconds(cards.lastOrNull()?.end ?: 0))
                        put("cards", JsonArray(cards.map { card ->
                            buildJsonObject {
                                put("lines", JsonArray(card.lines.map { JsonPrimitive(it) }))
                                put("at", seconds(card.at)); put("seconds", seconds(card.length))
                            }
                        }))
                    })
                }
                path == "/api/subtitles" && method == "PUT" -> {
                    // `?track=extended` saves the extended track; without it, the default one.
                    val track = SubtitleTrack.of(exchange.query("track"))
                    if (refused(exchange, if (track == SubtitleTrack.EXTENDED) "subtitlesExtended" else "subtitles")) return
                    val file = if (track == SubtitleTrack.EXTENDED) subtitlesExtendedFile else subtitlesFile
                    val was = if (track == SubtitleTrack.EXTENDED) subtitlesExtended else subtitles
                    val text = exchange.bytes().decodeToString()
                    val next = runCatching { Subtitles.parse(text) }.getOrElse {
                        exchange.json(400, error("not a subtitles file: ${it.message}"))
                        return
                    }
                    // The header and the voice-over beside the lines are the file's, not the page's.
                    val kept = Subtitles(next.lines, next.note ?: was.note, was.extra + next.extra)
                    kept.write(file)
                    if (track == SubtitleTrack.EXTENDED) subtitlesExtended = kept else subtitles = kept
                    commands += ApplySubtitles(kept, track)
                    println("organizer: saved ${file.path} (${kept.size} slides with a line)")
                    exchange.json(200, buildJsonObject { put("saved", file.path); put("applied", true); put("rev", revOf(file)) })
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
                    if (refused(exchange, "order")) return
                    val text = exchange.bytes().decodeToString()
                    val order = runCatching { Order.parse(text) }.getOrElse {
                        exchange.json(400, error("not an order: ${it.message}"))
                        return
                    }
                    order.write(orderFile)
                    commands += Apply(order)
                    if (presentation != null) show = runCatching { catalogue.arranged(order) }.getOrDefault(catalogue)
                    println("organizer: saved ${orderFile.path} (${order.refs().count { it.on }} slides on)")
                    exchange.json(200, buildJsonObject { put("saved", orderFile.path); put("applied", true); put("rev", revOf(orderFile)) })
                }
                path == "/api/modules" && method == "PUT" -> {
                    if (refused(exchange, "modules")) return
                    val text = exchange.bytes().decodeToString()
                    val next = runCatching { Modules.parse(text) }.getOrElse {
                        exchange.json(400, error("not a modules file: ${it.message}"))
                        return
                    }
                    val kept = Modules(next.modules, next.covered, next.source ?: modules.source)
                    kept.write(modulesFile)
                    modules = kept
                    commands += ApplyModules(kept)
                    if (presentation != null) reload()
                    println("organizer: saved ${modulesFile.path} (${kept.modules.size} modules to build)")
                    exchange.json(200, buildJsonObject { put("saved", modulesFile.path); put("applied", true); put("rev", revOf(modulesFile)) })
                }
                path == "/api/intents" && method == "PUT" -> {
                    if (refused(exchange, "intents")) return
                    val text = exchange.bytes().decodeToString()
                    val next = runCatching { Intents.parse(text) }.getOrElse {
                        exchange.json(400, error("not an intents file: ${it.message}"))
                        return
                    }
                    // The file's own header is the show's, not the page's, so it is kept.
                    val kept = Intents(next.intents, next.note ?: intents.note)
                    kept.write(intentsFile)
                    intents = kept
                    println("organizer: saved ${intentsFile.path} (${kept.size} slides with an intent)")
                    exchange.json(200, buildJsonObject { put("saved", intentsFile.path); put("applied", true); put("rev", revOf(intentsFile)) })
                }
                path == "/api/feedback" && method == "PUT" -> {
                    if (refused(exchange, "feedback")) return
                    val text = exchange.bytes().decodeToString()
                    val next = runCatching { Feedback.parse(text) }.getOrElse {
                        exchange.json(400, error("not a feedback file: ${it.message}"))
                        return
                    }
                    val kept = Feedback(next.items, next.note ?: feedback.note)
                    kept.write(feedbackFile)
                    feedback = kept
                    println("organizer: saved ${feedbackFile.path} (${kept.open} open of ${kept.total} notes)")
                    exchange.json(200, buildJsonObject { put("saved", feedbackFile.path); put("open", kept.open); put("rev", revOf(feedbackFile)) })
                }
                path == "/api/midi" && method == "PUT" -> {
                    if (refused(exchange, "midi")) return
                    val text = exchange.bytes().decodeToString()
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
                        put("rev", revOf(midiFile))
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
                // What has been rendered as speech, per track — polled by the page while the
                // renderer runs, so a line appears with a play button the moment it is written.
                path == "/api/voice" && method == "GET" -> exchange.json(200, voiceJson())
                path.startsWith("/voice/") && method == "GET" -> {
                    val name = path.removePrefix("/voice/")
                    val file = voiceDir?.let { File(it, name) }
                    if (!VOICE_NAME.matches(name) || file == null || !file.isFile) exchange.send(404, ByteArray(0), "text/plain")
                    else exchange.send(200, file.readBytes(), "audio/wav", cache = false)
                }
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
        } finally {
            body.remove()
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
        // Taken before anything is read, so a write landing in between leaves the page a revision
        // behind what it holds — read again on the next poll — rather than ahead of it.
        val revs = revs()
        val declared = Order.of(catalogue)
        val order = if (orderFile.isFile) runCatching { Order.read(orderFile) }.getOrDefault(declared) else declared
        val catalogueIds = catalogue.slideIds
        return buildJsonObject {
            put("canvas", buildJsonObject { put("width", show.settings.width); put("height", show.settings.height) })
            put("orderFile", orderFile.path)
            put("hasOrderFile", orderFile.isFile)
            put("modulesFile", modulesFile.path)
            put("hasModulesFile", modulesFile.isFile)
            put("revs", revs)
            put("running", JsonArray(ids.map { JsonPrimitive(it) }))
            put("catalogue", JsonArray(catalogueIds.mapIndexed { i, id -> slideJson(catalogue, i, id) }))
            put("order", order.toJson())
            put("declared", declared.toJson())
            put("modules", modules.toJson())
            put("intentsFile", intentsFile.path)
            put("intents", intents.toJson())
            put("subtitlesFile", subtitlesFile.path)
            put("subtitles", subtitles.toJson())
            put("subtitlesExtendedFile", subtitlesExtendedFile.path)
            put("subtitlesExtended", subtitlesExtended.toJson())
            // The voices rendered per track, seconds a state — read off the folder each time, since
            // the renderer writes it from outside the show.
            put("voiceDir", voiceDir?.path ?: "")
            put("voice", voiceJson())
            put("subtitleCps", pace.cps)
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
            (slide as? SketchWall)?.let { wall ->
                put("sketch", wall.sketch)
                wall.variantName?.let { put("sketchVariant", it) }
                File("sketch-previews/${wall.sketch}.png").takeIf { it.isFile }
                    ?.let { put("sketchPreview", "/sketch-previews/${wall.sketch}.png?t=${it.lastModified()}") }
            }
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

    /**
     * `{ track: { "<id>-<LETTER>": seconds } }` for every track with a rendered voice, and under
     * `flagged` the states whose best render still failed the renderer's read-back check.
     */
    private fun voiceJson(): JsonObject = buildJsonObject {
        val flagged = mutableListOf<String>()
        SubtitleTrack.entries.forEach { track ->
            val v = VoiceTrack.read(voiceDir?.path, track) ?: return@forEach
            put(track.key, buildJsonObject { v.seconds().forEach { (k, s) -> put(k, s) } })
            v.flagged.forEach { flagged += "${track.key}/$it" }
        }
        put("flagged", JsonArray(flagged.map { JsonPrimitive(it) }))
    }

    private fun stateJson(): JsonObject {
        val s = snapshot
        return buildJsonObject {
            put("index", s.index); put("id", s.id); put("step", s.step); put("steps", s.steps)
            put("frame", s.frame); put("previewsLeft", s.previewsLeft); put("previewStamp", s.previewStamp)
            put("concrete", s.concrete); put("hasConcrete", s.hasConcrete); put("grid", s.grid)
            put("muted", s.muted); put("hasSound", s.hasSound); put("subtitles", s.subtitles)
            put("subtitleTrack", s.subtitleTrack.key); put("voice", s.voice)
            put("mix", buildJsonObject { s.mix.forEach { (l, g) -> put(l.key, g) } })
            put("exporting", exporting); put("exportDone", exportDone); put("exportTotal", exportTotal)
            put("exportFrame", exportFrame); put("exportFrames", exportFrames)
            put("revs", revs())
            presentation?.let { put("presentation", presentationJson(it)) }
        }
    }

    // ------------------------------------------------------------------------------ //
    // The launcher's half: a server with no show behind it until the page starts one.

    private fun presentationJson(p: Presentation) = buildJsonObject {
        put("running", p.running); put("starting", p.starting); put("live", p.live)
        put("projection", p.projection); put("muted", p.muted); put("subtitles", p.subtitles)
        put("subtitleTrack", p.subtitleTrack.key); put("voice", p.voice)
        put("mix", buildJsonObject { p.mix.forEach { (l, g) -> put(l.key, g) } })
    }

    /**
     * GET says whether the show is up; POST `{ "action": "start" | "stop" }` starts or stops it.
     * `start` may name a slide `id` to open on, which is handed to the show as `SLIDES_START`.
     * `{ "action": "projection", "on": true }` puts the next start on the projectors.
     */
    private fun presentationRoute(exchange: HttpExchange, p: Presentation) {
        if (exchange.requestMethod == "POST") {
            val body = exchange.body()
            when (body["action"]?.jsonPrimitive?.contentOrNull) {
                "start" -> {
                    // SLIDES_START takes a number counting from 1 in the running order.
                    val at = body["id"]?.jsonPrimitive?.contentOrNull?.let { ids.indexOf(it) }?.takeIf { it >= 0 }
                    p.start(if (at != null) mapOf("SLIDES_START" to "${at + 1}") else emptyMap())
                }
                "stop" -> p.stop()
                // Takes effect on the next start: a window cannot be moved onto the wall from here.
                "projection" -> p.projection = body["on"]?.jsonPrimitive?.booleanOrNull ?: !p.projection
                else -> return exchange.json(400, error("action is start, stop or projection"))
            }
        }
        exchange.json(200, presentationJson(p))
    }

    private val client by lazy { HttpClient.newHttpClient() }

    /**
     * Hands a request to the show's own server and its answer back to the page, so while the
     * show is up it is the one state — saves are written and played there, exactly as when it
     * serves the page itself. The state gains the presentation's standing on the way through.
     */
    private fun forward(exchange: HttpExchange, port: Int, p: Presentation, body: ByteArray) {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port${exchange.requestURI}"))
            .method(exchange.requestMethod, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
            .apply { exchange.requestHeaders.getFirst("Content-Type")?.let { header("Content-Type", it) } }
            .build()
        val response = runCatching { client.send(request, HttpResponse.BodyHandlers.ofByteArray()) }.getOrElse {
            return exchange.json(503, error("the presentation is not answering (${it.message})"))
        }
        var bytes = response.body()
        if (exchange.requestURI.path == "/api/state" && response.statusCode() == 200) {
            runCatching {
                val state = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                bytes = JsonObject(state + ("presentation" to presentationJson(p))).toString().toByteArray()
            }
        }
        val type = response.headers().firstValue("Content-Type").orElse("application/json; charset=utf-8")
        exchange.send(response.statusCode(), bytes, type)
    }

    /**
     * Reads the files again and rebuilds the catalogue from them — for the launcher once the show
     * it started has gone, since everything saved while the show was up was saved there.
     */
    fun reload() {
        modules = if (modulesFile.isFile) runCatching { Modules.read(modulesFile) }.getOrDefault(Modules.EMPTY) else Modules.EMPTY
        intents = if (intentsFile.isFile) runCatching { Intents.read(intentsFile) }.getOrDefault(Intents.EMPTY) else Intents.EMPTY
        feedback = if (feedbackFile.isFile) runCatching { Feedback.read(feedbackFile) }.getOrDefault(Feedback.EMPTY) else Feedback.EMPTY
        subtitles = Subtitles.readOrEmpty(subtitlesFile.path)
        subtitlesExtended = Subtitles.readOrEmpty(subtitlesExtendedFile.path)
        midi = if (midiFile.isFile) runCatching { MidiWanted.read(midiFile) }.getOrDefault(MidiWanted.EMPTY) else MidiWanted.EMPTY
        catalogue = runCatching { catalogue.withModules(modules, references) }.getOrDefault(catalogue)
        show = runCatching { catalogue.arranged(currentOrder()) }.getOrDefault(catalogue)
        previewStamp = newestPreview()
    }

    private fun newestPreview(): Long = previewDir.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L

    private fun ok() = buildJsonObject { put("ok", true) }
    private fun error(message: String) = buildJsonObject { put("error", message) }

    /**
     * The request's body, read once per request. The launcher reads it to decide whether to
     * forward before any route sees it, and a stream read twice is empty the second time — every
     * save made with no show running came through as nothing. Kept on the handling thread rather
     * than as an exchange attribute: the JDK's server stores those in the context's one shared
     * map, so the first request's body was handed to every request after it.
     */
    private fun HttpExchange.bytes(): ByteArray {
        val kept = body.get()
        if (kept != null && kept.first === this) return kept.second
        return requestBody.readBytes().also { body.set(this to it) }
    }

    private val body = ThreadLocal<Pair<HttpExchange, ByteArray>>()

    /** A parameter off the request's query string, decoded. */
    private fun HttpExchange.query(name: String): String? =
        requestURI.rawQuery?.split("&")?.firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }

    private fun HttpExchange.body(): JsonObject =
        runCatching { Json.parseToJsonElement(bytes().decodeToString()).jsonObject }
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
        /** What only a running show can do: the launcher refuses these while there is none. */
        private val LIVE_ONLY = setOf(
            "/api/go", "/api/click", "/api/replay", "/api/concrete", "/api/grid", "/api/previews", "/api/export"
        )

        private val PREVIEW_NAME = Regex("^[a-z0-9-]+-[0-2]\\.png$")
        private val VOICE_NAME = Regex("^(default|extended)/[a-z0-9-]+-[A-Z]{1,2}\\.wav$")
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
