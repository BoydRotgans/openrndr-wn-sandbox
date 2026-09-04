package org.openrndr.figma.rest

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
sealed class Node() {
    var id: String = ""
    var name: String = ""
    var visible: Boolean = true
    var rotation: Double = 0.0
    var blendMode: BlendMode? = null
    var absoluteBoundingBox: org.openrndr.figma.rest.Rectangle? = null
    var absoluteRenderBounds: org.openrndr.figma.rest.Rectangle? = null
    var constraints: LayoutConstraint? = null
    var fills: List<Paint> = emptyList()
    var strokes: List<Paint> = emptyList()
    var strokeWeight: Double = 0.0
    var strokeAlign: String = ""
    var strokeDashes: List<Double> = emptyList()
    var effects: List<Effect> = emptyList()
    var locked: Boolean = false
    var preserveRatio: Boolean = false
    var cornerRadius: Double = 0.0
    var rectangleCornerRadii: List<Double> = List(4) { cornerRadius }
    var cornerSmoothing = 0.0
    var layoutSizingHorizontal: String = ""
    var layoutSizingVertical: String = ""
    var exportSettings: List<ExportSetting> = emptyList()
    var layoutGrow: Double = 0.0
    var maskType: String? = null
    // Figma sends interaction objects here, not strings. JsonElement keeps the data
    // without needing the whole prototyping schema modelled.
    var interactions: List<JsonElement> = listOf()

    @Contextual
    var pluginData: Any? = null

    @Contextual
    var sharedPluginData: Any? = null

    val scrollBehavior: String? = null

    /**
     * Document node
     */
    @Serializable
    @SerialName("DOCUMENT")
    class Document : Node(), ParentNode {
        override var children: List<Node> = emptyList()
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Document
            return children == other.children
        }

