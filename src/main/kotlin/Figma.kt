import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.openrndr.KEY_ARROW_LEFT
import org.openrndr.KEY_ARROW_RIGHT
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.DepthFormat
import org.openrndr.draw.MagnifyingFilter
import org.openrndr.draw.MinifyingFilter
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.colorBuffer
import org.openrndr.draw.isolatedWithTarget
import org.openrndr.draw.loadFont
import org.openrndr.draw.renderTarget
import org.openrndr.extra.composition.Composition
import org.openrndr.extra.composition.composition
import org.openrndr.extra.svg.loadSVG
import org.openrndr.math.Vector2
import org.openrndr.figma.rest.FigmaApi
import org.openrndr.figma.rest.Node
import org.openrndr.figma.rest.ParentNode
import java.io.File

/**
 * Exports the frames of a Figma page to svg and presents them one at a time.
 * Left and right arrows browse.
 *
 * Fill in FIGMA_TOKEN, FIGMA_FILE_KEY and FIGMA_PAGE in .env, then:
 *     ./gradlew run -Popenrndr.application=FigmaKt
 *
 * Frames are exported once and cached in data/figma, so later runs need no token and
 * no network. Set FIGMA_REFRESH=true in .env to pull the page again.
 *
 * This file sits in the root package on purpose: figma-rest exposes okFetcher and
 * okFileDownloader in the root package, and Kotlin cannot import from there.
 */
fun main() = application {
    // provisional; the window is resized to the frame as soon as one is loaded
    configure { width = 1280; height = 800 }

    program {
        // fetched here rather than before application {}: on macOS OPENRNDR restarts
        // the jvm, so anything above it would run twice
        val frames = fetchFigmaFrames()
        println("${frames.size} frames: ${frames.joinToString { it.name }}")

        // The frame is always rendered at its own resolution into a render target;
        // this only sets how large that target is presented on screen. 0.5 shows a
        // 3840x1080 frame in a 1920x540 window.
        val presentScale = Env["FIGMA_WINDOW_SCALE"]?.toDoubleOrNull() ?: 0.5

        // OPENRNDR fills shapes about a pixel wider on each side than Figma does, which
        // makes type read bolder. The error is a fixed size in render-target pixels, so
        // rendering larger and downsampling shrinks it in proportion. Measured against
        // Figma's own render of the same frame: 1x +5.6%, 2x +2.8%, 3x +1.9%, 4x +1.4%
        // too much ink. MSAA does not help — the geometry itself is wider, not the edge
        // blend. 4 is the ceiling here: 5x would exceed the 16384px max texture size.
        val supersample = Env["FIGMA_SUPERSAMPLE"]?.toIntOrNull()?.coerceIn(1, 4) ?: 4

        val font = loadFont("data/fonts/default.otf", 16.0)

        // svgs are parsed on first view rather than all up front
        val loaded = mutableMapOf<Int, Composition>()
        fun compositionAt(index: Int) = loaded.getOrPut(index) { loadSVG(frames[index].svg) }

        var index = 0
        var renderedIndex = -1
        var target: RenderTarget? = null

        keyboard.keyDown.listen { event ->
            when (event.key) {
                KEY_ARROW_RIGHT -> index = (index + 1) % frames.size
                KEY_ARROW_LEFT -> index = (index - 1 + frames.size) % frames.size
            }
        }

        extend {
            val composition = compositionAt(index)
            val size = composition.bounds.dimensions

            // The frame is static, so it is only rendered when the selection changes.
            if (renderedIndex != index) {
                val rt = targetFor(target, size * supersample.toDouble())
                if (rt !== target) target = rt

                drawer.isolatedWithTarget(rt) {
                    drawer.ortho(rt)
                    drawer.clear(ColorRGBa.WHITE)
                    drawer.scale(supersample.toDouble())
                    drawer.composition(composition)
                }

                // mipmaps so the downsample to window size is filtered rather than
                // point sampled, which would alias the thin parts of the letterforms
                rt.colorBuffer(0).apply {
                    generateMipmaps()
                    filterMin = MinifyingFilter.LINEAR_MIPMAP_LINEAR
                    filterMag = MagnifyingFilter.LINEAR
                }

                window.size = size * presentScale
                renderedIndex = index
                println(
                    "frame '${frames[index].name}' ${size.x.toInt()}x${size.y.toInt()}, " +
                            "rendered at ${rt.width}x${rt.height} (${supersample}x), " +
                            "presented at ${(presentScale * 100).toInt()}%"
                )
            }

            val rt = target ?: return@extend
            val image = rt.colorBuffer(0)

            drawer.clear(ColorRGBa.WHITE)
            drawer.image(image, image.bounds, drawer.bounds)

            drawer.fontMap = font
            drawer.fill = ColorRGBa.BLACK
            drawer.text("${index + 1}/${frames.size}   ${frames[index].name}   ←  →", 24.0, height - 18.0)
        }
    }
}

