package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

/**
 * Defines the image filters applied to an image paint. All values are from -1 to 1.
 */
@Serializable
data class ImageFilters(
    val exposure: Double = 0.0,
    val contrast: Double = 0.0,
    val saturation: Double = 0.0,
    val temperature: Double = 0.0,
    val tint: Double = 0.0,
    val highlights: Double = 0.0,
    val shadows: Double = 0.0
) {
}