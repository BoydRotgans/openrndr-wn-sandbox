package slideshow

/**
 * The show, declared: chapters, subchapters, the slides under them, and the shape of the
 * frame they are shown in.
 *
 *     val show = slideshow {
 *         canvas(3840, 1080)
 *         window(0.5)
 *
 *         chapter("Introduction") {
 *             subchapter("Opening") {
 *                 slide(TitleSlide())
 *                 slide(RevealSlide(), title = "Circle, then square", notes = "...")
 *             }
 *         }
 *     }
 *
 * This started as a json file and is Kotlin instead, which is better here for one reason
 * that matters: **`slide(TitleSlide())` is the constructor, not a name to look up.** A json
 * deck can only carry the *name* of a slide, so it needs a registry mapping names to
 * constructors — a second list to keep in step, and a typo that only surfaces when the file
 * is read. Written in Kotlin the compiler resolves it, the IDE completes it, renaming the
 * class updates the show, and a slide that does not exist will not build. Chapters and
 * titles read the same either way, so the json bought nothing and cost the registry.
 *
 * A slide is an ordinary object here, so anything Kotlin can do the running order can do:
 * a slide constructed with arguments, the same slide twice under two titles, a list built
 * in a loop.
 */
fun slideshow(build: ShowBuilder.() -> Unit): Show = ShowBuilder().apply(build).build()

/**
 * A whole show: the slides in order, where each one sits, and how it is presented.
 *
 * [panels] is the second track — the chapter cards standing in the left pane, one per
 * subchapter, and [panelOf] says which of them belongs beside each slide. Two tracks
 * rather than one composite drawing, because they change on different things: the slide
 * changes on a click, the panel only when the chapter or subchapter does.
 */
data class Show(
    val slides: List<Slide>,
    val outline: Outline,
    val settings: Settings,
    val panels: List<Slide> = emptyList(),
    val panelOf: List<Int> = emptyList()
)

/** The chapter and subchapter a panel stands for. */
data class Section(val number: String, val chapter: String, val subchapter: String)

/** Builds the card that stands beside the slides of one section. */
typealias PanelDrawer = (Section) -> Slide

/** Where one slide sits in the show, for the debug view to report. */
data class Placement(
    val chapter: Int,
    val chapterTitle: String,
    val subchapter: Int,
    val subchapterTitle: String,
    /** What to call this appearance, when the class name is not what you want to read. */
    val title: String?,
    /** Anything worth remembering about this slide. */
    val notes: String?
) {
    /** "1.2" — the number this slide's subchapter has in the running order. */
    val number: String get() = "$chapter.$subchapter"

    val path: String get() = listOf(chapterTitle, subchapterTitle).filter { it.isNotBlank() }.joinToString(" / ")

    /** What a panel standing beside this slide is drawing. */
    fun section() = Section(
        number = if (subchapter > 0) number else "$chapter",
        chapter = chapterTitle,
        subchapter = subchapterTitle
    )
}

/** The structure of the show, by slide index. */
class Outline(private val places: List<Placement> = emptyList()) {
    operator fun get(index: Int): Placement? = places.getOrNull(index)

    companion object {
        val EMPTY = Outline()
    }
}

@DslMarker
annotation class ShowDsl

@ShowDsl
class ShowBuilder internal constructor() {

    private var settings = Settings()
    private val slides = mutableListOf<Slide>()
    private val places = mutableListOf<Placement>()
    private var chapters = 0

    private var panelDrawer: PanelDrawer? = null
    private val panels = mutableListOf<Slide>()
    private val panelOf = mutableListOf<Int>()
    private var section: Pair<Int, Int>? = null

    // --- how the show is presented. .env overrides any of these for a single run. --- //

    /** The canvas everything is composed at. Slides lay out against this, not the window. */
    fun canvas(width: Int, height: Int) {
        settings = settings.copy(width = width, height = height)
    }

    /** How much of the screen the window takes. The canvas is unaffected. */
    fun window(scale: Double) {
        settings = settings.copy(windowScale = scale)
    }

    fun fullscreen(on: Boolean = true) {
        settings = settings.copy(fullscreen = on)
    }

    fun title(text: String) {
        settings = settings.copy(title = text)
    }

    /** Open on this slide: a number counting from 1, or a slide's name. */
    fun startAt(slide: String) {
        settings = settings.copy(start = slide)
    }

    /** Open with the debug view up; `d` toggles it either way. */
    fun debug(on: Boolean = true) {
        settings = settings.copy(debug = on)
    }

