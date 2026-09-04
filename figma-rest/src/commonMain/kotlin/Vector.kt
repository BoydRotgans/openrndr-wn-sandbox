package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

/**
 * A 2d vector
 * @param x X coordinate of the vector
 * @param y Y coordinate of the vector
 */
@Serializable
data class Vector(val x: Double, val y: Double)