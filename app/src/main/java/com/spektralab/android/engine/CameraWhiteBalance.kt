package com.spektralab.android.engine

import kotlin.math.sqrt
import kotlin.math.abs
import com.spektralab.android.core.model.EditorParams

/**
 * 相机白平衡（Adobe DNG 的温度/色调定义）：Kelvin + tint → 相机乘数。
 *
 * 与 SpektraLab-Windows 的 white_balance.cpp 一致：Robertson(1968) 等温线表
 * 把 Kelvin 映射到 CIE 1960 uv，tint 是沿等温线的带符号距离（×-3000，正=品红）。
 * 乘数 = 相机 cam_xyz 矩阵作用于该光源的 XYZ 后的倒数。
 */
object CameraWhiteBalance {
    data class Balance(val temperature: Double, val tint: Double)

    private data class Ruvt(val r: Double, val u: Double, val v: Double, val t: Double)

    // Robertson (1968)，DNG SDK 表：mired, u, v, 等温线斜率。
    private val table = arrayOf(
        Ruvt(0.0, 0.18006, 0.26352, -0.24341), Ruvt(10.0, 0.18066, 0.26589, -0.25479),
        Ruvt(20.0, 0.18133, 0.26846, -0.26876), Ruvt(30.0, 0.18208, 0.27119, -0.28539),
        Ruvt(40.0, 0.18293, 0.27407, -0.30470), Ruvt(50.0, 0.18388, 0.27709, -0.32675),
        Ruvt(60.0, 0.18494, 0.28021, -0.35156), Ruvt(70.0, 0.18611, 0.28342, -0.37915),
        Ruvt(80.0, 0.18740, 0.28668, -0.40955), Ruvt(90.0, 0.18880, 0.28997, -0.44278),
        Ruvt(100.0, 0.19032, 0.29326, -0.47888), Ruvt(125.0, 0.19462, 0.30141, -0.58204),
        Ruvt(150.0, 0.19962, 0.30921, -0.70471), Ruvt(175.0, 0.20525, 0.31647, -0.84901),
        Ruvt(200.0, 0.21142, 0.32312, -1.0182), Ruvt(225.0, 0.21807, 0.32909, -1.2168),
        Ruvt(250.0, 0.22511, 0.33439, -1.4512), Ruvt(275.0, 0.23247, 0.33904, -1.7298),
        Ruvt(300.0, 0.24010, 0.34308, -2.0637), Ruvt(325.0, 0.24702, 0.34655, -2.4681),
        Ruvt(350.0, 0.25591, 0.34951, -2.9641), Ruvt(375.0, 0.26400, 0.35200, -3.5814),
        Ruvt(400.0, 0.27218, 0.35407, -4.3633), Ruvt(425.0, 0.28039, 0.35577, -5.3762),
        Ruvt(450.0, 0.28863, 0.35714, -6.7262), Ruvt(475.0, 0.29685, 0.35823, -8.5955),
        Ruvt(500.0, 0.30505, 0.35907, -11.324), Ruvt(525.0, 0.31320, 0.35968, -15.628),
        Ruvt(550.0, 0.32129, 0.36011, -23.325), Ruvt(575.0, 0.32931, 0.36038, -40.770),
        Ruvt(600.0, 0.33724, 0.36051, -116.45),
    )
    private const val TINT_SCALE = -3000.0

