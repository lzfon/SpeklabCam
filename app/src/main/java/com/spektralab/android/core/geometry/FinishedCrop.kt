package com.spektralab.android.core.geometry

import org.json.JSONObject
import kotlin.math.roundToInt

/** A cut of the complete graded canvas, including any film border and exposed date. */
data class FinishedCrop(val x: Float = 0f, val y: Float = 0f, val width: Float = 1f, val height: Float = 1f) {
    init {
        require(listOf(x, y, width, height).all(Float::isFinite))
        require(x >= 0f && y >= 0f && width in .02f..1f && height in .02f..1f &&
            x + width <= 1.000001f && y + height <= 1.000001f)
    }

    val isFull: Boolean get() = this == FULL
    data class Pixels(val left: Int, val top: Int, val width: Int, val height: Int)

    fun pixels(frameWidth: Int, frameHeight: Int): Pixels {
        require(frameWidth > 0 && frameHeight > 0)
        val left = (x.toDouble() * frameWidth).roundToInt().coerceIn(0, frameWidth - 1)
        val top = (y.toDouble() * frameHeight).roundToInt().coerceIn(0, frameHeight - 1)
        val right = ((x.toDouble() + width) * frameWidth).roundToInt().coerceIn(left + 1, frameWidth)
        val bottom = ((y.toDouble() + height) * frameHeight).roundToInt().coerceIn(top + 1, frameHeight)
        return Pixels(left, top, right - left, bottom - top)
    }

    fun toJson(): JSONObject = JSONObject().put("version", 1).put("x", x.toDouble()).put("y", y.toDouble())
        .put("width", width.toDouble()).put("height", height.toDouble())

    companion object {
        val FULL = FinishedCrop()
        fun fromJson(json: JSONObject): FinishedCrop {
            require(json.keys().asSequence().all { it in setOf("version", "x", "y", "width", "height") })
            require(json.get("version") is Number && json.getDouble("version") == 1.0)
            fun value(key: String): Float {
                require(json.get(key) is Number)
                return json.getDouble(key).toFloat()
            }
            return FinishedCrop(value("x"), value("y"), value("width"), value("height"))
        }
    }
}
