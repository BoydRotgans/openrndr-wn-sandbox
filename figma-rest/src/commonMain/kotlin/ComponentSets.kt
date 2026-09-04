package org.openrndr.figma.rest


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ComponentSets(
    val key: String = "",
    val file_key: String = "",
    val node_id: String = "",
    val thumnbnail_url: String? = null,
    val name: String = "",
    val description: String = "",
    val created_at: String = "",
    val updated_at: String = "",
    val user: User? = null,
    val containing_frame: Map<String, String> = emptyMap(),
)