    /** Kelvin + tint → CIE xy。结果写进 [out][0..1]。 */
    fun temperatureTintToXy(temperature: Double, tint: Double, out: DoubleArray) {
        val r = 1.0e6 / temperature.coerceIn(1700.0, 50000.0)
        val offset = tint * (1.0 / TINT_SCALE)
        for (i in 0 until 30) {
            if (r < table[i + 1].r || i == 29) {
                val f = (table[i + 1].r - r) / (table[i + 1].r - table[i].r)
                var u = table[i].u * f + table[i + 1].u * (1.0 - f)
                var v = table[i].v * f + table[i + 1].v * (1.0 - f)
                var uu1 = 1.0; var vv1 = table[i].t; var uu2 = 1.0; var vv2 = table[i + 1].t
                val len1 = sqrt(1.0 + vv1 * vv1); val len2 = sqrt(1.0 + vv2 * vv2)
                uu1 /= len1; vv1 /= len1; uu2 /= len2; vv2 /= len2
                var uu3 = uu1 * f + uu2 * (1.0 - f); var vv3 = vv1 * f + vv2 * (1.0 - f)
                val len3 = sqrt(uu3 * uu3 + vv3 * vv3)
                uu3 /= len3; vv3 /= len3
                u += uu3 * offset
                v += vv3 * offset
                out[0] = 1.5 * u / (u - 4.0 * v + 2.0)
                out[1] = v / (u - 4.0 * v + 2.0)
                return
            }
        }
    }

    /**
     * 相机 cam_xyz 矩阵（3x3 行主序）+ Kelvin/tint → 相机白平衡乘数 (R, G, B, G2)。
     * 无法中性化（该相机不支持这个光源）时返回 false。
     */
    fun multipliersFor(camXyz: FloatArray, temperature: Double, tint: Double, out: FloatArray): Boolean {
        if (camXyz.size != 9 || out.size < 4 || !temperature.isFinite() || !tint.isFinite() || camXyz.any { !it.isFinite() }) return false
        val xy = DoubleArray(2)
        temperatureTintToXy(temperature, tint, xy)
        val xyz = doubleArrayOf(xy[0] / xy[1], 1.0, (1.0 - xy[0] - xy[1]) / xy[1])
        val n = DoubleArray(3)
        for (row in 0..2) {
            n[row] = camXyz[row * 3] * xyz[0] + camXyz[row * 3 + 1] * xyz[1] + camXyz[row * 3 + 2] * xyz[2]
        }
        if (!(n[0] > 0 && n[1] > 0 && n[2] > 0)) return false
        out[0] = (n[1] / n[0]).toFloat()
        out[1] = 1f
        out[2] = (n[1] / n[2]).toFloat()
        out[3] = 1f
        return true
    }

    /** Inverse of the existing Robertson table, same definition as desktop white_balance.cpp. */
    internal fun fromXy(x: Double, y: Double): Balance? {
        if (!x.isFinite() || !y.isFinite() || y <= 0 || x < 0 || x + y >= 1) return null
        val denominator = 1.5 - x + 6 * y
        val u = 2 * x / denominator
        val v = 3 * y / denominator
        var lastDistance = 0.0
        var lastU = 0.0
        var lastV = 0.0
        for (i in 1..30) {
            var du = 1.0 / sqrt(1 + table[i].t * table[i].t)
            var dv = table[i].t * du
            val distance = -(u - table[i].u) * dv + (v - table[i].v) * du
            if (distance <= 0 || i == 30) {
                val positive = -minOf(distance, 0.0)
                val f = if (i == 1) 0.0 else positive / (lastDistance + positive)
                val mired = table[i - 1].r * f + table[i].r * (1 - f)
                val deltaU = u - (table[i - 1].u * f + table[i].u * (1 - f))
                val deltaV = v - (table[i - 1].v * f + table[i].v * (1 - f))
                du = du * (1 - f) + lastU * f
                dv = dv * (1 - f) + lastV * f
                val length = sqrt(du * du + dv * dv)
                val temperature = 1e6 / mired
                val tint = (deltaU * du + deltaV * dv) / length * TINT_SCALE
                return if (temperature.isFinite() && tint.isFinite()) Balance(temperature, tint) else null
            }
            lastDistance = distance
            lastU = du
            lastV = dv
        }
        return null
    }

