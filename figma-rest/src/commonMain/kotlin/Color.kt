package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

@Serializable
data class Color(val r: Double, val g: Double, val b: Double, val a: Double)