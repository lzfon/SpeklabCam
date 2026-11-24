package com.spektralab.android.data.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.spektralab.android.core.model.TableSource
import com.spektralab.android.data.cache.LightTablePreviews
import java.io.File
import kotlin.math.abs

/** Recognize an old project's original preview, never use that preview as export pixels. */
internal object TableOriginalAppearance {
    fun matches(context: Context, source: TableSource, original: Uri, check: () -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        val cache = LightTablePreviews(File(context.cacheDir, "lighttable-previews"))
        val preview = listOfNotNull(source.renderedPreview, source.preview).distinct()
            .firstNotNullOfOrNull { cache.read(it, 640) } ?: return false
        var decoded: Bitmap? = null
        var sameAspect = false
        try {
            check()
            decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, original)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                sameAspect = abs(info.size.width.toDouble() / info.size.height -
                    preview.width.toDouble() / preview.height) <= .01
                // Old saved previews may be 192, 320 or 640 pixels. Always bound this
                // compatibility decode to their actual size, never to the original size.
                decoder.setTargetSize(preview.width, preview.height)
            }
            check()
            if (!sameAspect || decoded.width != preview.width || decoded.height != preview.height) return false
            // Interpolation differs between old thumbnail paths; compare bounded sampling,
            // with both a mean and an outlier limit so real edits are not treated as originals.
            var total = 0L
            var count = 0
            var outliers = 0
            for (y in 0 until preview.height step maxOf(1, preview.height / 64)) {
                check()
                for (x in 0 until preview.width step maxOf(1, preview.width / 64)) {
                    val a = preview.getPixel(x, y); val b = decoded.getPixel(x, y)
                    for (shift in 0..16 step 8) {
                        val delta = abs((a ushr shift and 255) - (b ushr shift and 255))
                        total += delta; count++; if (delta > 12) outliers++
                    }
                }
            }
            return count > 0 && total.toDouble() / count <= 2.0 && outliers <= count / 100
        } finally { decoded?.recycle(); preview.recycle() }
    }
}
