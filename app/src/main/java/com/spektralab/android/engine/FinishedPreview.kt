package com.spektralab.android.engine

import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.core.graphics.createBitmap
import com.spektralab.android.core.geometry.FinishedCrop

/** Stream a finished crop directly into its display bitmap; keep full-frame grade coordinates. */
internal object FinishedPreview {
    fun render(result: SpkResult, grade: Grade, crop: FinishedCrop, colorSpace: String,
               check: () -> Unit): Pair<Bitmap, Histogram> {
        val width = result.width
        val height = result.height
        val cut = crop.pixels(width, height)
        val bitmap = createBitmap(cut.width, cut.height)
        try {
            val transform = com.spektralab.android.core.color.OutputColorSpaces.displayTransform(colorSpace)
            val rows = minOf(32, cut.height, (1024 * 1024 / width).coerceAtLeast(1))
            val pixels = IntArray(width * rows)
            val discardedBins = IntArray(768)
            val counts = Histogram(IntArray(256), IntArray(256), IntArray(256))
            val native = if (transform != null && grade.usesNativeGrade) FloatArray(width * rows * 3) else null
            val pixel = FloatArray(3)
            var y = cut.top
            while (y < cut.top + cut.height) {
                check()
                val count = minOf(rows, cut.top + cut.height - y)
                if (transform == null) {
                    grade.nativeArgbRows(result.rgba16, y * width * 4, width, height, y, count, pixels, discardedBins)
                } else {
                    native?.let { grade.nativeRows(result.rgba16, y * width * 4, width, height, y, count, it) }
                    for (row in 0 until count) for (x in cut.left until cut.left + cut.width) {
                        val i = row * width + x
                        if (native != null) for (c in 0..2) pixel[c] = native[i * 3 + c]
                        else {
                            val at = ((y + row) * width + x) * 4
                            grade.gradeAt(result.rgba16[at], result.rgba16[at + 1], result.rgba16[at + 2],
                                pixel, x, y + row, width, height)
                        }
                        transform.transform(pixel)
                        pixels[i] = (0xff shl 24) or ((pixel[0] * 255f + .5f).toInt().coerceIn(0, 255) shl 16) or
                            ((pixel[1] * 255f + .5f).toInt().coerceIn(0, 255) shl 8) or
                            (pixel[2] * 255f + .5f).toInt().coerceIn(0, 255)
                    }
                }
                for (row in 0 until count) for (x in cut.left until cut.left + cut.width) {
                    val argb = pixels[row * width + x]
                    counts.r[(argb ushr 16) and 255]++
                    counts.g[(argb ushr 8) and 255]++
                    counts.b[argb and 255]++
                }
                bitmap.setPixels(pixels, cut.left, width, 0, y - cut.top, cut.width, count)
                y += count
            }
            return bitmap to counts
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }
}
