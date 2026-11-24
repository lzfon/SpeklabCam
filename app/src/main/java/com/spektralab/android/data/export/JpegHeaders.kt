package com.spektralab.android.data.export

import java.io.OutputStream

/** Bounded JPEG APP1/APP2 insertion shared by native and older-platform codecs. */
internal fun jpegHeaders(buffered: OutputStream, exif: ByteArray?, icc: ByteArray?, check: () -> Unit): OutputStream = object : OutputStream() {
    private var prefix = 0
    private var scan = icc != null
    private val segment = if (icc == null) ByteArray(0) else ByteArray(65537)
    private var segmentSize = 0
    private var expectedSegmentSize = -1
    private fun originalByte(value: Int) {
        segment[segmentSize++] = value.toByte()
        if (segmentSize == 1) require(value == 255) { "Invalid JPEG marker" }
        if (segmentSize == 2) {
            if (value == 255) { buffered.write(255); segmentSize = 1; return }
            require(value != 0 && value != 216) { "Invalid JPEG header" }
            if (value == 217 || value == 1 || value in 208..215) {
                buffered.write(segment, 0, segmentSize); segmentSize = 0; if (value == 217) scan = false
                return
            }
        }
        if (segmentSize == 4) {
            val length = ((segment[2].toInt() and 255) shl 8) or (segment[3].toInt() and 255)
            require(length >= 2) { "Invalid JPEG segment length" }
            expectedSegmentSize = length + 2
        }
        if (segmentSize == expectedSegmentSize) {
            val oldProfile = segment[1].toInt() and 255 == 226 && segmentSize >= 18 &&
                segment.copyOfRange(4,16).contentEquals("ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII))
            if (!oldProfile) buffered.write(segment, 0, segmentSize)
            if (segment[1].toInt() and 255 == 218) scan = false
            segmentSize = 0; expectedSegmentSize = -1
        }
    }
    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        check()
        var at = offset; var remaining = length
        while (prefix < 2 && remaining > 0) {
            val expected = if (prefix == 0) 255 else 216
            check(bytes[at].toInt() and 255 == expected) { "Invalid JPEG SOI" }
            buffered.write(expected); prefix++; at++; remaining--
            if (prefix == 2) {
                if (exif != null) {
                    buffered.write(255); buffered.write(225)
                    buffered.write((exif.size + 2) ushr 8); buffered.write((exif.size + 2) and 255)
                    buffered.write(exif)
                }
                if (icc != null) {
                    val segmentSize = 65519
                    val count = (icc.size + segmentSize - 1) / segmentSize
                    require(count in 1..255)
                    repeat(count) { sequence ->
                        val offset = sequence * segmentSize
                        val size = minOf(segmentSize, icc.size - offset)
                        val length = size + 16
                        buffered.write(255); buffered.write(226)
                        buffered.write(length ushr 8); buffered.write(length and 255)
                        buffered.write("ICC_PROFILE".toByteArray(Charsets.US_ASCII)); buffered.write(0)
                        buffered.write(sequence + 1); buffered.write(count)
                        buffered.write(icc, offset, size)
                    }
                }
            }
        }
        while (remaining > 0 && scan) { originalByte(bytes[at].toInt() and 255); at++; remaining-- }
        if (remaining > 0) buffered.write(bytes, at, remaining)
    }
}
