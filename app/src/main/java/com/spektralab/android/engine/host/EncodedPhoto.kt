package com.spektralab.android.engine.host

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.EOFException
import java.io.OutputStream

/** Completed encoded bytes in unlinked storage. Publishing remains with the caller. */
internal class EncodedPhoto(
    private val descriptor: ParcelFileDescriptor,
    val width: Int,
    val height: Int,
) : Closeable {
    private val size = descriptor.statSize

    init {
        require(width in 1..131072 && height in 1..131072 && size > 0) { "Incomplete encoded photo" }
    }

    fun copyTo(output: OutputStream, check: () -> Unit) {
        ParcelFileDescriptor.AutoCloseInputStream(descriptor.dup()).use { input ->
            input.channel.position(0)
            val bytes = ByteArray(64 * 1024)
            var remaining = size
            while (remaining > 0) {
                check()
                val count = input.read(bytes, 0, minOf(remaining, bytes.size.toLong()).toInt())
                if (count <= 0) throw EOFException("Incomplete encoded photo bytes")
                output.write(bytes, 0, count)
                remaining -= count
            }
        }
        check()
    }

    override fun close() = descriptor.close()
}