/**
 * A render target the size of [size], reusing [existing] when it already matches.
 * Shape drawing needs a stencil attachment, hence the depth format.
 */
private fun targetFor(existing: RenderTarget?, size: Vector2): RenderTarget {
    val width = size.x.toInt()
    val height = size.y.toInt()
    if (existing != null && existing.width == width && existing.height == height) return existing

    existing?.apply {
        colorBuffer(0).destroy()
        depthBuffer?.destroy()
        detachColorAttachments()
        detachDepthBuffer()
        destroy()
    }

    return renderTarget(width, height) {
        colorBuffer()
        depthBuffer(DepthFormat.DEPTH24_STENCIL8)
    }
}

// ------------------------------------------------------------------------------ //

/** Exported frames and their manifest are cached here. */
val figmaCacheDir = File("data/figma")

private val manifestFile = File(figmaCacheDir, "frames.json")
private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

/** One Figma frame, exported to a local svg file. */
@Serializable
data class FigmaFrame(val name: String, val id: String, val path: String) {
    val svg: File get() = File(path)
}

/**
 * Every top-level frame on [pageName], in the order Figma lists them, each exported
 * as svg into data/figma.
 *
 * Results are cached in a manifest, so later runs need neither a token nor a network
 * connection. Set FIGMA_REFRESH=true in .env to pull the page again after editing it.
 */
fun fetchFigmaFrames(
    fileRef: String = Env.require("FIGMA_FILE_KEY"),
    pageName: String? = Env["FIGMA_PAGE"],
    token: String = Env.require("FIGMA_TOKEN"),
    refresh: Boolean = Env.boolean("FIGMA_REFRESH")
): List<FigmaFrame> {
    if (!refresh) cachedFrames()?.let { return it }

    figmaCacheDir.mkdirs()
    val fileKey = figmaFileKeyFrom(fileRef)
    val api = FigmaApi(okFetcher(token))

    val document = api.file(fileKey).document
    val pages = (document as ParentNode).children.filterIsInstance<Node.Canvas>()

    val page = if (pageName.isNullOrBlank()) {
        pages.firstOrNull() ?: error("file $fileKey has no pages")
    } else {
        pages.find { it.name == pageName }
            ?: error("no page named '$pageName'. Pages in this file: " +
                    pages.joinToString { "'${it.name}'" })
    }

    val frames = page.frames().inReadingOrder()
    check(frames.isNotEmpty()) {
        "page '${page.name}' has no frames. Top-level nodes there: " +
                page.children.joinToString { "${it::class.simpleName} '${it.name}'" }
    }

    // Figma takes many ids per call; batch so the query string stays sane.
    val urls = frames.map { it.id }.chunked(20).flatMap { batch ->
        val images = api.images(fileKey, batch, format = "svg", svgOutlineText = true)
        images.err?.let { error("Figma refused the svg export: $it") }
        batch.map { it to images.images[it] }
    }.toMap()

    val download = okFileDownloader(token)

    val exported = frames.mapIndexed { index, frame ->
        val target = File(figmaCacheDir, "%02d-%s.svg".format(index, frame.name.sanitizedForFilename()))
        val url = urls[frame.id] ?: error("Figma returned no svg url for frame '${frame.name}'")
        if (refresh || !target.isFile) {
            download(url, target)
            target.writeText(sanitizedSvg(target.readText()))
        }
        FigmaFrame(frame.name, frame.id, target.path)
    }

    manifestFile.writeText(json.encodeToString(ListSerializer(FigmaFrame.serializer()), exported))
    return exported
}

/**
 * Top-level frames on a page. Frames sitting inside a section count too, since a
 * section is Figma's way of grouping frames rather than a frame itself.
 */
