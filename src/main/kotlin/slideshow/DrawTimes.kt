package slideshow

import java.io.File

/**
 * Where a filmed run's time went, slide by slide.
 *
 * Each draw is booked to the slide on screen as it began, off the wall clock between one draw and
 * the next — so it is the whole cost of a frame: the drawing, the GPU catching up, the recorder
 * and whatever waited on ffmpeg. Written beside the film as `<film>.timing`, a line a slide in the
 * running order, and the dearest printed at the end of the run: the list to work down when a film
 * takes too long to make.
 */
class DrawTimes {
    private val nanos = LinkedHashMap<String, Long>()
    private val draws = LinkedHashMap<String, Int>()
    private var key: String? = null
    private var since = 0L

    /** A draw begins with [next] on screen; the one before it is booked to what was on screen then. */
    fun lap(next: String) {
        val now = System.nanoTime()
        key?.let {
            nanos.merge(it, now - since, Long::plus)
            draws.merge(it, 1, Int::plus)
        }
        key = next
        since = now
    }

    fun report(file: File) {
        if (nanos.isEmpty()) return
        val total = nanos.values.sum() / 1e9
        file.printWriter().use { out ->
            out.println("slide\tdraws\tseconds\tms_per_draw")
            for ((id, n) in nanos) {
                val d = draws[id] ?: 1
                out.println("%s\t%d\t%.1f\t%.1f".format(id, d, n / 1e9, n / 1e6 / d))
            }
        }
        println("timing: %.0fs over %d draws, %.1f ms a draw — the dearest slides:".format(
            total, draws.values.sum(), total * 1000 / draws.values.sum().coerceAtLeast(1)))
        nanos.entries.sortedByDescending { it.value }.take(12).forEach { (id, n) ->
            val d = draws[id] ?: 1
            println("  %-36s %6.0fs  %5d draws  %6.1f ms a draw".format(id, n / 1e9, d, n / 1e6 / d))
        }
        println("timing: written to ${file.path}")
    }

    companion object {
        fun file(video: File) = File(video.parentFile, video.nameWithoutExtension + ".timing")
    }
}

/**
 * What a bench measured: every state's cost a draw, and what that comes to over the hold a filmed
 * run gives it at [fps] — so the list is ordered by what the slide costs a film, not by how heavy
 * one frame of it is. A slide that is dear a frame and stands for four seconds matters less than
 * a cheap one that stands for a minute and a half.
 */
internal fun benchReport(
    plan: List<Pair<Int, Int>>, holds: List<Int>, millis: List<Double>,
    ids: List<String>, slides: List<Slide>, fps: Int
) {
    val file = File("build/bench/bench.tsv").also { it.parentFile.mkdirs() }
    // deck frames a drawn frame covers at the recording rate
    val stride = FPS.toDouble() / fps
    data class Row(val id: String, val letter: String, val ms: Double, val hold: Int) {
        val cost: Double get() = hold / stride * ms / 1000.0
    }
    val rows = plan.mapIndexed { k, (s, step) ->
        Row(ids.getOrElse(s) { slides[s].name }, letter(step), millis.getOrElse(k) { 0.0 }, holds.getOrElse(k) { 0 })
    }
    file.printWriter().use { out ->
        out.println("slide\tstate\tms_per_draw\thold_s\tfilm_cost_s")
        rows.forEach { out.println("%s\t%s\t%.1f\t%.1f\t%.1f".format(it.id, it.letter, it.ms, seconds(it.hold), it.cost)) }
    }
    val bySlide = rows.groupBy { it.id }.mapValues { (_, r) -> r.sumOf { it.cost } to r.sumOf { it.ms * it.hold } / r.sumOf { it.hold }.coerceAtLeast(1) }
    val total = bySlide.values.sumOf { it.first }
    val film = rows.sumOf { it.hold }
    println("bench: %d states, a %.0fs film drawn at %d fps would take %.0fs of drawing (%.2fx real time)".format(
        rows.size, seconds(film), fps, total, total / seconds(film).coerceAtLeast(1.0)))
    bySlide.entries.sortedByDescending { it.value.first }.forEach { (id, v) ->
        println("  %-38s %6.0fs  %5.1f ms a draw  %4.1f%%".format(id, v.first, v.second, 100 * v.first / total.coerceAtLeast(1e-9)))
    }
    println("bench: per state in ${file.path}")
}
