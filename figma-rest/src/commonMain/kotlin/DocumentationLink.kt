package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

/**
 * Represents a link to documentation for a component.
 * @param uri Should be a valid URI (e.g. https://www.figma.com).
 */
@Serializable
data class DocumentationLink(val uri: String)