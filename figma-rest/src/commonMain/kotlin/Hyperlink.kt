package org.openrndr.figma.rest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A link to either a URL or another frame (node) in the document
 * @param Type of hyperlink
 */
@Serializable
data class Hyperlink(
    val type: String,
    val url: String
)