package slideshow

import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer

/**
 * One slide. Subclass it, override what differs from the defaults, and draw.
 *
 * The defaults are the minimal case on purpose — a slide that is one click, on white,
 * fading in, is nothing but a `draw`:
 *
 *     class Cover : Slide() {
 *         override val background = ColorRGBa.fromHex("F1EDE4")
 *         override fun draw(drawer: Drawer, stage: Stage) {
 *             drawer.fill = ColorRGBa.BLACK
 *             drawer.circle(stage.center, 200.0)
 *         }
 *     }
 *
 * Anything a sketch already does fits here: whatever `draw` would have been in an
 * `extend { }` goes in [draw], and whatever it loaded at startup — an svg, a mesh, a
 * font, a render target — goes in [load], which runs once for every slide in the deck
 * before the first frame. A show must not hitch on a click, so nothing loads late.
 */
abstract class Slide : MidiTimed {

    /** Shown in the debug overlay and matched by `SLIDES_START`. */
    open val name: String get() = this::class.simpleName ?: "slide"

    /** How many clicks this slide holds. 1 is a slide you click straight past. */
    open val steps: Int get() = 1

    /**
     * Whether this takes the **whole wall** rather than the pane beside a chapter card.
     *
     * The one thing the driver has to know to compose a kind, and it is asked as a property
     * rather than by type because it is asked in fourteen places: the buffers it needs, the
     * bounds it draws into, whether a card can be seen beside it, whether a card must replay
     * when the show steps out of it, and how a handover with something narrower is composed.
     * Every one of those was `slide is Backdrop` before there was a second wide kind, which is
     * exactly the check a third kind has to go and edit. Set it and the engine follows.
     *
     * A wide slide is never given a chapter card — see `ShowBuilder.add`, which reads this.
     */
    open val wide: Boolean get() = false

    /**
     * Whether this wide slide is its chapter's card taking the whole wall, rather than a picture
     * the card steps aside for. A chapter opening that draws the card's own field across both
     * projectors is one: while it is up the card is not drawn, but it stays the chapter's card and
     * keeps its clock — the show starts it on the very frame this slide came up — so the slide after
     * finds the card exactly where the wall left it instead of starting it again.
     *
     * So such a slide keeps its chapter's card in `panelOf` like any narrow slide, is a section
     * start like one (the card's sting announces it), and holds for the ordinary reading time
     * rather than a wall's. Only meaningful with [wide]; see `ChapterOpening`.
     */
    open val carriesCard: Boolean get() = false

    /** What kind of thing this is, for the running order to report: `backdrop`, `scene`. */
    open val kind: String get() = "slide"

    /** Frames one click takes to play. */
    open val stepFrames: Int get() = frames(0.45)

    /**
     * Frames the click that lands on [step] takes — [stepFrames] unless a slide says otherwise.
     *
     * One length for every click of a slide is right for most and wrong for a few: the city's
     * push wants twelve seconds and its cull three, and with one length the cull finished in a
     * quarter of its click and the slide stood on one element for the rest. A slide that has a
     * long click and a short one overrides this for the step that differs. Going back, the
     * click being undone is the one that landed on the higher step, so its length is the one
     * asked for. The auto cues and the previews read it too.
     */
    open fun stepLength(step: Int): Int = stepFrames

    /**
     * Frames the slide takes to finish its own opening once it is up, for a run that writes
     * its cues itself (`SLIDES_CUES=auto`): a hold is this plus a reading time. The click
     * length by default, which is right for a slide that only moves on clicks; a slide that
     * builds on its own frame count — the globe — says how long that takes.
     */
    open val settle: Int get() = stepFrames

    /** Frames in one turn of the slide's loop, or 0 for a slide that holds still. */
    open val loop: Int get() = 0

    // --- the slide as timing ------------------------------------------------------------ //
    //
    // Every slide can be written down, because every slide has states: it arrives, and then it
    // is clicked. That is the floor, and it is what makes the organizer's midi button offerable
    // on all of them rather than on the two that happen to declare something richer.

