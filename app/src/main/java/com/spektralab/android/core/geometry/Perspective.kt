package com.spektralab.android.core.geometry

import androidx.core.graphics.createBitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Desktop K·Rx·Ry·K^-1 homography, centre fixed; fill guarantees all four output corners exist. */
internal object Perspective {
    /** Canvas uses edge coordinates just like the source homography. Display-only crop workspace. */
    fun preview(source: android.graphics.Bitmap, vertical: Float, horizontal: Float): android.graphics.Bitmap {
        if (vertical == 0f && horizontal == 0f) return source
        val inverse = android.graphics.Matrix().apply {
            setValues(make(source.width, source.height, vertical, horizontal).inverse.map(Double:: toFloat).toFloatArray())
        }
        val forward = android.graphics.Matrix()
        check(inverse.invert(forward))
        val bitmap = createBitmap(source.width, source.height)
        android.graphics.Canvas(bitmap).drawBitmap(source, forward, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return bitmap
    }

    data class Map(val inverse: DoubleArray, val fill: Double) {
        fun source(x: Double, y: Double): Pair<Double, Double> = project(inverse, x, y)
    }
    fun make(width: Int, height: Int, vertical: Float, horizontal: Float): Map {
        require(width > 0 && height > 0 && vertical.isFinite() && horizontal.isFinite() &&
            vertical in -30f..30f && horizontal in -30f..30f)
        if (vertical == 0f && horizontal == 0f) return Map(identity(), 1.0)
        val w = width.toDouble(); val h = height.toDouble(); val f = maxOf(w, h); val cx = w/2; val cy = h/2
        val a = Math.toRadians(vertical.toDouble()); val b = Math.toRadians(horizontal.toDouble())
        val k = doubleArrayOf(f, 0.0, cx, 0.0, f, cy, 0.0, 0.0, 1.0)
        val ki = doubleArrayOf(1/f, 0.0, -cx/f, 0.0, 1/f, -cy/f, 0.0, 0.0, 1.0)
        val rx = doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(a), -sin(a), 0.0, sin(a), cos(a))
        val ry = doubleArrayOf(cos(b), 0.0, sin(b), 0.0, 1.0, 0.0, -sin(b), 0.0, cos(b))
        val turned = multiply(multiply(multiply(k, rx), ry), ki)
        val centre = project(turned, cx, cy)
        val back = doubleArrayOf(1.0, 0.0, cx-centre.first, 0.0, 1.0, cy-centre.second, 0.0, 0.0, 1.0)
        val inverse = invert(multiply(back, turned))
        fun inside(scale: Double): Boolean = listOf(0.0 to 0.0, w to 0.0, w to h, 0.0 to h).all { (x, y) ->
            val px = cx+scale*(x-cx); val py = cy+scale*(y-cy)
            val z = inverse[6]*px+inverse[7]*py+inverse[8]
            if (z <= 1e-9) false else project(inverse, px, py).let { it.first in -1e-6..w+1e-6 && it.second in -1e-6..h+1e-6 }
        }
        var fill = 1.0
        if (!inside(1.0)) {
            var low = 0.0; var high = 1.0
            repeat(48) {
                val mid = (low + high) / 2
                if (inside(mid)) low = mid else high = mid
            }
            fill = maxOf(low, 1e-3)
        }
        val scale = doubleArrayOf(fill, 0.0, cx*(1-fill), 0.0, fill, cy*(1-fill), 0.0, 0.0, 1.0)
        return Map(multiply(inverse, scale), fill)
    }
    private fun identity() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    private fun project(m: DoubleArray, x: Double, y: Double): Pair<Double, Double> {
        val z = m[6]*x+m[7]*y+m[8]
        require(abs(z)>1e-12)
        return (m[0]*x+m[1]*y+m[2])/z to (m[3]*x+m[4]*y+m[5])/z
    }
    private fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        (0..2).sumOf { a[i / 3 * 3 + it] * b[it * 3 + i % 3] }
    }
    private fun invert(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
        val det = a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g)
        require(abs(det)>1e-12 && det.isFinite())
        return doubleArrayOf(e*i-f*h, c*h-b*i, b*f-c*e, f*g-d*i, a*i-c*g, c*d-a*f, d*h-e*g, b*g-a*h, a*e-b*d)
            .also { result -> for (index in result.indices) result[index] /= det }
    }
}
