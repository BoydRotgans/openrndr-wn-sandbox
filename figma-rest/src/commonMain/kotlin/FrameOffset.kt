package org.openrndr.figma.rest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A relative offset within a frame
 * @param nodeID Unique id specifying the frame.
 * @param nodeOffset 2d vector offset within the frame.
 */
@Serializable
data class FrameOffset(@SerialName("node_id") val nodeID: String, @SerialName("node_offset") val nodeOffset: Vector)