package slideshow

import java.io.File

/**
 * A sound a slide makes when it arrives.
 *
 * A **value, not a player**: it names a file, how loud it is and whether it holds under the
 * slide, and says nothing about when or how it is heard. That is the same split the rest of
 * the deck is built on — a slide declares what it *is* and the driver works out what to do
 * with it — and it buys the same things here: the running order can be read back with its
 * cues in it, a silent run is the same run, and a filmed one is too.
 *
 * **It is declared in `Slideshow.kt`, not in a drawer.** A cue is direction rather than
 * drawing: which sound a chapter opens on is a decision about the talk, and a drawer that
 * knew its own sound could not be stood up twice under two different ones — which is
 * exactly what `Swivel02Slide` and `ObjectScene` already are.
 *
 * Nothing plays it yet. [Slide.sound] is read by no one; the playback engine is the next
 * piece, and this is the reference it will read.
 */
data class Sound(
    /** The file. A wav, read once at load — a show must not hitch on a click. */
    val file: File,
    /** 0..1, straight onto the source's gain. */
    val gain: Double = 1.0,
    /**
     * Come round again for as long as the slide is up, rather than firing once on arrival.
     *
     * **Off by default, and most cues want it off**: a sting is a transition and is over when
     * it is over. A bed is the exception — the ambience under the opening wall, which has to
     * still be there an hour later.
     */
    val loop: Boolean = false,

    /**
     * Frames to come up over as the slide arrives, and to go out over as it leaves.
     *
     * **What decides whether a cue fades is whether it is a bed or a sting, not whether it
     * loops.** A bed switched on at full gain reads as a fault and one cut off mid-phrase
     * reads as the machine being turned off; a sting is a shape in its own right, and taking
     * the front off it takes the attack with it. Looping is a separate question — the map's
     * cue runs 25s and does not loop, and still wants both.
     *
     * Frames rather than seconds, like everything else the deck is timed in — the show writes
     * `frames(4.0)`. 0 at both ends is a sting: in at full, and left to ring out.
     */
    val fadeIn: Int = 0,
    val fadeOut: Int = 0
) {
    constructor(
        path: String, gain: Double = 1.0, loop: Boolean = false,
        fadeIn: Int = 0, fadeOut: Int = 0
    ) : this(File(path), gain, loop, fadeIn, fadeOut)

    /**
     * `data/` is not committed, so a missing sound is an ordinary state rather than a fault —
     * the deck falls back to silence the way a missing sheet falls back to plain type.
     */
    val present: Boolean get() = file.isFile

    /**
     * Whether this cue is **taken away with its slide** rather than left to ring out.
     *
     * A [fadeOut] is what says so, and it is the honest test: a cue asked to go out over two
     * seconds is a cue that belongs to the slide it was declared on. It matters most for the
     * long ones — `1-02.wav` runs 25s against a click of 12, so without this it would still be
     * playing two slides later, under a picture it has nothing to do with.
     *
     * A sting declares no fade and is left alone, which is right: cutting a half-second mark
     * off at the click would be more noticeable than letting it finish.
     *
     * It also decides *where* the cue is played. A sustained cue needs a voice the driver can
     * find again to fade it, so it gets one of its own rather than the shared one-shot pool.
     */
    val sustained: Boolean get() = loop || fadeOut > 0

    override fun toString(): String = buildString {
        append(file.name)
        if (gain != 1.0) append(" @%.2f".format(gain))
        if (loop) append(" loop")
        if (fadeIn > 0 || fadeOut > 0) append(" fade %.1f/%.1fs".format(seconds(fadeIn), seconds(fadeOut)))
        if (!present) append(" (missing)")
    }
}
