package slideshow

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import org.openrndr.shape.ShapeContour
import slideshow.drawers.advanceWithSubscripts
import slideshow.drawers.setLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Where the autocue goes — set in the organizer's Projection tab beside the projectors, and kept in
 * `show-projection.json` with them. Whether it is on at all is a switch of the show ([Settings.autocue]).
 *
 * **Two places.** [WALL] draws it on top of the show's own window, over the wall, like the grid: its
 * 1920 by 1080 stands at [x], [y] on the canvas at [scale], so over the left projector at 1 it *is*
 * that projector — a rehearsal with the speaker's screen where the chapter card was, and nothing else
 * to plug in. [WINDOW] gives it a window of its own, to put on the speaker's screen: [windowX],
 * [windowY] on the desktop in points (null leaves it to macOS), [width] by [height] (1920 by 1080, or
 * half that to look at on a laptop), and [undecorated] to fill a screen with no title bar. [display]
 * is the name of the screen it was put on in the tab, a label only.
 *
 * Every one of these moves the running show at once: the window is opened the first time it is
 * wanted, and moved, resized and hidden from then on rather than closed.
 */
data class AutocueSetup(
    val place: String = WINDOW,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val scale: Double = 1.0,
    val windowX: Int? = null,
    val windowY: Int? = null,
    val width: Int = WIDTH,
    val height: Int = HEIGHT,
    val undecorated: Boolean = false,
    val display: String? = null
) {
    val ownWindow: Boolean get() = place == WINDOW

    companion object {
        const val WALL = "wall"
        const val WINDOW = "window"
        /** What the autocue is composed at, whatever it is shown at. */
        const val WIDTH = 1920
        const val HEIGHT = 1080

        fun json(a: AutocueSetup): JsonObject = buildJsonObject {
            put("place", a.place)
            put("x", a.x); put("y", a.y); put("scale", a.scale)
            put("window", buildJsonObject {
                put("x", a.windowX); put("y", a.windowY)
                put("width", a.width); put("height", a.height)
                put("undecorated", a.undecorated)
            })
            put("display", a.display)
        }

        /** [o] read over [base]: anything missing or out of range keeps [base]'s value. */
        fun parse(o: JsonObject?, base: AutocueSetup = AutocueSetup()): AutocueSetup {
            if (o == null) return base
            fun num(k: String, d: Double) = o[k]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && abs(it) <= 16384 } ?: d
            val w = runCatching { o["window"]?.jsonObject }.getOrNull()
            fun size(k: String, d: Int) = w?.get(k)?.jsonPrimitive?.intOrNull?.takeIf { it in 160..16384 } ?: d
            return AutocueSetup(
                place = o["place"]?.jsonPrimitive?.contentOrNull?.takeIf { it == WALL || it == WINDOW } ?: base.place,
                x = num("x", base.x), y = num("y", base.y),
                scale = num("scale", base.scale).takeIf { it in 0.1..4.0 } ?: base.scale,
                windowX = if (w != null && "x" in w) w["x"]?.jsonPrimitive?.intOrNull else base.windowX,
                windowY = if (w != null && "y" in w) w["y"]?.jsonPrimitive?.intOrNull else base.windowY,
                width = size("width", base.width), height = size("height", base.height),
                undecorated = w?.get("undecorated")?.jsonPrimitive?.booleanOrNull ?: base.undecorated,
                display = if ("display" in o) o["display"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } else base.display
            )
        }
    }
}

/**
 * When each word of a state's line is said, in frames from the state being reached — read off the very
 * cards the subtitles are cut into ([Pace.cards]), so the autocue scrolls at the pace the wall's subtitle
 * comes up at: a speaker's 15 characters a second, or with the voice on, the voice's own words.
 *
 * [end] is the frame the line is said by, which is when the autocue offers the click.
 */
class CueTiming private constructor(private val at: IntArray, private val chars: IntArray, val total: Int, val end: Int) {

    /** How far through the line the reading has come [elapsed] frames in, in characters, 0 to [total]. */
    fun charAt(elapsed: Int): Double {
        val n = at.size
        if (n == 0 || total == 0) return if (elapsed >= end) total.toDouble() else 0.0
        if (elapsed <= at[0]) return 0.0
        var i = 0
        while (i + 1 < n && at[i + 1] <= elapsed) i++
        val nextChar = if (i + 1 < n) chars[i + 1] else total
        val nextAt = if (i + 1 < n) at[i + 1] else end
        if (nextAt <= at[i]) return nextChar.toDouble()
        val f = ((elapsed - at[i]).toDouble() / (nextAt - at[i])).coerceIn(0.0, 1.0)
        return chars[i] + (nextChar - chars[i]) * f
    }

