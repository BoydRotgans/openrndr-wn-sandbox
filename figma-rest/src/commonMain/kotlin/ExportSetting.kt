package org.openrndr.figma.rest

import kotlinx.serialization.Serializable


@Serializable
data class ExportSetting(val suffix: String, val format: String, val constraint:Constraint)