    /**
     * The lanes this slide's build is written down on — one, its own name, by default.
     *
     * A slide that stands a *field* of things up has a lane per family and says so: the Plain
     * wall gives a lane a column, `Crowd` a lane a click. See [MidiTimed].
     */
    override val lanes: List<String> get() = listOf(name)

    /**
     * When this slide's states land: a note as it arrives and one a click, rising a semitone a
     * state so a run of them reads as the slide being built.
     *
     * **It is the states and not the picture**, which is the honest floor: nothing here knows
     * what a drawer puts on screen between one click and the next. A drawer that *does* know —
     * because its build is a schedule it steps through — overrides this and hands over what it
     * really stands up, and then the file is a score of the wall rather than of the clicking.
     */
    override fun arrivals(clicks: List<Int>): List<Arrival> =
        listOf(Arrival(lane = 0, index = 0, start = 0, length = settle)) +
                clicks.mapIndexed { i, at -> Arrival(lane = 0, index = i + 1, start = at, length = stepLength(i + 1)) }

    /** Cleared to this before [draw]. */
    open val background: ColorRGBa get() = ColorRGBa.WHITE

    /**
     * What this slide sounds like when it arrives, or null for a silent one.
     *
     * It sits here beside [transition] and [background] because it is the same kind of
     * thing — what the slide *is*, declared once and read by the driver — rather than
     * anything [draw] does. A slide never plays its own sound, for the reason it never
     * reads a clock: the deck can be clicked backwards, jumped into and filmed, and only
     * the driver knows which of those is happening.
     *
     * Stated in `Slideshow.kt` at the slide it belongs to. See [Sound].
     */
    open val sound: Sound? get() = null

    /**
     * A sound for the way *into* this slide, or null: played beside the state's own cue, whether or
     * not a cue sheet speaks for the slide, and only when the show comes to it **forward** or opens
     * on it — stepping back into it from the slide after is not arriving. It belongs to the slide
     * like any cue: with a fade out it is let go as the show leaves. The course transitions' sample
     * is one: heard once, as the dinner hands back to the talk, and not again on a step back.
     */
    open val leadIn: Sound? get() = null

    /**
     * Every sound this slide fires on **its own clock** rather than on a click — a wall that turns over
     * by itself, marking each turn. Declared so they are loaded before the first frame; [cuesBetween]
     * says when each falls due.
     */
    open val clockCues: List<Sound> get() = emptyList()

    /**
     * The [clockCues] that fall due after frame [from] and up to and including [until] of this slide's
     * own frame count — the driver asks for each stretch of frames once, as it passes, so a draw that
     * skips frames still sounds every turn exactly once, and a filmed run logs each on its own frame.
     */
    open fun cuesBetween(from: Int, until: Int): List<Sound> = emptyList()

    /**
     * Whether the playlist of the moment this slide stands in plays under it (see
     * `ShowBuilder.music`). True for every wall; false for one that takes the room *out* of its
     * moment, which lets the playlist go rather than carrying it on.
     */
    open val momentMusic: Boolean get() = true

    /**
     * Frames over which what the slide before sounded is let go as this one arrives, or null for
     * each sound's own fade out. A slide that wants the room to quiet slowly says how slowly.
     */
    open val outgoingFade: Int? get() = null

    /**
     * Whether this slide carries on from the one before it — draws it, or goes on from where it
     * stood — so that a filmed run may not be cut into pieces just before it (see `Pieces`).
     */
    open val carriesOn: Boolean get() = false

    /**
     * Whether this slide takes the stage through the show's [WallBuild] when it arrives going forward,
     * over [Settings.backdropBuild] — rather than simply being there. Every [Backdrop] does; a wall that
     * has to be the very picture the one before it left — the programme after the name tag, a course
     * transition after its course — says false. Only a wide slide is ever built.
     */
    open val buildsIn: Boolean get() = false

    /**
     * The seconds a hands-off run holds this slide once it has settled, where the deck's reading
     * time is wrong for it; null for the deck's own ([Settings.hold], or [Settings.holdWide] for a
     * wall of one picture). A slide that times itself to the second says so.
     */
    open val holdAfterSettle: Double? get() = null

