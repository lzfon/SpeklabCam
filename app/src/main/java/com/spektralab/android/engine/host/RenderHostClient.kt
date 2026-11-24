package com.spektralab.android.engine.host

import android.content.*
import android.os.*
import com.spektralab.android.core.model.ExportFrameRequest
import com.spektralab.android.engine.SpkException
import com.spektralab.android.engine.ExportFailure
import com.spektralab.android.engine.ExportFailureCode
import com.spektralab.android.data.export.ExportMetadata
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.Closeable

/** Asynchronous control channel. Progress/cancel never queue behind a native dispatch. */
internal class RenderHostClient(private val context: Context) {
    internal var onHostStarted: ((Int) -> Unit)? = null

    suspend fun render(
        request: ExportFrameRequest,
        source: ParcelFileDescriptor,
        check: () -> Unit,
        progress: (String, Float) -> Unit,
    ): DescriptorRgba16Source = run(request.jobId, request.toJson(), source, RenderHostProtocol.START, check, progress, {}) { fd, data ->
        DescriptorRgba16Source(fd, data.getInt(RenderHostProtocol.WIDTH), data.getInt(RenderHostProtocol.HEIGHT))
    }

    suspend fun encode(
        request: ExportFrameRequest,
        source: ParcelFileDescriptor,
        width: Int,
        height: Int,
        pixelOffset: Long,
        metadata: ExportMetadata,
        check: () -> Unit,
        progress: (String, Float) -> Unit = { _, _ -> },
    ): EncodedPhoto = run(request.jobId, request.toJson(), source, RenderHostProtocol.ENCODE, check, progress, {
        putInt(RenderHostProtocol.WIDTH, width)
        putInt(RenderHostProtocol.HEIGHT, height)
        putLong(RenderHostProtocol.OFFSET, pixelOffset)
        val encoded = JSONObject(metadata.attributes).toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= 128 * 1024) { "Export metadata exceeds host capacity" }
        putString(RenderHostProtocol.METADATA, encoded)
    }) { fd, data ->
        EncodedPhoto(fd, data.getInt(RenderHostProtocol.WIDTH), data.getInt(RenderHostProtocol.HEIGHT))
    }

    suspend fun composeTable(
        manifest: ParcelFileDescriptor,
        sources: List<ParcelFileDescriptor>,
        check: () -> Unit,
        progress: (String, Float) -> Unit = { _, _ -> },
    ): EncodedPhoto = run(UUID.randomUUID().toString(), "", manifest, RenderHostProtocol.COMPOSE_TABLE,
        check, progress, {
            require(sources.size in 1..200)
            putParcelableArrayList(RenderHostProtocol.TABLE_SOURCES, ArrayList(sources))
        }) { fd, data ->
        EncodedPhoto(fd, data.getInt(RenderHostProtocol.WIDTH), data.getInt(RenderHostProtocol.HEIGHT))
    }

    private suspend fun <T : Closeable> run(
        jobId: String,
        requestJson: String,
        source: ParcelFileDescriptor,
        operation: Int,
        check: () -> Unit,
        progress: (String, Float) -> Unit,
        extras: Bundle.() -> Unit,
        receive: (ParcelFileDescriptor, Bundle) -> T,
    ): T = coroutineScope {
        val connection = Connection(jobId, progress, receive)
        var transferred = false
        var watcher: Job? = null
        try {
            withContext(Dispatchers.Main.immediate) { connection.bind() }
            val host = withTimeout(10_000) { connection.ready.await() }
            check()
            require(requestJson.toByteArray(Charsets.UTF_8).size <= 128 * 1024)
            host.send(Message.obtain(null, operation).apply {
                replyTo = connection.receiver
                data = Bundle().apply {
                    putString(RenderHostProtocol.JOB, jobId)
                    putString(RenderHostProtocol.REQUEST, requestJson)
                    putParcelable(RenderHostProtocol.DESCRIPTOR, source)
                    extras()
                }
            })
            watcher = launch(Dispatchers.IO) {
                while (isActive && !connection.result.isCompleted) {
                    delay(100)
                    try { check() } catch (failure: Exception) {
                        connection.result.completeExceptionally(failure)
                        break
                    }
                }
            }
            val result = connection.result.await()
            check()
            transferred = true
            result
        } finally {
            withContext(NonCancellable) {
                watcher?.cancelAndJoin()
                connection.finish(transferred)
            }
        }
    }

    private inner class Connection<T : Closeable>(
        val id: String,
        val progress: (String, Float) -> Unit,
        val receive: (ParcelFileDescriptor, Bundle) -> T,
    ) : ServiceConnection {
        val ready = CompletableDeferred<Messenger>()
        val result = CompletableDeferred<T>()
        private val terminated = CompletableDeferred<Unit>()
        private var host: Messenger? = null
        private var binder: IBinder? = null
        private var bound = false
        private var pid = 0
        private var returned: T? = null
        private val intent = Intent(context, RenderHostService::class.java)
        private val death = IBinder.DeathRecipient { failed("Render host exited; the current edits are preserved") }
        val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
            message.data.classLoader = ParcelFileDescriptor::class.java.classLoader
            @Suppress("DEPRECATION")
            val descriptor = message.data.getParcelable<ParcelFileDescriptor>(RenderHostProtocol.DESCRIPTOR)
            if (message.data.getString(RenderHostProtocol.JOB) != id) { descriptor?.close(); return@Handler true }
            when (message.what) {
                RenderHostProtocol.STARTED -> {
                    pid = message.data.getInt("pid")
                    onHostStarted?.invoke(pid)
                }
                RenderHostProtocol.PROGRESS -> if (!result.isCompleted) runCatching {
                    progress(message.data.getString("stage").orEmpty(), message.data.getFloat("percent"))
                }.onFailure { result.completeExceptionally(it) }
                RenderHostProtocol.COMPLETE -> {
                    terminated.complete(Unit)
                    if (descriptor != null) {
                        try {
                            val output = receive(descriptor, message.data)
                            returned = output
                            if (!result.complete(output)) {
                                returned = null
                                output.close()
                            }
                        } catch (failure: Throwable) {
                            if (failure !is Exception && failure !is OutOfMemoryError) throw failure
                            runCatching { descriptor.close() }
                            result.completeExceptionally(failure)
                        }
                    } else result.completeExceptionally(SpkException("Missing host result"))
                }
                RenderHostProtocol.FAILED -> {
                    descriptor?.close(); terminated.complete(Unit)
                    val error = message.data.getString("error") ?: "Render host failed"
                    val code = runCatching { ExportFailureCode.valueOf(message.data.getString("code").orEmpty()) }
                        .getOrDefault(ExportFailureCode.INTERNAL)
                    result.completeExceptionally(ExportFailure(code, error))
                }
                RenderHostProtocol.ABORTING -> {
                    descriptor?.close()
                    // Cleanup still waits for termination or kills a stuck worker before GPU hand-back.
                    result.completeExceptionally(ExportFailure(ExportFailureCode.TIMEOUT,
                        message.data.getString("error") ?: "Render host timed out"))
                }
                else -> descriptor?.close()
            }
            true
        })

        fun bind() {
            RenderHostLease.checkHealthy()
            if (!RenderHostLease.active) context.startForegroundService(intent)
            bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE)
            check(bound) { "Cannot bind render host" }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            try {
                service.linkToDeath(death, 0)
                val messenger = Messenger(service); host = messenger; ready.complete(messenger)
            } catch (_: RemoteException) { failed("Render host connection failed") }
        }

        override fun onServiceDisconnected(name: ComponentName) = failed("Render host disconnected")
        override fun onBindingDied(name: ComponentName) = failed("Render host binding died")
        override fun onNullBinding(name: ComponentName) = failed("Render host unavailable")

        private fun failed(message: String) {
            val error = ExportFailure(ExportFailureCode.HOST_DIED, message)
            ready.completeExceptionally(error); result.completeExceptionally(error); terminated.complete(Unit)
        }

        suspend fun finish(transferred: Boolean) {
            if (!transferred) result.cancel() // Late replies must close their FD rather than acquire an abandoned result.
            if (!terminated.isCompleted) {
                runCatching { host?.send(Message.obtain(null, RenderHostProtocol.CANCEL).apply {
                    data = Bundle().apply { putString(RenderHostProtocol.JOB, id) }
                }) }
                if (withTimeoutOrNull(8_000) { terminated.await(); true } != true && pid > 0 && pid != Process.myPid()) {
                    // Same-UID private service, known from this connection. End a stuck worker
                    // before the UI is allowed to recreate its GPU owner.
                    Process.killProcess(pid)
                    withTimeoutOrNull(3_000) { terminated.await() }
                }
            }
            if (!transferred) returned?.close()
            withContext(Dispatchers.Main.immediate) {
                binder?.let { runCatching { it.unlinkToDeath(death, 0) } }
                if (bound) { context.unbindService(this@Connection); bound = false }
                if (!RenderHostLease.active) context.stopService(intent)
            }
        }
    }
}