    /** The frame character [c] of the line is said on: [charAt] read the other way. */
    fun timeAt(c: Double): Double {
        val n = at.size
        if (n == 0 || total == 0) return end.toDouble()
        if (c <= chars[0]) return at[0].toDouble()
        var i = 0
        while (i + 1 < n && chars[i + 1] <= c) i++
        val nextChar = if (i + 1 < n) chars[i + 1] else total
        val nextAt = if (i + 1 < n) at[i + 1] else end
        if (nextChar <= chars[i]) return at[i].toDouble()
        return at[i] + (nextAt - at[i]) * ((c - chars[i]) / (nextChar - chars[i])).coerceIn(0.0, 1.0)
    }

    companion object {
        val NONE = CueTiming(IntArray(0), IntArray(0), 0, 0)

        /** [said] as the subtitles time it — with [fit] and [speech] where the voice is rendered, as [Pace.cards] takes them. */
        fun of(said: String, pace: Pace, fit: Int? = null, speech: Speech? = null): CueTiming {
            val flat = flat(said)
            if (flat.isEmpty()) return NONE
            val words = flat.split(' ')
            val chars = IntArray(words.size)
            var c = 0
            words.forEachIndexed { i, w -> chars[i] = c; c += w.length + 1 }
            val cards = pace.cards(said, fit, speech)
            val end = cards.lastOrNull()?.end ?: 0
            // A card's words are the line's words in order — the cards cut it only at spaces — so they pair
            // one for one. Should they ever not, the reading runs evenly through the characters instead.
            val times = cards.flatMap { card -> pace.wordTimes(card).map { card.at + it } }
            val lead = frames(Pace.LEAD_SECONDS)
            val at = if (times.size == words.size) times.toIntArray()
                else IntArray(words.size) { lead + ((end - lead) * chars[it].toDouble() / flat.length).toInt() }
            return CueTiming(at, chars, flat.length, end)
        }

        /** The line with its white space run together, which is what is laid out and timed. */
        fun flat(said: String) = said.replace(Regex("\\s+"), " ").trim()
    }
}

/**
 * The autocue: the speaker's screen, what is to be said over the state on the wall and what comes next,
 * composed at 1920 by 1080 and shown wherever [AutocueSetup] says — on top of the show's window, or a
 * window of its own on the speaker's screen (12 October 2026).
 *
 * **The whole talk is one column**, a block a state in the running order: a hairline divider naming the
 * state — the slide's number and title on its first, the nameplate's letter on every one — then the line
 * the subtitle track gives it, set large, or `stil` where it gives none. So the speaker reads on through
 * the evening the way a teleprompter runs, and sees the next state's words below the one being said.
 *
 * **It scrolls with the speaking, and stops for the click.** The reading line stands a little above the
 * middle, marked at both ends. Through a state it moves through the line on the subtitles' own timing
 * ([CueTiming]) — the word being said is on the reading line — and at the end of the line it stops. Once
 * the line has been said *and* the wall has stopped moving, the next divider glides up onto the reading
 * line and goes green: KLIK, the presenter's to take when ready. **Autoplay is the autocue's own option**
 * ([Settings.autoplay], off unless asked for): on, the show continues by itself once each state is done, voice or
 * no voice, and the foot counts down to that click for the whole of every state — AUTO, the seconds, a line
 * coming down — while the next divider says AUTO in blue rather than KLIK in green. It follows the speaking a line at a time and smoothly, never word by word —
 * see [reading]. The click starts the next state's line from its first word. A speaker who is
 * slower than the estimate finds the words just above the line; one who clicks early sees the scroll ease
 * on to where the click took the wall. Nothing scrolls past a click that has not been made.
 *
 * **It is a function of the state and the frames since it was reached**, the clock the subtitles keep,
 * plus one remembered number: where the scroll stood when the state changed, so a click is eased into
 * rather than jumped. The driver keeps that; this class only lays out and draws.
 *
 * It is set in the talk's own face, through [setLine] so CO₂ is set. The labels are Dutch: it is the
 * speaker's screen, and the script is Dutch.
 *
 * **What is done is drawn apart from what is said** (19 October 2026). A track may carry directions beside its
 * lines ([Subtitles.directions]) — Erik's autocue does, off the REGIE blocks of his script — and a state's
 * directions stand under its line in amber, in the labels' face and a bar beside each, never in the script's
 * white: a direction is not to be read aloud. The reading follows the spoken lines; a state with a direction
 * and nothing to say stands its direction on the reading line.
 *
 * **A line keeps the breaks it was written with**, and a blank line between two is a paragraph (19 October 2026):
 * a speech written a sentence a line, with lists in it — Karim Weiss's welcome — is read the way it was written,
 * where run together it would be a wall of text.
 *
 * **Who speaks is always marked, small** (19 October 2026): a pill with the speaker's name on every state's divider
 * and for the state on the wall in the header — the track's own speaker in light grey, anyone else in cyan (Karim
 * Weiss's welcome and closing in Erik's script), and an amber outlined REGIE where a state has a direction and
 * nothing to say. Amber stays the colour of what is not read aloud.
 */
