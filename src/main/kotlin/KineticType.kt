import org.openrndr.KEY_ESCAPE
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.depthBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadFont
import org.openrndr.draw.renderTarget
import org.openrndr.ffmpeg.ScreenRecorder
import org.openrndr.shape.Rectangle
import slideshow.FPS
import slideshow.drawers.TYPE_CHARACTERS
import slideshow.drawers.advanceOf
import slideshow.drawers.setToFit
import slideshow.easeInOutCubic
import slideshow.frames
import java.io.File
import kotlin.math.min
import kotlin.random.Random

/**
 * A sentence set as separate words, dealt across a frame so that none of them touch, and read
 * out a word at a time.
 *
 * This is the type on its own. `ObjectChapterPanel` stands the same thing up in Willy Naessens
 * elements — it paints this into a plate and a field of components reads it — and the two share
 * this file rather than each having their own arrangement, so a change to how the words fall is
 * a change to both. Run it alone with
 *
 *     ./gradlew run -Popenrndr.application=KineticTypeKt
 *
 * **The layout is a pure function of one number.** [at] maps a frame count to every word's box
 * and how far it has arrived; nothing is integrated and nothing is kept, so the piece can be
 * scrubbed, recorded, jumped into or stepped and shows the same picture at the same frame. It
 * is `packBoxes` and `stateAt` again, applied to a sentence.
 *
 * Four decisions carry it:
 *
 * - **The size is whatever lets every word stand apart.** The search opens *above* the size the
 *   sentence would be set at as a block and steps down until the whole of it is down — so a
 *   deal is as large as its own arrangement allows rather than as large as a block would be,
 *   and a word that cannot be placed never simply goes missing, which is the one thing a title
 *   may not do.
 * - **No two words touch, and that is a property of the deal** rather than something checked
 *   after the fact: a word takes the first place offered that clears every word already down by
 *   [gap] of a line, and if a hundred places are refused it walks a lattice, which always finds
 *   one.
 * - **A word may hang off the frame but never by half.** [bleed] is what lets the type be
 *   bigger than the frame and be *cropped* — held inside, a word can only be as large as the
 *   narrowest way of seating them all — and it is capped, because past halfway a word stops
 *   being a cropped word and becomes a mark at the edge that happens to be made of letters.
 * - **The words arrive and leave; they do not travel.** Each comes in on its own beat, in the
 *   order the sentence is read, and later goes the same way. What eases is how far it has
 *   arrived, which the card turns into elements growing into the letters.
 */
