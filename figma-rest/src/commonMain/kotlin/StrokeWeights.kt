package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

/**
 * Individual stroke weights
 * @param top The top stroke weight
 * @param right The right stroke weight
 * @param bottom The bottom stroke weight
 * @param left The left stroke weight
 */
@Serializable
data class StrokeWeights(val top: Double, val right: Double, val bottom: Double, val left: Double)