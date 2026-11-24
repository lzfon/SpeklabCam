package com.spektralab.android.core.model

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface

/**
 * 系统「照片选择器」的 uri 形如：
 *   content://media/picker/0/com.android.providers.media.photopicker/media/19698
 * authority 只是 "media"，选择器标记藏在 path 里。
 * 这种 uri 查 DISPLAY_NAME 得到的是行号 ID，读到的字节也是脱敏副本（GPS 被抹掉），
 * 因此取名、读 EXIF、导出取样都必须先换成底层 MediaStore 条目 uri。
 *
 * 换的方式有两层：
 *  1) MediaStore.getMediaUri（选择器会话还在时有效）
 *  2) 会话失效后用选择器副本自带 EXIF 的「拍摄时间 + 像素尺寸」在相册里找回唯一一条
 */
object MediaSourceUri {

    private var memoKey: String? = null
    private var memoValue: Uri? = null

    fun isPicker(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val path = uri.path.orEmpty()
        if (path.contains("photopicker", ignoreCase = true)) return true
        return path.trim('/').split('/').any { it.equals("picker", ignoreCase = true) }
    }

    /** 选择器 uri → 底层 MediaStore uri；换不成时原样返回。 */
    fun resolve(context: Context, uri: Uri): Uri {
        if (!isPicker(uri)) return uri
        // getMediaUri 自 API 30 起可用；旧门槛 34 会让 Android 11–13 直接放弃最优映射路径。
        if (Build.VERSION.SDK_INT < 30) return uri
        return runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull() ?: uri
    }

    /**
     * 给「取名 / 读 EXIF / 导出取样」用的最终源地址：
     * 能换成真实相册条目的就换（这样才有真文件名和未脱敏的 GPS），换不成保持原样。
     */
    fun resolveForOriginal(context: Context, uri: Uri): Uri {
        if (!isPicker(uri)) return uri
        synchronized(this) { if (memoKey == uri.toString()) memoValue?.let { return it } }
        val mapped = resolve(context, uri)
        val result = if (mapped != uri) mapped else findMediaUri(context, uri) ?: uri
        synchronized(this) { memoKey = uri.toString(); memoValue = result }
        return result
    }

    /** 用选择器副本自带 EXIF 的拍摄时间与像素尺寸，在相册里找回那一条（要求唯一）。 */
    private fun findMediaUri(context: Context, pickerUri: Uri): Uri? {
        val exif = runCatching {
            context.contentResolver.openInputStream(pickerUri)?.use { ExifInterface(it) }
        }.getOrNull() ?: return null
        val taken = runCatching {
            java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                .parse(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).orEmpty())
        }.getOrNull() ?: return null
        val width = exif.getAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION)?.toIntOrNull()
        val height = exif.getAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION)?.toIntOrNull()
        val bases = listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Files.getContentUri("external"))
        for (base in bases) {
            val hits = runCatching {
                val rows = mutableListOf<Pair<Long, Boolean>>()
                context.contentResolver.query(
                    base,
                    arrayOf(android.provider.BaseColumns._ID, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT),
                    "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} <= ?",
                    arrayOf((taken.time - 5_000L).toString(), (taken.time + 5_000L).toString()),
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val w = if (cursor.isNull(1)) null else cursor.getInt(1)
                        val h = if (cursor.isNull(2)) null else cursor.getInt(2)
                        val sizeOk = width == null || height == null || w == null || h == null ||
                            (minOf(w, h) == minOf(width, height) && maxOf(w, h) == maxOf(width, height))
                        if (sizeOk) rows.add(id to sizeOk)
                    }
                }
                rows
            }.getOrDefault(emptyList())
            if (hits.size == 1) return ContentUris.withAppendedId(base, hits.first().first)
        }
        return null
    }
}
