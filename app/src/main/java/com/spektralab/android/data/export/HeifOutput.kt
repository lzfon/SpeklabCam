package com.spektralab.android.data.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.heifwriter.HeifWriter
import androidx.core.graphics.createBitmap
import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.Rgba16Source
import java.io.File
import java.io.OutputStream

/** Actual device codec probe; one SDR sRGB image, no renamed JPEG fallback. */
internal object HeifOutput {
    data class Capability(val available: Boolean, val reason: String)
    @Volatile private var cached: Capability? = null

    @Synchronized
    fun capability(context: Context): Capability {
        cached?.let { return it }
        val result = if (Build.VERSION.SDK_INT < 28) Capability(false, "HEIF_REQUIRES_API_28") else {
            var file: File? = null
            var bitmap: Bitmap? = null
            try {
                file = File.createTempFile(".heif-probe-", ".heic", context.cacheDir)
                bitmap = createBitmap(32, 32).apply { eraseColor(0xff7799bb.toInt()) }
                Api28.encode(file, bitmap, 90, ExportMetadata.EMPTY)
                val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                try { check(decoded.width == 32 && decoded.height == 32) } finally { decoded.recycle() }
                Capability(true, "")
            } catch (error: Exception) {
                Capability(false, "HEIF_CODEC_UNAVAILABLE")
            } catch (_: OutOfMemoryError) {
                Capability(false, "HEIF_CODEC_UNAVAILABLE")
            } catch (_: LinkageError) {
                Capability(false, "HEIF_CODEC_UNAVAILABLE")
            } finally { bitmap?.recycle(); file?.delete() }
        }
        cached = result
        return result
    }

    fun write(context: Context, source: Rgba16Source, grade: Grade, quality: Int, output: OutputStream,
              check: () -> Unit, metadata: ExportMetadata) {
        if (Build.VERSION.SDK_INT < 28 || !capability(context).available) error("HEIF_CODEC_UNAVAILABLE")
        check()
        val file = File.createTempFile(".heif-output-", ".heic", context.cacheDir)
        var bitmap: Bitmap? = null
        try {
            check(context.cacheDir.usableSpace > source.width.toLong() * source.height * 4 + 64L * 1024 * 1024) { "Insufficient HEIF staging space" }
            bitmap = Rgba16Bitmap.convert(source, grade, check)
            check()
            Api28.encode(file, bitmap, quality, metadata)
            check()
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { check(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
            }
        } finally { bitmap?.recycle(); file.delete() }
    }

    @RequiresApi(28)
    private object Api28 {
        fun encode(file: File, bitmap: Bitmap, quality: Int, metadata: ExportMetadata) {
            val writer = HeifWriter.Builder(file.absolutePath, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
                .setMaxImages(1).setGridEnabled(true).setQuality(quality.coerceIn(1, 100)).build()
            try {
                writer.start()
                if (metadata.attributes.isNotEmpty()) {
                    val exif = byteArrayOf(69, 120, 105, 102, 0, 0) + metadata.tiffDirectory(bitmap.width, bitmap.height)
                    writer.addExifData(0, exif, 0, exif.size)
                }
                writer.addBitmap(bitmap)
                // AndroidX has no synchronous cancellation API. Bound drain; never publish before it finishes.
                writer.stop(30000)
                check(file.length() > 16) { "Empty HEIF output" }
            } finally { (writer as AutoCloseable).close() }
            HeifIcc.attach(file, com.spektralab.android.core.color.OutputIcc.generate("sRGB"))
        }
    }
}
