package com.spektralab.android.data.export

import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.Rgba16Source
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Lossless RGB PNG. Native zlib consumes rows; no full-size Bitmap or encoded byte array. */
internal object PngRowWriter {
    fun write(source: Rgba16Source, grade: Grade, output: OutputStream, check: () -> Unit,
              metadata: ExportMetadata = ExportMetadata.EMPTY,
              imprint: com.spektralab.android.core.model.DateImprintPaint.Overlay? = null,
              icc: ByteArray? = null) {
        val out = DataOutputStream(output)
        out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        chunk(out, "IHDR", ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
            .putInt(source.width).putInt(source.height).put(8).put(2).put(0).put(0).put(0).array())
        if (icc == null) chunk(out, "sRGB", byteArrayOf(0)) else {
            val compressed = java.io.ByteArrayOutputStream()
            DeflaterOutputStream(compressed).use { it.write(icc) }
            chunk(out, "iCCP", "Theia RGB".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0) + compressed.toByteArray())
        }
        if (metadata.attributes.isNotEmpty()) chunk(out, "eXIf", metadata.tiffDirectory(source.width, source.height))
        val packets = object : OutputStream() {
            private val bytes = ByteArray(64 * 1024)
            private var size = 0
            override fun write(value: Int) { if (size == bytes.size) flush(); bytes[size++] = value.toByte() }
            override fun write(value: ByteArray, offset: Int, length: Int) {
                var at = offset; var remaining = length
                while (remaining > 0) {
                    if (size == bytes.size) flush()
                    val count = minOf(remaining, bytes.size - size)
                    value.copyInto(bytes, size, at, at + count); size += count; at += count; remaining -= count
                }
            }
            override fun flush() {
                if (size > 0) { check(); chunk(out, "IDAT", bytes, size); size = 0 }
            }
        }
        val deflater = Deflater(6)
        try {
            val compressed = DeflaterOutputStream(packets, deflater, 64 * 1024)
            val row = ByteArray(source.width * 3 + 1)
            row[0] = 1 // PNG Sub filter: exact byte differences, not a colour/quality operation.
            Rgba16Strips(source, grade, imprint).forEach(check) { _, rows, pixels ->
                for (y in 0 until rows) {
                    check()
                    var previousR = 0; var previousG = 0; var previousB = 0
                    for (x in 0 until source.width) {
                        val pixel = pixels[y * source.width + x]
                        val r = pixel ushr 16 and 255; val g = pixel ushr 8 and 255; val b = pixel and 255
                        val at = x * 3 + 1
                        row[at] = (r - previousR).toByte(); row[at + 1] = (g - previousG).toByte(); row[at + 2] = (b - previousB).toByte()
                        previousR = r; previousG = g; previousB = b
                    }
                    compressed.write(row)
                }
            }
            compressed.finish(); packets.flush(); check()
        } finally { deflater.end() }
        chunk(out, "IEND", byteArrayOf())
    }

    private fun chunk(out: DataOutputStream, type: String, bytes: ByteArray, length: Int = bytes.size) {
        val name = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(name); update(bytes, 0, length) }
        out.writeInt(length); out.write(name); out.write(bytes, 0, length); out.writeInt(crc.value.toInt())
    }
}
