package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.draw.loadImage
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Module
import slideshow.ReferenceFrame
import slideshow.Slide
import slideshow.Stage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A slide that is not built yet, standing in for one that will be.
 *
 * Pink, so it cannot be mistaken for a finished slide from across a room, and carrying the
 * frames of the client's deck it stands for — **a click a frame**, so clicking through the
 * placeholder is clicking through what the client drew, at the place in the talk where it
 * will go. The title along the top is the module's, the number at the right is where in its
 * frames it stands, and the line at the foot is the start of that frame's speaker note.
 *
 * It is a pure function of `stage.position` like every other slide here: frame `floor` under
 * frame `ceil`, the second fading up by the fraction, so the deck's own ease carries the
 * crossfade and clicking back plays it in reverse. Nothing is read from a clock.
 *
 * A frame drawn beside its chapter card is shown as its own pane, cut from the frame at the
 * pane's size; one drawn as a plain 16:9 slide — most of the deck — is that slide, which is
 * the pane's own shape; one that paints across the whole wall is shown whole and fitted, which
 * on a pane slide means small. `Module.wide` makes the placeholder take the wall, for a moment
 * the show means to keep at that width.
 *
 * Built from [Modules][slideshow.Modules], never declared in `Slideshow.kt`: see there for why
 * that is the one exception to the rule that a slide is a constructor.
 */
class PlaceholderSlide(
    module: Module,
    private val refs: List<ReferenceFrame>,
    private val fontPath: String
) : Slide() {

    /** The module this stands for. A `var` so a retitled or re-briefed module updates in place. */
    @Volatile
    var module: Module = module

    override val name: String get() = module.title
    override val kind: String get() = "module"
    override val wide: Boolean get() = module.wide
    override val steps: Int get() = max(1, refs.size)
    override val background: ColorRGBa get() = PINK

    override fun stepName(step: Int): String? = refs.getOrNull(step)?.let { "frame ${it.name}" }

    private var pictures: List<ColorBuffer?> = emptyList()
    private lateinit var title: FontImageMap
    private lateinit var meta: FontImageMap
    private lateinit var note: FontImageMap

    /** Whether [load] has run: a placeholder can be handed to a running show, which loads it once. */
    var loaded: Boolean = false
        private set

    override fun load(program: Program) {
        if (loaded) return
        loaded = true
        title = program.loadFont(fontPath, TITLE, TYPE_CHARACTERS, contentScale = 1.0)
        meta = program.loadFont(fontPath, META, TYPE_CHARACTERS, contentScale = 1.0)
        note = program.loadFont(fontPath, NOTE, TYPE_CHARACTERS, contentScale = 1.0)
        pictures = refs.map { ref ->
            ref.image?.let { file -> runCatching { loadImage(file) }.getOrElse { println("module ${module.id}: could not read ${file.path}"); null } }
        }
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val w = stage.width
        val h = stage.height
        val m = MARGIN

        // The frame this click stands on and the one it is going to, the second fading up.
        val position = stage.position.coerceIn(0.0, (steps - 1).toDouble())
        val from = floor(position).toInt()
        val to = min(ceil(position).toInt(), steps - 1)
        val mix = position - from

        val box = Rectangle(m, HEAD, w - 2 * m, h - HEAD - FOOT)
        drawer.stroke = null

        if (refs.isEmpty()) {
            // Nothing to show yet: the title alone, large, in the middle.
            drawer.fill = INK
            drawer.setLine(module.title, title, Vector2(w / 2, h / 2), TITLE * 1.6, TITLE, align = 0.5)
            drawer.fill = INK.opacify(0.7)
            drawer.setLine("MODULE · NOG TE BOUWEN · GEEN REFERENTIEFRAMES", meta, Vector2(w / 2, h / 2 + 64.0), META, META, align = 0.5)
            return
        }

        // The picture, or the two pictures mid-click: the leaving one whole, the arriving one
        // over it at the fraction of the click that has passed.
        pictures.getOrNull(from)?.let { picture(drawer, it, box, 1.0) }
        if (to != from && mix > 0.0) pictures.getOrNull(to)?.let { picture(drawer, it, box, mix) }
        drawer.fill = null
        drawer.stroke = INK.opacify(0.35)
        drawer.strokeWeight = 1.0
        drawer.rectangle(box)
        drawer.stroke = null

        // The furniture: the module's title, where in its frames it stands, and the note.
        val shown = if (mix < 0.5) from else to
        val ref = refs[shown]
        drawer.fill = INK
        drawer.setLine(module.title, title, Vector2(m, HEAD - 38.0), TITLE, TITLE)
        drawer.fill = INK.opacify(0.75)
        val where = "MODULE · NOG TE BOUWEN · ${shown + 1} / ${refs.size} · FRAME ${ref.name}" +
                (if (ref.wide) " · HELE WAND" else if (ref.paneOnly) " · LOSSE 16:9-SLIDE" else " · NAAST DE KAART")
        drawer.setLine(where, meta, Vector2(w - m, HEAD - 42.0), META, META, align = 1.0)

        val line = firstLine(ref.note, w - 2 * m)
        if (line.isNotEmpty()) {
            drawer.fill = INK.opacify(0.8)
            drawer.setLine(line, note, Vector2(m, h - FOOT + 52.0), NOTE, NOTE)
        }
    }

    /** [pic] fitted inside [box] and centred, at [alpha]. */
    private fun picture(drawer: Drawer, pic: ColorBuffer, box: Rectangle, alpha: Double) {
        val fit = min(box.width / pic.width, box.height / pic.height)
        val pw = pic.width * fit
        val ph = pic.height * fit
        val target = Rectangle(box.x + (box.width - pw) / 2.0, box.y + (box.height - ph) / 2.0, pw, ph)
        drawer.isolated {
            drawer.fill = ColorRGBa.WHITE
            if (alpha < 1.0) {
                // The image shader's texel is x_fill; scaling all four channels keeps the
                // blend premultiplied, which is what the driver composites with.
                drawer.shadeStyle = shadeStyle {
                    fragmentTransform = "x_fill *= p_alpha;"
                    parameter("alpha", alpha)
                }
            }
            drawer.image(pic, Rectangle(0.0, 0.0, pic.width.toDouble(), pic.height.toDouble()), target)
        }
    }

    /** The first line of [text] that fits [measure], with an ellipsis where there is more. */
    private fun firstLine(text: String, measure: Double): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return ""
        val lines = note.wrapped(flat, measure - note.advanceOf(" …"))
        return if (lines.size > 1) lines[0] + " …" else lines[0]
    }

    companion object {
        /** The one colour a placeholder is: nothing in the house palette, nothing a finished slide would be. */
        val PINK: ColorRGBa = ColorRGBa.fromHex("FF3F9E")
        private val INK = ColorRGBa.BLACK

        private const val MARGIN = 96.0
        private const val HEAD = 150.0
        private const val FOOT = 110.0
        private const val TITLE = 44.0
        private const val META = 22.0
        private const val NOTE = 24.0
    }
}
