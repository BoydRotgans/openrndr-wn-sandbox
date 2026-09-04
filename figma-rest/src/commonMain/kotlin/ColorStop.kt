package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

/**
 * A position color pair representing a gradient stop
 * @param position Value between 0 and 1 representing position along gradient axis
 * @param color Color attached to corresponding position
 */
@Serializable
data class ColorStop(val position: Double, val color: Color) {
}