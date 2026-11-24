package com.spektralab.android.data.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.OutputStream

/** Encode to an unpublished destination, commit once, and remove incomplete files on failure. */
internal class ExportPublisher(
    private val context: Context,
    private val checkCancelled: () -> Unit,
    private val onCommitted: () -> Unit,
    private val directory: Uri? = null,
) {
    internal var onPrepared: (String) -> Unit = {}
    internal var onReadyToCommit: () -> Unit = {}
    /** 原片拍摄时刻（毫秒）。写入相册 DATE_TAKEN 列，扫描器就不会按 UTC 误读无时区标记的 EXIF。 */
    internal var takenEpochMs: Long? = null
    fun write(name: String, mime: String, encode: (OutputStream) -> Unit): String {
        checkCancelled()
        if (directory != null) {
            val tree = DocumentFile.fromTreeUri(context, directory)
                ?: error("无法访问所选文件夹")
            val file = tree.createFile(mime, name) ?: error("无法在所选文件夹创建文件")
            return try {
                onPrepared(file.uri.toString())
                val stream = context.contentResolver.openOutputStream(file.uri) ?: error("无法写入所选文件夹")
                stream.use(encode)
                checkCancelled()
                onReadyToCommit()
                onCommitted()
                "文件夹: ${file.name}"
            } catch (error: Throwable) {
                runCatching { file.delete() }
                throw error
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/spektralab")
                put(MediaStore.Images.Media.IS_PENDING, 1)
                takenEpochMs?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            resolver.insert(collection, values)?.let { uri ->
                try {
                    onPrepared(uri.toString())
                    val stream = resolver.openOutputStream(uri) ?: error("无法写入相册文件")
                    stream.use(encode)
                    checkCancelled()
                    onReadyToCommit()
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    check(resolver.update(uri, values, null, null) > 0) { "无法完成相册文件" }
                    onCommitted()
                    return "相册: $uri"
                } catch (error: Throwable) {
                    resolver.delete(uri, null, null)
                    throw error
                }
            }
        }

        val directory = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        val destination = File(directory, name)
        val pending = File(directory, "$name.pending")
        try {
            onPrepared(pending.absolutePath)
            pending.outputStream().use(encode)
            checkCancelled()
            onReadyToCommit()
            check(pending.renameTo(destination)) { "无法完成相册文件" }
            onCommitted()
            return destination.absolutePath
        } finally {
            pending.delete()
        }
    }
}
