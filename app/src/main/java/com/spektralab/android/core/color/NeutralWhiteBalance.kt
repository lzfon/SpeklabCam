package com.spektralab.android.core.color

import kotlin.math.floor
import kotlin.math.min

/** Measurement works on stage pixels, never on a displayed bitmap or screenshot. */
internal object NeutralWhiteBalance {
    enum class Target { DECODE, PRINT }
    data class Balance(val temperature: Double, val tint: Double, val limited: Boolean = false)
    data class Patch(val rgb: DoubleArray, val pixels: Int)

    fun printBalance(rgb: DoubleArray): Balance? {
        if (rgb.size != 3 || rgb.any { !it.isFinite() || it <= .002 || it >= .995 }) return null
        val temperature = (rgb[2] - rgb[0]) / (rgb[2] + rgb[0]) / .18
        val level = rgb[0] * (1 + .18 * temperature)
        val tint = (1 - level / rgb[1]) / .12
        return Balance(temperature.coerceIn(-1.0, 1.0), tint.coerceIn(-1.0, 1.0),
            temperature !in -1.0..1.0 || tint !in -1.0..1.0)
    }

    fun sample(width: Int, height: Int, x: Double, y: Double, pixel: (Int, Int) -> DoubleArray?): Patch? {
        if (width <= 0 || height <= 0 || !x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0) return null
        val cx = min(floor(x * width).toInt(), width - 1)
        val cy = min(floor(y * height).toInt(), height - 1)
        val result = DoubleArray(3)
        var accepted = 0; var total = 0
        for (py in maxOf(0, cy - 3)..minOf(height - 1, cy + 3))
            for (px in maxOf(0, cx - 3)..minOf(width - 1, cx + 3)) {
                total++
                val value = pixel(px, py) ?: continue
                if (value.size != 3 || value.any { !it.isFinite() || it <= .0001 || it >= .995 }) continue
                for (channel in 0..2) result[channel] += value[channel]
                accepted++
            }
        if (accepted < (total + 1) / 2) return null
        for (channel in 0..2) result[channel] /= accepted
        return Patch(result, accepted)
    }

    /** ContentScale.Fit bounds: letterboxing is rejected rather than clamped into the photo. */
    fun imagePoint(viewWidth: Float, viewHeight: Float, imageWidth: Int, imageHeight: Int,
                   x: Float, y: Float): Pair<Double, Double>? {
        if (!viewWidth.isFinite() || !viewHeight.isFinite() || viewWidth <= 0 || viewHeight <= 0 ||
            imageWidth <= 0 || imageHeight <= 0 || !x.isFinite() || !y.isFinite()) return null
        val scale = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
        val width = imageWidth * scale; val height = imageHeight * scale
        val left = (viewWidth - width) / 2; val top = (viewHeight - height) / 2
        if (x < left || x > left + width || y < top || y > top + height) return null
        return ((x - left) / width).toDouble() to ((y - top) / height).toDouble()
    }
}
