package slideshow.drawers

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.draw.FontImageMap
import org.openrndr.draw.isolated
import org.openrndr.draw.loadFont
import org.openrndr.math.Vector2
import org.openrndr.shape.ShapeContour
import org.openrndr.shape.contour
import slideshow.Cut
import slideshow.Mark
import slideshow.Palette
import slideshow.Slide
import slideshow.Sound
import slideshow.Stage
import slideshow.frames
import slideshow.smoothstep
import kotlin.math.abs
import kotlin.math.min

/**
 * One node of the tree: a label, and whatever hangs off it.
 *
 * A leaf is a label and nothing else, which is the common case — the two lists a
 * [TreeSlide] takes are usually flat. Nesting is there for a branch that needs a level of
 * its own, and costs a click to open.
 */
data class TreeNode(val label: String = "", val children: List<TreeNode> = emptyList())

/** `node("Locatie", node("Bodemgesteldheid"), node("Topografie"))` — a branch and its own. */
fun node(label: String, vararg children: TreeNode) = TreeNode(label, children.toList())

/** A flat run of leaves, which is what a side of the tree usually is. */
fun nodes(vararg labels: String): List<TreeNode> = labels.map { TreeNode(it) }

/** The same from a list already declared, so one set of words can feed two slides. */
fun nodes(labels: List<String>): List<TreeNode> = labels.map { TreeNode(it) }

/**
 * A node-link tree growing out of one element, both ways at once: the labels ranged down
 * either side of the frame and a curve running from each of them into the middle.
 *
 * **It opens on the frame the slide before it closed on.** [opening] hands over a [Mark] —
 * the shape the previous slide left standing, at the size and place it left it — and the
 * tree's first state is exactly that and nothing else, so the cut between the two is
 * invisible. `CityMapSlide.closingMark` is the one that does this: the city closes down
 * onto a single catalogue element, and that element is this tree's root. The first click
 * then shrinks it to node size, turns it [accent], and fans the tree out of it.
 *
 * That handover is a load-order dependency and the only fragile thing here: [opening] is
 * called in this slide's `load`, and it can only answer if the slide that fills it was
 * declared *earlier* in the show. Declared the other way round the tree opens on its own
 * fallback rather than on the city, which draws perfectly well and is silently wrong.
 *
 * **The links are d3's `linkHorizontal`** — a cubic with both control points on the
 * midline between the two ends, which is what gives the tight bundle at the root and the
 * even fan out to the labels. They are drawn as `contour.sub(0, t)`, so growing one is
 * taking a shorter piece of the same curve rather than animating a different shape.
 *
 * **The type is one size across both sides.** It is set to the tighter of two limits — the
 * row pitch, and the width the widest label may take — so any number of labels of any
 * length composes rather than clipping, and a row of names all of the same kind of thing
 * looks like it. Fitting each side separately would set a short list larger than a long one
 * beside it.
 *
 * A label sits at its own node and ranges away from the middle, which is right for a leaf
 * and approximate for a branch: a branch's label runs back over the curves leaving it.
 * Nothing in the show needs that yet.
 *
 * **[highlights] picks a few things out before the fan.** Forty labels arriving at once is a
 * picture; one arriving is a point. So the first clicks draw one named factor each — its curve
 * and its label, in [accent], with nothing else on the pane but the root — and only the click
 * after those opens the whole fan around them. The picked ones keep the accent once the rest
 * are up, so the two the speaker named stay the two the room can find. The root shrinks to
 * node size on the very first click either way, since a curve has to have an edge to leave.
 *
 * **A highlight stands beside the root while it is the only thing there, and travels to its own
 * row when the fan opens.** Drawn at its settled place from the start it is wherever its row
 * happens to fall — "Locatie" is the top of the left column, a line of type in the corner of an
 * otherwise empty pane — and the one thing this click has to do is let the word be read. So while
 * the highlights are on their own they are dealt half a row either side of the middle, a stem
 * each, and the click that opens the fan carries them out to their rows with everything else.
 * They are named one a side in the show, so the pane reads left, then right, then all of it.
 */