    /**
     * Splits the frame in two: [build] draws the left pane, carrying the chapter and
     * subchapter, and the slides get what is left over on the right.
     *
     * The card is built once per section and stands for as long as the show is in it, so
     * it holds still while the slides beside it are clicked through and changes only when
     * the chapter or subchapter does — with a handover of its own, which is why it is an
     * ordinary [Slide] rather than something drawn into the corner of one.
     *
     * On the committed 3840x1080 canvas, `panel(::ChapterPanel)` gives two 1920x1080
     * frames side by side with nothing between them. [gap] opens a gutter, in canvas
     * pixels, if the two should be held apart instead — the canvas has to carry it:
     * `canvas(3908, 1080)` with `gap = 68`, or the right pane loses the difference.
     */
    fun panel(build: PanelDrawer, width: Int = 1920, gap: Int = 0) {
        panelDrawer = build
        settings = settings.copy(panelWidth = width, panelGap = gap)
    }

    /** What shows in the gutter between the panes. */
    fun gutter(colour: org.openrndr.color.ColorRGBa) {
        settings = settings.copy(gutter = colour)
    }

    // --- the running order ---------------------------------------------------------- //

    fun chapter(title: String, panel: PanelDrawer? = null, build: ChapterBuilder.() -> Unit) {
        chapters++
        ChapterBuilder(this, chapters, title, panel).apply(build)
    }

    /** A slide with no chapter over it, for a show too short to need the structure. */
    fun slide(slide: Slide, title: String? = null, notes: String? = null) =
        add(slide, Placement(0, "", 0, "", title, notes))

    internal fun add(slide: Slide, place: Placement, panel: PanelDrawer? = null) {
        slides += slide
        places += place

        // A new card whenever the section changes, and the same one for every slide
        // inside it — which is what keeps the left pane still while the right one moves.
        val here = place.chapter to place.subchapter
        if (here != section) {
            section = here
            (panel ?: panelDrawer)?.let { panels += it(place.section()) }
        }
        if (panels.isNotEmpty()) panelOf += panels.size - 1
    }

    internal fun build(): Show {
        require(slides.isNotEmpty()) { "a show needs at least one slide" }
        require(panelOf.isEmpty() || panelOf.size == slides.size) {
            "a chapter sets its own panel but the show has none. Call panel(::YourPanel) " +
                    "in the show, then override it on the chapters that need another card."
        }
        return Show(slides.toList(), Outline(places.toList()), settings, panels.toList(), panelOf.toList())
    }
}

@ShowDsl
class ChapterBuilder internal constructor(
    private val show: ShowBuilder,
    private val index: Int,
    private val chapterTitle: String,
    private val panel: PanelDrawer?
) {
    private var subchapters = 0

    fun subchapter(title: String, build: SubchapterBuilder.() -> Unit) {
        subchapters++
        SubchapterBuilder(show, index, chapterTitle, subchapters, title, panel).apply(build)
    }

    /** A slide directly under the chapter, with no subchapter. */
    fun slide(slide: Slide, title: String? = null, notes: String? = null) =
        show.add(slide, Placement(index, chapterTitle, 0, "", title, notes), panel)
}

@ShowDsl
class SubchapterBuilder internal constructor(
    private val show: ShowBuilder,
    private val chapter: Int,
    private val chapterTitle: String,
    private val index: Int,
    private val subchapterTitle: String,
    private val panel: PanelDrawer?
) {
    fun slide(slide: Slide, title: String? = null, notes: String? = null) =
        show.add(slide, Placement(chapter, chapterTitle, index, subchapterTitle, title, notes), panel)
}

// ------------------------------------------------------------------------------ //

/**
 * The running order, as a tree, for reading back.
 *
 * The show is *declared* as a hierarchy and *played* as a flat list, and both are true at
 * once: chapters and subchapters are what the file says and what the debug view reports,
 * while the deck steps through slides in the order they were declared, one after another,
 * with no boundary a click can feel. This prints the first of those, which is otherwise
 * only visible a slide at a time.
 */
fun Show.runningOrder(): String = buildString {
    var chapter = Int.MIN_VALUE
    var subchapter = Int.MIN_VALUE

    slides.forEachIndexed { index, slide ->
        val place = outline[index]

        if (place != null && place.chapter != chapter) {
            chapter = place.chapter
            subchapter = Int.MIN_VALUE
            if (place.chapterTitle.isNotBlank()) appendLine("\n${place.chapter}  ${place.chapterTitle}")
        }
        if (place != null && place.subchapter != subchapter) {
            subchapter = place.subchapter
            if (place.subchapterTitle.isNotBlank()) appendLine("   ${place.number}  ${place.subchapterTitle}")
        }

        appendLine(
            "      %2d  %-20s %s".format(
                index + 1,
                place?.title ?: slide.name,
                buildList {
                    add(if (slide.steps == 1) "1 click" else "${slide.steps} clicks")
                    add("%.2fs".format(seconds(slide.stepFrames)))
                    if (slide.loop > 0) add("loop %.1fs".format(seconds(slide.loop)))
                    add(slide.transition::class.simpleName?.lowercase() ?: "")
                }.joinToString("  ")
            )
        )
    }
}
