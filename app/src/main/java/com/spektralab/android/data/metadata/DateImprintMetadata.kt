package com.spektralab.android.data.metadata

import androidx.exifinterface.media.ExifInterface
import com.spektralab.android.core.model.EditorParams
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** A small, immutable projection of the EXIF already read during import. Never opens the source. */
internal data class DateImprintMetadata(val dateText: String = "", val dataText: String = "") {
    val available: Boolean get() = dateText.isNotEmpty() || dataText.isNotEmpty()

    /** Opening an existing edit never changes it. Only an explicit enable/read action applies text. */
    fun applyTo(params: EditorParams, replace: Boolean = false) {
        if (dateText.isNotEmpty() && (replace || params.dateImprintText.isBlank())) params.dateImprintText = dateText
        if (dataText.isNotEmpty() && (replace || params.dateImprintDataText.isBlank())) params.dateImprintDataText = dataText
    }

    companion object {
        private val datePattern = Regex("^(\\d{4})[:-](\\d{2})[:-](\\d{2})(?:[ T].*)?$")

        fun from(exif: ExifInterface?): DateImprintMetadata = from(exif, takenEpochMs = null)

        /**
         * [takenEpochMs]：相册 `DATE_TAKEN`（EXIF 没有拍摄日期时用它兜底）。
         * EXIF 的拍摄时刻优先级永远更高。
         */
        fun from(exif: ExifInterface?, takenEpochMs: Long?): DateImprintMetadata {
            if (exif == null && takenEpochMs == null) return DateImprintMetadata()
            // DateTime is a modification timestamp. Do not substitute it or the file/system clock.
            val date = exif?.let { e ->
                listOf(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED)
                    .firstNotNullOfOrNull { tag -> parseDate(e.getAttribute(tag)) }
            } ?: takenEpochMs?.takeIf { it > 0L }?.let { ms ->
                runCatching {
                    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                }.getOrNull()
            }
            val data = exif?.let { e ->
                fun number(tag: String): Double? = e.getAttributeDouble(tag, Double.NaN)
                    .takeIf { it.isFinite() && it > 0 && it <= 1_000_000_000 }
                buildList {
                    number(ExifInterface.TAG_FOCAL_LENGTH)?.let { add("${decimal(it)}mm") }
                    number(ExifInterface.TAG_F_NUMBER)?.let { add("F${decimal(it)}") }
                    number(ExifInterface.TAG_EXPOSURE_TIME)?.let { seconds ->
                        val reciprocal = 1.0 / seconds
                        val denominator = reciprocal.roundToLong()
                        val shutter = if (seconds < 1 && denominator >= 2 && abs(reciprocal - denominator) / reciprocal < .005)
                            "1/$denominator" else decimal(seconds)
                        add("${shutter}s")
                    }
                    number(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { add("ISO${decimal(it)}") }
                }.joinToString(" ")
            }.orEmpty()
            return DateImprintMetadata(date?.let(::formatDate).orEmpty(), data)
        }

        /** 后背字样：`'26 11 24` —— 两位年、月/日不补零，和传统胶片后背的排字一致。 */
        private fun formatDate(day: LocalDate) =
            "'${(day.year % 100).toString().padStart(2, '0')} ${day.monthValue} ${day.dayOfMonth}"

        private fun parseDate(value: String?): LocalDate? {
            val match = datePattern.matchEntire(value?.trim().orEmpty()) ?: return null
            return runCatching {
                val (year, month, day) = match.destructured
                require(year.toInt() > 0)
                LocalDate.of(year.toInt(), month.toInt(), day.toInt())
            }.getOrNull()
        }

        private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.4f", value)
            .trimEnd('0').trimEnd('.')
    }
}