class TreeSlide(
    /** The branch running down the left of the frame, labels ranged right. */
    private val left: List<TreeNode> = emptyList(),
    /** The branch running down the right, labels ranged left. */
    private val right: List<TreeNode> = emptyList(),
    /** The shape the previous slide left standing. See the note above on load order. */
    private val opening: () -> Mark? = { null },
    private val fontPath: String = "data/fonts/default.otf",
    /** What the root becomes as the tree opens, and what a highlighted factor is drawn in. Null leaves the ink. */
    private val accent: ColorRGBa? = Palette.onBlack.structure,
    /** Pane pixels the root settles at, once it is a node rather than the whole subject. */
    private val rootHeight: Double = 94.0,
    private val pace: Double = 1.2,
    /** Labels drawn one a click, in [accent], before the fan opens. In the order they are named. */
    private val highlights: List<String> = emptyList(),
    /**
     * The cue as the fan opens — **not** on arrival.
     *
     * This slide opens on the city's own last frame, one element on an empty pane, and holds
     * there: a cue on the arrival would mark a cut nobody can see. What it should mark is the
     * fan coming apart, which is the click after the highlights.
     */
    private val fanCue: Sound? = null
) : Slide() {
    override val name = "Tree"

    /** A click a highlight, then one click a level, and the first of all is the tree arriving. */
    override val steps = 1 + highlights.size + maxOf(depthOf(left), depthOf(right))
    override val stepFrames = frames(pace)

    /** The click that opens the whole fan: the one after the last highlight. */
    private val fanStep: Int get() = 1 + highlights.size

    /** Seamless: the first frame here is the last frame of the slide before it. */
    override val transition = Cut

    override fun stepName(step: Int) = when {
        step in 1..highlights.size -> highlights[step - 1]
        step == fanStep -> "open the tree"
        else -> null
    }

    override fun stepSound(step: Int): Sound? = if (step == fanStep) fanCue else null

    /** The ground is taken from the shape handed over, so the two slides cannot disagree. */
    override val background: ColorRGBa get() = mark?.paper ?: ColorRGBa.BLACK
    private val ink: ColorRGBa get() = mark?.ink ?: ColorRGBa.WHITE

    private var mark: Mark? = null
    private lateinit var face: FontImageMap

    override fun load(program: Program) {
        mark = opening()
        face = program.loadFont(fontPath, ATLAS, TYPE_CHARACTERS, contentScale = 1.0)
    }

    override fun draw(drawer: Drawer, stage: Stage) {
        val opened = stage.on(1)
        val centre = stage.center

        // One scale for both sides: the tighter of the row pitch and the measure a label
        // may take, so the type is set to the frame rather than to a number chosen here.
        val rows = maxOf(leavesOf(left), leavesOf(right), 1)
        val pitch = (stage.height - 2 * INSET) / rows
        val widest = (left + right).flatMap { flatten(it) }
            .maxOfOrNull { face.advanceOf(it.label) } ?: 1.0
        val measure = stage.width * COLUMN
        val scale = min(pitch * LINE / ATLAS, measure / widest.coerceAtLeast(1.0))
        val column = widest * scale

        // Where a link meets the root, and where it meets a label. The root's own half
        // width comes off the shape rather than being guessed, so the bundle sits on its
        // edge whatever element the city happened to close on.
        val root = mark
        val height = mix(root?.height ?: rootHeight, rootHeight, opened)
        val hub = (root?.halfWidth ?: 0.5) * height + HUB_GAP
        val tip = stage.width / 2.0 - INSET - column - LABEL_GAP

        drawer.strokeWeight = LINK

        for (side in listOf(-1, 1)) {
            val branch = if (side < 0) left else right
            if (branch.isEmpty()) continue

            val placed = place(branch, side, centre, stage.height, pitch, hub, tip)
            placed.forEach { one ->
                // A highlighted factor has a click of its own and arrives whole; the rest open
                // from the middle of the fan outwards, which is the order the bundle at the
                // root can actually come apart in, on the clicks after the highlights.
                val picked = highlights.indexOf(one.node.label)
                val grown = if (picked >= 0) stage.on(picked + 1)
                            else smoothstep((stage.on(fanStep + one.depth - 1) - one.row * STAGGER) / (1.0 - STAGGER))
                if (grown <= 0.0) return@forEach
                val tone = if (picked >= 0) accent ?: ink else ink

                // While a highlight is on its own it stands beside the root, half a row off the
                // middle, and the fan's own click carries it out to its row.
                val at = if (picked < 0) one.at else {
                    val waiting = centre.y + (picked - (highlights.size - 1) / 2.0) * pitch
                    Vector2(one.at.x, waiting + (one.at.y - waiting) * stage.on(fanStep))
                }

                drawer.fill = null
                drawer.stroke = tone.opacify(grown)
                drawer.contour(link(one.from, at).sub(0.0, grown))

                // the label arrives with the last of its own curve
                val shown = smoothstep((grown - 1.0 + LABEL_IN) / LABEL_IN)
                if (one.node.label.isNotBlank() && shown > 0.0) {
                    drawer.stroke = null
                    drawer.fill = tone.opacify(shown)
                    label(drawer, one.node.label, at + Vector2(side * LABEL_GAP, 0.0), scale, side)
                }
            }
        }

        // The root last, so the bundle runs under it rather than across it.
        drawer.stroke = null
        drawer.fill = accent?.let { mix(ink, it, opened) } ?: ink
        root?.draw(drawer, centre, height) ?: drawer.circle(centre, height / 2.0)
    }

    // --- layout: a pure function of the branch and the frame ------------------------ //

    /** One node, placed: where it sits, and where the curve that reaches it starts. */
    private class Placed(
        val node: TreeNode, val depth: Int, val at: Vector2, val from: Vector2,
        /** 0 in the middle of the fan, 1 at either end of it — the stagger reads off this. */
        val row: Double
    )

    private fun place(
        branch: List<TreeNode>, side: Int, centre: Vector2, height: Double,
        pitch: Double, hub: Double, tip: Double
    ): List<Placed> {
        val out = mutableListOf<Placed>()
        val leaves = branch.sumOf { leavesOf(listOf(it)) }.coerceAtLeast(1)
        val depth = depthOf(branch).coerceAtLeast(1)
        val top = centre.y - leaves * pitch / 2.0
        val cursor = intArrayOf(0)

        fun x(level: Int) = centre.x + side * (hub + (tip - hub) * level / depth)

        fun walk(node: TreeNode, level: Int, from: Vector2): Double {
            val here = out.size
            out += Placed(node, level, Vector2.ZERO, from, 0.0)   // y follows the children
            val at = Vector2(x(level), 0.0)
            val y = if (node.children.isEmpty()) {
                top + (cursor[0]++ + 0.5) * pitch
            } else {
                node.children.map { walk(it, level + 1, at) }.average()
            }
            val settled = Vector2(at.x, y)
            out[here] = Placed(node, level, settled, from, 0.0)
            // a parent's children were placed against a y it did not have yet, so their
            // start points are corrected once it does
            for (i in here + 1 until out.size) {
                if (out[i].depth == level + 1 && out[i].from.x == at.x)
                    out[i] = Placed(out[i].node, out[i].depth, out[i].at, settled, 0.0)
            }
            return y
        }

        branch.forEach { walk(it, 1, centre) }

        // How far off the middle of the fan each one is, for the stagger.
        val middle = centre.y
        val reach = (leaves * pitch / 2.0).coerceAtLeast(1.0)
        return out.map { Placed(it.node, it.depth, it.at, it.from, abs(it.at.y - middle) / reach) }
    }

    /** d3's `linkHorizontal`: both control points on the midline between the two ends. */
    private fun link(from: Vector2, to: Vector2): ShapeContour {
        val mid = (from.x + to.x) / 2.0
        return contour {
            moveTo(from)
            curveTo(Vector2(mid, from.y), Vector2(mid, to.y), to)
        }
    }

    /** One line, ranged away from the middle, sitting on its row. */
    private fun label(drawer: Drawer, text: String, at: Vector2, scale: Double, side: Int) {
        val width = face.advanceOf(text) * scale
        drawer.fontMap = face
        drawer.isolated {
            drawer.translate(at.x - if (side < 0) width else 0.0, at.y + ATLAS * scale * BASELINE)
            drawer.scale(scale)
            drawer.text(text, 0.0, 0.0)
        }
    }

    private fun mix(a: ColorRGBa, b: ColorRGBa, t: Double) = ColorRGBa(
        a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t,
        a.b + (b.b - a.b) * t, a.alpha + (b.alpha - a.alpha) * t
    )

    private fun mix(a: Double, b: Double, t: Double) = a + (b - a) * t

    private companion object {
        /** The atlas the labels are baked at; everything is scaled down from it, never up. */
        const val ATLAS = 64.0

        /** The frame's own margin, top and bottom and at the ends of the label columns. */
        const val INSET = 40.0

        /** Of the row pitch, how much a line of type may take. */
        const val LINE = 0.66

        /** Of the frame's width, how much one label column may take. */
        const val COLUMN = 0.28

        const val LABEL_GAP = 14.0
        const val HUB_GAP = 18.0
        const val LINK = 1.6
        const val BASELINE = 0.31

        /** How much of the click the fan is spread over, middle rows first. */
        const val STAGGER = 0.4

        /** The last of a curve's own growth, over which its label arrives. */
        const val LABEL_IN = 0.35
    }
}

// --- shape of the tree, which the step count is read off before anything is drawn --- //

private fun flatten(node: TreeNode): List<TreeNode> = listOf(node) + node.children.flatMap { flatten(it) }

private fun leavesOf(branch: List<TreeNode>): Int =
    branch.sumOf { if (it.children.isEmpty()) 1 else leavesOf(it.children) }

private fun depthOf(branch: List<TreeNode>): Int =
    branch.maxOfOrNull { 1 + depthOf(it.children) } ?: 0
