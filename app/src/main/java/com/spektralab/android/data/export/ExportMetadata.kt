package com.spektralab.android.data.export

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit allowlists keep location independent of camera data and exclude maker notes/thumbnails. */
data class ExportMetadata(val attributes: Map<String, String>) {
    /** 原片读不到、但早期存下过的条目（目前只用于位置信息）补进来，已有值不覆盖。 */
    fun withFallback(extra: Map<String, String>): ExportMetadata {
        if (extra.isEmpty()) return this
        val merged = attributes.toMutableMap()
        extra.forEach { (tag, value) -> if (merged[tag].isNullOrBlank()) merged[tag] = value }
        return if (merged.size == attributes.size) this else ExportMetadata(merged)
    }

    /**
     * 拍摄时刻的毫秒值：EXIF 的日期时间字符串按「设备本地时区」解释。
     * 相册/媒体库若把无偏移标记的时间当 UTC，就会整体偏 +8 小时甚至跨到明天，
     * 所以这里同时给出显式时区偏移标签与 DATE_TAKEN 需要的毫秒值。
     */
    fun takenMillis(): Long? {
        val raw = attributes["DateTimeOriginal"] ?: attributes["DateTimeDigitized"]
            ?: attributes["DateTime"] ?: return null
        return parseLocal(raw)
    }

    fun attach(file: File, width: Int, height: Int) {
        if (attributes.isEmpty()) return
        val target = ExifInterface(file)
        attributes.forEach { (tag, value) -> target.setAttribute(tag, value) }
        val taken = takenMillis()
        if (taken != null) {
            // 原片没带时区偏移标签时补一条，读侧才能把这串时间认定为本地球面时间。
            val missing = listOf(
                ExifInterface.TAG_OFFSET_TIME to "OffsetTime",
                ExifInterface.TAG_OFFSET_TIME_ORIGINAL to "OffsetTimeOriginal",
                ExifInterface.TAG_OFFSET_TIME_DIGITIZED to "OffsetTimeDigitized",
            ).filter { (_, key) -> attributes[key].isNullOrBlank() }
            for ((tag, _) in missing) {
                runCatching { target.setAttribute(tag, zoneOffsetText(taken)) }
            }
        }
        target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        target.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
        target.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
        target.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        target.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        target.saveAttributes()
    }

