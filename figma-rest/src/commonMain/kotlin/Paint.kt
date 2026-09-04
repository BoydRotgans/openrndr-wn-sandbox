package org.openrndr.figma.rest

import kotlinx.serialization.Serializable


@Serializable
data class Paint(val type: String,
                 val visible: Boolean = true,
                 val opacity: Double = 1.0,
                 val color: Color = Color(0.0, 0.0, 0.0, 0.0),
                 val blendMode: BlendMode = "NORMAL",
                 val gradientHandlePositions: List<Vector> = emptyList(),
                 val gradientStops: List<ColorStop> = emptyList(),
                 val scaleMode: String? = null,
                 val imageTransform: Transform? = null,
                 val scalingFactor: Double? = null,
                 val rotation: Double? = null,
                 val imageRef: String? = null,
                 val filters: ImageFilters? = null,
                 val gifRef: String? = null,
                 val boundVariables: Map<String, VariableAlias> = emptyMap()
)