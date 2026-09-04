import org.openrndr.extra.composition.CompositionNode
import org.openrndr.extra.composition.GroupNode
import org.openrndr.extra.composition.ShapeNode
import org.openrndr.extra.composition.findImages
import org.openrndr.extra.composition.findShapes
import org.openrndr.extra.svg.loadSVG
import org.openrndr.shape.Rectangle

/**
 * Reports what survives the Figma -> svg -> OPENRNDR round trip, frame by frame.
 *
 *     FIGMA_PAGE=SHEETS FIGMA_REFRESH=true ./gradlew run -Popenrndr.application=FigmaProbeKt
 *
 * There is no window: loadSVG is a parse and needs no GL context, so the whole deck can
 * be checked without presenting it. This answers the questions a slide player has to know
 * the answer to before it is worth writing — whether every frame parses, how heavy the
 * heaviest one is, whether the outlined text really came through as shapes, whether Figma
 * quietly rasterised anything, and whether a frame's content stays inside its own bounds.
 */
fun main() {
    val frames = fetchFigmaFrames()
    println("${frames.size} frames on '${Env["FIGMA_PAGE"] ?: "(first page)"}'\n")

    println(
        "%-14s %9s %7s %7s %8s %9s %6s %6s %6s %6s  %s".format(
            "frame", "svg", "w", "h", "shapes", "segments", "groups", "images", "text", "empty", "notes"
        )
    )

    var totalShapes = 0
    var totalSegments = 0
    var totalBytes = 0L
    var totalParseMs = 0L
    val failures = mutableListOf<String>()

    for (frame in frames) {
        val bytes = frame.svg.length()
        totalBytes += bytes

        val started = System.nanoTime()
        val composition = try {
            loadSVG(frame.svg)
        } catch (error: Throwable) {
            failures += "${frame.name}: ${error::class.simpleName} ${error.message}"
            println("%-14s %9s  PARSE FAILED — %s".format(frame.name, bytes.kb(), error.message))
            continue
        }
        val parseMs = (System.nanoTime() - started) / 1_000_000
        totalParseMs += parseMs

        val shapes = composition.findShapes()
        val segments = shapes.sumOf { node -> node.effectiveShape.contours.sumOf { it.segments.size } }
        val nodes = composition.root.walk().toList()
        val groups = nodes.count { it is GroupNode }
        val images = composition.findImages().size
        // svgOutlineText=true should mean no live <text> survives; anything here would not render
        val texts = nodes.count { it::class.simpleName == "TextNode" }
        val empty = shapes.count { it.effectiveShape.contours.isEmpty() }

        // Composition.bounds is a CompositionDimensions (svg Lengths); the viewBox as a Rectangle
        val bounds = Rectangle(composition.bounds.position, composition.bounds.dimensions.x, composition.bounds.dimensions.y)
        val notes = buildList {
            if (images > 0) add("RASTER")
            if (texts > 0) add("LIVE TEXT")
            if (parseMs > 250) add("${parseMs}ms")
            outsideOf(bounds, shapes)?.let { add(it) }
        }.joinToString(" ")

        totalShapes += shapes.size
        totalSegments += segments

        println(
            "%-14s %9s %7.0f %7.0f %8d %9d %6d %6d %6d %6d  %s".format(
                frame.name, bytes.kb(), bounds.width, bounds.height,
                shapes.size, segments, groups, images, texts, empty, notes
            )
        )
    }

    println()
    println("total: ${frames.size} frames, ${totalBytes.kb()}, $totalShapes shapes, $totalSegments segments")
    println("parsed in ${totalParseMs}ms (${totalParseMs / frames.size.coerceAtLeast(1)}ms average)")
    if (failures.isEmpty()) println("every frame parsed") else {
        println("\n${failures.size} FAILED:")
        failures.forEach { println("  $it") }
    }
}

/** How far the drawn shapes reach outside the composition's own bounds, if at all. */
private fun outsideOf(bounds: Rectangle, shapes: List<ShapeNode>): String? {
    val drawn = shapes.filter { it.effectiveShape.contours.isNotEmpty() }.map { it.effectiveShape.bounds }
    if (drawn.isEmpty()) return null
    val right = drawn.maxOf { it.x + it.width } - (bounds.x + bounds.width)
    val left = bounds.x - drawn.minOf { it.x }
    val over = maxOf(right, left)
    return if (over > 1.0) "overflows ${over.toInt()}px" else null
}

private fun CompositionNode.walk(): Sequence<CompositionNode> = sequence {
    yield(this@walk)
    if (this@walk is GroupNode) children.forEach { yieldAll(it.walk()) }
}

private fun Long.kb() = if (this > 1_048_576) "%.1fMB".format(this / 1_048_576.0) else "%dKB".format(this / 1024)
