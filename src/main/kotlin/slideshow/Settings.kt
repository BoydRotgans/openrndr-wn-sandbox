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
     * Slide to open on by its id, which wins over [start]: what the organizer hands a show started on the
     * slide selected in it, since an id is the show's own name for a slide however the order was arranged
     * since the organizer read it, where a number counts in an order that may be stale.
     */
    val startId: String? = null,
    /** The state to open [startId] or [start] on, 0 being the state a slide arrives on. */
    val startStep: Int = 0,
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
    /**
     * The least a **backdrop** state may stand on a hands-off run, whatever the rule above works
     * out — the walls around the talk are one picture each and read as a glimpse if they are given
     * only a click's length plus a reading time. It is a floor rather than a hold, so a backdrop
     * with a long line spoken over it still stands for as long as the voice takes.
     *
     * It is not [holdWide], which serves a wall of a single state; a backdrop that builds (the
     * programme, at six states) would otherwise take the ordinary [hold] on every one of them.
     */
    val holdBackdrop: Double = 15.0,
    /**
     * Seconds every backdrop takes to come up through [wallBuild] as it arrives, the same for all of them
     * so each wall of the evening takes the stage slowly and alike; 0 puts every backdrop straight up. A
     * hands-off run holds a building backdrop at least this long before it reads the wall.
     */
    val backdropBuild: Double = 0.0,
    /** How a backdrop comes up — see [WallBuild]. None, and every backdrop is simply there. */
    val wallBuild: WallBuild? = null,
    /** Seconds the clicker's screen button is held before the show goes back to its start — see [HoldRestart]. */
    val holdRestart: Double = 3.0,
    /** Seconds within which a second press of the clicker's play button opens the overview — see [SlideOverview]. */
    val overviewDouble: Double = 0.6,
    /**
     * Practice mode: whether the clicker's screen and play buttons do anything — the restart ring and the
     * overview. Off, they are dead, so a wrong press in front of the room changes nothing; forward and back
     * work either way. The organizer's `practice mode` button switches it on a running show.
     */
    val practice: Boolean = false,
    /**
     * Wait for the animation: a forward click is refused until the state on the wall has finished moving —
     * its handover, its click, or its own opening by the rule a hands-off run holds it by — and while it
     * moves, the show's countdown line bottom left comes down with how much is left. Back always goes
     * through. The organizer's `wait for animation` button switches it on a running show. See [Countdown].
     */
    val waitForAnimation: Boolean = false,
    /**
     * The autocue: the speaker's screen, what is to be said over the state on the wall, scrolling as it is
     * said and offering the click once it has been — see [Autocue]. On or off; where it goes, on top of the
     * show's window or a window of its own on a chosen screen, is the Projection tab's ([AutocueSetup]).
     * The organizer's `autocue` button and `c` in the show switch it; never on a film, a still or a bench.
     * With [autoplay] off the presenter clicks and nothing clicks on by itself, a cue list included.
     */
    val autocue: Boolean = false,
    /** The autocue's type size, in pixels of the 1920 by 1080 it is composed at. */
    val autocueText: Double = Autocue.TEXT,
    /**
     * The pace, in characters a second, the autocue scrolls a line at where no voice is rendered for it — and
     * offers the click at, counts SPREKEN down by and, under autoplay, clicks on at. A little slower than the
     * subtitles' [subtitleCps] (13 against 15, 19 October 2026), so it keeps step with a speaker reading aloud
     * to a room rather than running ahead of him. The subtitles, the voice and a filmed run keep their own pace.
     */
    val autocueCps: Double = 13.0,
    /**
     * The face the autocue's script is read in, a font file; null takes the talk's own. Arial Bold as committed
     * (15 October 2026), the teleprompter recommendation: an even sans at a heavy weight reads from further off
     * and at a glance better than the talk's slab serif. The labels round it stay in the talk's face.
     */
    val autocueFont: String? = null,
    /**
     * A filmed run also films the autocue, into this file beside the wall's film (15 October 2026): the presenter's
     * autocue — nothing counting down, KLIK once a line is said and the wall still — on the very frames of the wall,
     * so the rehearsal page can play the two together. [autocueFilmWidth] wide, at [autocueFilmCrf]. Null films none.
     */
    val autocueFilm: String? = null,
    val autocueFilmWidth: Int = 1280,
    val autocueFilmCrf: Int = 28,
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
    /**
     * What films a run: `pipelined`, [WallRecorder], which reads each frame back while the next is
     * drawn, or `screen`, OPENRNDR's own `ScreenRecorder`, which waits for every frame. The two
     * write the same frames; the second is kept to check that against.
     */
    val recorder: String = "pipelined",
    /**
     * The x264 quality the pipelined recorder films at, lower being better; null leaves ffmpeg's own
     * default (crf 23), which is what every film before it was made at. A clip cut for elsewhere —
     * cropped and encoded again — wants a low number here so the second encode has the grain to keep.
     */
    val recordCrf: Int? = null,
    /** Write one png per click of every slide to screenshots/ and quit. */
    val stills: Boolean = false,
    /** Seconds each still waits after its cut before it is taken; null for the engine's own 95 frames. */
    val stillHold: Double? = null,
    /**
     * Time every state of the running order instead of showing it, and quit: each state is drawn
     * [benchSamples] times across its hold, the GPU finished after every draw, and the cost is
     * printed per slide against how long a filmed run spends on it. See `bench` in Show.
     */
    val bench: Boolean = false,
    val benchSamples: Int = 12,
    /**
     * Whether OPENRNDR finishes the GPU after every outline of a shape it fills — its workaround for
     * an artifact under ANGLE on a Mac (`DrawerConfiguration.waitForFinish`), on by default there. The
     * show runs native GL, not ANGLE, and a filled letter with a counter in it then stalls the frame
     * for nothing. Null is off — in a watched show as on a filmed run, which was checked byte-identical.
     */
    val waitForFinish: Boolean? = null,
    /**
     * Whether OPENRNDR's own `glFinish` on every render-target switch is kept. Off by default: it
     * makes the CPU wait for the GPU at every `isolatedWithTarget`, which on native GL buys nothing.
     * See [UnbindFinish].
     */
    val finishOnUnbind: Boolean = false,

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
     * The aligned subtitles file: the extended track rewritten to what is on the wall at each
     * state — see [SubtitleTrack]. Null, or no file there, has none.
     */
    val subtitlesAligned: String? = null,
    /**
     * Erik's autocue: his script for the evening, a line a state, with the stage directions the
     * autocue draws beside it — see [SubtitleTrack]. Null, or no file there, has none.
     */
    val subtitlesErik: String? = null,
    /**
     * Which file is read: `default`, `extended`, `aligned` or `erik`. The organizer's `voice-over`
     * selector switches it while the show runs.
     */
    val subtitleTrack: SubtitleTrack = SubtitleTrack.DEFAULT,
    /**
     * Whose lines are on the autocue besides its own speaker, Erik's: `SLIDES_SPEAKERS`. A speaker not here keeps their
     * slides but has no line on the autocue — see [Subtitles.forSpeakers]. Empty, the default, is Erik alone.
     */
    val speakers: Set<String> = emptySet(),
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
     * The autocue's option: with the autocue up, whether the show continues by itself once each state is
     * done — its line said (by the voice where it is on, at reading pace where it is not), or its hold where
     * it has none — with the autocue counting down to the click; off, the presenter clicks. Nothing without
     * the autocue, and nothing to do with the voice or the subtitles. The organizer's `autoplay` button beside
     * `autocue` switches it while the show runs; kept across starts. Off unless asked for.
     */
    val autoplay: Boolean = false,
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
     * Seconds between the lines of a watched show's frame-time log — draws, paints, the gap between draws and
     * the heap, see [PerfLog]. 0, as committed, prints none. Never on a film, a bench or stills.
     */
    val perfLog: Double = 0.0,
    /**
     * Seconds the sound design and any sample still ringing take to fade away on a step back, inside a slide
     * or off its front: the picture cuts back at once, and its sound goes with it rather than playing on.
     */
    val backFade: Double = 0.3,

    /**
     * The feedback file: notes written against a slide in the organizer while it is watched,
     * each ticked off once acted on — see [Feedback]. It carries no behaviour and the deck
     * never reads it. Null, or no file there, starts an empty one on the first note.
     */
    val feedback: String? = null,

    /**
     * The meeting tasks: what a meeting asked of each slide, read off its transcript as tasks,
     * each one agreed, adjusted or dropped in the organizer — see [MeetingTasks]. Like the
     * feedback it carries no behaviour and the deck never reads it.
     */
    val meeting: String? = null,

    /**
     * The production's draaiboek, an .xlsx, and the tab of it that is the evening's clock (empty:
     * the first tab whose name starts with "Show"). Read only, for the times the organizer's
     * Subtitles tab sets against each section — see [Draaiboek].
     */
    val draaiboek: String? = null,
    val draaiboekTab: String? = null,

    /** Notes written against subtitle lines in the organizer's Subtitles tab — see [SubtitleFeedback]. */
    val subtitleFeedback: String? = null,

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
     * The playlist of each moment of the evening, by the moment's name in the order file — the bed
     * under every wall that stands in it, carried unbroken from one wall of the moment to the next.
     * A wall's own bed gives way to it. See [ShowBuilder.music].
     */
    val momentMusic: Map<String, Sound> = emptyMap(),
    /**
     * What a moment is called on the speaker's screen, by the order file's name for it: the order names its moments
     * in English, the evening is run in Dutch ("Opening course" is the menu's "1ᵉ gang"). Unnamed, the order's own.
     */
    val momentTitles: Map<String, String> = emptyMap(),

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
     * The gain the audio timeline review gave each sound-design cue, per state, laid over whatever
     * the sheet or the slide says — `show-gains.json`. Null plays every cue as delivered. See
     * [ReviewGains], and [ShowBuilder.gains].
     */
    val gains: ReviewGains? = null,

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

/** The subtitles file [track] reads, with each track's committed name where none is set. */
fun Settings.subtitlesFile(track: SubtitleTrack): String = when (track) {
    SubtitleTrack.DEFAULT -> subtitles ?: "show-subtitles.json"
    SubtitleTrack.EXTENDED -> subtitlesExtended ?: "show-subtitles-extended.json"
    SubtitleTrack.ALIGNED -> subtitlesAligned ?: "show-subtitles-aligned.json"
    SubtitleTrack.ERIK -> subtitlesErik ?: "show-subtitles-erik.json"
}