class KineticType(
    val text: String,
    private val face: FontImageMap,
    /** The size the atlas was baked at — everything is scaled off it. */
    private val size: Double,
    private val leading: Double,
    /** The measure the words are dealt into, before any [bleed]. */
    private val box: Rectangle,
    /** Frames a word waits before the next one joins it. */
    private val beat: Int,
    /** Frames one word takes to arrive or to go. */
    private val move: Int,
    /** Beats the whole sentence stands before it starts going away again. */
    private val hold: Int = 2,
    /** How far a word may hang off [box], as a fraction of its own size. Never past [HALF]. */
    bleed: Double = 0.0,
    /** Clearance between two words, as a fraction of a line's height. */
    private val gap: Double = 0.35,
    /** Break the sentence over exactly this many lines when sizing it as a block. */
    private val lines: Int? = null
) {
    private val bleed = bleed.coerceIn(0.0, HALF)

    /** The sentence, in the order it is read. */
    val words: List<String> = text.split(" ").filter { it.isNotBlank() }

    /** Where each word stands, and the size they are all set at. Dealt once. */
    val home: List<Pair<String, Rectangle>> = dealt()

    /** The size the deal came out at, in [face]'s own units. */
    val scale: Double get() = (home.firstOrNull()?.second?.height ?: 1.0) / (leading * size)

    /**
     * Frames the whole thing takes to come back to itself: a beat a word in, [hold] beats
     * standing, a beat a word out. Stating a length as well would be a second number to keep in
     * step, and it would be wrong for a sentence of another length.
     */
    val turn: Int = ((2 * words.size + hold) * beat).coerceAtLeast(1)

    /** One word, where it stands and how far it is there. */
    class Word(val text: String, val box: Rectangle, val on: Double)

    /**
     * Every word at [frame] frames into the turn: in reading order, each with how far it has
     * arrived — up over [move] on its own beat, and away again on its own beat later.
     */
    fun at(frame: Int): List<Word> {
        val now = frame.mod(turn)
        return home.mapIndexed { i, (text, box) ->
            val on = ramp(now - i * beat)
            val off = ramp(now - (words.size + hold + i) * beat)
            Word(text, box, on * (1.0 - off))
        }
    }

    /**
     * Draws the sentence at [frame] in [ink].
     *
     * A word arrives by being drawn **fainter**, not smaller and not sliding: the card reads
     * this as a picture and a half-drawn word is a half-grown mark under it, so the elements
     * swell into the letters. On its own it reads as type coming up out of the paper.
     */
    fun draw(drawer: Drawer, frame: Int, ink: ColorRGBa = ColorRGBa.WHITE) {
        drawer.fontMap = face
        at(frame).forEach { word ->
            if (word.on <= 0.002) return@forEach
            drawer.fill = ink.opacify(word.on)
            drawer.isolated {
                drawer.translate(word.box.corner)
                drawer.scale(scale)
                drawer.text(word.text, 0.0, BASELINE * leading * size)
            }
        }
    }

    /**
     * The frame a word may be placed in: the measure, opened out by [bleed] of the word's own
     * size on every side, so a word can be cropped by the frame rather than fitted into it.
     */
    fun room(width: Double, height: Double) = Rectangle(
        box.corner.x - bleed * width, box.corner.y - bleed * height,
        box.width + 2 * bleed * width, box.height + 2 * bleed * height
    )

    /** How far a word is in or out, [move] frames after its beat comes up. */
    private fun ramp(into: Int): Double =
        easeInOutCubic((into.toDouble() / move.coerceAtLeast(1)).coerceIn(0.0, 1.0))

    /** The largest size at which the whole sentence stands apart — see the note above. */
    private fun dealt(): List<Pair<String, Rectangle>> {
        if (words.isEmpty()) return emptyList()
        var scale = face.setToFit(text, box, size, leading, lines).scale * UP
        repeat(STEPS) {
            val laid = deal(scale)
            if (laid.size == words.size) return laid
            scale *= SHRINK
        }
        return deal(scale)
    }

    /** One attempt at a deal, at one size. Empty when the whole sentence will not stand. */
    private fun deal(scale: Double): List<Pair<String, Rectangle>> {
        val row = leading * size * scale
        val margin = gap * row
        val random = Random(text.hashCode())
        val placed = mutableListOf<Rectangle>()

        // **Placed widest first, handed back in reading order.** A long word left until last has
        // to find a gap the short ones have already broken up, so a deal fails at a size that
        // would have seated comfortably — but the order this comes back in is the order the
        // sentence is built in, and that has to be the order it is read in.
        val seats = arrayOfNulls<Rectangle>(words.size)
        for (index in words.indices.sortedByDescending { face.advanceOf(words[it]) }) {
            val width = face.advanceOf(words[index]) * scale
            val room = room(width, row)
            val free = room.width - width
            val fall = room.height - row
            if (free <= 0.0 || fall <= 0.0) return emptyList()

            fun clear(x: Double, y: Double): Rectangle? {
                val grown = Rectangle(x - margin, y - margin, width + 2 * margin, row + 2 * margin)
                return if (placed.none { it.intersects(grown) }) Rectangle(x, y, width, row) else null
            }

            var found: Rectangle? = null
            repeat(TRIES) {
                if (found == null) found = clear(
                    room.corner.x + random.nextDouble() * free,
                    room.corner.y + random.nextDouble() * fall
                )
            }
            if (found == null) {
                // the lattice: coarse enough to be quick, fine enough to find the gap
                val step = row / 2.0
                var y = room.corner.y
                while (found == null && y <= room.corner.y + fall) {
                    var x = room.corner.x
                    while (found == null && x <= room.corner.x + free) {
                        found = clear(x, y)
                        x += step
                    }
                    y += step
                }
            }
            seats[index] = found ?: return emptyList()
            placed += seats[index]!!
        }
        return words.mapIndexed { i, word -> word to seats[i]!! }
    }

    companion object {
        /** Where the baseline sits inside a line, as a fraction of the leading. */
        const val BASELINE = 0.78

        /**
         * The most of a word that may hang off the frame. Under a half by a margin, so a
         * cropped word always has more of itself on the frame than off it.
         */
        const val HALF = 0.45

        /** Where the search for a deal's size opens, against the size a block would be set at. */
        const val UP = 3.4

        /** What it is stood down by when the whole sentence will not fit, and how often. */
        const val SHRINK = 0.92
        const val STEPS = 20

        /** Places a word is offered before it goes looking on the lattice. */
        const val TRIES = 120
    }
}

/**
 * The sketch: the sentence on its own, white on black, building and clearing for ever.
 *
 *     ./gradlew run -Popenrndr.application=KineticTypeKt
 *
 * `b` outlines the boxes the deal gave the words, `esc` quits. `KINETIC_RECORD=true` films one
 * turn — the length is the turn itself, since that is what loops — and `KINETIC_STILL=t` writes
 * the frame at t seconds and quits.
 *
 * Everything is composed into a target at `KINETIC_WIDTH` x `KINETIC_HEIGHT` and the window only
 * shows that image, so a still, a filmed frame and what is on screen are the same pixels — the
 * arrangement the deck and the card both use, for the same reason.
 */
