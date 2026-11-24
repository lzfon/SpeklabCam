package com.spektralab.android.data.export

import android.content.Context
import android.os.ParcelFileDescriptor
import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.Rgba16Source
import java.io.OutputStream
import java.util.zip.DeflaterOutputStream

/** Stage one compressed strip so its length is known before writing to a non-seekable destination. */
internal object DeflatedTiffWriter {
    fun write(
        context: Context,
        source: Rgba16Source,
        grade: Grade,
        icc: ByteArray?,
        output: OutputStream,
        check: () -> Unit,
        metadata: ExportMetadata,
        imprint: com.spektralab.android.core.model.DateImprintPaint.Overlay? = null,
    ) {
        check()
        val bytes = TiffWriter.checkedPixelBytes(source.width, source.height).toLong()
        // zlib's compressBound for its default window/memory settings, plus the export disk reserve.
        val bound = bytes + (bytes shr 12) + (bytes shr 14) + (bytes shr 25) + 13
        check(context.cacheDir.usableSpace > bound + 64L * 1024 * 1024) { "Insufficient lossless TIFF staging space" }
        EphemeralExportFile.create(context).use { strip ->
            ParcelFileDescriptor.AutoCloseOutputStream(strip.writer).use { file ->
                DeflaterOutputStream(file.buffered(64 * 1024)).use { compressed ->
                    TiffWriter.writePixels(source.width, source.height, grade, compressed, check, source::readRows, imprint)
                }
            }
            val length = strip.reader.statSize
            require(length in 1..Int.MAX_VALUE.toLong()) { "Unsupported compressed TIFF strip size" }
            check()
            TiffWriter.writeHeader(source.width, source.height, 8, length.toInt(), icc, metadata, output)
            ParcelFileDescriptor.AutoCloseInputStream(strip.reader).use { input ->
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    check()
                    val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    check(count > 0) { "Truncated compressed TIFF strip" }
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
            check()
        }
    }
}
