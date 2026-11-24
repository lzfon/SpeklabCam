package com.spektralab.android.data.export

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.Closeable
import java.io.File

/** Unlinked before any photo bytes are written. Kernel reclaims storage on the last FD close/crash. */
internal class EphemeralExportFile private constructor(
    val reader: ParcelFileDescriptor,
    val writer: ParcelFileDescriptor,
) : Closeable {
    override fun close() { try { reader.close() } finally { writer.close() } }

    companion object {
        fun create(context: Context): EphemeralExportFile {
            val name = File.createTempFile("export-fd-", ".tmp", context.cacheDir)
            var reader: ParcelFileDescriptor? = null
            var writer: ParcelFileDescriptor? = null
            try {
                Os.chmod(name.path, 384) // 0600, before any private photo data exists
                reader = ParcelFileDescriptor.open(name, ParcelFileDescriptor.MODE_READ_ONLY)
                writer = ParcelFileDescriptor.open(name, ParcelFileDescriptor.MODE_WRITE_ONLY)
                check(name.delete()) { "Cannot unlink temporary export storage" }
                return EphemeralExportFile(reader, writer)
            } catch (failure: Throwable) {
                reader?.close(); writer?.close(); name.delete(); throw failure
            }
        }
    }
}
