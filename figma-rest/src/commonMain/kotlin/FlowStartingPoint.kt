package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

/**
 * A flow starting point used when launching a prototype to enter Presentation view.
 * @param nodeId Unique identifier specifying the frame
 * @param name Name of flow
 */
@Serializable
data class FlowStartingPoint(val nodeId: String, val name: String)