        override fun hashCode(): Int {
            return super.hashCode() * 31 + children.hashCode()
        }
    }

    /**
     * Canvas node
     */
    @Serializable
    @SerialName("BOOLEAN_OPERATION")
    class BooleanOperation: Node() {
        var children: List<Node> = emptyList()
        var booleanOperation: String = ""

    }

    /**
     * Canvas node
     */
    @Serializable
    @SerialName("CANVAS")
    class Canvas() : Node(), ParentNode {
        override var children: List<Node> = emptyList()
        var backgroundColor: Color = Color(0.0, 0.0, 0.0, 0.0)

        @Deprecated("This is deprecated with the introduction of multiple flows. Please use the flowStartingPoints field.")
        var prototypeStartNodeID: String? = null
        var flowStartingPoints: List<FlowStartingPoint> = emptyList()
        var prototypeDevice: PrototypeDevice? = null
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            if (!super.equals(other)) return false

            other as Canvas

            if (children != other.children) return false
            if (backgroundColor != other.backgroundColor) return false
            if (prototypeStartNodeID != other.prototypeStartNodeID) return false
            if (flowStartingPoints != other.flowStartingPoints) return false
            if (prototypeDevice != other.prototypeDevice) return false
            if (exportSettings != other.exportSettings) return false

            return true
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + children.hashCode()
            result = 31 * result + backgroundColor.hashCode()
            result = 31 * result + (prototypeStartNodeID?.hashCode() ?: 0)
            result = 31 * result + flowStartingPoints.hashCode()
            result = 31 * result + (prototypeDevice?.hashCode() ?: 0)
            result = 31 * result + exportSettings.hashCode()
            return result
        }
    }

    /**
     * Frame node
     */
    @Serializable
    @SerialName("FRAME")
    open class Frame() : Node(), ParentNode {

        override var children: List<Node> = emptyList()
        @Deprecated("This is deprecated, as backgrounds for frames are now in the fills field.")
        var background: List<Paint> = emptyList()

        @Deprecated("This is deprecated, as backgrounds for frames are now in the fills field.")
        var backgroundColor: Color? = null
        var layoutAlign: String = ""
        var transitionNodeID: String? = null
        var transitionDuration: Double? = null
        var transitionEasing: EasingType? = null
        var opacity: Double = 1.0
        var size: org.openrndr.figma.rest.Vector = Vector(0.0, 0.0)
        var minWidth: Double? = null
        var maxWidth: Double? = null
        var minHeight: Double? = null
        var maxHeight: Double? = null
        var relativeTransform: Transform = emptyList()
        var clipsContent: Boolean = false
        var layoutMode: String = "NONE"
        var layoutWrap: String = "NO_WRAP"
        var primaryAxisSizingMode: String = "AUTO"
        var primaryAxisAlignItems: String = "MIN"
        var counterAxisAlignItems: String = "MIN"
        var counterAxisAlignContent: String = "AUTO"
        var counterAxisSizingMode: String = "AUTO"
        var paddingLeft: Double = 0.0
        var paddingRight: Double = 0.0
        var paddingTop: Double = 0.0
        var paddingBottom: Double = 0.0
        var horizontalPadding: Double = 0.0
        var verticalPadding: Double = 0.0
        var itemSpacing: Double = 0.0
        var counterAxisSpacing: Double = 0.0
        var layoutPositioning: String = "AUTO"
        var itemReverseZIndex: Boolean = false
        var strokesIncludedInLayoutBoolean: Boolean = false
        var layoutGrids: List<LayoutGrid> = emptyList()
        var overflowDirection: String = "NONE"
        var isMask: Boolean = false

        @Deprecated("This property is deprecated; please use the maskType field instead (isMaskOutline=true corresponds to maskType=\"VECTOR\").")
        var isMaskOutline: Boolean = false
        var styles: Map<StyleType, String> = emptyMap()


        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            if (!super.equals(other)) return false

            other as Frame

            if (children != other.children) return false
            if (locked != other.locked) return false
            if (background != other.background) return false
            if (backgroundColor != other.backgroundColor) return false
            if (fills != other.fills) return false
            if (strokes != other.strokes) return false
            if (strokeWeight != other.strokeWeight) return false
            if (strokeAlign != other.strokeAlign) return false
            if (strokeDashes != other.strokeDashes) return false
            if (cornerRadius != other.cornerRadius) return false
            if (rectangleCornerRadii != other.rectangleCornerRadii) return false
            if (cornerSmoothing != other.cornerSmoothing) return false
            if (exportSettings != other.exportSettings) return false
            if (blendMode != other.blendMode) return false
            if (preserveRatio != other.preserveRatio) return false
            if (constraints != other.constraints) return false
            if (layoutAlign != other.layoutAlign) return false
            if (transitionNodeID != other.transitionNodeID) return false
            if (transitionDuration != other.transitionDuration) return false
            if (transitionEasing != other.transitionEasing) return false
            if (opacity != other.opacity) return false
            if (absoluteBoundingBox != other.absoluteBoundingBox) return false
            if (absoluteRenderBounds != other.absoluteRenderBounds) return false
            if (size != other.size) return false
            if (minWidth != other.minWidth) return false
            if (maxWidth != other.maxWidth) return false
            if (minHeight != other.minHeight) return false
            if (maxHeight != other.maxHeight) return false
            if (relativeTransform != other.relativeTransform) return false
            if (clipsContent != other.clipsContent) return false
            if (layoutMode != other.layoutMode) return false
            if (layoutSizingHorizontal != other.layoutSizingHorizontal) return false
            if (layoutSizingVertical != other.layoutSizingVertical) return false
            if (layoutWrap != other.layoutWrap) return false
            if (primaryAxisSizingMode != other.primaryAxisSizingMode) return false
            if (primaryAxisAlignItems != other.primaryAxisAlignItems) return false
            if (counterAxisAlignItems != other.counterAxisAlignItems) return false
            if (counterAxisAlignContent != other.counterAxisAlignContent) return false
            if (paddingLeft != other.paddingLeft) return false
            if (paddingRight != other.paddingRight) return false
            if (paddingTop != other.paddingTop) return false
            if (paddingBottom != other.paddingBottom) return false
            if (horizontalPadding != other.horizontalPadding) return false
            if (verticalPadding != other.verticalPadding) return false
            if (itemSpacing != other.itemSpacing) return false
            if (counterAxisSpacing != other.counterAxisSpacing) return false
            if (layoutPositioning != other.layoutPositioning) return false
            if (itemReverseZIndex != other.itemReverseZIndex) return false
            if (strokesIncludedInLayoutBoolean != other.strokesIncludedInLayoutBoolean) return false
            if (layoutGrids != other.layoutGrids) return false
            if (overflowDirection != other.overflowDirection) return false
            if (effects != other.effects) return false
            if (isMask != other.isMask) return false
            if (isMaskOutline != other.isMaskOutline) return false
            if (maskType != other.maskType) return false
            if (styles != other.styles) return false

            return true
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + children.hashCode()
            result = 31 * result + locked.hashCode()
            result = 31 * result + (background?.hashCode() ?: 0)
            result = 31 * result + (backgroundColor?.hashCode() ?: 0)
            result = 31 * result + fills.hashCode()
            result = 31 * result + strokes.hashCode()
            result = 31 * result + strokeWeight.hashCode()
            result = 31 * result + strokeAlign.hashCode()
            result = 31 * result + strokeDashes.hashCode()
            result = 31 * result + cornerRadius.hashCode()
            result = 31 * result + rectangleCornerRadii.hashCode()
            result = 31 * result + cornerSmoothing.hashCode()
            result = 31 * result + exportSettings.hashCode()
            result = 31 * result + (blendMode?.hashCode() ?: 0)
            result = 31 * result + preserveRatio.hashCode()
            result = 31 * result + (constraints?.hashCode() ?: 0)
            result = 31 * result + layoutAlign.hashCode()
            result = 31 * result + (transitionNodeID?.hashCode() ?: 0)
            result = 31 * result + (transitionDuration?.hashCode() ?: 0)
            result = 31 * result + (transitionEasing?.hashCode() ?: 0)
            result = 31 * result + opacity.hashCode()
            result = 31 * result + (absoluteBoundingBox?.hashCode() ?: 0)
            result = 31 * result + (absoluteRenderBounds?.hashCode() ?: 0)
            result = 31 * result + size.hashCode()
            result = 31 * result + (minWidth?.hashCode() ?: 0)
            result = 31 * result + (maxWidth?.hashCode() ?: 0)
            result = 31 * result + (minHeight?.hashCode() ?: 0)
            result = 31 * result + (maxHeight?.hashCode() ?: 0)
            result = 31 * result + relativeTransform.hashCode()
            result = 31 * result + clipsContent.hashCode()
            result = 31 * result + layoutMode.hashCode()
            result = 31 * result + layoutSizingHorizontal.hashCode()
            result = 31 * result + layoutSizingVertical.hashCode()
            result = 31 * result + layoutWrap.hashCode()
            result = 31 * result + primaryAxisSizingMode.hashCode()
            result = 31 * result + primaryAxisAlignItems.hashCode()
            result = 31 * result + counterAxisAlignItems.hashCode()
            result = 31 * result + counterAxisAlignContent.hashCode()
            result = 31 * result + paddingLeft.hashCode()
            result = 31 * result + paddingRight.hashCode()
            result = 31 * result + paddingTop.hashCode()
            result = 31 * result + paddingBottom.hashCode()
            result = 31 * result + horizontalPadding.hashCode()
            result = 31 * result + verticalPadding.hashCode()
            result = 31 * result + itemSpacing.hashCode()
            result = 31 * result + counterAxisSpacing.hashCode()
            result = 31 * result + layoutPositioning.hashCode()
            result = 31 * result + itemReverseZIndex.hashCode()
            result = 31 * result + strokesIncludedInLayoutBoolean.hashCode()
            result = 31 * result + layoutGrids.hashCode()
            result = 31 * result + overflowDirection.hashCode()
            result = 31 * result + effects.hashCode()
            result = 31 * result + isMask.hashCode()
            result = 31 * result + isMaskOutline.hashCode()
            result = 31 * result + (maskType?.hashCode() ?: 0)
            result = 31 * result + styles.hashCode()
            return result
        }
    }

    /**
     * Group node
     */
    @Serializable
    @SerialName("GROUP")
    class Group : Node(), ParentNode {

        override var children: List<Node> = emptyList()
        @Deprecated("This is deprecated, as backgrounds for frames are now in the fills field.")
        var background: List<Paint> = emptyList()

        @Deprecated("This is deprecated, as backgrounds for frames are now in the fills field.")
        var backgroundColor: Color? = null
        var layoutAlign: String = ""
        var transitionNodeID: String? = null
        var transitionDuration: Double? = null
        var transitionEasing: EasingType? = null
        var opacity: Double = 1.0
        var size: org.openrndr.figma.rest.Vector = Vector(0.0, 0.0)
        var minWidth: Double? = null
        var maxWidth: Double? = null
        var minHeight: Double? = null
        var maxHeight: Double? = null
        var relativeTransform: Transform = emptyList()
        var clipsContent: Boolean = false
        var layoutMode: String = "NONE"
        var layoutWrap: String = "NO_WRAP"
        var primaryAxisSizingMode: String = "AUTO"
        var primaryAxisAlignItems: String = "MIN"
        var counterAxisAlignItems: String = "MIN"
        var counterAxisAlignContent: String = "AUTO"
        var paddingLeft: Double = 0.0
        var paddingRight: Double = 0.0
        var paddingTop: Double = 0.0
        var paddingBottom: Double = 0.0
        var horizontalPadding: Double = 0.0
        var verticalPadding: Double = 0.0
        var itemSpacing: Double = 0.0
        var counterAxisSpacing: Double = 0.0
        var layoutPositioning: String = "AUTO"
        var itemReverseZIndex: Boolean = false
        var strokesIncludedInLayoutBoolean: Boolean = false
        var layoutGrids: List<LayoutGrid> = emptyList()
        var overflowDirection: String = "NONE"
        var isMask: Boolean = false

        @Deprecated("This property is deprecated; please use the maskType field instead (isMaskOutline=true corresponds to maskType=\"VECTOR\").")
        var isMaskOutline: Boolean = false
        var styles: Map<StyleType, String> = emptyMap()


        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            if (!super.equals(other)) return false

            other as Frame

            if (children != other.children) return false
            if (locked != other.locked) return false
            if (background != other.background) return false
            if (backgroundColor != other.backgroundColor) return false
            if (fills != other.fills) return false
            if (strokes != other.strokes) return false
            if (strokeWeight != other.strokeWeight) return false
            if (strokeAlign != other.strokeAlign) return false
            if (strokeDashes != other.strokeDashes) return false
            if (cornerRadius != other.cornerRadius) return false
            if (rectangleCornerRadii != other.rectangleCornerRadii) return false
            if (cornerSmoothing != other.cornerSmoothing) return false
            if (exportSettings != other.exportSettings) return false
            if (blendMode != other.blendMode) return false
            if (preserveRatio != other.preserveRatio) return false
            if (constraints != other.constraints) return false
            if (layoutAlign != other.layoutAlign) return false
            if (transitionNodeID != other.transitionNodeID) return false
            if (transitionDuration != other.transitionDuration) return false
            if (transitionEasing != other.transitionEasing) return false
            if (opacity != other.opacity) return false
            if (absoluteBoundingBox != other.absoluteBoundingBox) return false
            if (absoluteRenderBounds != other.absoluteRenderBounds) return false
            if (size != other.size) return false
            if (minWidth != other.minWidth) return false
            if (maxWidth != other.maxWidth) return false
            if (minHeight != other.minHeight) return false
            if (maxHeight != other.maxHeight) return false
            if (relativeTransform != other.relativeTransform) return false
            if (clipsContent != other.clipsContent) return false
            if (layoutMode != other.layoutMode) return false
            if (layoutSizingHorizontal != other.layoutSizingHorizontal) return false
            if (layoutSizingVertical != other.layoutSizingVertical) return false
            if (layoutWrap != other.layoutWrap) return false
            if (primaryAxisSizingMode != other.primaryAxisSizingMode) return false
            if (primaryAxisAlignItems != other.primaryAxisAlignItems) return false
            if (counterAxisAlignItems != other.counterAxisAlignItems) return false
            if (counterAxisAlignContent != other.counterAxisAlignContent) return false
            if (paddingLeft != other.paddingLeft) return false
            if (paddingRight != other.paddingRight) return false
            if (paddingTop != other.paddingTop) return false
            if (paddingBottom != other.paddingBottom) return false
            if (horizontalPadding != other.horizontalPadding) return false
            if (verticalPadding != other.verticalPadding) return false
            if (itemSpacing != other.itemSpacing) return false
            if (counterAxisSpacing != other.counterAxisSpacing) return false
            if (layoutPositioning != other.layoutPositioning) return false
            if (itemReverseZIndex != other.itemReverseZIndex) return false
            if (strokesIncludedInLayoutBoolean != other.strokesIncludedInLayoutBoolean) return false
            if (layoutGrids != other.layoutGrids) return false
            if (overflowDirection != other.overflowDirection) return false
            if (effects != other.effects) return false
            if (isMask != other.isMask) return false
            if (isMaskOutline != other.isMaskOutline) return false
            if (maskType != other.maskType) return false
            if (styles != other.styles) return false

            return true
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + children.hashCode()
            result = 31 * result + locked.hashCode()
            result = 31 * result + (background?.hashCode() ?: 0)
            result = 31 * result + (backgroundColor?.hashCode() ?: 0)
            result = 31 * result + fills.hashCode()
            result = 31 * result + strokes.hashCode()
            result = 31 * result + strokeWeight.hashCode()
            result = 31 * result + strokeAlign.hashCode()
            result = 31 * result + strokeDashes.hashCode()
            result = 31 * result + cornerRadius.hashCode()
            result = 31 * result + rectangleCornerRadii.hashCode()
            result = 31 * result + cornerSmoothing.hashCode()
            result = 31 * result + exportSettings.hashCode()
            result = 31 * result + (blendMode?.hashCode() ?: 0)
            result = 31 * result + preserveRatio.hashCode()
            result = 31 * result + (constraints?.hashCode() ?: 0)
            result = 31 * result + layoutAlign.hashCode()
            result = 31 * result + (transitionNodeID?.hashCode() ?: 0)
            result = 31 * result + (transitionDuration?.hashCode() ?: 0)
            result = 31 * result + (transitionEasing?.hashCode() ?: 0)
            result = 31 * result + opacity.hashCode()
            result = 31 * result + (absoluteBoundingBox?.hashCode() ?: 0)
            result = 31 * result + (absoluteRenderBounds?.hashCode() ?: 0)
            result = 31 * result + size.hashCode()
            result = 31 * result + (minWidth?.hashCode() ?: 0)
            result = 31 * result + (maxWidth?.hashCode() ?: 0)
            result = 31 * result + (minHeight?.hashCode() ?: 0)
            result = 31 * result + (maxHeight?.hashCode() ?: 0)
            result = 31 * result + relativeTransform.hashCode()
            result = 31 * result + clipsContent.hashCode()
            result = 31 * result + layoutMode.hashCode()
            result = 31 * result + layoutSizingHorizontal.hashCode()
            result = 31 * result + layoutSizingVertical.hashCode()
            result = 31 * result + layoutWrap.hashCode()
            result = 31 * result + primaryAxisSizingMode.hashCode()
            result = 31 * result + primaryAxisAlignItems.hashCode()
            result = 31 * result + counterAxisAlignItems.hashCode()
            result = 31 * result + counterAxisAlignContent.hashCode()
            result = 31 * result + paddingLeft.hashCode()
            result = 31 * result + paddingRight.hashCode()
            result = 31 * result + paddingTop.hashCode()
            result = 31 * result + paddingBottom.hashCode()
            result = 31 * result + horizontalPadding.hashCode()
            result = 31 * result + verticalPadding.hashCode()
            result = 31 * result + itemSpacing.hashCode()
            result = 31 * result + counterAxisSpacing.hashCode()
            result = 31 * result + layoutPositioning.hashCode()
            result = 31 * result + itemReverseZIndex.hashCode()
            result = 31 * result + strokesIncludedInLayoutBoolean.hashCode()
            result = 31 * result + layoutGrids.hashCode()
            result = 31 * result + overflowDirection.hashCode()
            result = 31 * result + effects.hashCode()
            result = 31 * result + isMask.hashCode()
            result = 31 * result + isMaskOutline.hashCode()
            result = 31 * result + (maskType?.hashCode() ?: 0)
            result = 31 * result + styles.hashCode()
            return result
        }
    }

    /**
     * Section node
     */
    @Serializable
    @SerialName("SECTION")
    class Section : Node(), ParentNode {
        var sectionContentsHidden: Boolean = false

        @Contextual
        var devStatus: Any? = null
        override var children: List<Node> = emptyList()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            if (!super.equals(other)) return false

            other as Section

            if (sectionContentsHidden != other.sectionContentsHidden) return false
            if (devStatus != other.devStatus) return false
            if (fills != other.fills) return false
            if (strokes != other.strokes) return false
            if (strokeWeight != other.strokeWeight) return false
            if (strokeAlign != other.strokeAlign) return false
            if (children != other.children) return false
            if (absoluteBoundingBox != other.absoluteBoundingBox) return false
            if (absoluteRenderBounds != other.absoluteRenderBounds) return false

            return true
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + sectionContentsHidden.hashCode()
            result = 31 * result + (devStatus?.hashCode() ?: 0)
            result = 31 * result + fills.hashCode()
            result = 31 * result + strokes.hashCode()
            result = 31 * result + strokeWeight.hashCode()
            result = 31 * result + strokeAlign.hashCode()
            result = 31 * result + children.hashCode()
            result = 31 * result + (absoluteBoundingBox?.hashCode() ?: 0)
            result = 31 * result + (absoluteRenderBounds?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * Component node
     */

    @Serializable
    @SerialName("COMPONENT")
    class Component : Node(), ParentNode {
        override var children: List<Node> = emptyList()
        var componentId: String = ""
    }

    @Serializable
    @SerialName("COMPONENT_SET")
    class ComponentSet : Node(), ParentNode {
        override var children: List<Node> = emptyList()
    }

    @Serializable
    data class ComponentSets(
        val file_key: String = "",
        val node_id: String = "",
        val thumnbnail_url: String? = null,  // note: typo is in the Figma API itself
        val created_at: String = "",
        val updated_at: String = "",
        val user: User? = null
    )

    @Serializable
    @SerialName("INSTANCE")
    class Instance : Node(), ParentNode {
        override var children: List<Node> = emptyList()
        var componentId: String = ""
        var isExposedInstance: Boolean = false
        var exposedInstances: List<String> = emptyList()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            if (!super.equals(other)) return false
            other as Instance
            if (children != other.children) return false
            if (componentId != other.componentId) return false
            if (isExposedInstance != other.isExposedInstance) return false
            if (exposedInstances != other.exposedInstances) return false
            return true
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + children.hashCode()
            result = 31 * result + componentId.hashCode()
            result = 31 * result + isExposedInstance.hashCode()
            result = 31 * result + exposedInstances.hashCode()
            return result
        }
    }

    @Serializable
    sealed class VectorBase : Node() {
        val boundVariables: Map<String, List<VariableAlias>> = emptyMap()
        var layoutAlign: String = ""
        var transitionNodeID: String? = null
        var transitionDuration: Double? = null
        var transitionEasing: EasingType? = null
        var opacity: Double = 1.0
        var size: org.openrndr.figma.rest.Vector = Vector(0.0, 0.0)
        var relativeTransform: Transform = emptyList()
        var isMask: Boolean = false
        var fillGeometry: Path? = null
        var fillOverrideTable: Map<Int, PaintOverride?> = emptyMap()
        var individualStrokeWeights: StrokeWeights? = null
        var strokeCap: String = "NONE"
        var strokeJoin: String = "MITER"
        var strokeMiterAngle: Double = 28.96
        var strokeGeometry: List<Path> = emptyList()
        var styles: Map<StyleType, String> = emptyMap()
    }

    @Serializable
    @SerialName("VECTOR")
    class Vector: VectorBase() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            if (!super.equals(other)) return false
            return true
        }
    }

    @Serializable
    @SerialName("STAR")
    class Star: VectorBase() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            if (!super.equals(other)) return false
            return true
        }
    }

    @Serializable
    @SerialName("LINE")
    class Line: VectorBase() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            if (!super.equals(other)) return false
            return true
        }
    }

    @Serializable
    @SerialName("REGULAR_POLYGON")
    class RegularPolygon: VectorBase() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            if (!super.equals(other)) return false
            return true
        }
    }

    @Serializable
    @SerialName("ELLIPSE")
    class Ellipse: VectorBase() {
        var arcData: ArcData? = null


        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            if (!super.equals(other)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + (arcData?.hashCode() ?: 0)
            return result
        }
    }


    @Serializable
    @SerialName("RECTANGLE")
    class Rectangle: VectorBase() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            if (!super.equals(other)) return false

            other as Rectangle

            if (cornerRadius != other.cornerRadius) return false
            if (rectangleCornerRadii != other.rectangleCornerRadii) return false
            if (cornerSmoothing != other.cornerSmoothing) return false

            return true
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + cornerRadius.hashCode()
            result = 31 * result + rectangleCornerRadii.hashCode()
            result = 31 * result + cornerSmoothing.hashCode()
            return result
        }
    }
    @Serializable
    @SerialName("TEXT")
    class Text: VectorBase() {
        var characters: String = ""
        var style: TypeStyle? = null
        var characterStyleOverrides: List<Int> = emptyList()
        var styleOverrideTable: Map<Int, TypeStyle> = emptyMap()
        var lineTypes: List<String> = emptyList()
        var lineIndentations: List<Int> = emptyList()
        val layoutVersion: Int? = null
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            if (!super.equals(other)) return false

            other as Text

            if (characters != other.characters) return false
            if (style != other.style) return false
            if (characterStyleOverrides != other.characterStyleOverrides) return false
            if (styleOverrideTable != other.styleOverrideTable) return false
            if (lineTypes != other.lineTypes) return false
            if (lineIndentations != other.lineIndentations) return false
            if (layoutVersion != other.layoutVersion) return false

            return true
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + characters.hashCode()
            result = 31 * result + (style?.hashCode() ?: 0)
            result = 31 * result + characterStyleOverrides.hashCode()
            result = 31 * result + styleOverrideTable.hashCode()
            result = 31 * result + lineTypes.hashCode()
            result = 31 * result + lineIndentations.hashCode()
            result = 31 * result + (layoutVersion ?: 0)
            return result
        }
    }

    @Serializable
    @SerialName("SLICE")
    // Slice had no supertype, so the polymorphic decoder could not resolve "SLICE"
    // and any document containing a slice failed to parse. exportSettings,
    // absoluteBoundingBox and absoluteRenderBounds already exist on Node.
    class Slice : Node() {
        var size: Vector? = null
        var relativeTransform: Transform = emptyList()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            if (!super.equals(other)) return false

            other as Slice

            if (size != other.size) return false
            if (relativeTransform != other.relativeTransform) return false

            return true
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + (size?.hashCode() ?: 0)
            result = 31 * result + relativeTransform.hashCode()
            return result
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Node

        if (id != other.id) return false
        if (name != other.name) return false
        if (visible != other.visible) return false
        if (rotation != other.rotation) return false
        if (pluginData != other.pluginData) return false
        if (sharedPluginData != other.sharedPluginData) return false
        if (scrollBehavior != other.scrollBehavior) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + visible.hashCode()
        result = 31 * result + rotation.hashCode()
        result = 31 * result + (pluginData?.hashCode() ?: 0)
        result = 31 * result + (sharedPluginData?.hashCode() ?: 0)
        result = 31 * result + (scrollBehavior?.hashCode() ?: 0)
        return result
    }
}