    /**
     * The cue click [step] makes as it lands, or null for a silent click.
     *
     * The counterpart to [sound] for a slide that is *built* rather than simply arrived at:
     * [sound] is what the slide says on coming up, this is what each click says after that.
     * The stack is the case it exists for — a band a click, each one landing.
     *
     * The tree is the other case, and a different one: it opens on the city's own last frame
     * and holds there, so its cue belongs to the click that fans the labels out rather than
     * to an arrival nobody can see.
     *
     * Only ever fired going **forward**, the same rule the chapter cards follow. Clicking
     * back through a build is a correction, and re-firing the marks on the way would say
     * something is being built when it is being taken apart.
     *
     * Defaults to reading [stepCues], which is what a slide usually wants; override the
     * function itself where the cue has to be worked out rather than listed.
     */
    open fun stepSound(step: Int): Sound? = stepCues.getOrNull(step - 1)

    /**
     * A cue a click, **from click 1 on** — click 0 is the slide arriving, and that is [sound].
     *
     * Short of the step count it simply runs out, so a slide can gain a click without a cue
     * having to be found for it. It is the list behind [stepSound]; a slide whose cue depends
     * on more than the step number overrides that instead.
     */
    open val stepCues: List<Sound> get() = emptyList()

    /**
     * How this slide arrives, and by default it does not — it is simply there.
     *
     * The default was a [Fade] once and every slide in the deck has since been asked to stop
     * doing it, one at a time, which is the deck saying what it wants: a talk moves between
     * subjects and a dissolve reads as one picture becoming another. A slide that *should*
     * hand over — a [Push], or a fade between two states of the same thing — says so.
     */
    open val transition: Transition get() = Cut

    /**
     * What click [step] does, for the debug overlay — "build down", "reveal the plan",
     * whatever it is for. The overlay times the click but cannot tell one that reveals
     * from one that takes away, because that is inside [draw]; this is how a slide says.
     * Left null it reads "reveal" going forward and "build down" going back.
     */
    open fun stepName(step: Int): String? = null

    /**
     * The deck has just left [previous] for this slide, after it had stood [stood] frames on click [leftOn], and
     * this slide opens on click [opensOn]. A no-op for nearly every slide; it is for one that carries on from the
     * slide before it — the catalogue's grid picks the ring up at the turn it had reached, which only the deck
     * knows. Called on every move between two slides, never by a preview; an export calls it only where it runs
     * on into the next slide ([runsInto]). It may note what it is told and nothing more: what is drawn when is
     * still [draw]'s, off its [Stage].
     */
    open fun cameFrom(previous: Slide, stood: Int, leftOn: Int, opensOn: Int) {}

    /**
     * The slide an export of this one runs on into, one click further: that slide's opening state, settled and
     * held, is filmed and scored as the last state of this one. Null, the default, exports the slide alone.
     *
     * It is for a move that is heard as the end of one slide and drawn as the arrival of the next: the
     * catalogue's sheet pouring into the webtool's button is the webtool's state A, and a clip of the grid
     * stopping short of it gives the sound design nothing to cut that move against. See [exportRun].
     */
    open val runsInto: Slide? get() = null

    /** Runs once at startup, for everything that would otherwise load on the click. */
    open fun load(program: Program) {}

    /**
     * A key, offered to the slide on screen before the show or the studio acts on it — for a
     * slide that wants controls of its own, such as the size of what it draws. Return true to
     * claim it, and nothing else acts on that key while this slide is up; `esc` is never offered.
     * The slide asks first so a control can sit on a letter the studio also uses — on the shadow
     * wall `s` takes a row away, and writes a still on every other slide.
     *
     * A handler may change *what* is drawn, never *when*: it must not read a clock, because under
     * `ScreenRecorder` a handler sees wall time while [draw] sees video time (the note under
     * demo01 in CLAUDE.md). Set a value here and let [draw] act on it.
     */
    open fun key(name: String): Boolean = false

    /** One frame of the slide. Read [stage], draw, keep nothing. */
    abstract fun draw(drawer: Drawer, stage: Stage)
}
