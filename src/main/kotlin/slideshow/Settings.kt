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
    /**
     * Drop the title bar and let the window sit exactly where it is put.
     *
     * This, rather than [fullscreen], is what puts the show on a wall of projectors:
     * fullscreen takes **one** display, so a 3840x1080 canvas spanning two of them has to be
     * an undecorated window placed across both. Decorations would also shift the canvas down
     * by the bar's height and leave it there.
     */
    val undecorated: Boolean = false,
    /**
     * Where the window's top-left corner goes, in screen points — not pixels, so on a Retina
     * laptop the offset to a display beside it is that laptop's *logical* width and not its
     * native one. Null leaves the placement to the window manager.
     */
    val windowX: Int? = null,
    val windowY: Int? = null,
    val title: String = "slideshow",
    /** Slide to open on: a number from 1, or a slide's name. Empty starts at the first. */
    val start: String? = null,
    /** Open with the debug overlay up. It can be toggled with `d` either way. */
    val debug: Boolean = false,
    /** Seconds between automatic clicks, for recording a run hands-off. 0 leaves it to the keys. */
    val autoStep: Double = 0.0,

    /**
     * Seconds to hold at each stop before clicking on — one number per click, in order.
     *
     * [autoStep]'s single interval cannot film this deck: a click of the city takes twelve
     * seconds and a click of the stack takes half of one, so any interval that lets the first
     * finish holds the second for twenty times longer than it needs. A cue list is the same
     * hands-off run with the pauses written out. It wins over [autoStep] when set, and when it
     * runs out the deck simply stops where it is.
     */
    val cues: List<Double> = emptyList(),
    /**
     * Write the cue list off the deck instead: every state held for as long as it takes to
     * finish moving plus a reading time — [hold] for a slide, [holdWide] for a wall of one
     * state — and a filmed run ends after the last. The list is printed at startup, to be
     * copied into [cues] and tuned by hand.
     */
    val cuesAuto: Boolean = false,
    val hold: Double = 3.5,
    val holdWide: Double = 12.0,
    val record: Boolean = false,
    val fps: Int = FPS,
    val duration: Double? = null,
    /**
     * Where a filmed run goes. The soundtrack is rendered beside it, in line with the picture
     * frame for frame, and with [mix] on and ffmpeg on the path the two are mixed into one
     * file beside those — see [Soundtrack].
     */
    val video: String = "video/presentation.mp4",
    val mix: Boolean = true,
    /** Write one png per click of every slide to screenshots/ and quit. */
    val stills: Boolean = false,

    /**
     * Whether the deck makes any sound at all — the cues a slide or a chapter card declares.
     *
     * On by default and turned off for a rehearsal, or on a machine where the audio would go
     * somewhere it should not. It is a *load* switch as well as a mute: off, no device is
     * opened and nothing is decoded. Stills are silent whatever this says — that run jumps
     * through every slide in the deck on a timer and would fire the whole cue sheet at it.
     */
    val sound: Boolean = true,

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
