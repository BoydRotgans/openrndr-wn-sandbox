package org.openrndr.figma.rest


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TeamProjects(
    @SerialName("name")
    val name: String,
    @SerialName("projects")
    val projects: List<Project>
)