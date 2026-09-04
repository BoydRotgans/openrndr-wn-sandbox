package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

/**
 * Metadata for character formatting
 */
@Serializable
data class TypeStyle(
    val fontFamily: String = "",
    val fontPostScriptName: String? = null,
    val paragraphSpacing: Double = 0.0,
    val paragraphIndent: Double = 0.0,
    val listSpacing: Double = 0.0,
    val italic: Boolean? = null,
    val fontWeight: Double = 0.0,
    val fontSize: Double? = null,
    val textCase: String = "ORIGINAL",
    val textDecoration: String = "NONE",
    val textAutoResize: String = "NONE",
    val textTruncation: String = "NONE",
    val maxLines: Double? = null,
    val textAlignHorizontal: String = "LEFT",
    val textAlignVertical: String? = null,
    val letterSpacing: Double = 0.0,
    val fills: List<Paint> = emptyList(),
    val hyperlink: Hyperlink? = null,
    val openTypeFlags: Map<String, Double> = emptyMap(),
    val lineHeightPx: Double? = null,
    val lineHeightPercent: Double? = null,
    val lineHeightPercentFontSize: Double? = null,
    val lineHeightUnit: String? = null,
)