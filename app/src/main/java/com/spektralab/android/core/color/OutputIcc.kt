package com.spektralab.android.core.color

import android.graphics.ColorSpace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** ICC v2 RGB matrix/TRC profiles in D50 PCS, built from the platform's standard spaces. */
internal object OutputIcc {
    private fun signature(text: String): Int = text.fold(0) { value, char -> (value shl 8) or char.code }
    private fun fixed(value: Double) = (value * 65536).roundToInt()
    private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)

    fun generate(name: String): ByteArray {
        if (name == "Display P3") return DisplayP3Icc.generate()
        val space = OutputColorSpaces.named(name) as ColorSpace.Rgb
        val adapted = ColorSpace.adapt(space, floatArrayOf(.9642f, 1f, .8249f)) as ColorSpace.Rgb
        val matrix = adapted.transform
        fun xyz(x: Double, y: Double, z: Double) = buffer(20).putInt(signature("XYZ ")).putInt(0)
            .putInt(fixed(x)).putInt(fixed(y)).putInt(fixed(z)).array()
        val ascii = name.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val description = buffer((12 + ascii.size + 78 + 3) and -4)
            .putInt(signature("desc")).putInt(0).putInt(ascii.size).put(ascii).array()
        val copyright = "Theia standard RGB output".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val trc = buffer(12 + 4096 * 2).putInt(signature("curv")).putInt(0).putInt(4096)
        repeat(4096) { i ->
            trc.putShort((space.eotf.applyAsDouble(i / 4095.0).coerceIn(0.0, 1.0) * 65535).roundToInt().toShort())
        }
        val tags = listOf("desc" to description,
            "cprt" to buffer(8 + copyright.size).putInt(signature("text")).putInt(0).put(copyright).array(),
            "wtpt" to xyz(.9642, 1.0, .8249),
            "rXYZ" to xyz(matrix[0].toDouble(), matrix[1].toDouble(), matrix[2].toDouble()),
            "gXYZ" to xyz(matrix[3].toDouble(), matrix[4].toDouble(), matrix[5].toDouble()),
            "bXYZ" to xyz(matrix[6].toDouble(), matrix[7].toDouble(), matrix[8].toDouble()),
            "rTRC" to trc.array(), "gTRC" to trc.array(), "bTRC" to trc.array())
        var end = 128 + 4 + tags.size * 12
        val offsets = tags.map { (_, bytes) -> end.also { end = (end + bytes.size + 3) and -4 } }
        val output = buffer(end).putInt(end).putInt(0).putInt(0x02100000)
            .putInt(signature("mntr")).putInt(signature("RGB ")).putInt(signature("XYZ "))
        listOf(2026, 11, 19, 0, 0, 0).forEach { output.putShort(it.toShort()) }
        output.putInt(signature("acsp")).putInt(0).putInt(0).putInt(0).putInt(0).putLong(0).putInt(0)
            .putInt(fixed(.9642)).putInt(fixed(1.0)).putInt(fixed(.8249)).putInt(0).put(ByteArray(44))
        output.putInt(tags.size)
        tags.forEachIndexed { i, (key, bytes) -> output.putInt(signature(key)).putInt(offsets[i]).putInt(bytes.size) }
        tags.forEachIndexed { i, (_, bytes) -> output.position(offsets[i]); output.put(bytes) }
        return output.array()
    }
}