private fun Node.Canvas.frames(): List<Node> = children.flatMap { child ->
    when (child) {
        is Node.Frame -> listOf(child)
        is Node.Section -> child.children.filterIsInstance<Node.Frame>()
        else -> emptyList()
    }
}

/** The previously exported frames, or null when the cache is missing or incomplete. */
private fun cachedFrames(): List<FigmaFrame>? {
    if (!manifestFile.isFile) return null
    val frames = runCatching {
        json.decodeFromString(ListSerializer(FigmaFrame.serializer()), manifestFile.readText())
    }.getOrNull() ?: return null
    return frames.takeIf { it.isNotEmpty() && it.all { frame -> frame.svg.isFile } }
}

/**
 * Accepts either a bare file key or a full Figma url and returns the key.
 *   https://www.figma.com/design/<key>/<name>?node-id=...  ->  <key>
 */
fun figmaFileKeyFrom(fileRef: String): String =
    Regex("figma\\.com/(?:file|design|proto)/([A-Za-z0-9]+)").find(fileRef)?.groupValues?.get(1)
        ?: fileRef.trim()

private fun String.sanitizedForFilename() = replace(Regex("[^A-Za-z0-9._-]+"), "_")

/**
 * Removes the two things Figma puts in every frame export that OPENRNDR draws as content.
 *
 * Figma closes each file with a full-bleed clip rect:
 *
 *     <defs><clipPath id="clip0_687_39"><rect width="3908" height="1080" fill="white"/></clipPath></defs>
 *
 * orx-svg does not implement clipPath, so rather than defining a clip it draws that rect —
 * white, the size of the artboard, and last in the document, so it paints over the whole
 * slide. Measured over the SHEETS page: 43 of the 47 frames carrying a clipPath rendered as
 * one flat white rectangle, and every frame without one rendered correctly. Decision.kt
 * works around the same rect by picking the artboard as the largest closed shape; here the
 * frames are the artwork, so it is cut at the source instead.
 *
 * The groups that referenced the clip keep their clip-path attribute, which orx-svg ignores
 * anyway. Nothing is lost by dropping the definition: Figma has already clipped the geometry
 * to the frame, so every exported viewBox is exactly the frame's own size.
 *
 * The second is the frame border Figma draws half a pixel inside the artboard —
 * a stroke-only rect that would put a light grey hairline around every slide.
 *
 * Only clipPath elements are removed, not the whole defs block: four frames on this page
 * carry a mask, and those still resolve.
 */
internal fun sanitizedSvg(svg: String): String = svg
    .replace(Regex("""<clipPath\b[\s\S]*?</clipPath>"""), "")
    .replace(Regex("""<defs>\s*</defs>"""), "")
    .replace(Regex("""<rect x="0\.5" y="0\.5"(?![^>]*\bfill=)[^>]*stroke="[^"]*"[^>]*/>"""), "")

/**
 * The frames in the order they are read off the board — down it in rows, left to right
 * within a row — rather than in the order Figma lists its children, which is z-order and
 * bears no relation to the deck. On the SHEETS page Figma's own order runs from 4-02 to
 * chapter-4.1, so the arrow keys walk the presentation roughly backwards.
 *
 * Sorting by position also puts each divider card at the head of its own row, which is
 * where it belongs in the deck and is not derivable from the names: chapter-2.2 falls
 * between 2-08 and 2-09, and chapter-4.1 between 4-10 and 4-11.
 *
 * Rows are grouped with a tolerance of half a frame height, which is safe in both
 * directions here — everything on one row of the board shares a y exactly, and the next
 * row down starts more than a frame away. Figma's order is kept if any frame arrives
 * without a bounding box, since then there is nothing to sort on.
 */
private fun List<Node>.inReadingOrder(): List<Node> {
    val boxed = map { node -> node to (node.absoluteBoundingBox ?: return this) }
    val tolerance = boxed.minOf { (_, box) -> box.height } / 2.0

    val rows = mutableListOf<MutableList<Pair<Node, org.openrndr.figma.rest.Rectangle>>>()
    for (entry in boxed.sortedWith(compareBy({ it.second.y }, { it.second.x }))) {
        val row = rows.lastOrNull()
        if (row != null && entry.second.y - row.first().second.y <= tolerance) row += entry
        else rows += mutableListOf(entry)
    }
    return rows.flatMap { row -> row.sortedBy { it.second.x }.map { it.first } }
}
