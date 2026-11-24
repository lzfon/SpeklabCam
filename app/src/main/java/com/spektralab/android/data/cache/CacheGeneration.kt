package com.spektralab.android.data.cache

import android.util.AtomicFile
import java.io.File

/** Cache keys already include install identity; retire their now-unreachable pixels on upgrade. */
internal class CacheGeneration(file: File) {
    private val stamp = AtomicFile(file)
    @Volatile private var ready: String? = null

    fun ensure(expected: String, retire: () -> Unit) {
        if (ready == expected) return
        synchronized(this) {
            if (ready == expected) return
            require(expected.toByteArray(Charsets.UTF_8).size < 128)
            val previous = runCatching {
                stamp.openRead().use { input ->
                    val bytes = ByteArray(128)
                    var size = 0
                    while (size < bytes.size) {
                        val count = input.read(bytes, size, bytes.size - size)
                        if (count <= 0) break
                        size += count
                    }
                    String(bytes, 0, size, Charsets.UTF_8)
                }
            }.getOrNull()
            if (previous != expected) {
                retire()
                val output = stamp.startWrite()
                try {
                    output.write(expected.toByteArray(Charsets.UTF_8))
                    stamp.finishWrite(output)
                } catch (failure: Throwable) {
                    stamp.failWrite(output)
                    throw failure
                }
            }
            ready = expected
        }
    }
}
