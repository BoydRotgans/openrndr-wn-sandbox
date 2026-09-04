package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

/**
 * Defines a single path
 * @param path  A series of path commands that encodes how to draw the path.
 * @param windingRule The winding rule for the path (same as in SVGs). This determines whether a given point in space is inside or outside the path.
 * @param overrideID If there is a per-region fill, this refers to an ID in the fillOverrideTable.
 */
@Serializable
data class Path(val path: String, val windingRule: String, val overrideID: Int)