fun main() = application {
    val width = Env["KINETIC_WIDTH"]?.toIntOrNull() ?: 1920
    val height = Env["KINETIC_HEIGHT"]?.toIntOrNull() ?: 1080
    val scale = Env["KINETIC_WINDOW_SCALE"]?.toDoubleOrNull() ?: 1.0

    configure {
        this.width = (width * scale).toInt()
        this.height = (height * scale).toInt()
        title = "kinetic type"
    }

    program {
        val ink = ColorRGBa.fromHex(Env["KINETIC_INK"] ?: "#FFFFFF")
        val paper = ColorRGBa.fromHex(Env["KINETIC_PAPER"] ?: "#000000")
        val inset = Env["KINETIC_INSET"]?.toDoubleOrNull() ?: 70.0
        val record = Env.boolean("KINETIC_RECORD")

        // The card's own face, so the sketch and the chapter it stands for are set the same.
        val face = loadFont(cardFont, SIZE, TYPE_CHARACTERS, contentScale = 1.0)
        val type = KineticType(
            text = Env["KINETIC_TEXT"] ?: "De wereld van bouwen",
            face = face,
            size = SIZE,
            leading = Env["KINETIC_LEADING"]?.toDoubleOrNull() ?: 0.95,
            box = Rectangle(inset, inset, width - 2 * inset, height - 2 * inset),
            beat = frames(Env["KINETIC_BEAT"]?.toDoubleOrNull() ?: 2.0),
            move = frames(Env["KINETIC_MOVE"]?.toDoubleOrNull() ?: 0.4),
            hold = Env["KINETIC_HOLD"]?.toIntOrNull() ?: 2,
            bleed = Env["KINETIC_BLEED"]?.toDoubleOrNull() ?: 0.5
        )
        println("\"${type.text}\": ${type.words.size} words, turn %.1fs".format(type.turn / FPS.toDouble()))

        var boxes = Env.boolean("KINETIC_BOXES")
        val canvas = renderTarget(width, height) {
            colorBuffer()
            depthBuffer(DepthFormat.DEPTH24_STENCIL8)
        }
        canvas.colorBuffer(0).filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        canvas.colorBuffer(0).filterMag = MagnifyingFilter.LINEAR

        keyboard.keyDown.listen {
            when {
                it.key == KEY_ESCAPE -> application.exit()
                it.name == "b" -> boxes = !boxes
            }
        }

        if (record) {
            extend(ScreenRecorder().apply {
                outputFile = "video/kinetic-type.mp4"
                frameRate = Env["KINETIC_FPS"]?.toIntOrNull() ?: FPS
                contentScale = 1.0 / scale
                // one turn exactly, which is what loops
                maximumDuration = Env["KINETIC_DURATION"]?.toDoubleOrNull() ?: (type.turn / FPS.toDouble())
            })
        }
        val still = Env["KINETIC_STILL"]?.toDoubleOrNull()

        extend {
            // The clock is read here and nowhere else, so this is video time while recording —
            // the note under demo01 in CLAUDE.md.
            val frame = (seconds * FPS).toInt()

            drawer.isolatedWithTarget(canvas) {
                drawer.ortho(canvas)
                drawer.clear(paper)
                drawer.stroke = null
                type.draw(drawer, frame, ink)

                if (boxes) {
                    drawer.fill = null
                    drawer.strokeWeight = 3.0
                    drawer.stroke = ColorRGBa.fromHex("FF3B30")
                    drawer.rectangle(0.0, 0.0, width.toDouble(), height.toDouble())
                    drawer.stroke = ColorRGBa.fromHex("0A84FF")
                    type.at(frame).forEach { drawer.rectangle(it.box) }
                    drawer.strokeWeight = 1.0
                    drawer.stroke = null
                }
            }

            val shown = Rectangle(0.0, 0.0, this.width.toDouble(), this.height.toDouble())
            val fit = min(shown.width / width, shown.height / height)
            val into = Rectangle.fromCenter(shown.center, width * fit, height * fit)
            if (fit < 1.0) canvas.colorBuffer(0).generateMipmaps()
            drawer.clear(ColorRGBa.BLACK)
            drawer.image(canvas.colorBuffer(0), into.corner.x, into.corner.y, into.width, into.height)

            still?.let {
                if (frame >= frames(it)) {
                    val file = File("screenshots/kinetic-type-%.1fs.png".format(it))
                    file.parentFile.mkdirs()
                    canvas.colorBuffer(0).saveToFile(file)
                    println("saved ${file.path}")
                    application.exit()
                }
            }
        }
    }
}

/** The size the atlas is baked at. Everything is scaled off it, never past it. */
private const val SIZE = 190.0
