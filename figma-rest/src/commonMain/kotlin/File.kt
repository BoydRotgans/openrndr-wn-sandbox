package org.openrndr.figma.rest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class File(
    val key: String,
    @SerialName("last_modified")
    val lastModified: String,
    val name: String,
    @SerialName("thumbnail_url")
    val thumbnailUrl: String
)