    internal data class Field(val id: Int, val type: Int, val count: Int, val bytes: ByteArray)
    private fun field(id: Int, type: Int, key: String): Field? {
        val raw = attributes[key] ?: return null
        return runCatching {
            when (type) {
                2 -> (raw.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)).let { Field(id, type, it.size, it) }
                1 -> Field(id, type, 1, byteArrayOf(raw.toInt().toByte()))
                3 -> Field(id, type, 1, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(raw.toInt().toShort()).array())
                5 -> {
                    val values = if (key == "GPSTimeStamp" && ':' in raw) raw.split(':') else raw.split(',')
                    val bytes = ByteBuffer.allocate(values.size * 8).order(ByteOrder.LITTLE_ENDIAN)
                    for (value in values) {
                        val parts = value.trim().split('/')
                        val numerator: Long; val denominator: Long
                        if (parts.size == 2) { numerator = parts[0].toLong(); denominator = parts[1].toLong() }
                        else { denominator = 1_000_000L; numerator = (value.toDouble() * denominator).toLong() }
                        require(numerator in 0..0xFFFFFFFFL && denominator in 1..0xFFFFFFFFL)
                        bytes.putInt(numerator.toInt()).putInt(denominator.toInt())
                    }
                    Field(id, type, values.size, bytes.array())
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun ascii(id: Int, text: String) =
        text.toByteArray(Charsets.US_ASCII)
            .let { Field(id, 2, it.size + 1, it + byteArrayOf(0)) }

    /** "+08:00" / "-05:30"：按拍摄那一刻该时区真实生效的偏移（含夏令时）。 */
    private fun zoneOffsetText(epochMs: Long): String {
        val total = java.util.TimeZone.getDefault().getOffset(epochMs) / 1000
        val sign = if (total >= 0) '+' else '-'
        val magnitude = kotlin.math.abs(total)
        return "%c%02d:%02d".format(sign, magnitude / 3600, (magnitude % 3600) / 60)
    }

    /** 原片已有对应偏移才照抄；没有就按本地时区补一条，绝不凭空造时间。 */
    private fun offsetFields(): List<Field> {
        val taken = takenMillis() ?: return emptyList()
        val fallback = zoneOffsetText(taken)
        return listOfNotNull(
            field(34858, 2, "OffsetTime") ?: ascii(34858, fallback),
            field(36881, 2, "OffsetTimeOriginal") ?: ascii(36881, fallback),
            field(36882, 2, "OffsetTimeDigitized") ?: ascii(36882, fallback),
        )
    }

    // TIFF writing uses native EXIF/GPS directories: ExifInterface cannot rewrite 16-bit TIFF.
    internal fun rootFields() = listOfNotNull(
        field(271, 2, "Make"), field(272, 2, "Model"), field(306, 2, "DateTime"),
    ) + offsetFields().filter { it.id == 34858 }

    internal fun exifFields() = listOfNotNull(
        field(33434, 5, "ExposureTime"), field(33437, 5, "FNumber"),
        field(34855, 3, "PhotographicSensitivity"), field(36867, 2, "DateTimeOriginal"),
        field(36868, 2, "DateTimeDigitized"), field(37385, 3, "Flash"),
        field(37386, 5, "FocalLength"), field(37521, 2, "SubSecTimeOriginal"),
        field(41987, 3, "WhiteBalance"), field(41989, 3, "FocalLengthIn35mmFilm"),
        field(42035, 2, "LensMake"), field(42036, 2, "LensModel"),
    ) + offsetFields().filter { it.id != 34858 }

    internal fun gpsFields(): List<Field> {
        val fields = listOfNotNull(field(1, 2, "GPSLatitudeRef"), field(2, 5, "GPSLatitude"),
            field(3, 2, "GPSLongitudeRef"), field(4, 5, "GPSLongitude"),
            field(5, 1, "GPSAltitudeRef"), field(6, 5, "GPSAltitude"),
            field(7, 5, "GPSTimeStamp"), field(16, 2, "GPSImgDirectionRef"),
            field(17, 5, "GPSImgDirection"), field(29, 2, "GPSDateStamp"))
        return if (fields.isEmpty()) fields else listOf(Field(0, 1, 4, byteArrayOf(2, 3, 0, 0))) + fields
    }

    companion object {
        val EMPTY = ExportMetadata(emptyMap())
        private val captureTags = listOf("Make", "Model", "DateTime", "DateTimeOriginal", "DateTimeDigitized",
            "ExposureTime", "FNumber", "PhotographicSensitivity", "FocalLength", "FocalLengthIn35mmFilm",
            "Flash", "WhiteBalance", "SubSecTimeOriginal", "LensMake", "LensModel",
            "OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized")
        private val locationTags = listOf("GPSLatitudeRef", "GPSLatitude", "GPSLongitudeRef", "GPSLongitude",
            "GPSAltitudeRef", "GPSAltitude", "GPSTimeStamp", "GPSDateStamp", "GPSImgDirectionRef", "GPSImgDirection")
        fun from(source: ExifInterface?, capture: Boolean, location: Boolean): ExportMetadata {
            if (source == null) return EMPTY
            val tags = (if (capture) captureTags else emptyList()) + (if (location) locationTags else emptyList())
            return ExportMetadata(tags.mapNotNull { tag -> source.getAttribute(tag)?.let { tag to it } }.toMap())
        }

        /** 打开照片时那条源 EXIF 的拍摄时刻（本地时区解释），供相册 DATE_TAKEN 用。 */
        fun takenMillis(exif: ExifInterface?): Long? {
            val raw = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif?.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                ?: exif?.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
            return parseLocal(raw)
        }

        internal fun parseLocal(raw: String): Long? = runCatching {
            java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getDefault(); isLenient = true }
                .parse(raw.trim().take(19))?.time
        }.getOrNull()
    }
}
