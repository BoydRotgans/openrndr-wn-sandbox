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
    /**
     * The last slide a hands-off run plays, inclusive — a number from 1 or a slide's name, the
     * same forms [start] takes. Empty runs to the end of the deck, which is what it always did.
     *
     * **It bounds the cue list, not the deck.** A written run ends where its holds run out, so
     * with `SLIDES_CUES=auto` this is how a chapter is filmed on its own: the deck still holds
     * every slide and the arrows still reach them, but the run stops clicking at this one and a
     * filmed run ends there. Anything else would be a second notion of what the deck contains,
     * and the show is one running order.
     */
    val until: String? = null,
    /** Open with the debug overlay up. It can be toggled with `d` either way. */
    val debug: Boolean = false,
    /**
     * Name every frame with its slide and click — `panels-turning-A` — in a box on the wall.
     *
     * Unlike the debug overlay and the grid this is drawn **into the canvas**, so it lands in a
     * still, a preview and a filmed run. That is what it is for: a review copy that says which
     * slide is which. Off for anything that will be projected.
     */
    val nameplate: Boolean = false,
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
    /** Start muted: the cues still run, and the organizer's `sound` button brings them back. See [Speakers.muted]. */
    val muted: Boolean = false,

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
    val gutter: ColorRGBa = ColorRGBa.BLACK,

    /**
     * Serve the organizer — the web page that arranges the running order and steers this
     * window — at `http://localhost:[organizerPort]/`. See [Remote].
     */
    val organizer: Boolean = false,
    val organizerPort: Int = 8765,
    /** Open the organizer in a browser as it comes up. Off when the launcher starts the show, whose page is already open. */
    val organizerOpen: Boolean = true,

    /**
     * The order file: which slides play, in what order, under which chapters. Read at launch
     * where it exists and written by the organizer. Null, or no file there, plays the show as
     * `Slideshow.kt` declares it. See [Order].
     */
    val order: String? = null,

    /**
     * The modules file: the slides still to be built, each a title, the frames of the
     * client's deck it stands for and a brief — see [Modules]. Every one plays as a pink
     * placeholder until a drawer replaces it. Null, or no file there, adds none.
     */
    val modules: String? = null,

    /**
     * The intents file: the intended update per slide, shown in the organizer beside the
     * speaker notes and edited there. It carries no behaviour and the deck never reads it —
     * see [Intents]. Null, or no file there, shows none.
     */
    val intents: String? = null,

    /**
     * The subtitles file: what is said over each state of each slide, in Dutch — see [Subtitles].
     * Edited in the organizer; only drawn in [subtitleMode]. Null, or no file there, has none.
     */
    val subtitles: String? = null,
    /**
     * The extended subtitles file: the whole presentation as it would be given, a line for every
     * state — see [SubtitleTrack]. Null, or no file there, has none.
     */
    val subtitlesExtended: String? = null,
    /**
     * Which file is read: `default` or `extended`. The organizer's `voice-over` selector switches
     * it while the show runs. On the extended track, with subtitles on, the deck advances itself
     * once each state's line has been said.
     */
    val subtitleTrack: SubtitleTrack = SubtitleTrack.DEFAULT,
    /**
     * Subtitle mode: the line said over the state on the wall, a card at a time at a speaker's
     * pace, drawn into the canvas so a filmed run carries it — and a hands-off run
     * (`SLIDES_CUES=auto`) holds every state until its last card has been read. The organizer's
     * `subtitles` button and `s` in the show switch it while it runs.
     */
    val subtitleMode: Boolean = false,
    /** The pace, in characters a second. See [Pace]. */
    val subtitleCps: Double = Pace.DEFAULT_CPS,
    /**
     * The voice: the folder holding a subtitle track rendered as speech, `<track>/<id>-<LETTER>.wav`
     * — see [VoiceTrack]. Played under the cards in subtitle mode, and where a state has one the
     * state is held for the voice's length rather than the text's estimate. Null has none.
     */
    val voice: String? = null,
    /** The voice's gain, 0..1, onto the source. */
    val voiceGain: Double = 1.0,
    /**
     * Whether the voice is spoken. Independent of [subtitleMode]: the talk can be heard with or
     * without its words on the wall, and on the extended track either one makes the deck present
     * itself. The organizer's `voice` button and `v` in the show switch it.
     */
    val voiceOn: Boolean = false,
    /**
     * The mix: a gain per track of the sound — voice, design, music — see [Layer]. All three play
     * at once; this is how loud each is, and 0 mutes one. The organizer's `mix` panel moves them
     * live, and a filmed run's soundtrack is made at the mix it was played at.
     */
    val levels: Map<Layer, Double> = mapOf(Layer.VOICE to 0.3, Layer.DESIGN to 1.0, Layer.MUSIC to 1.0),
    /**
     * Trace the voice: print each line as it is asked for and, a fifth of a second later, whether
     * OpenAL is really playing it — how a voice that "sometimes does not play" is caught.
     */
    val soundTrace: Boolean = false,

    /**
     * The feedback file: notes written against a slide in the organizer while it is watched,
     * each ticked off once acted on — see [Feedback]. It carries no behaviour and the deck
     * never reads it. Null, or no file there, starts an empty one on the first note.
     */
    val feedback: String? = null,

    /**
     * The file saying which slides are wanted as MIDI — ticked in the organizer, written as
     * `midi/<slide-id>.mid` whenever it is saved. See [MidiWanted]. Only a slide that is
     * [MidiTimed] can be ticked; null, or no file there, wants none.
     */
    val midi: String? = null,

    /**
     * Where the reference frames are: the client's deck cut into pictures by
     * `tools/reference_frames.py`, one a frame, with `frames.json` beside them. A
     * placeholder shows the frames it stands for, and the organizer shows every frame
     * beside the slide that covers it.
     */
    val references: String? = null,

    /**
     * The bed under the talk: a loop that plays while the show is on a slide inside a chapter
     * and fades out while a backdrop or a whole-wall scene is up, which carry sound of their own.
     * Null plays nothing under the slides. See [ShowBuilder.slideBed].
     */
    val slideBed: Sound? = null,

    /**
     * A sound design delivered as a folder named against the show — one wav per state of one
     * slide, placed by the file's own name rather than by anything stated here. Null is a show
     * whose cues are all declared in `Slideshow.kt`. See [CueSheet], and [ShowBuilder.cueSheet].
     *
     * Named apart from [cues], which is the list of *holds* a hands-off run steps on — a
     * different thing entirely, and the reason this one says sheet.
     */
    val cueSheet: CueSheet? = null,

    /**
     * A concrete texture laid over the whole finished frame, as if the show were projected onto
     * a concrete wall — a preview of the room, not part of any slide. Null has none to offer;
     * [concreteOn] is whether it starts on, and the organizer switches it while the show runs.
     */
    val concrete: String? = null,
    val concreteOn: Boolean = false,
    /** How strongly the stone's grain darkens the picture, from its brightest tones: 1 is the photo as it is, above 1 exaggerates it. */
    val concreteMix: Double = 2.5,
    /** How big one tile of the texture is drawn, in canvas pixels against its own. */
    val concreteScale: Double = 1.0,
    /**
     * The darkest the wall goes, before the grain, in linear light: black is lifted to this grey
     * and then grained like everything else, so every slide paints black and the wall is one grey
     * concrete behind all of them. 0.045 is `3C3C3C`, the grey the quote and card used to paint
     * themselves. 0 is the plain multiply, where black stays black.
     */
    val concreteFloor: Double = 0.0225
)
