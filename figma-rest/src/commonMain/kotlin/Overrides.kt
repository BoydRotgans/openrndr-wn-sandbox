package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

/**
 * Fields directly overridden on an instance. Inherited overrides are not included.
 * @param id A unique ID for a node
 * @param overridenFields An array of properties
 */
@Serializable
data class Overrides(val id: String, val overridenFields: List<String>)