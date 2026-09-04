package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

/**
 * Information about the arc properties of an ellipse. 0° is the x axis and increasing angles rotate clockwise
 */
@Serializable
data class ArcData(val startingAngle: Double, val endingAngle: Double, val innerRadius: Double)