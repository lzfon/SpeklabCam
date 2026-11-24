package com.spektralab.android.data.export

import com.spektralab.android.core.geometry.FinishedCrop
import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.Rgba16Source
import kotlin.math.roundToInt

/** Grade in full-canvas coordinates, then cut; the source remains owned by the caller. */
internal class FinishedRgba16Source private constructor(
    private val source: Rgba16Source,
    private val grade: Grade,
    private val crop: FinishedCrop.Pixels,
    private val checkCancelled: () -> Unit,
) : Rgba16Source {
    override val width = crop.width
    override val height = crop.height
    override val gradeApplied = true
    private val row = ShortArray(source.width * 4)
    private val graded = if (grade.usesNativeGrade) FloatArray(source.width * 3) else null
    private val pixel = FloatArray(3)

    override fun readRows(y: Int, rows: Int, destination: ShortArray) {
        require(y >= 0 && rows >= 0 && y.toLong() + rows <= height)
        require(destination.size.toLong() >= width.toLong() * rows * 4)
        for (dy in 0 until rows) {
            checkCancelled()
            val sy = crop.top + y + dy
            source.readRows(sy, 1, row)
            if (grade.isIdentity) {
                row.copyInto(destination, dy * width * 4, crop.left * 4, (crop.left + width) * 4)
                continue
            }
            graded?.let { grade.nativeRows(row, 0, source.width, source.height, sy, 1, it) }
            for (x in 0 until width) {
                if (x % 256 == 0) checkCancelled()
                val sx = crop.left + x
                if (graded != null) for (c in 0..2) pixel[c] = graded[sx * 3 + c]
                else grade.gradeAt(row[sx * 4], row[sx * 4 + 1], row[sx * 4 + 2], pixel,
                    sx, sy, source.width, source.height)
                val offset = (dy * width + x) * 4
                for (c in 0..2) destination[offset + c] = (pixel[c] * 65535f).roundToInt().coerceIn(0, 65535).toShort()
                destination[offset + 3] = row[sx * 4 + 3]
            }
        }
    }

    companion object {
        fun wrap(source: Rgba16Source, grade: Grade, crop: FinishedCrop, check: () -> Unit): Rgba16Source =
            if (crop.isFull) source else FinishedRgba16Source(source, grade, crop.pixels(source.width, source.height), check)
    }
}
