package org.openrndr.figma.rest


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Files(
    val componentSets: Map<String, Component> = emptyMap(),
    val components: Map<String, ComponentSets> = emptyMap(),
    val document: Node,
    val editorType: String,
    val lastModified: String,
    val linkAccess: String,
    val name: String,
    val role: String,
    val schemaVersion: Int,
    val styles: Styles,
    val thumbnailUrl: String,
    val version: String
)