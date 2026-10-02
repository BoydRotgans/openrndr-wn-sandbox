// ============================================================================ //
//  No `package` declaration: it reads the life cycle's own phases (LifePhase,
//  LifeStep), which live beside LifeCycle in the default package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.loadFont
import org.openrndr.draw.loadImage
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.advanceOf
import slideshow.easeInOutCubic
import slideshow.frames
import slideshow.smoothstep
import java.io.File

/**
 * The letters of the life cycle, explained before it is shown: A, B and C as one road, a stretch a
 * click.
 *
 * Asked for at the meeting of 30 September — "say briefly what A, B and C stand for". The life cycle
 * sets every box with its code (A1, B3, C4), the language of a life cycle analysis, which reads as
 * jargon to a room that has never seen one. On 2 October it became **the road** off the LCA picture the
 * client knows (quarry, factory, crane, city, demolition, dump, all on one road): a single line across
 * the pane, drawn a stretch at a time. A draws with the slide, B and C each on their click.
 *
 * - **Each stop is a step of its letter**: a dot on the road with its code over it, and the step
 *   listed under the stretch. Dot and line arrive as the road reaches them.
 * - **The letter and its meaning stand over the stretch** as the road starts on it.
 * - **B is the longest stretch**, because the use of a building is decades where making and
 *   demolishing it are months.
 * - **C runs in red into a dead end**: a bar across the road where it stops. That is the linear life
 *   the next slide breaks, when The Circle takes C's place.
 * - **A subtle illustration stands on the road over each stop**, as in the client's picture: grey line
 *   drawings off [illustrations], eight drawn for this slide in one plain outline after the reduction
 *   slide's battery and solar panel (excavator, lorry, factory, tower crane, building, demolition,
 *   recycling, heap), close to square so each can stand tall in its stop's share of the road and
 *   none touches the next. The reduction slide's own trailer truck is four times as wide as it is
 *   high and came out a smudge at a stop; its crane, a hairline photo-trace, read fainter than the
 *   outlines beside it. Either is one word in [icons] to bring back. [icons] says which stands at which code; replacing an svg of the same name
 *   replaces the drawing. B's buildings stand at different heights, a street rather than a row.
 *
 * **It is read off the life cycle's own phases**, grouped by the letter their code starts with, so the
 * two slides cannot disagree: rename a step in the show and both follow. A phase with no code (The
 * Circle's column) belongs to no letter and is left out. [meaning] names each letter in the words the
 * room should keep, and falls back to the phases' own names.
 */
