package com.spektralab.android.data.export

import com.spektralab.android.core.color.OutputColorSpaces
import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.Rgba16Source
import kotlin.math.roundToInt

/** Convert the finished graded raster, never reinterpret an ICC tag as a pixel transform. */
internal class OutputConvertedSource private constructor(
    private val source: Rgba16Source,
    private val grade: Grade,
    from: String,
    to: String,
    private val check: () -> Unit,
) : Rgba16Source {
    override val width = source.width
    override val height = source.height
    override val gradeApplied = true
    private val transform = android.graphics.ColorSpace.connect(OutputColorSpaces.named(from), OutputColorSpaces.named(to))
    private val input = ShortArray(width * 4)
    private val graded = if (grade.usesNativeGrade) FloatArray(width * 3) else null
    private val pixel = FloatArray(3)

    override fun readRows(y: Int, rows: Int, destination: ShortArray) {
        require(y >= 0 && rows >= 0 && y.toLong() + rows <= height)
        require(destination.size.toLong() >= width.toLong() * rows * 4)
        for (row in 0 until rows) {
            check()
            source.readRows(y + row, 1, input)
            graded?.let { grade.nativeRows(input, 0, width, height, y + row, 1, it) }
            for (x in 0 until width) {
                if (x % 256 == 0) check()
                if (grade.isIdentity) for (c in 0..2) pixel[c] = (input[x * 4 + c].toInt() and 65535) / 65535f
                else if (graded != null) for (c in 0..2) pixel[c] = graded[x * 3 + c]
                else grade.gradeAt(input[x * 4], input[x * 4 + 1], input[x * 4 + 2], pixel, x, y + row, width, height)
                transform.transform(pixel)
                val at = (row * width + x) * 4
                for (c in 0..2) destination[at + c] = (pixel[c] * 65535f).roundToInt().coerceIn(0, 65535).toShort()
                destination[at + 3] = input[x * 4 + 3]
            }
        }
    }

    companion object {
        fun wrap(source: Rgba16Source, grade: Grade, from: String, to: String, check: () -> Unit): Rgba16Source =
            if (from == to) source else OutputConvertedSource(source, grade, from, to, check)
    }
}