class Autocue(
    private val face: FontImageMap?,
    /** The script's type size, in pixels of the 1920 by 1080 the autocue is composed at — the size [face] was loaded at. */
    private val size: Double,
    private val small: FontImageMap?,
    private val large: FontImageMap?
) {
    /**
     * One state of the running order: its slide's [index] and [number] in it, the slide's [title], the line
     * [said] over it, and what is done over it instead or besides, [directions].
     */
    class Entry(val index: Int, val step: Int, val number: Int, val title: String, val said: String,
                val directions: List<String> = emptyList(), val speaker: String? = null,
                /** Whether [speaker] is someone other than the script's own speaker. */
                val guest: Boolean = false)

    /** A line of a block, [start] to [next] the characters of the state's flat line it carries. */
    private class Line(val text: String, val top: Double, val start: Int, val next: Int)

    /** A line of a direction, the [group]-th of its state's directions. */
    private class Cue(val text: String, val top: Double, val group: Int)

    private class Block(val entry: Entry, val divider: Double, val top: Double, val bottom: Double, val lines: List<Line>,
                        val cues: List<Cue> = emptyList())

    /** The status the foot shows, and the reading line's colour. */
    enum class Status { SPEAKING, WAITING, READY, AUTO, END }

    /** What the driver hands over each frame. */
    class Now(
        val index: Int, val step: Int, val steps: Int,
        /** The chapter or moment, and the slide's title. */
        val section: String, val title: String, val number: Int, val count: Int,
        /** The script's own y at the reading line. */
        val scroll: Double,
        val status: Status,
        /** Seconds left of what [status] counts, and how much of the line is said, 0 to 1. */
        val left: Double, val said: Double,
        /** What the next click brings: a label for it, and the thing itself. */
        val nextLabel: String, val next: String,
        val clock: String,
        /** For the pulse of a click that is ready. */
        val frame: Int,
        /** Whether the next divider stands on the reading line: the line said and the wall still. */
        val offered: Boolean = status == Status.READY,
        /** Under [Status.AUTO], how much of the wait to the click is still to go, 1 coming down to 0. */
        val countdown: Double = 0.0,
        /** Whether the speaking stands paused (space): the scroll, the voice and autoplay hold, and the foot says so. */
        val paused: Boolean = false,
        /** A click just made, 1 coming down to 0: the status box flashes light, so every click is seen to land. */
        val felt: Double = 0.0,
        /** The track the script is read from, named in the header: "Erik autocue", "aligned". */
        val track: String = "",
        /** Who reads this state: "Erik Koremans", "Karim Weiss"; null where nothing is said. */
        val speaker: String? = null,
        /** Whether [speaker] is someone other than the script's own speaker. */
        val guest: Boolean = false,
        /** Whether the state has a direction and nothing to say: marked REGIE. */
        val regie: Boolean = false
    )

    private var blocks: List<Block> = emptyList()
    private var blockAt: Map<Pair<Int, Int>, Int> = emptyMap()
    private var laidFor: Any? = null
    private val lead get() = size * LEADING

    /** Lays the talk out, once for each [key] — the running order and the track it reads — off the [entries] asked for then. */
    fun layout(key: Any, entries: () -> List<Entry>) {
        if (key == laidFor) return
        laidFor = key
        val font = face
        val all = entries()
        val out = ArrayList<Block>(all.size)
        var y = 0.0
        for (e in all) {
            val divider = y + DIVIDER / 2.0
            y += DIVIDER
            val top = y
            val flat = CueTiming.flat(e.said)
            // Each line of the source wrapped on its own and started on a fresh line, a blank line before it a
            // paragraph. The pieces are the flat line cut where it had a break, so every character keeps its
            // place in the flat line the timing counts in.
            val wrapped = mutableListOf<Triple<String, Int, Boolean>>()
            if (font != null && flat.isNotEmpty()) {
                var at = 0
                var gap = false
                for (raw in e.said.split('\n')) {
                    val piece = CueTiming.flat(raw)
                    if (piece.isEmpty()) { if (wrapped.isNotEmpty()) gap = true; continue }
                    wrap(piece, font).forEachIndexed { i, (text, start) -> wrapped += Triple(text, at + start, gap && i == 0) }
                    at += piece.length + 1
                    gap = false
                }
            }
            var ly = top
            val lines = wrapped.mapIndexed { i, (text, start, paragraph) ->
                if (paragraph) ly += lead * PARAGRAPH
                Line(text, ly, start, wrapped.getOrNull(i + 1)?.second ?: flat.length).also { ly += lead }
            }
            val directions = e.directions.map { CueTiming.flat(it) }.filter { it.isNotEmpty() }
            y = if (lines.isEmpty()) y + (if (directions.isEmpty()) lead * SILENT else 0.0) else ly
            // The directions under the line, in the labels' face: a group a direction, a little apart.
            val cues = mutableListOf<Cue>()
            val cueFont = large
            if (cueFont != null) directions.forEachIndexed { g, d ->
                y += if (g == 0) (if (lines.isEmpty()) CUE_GAP / 2.0 else CUE_GAP) else CUE_SPACE
                for ((text, _) in wrap(d, cueFont, CUE_COLUMN)) { cues += Cue(text, y, g); y += CUE_LEAD }
            }
            out += Block(e, divider, top, y, lines, cues)
            y += GAP
        }
        blocks = out
        blockAt = out.withIndex().associate { (i, b) -> (b.entry.index to b.entry.step) to i }
    }

    /** [flat] broken between words to [column], each line with the character it starts on. */
    private fun wrap(flat: String, font: FontImageMap, column: Double = COLUMN): List<Pair<String, Int>> {
        val space = font.advanceWithSubscripts(" ")
        val out = mutableListOf<Pair<String, Int>>()
        val line = StringBuilder()
        var start = 0
        var width = 0.0
        var c = 0
        for (word in flat.split(' ')) {
            val w = font.advanceWithSubscripts(word)
            if (line.isNotEmpty() && width + space + w > column) {
                out += line.toString() to start
                line.clear()
            }
            if (line.isEmpty()) { start = c; line.append(word); width = w }
            else { line.append(' ').append(word); width += space + w }
            c += word.length + 1
        }
        if (line.isNotEmpty()) out += line.toString() to start
        return out
    }

    /**
     * Where the reading stands in [b] [t] frames into its state: **a line at a time, not a word at a time.**
     * Each line's middle stands on the reading line at the moment its middle character is said, the path runs
     * straight from one line to the next, and it is then averaged over [SMOOTH] seconds either side — which
     * evens the speed out across a sentence's pauses and costs nothing in place: measured over the talk, the
     * band stands a fifth of a line off the line being said at the median, and never a whole line, at 0.6 s
     * or 1.4 s alike, while the fastest second against the median went from 2.5 to 1.7 times.
     *
     * Following the words was the first version, and it hiccuped. The voice's word times are matched to the
     * script by letters, so where Whisper heard a compound or a number differently several words share a
     * start and the scroll leapt across them in one frame — up to 59 px, measured over the talk — and even on
     * the estimate the speed changed word by word and a paragraph's short last line rushed by. Line by line,
     * smoothed, it never jumps and still says where in the line the voice is to within a line.
     */
    private fun reading(b: Block, timing: CueTiming, t: Double): Double {
        if (b.lines.isEmpty()) return b.cues.firstOrNull()?.let { it.top + CUE_LEAD / 2.0 } ?: (b.top + lead * SILENT / 2.0)
        val times = DoubleArray(b.lines.size) { timing.timeAt((b.lines[it].start + b.lines[it].next) / 2.0) }
        val ys = DoubleArray(b.lines.size) { b.lines[it].top + lead / 2.0 }
        fun path(u: Double): Double {
            if (u <= times[0]) return ys[0]
            for (k in 1 until times.size) if (u < times[k]) {
                val span = times[k] - times[k - 1]
                return if (span <= 0.0) ys[k] else ys[k - 1] + (ys[k] - ys[k - 1]) * (u - times[k - 1]) / span
            }
            return ys.last()
        }
        val half = SMOOTH * FPS
        var sum = 0.0
        for (k in 0 until SAMPLES) sum += path(t + half * (2.0 * (k + 0.5) / SAMPLES - 1.0))
        return sum / SAMPLES
    }

    /**
     * The script's y at the reading line for [index] and [step], [elapsed] frames into it on [timing], and
     * [glide] of the way from there on to the next state's divider — the click offered.
     */
    fun target(index: Int, step: Int, timing: CueTiming, elapsed: Int, glide: Double = 0.0): Double {
        val i = blockAt[index to step] ?: return 0.0
        val b = blocks[i]
        val here = reading(b, timing, elapsed.toDouble())
        if (glide <= 0.0) return here
        val next = blocks.getOrNull(i + 1)?.divider ?: (b.bottom + GAP + DIVIDER / 2.0)
        return here + (next - here) * glide.coerceAtMost(1.0)
    }

    /** Draws the autocue for [now] into a 1920 by 1080 frame; [wall] is what the wall shows, drawn small in the foot. */
    fun draw(drawer: Drawer, now: Now, wall: ColorBuffer? = null) {
        drawer.isolated {
            drawer.clear(BLACK)
            drawer.shadeStyle = null
            drawer.stroke = null
            script(drawer, now)
            header(drawer, now)
            foot(drawer, now, wall)
        }
    }

    private fun script(drawer: Drawer, now: Now) {
        val font = face ?: return
        val current = blockAt[now.index to now.step] ?: -1
        val offered = now.offered
        // the click is the presenter's in green, the show's own in blue
        val auto = now.status == Status.AUTO
        val go = if (auto) AUTO else GREEN
        val off = READ_Y - now.scroll
        // the reading band, behind the words
        drawer.fill = BAND
        drawer.rectangle(0.0, READ_Y - lead / 2.0, WIDTH, lead)

        drawer.drawStyle.clip = Rectangle(0.0, AREA_TOP, WIDTH, AREA_BOTTOM - AREA_TOP)
        var i = firstBelow(now.scroll - (READ_Y - AREA_TOP) - DIVIDER)
        while (i < blocks.size) {
            val b = blocks[i]
            if (b.divider + off - DIVIDER > AREA_BOTTOM) break
            divider(drawer, b, off, when {
                i == current + 1 && offered -> go
                i == current + 1 -> LABEL
                i == current -> LABEL
                else -> DARK
            }, i == current + 1, offered, if (auto) "AUTO" else "KLIK")
            if (b.lines.isEmpty() && b.cues.isEmpty()) {
                small?.let { s ->
                    drawer.fill = if (i == current) LABEL else DARK
                    drawer.setLine("stil — geen tekst", s, Vector2(COL_LEFT, b.top + off + lead * SILENT * 0.62), SMALL, SMALL)
                }
            } else for (l in b.lines) {
                val top = l.top + off
                if (top > AREA_BOTTOM || top + lead < AREA_TOP) continue
                drawer.fill = when {
                    i < current -> PAST
                    i == current -> if (top + lead <= READ_Y + 1.0) SAID else NOW
                    else -> AHEAD
                }
                drawer.setLine(l.text, font, Vector2(COL_LEFT, top + lead * BASELINE), size, size)
            }
            cues(drawer, b, off, when {
                i < current -> CUE_PAST
                i == current -> AMBER
                else -> CUE_AHEAD
            })
            i++
        }
        drawer.drawStyle.clip = null

        // The area's top and foot fade out, so a line arrives and leaves rather than being cut. A gradient
        // rather than bands of black: bands at fractional edges let the letters show through their seams as stripes.
        drawer.fill = BLACK
        drawer.shadeStyle = FADE_IN
        drawer.rectangle(0.0, AREA_TOP, WIDTH, FADE_TOP)
        drawer.shadeStyle = FADE_OUT
        drawer.rectangle(0.0, AREA_BOTTOM - FADE_FOOT, WIDTH, FADE_FOOT)
        drawer.shadeStyle = null

        // The reading line's two marks: green when the click is offered (blue when the show will make it), amber
        // while the wall still moves.
        drawer.fill = when {
            offered -> go
            now.status == Status.WAITING -> AMBER
            else -> MARK
        }
        val t = MARK_SIZE
        drawer.contour(ShapeContour.fromPoints(listOf(
            Vector2(COL_LEFT - 82.0, READ_Y - t), Vector2(COL_LEFT - 82.0 + t * 1.5, READ_Y), Vector2(COL_LEFT - 82.0, READ_Y + t)), true))
        drawer.contour(ShapeContour.fromPoints(listOf(
            Vector2(COL_RIGHT + 82.0, READ_Y - t), Vector2(COL_RIGHT + 82.0 - t * 1.5, READ_Y), Vector2(COL_RIGHT + 82.0, READ_Y + t)), true))
    }

    /** [b]'s directions, [off] down the screen in [colour]: each in the labels' face with a bar beside it. */
    private fun cues(drawer: Drawer, b: Block, off: Double, colour: ColorRGBa) {
        val l = large ?: return
        if (b.cues.isEmpty()) return
        drawer.fill = colour
        for (group in b.cues.groupBy { it.group }.values) {
            val top = group.first().top + off
            val bottom = group.last().top + CUE_LEAD + off
            if (bottom < AREA_TOP || top > AREA_BOTTOM) continue
            drawer.rectangle(COL_LEFT - 30.0, top + CUE_LEAD * 0.18, 6.0, bottom - top - CUE_LEAD * 0.36)
            for (c in group) drawer.setLine(c.text, l, Vector2(COL_LEFT, c.top + off + CUE_LEAD * 0.7), LARGE, LARGE)
        }
    }

    /** The first block whose foot is below [y], by halving. */
    private fun firstBelow(y: Double): Int {
        var lo = 0
        var hi = blocks.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (blocks[mid].bottom < y) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** A state's divider: a hairline with its name over it, and on the next click [word] — KLIK, or AUTO when the show makes it. */
    private fun divider(drawer: Drawer, b: Block, off: Double, colour: ColorRGBa, next: Boolean, offered: Boolean, word: String) {
        val s = small ?: return
        val y = b.divider + off
        val e = b.entry
        val name = if (e.step == 0) "${e.number}  ${e.title}   ${letter(e.step)}" else letter(e.step)
        val who = marker(e.speaker, e.guest, b.lines.isEmpty() && b.cues.isNotEmpty())
        var x = COL_LEFT
        drawer.fill = colour
        drawer.rectangle(COL_LEFT, y - 0.75, COLUMN, 1.5)
        // the label stands in a gap cut out of the hairline
        val klik = if (next) "$word  " else ""
        val width = s.advanceWithSubscripts(klik + name) + (if (next) SMALL * 0.9 else 0.0) +
                (who?.let { pillWidth(s, it.first) + PILL_GAP } ?: 0.0)
        drawer.fill = BLACK
        drawer.rectangle(x - 12.0, y - SMALL * 0.7, width + 24.0, SMALL * 1.4)
        drawer.fill = colour
        if (next) {
            drawer.setLine(word, s, Vector2(x, y + SMALL * 0.36), SMALL, SMALL)
            x += s.advanceWithSubscripts("$word ")
            val t = SMALL * 0.32
            drawer.contour(ShapeContour.fromPoints(listOf(
                Vector2(x, y - t), Vector2(x + t * 1.5, y), Vector2(x, y + t)), true))
            x += SMALL * 0.9
            if (!offered) drawer.fill = DARK.mix(colour, 0.5)
        }
        drawer.setLine(name, s, Vector2(x, y + SMALL * 0.36), SMALL, SMALL)
        // who reads it, a pill after the name: dimmed on the states gone by and the ones far ahead
        who?.let { (text, kind) -> pill(drawer, s, text, kind, x + s.advanceWithSubscripts(name) + PILL_GAP, y, dim = colour == DARK) }
    }

    /** What a state's pill says and how it is drawn: the speaker, or REGIE where only a direction stands; null for neither. */
    private fun marker(speaker: String?, guest: Boolean, regie: Boolean): Pair<String, Int>? = when {
        speaker != null -> speaker.uppercase() to (if (guest) PILL_GUEST else PILL_OWN)
        regie -> "REGIE" to PILL_REGIE
        else -> null
    }

    private fun pillWidth(s: FontImageMap, text: String) = s.advanceWithSubscripts(text) * PILL / SMALL + PILL_PAD * 2.0

    /** A small pill naming who speaks, its left edge at [x] and its middle on [y]: filled for a speaker, outlined in amber for REGIE. */
    private fun pill(drawer: Drawer, s: FontImageMap, text: String, kind: Int, x: Double, y: Double, dim: Boolean = false) {
        val w = pillWidth(s, text)
        val h = PILL * 1.45
        val ground = when (kind) { PILL_GUEST -> GUEST; PILL_OWN -> OWN; else -> AMBER }.let { if (dim) it.mix(BLACK, 0.55) else it }
        if (kind == PILL_REGIE) {
            drawer.fill = BLACK
            drawer.stroke = ground
            drawer.strokeWeight = 2.0
            drawer.rectangle(x, y - h / 2.0, w, h)
            drawer.stroke = null
            drawer.fill = ground
        } else {
            drawer.fill = ground
            drawer.rectangle(x, y - h / 2.0, w, h)
            drawer.fill = BLACK
        }
        // the face is loaded at SMALL: set at PILL, scaled from it
        drawer.setLine(text, s, Vector2(x + PILL_PAD, y + PILL * 0.36), PILL, SMALL)
    }

    private fun header(drawer: Drawer, now: Now) {
        val s = small ?: return
        val l = large ?: return
        drawer.fill = BLACK
        drawer.rectangle(0.0, 0.0, WIDTH, AREA_TOP - 8.0)
        drawer.fill = LABEL
        val section = fit(now.section.ifBlank { " " }, s, 1200.0)
        drawer.setLine(section, s, Vector2(MARGIN, 52.0), SMALL, SMALL)
        // who reads this state: the same pill as its divider, larger
        marker(now.speaker, now.guest, now.regie)?.let { (text, kind) ->
            val x = MARGIN + s.advanceWithSubscripts(section) + 24.0
            val y = 43.0
            val w = s.advanceWithSubscripts(text) + PILL_PAD * 2.0
            val h = SMALL * 1.4
            val ground = when (kind) { PILL_GUEST -> GUEST; PILL_OWN -> OWN; else -> AMBER }
            if (kind == PILL_REGIE) {
                drawer.fill = BLACK; drawer.stroke = ground; drawer.strokeWeight = 2.0
                drawer.rectangle(x, y - h / 2.0, w, h)
                drawer.stroke = null; drawer.fill = ground
            } else {
                drawer.fill = ground
                drawer.rectangle(x, y - h / 2.0, w, h)
                drawer.fill = BLACK
            }
            drawer.setLine(text, s, Vector2(x + PILL_PAD, y + SMALL * 0.36), SMALL, SMALL)
        }
        val state = "   ${letter(now.step)}  ${now.step + 1}/${now.steps}"
        val title = fit("${now.number}  ${now.title}", l, 1200.0 - l.advanceWithSubscripts(state))
        drawer.fill = NOW
        drawer.setLine(title, l, Vector2(MARGIN, 104.0), LARGE, LARGE)
        drawer.fill = LABEL
        drawer.setLine(state, l, Vector2(MARGIN + l.advanceWithSubscripts(title), 104.0), LARGE, LARGE)
        drawer.fill = NOW
        drawer.setLine(now.clock, l, Vector2(WIDTH - MARGIN, 68.0), LARGE, LARGE, align = 1.0)
        drawer.fill = LABEL
        drawer.setLine("dia ${now.number} van ${now.count}" + (if (now.track.isNotBlank()) "  ·  ${now.track}" else ""),
            s, Vector2(WIDTH - MARGIN, 106.0), SMALL, SMALL, align = 1.0)
        drawer.fill = DARK
        drawer.rectangle(MARGIN, AREA_TOP - 8.0, WIDTH - 2 * MARGIN, 1.5)
    }

    private fun foot(drawer: Drawer, now: Now, wall: ColorBuffer?) {
        val s = small ?: return
        val l = large ?: return
        drawer.fill = BLACK
        drawer.rectangle(0.0, AREA_BOTTOM, WIDTH, HEIGHT - AREA_BOTTOM)
        drawer.fill = DARK
        drawer.rectangle(MARGIN, AREA_BOTTOM + 8.0, WIDTH - 2 * MARGIN, 1.5)

        // The status: what the speaker is to do now.
        val pill = Rectangle(MARGIN, AREA_BOTTOM + 34.0, 440.0, 120.0)
        val pulse = 0.82 + 0.18 * sin(now.frame / FPS.toDouble() * PI * 1.4)
        val sign = if (now.paused) Sign(WAIT_GROUND, AMBER, "PAUZE", "spatie: verder") else when (now.status) {
            Status.READY -> Sign(GREEN.opacify(pulse), BLACK, "KLIK", "")
            Status.AUTO -> Sign(AUTO_GROUND, AUTO, "AUTO", "klikt over ${secondsText(now.left)}")
            Status.WAITING -> Sign(WAIT_GROUND, AMBER, "WACHT", if (now.left > 0.05) secondsText(now.left) else "")
            Status.SPEAKING -> Sign(SPEAK_GROUND, NOW, "SPREKEN", "nog ${secondsText(now.left)}")
            Status.END -> Sign(SPEAK_GROUND, LABEL, "EINDE", "")
        }
        // Lit by a click, taken or refused: toward white and back.
        drawer.fill = sign.ground.mix(ColorRGBa.WHITE, CLICK_LIGHT * now.felt.coerceIn(0.0, 1.0))
        drawer.rectangle(pill)
        drawer.fill = sign.ink
        if (now.status == Status.READY) {
            drawer.setLine(sign.word, l, Vector2(pill.x + 36.0, pill.center.y + LARGE * 0.36), LARGE * 1.25, LARGE)
            val x = pill.x + 36.0 + l.advanceWithSubscripts("KLIK ") * 1.25
            val t = LARGE * 0.42
            drawer.contour(ShapeContour.fromPoints(listOf(
                Vector2(x, pill.center.y - t), Vector2(x + t * 1.5, pill.center.y), Vector2(x, pill.center.y + t)), true))
        } else {
            drawer.setLine(sign.word, s, Vector2(pill.x + 28.0, pill.y + 40.0), SMALL, SMALL)
            drawer.setLine(sign.figure, l, Vector2(pill.x + 28.0, pill.y + 92.0), LARGE, LARGE)
            if (now.status == Status.SPEAKING) {
                drawer.fill = DARK
                drawer.rectangle(pill.x, pill.y + pill.height - 6.0, pill.width, 6.0)
                drawer.fill = NOW
                drawer.rectangle(pill.x, pill.y + pill.height - 6.0, pill.width * now.said.coerceIn(0.0, 1.0), 6.0)
            }
            // The wait to the show's own click, as a line coming down — the show's countdown, in the autocue's blue.
            if (now.status == Status.AUTO) {
                drawer.fill = DARK
                drawer.rectangle(pill.x, pill.y + pill.height - 8.0, pill.width, 8.0)
                drawer.fill = AUTO
                drawer.rectangle(pill.x, pill.y + pill.height - 8.0, pill.width * now.countdown.coerceIn(0.0, 1.0), 8.0)
            }
        }

        // What the next click brings.
        val wallWidth = if (wall != null) WALL_WIDTH else 0.0
        val room = WIDTH - MARGIN - wallWidth - 40.0 - (pill.x + pill.width + 48.0)
        val x = pill.x + pill.width + 48.0
        drawer.fill = LABEL
        drawer.setLine(now.nextLabel, s, Vector2(x, pill.y + 40.0), SMALL, SMALL)
        drawer.fill = AHEAD
        drawer.setLine(fit(now.next, l, room), l, Vector2(x, pill.y + 92.0), LARGE, LARGE)

        // The wall as the room sees it, small, where the autocue has a screen of its own.
        if (wall != null) {
            val h = WALL_WIDTH * wall.height / wall.width.toDouble()
            val r = Rectangle(WIDTH - MARGIN - WALL_WIDTH, pill.center.y - h / 2.0, WALL_WIDTH, h)
            drawer.image(wall, r.x, r.y, r.width, r.height)
            drawer.fill = null
            drawer.stroke = DARK
            drawer.strokeWeight = 1.5
            drawer.rectangle(r)
            drawer.stroke = null
        }
    }

    /** How the foot's status is drawn: its ground, its ink, the word and the figure under it. */
    private class Sign(val ground: ColorRGBa, val ink: ColorRGBa, val word: String, val figure: String)

    /** [text] cut back with an ellipsis until it sets within [width] in [font]. */
    private fun fit(text: String, font: FontImageMap, width: Double): String {
        if (font.advanceWithSubscripts(text) <= width) return text
        var t = text
        while (t.length > 1 && font.advanceWithSubscripts("$t…") > width) t = t.dropLast(1)
        return t.trimEnd() + "…"
    }

    companion object {
        const val WIDTH = 1920.0
        const val HEIGHT = 1080.0
        /** The type size of the script by default, in pixels of the 1920 by 1080: about 44 characters to a line. */
        const val TEXT = 64.0
        const val SMALL = 26.0
        const val LARGE = 40.0

        private const val MARGIN = 60.0
        private const val COL_LEFT = 220.0
        private const val COL_RIGHT = 1700.0
        private const val COLUMN = COL_RIGHT - COL_LEFT
        private const val LEADING = 1.34
        private const val BASELINE = 0.74
        /** The silent state's height, in lines. */
        private const val SILENT = 0.8
        /** The room a blank line in the source leaves between two paragraphs, in lines. */
        private const val PARAGRAPH = 0.5
        /** The speaker's pill on a divider: its type size, the room inside it either side, and before it. */
        private const val PILL = 21.0
        private const val PILL_PAD = 9.0
        private const val PILL_GAP = 18.0
        private const val PILL_OWN = 0
        private const val PILL_GUEST = 1
        private const val PILL_REGIE = 2
        private const val DIVIDER = 64.0
        /** A direction's line height, the room above a state's first direction, and between two of them. */
        private const val CUE_LEAD = LARGE * 1.32
        private const val CUE_GAP = 22.0
        private const val CUE_SPACE = 12.0
        private const val CUE_COLUMN = COLUMN
        private const val GAP = 26.0
        private const val AREA_TOP = 132.0
        private const val AREA_BOTTOM = 900.0
        /** The reading line: a little above the middle, so more of what is coming shows than of what has gone. */
        const val READ_Y = AREA_TOP + (AREA_BOTTOM - AREA_TOP) * 0.38
        private const val FADE_TOP = 90.0
        private const val FADE_FOOT = 120.0
        /** Black over the area's top edge going to nothing, and from nothing to black over its foot, down the rectangle. */
        private val FADE_IN = shadeStyle { fragmentTransform = "x_fill.a *= 1.0 - smoothstep(0.0, 1.0, c_boundsPosition.y);" }
        private val FADE_OUT = shadeStyle { fragmentTransform = "x_fill.a *= smoothstep(0.0, 1.0, c_boundsPosition.y);" }
        private const val MARK_SIZE = 20.0
        private const val WALL_WIDTH = 480.0

        /** Seconds either side the reading path is averaged over, and in how many samples. */
        const val SMOOTH = 1.4
        private const val SAMPLES = 24
        /** Seconds the next divider takes to glide onto the reading line once the click is offered. */
        const val GLIDE = 0.5
        /** Seconds a click is eased into, from where the scroll stood. */
        const val EASE = 0.6
        /** A jump further than this is cut rather than scrolled — a step back over a chapter, a jump from the organizer. */
        const val FAR = 900.0

        private val BLACK = ColorRGBa.fromHex("000000")
        private val NOW = ColorRGBa.fromHex("FFFFFF")
        private val SAID = ColorRGBa.fromHex("8A8C92")
        private val PAST = ColorRGBa.fromHex("4E5056")
        private val AHEAD = ColorRGBa.fromHex("A4A6AC")
        private val LABEL = ColorRGBa.fromHex("8A8C92")
        private val DARK = ColorRGBa.fromHex("34363C")
        private val MARK = ColorRGBa.fromHex("D8DADF")
        private val BAND = ColorRGBa.fromHex("15171B")
        val GREEN: ColorRGBa = ColorRGBa.fromHex("2BD45A")
        val AMBER: ColorRGBa = ColorRGBa.fromHex("FFB020")
        /** The script's own speaker's pill, and anyone else's. */
        private val OWN = ColorRGBa.fromHex("D8DADF")
        private val GUEST = ColorRGBa.fromHex("4FC3F7")
        /** A direction of a state still to come, and of one gone by. */
        private val CUE_AHEAD = ColorRGBa.fromHex("A8771A")
        private val CUE_PAST = ColorRGBa.fromHex("5A4417")
        private val WAIT_GROUND = ColorRGBa.fromHex("2A2110")
        private val SPEAK_GROUND = ColorRGBa.fromHex("1A1C20")
        private val AUTO = ColorRGBa.fromHex("7FA6FF")
        /** How far toward white a click lights the status box at its height. */
        private const val CLICK_LIGHT = 0.55
        private val AUTO_GROUND = ColorRGBa.fromHex("101A30")

        /** Seconds as the speaker reads them: "4 s", "1,2 s", "1:05". */
        fun secondsText(s: Double): String = when {
            s >= 60.0 -> kotlin.math.ceil(s).toInt().let { "%d:%02d".format(it / 60, it % 60) }
            s >= 10.0 -> "${kotlin.math.ceil(s).toInt()} s"
            else -> "%.1f s".format(s.coerceAtLeast(0.0)).replace('.', ',')
        }
    }
}
