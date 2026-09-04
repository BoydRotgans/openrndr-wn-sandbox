package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

/**
 * The device used to view a prototype
 */
@Serializable
data class PrototypeDevice(val type: String, val size: Size? = null, val presetIdentifier: String? = null, val rotation: String)