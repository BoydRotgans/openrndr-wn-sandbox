package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

/**
 * A description of a main component. Helps you identify which component instances are attached to
 * @param key  The key of the component
 * @param name  The name of the component
 * @param description  The description of the component as entered in the editor
 * @param componentSetId The ID of the component set if the component belongs to one
 * @param documentationLinks The documentation links for this component.
 * @param remote Whether this component is a remote component that doesn't live in this file
 */
@Serializable
data class Component(
    val key: String, val name: String, val description: String, val componentSetId: String? = null,
    val documentationLinks: List<DocumentationLink> = emptyList(), val remote: Boolean
)