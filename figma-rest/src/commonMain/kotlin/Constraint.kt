package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

@Serializable
data class Constraint(val type: String, val value: Double)