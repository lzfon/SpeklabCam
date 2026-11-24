package com.spektralab.android.data.export

import android.graphics.Bitmap
import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.Rgba16Source

/** The same final U16/grade-to-8-bit quantizer used by ordinary photo PNG/JPEG output. */
internal object Rgba16Bitmap {
    fun convert(result: Rgba16Source, grade: Grade, checkCancelled: () -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
        try {
            Rgba16Strips(result, grade).forEach(checkCancelled) { y, rows, argb ->
                bitmap.setPixels(argb, 0, result.width, 0, y, result.width, rows)
            }
            return bitmap
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }
}