class LifeCycleKey(
    private val title: String = "A, B en C: de fasen van een levenscyclus",
    phases: List<LifePhase>,
    /** What each letter stands for, set under it; a letter not named takes its phases' names. */
    private val meaning: Map<String, String> = emptyMap(),
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    /** The road and everything on it before the end of life. */
    private val road: ColorRGBa = ColorRGBa.WHITE,
    /** The end of life: its stretch, its dots, its letter and the dead end. */
    private val ink: ColorRGBa = slideshow.Palette.RED,
    /** Where the illustrations are, one svg a drawing, white on transparent; null draws none. */
    private val illustrations: File? = File("data/illustrations"),
    /** Which drawing stands at which code, by its svg's name. */
    private val icons: Map<String, String> = ICONS,
    override val background: ColorRGBa = ColorRGBa.BLACK,
    override val stepFrames: Int = frames(1.6),
    override val sound: Sound? = null,
    override val stepCues: List<Sound> = emptyList()
) : Slide() {
    /** One letter: its codes and step names, in the order they come. */
    private class Letter(val letter: String, val heading: String, val codes: List<String>, val steps: List<String>)
    private val letters: List<Letter> = phases
        .filter { it.code.isNotEmpty() }
        .groupBy { it.code.take(1) }
        .map { (letter, group) ->
            Letter(
                letter,
                meaning[letter] ?: group.joinToString(" en ") { it.name.lowercase() }.replaceFirstChar { it.uppercase() },
                group.flatMap { phase -> phase.steps.map { it.code } },
                group.flatMap { phase -> phase.steps.map { it.name } }
            )
        }

    override val name = "Life cycle key"
    override val steps get() = letters.size.coerceAtLeast(1)
    override fun stepName(step: Int) = letters.getOrNull(step)?.letter
    /** The use stretch is the longest, so its click is too: the road travels at one pace. */
    override fun stepLength(step: Int): Int = frames(OPEN * share(step) / share(0))
    override val settle: Int get() = frames(OPEN)

    private lateinit var bold: FontImageMap
    private lateinit var text: FontImageMap

    override fun load(program: Program) {
        bold = program.loadFont(boldPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        text = program.loadFont(textPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        pictures = icons.values.distinct().mapNotNull { name -> raster(name)?.let { name to it } }.toMap()
    }

    private var pictures: Map<String, ColorBuffer> = emptyMap()

    /** A white drawing taken to the tint: grey, and faded as it arrives. */
    private val tintStyle = shadeStyle { fragmentTransform = "x_fill *= p_tint;" }

    /**
     * The drawing's svg as a png, converted once by `rsvg-convert` and again only when the svg is
     * newer — two of them are pictures wrapped in an svg, which OPENRNDR's own loader cannot draw.
     * Into a folder of its own, since the reduction slide rasterises the crane and the truck at
     * another size under the same names.
     */
    private fun raster(name: String): ColorBuffer? {
        val svg = illustrations?.let { File(it, "$name.svg") }?.takeIf { it.isFile } ?: run {
            println("LifeCycleKey: no $name.svg in ${illustrations?.path} — that stop stands bare")
            return null
        }
        val png = File("build/illustrations/road/$name.png")
        if (!png.isFile || svg.lastModified() > png.lastModified()) {
            png.parentFile.mkdirs()
            val code = listOf("rsvg-convert", "/opt/homebrew/bin/rsvg-convert", "/usr/local/bin/rsvg-convert").firstNotNullOfOrNull { tool ->
                runCatching { ProcessBuilder(tool, "-h", RASTER.toString(), "-a", svg.path, "-o", png.path).inheritIO().start().waitFor() }.getOrNull()
            }
            if (code != 0) {
                println("LifeCycleKey: could not convert ${svg.path} (is rsvg-convert installed?) — that stop stands bare")
                return null
            }
        }
        return runCatching { loadImage(png) }.getOrNull()?.apply {
            generateMipmaps()
            filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        }
    }

    /** How much of the road letter [i]'s stretch takes. */
    private fun share(i: Int): Double = SHARES.getOrElse(i) { 1.0 / letters.size.coerceAtLeast(1) } / SHARES.take(letters.size).sum().coerceAtLeast(1e-6)

    override fun draw(drawer: Drawer, stage: Stage) {
        // How far each stretch has been drawn: A on the slide's own clock, B and C on their clicks,
        // each on the deck's own ease.
        val drawn = letters.indices.map { i ->
            if (i == 0) easeInOutCubic(stage.since(0, frames(OPEN))) else stage.on(i)
        }
        paint(drawer, stage.width, stage.height, drawn)
    }

    /** Where a step goes when the road hands over: a box on the slide after, and its type size there in pane pixels. */
    class Target(val box: Rectangle, val size: Double)

    /**
     * The road handing over to the life cycle after it, drawn by that slide on its own clock. At [t] 0
     * it is this slide's last state to the pixel, so the cut between the two cannot be seen; by 1 the
     * title, the letters, the road, its drawings and every step have gone but those named in [into],
     * which have travelled from the list into their box on the next slide and faded as its own
     * labels take over there.
     */
    fun leave(drawer: Drawer, w: Double, h: Double, t: Double, into: Map<String, Target>) =
        paint(drawer, w, h, letters.map { 1.0 }, t.coerceIn(0.0, 1.0), into)

    private fun paint(drawer: Drawer, w: Double, h: Double, drawn: List<Double>, leaving: Double = 0.0, into: Map<String, Target> = emptyMap()) {
        drawer.stroke = null
        // Everything that does not travel goes over the first part of the hand-over.
        val rest = 1.0 - smoothstep((leaving / LEAVE_REST).coerceIn(0.0, 1.0))

        if (rest > 0.0) {
            drawer.fill = ColorRGBa.WHITE.opacify(rest)
            line(drawer, title, bold, Vector2(w / 2.0, h * TITLE_Y), h * TITLE / SIZE)
        }
        if (letters.isEmpty()) return

        val left = w * ROAD_LEFT
        val length = w * (ROAD_RIGHT - ROAD_LEFT)
        val y = h * ROAD_Y
        var start = left
        letters.forEachIndexed { i, l ->
            val span = length * share(i)
            val end = start + span
            val colour = if (i == letters.lastIndex) ink else road
            val t = drawn[i]
            val head = start + span * t

            // The letter and what it stands for, over its stretch, as the road starts on it.
            val seen = (t / LABEL_IN).coerceIn(0.0, 1.0) * rest
            if (seen > 0.0) {
                val rise = (1.0 - (t / LABEL_IN).coerceIn(0.0, 1.0)) * h * 0.02
                drawer.fill = (if (i == letters.lastIndex) ink else ColorRGBa.WHITE).opacify(seen)
                leftLine(drawer, l.letter, bold, Vector2(start, h * LETTER_Y + rise), h * LETTER / SIZE)
                drawer.fill = ColorRGBa.WHITE.opacify(seen)
                // Broken to its own stretch, so a long meaning never runs into the next letter's.
                val scale = h * HEADING / SIZE
                broken(l.heading, bold, (span - w * HEADING_GAP) / scale).forEachIndexed { k, part ->
                    leftLine(drawer, part, bold, Vector2(start, h * (HEADING_Y + k * HEADING_LEAD) + rise), scale)
                }
            }

            // The road itself, up to its head.
            if (t > 0.0 && rest > 0.0) {
                drawer.fill = colour.opacify(rest)
                drawer.rectangle(Rectangle(start, y - h * ROAD / 2.0, head - start, h * ROAD))
            }

            // The stops: a dot and its code as the head passes, and the step listed under the stretch.
            val n = l.steps.size
            l.steps.forEachIndexed { k, step ->
                val x = start + span * (k + 0.5) / n
                val passed = ((head - x) / (h * POP)).coerceIn(0.0, 1.0)
                if (passed <= 0.0) return@forEachIndexed
                val grow = 1.0 - (1.0 - passed) * (1.0 - passed)
                val code = l.codes[k]

                if (rest > 0.0) {
                    // The drawing standing on the road over its stop, fitted to its share of the road.
                    pictures[icons[code]]?.let { picture ->
                        val tall = h * ICON_H * (ICON_HEIGHTS[code] ?: 1.0)
                        val scale = minOf(tall / picture.height, span / n * ICON_W / picture.width)
                        val pw = picture.width * scale
                        val ph = picture.height * scale
                        drawer.isolated {
                            drawer.shadeStyle = tintStyle
                            tintStyle.parameter("tint", ColorRGBa(ICON_TONE, ICON_TONE, ICON_TONE, passed * rest))
                            drawer.image(picture, Rectangle(x - pw / 2.0, y - h * (ROAD / 2.0 + ICON_GAP) - ph + (1.0 - grow) * h * 0.01, pw, ph))
                        }
                    }
                    drawer.fill = colour.opacify(rest)
                    drawer.circle(Vector2(x, y), h * DOT * grow)
                    drawer.fill = colour.opacify(passed * rest)
                    line(drawer, code, bold, Vector2(x, y + h * CODE_BELOW), h * CODE / SIZE)
                }

                // The step in the list under the stretch — or, handing over, travelling to its box.
                val at = Vector2(start, h * (LIST_Y + k * LIST_LEAD) + (1.0 - passed) * h * 0.01)
                val target = into[code]
                if (target == null) {
                    if (rest <= 0.0) return@forEachIndexed
                    drawer.fill = colour.opacify(passed * rest)
                    leftLine(drawer, code, bold, at, h * ITEM / SIZE)
                    drawer.fill = ColorRGBa.WHITE.opacify(passed * rest)
                    leftLine(drawer, step, text, at + Vector2(h * ITEM_INDENT, 0.0), h * ITEM / SIZE)
                } else travel(drawer, code, step, at, h * ITEM, h * ITEM_INDENT, target, leaving)
            }

            // The dead end: a bar across the road where it stops.
            if (i == letters.lastIndex && rest > 0.0) {
                val shut = ((t - 0.97) / 0.03).coerceIn(0.0, 1.0)
                if (shut > 0.0) {
                    drawer.fill = ink.opacify(rest)
                    val tall = h * END * (1.0 - (1.0 - shut) * (1.0 - shut))
                    drawer.rectangle(Rectangle(end - h * ROAD, y - tall / 2.0, h * ROAD * 1.6, tall))
                }
            }
            start = end + w * BREAK
        }
    }

    /**
     * One step on its way from the list to its box on the next slide: the code bold and the name
     * regular as in the list, the gap between them closing to a space, the pair centred in the box at
     * the next slide's size by the end, and gone over the last stretch while that slide's own label
     * comes up in its place.
     */
    private fun travel(drawer: Drawer, code: String, step: String, from: Vector2, fromSize: Double, fromIndent: Double, target: Target, t: Double) {
        val m = smoothstep(((t - TRAVEL_FROM) / (TRAVEL_UNTIL - TRAVEL_FROM)).coerceIn(0.0, 1.0))
        val alpha = 1.0 - smoothstep(((t - TRAVEL_UNTIL) / (1.0 - TRAVEL_UNTIL)).coerceIn(0.0, 1.0))
        if (alpha <= 0.0) return
        val size = fromSize + (target.size - fromSize) * m
        val scale = size / SIZE
        val codeW = bold.advanceOf(code)
        val nameW = text.advanceOf(step)
        val gapFrom = fromIndent - codeW * fromSize / SIZE
        val gapTo = text.advanceOf(" ") * target.size / SIZE
        val gap = gapFrom + (gapTo - gapFrom) * m
        val widthTo = (codeW + nameW) * target.size / SIZE + gapTo
        val toLeft = target.box.center.x - widthTo / 2.0
        val toY = target.box.center.y + target.size * 0.34
        val x = from.x + (toLeft - from.x) * m
        val y = from.y + (toY - from.y) * m
        drawer.fill = road.opacify(alpha)
        leftLine(drawer, code, bold, Vector2(x, y), scale)
        drawer.fill = ColorRGBa.WHITE.opacify(alpha)
        leftLine(drawer, step, text, Vector2(x + codeW * scale + gap, y), scale)
    }

    /** [content] broken between words to lines no wider than [measure], in the face's own units. */
    private fun broken(content: String, face: FontImageMap, measure: Double): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        for (word in content.split(" ").filter { it.isNotEmpty() }) {
            val next = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && face.advanceOf(next) > measure) { lines += current; current = word } else current = next
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    /** One line, centred on [at]. */
    private fun line(drawer: Drawer, content: String, face: FontImageMap, at: Vector2, scale: Double) {
        drawer.isolated {
            drawer.fontMap = face
            drawer.translate(at)
            drawer.scale(scale)
            drawer.text(content, -face.advanceOf(content) / 2.0, 0.0)
        }
    }

    /** One line, ranged left from [at]. */
    private fun leftLine(drawer: Drawer, content: String, face: FontImageMap, at: Vector2, scale: Double) {
        drawer.isolated {
            drawer.fontMap = face
            drawer.translate(at)
            drawer.scale(scale)
            drawer.text(content, 0.0, 0.0)
        }
    }

    private companion object {
        const val SIZE = 150.0
        /** Seconds the first stretch takes to draw; the others take their share of the road at its pace. */
        const val OPEN = 1.6
        /** A, B and C's shares of the road: the use the longest. */
        val SHARES = listOf(0.33, 0.40, 0.27)

        // Everything as shares of the pane: the title band, then letters, road and lists down the pane.
        const val TITLE = 0.038
        const val TITLE_Y = 0.060
        const val ROAD_LEFT = 0.05
        const val ROAD_RIGHT = 0.95
        /** The small step left between two stretches, so each reads as its own. */
        const val BREAK = 0.0
        const val ROAD_Y = 0.60
        const val ROAD = 0.008
        const val DOT = 0.014
        const val POP = 0.03
        const val END = 0.08
        const val CODE = 0.026
        const val CODE_BELOW = 0.052
        const val LETTER = 0.16
        const val LETTER_Y = 0.295
        const val HEADING = 0.034
        const val HEADING_Y = 0.36
        const val HEADING_LEAD = 0.045
        /** Room kept between a meaning and the next letter. */
        const val HEADING_GAP = 0.015
        /** How much of its stretch the road draws before its letter is fully up. */
        const val LABEL_IN = 0.25
        const val LIST_Y = 0.72
        const val LIST_LEAD = 0.045
        const val ITEM = 0.028
        const val ITEM_INDENT = 0.055

        /** The hand-over to the life cycle, as shares of its length: what stays goes by [LEAVE_REST], the steps travel between the other two. */
        const val LEAVE_REST = 0.45
        const val TRAVEL_FROM = 0.15
        const val TRAVEL_UNTIL = 0.82

        /** The tallest a drawing stands, its widest against its stop's share of the road, its gap over the road, its grey. */
        const val ICON_H = 0.14
        const val ICON_W = 0.9
        const val ICON_GAP = 0.008
        const val ICON_TONE = 0.28
        /** Pixels high a drawing is rasterised at: twice the most it is drawn at. */
        const val RASTER = 300

        /** The client's LCA picture in the show's drawings: what stands at each step. */
        val ICONS = mapOf(
            "A1" to "excavator", "A2" to "lorry", "A3" to "factory", "A4" to "lorry", "A5" to "tower-crane",
            "B1" to "building", "B2" to "building", "B3" to "building", "B4" to "building", "B5" to "building",
            "C1" to "demolition", "C2" to "lorry", "C3" to "recycling", "C4" to "heap"
        )
        /** B's buildings at different heights, so the use stretch reads as a street. */
        val ICON_HEIGHTS = mapOf("B1" to 0.74, "B2" to 0.58, "B3" to 0.68, "B4" to 0.54, "B5" to 0.64)
    }
}
