package com.spektralab.android.core.model

import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A limit on the finished raster, after geometry and film borders. Never enlarges it. */
data class ExportSize(val kind: Kind, val value: Double) {
    enum class Kind { LONG_EDGE, SHORT_EDGE, MEGAPIXELS }

    init {
        require(value.isFinite()) { "INVALID_EXPORT_SIZE" }
        require(if (kind == Kind.MEGAPIXELS) value in .01..200.0
            else value in 1.0..65535.0 && value == value.toInt().toDouble()) { "INVALID_EXPORT_SIZE" }
    }

    fun dimensions(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = when (kind) {
            Kind.LONG_EDGE -> value / maxOf(width, height)
            Kind.SHORT_EDGE -> value / minOf(width, height)
            Kind.MEGAPIXELS -> sqrt(value * 1_000_000.0 / (width.toDouble() * height))
        }.coerceAtMost(1.0)
        return (width * scale).roundToInt().coerceIn(1, width) to
            (height * scale).roundToInt().coerceIn(1, height)
    }

    fun toJson(): JSONObject = JSONObject().put("version", 1).put("kind", kind.name).put("value", value)

    companion object {
        fun fromJson(json: JSONObject): ExportSize {
            require(json.getDouble("version") == 1.0) { "UNSUPPORTED_EXPORT_SIZE" }
            return ExportSize(Kind.valueOf(json.getString("kind")), json.getDouble("value"))
        }
    }
}
