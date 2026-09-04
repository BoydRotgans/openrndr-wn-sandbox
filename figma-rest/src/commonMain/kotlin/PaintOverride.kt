package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

/**
 * Paint metadata to override default paints
 * @param fill Paints applied to characters
 * @param inheritedFillStyleId ID of style node, if any, that this inherits fill data from
 */
@Serializable
data class PaintOverride(val fills: List<Paint>, val inheritedFillStyleId: String? = null) {
}