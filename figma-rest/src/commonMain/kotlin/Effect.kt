package org.openrndr.figma.rest

import kotlinx.serialization.Serializable

/**
 * A visual effect such as a shadow or blur
 * @param type Type of effect as a string enum
 * * INNER_SHADOW
 * * DROP_SHADOW
 * * LAYER_BLUR
 * * BACKGROUND_BLUR
 * @param visible Is the effect active?
 * @param radius Radius of the blur effect (applies to shadows as well)
 * @param color The color of the shadow
 * @param blendMode Blend mode of the shadow
 * @param offset How far the shadow is projected in the x and y directions
 * @param spread  How far the shadow spreads
 * @param showShadowBehindNode Whether to show the shadow behind translucent or transparent pixels (applies only to drop shadows)
 * @param boundVariables A mapping of field to the VariableAlias of the bound variable.
 */
@Serializable
data class Effect(
    val type: String,
    val visible: Boolean,
    val radius: Double,
    val color: Color = Color(1.0, 1.0, 1.0, 1.0),
    val blendMode: BlendMode? = null,
    val offset: Vector = Vector(0.0, 0.0),
    val spread: Double = 0.0,
    val showShadowBehindNode: Boolean = false,
    val boundVariables: Map<String, VariableAlias> = emptyMap()
)