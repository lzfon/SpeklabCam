package com.spektralab.android.core.color

import java.util.Locale
import org.json.JSONObject

/** Decode-stage curves. Unit 0..1 means -8..+8 film stops, not encoded output values. */
class SceneCurves(val curves: ToneCurves = ToneCurves.IDENTITY) {
    val isIdentity: Boolean get() = curves.isIdentity
    val isDefault: Boolean get() = curves.isDefault

    /** The reference's five 129-sample rows, rounded once to five decimal places. */
    val wire: String by lazy {
        if (isIdentity) "" else CurveChannel.entries.joinToString(",") { channel ->
            (0 until SAMPLES).joinToString(",") { index ->
                String.format(Locale.ROOT, "%.5f", curves[channel].evaluate(index / (SAMPLES - 1f)))
            }
        }
    }

    fun toJson(): JSONObject = JSONObject().put("version", 1).put("stops", STOPS)
        .put("curves", curves.toJson())

    override fun equals(other: Any?): Boolean = other is SceneCurves && curves == other.curves
    override fun hashCode(): Int = curves.hashCode()

    companion object {
        const val STOPS = 8
        const val SAMPLES = 129
        val IDENTITY = SceneCurves()

        fun fromJson(json: JSONObject): SceneCurves {
            require(json.get("version") is Number && json.getDouble("version") == 1.0)
            require(json.get("stops") is Number && json.getDouble("stops") == STOPS.toDouble())
            require(json.keys().asSequence().toSet() == setOf("version", "stops", "curves"))
            val rows = json.getJSONObject("curves")
            require(rows.get("version") is Number && rows.getDouble("version") == 1.0)
            require(rows.keys().asSequence().toSet() == setOf("version", "rgb", "luma", "red", "green", "blue"))
            for (channel in CurveChannel.entries) {
                val points = rows.getJSONArray(channel.key)
                for (index in 0 until points.length()) {
                    val point = points.getJSONArray(index)
                    require(point.length() == 2 && point.get(0) is Number && point.get(1) is Number)
                }
            }
            return SceneCurves(ToneCurves.fromJson(rows))
        }
    }
}
