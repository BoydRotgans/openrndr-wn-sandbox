package org.openrndr.figma.rest

import kotlinx.serialization.Serializable


/**
 * A width and a height
 * @param width the width of a size
 * @param height the height of a size
 */
@Serializable
data class Size(val width: Double, val height: Double)