package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

@Serializable
data class Images(val err: String?, val images: Map<String, String?>)