    internal fun asShot(cameraXyz: FloatArray, multipliers: FloatArray): Balance? {
        if (multipliers.size < 3 || multipliers.take(3).any { !it.isFinite() || it <= 0 }) return null
        val inverse = inverse(cameraXyz.map(Float::toDouble).toDoubleArray()) ?: return null
        // Do not offer an independent as-shot axis if the forward conversion would clamp it.
        return fromCamera(inverse, DoubleArray(3) { 1.0 / multipliers[it] })
            ?.takeIf { it.temperature in 1700.0..50000.0 && it.tint in -150.0..150.0 }
    }

    internal fun neutral(rgb: DoubleArray, calibration: FloatArray, cameraXyz: FloatArray): Balance? {
        if (rgb.size != 3 || calibration.size != 13 || calibration[12] != 3f || rgb.any { !it.isFinite() || it <= .0001 || it >= .995 }) return null
        val output = inverse(DoubleArray(9) { calibration[it].toDouble() }) ?: return null
        val camera = inverse(cameraXyz.map(Float::toDouble).toDoubleArray()) ?: return null
        val neutral = DoubleArray(3)
        for (row in 0..2) {
            val value = (0..2).sumOf { output[row * 3 + it] * rgb[it] }
            val applied = calibration[9 + row].toDouble()
            if (!value.isFinite() || value <= .0001 || value >= .995 || !applied.isFinite() || applied <= 0) return null
            neutral[row] = value / applied
        }
        return fromCamera(camera, neutral)?.takeIf { it.temperature in 2000.0..50000.0 && it.tint in -150.0..150.0 }
    }

    private fun fromCamera(inverse: DoubleArray, neutral: DoubleArray): Balance? {
        val xyz = DoubleArray(3) { row -> (0..2).sumOf { inverse[row * 3 + it] * neutral[it] } }
        val sum = xyz.sum()
        return if (sum.isFinite() && sum > 0 && xyz[1] > 0) fromXy(xyz[0] / sum, xyz[1] / sum) else null
    }

    internal fun resolve(params: EditorParams, cameraXyz: FloatArray, shotMultipliers: FloatArray?): FloatArray? {
        if (!params.cameraWbCustom || params.cameraWbTemperatureAsShot && params.cameraWbTintAsShot) return null
        val shot = if (params.cameraWbTemperatureAsShot || params.cameraWbTintAsShot) {
            asShot(cameraXyz, shotMultipliers ?: throw SpkException("RAW white balance: as-shot calibration unavailable"))
                ?: throw SpkException("RAW white balance: as-shot calibration unavailable")
        } else null
        val t = if (params.cameraWbTemperatureAsShot) shot!!.temperature else params.cameraWbKelvin.toDouble()
        val g = if (params.cameraWbTintAsShot) shot!!.tint else params.cameraWbTint.toDouble()
        val values = FloatArray(4)
        if (!multipliersFor(cameraXyz, t, g, values)) {
            // Legacy custom edits used the native as-shot fallback for unsupported matrices.
            if (!params.cameraWbTemperatureAsShot && !params.cameraWbTintAsShot) return null
            throw SpkException("无法应用此拍摄白平衡轴，请恢复拍摄白平衡或使用完整自定义值")
        }
        return values
    }

    private fun inverse(matrix: DoubleArray): DoubleArray? {
        if (matrix.size != 9 || matrix.any { !it.isFinite() }) return null
        val a = matrix[0]
        val b = matrix[1]
        val c = matrix[2]
        val d = matrix[3]
        val e = matrix[4]
        val f = matrix[5]
        val g = matrix[6]
        val h = matrix[7]
        val i = matrix[8]
        val determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (!determinant.isFinite() || abs(determinant) < 1e-12) return null
        return doubleArrayOf(
            e * i - f * h, c * h - b * i, b * f - c * e,
            f * g - d * i, a * i - c * g, c * d - a * f,
            d * h - e * g, b * g - a * h, a * e - b * d,
        )
            .also { result -> for (index in result.indices) result[index] /= determinant }
    }
}
