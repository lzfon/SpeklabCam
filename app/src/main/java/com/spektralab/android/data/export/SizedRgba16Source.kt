package com.spektralab.android.data.export

import com.spektralab.android.core.model.ExportSize
import com.spektralab.android.engine.Rgba16Source
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Exact area coverage of the finished U16 raster, with only row-sized working buffers.
 * The caller owns the source lifetime. Identity sizing returns the original source.
 */
internal class SizedRgba16Source private constructor(
    private val source: Rgba16Source,
    override val width: Int,
    override val height: Int,
    private val checkCancelled: () -> Unit,
) : Rgba16Source {
    override val gradeApplied: Boolean get() = source.gradeApplied
    private val row = ShortArray(source.width * 4)
    private val horizontal = DoubleArray(width * 4)
    private val sum = DoubleArray(width * 4)
    private var cachedRow = -1
    private val scaleX = source.width.toDouble() / width
    private val scaleY = source.height.toDouble() / height

    override fun readRows(y: Int, rows: Int, destination: ShortArray) {
        require(y >= 0 && rows >= 0 && y.toLong() + rows <= height)
        require(destination.size.toLong() >= width.toLong() * rows * 4)
        for (targetY in y until y + rows) {
            checkCancelled()
            sum.fill(0.0)
            val top = targetY * scaleY
            val bottom = (targetY + 1) * scaleY
            for (sourceY in floor(top).toInt() until minOf(ceil(bottom).toInt(), source.height)) {
                checkCancelled()
                if (cachedRow != sourceY) {
                    source.readRows(sourceY, 1, row)
                    for (x in 0 until width) {
                        if (x % 256 == 0) checkCancelled()
                        val left = x * scaleX
                        val right = (x + 1) * scaleX
                        for (c in 0..3) {
                            var value = 0.0
                            for (sx in floor(left).toInt() until minOf(ceil(right).toInt(), source.width)) {
                                val coverage = minOf(right, sx + 1.0) - maxOf(left, sx.toDouble())
                                value += (row[sx * 4 + c].toInt() and 65535) * coverage
                            }
                            horizontal[x * 4 + c] = value / scaleX
                        }
                    }
                    cachedRow = sourceY
                }
                val coverage = minOf(bottom, sourceY + 1.0) - maxOf(top, sourceY.toDouble())
                for (i in sum.indices) sum[i] += horizontal[i] * coverage
            }
            val offset = (targetY - y) * width * 4
            for (i in sum.indices) destination[offset + i] = (sum[i] / scaleY).roundToInt().coerceIn(0, 65535).toShort()
        }
    }

    companion object {
        fun wrap(source: Rgba16Source, size: ExportSize?, check: () -> Unit): Rgba16Source {
            val (width, height) = size?.dimensions(source.width, source.height) ?: return source
            return if (width == source.width && height == source.height) source
                else SizedRgba16Source(source, width, height, check)
        }
    }
}
