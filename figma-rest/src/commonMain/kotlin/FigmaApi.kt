package org.openrndr.figma.rest

import kotlinx.serialization.json.Json
import java.io.File

class FigmaApi(val fetcher: (String) -> String) {
    fun me(): User = Json.decodeFromString<User>(fetcher("/v1/me"))
    fun teamProjects(teamId: String) = Json.decodeFromString<TeamProjects>(fetcher("/v1/teams/$teamId/projects"))
    fun projectFiles(projectId: String) = Json.decodeFromString<ProjectFiles>(fetcher("/v1/projects/$projectId/files"))

    private val json = Json {
        ignoreUnknownKeys = true
        // Figma sends an explicit null for optional numeric fields it has no value for —
        // "rotation": null on a BOOLEAN_OPERATION is the one that turns up first. Those are
        // declared non-null with a default here, so without this the whole document fails to
        // parse. Coercing puts the declared default back and covers the whole family rather
        // than the one field that happens to be in the current document.
        coerceInputValues = true
    }

    fun file(
        key: String, version: String? = null, ids: List<String> = emptyList(), depth: Int? = null,
        geometry: String? = null,
        pluginData: String? = null,
        branchData: Boolean = false
    ): Files {

        val qVersion = if (!version.isNullOrBlank()) "version=$version" else null
        val qIds = if (ids.isNotEmpty()) "ids=${ids.joinToString(",")}" else null
        val qDepth = if (depth != null) "depth=$depth" else null
        val qGeometry = if (!geometry.isNullOrBlank()) "geometry=$geometry" else null
        val qPluginData = if (!pluginData.isNullOrBlank()) "plugin_data=$pluginData" else null
        val qBranchData = if (branchData) "branch_data=true" else null

        val qs = listOfNotNull(qVersion, qIds, qDepth, qGeometry, qPluginData, qBranchData).joinToString("&")
        val q = if (qs.isNotBlank()) "?$qs" else ""
        val result = fetcher("/v1/files/$key$q")

        File("data/template.json").writeText(result)

        return json.decodeFromString<Files>(result)
    }

    fun images(
        key: String,
        ids: List<String>,
        scale: Double? = null,
        format: String? = null,
        svgOutlineText: Boolean? = null,
        svgIncludeId: Boolean? = null,
        svgSimplifyStroke: Boolean? = null,
        contentsOnly: Boolean? = null,
        useAbsoluteBounds: Boolean? = null,
        version: String? = null
    ): Images {

        val qs = listOfNotNull(
            if (ids.isNotEmpty()) "ids=${ids.joinToString(",")}" else null,
            if (scale != null) "scale=${scale}" else null,
            if (format != null) "format=${format}" else null,
            if (svgOutlineText != null) "svg_outline_text=${if (svgOutlineText) "true" else "false"}" else null,
            if (svgIncludeId != null) "svg_include_id=${if (svgIncludeId) "true" else "false"}" else null,
            if (svgSimplifyStroke != null) "svg_simplify_stroke=${if (svgSimplifyStroke) "true" else "false"}" else null,
            if (contentsOnly != null) "contents_only=${if (contentsOnly) "true" else "false"}" else null,
            if (useAbsoluteBounds != null) "use_absolute_bounds=${if (useAbsoluteBounds) "true" else "false"}" else null,
            if (version != null) "version=$version" else null
        ).joinToString("&")
        val q = if (!qs.isNullOrBlank()) "?$qs" else ""
        return Json.decodeFromString<Images>(fetcher("/v1/images/$key$q"))
    }
}