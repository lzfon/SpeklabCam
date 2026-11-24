package com.spektralab.android.core.model

import org.json.JSONObject
import java.util.UUID

internal enum class ExportArtifact { FINISHED, DIGITAL_INTERMEDIATE }

/** Owned, versioned request. No live editor state or pixel buffers cross the host boundary. */
internal data class ExportFrameRequest(
    val jobId: String,
    val sourceHash: String,
    val sourceName: String,
    val raw: Boolean,
    val edit: EditSnapshot,
    val options: ExportOptions,
    val decoderIdentity: String,
    val rendererIdentity: String,
    val realisation: ExportRealisation = ExportRealisation.fresh(edit.toParams()),
    val submittedAt: Long = System.currentTimeMillis(),
    val namingDate: String = java.time.LocalDate.now().toString(),
    val appIdentity: String = "",
    val artifact: ExportArtifact = ExportArtifact.FINISHED,
) {
    init {
        require(UUID.fromString(jobId).toString() == jobId)
        require(sourceHash.matches(Regex("[a-f0-9]{64}")))
        require(sourceName.length <= 512)
        require(options.resolution >= 0)
        require(submittedAt >= 0 && (namingDate.isEmpty() || java.time.LocalDate.parse(namingDate).toString() == namingDate))
        require(appIdentity.isEmpty() || appIdentity.matches(Regex("[a-f0-9]{64}")))
        require(artifact != ExportArtifact.DIGITAL_INTERMEDIATE ||
            options.format == "TIFF" && options.colorSpace == "sRGB" && options.customSize == null && !options.tiffDeflate)
    }

    fun toJson(): String = JSONObject().apply {
        put("schema", 6); put("job_id", jobId); put("source_sha256", sourceHash)
        put("artifact", artifact.name)
        put("submitted_at", submittedAt); put("naming_date", namingDate)
        put("app_identity", appIdentity)
        put("source_name", sourceName); put("raw", raw); put("edit", JSONObject(edit.json))
        put("decoder", decoderIdentity); put("renderer", rendererIdentity)
        put("random_version", 1); put("film_seed", realisation.film); put("print_seed", realisation.print)
        put("options", JSONObject().apply {
            put("format", options.format); put("resolution", options.resolution)
            put("color_space", options.colorSpace); put("jpeg_quality", options.jpegQuality)
            put("keep_capture", options.keepCapture); put("keep_location", options.keepLocation)
            put("correct_lens", options.correctLens); put("tiff_deflate", options.tiffDeflate)
            options.customSize?.let { put("custom_size", it.toJson()) }
            put("naming", options.naming.toJson())
        })
    }.toString()

    companion object {
        fun fromJson(value: String): ExportFrameRequest {
            require(value.toByteArray(Charsets.UTF_8).size <= 128 * 1024) { "Export request exceeds IPC limit" }
            val json = JSONObject(value)
            val schema = json.getInt("schema")
            require(json.get("schema") is Number && json.getDouble("schema") == schema.toDouble())
            require(schema in 1..6) { "Unsupported export request" }
            require(schema == 1 || json.getInt("random_version") == 1) { "Unsupported random realisation" }
            val options = json.getJSONObject("options")
            for (key in listOf("custom_size", "naming"))
                require(!options.has(key) || options.get(key) is JSONObject) { "Invalid export option: $key" }
            require(!options.has("tiff_deflate") || options.get("tiff_deflate") is Boolean)
            val edit = EditSnapshot.fromJson(json.getJSONObject("edit"))
            return ExportFrameRequest(json.getString("job_id"), json.getString("source_sha256"),
                json.getString("source_name"), json.getBoolean("raw"),
                edit,
                ExportOptions(options.getString("format"), options.getInt("resolution"),
                    if (schema < 4 && options.getString("format") != "TIFF") "sRGB" else options.getString("color_space"),
                    options.getInt("jpeg_quality"), options.getBoolean("keep_capture"), options.getBoolean("keep_location"),
                    options.getBoolean("correct_lens"),
                    options.optJSONObject("custom_size")?.let(ExportSize::fromJson),
                    options.optJSONObject("naming")?.let(ExportNaming::fromJson) ?: ExportNaming(), options.optBoolean("tiff_deflate",false)),
                json.getString("decoder"), json.getString("renderer"),
                if (json.has("film_seed")) ExportRealisation(json.getLong("film_seed"), json.getLong("print_seed"))
                else ExportRealisation.fresh(edit.toParams()),
                if(schema>=5)json.getLong("submitted_at").also { require(json.get("submitted_at") is Number && json.getDouble("submitted_at")==it.toDouble()) }else 0L,
                if(schema>=5)json.getString("naming_date")else "",if(schema>=5)json.getString("app_identity")else "",
                if(schema>=6)ExportArtifact.valueOf(json.getString("artifact"))else ExportArtifact.FINISHED)
        }
    }
}
