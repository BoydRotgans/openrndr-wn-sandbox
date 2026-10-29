package slideshow

import java.io.File
import javax.sound.sampled.AudioSystem

/**
 * A sound design delivered as a **folder named against the show** — one wav per state of one
 * slide, the file saying which state it belongs to.
 *
 * `data/sounds/P1` is the first of these: `P1-02-catalogue-city-B.wav` is the cue for the
 * catalogue city's first click. The tail of every name is the [Nameplate]'s own label —
 * the slide's id and a letter a click, A for the state a slide arrives on — which is the
 * same name the order file, the organizer and `show-feedback.json` all call a state by. So a
 * cue, a note written against a review copy and a line in the running order are all about
 * the one thing, and a sound designer watching a filmed run with `SLIDES_NAMEPLATE=true` can
 * name a file without asking anyone what the slide is called.
 *
 * **That is why this is read off the folder rather than declared in `Slideshow.kt`.** The
 * hand-declared cues above it are a decision a slide at a time — which is right for a sheet of
 * numbered stings that has to be placed by ear. Here the placement is already in the file name,
 * and writing it out again in Kotlin would be a second copy of it to keep in step: 31 cues in
 * chapter 1 alone, and a rename of a slide would have to be made in two places or the sound
 * would quietly land on the wrong state.
 *
 * **It is keyed by id, not by where a slide is declared**, and that is load-bearing. `De
 * fabrieken` is declared in the fourth chapter of `Slideshow.kt` and *played* in the first,
 * because `show-order.json` puts it there — so a cue attached at the declaration would be
 * attached to the wrong chapter's sound design. An id travels with the slide through every
 * reorder, which is the whole reason the order file uses one.
 *
 * **A sheet owns the slides it names.** Where it carries a cue for a slide at all, it is that
 * slide's whole cue list and a state it leaves bare is silent — the designer chose not to mark
 * it. Falling back state by state would put the old sheet's sound under the new one on exactly
 * the states the new design left clear. Slides the sheet does not name keep whatever
 * `Slideshow.kt` declares for them, which is what keeps chapters 2 to 4 sounding as they did.
 *
 * **A slide folded into fewer states keeps every sound it was delivered with** (28 October 2026). Its
 * [Slide.cueFold] says where each delivered letter now lands: on which state, and how many seconds into
 * it. The first at no delay is the state's cue as before; the rest are [later], fired by the driver at
 * their moment, so a build that now happens on one click still sounds each of its pieces.
 */
