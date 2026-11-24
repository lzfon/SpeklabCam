package com.spektralab.android.engine.host

import android.content.Context
import com.spektralab.android.engine.EngineClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One native owner across service rebindings. Only the empty device is kept briefly for a batch. */
internal object NativeSessionOwner {
    val executor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "theia-render-owner") }
    // Accessed exclusively on executor. No source, negative, result or photo FD is retained here.
    private var engine: EngineClient? = null
    private var generation = 0L

    /** Serialized acknowledgement boundary before the UI recreates its own preview device. */
    fun releaseDevice() {
        val current = engine; engine = null; generation++
        current?.close()
    }

    fun <T> use(context: Context, block: (EngineClient) -> T): T {
        val current = engine ?: EngineClient.create(context).also { engine = it }
        try {
            return block(current)
        } catch (failure: Throwable) {
            engine = null
            runCatching { current.close() }
            throw failure
        } finally {
            current.closeSession()
            val token = ++generation
            executor.schedule({
                if (token == generation) {
                    releaseDevice()
                }
            }, 10, TimeUnit.SECONDS)
        }
    }
}
