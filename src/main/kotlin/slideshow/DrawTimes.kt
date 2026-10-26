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

/**
 * A watched show's frame times, printed a line every [every] seconds (`SLIDES_PERF_LOG`, 15 October 2026):
 * how many draws and how many of them painted, the gap from one draw to the next — mean, 99th percentile
 * and the longest, with the state it fell on — what a draw cost the CPU, and the heap in use. The gap is
 * the honest number: it holds the GPU catching up and the wait for the display, which the CPU time does not.
 *
 * A stress test reads a running show off these lines; a bench steps its own clock and cannot.
 */
class PerfLog(private val every: Double, private val extra: () -> String = { "" }) {
    private var gaps = DoubleArray(2048)
    private var n = 0
    private var painted = 0
    private var cpuSum = 0.0
    private var cpuMax = 0.0
    private var cpuN = 0
    private var worst = 0.0
    private var worstOn = ""
    private var key = ""
    private var lastStart = 0L
    private var windowStart = 0L
    private var started = 0L

    /** A draw begins, with [key] on the wall. */
    fun begin(key: String) {
        val now = System.nanoTime()
        if (lastStart != 0L) {
            val gap = (now - lastStart) / 1e6
            if (n == gaps.size) gaps = gaps.copyOf(n * 2)
            gaps[n++] = gap
            // the gap is the draw before's, and the state it drew
            if (gap > worst) { worst = gap; worstOn = this.key }
        } else windowStart = now
        lastStart = now
        started = now
        this.key = key
        if (n > 0 && (now - windowStart) / 1e9 >= every) report(now)
    }

    /** The draw ends; [fresh] when it painted the wall rather than showing the last picture again. */
    fun end(fresh: Boolean) {
        val cpu = (System.nanoTime() - started) / 1e6
        cpuSum += cpu; cpuN++
        if (cpu > cpuMax) cpuMax = cpu
        if (fresh) painted++
    }

    private fun report(now: Long) {
        val s = gaps.copyOf(n).also { it.sort() }
        val seconds = (now - windowStart) / 1e9
        val rt = Runtime.getRuntime()
        println("perf: %4.1fs  %4d draws %3.0f/s  %4d painted  gap %.1f p99 %.1f max %.1f ms on %s  >25ms %d  >50ms %d  cpu %.1f max %.1f ms  heap %d MB  %s".format(
            seconds, n, n / seconds, painted, s.average(), s[((n - 1) * 0.99).toInt()], worst, worstOn.ifBlank { "-" },
            s.count { it > 25.0 }, s.count { it > 50.0 }, cpuSum / cpuN.coerceAtLeast(1), cpuMax,
            (rt.totalMemory() - rt.freeMemory()) / (1 shl 20), key) + extra().let { if (it.isEmpty()) "" else "  sound: $it" })
        n = 0; painted = 0; cpuSum = 0.0; cpuMax = 0.0; cpuN = 0; worst = 0.0; worstOn = ""
        windowStart = now
    }
}
