package com.spektralab.android.data.export

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.spektralab.android.data.local.EditStore
import java.io.Closeable
import java.security.MessageDigest
import java.io.InputStream

/** Exact source bytes, retained only by open descriptors during one export; never a durable photo cache. */
internal class ExportSourceSnapshot private constructor(private val storage: EphemeralExportFile, val sha256: String) : Closeable {
    val descriptor: ParcelFileDescriptor get() = storage.reader
    override fun close() = storage.close()

    fun verifyOriginal(context: Context, reference: Uri, keepLocation: Boolean, check: () -> Unit) {
        val source = EditStore.mediaSourceUri(context, reference)
        // A cloud stream is fetched once; the private immutable snapshot is its source authority.
        if (source.scheme != "file" && source.authority != "media") return
        val digest = MessageDigest.getInstance("SHA-256")
        open(context, source, keepLocation).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                check(); val count = input.read(buffer); if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(digest.digest().hex() == sha256) { "源照片在导出期间被更改，请重新打开后导出" }
    }

    companion object {
        fun create(context: Context, reference: Uri, keepLocation: Boolean, check: () -> Unit): ExportSourceSnapshot {
            val storage = EphemeralExportFile.create(context)
            val digest = MessageDigest.getInstance("SHA-256")
            val source = EditStore.mediaSourceUri(context, reference)
            try {
                open(context, source, keepLocation).use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(storage.writer).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var bytes = 0L
                        while (true) {
                            check()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            check(bytes <= 1024L * 1024 * 1024 && context.cacheDir.usableSpace > count + 64L * 1024 * 1024) { "Insufficient export staging space" }
                            digest.update(buffer, 0, count); output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                check(); check(storage.reader.statSize > 0) { "Empty export source" }
                return ExportSourceSnapshot(storage, digest.digest().hex())
            } catch (failure: Throwable) { storage.close(); throw failure }
        }

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

        private fun open(context: Context, source: Uri, keepLocation: Boolean): InputStream {
            val original = if (android.os.Build.VERSION.SDK_INT >= 29 && keepLocation && source.authority == "media" &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_MEDIA_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runCatching { context.contentResolver.openInputStream(android.provider.MediaStore.setRequireOriginal(source)) }.getOrNull()
            } else null
            return requireNotNull(original ?: context.contentResolver.openInputStream(source)) { "Cannot read export source" }
        }
    }
}
