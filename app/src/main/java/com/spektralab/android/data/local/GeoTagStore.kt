package com.spektralab.android.data.local

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 位置信息保险柜：照片刚导入时照片选择器的授权还活着，那一刻能读到原片 GPS。
 * 之后导出时授权多半已经失效（系统只给脱敏副本），所以导入/打开时先读一次存起来，
 * 导出时若原片读不到位置，就用这里存下的一份补上。
 */
object GeoTagStore {

    private val tags = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
    )

    private fun directory(context: Context) = File(context.filesDir, "geo-tags")

    private fun fileFor(context: Context, uri: Uri): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
        val key = digest.take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory(context), "$key.json")
    }

    private fun values(exif: ExifInterface?): Map<String, String> {
        if (exif == null) return emptyMap()
        return tags.mapNotNull { tag -> exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { tag to it } }.toMap()
    }

    /** 从原片 EXIF 抄一份位置；没有位置就什么都不做（不覆盖已有的）。 */
    fun capture(context: Context, uri: Uri, exif: ExifInterface?) {
        val found = values(exif).filterKeys { it.startsWith("GPS") }
        if (found.isEmpty() || !found.containsKey(ExifInterface.TAG_GPS_LATITUDE)) return
        runCatching {
            directory(context).mkdirs()
            val json = JSONObject().apply { found.forEach { (k, v) -> put(k, v) } }
            fileFor(context, uri).writeText(json.toString())
        }
    }

    fun load(context: Context, uri: Uri?): Map<String, String> = runCatching {
        if (uri == null) return emptyMap()
        val file = fileFor(context, uri)
        if (!file.exists()) return emptyMap()
        val json = JSONObject(file.readText())
        val result = mutableMapOf<String, String>()
        json.keys().forEach { key -> json.optString(key).takeIf { it.isNotBlank() }?.let { result[key] = it } }
        result
    }.getOrDefault(emptyMap())

    fun has(context: Context, uri: Uri?): Boolean = load(context, uri).isNotEmpty()
}
