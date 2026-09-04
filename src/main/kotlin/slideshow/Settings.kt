package slideshow

import org.openrndr.color.ColorRGBa

/**
 * Everything about a show that is not a slide.
 *
 * It is a plain value with sensible defaults so the engine has no idea `.env` exists —
 * `Slides.kt` fills one in from `SLIDES_*` and hands it over. That keeps the deck
 * runnable from a test or a second launcher without a config file anywhere near it.
 */
data class Settings(
    /** The canvas everything is composed at. Slides lay out against this, never the window. */
    val width: Int = 1920,
    val height: Int = 1080,
    /** How much of the screen the window takes. The canvas is unaffected. */
    val windowScale: Double = 0.6,
    val fullscreen: Boolean = false,
    val title: String = "slideshow",
    /** Slide to open on: a number from 1, or a slide's name. Empty starts at the first. */
    val start: String? = null,
    /** Open with the debug overlay up. It can be toggled with `d` either way. */
    val debug: Boolean = false,
    /** Seconds between automatic clicks, for recording a run hands-off. 0 leaves it to the keys. */
    val autoStep: Double = 0.0,
    val record: Boolean = false,
    val fps: Int = FPS,
    val duration: Double? = null,
    /** Write one png per click of every slide to screenshots/ and quit. */
    val stills: Boolean = false,

    /**
     * Width of the left pane, the one carrying the chapter. Null is a single-pane show,
     * where the slide has the whole canvas.
     */
    val panelWidth: Int? = null,

    /**
     * The gutter between the two panes, in canvas pixels. 0 stands them flush, which is
     * what the committed show does: 3840 = 1920 + 1920, and the two grounds meeting is
     * what divides the frame rather than a bar drawn between them.
     */
    val panelGap: Int = 0,

    /** What shows in the gutter, and behind a pane that does not fill the canvas. */
    val gutter: ColorRGBa = ColorRGBa.BLACK
)
