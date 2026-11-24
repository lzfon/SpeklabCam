package com.spektralab.android.data.export

import com.spektralab.android.core.model.*
import org.json.JSONArray
import org.json.JSONObject

/** Versioned, bounded composition input. Image bytes travel only through file descriptors. */
internal data class TableCompositionHostRequest(
    val request: TableExportRequest,
    val sourceUris: List<String>,
) {
    fun toJson(): String {
        val options = request.options
        return JSONObject().put("schema", 1)
            .put("scene", request.scene.toJson())
            .put("options", JSONObject()
                .put("mode", options.mode.name).put("paper", options.paper.name).put("format", options.format.name)
                .put("long_edge", options.longEdge).put("transparent", options.transparent).put("title", options.title)
                .put("date", options.date).put("numbered", options.numbered).put("shadows", options.shadows)
                .put("latest_edits", options.latestEdits).put("viewport_enabled", options.viewport))
            .put("viewport", request.viewport?.let {
                JSONObject().put("left", it.left).put("top", it.top).put("right", it.right).put("bottom", it.bottom)
            } ?: JSONObject.NULL)
            .put("transmission_fade", request.transmissionFade.toDouble())
            .put("sources", JSONArray(sourceUris)).toString().also {
                require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "TABLE_EXPORT_CAPACITY" }
            }
    }

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024

        fun freeze(request: TableExportRequest, sourceKeys: List<String>): TableCompositionHostRequest {
            val aliases = sourceKeys.withIndex().associate { (index, key) -> key to "file:///stage/$index" }
            val scene = request.scene.copy(items = request.scene.items.map { item ->
                item.copy(source = TableSource(aliases[item.source.uri] ?: "file:///outside", ""))
            })
            return TableCompositionHostRequest(request.copy(scene = scene), sourceKeys.map(aliases::getValue))
        }

        fun fromJson(raw: String): TableCompositionHostRequest {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "TABLE_EXPORT_CAPACITY" }
            val root = JSONObject(raw)
            require(root.keys().asSequence().all { it in setOf("schema", "scene", "options", "viewport", "transmission_fade", "sources") })
            require(root.get("schema") is Number && root.getInt("schema") == 1 && root.getDouble("schema") == 1.0)
            val values = root.getJSONObject("options")
            require(values.keys().asSequence().all { it in setOf(
                "mode", "paper", "format", "long_edge", "transparent", "title", "date", "numbered", "shadows",
                "latest_edits", "viewport_enabled",
            ) })
            fun bool(key: String) = values.get(key) as? Boolean ?: error("TABLE_EXPORT_SOURCE")
            val options = TableExportOptions(
                mode = TableOutputMode.valueOf(values.getString("mode")),
                paper = TablePaper.valueOf(values.getString("paper")),
                format = TableOutputFormat.valueOf(values.getString("format")),
                longEdge = values.getInt("long_edge"), transparent = bool("transparent"), title = values.getString("title"),
                date = values.getString("date"), numbered = bool("numbered"), shadows = bool("shadows"),
                latestEdits = bool("latest_edits"), viewport = bool("viewport_enabled"),
            )
            val viewport = when (val rawViewport = root.get("viewport")) {
                JSONObject.NULL -> null
                is JSONObject -> {
                    require(rawViewport.keys().asSequence().all { it in setOf("left", "top", "right", "bottom") })
                    fun coordinate(key: String): Float {
                        require(rawViewport.get(key) is Number)
                        return rawViewport.getDouble(key).toFloat()
                    }
                    TableBox(coordinate("left"), coordinate("top"), coordinate("right"), coordinate("bottom"))
                }
                else -> error("TABLE_EXPORT_SOURCE")
            }
            require(root.get("transmission_fade") is Number)
            val request = TableExportRequest(LightTableScene.fromJson(root.getString("scene")), options, viewport,
                root.getDouble("transmission_fade").toFloat())
            val sources = root.getJSONArray("sources")
            require(sources.length() in 1..200)
            val sourceUris = List(sources.length()) { index -> sources.getString(index) }
            require(sourceUris.distinct().size == sourceUris.size) { "TABLE_EXPORT_SOURCE" }
            return TableCompositionHostRequest(request, sourceUris)
        }
    }
}
