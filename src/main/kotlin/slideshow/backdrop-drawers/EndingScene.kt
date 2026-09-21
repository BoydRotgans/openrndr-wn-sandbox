package slideshow.backdrops

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Backdrop
import slideshow.Palette
import slideshow.Sound
import slideshow.Stage
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.setLine
import slideshow.drawers.setToFit
import slideshow.drawers.wrapped
import slideshow.easeInOutCubic
import slideshow.frames

/**
 * The ending: one sentence, one action, and the thing that triggers it — a QR code the room can
 * point a phone at. The whole wall, before the closing scene.
 *
 * The code is generated at load from [url] and drawn as squares, white on black, with its own
 * quiet zone; it grows from its middle once the words are up. Everything builds on the wall's
 * own clock and then holds. Sentence, action and address are the show's.
 */
class EndingScene(
    private val sentence: String,
    private val action: String,
    private val url: String,
    private val boldPath: String = "data/fonts/default.otf",
    private val textPath: String = boldPath,
    private val ink: ColorRGBa = Palette.onBlack.ink,
    private val accent: ColorRGBa = Palette.onBlack.accent,
    override val background: ColorRGBa = Palette.onBlack.paper,
    override val sound: Sound? = null
) : Backdrop() {

    override val name = "Ending"
    override val settle get() = frames(1.5)

    private lateinit var bold: FontImageMap
    private lateinit var text: FontImageMap
    private var modules: Array<BooleanArray> = emptyArray()

    override fun load(program: Program) {
        bold = program.loadFont(boldPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        text = program.loadFont(textPath, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        modules = runCatching {
            val m = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0, mapOf(
                EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
            ))
            Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
        }.getOrElse { println("ending: could not encode \"$url\" (${it.message})"); emptyArray() }
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val w = stage.width
        val h = stage.height
        drawer.stroke = null

        val said = easeInOutCubic(stage.since(0, frames(0.6)))
        val asked = easeInOutCubic(stage.since(frames(0.4), frames(0.5)))
        val shown = easeInOutCubic(stage.since(frames(0.7), frames(0.7)))

        // The sentence in the left projector, the code in the right: the wall is two panes meeting
        // at the middle, and a line of type across the seam breaks there.
        val pane = w / 2.0
        val box = Rectangle(pane * MARGIN, h * SENTENCE_TOP, pane * (1.0 - 2.0 * MARGIN), h * SENTENCE_H)
        drawer.fill = ink.opacify(said)
        bold.setToFit(sentence, box, SIZE, 1.15).draw(drawer, box.center)

        // The right pane is one stack — the action, the code, the address — laid out at full size
        // and centred on the pane's middle, level with the sentence, so the three read as one group
        // with even gaps rather than a headline adrift at the top and a caption jammed under the
        // code. A line is placed by its baseline; its ink starts a cap height above it.
        val cx = pane * 1.5
        val actionLines = text.wrapped(action, (pane * ACTION_W) * SIZE / (h * ACTION))
        val outer = h * CODE * (1.0 + 2.0 * QUIET)
        val stack = CAP * h * ACTION + (actionLines.size - 1) * h * ACTION_LEAD +
            h * GAP_ABOVE + outer + h * GAP_BELOW + CAP * h * URL
        val top = (h - stack) / 2.0
        val firstBaseline = top + CAP * h * ACTION
        val codeTop = firstBaseline + (actionLines.size - 1) * h * ACTION_LEAD + h * GAP_ABOVE
        val cy = codeTop + outer / 2.0
        val urlBaseline = codeTop + outer + h * GAP_BELOW + CAP * h * URL

        // The code, growing from its middle.
        val side = h * CODE * shown
        if (modules.isNotEmpty() && shown > 0.0) {
            val n = modules.size
            val quiet = side * QUIET
            drawer.fill = ink
            drawer.rectangle(Rectangle(cx - side / 2.0 - quiet, cy - side / 2.0 - quiet, side + 2 * quiet, side + 2 * quiet))
            drawer.fill = background
            val cell = side / n
            for (y in 0 until n) for (x in 0 until n) if (modules[y][x]) {
                drawer.rectangle(Rectangle(cx - side / 2.0 + x * cell, cy - side / 2.0 + y * cell, cell + 0.5, cell + 0.5))
            }
        }
        drawer.fill = accent.opacify(asked)
        actionLines.forEachIndexed { i, line ->
            drawer.setLine(line, bold, Vector2(cx, firstBaseline + i * h * ACTION_LEAD), h * ACTION, SIZE, align = 0.5)
        }
        drawer.fill = ink.opacify(shown)
        drawer.setLine(url, text, Vector2(cx, urlBaseline), h * URL, SIZE, align = 0.5)
    }

    private companion object {
        const val SIZE = 200.0
        const val MARGIN = 0.06
        const val SENTENCE_TOP = 0.22
        const val SENTENCE_H = 0.56
        /** The code's side as a share of the height — half of it, so it scans from the seats — and its white border as a share of the side. */
        const val CODE = 0.5
        const val QUIET = 0.06
        /** The action is the headline of the wall: title size on the pane, over the code. */
        const val ACTION = 0.06
        const val ACTION_LEAD = 0.07
        const val ACTION_W = 0.86
        /** The address, large enough to be read off the wall and typed, not just a caption. */
        const val URL = 0.036
        /** From the action's baseline to the code, and from the code to the address's cap height. */
        const val GAP_ABOVE = 0.05
        const val GAP_BELOW = 0.04
        /** A capital's height as a share of the type size, to place a line by its ink rather than its baseline. */
        const val CAP = 0.72
    }
}
