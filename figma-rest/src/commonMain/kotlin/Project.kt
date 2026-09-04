package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: String,
    val name: String
)