class CueSheet private constructor(
    /** The folders read, for the report. */
    private val folders: List<File>,
    /** Cue by name — a slug while the sheet is unbound, a slide's id once [boundTo] has run. */
    private val states: Map<String, Map<Int, Sound>>,
    /** The cues a folded state fires after its first, with the seconds into the state each falls at. */
    private val laterCues: Map<String, Map<Int, List<Pair<Sound, Double>>>> = emptyMap()
) {

    /** The cue for [step] of the slide called [id], or null where the sheet leaves it bare. */
    operator fun get(id: String, step: Int): Sound? = states[id]?.get(step)

    /** The cues [step] of the slide called [id] fires after its own, each with the seconds into the state it falls at. */
    fun later(id: String, step: Int): List<Pair<Sound, Double>> = laterCues[id]?.get(step).orEmpty()

    /**
     * Whether this sheet speaks for [id] at all — and so whether what `Slideshow.kt` declares
     * for that slide is heard. See the note on ownership above.
     */
    fun owns(id: String): Boolean = id in states || id in laterCues

    /** Every cue in the sheet, for [Speakers.load] to decode before the first frame. */
    val all: List<Sound> get() = states.values.flatMap { it.values } + laterCues.values.flatMap { it.values.flatten().map { (sound, _) -> sound } }

    val isEmpty: Boolean get() = states.isEmpty()

    /**
     * The sheet re-keyed by the show's own slide ids, and the report of what matched.
     *
     * The names in the folder are *nearly* the ids and cannot be relied on to be exactly them —
     * `P1-02-catalogue-city` stands for `the-catalogue-city`, the article dropped. So a cue's
     * slug matches an id when it is that id or a tail of it on a dash boundary, and a cue that
     * matches none, or more than one, is **dropped with a line saying so**. Silently guessing
     * would put a chapter's sound design on the wrong slide, which is worse than leaving it off:
     * a missing cue is heard immediately and a misplaced one is not heard as a fault at all.
     *
     * A slide in [folds] has its letters laid onto the states its fold names rather than one a state.
     */
    fun boundTo(states: Map<String, Int>, folds: Map<String, List<CueFold>> = emptyMap()): CueSheet {
        val ids = states.keys
        val bound = mutableMapOf<String, Map<Int, Sound>>()
        val later = mutableMapOf<String, Map<Int, List<Pair<Sound, Double>>>>()
        val report = mutableListOf<String>()

        for ((slug, cues) in this.states) {
            val hits = hitsFor(slug, ids)
            val id = idOf(slug, hits)
            when {
                id == null && hits.isEmpty() ->
                    report += "  $slug — no slide of that name; ${cues.size} cue(s) ignored"
                id == null ->
                    report += "  $slug — matches ${hits.joinToString(", ")}; ${cues.size} cue(s) ignored"
                id in folds -> {
                    val (first, rest, over) = folded(cues, folds.getValue(id), states.getValue(id))
                    bound[id] = first
                    later[id] = rest
                    val on = (first.keys + rest.keys).sorted().joinToString(" ") { s ->
                        (listOfNotNull(first[s]?.let { 0.0 to it }) + rest[s].orEmpty().map { (sound, at) -> at to sound })
                            .sortedBy { it.first }.joinToString("+") { (_, sound) -> letterOf(sound) }
                    }
                    report += "  %-34s %s  (folded)".format(id, on)
                    if (over.isNotEmpty()) report += "  %-34s %s past its fold; ignored".format("", over.sorted().joinToString(" ") { letter(it) })
                }
                else -> {
                    // A cue for a state the slide does not have would simply never fire, which is
                    // a sheet and a deck that have drifted apart — a click taken out of a slide,
                    // or a letter counted from B. Said out loud, because it is the one fault here
                    // that is inaudible: nothing is wrong on the wall and nothing is wrong in the
                    // folder, and the cue is just never reached.
                    val steps = states.getValue(id)
                    val (kept, over) = cues.entries.partition { it.key < steps }
                    bound[id] = kept.associate { it.key to it.value }
                    report += "  %-34s %s".format(id, kept.map { it.key }.sorted().joinToString(" ") { letter(it) })
                    if (over.isNotEmpty()) report += "  %-34s %s past its %d state(s); ignored".format(
                        "", over.map { it.key }.sorted().joinToString(" ") { letter(it) }, steps
                    )
                }
            }
        }

        println("cues: ${folders.joinToString(", ") { it.path }} — ${bound.size} slides, " +
                "${bound.values.sumOf { it.size } + later.values.sumOf { it.values.sumOf { l -> l.size } }} cues")
        report.forEach { println(it) }
        return CueSheet(folders, bound, later)
    }

    /**
     * A slide's delivered cues laid onto its folded states: per state the cue at no delay, the rest with
     * their seconds, and the letters the fold has no place for. A state with several cues at no delay
     * keeps the first as its own and fires the others with it.
     */
    private fun folded(cues: Map<Int, Sound>, fold: List<CueFold>, steps: Int):
            Triple<Map<Int, Sound>, Map<Int, List<Pair<Sound, Double>>>, List<Int>> {
        val first = mutableMapOf<Int, Sound>()
        val rest = mutableMapOf<Int, MutableList<Pair<Sound, Double>>>()
        val over = mutableListOf<Int>()
        for ((delivered, sound) in cues.entries.sortedBy { it.key }) {
            val to = fold.getOrNull(delivered)?.takeIf { it.state < steps }
            when {
                to == null -> over += delivered
                to.seconds <= 0.0 && to.state !in first -> first[to.state] = sound
                else -> rest.getOrPut(to.state) { mutableListOf() } += sound to to.seconds
            }
        }
        return Triple(first, rest.mapValues { (_, l) -> l.sortedBy { it.second } }, over)
    }

    /** The letter a cue was delivered under, off its file name, for the report. */
    private fun letterOf(sound: Sound): String = sound.file.nameWithoutExtension.substringAfterLast('-')

    /**
     * The files the folders hold now for the slides in [states] (id to state count), by state: what the
     * organizer shows beside the name a sound for each state is to have (12 October 2026). Read off the
     * folders again each time, so a delivery shows without a restart, and matched by the rule [boundTo]
     * binds by — quietly, where [boundTo] reports. Later folders win, as in [read]. A state a [folds] slide
     * stacks several cues on names the first file and the letters laid on it after: `…-A + B + C + D.wav`.
     */
    fun files(states: Map<String, Int>, folds: Map<String, List<CueFold>> = emptyMap()): Map<String, Map<Int, String>> {
        val found = mutableMapOf<String, MutableMap<Int, String>>()
        for (folder in folders) {
            val wavs = folder.listFiles { f: File -> f.isFile && f.extension.equals("wav", true) }?.sortedBy { it.name }.orEmpty()
            for (file in wavs) {
                val (slug, step) = parse(file.nameWithoutExtension) ?: continue
                val id = idOf(slug, hitsFor(slug, states.keys)) ?: continue
                if (step < states.getValue(id) || id in folds) found.getOrPut(id) { mutableMapOf() }[step] = file.name
            }
        }
        return found.mapValues { (id, byLetter) ->
            val fold = folds[id] ?: return@mapValues byLetter.toMap()
            byLetter.entries.mapNotNull { (delivered, name) -> fold.getOrNull(delivered)?.let { Triple(it.state, it.seconds, name) } }
                .filter { it.first < states.getValue(id) }
                .groupBy { it.first }
                .mapValues { (_, on) ->
                    val names = on.sortedBy { it.second }.map { it.third.removeSuffix(".wav") }
                    (listOf(names.first()) + names.drop(1).map { it.substringAfterLast('-') }).joinToString(" + ") + ".wav"
                }
        }
    }

    /** The ids a cue's slug could stand for: the id itself, or one it is a tail of on a dash boundary, or the other way round. */
    private fun hitsFor(slug: String, ids: Collection<String>) =
        ids.filter { it == slug || it.endsWith("-$slug") || slug.endsWith("-$it") }

    /** The one id among [hits], or the exact one where several match; null where none decides. */
    private fun idOf(slug: String, hits: List<String>) = hits.singleOrNull() ?: hits.firstOrNull { it == slug }

    companion object {

        /**
         * Reads [folders] in order, later ones winning — so a revised sheet stands beside the
         * one it revises rather than replacing the folder.
         *
         * Everything but the level is derived, because everything but the level is in the file.
         *
         * **A cue longer than [hold] seconds is taken away with its slide**, over [fadeOut];
         * a shorter one rings out. That is the rule the hand-declared sheet arrived at one cue
         * at a time — `1-02`, `1-07` and `1-10` fade and the two-second marks do not — stated
         * once instead of decided again per file, and the first sheet splits on it cleanly: its
         * cues run 2.4 to 5.1 seconds or 9.0 to 19.2, with nothing in between. A [Sound.fadeOut]
         * is what says a cue belongs to its slide rather than playing on under the next one; see
         * [Sound.sustained].
         *
         * A folder that is not there is not a fault — `data/` is not committed, so a missing
         * sheet leaves the show sounding as `Slideshow.kt` declares it, the way a missing object
         * sheet falls back to plain type.
         */
        fun read(
            folders: List<File>,
            gain: Double = 1.0,
            hold: Double = 6.0,
            fadeOut: Double = 1.5
        ): CueSheet? {
            val states = mutableMapOf<String, MutableMap<Int, Sound>>()
            val found = folders.filter { it.isDirectory }
            if (found.isEmpty()) return null

            for (folder in found) {
                val files = folder.listFiles { f: File -> f.isFile && f.extension.equals("wav", true) }
                    ?.sortedBy { it.name }.orEmpty()
                for (file in files) {
                    val parsed = parse(file.nameWithoutExtension)
                    if (parsed == null) {
                        println("cues: ${file.name} does not end in a state letter — ignored")
                        continue
                    }
                    val (slug, step) = parsed
                    states.getOrPut(slug) { mutableMapOf() }[step] =
                        Sound(file, gain = gain, fadeOut = if (lengthOf(file) > hold) frames(fadeOut) else 0)
                }
            }
            return if (states.isEmpty()) null else CueSheet(found, states.mapValues { it.value.toMap() })
        }

        /**
         * A cue's file name read as the slide it names and the state: `P1-12-de-fabrieken-H` is
         * `de-fabrieken` and 7. Null where it does not end in a state letter. The slide number is
         * optional: `P4-plain-A` names its slide as well as `P4-03-100-elementen-A` does, and was
         * ignored until 22 September.
         */
        fun parse(name: String): Pair<String, Int>? {
            val cut = name.lastIndexOf('-')
            val step = stepOf(if (cut > 0) name.substring(cut + 1) else "") ?: return null
            return slugOf(name.substring(0, cut)).replace(Regex("^p?\\d+-(\\d+-)?"), "") to step
        }

        /**
         * The state a label names — the inverse of [letter], so `A` is the state a slide arrives
         * on and `B` its first click. Null for anything that is not one, which is how a stray
         * file in the folder is told from a cue.
         */
        fun stepOf(label: String): Int? {
            if (label.isEmpty() || label.any { it !in 'A'..'Z' }) return null
            var n = 0
            for (c in label) n = n * 26 + (c - 'A' + 1)
            return n - 1
        }

        /**
         * How long a wav runs, from its header alone — no decoding, since all that is wanted is
         * whether it outlives the state it marks. 0 for anything that will not open, which lands
         * it on the ring-out side of [read]'s rule and leaves the failure to be reported by
         * [Speakers.load], whose job it is.
         */
        private fun lengthOf(file: File): Double = runCatching {
            val format = AudioSystem.getAudioFileFormat(file)
            format.frameLength.toDouble() / format.format.frameRate
        }.getOrDefault(0.0)
    }
}

/**
 * Where one delivered letter of a folded slide plays: on [state], [seconds] into it. See [Slide.cueFold].
 */
class CueFold(val state: Int, val seconds: Double = 0.0)
