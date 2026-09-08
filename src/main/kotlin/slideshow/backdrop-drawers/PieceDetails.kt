// ============================================================================ //
//  No `package` declaration: it belongs with the backdrop drawers, which are in
//  the default package because they stand on loadObjectSheet.
// ============================================================================ //

import java.io.File

/**
 * What the catalogue knows about one piece, straight out of `objects-115-details.csv`.
 *
 * Every field is the register's own — nothing here is derived, rounded into being, or
 * inferred from the drawing. That is the point of the file: until it arrived, a silhouette
 * on the wall could be given a number and a proportion and nothing else, because the sheets
 * carry their captions as outlined paths and the pieces could not be paired to the named
 * meshes in `data/objects` (measured: only 27 of 112 front-sheet silhouettes were clear of
 * their runner-up, most of the catalogue being plain rectangles).
 */
data class PieceDetail(
    /** Its place in the catalogue, counting from 1 — and the sheet's own reading order. */
    val no: Int,
    val name: String,
    /** The assembly it belongs to, and that assembly's tag on the drawings. */
    val assembly: String,
    val tag: String,
    /** The IFC class, as exported. */
    val type: String,
    /** The register's own short description — a profile, a plate size. */
    val description: String,
    val widthMm: Double,
    val heightMm: Double,
    val depthMm: Double
) {
    /**
     * `W x D x H`, in the order a component is quoted in, in whole millimetres.
     *
     * Depth is the one a drawing cannot give you: a silhouette has two dimensions and this is
     * the third, so it is the clearest single thing the register adds to the wall.
     */
    fun size(): String = "%d × %d × %d MM".format(
        widthMm.toInt(), depthMm.toInt(), heightMm.toInt()
    )

    /**
     * The IFC class, made readable: `IfcBeam` is `BEAM` and `IfcDiscreteAccessory` is
     * `DISCRETE ACCESSORY`. Dropping the prefix alone leaves `DISCRETEACCESSORY`, which is a
     * word nobody can read at a glance across a room — the camel case is the only word break
     * the class name has, so it is the one to use.
     */
    fun shortType(): String = type.removePrefix("Ifc")
        .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
        .uppercase()
}

/**
 * Reads the catalogue's own detail file, in `obj_no` order.
 *
 * **The order is the sheet's**, which was checked rather than assumed and is the whole basis
 * for showing a name beside a drawing at all. An isometric drawing's on-screen proportion is
 * predictable from the piece's box — a base of `(W + D) * cos 30` against a height of
 * `(W + D) * sin 30 + H` — so the file can be tested against the sheet directly: taken in
 * `obj_no` order the predicted proportion tracks the measured one at a **correlation of
 * 0.971**, with 92 of the 115 within 12%. The two other ways of reading the columns as a box
 * correlate at -0.275 and -0.707, so which column is which is settled by the same test.
 *
 * Returned as a list in file order; the caller pairs it with the sheet by index, and must
 * check the counts agree before it does — see [OpeningScene].
 */
fun loadPieceDetails(file: File): List<PieceDetail> {
    if (!file.isFile) return emptyList()

    val lines = file.readLines().filter { it.isNotBlank() }
    if (lines.size < 2) return emptyList()

    val head = lines.first().split(",").map { it.trim() }
    fun index(name: String) = head.indexOf(name)

    val no = index("obj_no")
    val name = index("name")
    val assembly = index("assembly")
    val tag = index("assembly_tag")
    val type = index("ifc_type")
    val description = index("description")
    val w = index("width_mm")
    val h = index("height_mm")
    val d = index("depth_mm")
    if (listOf(no, name, w, h, d).any { it < 0 }) {
        println("${file.path} is missing a column this needs; the wall falls back to proportions")
        return emptyList()
    }

    return lines.drop(1).mapNotNull { line ->
        // The register writes no quoted commas, so a plain split is enough and stays honest
        // about it: a row that does not have the columns it should is dropped rather than
        // shifted into the wrong fields.
        val cell = line.split(",").map { it.trim() }
        if (cell.size < head.size) return@mapNotNull null
        PieceDetail(
            no = cell[no].toIntOrNull() ?: return@mapNotNull null,
            name = cell[name],
            assembly = cell.getOrElse(assembly) { "" },
            tag = cell.getOrElse(tag) { "" },
            type = cell.getOrElse(type) { "" },
            description = cell.getOrElse(description) { "" },
            widthMm = cell[w].toDoubleOrNull() ?: 0.0,
            heightMm = cell[h].toDoubleOrNull() ?: 0.0,
            depthMm = cell[d].toDoubleOrNull() ?: 0.0
        )
    }.sortedBy { it.no }
}

/**
 * The register lined up with a sheet, one entry per drawing, or nothing at all.
 *
 * The two sheets are the same catalogue and the same order, but they do not recover the same
 * number of drawings: `objects-iso.svg` gives all 115 and `objects-front.svg` gives 112. Pairing
 * either by index without saying which is which would shift every name from the first gap
 * onwards and never mention it — so the shape of the mismatch has to be *known*, not assumed.
 *
 * **Which three the front sheet is missing was measured rather than guessed.** An elevation's
 * drawn proportion is one of the register's own faces, so the file can be checked against the
 * sheet directly: an order-preserving alignment over 112 drawings and 115 rows lands on rows
 * **103, 113 and 114** being absent, and with that skip applied **all 112** drawings match their
 * row's proportion to within 12% — allowing either width or depth to be the horizontal, since
 * some pieces are drawn as a side elevation. Nothing else about the order moves.
 *
 * Any other pairing of counts returns nothing, and the caller says so: a wall of mislabelled
 * components is worse than a wall of unlabelled ones.
 */
fun List<PieceDetail>.alignedTo(drawings: Int): List<PieceDetail>? = when {
    size == drawings -> this
    size == 115 && drawings == 112 -> filterIndexed { i, _ -> i !in FRONT_SHEET_GAPS }
    else -> null
}

/** The register rows `objects-front.svg` does not draw. See [alignedTo]. */
private val FRONT_SHEET_GAPS = setOf(103, 113, 114)

