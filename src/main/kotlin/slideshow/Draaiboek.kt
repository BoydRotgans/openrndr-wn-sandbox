package slideshow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The evening's clock: the Show tab of the production's draaiboek, an .xlsx kept outside the
 * project, read as a row a part of the evening — its time (Tijd), length (Duur) and name
 * (Onderdeel), with the room and what the guests do beside it.
 *
 * **Read, never written, and read again on every ask**, so a time moved in the workbook is in
 * the organizer's Subtitles tab on its next poll. The server only reads the rows; which row
 * belongs to which chapter or moment is the page's to say, since the page holds the order being
 * edited — `tools/subtitle_notes_pdf.py` lays the same rows on the same rules for the printed
 * sheets (`section_times` there, `draaiboekTimes` in the page).
 *
 * **An .xlsx is a zip of XML**, so one tab is read with the JDK alone: the workbook names its
 * sheets, its relations say which file each is, the shared strings hold the text, and a time of
 * day stored as a number is a share of the day.
 */
object Draaiboek {
    private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val TIME = Regex("(\\d{1,2})[:.](\\d{2})")
    private val MINUTES = Regex("(\\d+)\\s*min")

    /** The tab [tab], or the first whose name starts with "Show", as JSON for the page; `found: false` with a reason when it cannot be read. */
    fun json(file: File?, tab: String?): JsonObject = buildJsonObject {
        put("file", file?.path ?: "")
        if (file == null || !file.isFile) {
            put("found", false); put("error", if (file == null) "no SLIDES_DRAAIBOEK set" else "no file at ${file.path}")
            return@buildJsonObject
        }
        runCatching { read(file, tab) }.onSuccess { (name, rows) ->
            put("found", true)
            put("tab", name)
            put("day", rows.firstOrNull()?.get("A")?.let { Regex("[–-]\\s*([^|]+?)\\s*(\\||$)").find(it)?.groupValues?.get(1) } ?: "")
            put("parts", JsonArray(rows.drop(2).mapNotNull { part(it) }))
        }.onFailure {
            put("found", false); put("error", it.message ?: it.javaClass.simpleName)
        }
    }

    private fun part(row: Map<String, String>): JsonObject? {
        val title = row["C"]?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (title.isEmpty()) return null
        val times = TIME.findAll(row["A"].orEmpty()).map { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }.toList()
        val minutes = MINUTES.find(row["B"].orEmpty())?.groupValues?.get(1)?.toInt()
        val start = times.firstOrNull()
        val end = times.getOrNull(1) ?: if (start != null && minutes != null) start + minutes else null
        return buildJsonObject {
            put("title", title)
            put("start", start?.let { JsonPrimitive(it) } ?: JsonNull)
            put("end", end?.let { JsonPrimitive(it) } ?: JsonNull)
            put("minutes", minutes?.let { JsonPrimitive(it) } ?: JsonNull)
            put("room", row["D"].orEmpty())
            put("what", row["E"].orEmpty())
        }
    }

    /** The tab's name and its rows, each {column letter: text}. */
    private fun read(file: File, tab: String?): Pair<String, List<Map<String, String>>> = ZipFile(file).use { zip ->
        fun doc(name: String): Element {
            val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            return zip.getInputStream(zip.getEntry(name) ?: error("no $name in the workbook")).use { f.newDocumentBuilder().parse(it).documentElement }
        }
        fun Element.all(local: String) = getElementsByTagNameNS(MAIN, local).let { l -> (0 until l.length).map { l.item(it) as Element } }

        val targets = doc("xl/_rels/workbook.xml.rels").getElementsByTagName("Relationship").let { l ->
            (0 until l.length).associate { (l.item(it) as Element).let { r -> r.getAttribute("Id") to r.getAttribute("Target") } }
        }
        val sheets = doc("xl/workbook.xml").all("sheet").map { it.getAttribute("name") to targets[it.getAttributeNS(RELS, "id")] }
        val (name, target) = (if (!tab.isNullOrBlank()) sheets.firstOrNull { it.first == tab } else sheets.firstOrNull { it.first.lowercase().startsWith("show") })
            ?: error("no tab ${tab?.takeIf { it.isNotBlank() } ?: "Show…"} among ${sheets.map { it.first }}")
        val shared = if (zip.getEntry("xl/sharedStrings.xml") == null) emptyList()
                     else doc("xl/sharedStrings.xml").all("si").map { si -> si.all("t").joinToString("") { it.textContent } }
        val path = "xl/" + target!!.substringAfter("xl/").trimStart('/')
        name to doc(path).all("row").map { row ->
            row.all("c").mapNotNull { c ->
                val col = Regex("[A-Z]+").find(c.getAttribute("r"))?.value ?: return@mapNotNull null
                val v = c.all("v").firstOrNull()?.textContent
                val text = when (c.getAttribute("t")) {
                    "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }
                    "inlineStr" -> c.all("t").joinToString("") { it.textContent }
                    else -> v?.let { raw ->
                        // a time of day is a share of the day; only the Tijd column holds them
                        if (col == "A") raw.toDoubleOrNull()?.let { val m = Math.round(it % 1 * 1440).toInt(); "%02d:%02d".format(m / 60, m % 60) } ?: raw
                        else raw
                    }
                } ?: return@mapNotNull null
                col to text.trim()
            }.toMap()
        }
    }
}
