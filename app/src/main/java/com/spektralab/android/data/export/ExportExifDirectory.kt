package com.spektralab.android.data.export

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PNG eXIf carries a standalone TIFF metadata directory, with the same camera/GPS allowlists as TIFF output. */
internal fun ExportMetadata.tiffDirectory(width: Int, height: Int): ByteArray {
    val root = rootFields()
    fun integer(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    val exif = exifFields() + listOf(
        ExportMetadata.Field(40962, 4, 1, integer(width)),
        ExportMetadata.Field(40963, 4, 1, integer(height)),
    )
    val gps = gpsFields()
    val count = root.size + 3 + (if (exif.isNotEmpty()) 1 else 0) + (if (gps.isNotEmpty()) 1 else 0)
    val start = 8 + 2 + count * 12 + 4
    val payload = ByteArrayOutputStream()
    fun store(bytes: ByteArray): Int {
        if ((start + payload.size()) % 2 != 0) payload.write(0)
        val offset = start + payload.size(); payload.write(bytes); return offset
    }
    fun value(field: ExportMetadata.Field): Int = if (field.bytes.size > 4) store(field.bytes)
        else ByteBuffer.wrap(field.bytes.copyOf(4)).order(ByteOrder.LITTLE_ENDIAN).int
    fun entry(buffer: ByteBuffer, id: Int, type: Int, size: Int, offset: Int) {
        buffer.putShort(id.toShort()).putShort(type.toShort()).putInt(size).putInt(offset)
    }
    fun directory(fields: List<ExportMetadata.Field>): Int {
        val buffer = ByteBuffer.allocate(2 + fields.size * 12 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(fields.size.toShort())
        fields.sortedBy { it.id }.forEach { entry(buffer, it.id, it.type, it.count, value(it)) }
        buffer.putInt(0); return store(buffer.array())
    }
    val entries = root.map { intArrayOf(it.id, it.type, it.count, value(it)) }.toMutableList()
    entries += intArrayOf(256, 4, 1, width)
    entries += intArrayOf(257, 4, 1, height)
    entries += intArrayOf(274, 3, 1, 1)
    if (exif.isNotEmpty()) entries += intArrayOf(34665, 4, 1, directory(exif))
    if (gps.isNotEmpty()) entries += intArrayOf(34853, 4, 1, directory(gps))
    val header = ByteBuffer.allocate(start).order(ByteOrder.LITTLE_ENDIAN)
    header.put(73).put(73).putShort(42).putInt(8).putShort(count.toShort())
    entries.sortedBy { it[0] }.forEach { entry(header, it[0], it[1], it[2], it[3]) }
    header.putInt(0)
    return header.array() + payload.toByteArray()
}
