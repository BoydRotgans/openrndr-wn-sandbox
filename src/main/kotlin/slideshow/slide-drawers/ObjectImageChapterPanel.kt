// ============================================================================ //
//  No `package` declaration, deliberately, and for the same reason
//  ObjectChapterPanel has none: loadObjectTemplates, mosaicField, fieldBuffer and
//  MOSAIC_FIELD all live in the default package, which Kotlin cannot import into a
//  named one. The file still belongs in slide-drawers/, which is a folder rather
//  than a package.
// ============================================================================ //

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.DrawPrimitive
import org.openrndr.draw.Drawer
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.VertexBuffer
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.isolated
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadImage
import org.openrndr.draw.parameter
import org.openrndr.draw.renderTarget
import org.openrndr.draw.shadeStyle
import org.openrndr.math.Vector2
import org.openrndr.shape.Rectangle
import slideshow.Cut
import slideshow.Section
import slideshow.Slide
import slideshow.Stage
import slideshow.drawers.Type
import slideshow.drawers.advanceOf
import slideshow.frames
import java.io.File
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/**
 * The chapter card again, but reading a **picture off disk** rather than type it sets itself.
 *
 * Everything downstream of the plate is `ObjectChapterPanel`'s and unchanged: a standing field
 * of Willy Naessens elements reads a black and white plate and stands wherever the ink is, the
 * marks sized by how much of their cell that ink covers, the lot arriving one at a time out of
 * one draw call. What changes is only where the plate comes from — [image] instead of
 * [slideshow.drawers.TypeBlock] — which is the whole point of doing it through a plate in the
 * first place: the field never knew it was reading letters.
 *
 * **So a chapter title that has been drawn rather than set can still be made of components.**
 * Lettering the show's own face cannot reach — a logotype, a title spaced and kerned by hand in
 * Figma, a mark that is not type at all — goes in as a png and comes out as catalogue.
 *
 * What the picture has to be: **white on black, at the pane's proportion.** White is ink and
 * black is ground, which is what the mask means everywhere else in this pipeline — a picture
 * the other way round is what [invert] is for. Anything in between is read as partial coverage
 * and comes out as part-grown marks, so a soft-edged picture gives a soft-edged field, which is
 * either the point or a surprise depending on the picture.
 *
 * **The plate is painted once, in `load`, and never again** — and that is the one real
 * difference from the card that sets its own type. There, the words move, so the plate is
 * repainted and its mip chain rebuilt every frame; here the picture is a file and cannot move,
 * so both happen once and a frame is one `vertexBuffer` call against a texture that was ready
 * before the card came up. The elements still arrive over [reveal], because that is baked into
 * the field rather than into the picture.
 *
 * If the sheet is missing — `data/` is not committed — it falls back to drawing the picture
 * itself, which is the honest equivalent of the other card falling back to plain type. If the
 * *picture* is missing there is nothing to draw and the card says so at load rather than
 * failing the run.
 */
