package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

/**
 * Contains a variable alias.
 * @param type VARIABLE_ALIAS
 * @param id The id of the variable that the current variable is aliased to. This variable can be a local or remote variable, and both can be retrieved via the `GET /v1/files/:file_key/variables/local` endpoint.
 */
@Serializable
data class VariableAlias(val type: String, val id: String)