package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

/**
 * Guides to align and place objects within a frame
 * @param pattern Orientation of the grid as a string enum
 * * COLUMNS: Vertical grid
 * * ROWS: Horizontal grid
 * * GRID: Square grid
 * @param sectionSize Width of column grid or height of row grid or square grid spacing
 * @param visible Is the grid currently visible?
 * @param color Color of the grid
 * @param alignment Positioning of grid as a string enum
 * * MIN: Grid starts at the left or top of the frame
 * * STRETCH: Grid is stretched to fit the frame
 * * CENTER: Grid is center aligned
 * @param gutterSize Spacing in between columns and rows
 * @param offset Spacing before the first column or row
 * @param count Number of columns or rows
 * @param boundVariables A mapping of field to the [VariableAlias] of the bound variable.
 */
@Serializable
data class LayoutGrid(
    val pattern: String,
    val sectionSize: Double,
    val visible: Boolean,
    val color: Color,
    val alignment: String,
    val gutterSize: Double,
    val offset: Double,
    val count: Int,
    val boundVariables: Map<String, VariableAlias> = mapOf()
) {
}