class ObjectImageChapterPanel(
    private val section: Section,
    /** The picture the field reads. White on black — see [invert] for the other way round. */
    private val image: String = Env["SLIDES_CARD_IMAGE"] ?: "data/slides/chapter0.png",
    /**
     * Read the picture the other way up: dark ink on a light ground becomes light on dark.
     *
     * It is applied to the picture alone and not to the plate it is drawn onto, which stays
     * black — black is "no ink" whichever way the picture reads, so a picture that does not
     * cover the whole pane leaves ground rather than a band of marks.
     */
    private val invert: Boolean = Env.boolean("SLIDES_CARD_IMAGE_INVERT"),
    /**
     * The picture's black and white points, `lo,hi`, applied to the plate before the field
     * reads it. `0,1` leaves it alone.
     *
     * **This is not a taste control, it is what makes a picture readable as a mask.** The field
     * asks one question of the plate — how much of this cell is ink — and answers it with a mip
     * average, so a word drawn in *grey* covers less of its cell than the same word drawn in
     * white and stands in the ground colour however thick its strokes are. Measured on
     * `chapter0.png`: "WERELD" and "BOUWEN" are solid white, p95 of 255, while "DE" and "VAN"
     * are soft grey outlines whose lit pixels average 106 — the same letters, a third of the
     * signal. Pulling the white point down to where the grey type actually sits lifts it to
     * full ink; holding the black point just above the picture's blur skirt is what stops the
     * halo coming with it, which a plain gain cannot do.
     */
    private val levels: Pair<Double, Double> =
        (Env["SLIDES_CARD_LEVELS"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.takeIf { it.size == 2 }?.let { it[0] to it[1] }) ?: (0.0 to 1.0),
    /**
     * The marks: a catalogue sheet, or a **folder** with one svg to a mark — see
     * [loadMarkTemplates]. A folder is what a set of drawn marks looks like on disk when it
     * was not exported as a catalogue page, and `data/svg/3-shapes` is one.
     */
    private val sheet: String = Env["SLIDES_CARD_SHEET"] ?: Env["CITY_OBJECT_SHEET"]
        ?: "data/svg/subset.svg",
    /** The biggest a mark may be — a cell this size wholly inside the ink stands one. */
    private val coarse: Double = Env["SLIDES_CARD_COARSE"]?.toDoubleOrNull() ?: 32.0,
    /** The smallest, which is what traces an edge. Equal to [coarse] is a field of one size. */
    private val finest: Double = Env["SLIDES_CARD_FINEST"]?.toDoubleOrNull() ?: coarse,
    /** The cell's wide-to-high. Unset takes the sheet's own median — see [mosaicField]. */
    private val shape: Double? = Env["SLIDES_CARD_SHAPE"]?.toDoubleOrNull(),
    /**
     * Which pieces of the sheet to use, by index, or null for all of them.
     *
     * It matters more here than it looks. A mark's *proportion* is the sheet's, not the card's,
     * and a cell of another proportion either letterboxes it or — with [uniform] — lets it
     * overflow: `subset.svg` runs 0.85 to 3.29 wide-to-high, so a square cell suits two of its
     * fifteen pieces and smears or starves the rest. Naming the few that fit the cell is how a
     * catalogue sheet gives a square grain at all. The same affordance as `DECISION_OBJECTS`,
     * and for the same reason: the pieces are real components and they are not interchangeable.
     */
    private val objects: List<Int>? =
        Env["SLIDES_CARD_OBJECTS"]?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.isNotEmpty() },
    /** How much of its cell an element takes, so the rest of it is the air around the mark. */
    private val fill: Double = Env["SLIDES_CARD_FILL"]?.toDoubleOrNull() ?: 0.9,
    /** Every element at one height, rather than each fitted by its own proportion. */
    private val uniform: Boolean = Env.boolean("SLIDES_CARD_UNIFORM", true),
    /**
     * Clearance held between neighbouring marks, in pane pixels, whatever size they are — see
     * [mosaicField]. [fill] alone cannot hold it: being a fraction of the cell it leaves a
     * hairline between two fine marks and a chasm between two coarse ones.
     */
    private val gap: Double = Env["SLIDES_CARD_GAP"]?.toDoubleOrNull() ?: 2.0,
    /** What the elements standing outside the ink are drawn in. `none` leaves the card bare. */
    private val ground: ColorRGBa? = imageGround(Env["SLIDES_CARD_GROUND"]),
    /** What the picture is picked out in. */
    private val ink: ColorRGBa = ColorRGBa.fromHex(Env["SLIDES_CARD_INK"] ?: "#FFFFFF"),
    override val background: ColorRGBa = ColorRGBa.fromHex(Env["SLIDES_CARD_PAPER"] ?: "#000000"),
    /** How small a mark standing wholly outside the ink is drawn — 1 hands the job to colour. */
    private val shrink: Double = Env["SLIDES_CARD_SHRINK"]?.toDoubleOrNull() ?: 0.3,
    /** How much ink a cell needs before it is drawn in [ink] rather than [ground]. */
    private val threshold: Double = Env["SLIDES_CARD_THRESHOLD"]?.toDoubleOrNull() ?: 0.32,
    /**
     * How much ink a cell needs to count as **wholly inside** a stroke, and so to stand at its
     * own size rather than hand the place to four smaller cells.
     *
     * It is what decides how big a mark a piece of lettering is drawn with, and the default of
     * 0.88 is strict. Over a thin stroke no coarse cell ever reaches it, so every cell there
     * subdivides to the finest level and the word comes out in the smallest marks the field
     * has — beside a ground standing in the largest. Measured on `chapter0.png`, that is
     * exactly why "VAN" would not read while "BOUWEN" did: not too little ink, but ink drawn
     * at a quarter of the size of everything around it. Lowering this lets a mostly-covered
     * cell stand, so a thin stroke is carried by marks the size of its neighbours.
     */
    private val solid: Double = Env["SLIDES_CARD_SOLID"]?.toDoubleOrNull() ?: 0.88,
    /** How long the whole field takes to arrive. */
    private val reveal: Int = frames(Env["SLIDES_CARD_REVEAL"]?.toDoubleOrNull() ?: 1.2),
    /** How long one element takes to grow from its own centre. */
    private val pop: Int = frames(Env["SLIDES_CARD_POP"]?.toDoubleOrNull() ?: 0.3),
    /**
     * How much of the arrival is a staged sweep rather than the field's baked random order.
     *
     * 0 is the order the typeset card uses: every cell independent, the whole field filling in
     * at once. 1 stages it in two passes — the ground up from the foot, then the ink down from
     * the head — which is what makes the card read as *a wall being built and then written on*
     * rather than as a picture fading up.
     *
     * It cannot be baked into the buffer, and that is the point of doing it in the shader:
     * which pass a cell belongs to is whether it is standing in ink, and that is the mask's
     * answer, read per frame. A card whose picture moves would restage itself as it went.
     */
    private val sweep: Double = Env["SLIDES_CARD_SWEEP"]?.toDoubleOrNull() ?: 1.0,
    /** The ground pass's share of the arrival, before the pause and the ink pass. */
    private val stage: Double = Env["SLIDES_CARD_STAGE"]?.toDoubleOrNull() ?: 0.45,
    /** The pause between the two passes, as a share of the arrival. */
    private val delay: Double = Env["SLIDES_CARD_DELAY"]?.toDoubleOrNull() ?: 0.12,
    private val paneWidth: Int = 1920,
    private val paneHeight: Int = 1080
) : Slide(), MosaicCard {

    override val name get() = section.chapter.ifBlank { "Panel" }

    /**
     * A cut, for the same reason the other card cuts: a crossfade between two cards reads as one
     * title dissolving into another — as something happening beside the slide, rather than as
     * the heading having changed.
     */
    override val transition = Cut

    /** Draw the plate itself rather than the field that reads it — `CardStudio`'s `b`. */
    override var plainly = Env.boolean("SLIDES_CARD_DEBUG")

    /** The picture, as loaded. */
    private var picture: ColorBuffer? = null

    /** The plate the field reads: the picture, on the cell's aspect, with its mip chain. */
    private var plate: RenderTarget? = null

    /** The standing field: every cell of the quadtree, at every level, in one buffer. */
    private var cells: VertexBuffer? = null
    private var cellCount = 0

    /** The catalogue itself, kept so the field can be rebuilt at another size — see [rescale]. */
    private var templates: List<ObjectTemplate> = emptyList()
    private var seed = 0

    /** The sheet the marks came off, and what its objects are shaped like. */
    private var catalogue = ""

    /** [shape], or the sheet's own median once it has been read. */
    private var cell = 1.9

    /** The mark size in force: [coarse] and [finest], until the studio's j/k moves them. */
    private var markSize = 0.0
    private var markFinest = 0.0

    override fun load(program: Program) {
        Type.load(program)

        val file = File(image)
        if (!file.isFile) {
            println("no card image at ${file.path}; \"${section.chapter}\" has nothing to read")
            return
        }
        picture = loadImage(file.path)

        val sheetFile = File(sheet)
        if (!sheetFile.exists()) {
            println("no object sheet at ${sheetFile.path}; drawing ${file.name} as it is")
            return
        }
        // A sheet or a folder of marks — see [loadMarkTemplates]. A folder is how a set of
        // drawn marks actually sits on disk when it was not exported as a catalogue page.
        val all = loadMarkTemplates(sheetFile)
        // Named pieces, in the order named; an index the sheet does not hold is dropped rather
        // than failing the run, so a selection written for one sheet still opens on another.
        templates = objects?.mapNotNull { all.getOrNull(it) }?.takeIf { it.isNotEmpty() } ?: all
        if (templates.isEmpty()) return

        // Seeded off the chapter, so a card is the same field every run and two chapters are
        // not the same field as each other.
        seed = section.chapter.hashCode()
        val median = templates.map { it.aspect }.sorted()[templates.size / 2]
        cell = shape ?: median
        markSize = coarse
        markFinest = finest.coerceAtMost(coarse)
        catalogue = "%s, %d of %d objects, median %.2f".format(
            sheetFile.name, templates.size, all.size, median)
        stand()

        // The plate the field reads. **On the cell's aspect, not the pane's**: a cell is then
        // square in texels, so a square mip average is exactly the cell's own box and coverage
        // costs one `textureLod` — see [MOSAIC_FIELD].
        val across = (paneWidth / TEXEL).toInt()
        val down = (paneHeight * cell / TEXEL).toInt()

        // **Asked for with a mip chain, and it has to be asked for**: a colour buffer is one
        // level by default, and `textureLod` on a one-level texture does not fail — it hands
        // back level 0. Every cell then reads a point sample at its own centre, which is always
        // 0 or 1, so nothing ever straddles an edge and nothing subdivides. It renders, and it
        // is unreadable.
        // **Twice the texels.** `contentScale` multiplies the real size behind the same
        // coordinates, so nothing downstream changes — the shader samples in normalised uv and
        // lays out in pane pixels either way — while every mip level is averaged from four
        // times the samples. The chain has to be counted on the *real* size, or the last level
        // is not a single texel and a coarse cell reads a blur rather than its own average.
        val buffer = colorBuffer(
            across, down,
            contentScale = DETAIL,
            levels = imageMips((across * DETAIL).toInt(), (down * DETAIL).toInt())
        )
        buffer.filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
        buffer.filterMag = MagnifyingFilter.LINEAR
        val target = renderTarget(across, down, contentScale = DETAIL) { colorBuffer(buffer) }
        plate = target

        // Painted here rather than in `draw`, and this is the whole difference from the card
        // that sets its own type: a picture off disk cannot move, so the plate and its mip
        // chain are built once with the field and a frame never touches them again.
        paint(program.drawer, target, picture!!)
        buffer.generateMipmaps()

        println("chapter card \"${section.chapter}\": $recipe")
    }

    /** The field at the mark size in force. The plate is unaffected — it is the picture. */
    private fun stand() {
        cells?.destroy()
        val field = mosaicField(
            paneWidth, paneHeight, templates, markSize, markFinest, cell, fill, gap, seed, uniform
        )
        cellCount = field.size
        cells = fieldBuffer(field, templates)
    }

    /**
     * The marks a step bigger or smaller, and the field rebuilt around them — the studio's `j`
     * and `k`. A rebuild rather than a scale, for the reason given on the other card: the cells
     * *are* the field, so a bigger mark is a different packing of the same picture rather than
     * the same picture drawn larger.
     */
    override fun rescale(by: Double) {
        if (templates.isEmpty()) return
        val oneSize = markFinest >= markSize
        markSize = (markSize * by).coerceIn(8.0, 256.0)
        markFinest = if (oneSize) markSize else (markFinest * by).coerceIn(4.0, markSize)
        stand()
        println("chapter card \"${section.chapter}\": $recipe")
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val pic = picture ?: return
        val mesh = cells
        val mask = plate

        // no sheet: the picture as it is, which is the honest fallback — the card is then a
        // plain title card rather than a field of components standing for one.
        if (mesh == null || mask == null) {
            composed(drawer, stage) {
                drawer.fill = ColorRGBa.WHITE
                drawer.image(pic, source(pic), placed(pic))
            }
            subchapter(drawer, stage)
            return
        }

        composed(drawer, stage) {
            if (plainly) showPlate(drawer, mask) else {
                drawer.fill = ink
                drawer.shadeStyle = standing(stage, mask)
                drawer.vertexBuffer(mesh, DrawPrimitive.TRIANGLES)
                drawer.shadeStyle = null
            }
        }
        subchapter(drawer, stage)
    }

    /**
     * The picture into the plate, in the pane's own coordinates.
     *
     * The plate is taller than the pane in proportion — see [load] — so laying out in pane
     * pixels means one scale here and nothing else anywhere having to know.
     */
    private fun paint(drawer: Drawer, mask: RenderTarget, pic: ColorBuffer) {
        val across = mask.width / paneWidth.toDouble()
        val down = mask.height / paneHeight.toDouble()
        drawer.isolatedWithTarget(mask) {
            drawer.ortho(mask)
            // Black is "no ink", whichever way round the picture reads — so a picture that does
            // not fill the pane leaves ground beside it rather than a band of standing marks.
            drawer.clear(ColorRGBa.BLACK)
            drawer.fill = ColorRGBa.WHITE
            drawer.stroke = null
            // Inversion and the levels in one pass over the picture, before anything reads it.
            drawer.shadeStyle = shadeStyle {
                fragmentTransform = """
                    vec3 c = x_fill.rgb;
                    ${if (invert) "c = vec3(1.0) - c;" else ""}
                    x_fill.rgb = clamp((c - p_black) / max(p_white - p_black, 1e-4), 0.0, 1.0);
                """
                parameter("black", levels.first)
                parameter("white", levels.second)
            }
            drawer.scale(across, down)
            drawer.image(pic, source(pic), placed(pic))
            drawer.shadeStyle = null
        }
    }

    /** The debug view: the plate itself, with the pane's own edge on it. */
    private fun showPlate(drawer: Drawer, mask: RenderTarget) {
        val card = Rectangle(0.0, 0.0, paneWidth.toDouble(), paneHeight.toDouble())
        drawer.fill = ColorRGBa.WHITE
        drawer.image(
            mask.colorBuffer(0),
            Rectangle(0.0, 0.0, mask.width.toDouble(), mask.height.toDouble()),
            card
        )
        drawer.fill = null
        drawer.strokeWeight = 3.0
        drawer.stroke = ColorRGBa.fromHex("FF3B30")
        drawer.rectangle(card)
        drawer.strokeWeight = 1.0
        drawer.stroke = null
    }

    private fun standing(stage: Stage, mask: RenderTarget) = shadeStyle {
        vertexTransform = MOSAIC_FIELD
        fragmentTransform = MOSAIC_FIELD_COLOUR
        parameter("mask", mask.colorBuffer(0))
        parameter("pane", Vector2(paneWidth.toDouble(), paneHeight.toDouble()))
        parameter("shape", cell)
        parameter("texel", TEXEL)
        // **[markFinest], not [finest]**: the field is rebuilt when j or k moves the mark size,
        // and a shader still holding the size the card was built with decides every cell is too
        // big to be the finest level — so nothing straddling an edge may stand and the picture
        // comes out as a hole in an otherwise intact ground.
        parameter("finest", markFinest)
        parameter("solid", solid)
        parameter("empty", EMPTY)
        parameter("threshold", threshold)
        parameter("shrink", shrink)
        parameter("ink", ink)
        parameter("ground", ground ?: background)
        // The count runs linearly — it is a count rather than a move, and an eased ramp crowds
        // the arrivals into the middle of it. What is eased is each element's own growth.
        parameter("sweep", sweep)
        parameter("stage", this@ObjectImageChapterPanel.stage)
        parameter("delay", delay)
        parameter("jitter", JITTER)
        parameter("arrived", stage.since(0, reveal + pop))
        parameter("ramp", (pop.toDouble() / (reveal + pop).coerceAtLeast(1)).coerceIn(0.01, 1.0))
    }

    /** The whole of the picture. */
    private fun source(pic: ColorBuffer) =
        Rectangle(0.0, 0.0, pic.width.toDouble(), pic.height.toDouble())

    /**
     * Where the picture stands on the pane: fitted inside it and centred, never cropped and
     * never stretched. A picture at the pane's own proportion — which is what a card wants —
     * fills it exactly and this costs nothing.
     */
    private fun placed(pic: ColorBuffer): Rectangle {
        val fit = min(paneWidth / pic.width.toDouble(), paneHeight / pic.height.toDouble())
        val w = pic.width * fit
        val h = pic.height * fit
        return Rectangle((paneWidth - w) / 2.0, (paneHeight - h) / 2.0, w, h)
    }

    /**
     * The pane the card was composed for, fitted into the pane it actually got, centred — and
     * clipped to it, so a field that runs to the edge of the card stays inside the card's frame.
     */
    private fun composed(drawer: Drawer, stage: Stage, draw: () -> Unit) {
        val fit = min(stage.width / paneWidth, stage.height / paneHeight)
        val corner = stage.center - Vector2(paneWidth * fit, paneHeight * fit) / 2.0
        drawer.stroke = null
        drawer.isolated {
            drawer.drawStyle.clip = Rectangle(corner, paneWidth * fit, paneHeight * fit)
            drawer.translate(corner)
            drawer.scale(fit, fit)
            draw()
            drawer.shadeStyle = null
        }
    }

    /** Where this sits in the show, kept quiet and out of the way of the picture. */
    private fun subchapter(drawer: Drawer, stage: Stage) {
        if (section.subchapter.isBlank()) return
        drawer.shadeStyle = null
        drawer.fontMap = Type.panelSub
        drawer.fill = ink.opacify(0.45)
        val label = "${section.number}   ${section.subchapter}"
        drawer.text(label, (stage.width - Type.panelSub.advanceOf(label)) / 2.0, stage.height - MARGIN)
    }

    /**
     * What this card is made of, in one line. Printed at load and read by `CardStudio`, so the
     * studio never keeps a second copy of the defaults to show.
     */
    override val recipe: String get() = ("%s   %d cells   coarse %.0f → finest %.0f   " +
            "cell %.2f%s   fill %.2f%s gap %.0fpx solid %.2f   picture %s%s   %s").format(
        catalogue, cellCount, markSize, markFinest,
        cell, if (shape == null) " (off the sheet)" else "",
        fill, if (uniform) " one size" else " fitted", gap, solid,
        File(image).name,
        (if (invert) " inverted" else "") +
            (if (levels != 0.0 to 1.0) " levels %.2f-%.2f".format(levels.first, levels.second) else ""),
        if (sweep > 0.0) "ground up %.0f%% → wait %.0f%% → ink down".format(stage * 100, delay * 100)
        else "arrives at random"
    )

    private companion object {
        /**
         * A little of each cell's own random order mixed back into the sweep, so the leading
         * edge is a ragged front of marks rather than a ruled line crossing the card.
         */
        const val JITTER = 0.06

        /**
         * Pane pixels to one texel of the plate. The plate only has to resolve a *cell*, since
         * that is the only question asked of it.
         */
        const val TEXEL = 4.0

        /**
         * Texels per plate pixel. The picture is a png at the pane's own size or better, so the
         * plate is the thing that decides how much of it survives to be averaged.
         */
        const val DETAIL = 2.0

        /** Where the subchapter line sits above the foot. */
        const val MARGIN = 120.0
    }
}

/**
 * `SLIDES_CARD_GROUND` as a colour: a hex value, `none` for a bare card, unset for the default.
 * Its own copy rather than the other card's, which is private to that file.
 */
private fun imageGround(value: String?): ColorRGBa? = when {
    value == null -> ColorRGBa.fromHex("343434")
    value.equals("none", ignoreCase = true) -> null
    else -> ColorRGBa.fromHex(value)
}

/** Levels in a full mip chain for a [width] x [height] picture — down to a single texel. */
private fun imageMips(width: Int, height: Int): Int =
    floor(log2(max(width, height).toDouble())).toInt() + 1
