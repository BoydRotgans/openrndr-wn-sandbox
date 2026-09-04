package org.openrndr.figma.rest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


/**
 * A description of a user
 */
@Serializable
data class User(
    val id: String,
    val handle: String,
    @SerialName("img_url")
    val imageUrl: String,
    val email: String)