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
     * `base-loop.wav` is the bed this is for; the numbered cues are one-shots.
     */
    val loop: Boolean = false
) {
    constructor(path: String, gain: Double = 1.0, loop: Boolean = false) :
            this(File(path), gain, loop)

    /**
     * `data/` is not committed, so a missing sound is an ordinary state rather than a fault —
     * the deck falls back to silence the way a missing sheet falls back to plain type.
     */
    val present: Boolean get() = file.isFile

    override fun toString(): String = buildString {
        append(file.name)
        if (gain != 1.0) append(" @%.2f".format(gain))
        if (loop) append(" loop")
        if (!present) append(" (missing)")
    }
}
