package org.openrndr.figma.rest


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProjectFiles(
    @SerialName("files")
    val files: List<File>,
    @SerialName("name")
    val name: String
)