package org.openrndr.figma.rest

import kotlinx.serialization.Serializable


@Serializable
data class Rectangle(val x: Double, val y: Double, val width: Double, val height: Double)