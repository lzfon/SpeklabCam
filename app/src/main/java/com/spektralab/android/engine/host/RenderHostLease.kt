package com.spektralab.android.engine.host

import android.content.*
import android.os.*
import com.spektralab.android.engine.ExportFailure
import com.spektralab.android.engine.ExportFailureCode
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import kotlinx.coroutines.*

/** Keep the foreground service bound across preparation, encoding and every photo of a batch. */
internal class RenderHostLease private constructor(private val context: Context) : Closeable, ServiceConnection {
    private val open = AtomicBoolean(true)
    private val intent = Intent(context, RenderHostService::class.java)
    private var bound = false
    private val ready = CompletableDeferred<Messenger>()

    override fun onServiceConnected(name: ComponentName, binder: IBinder) { ready.complete(Messenger(binder)) }
    override fun onServiceDisconnected(name: ComponentName) = disconnected()
    override fun onBindingDied(name: ComponentName) = disconnected()
    override fun onNullBinding(name: ComponentName) = disconnected()

    private fun disconnected() { if (open.get()) synchronized(lock) { failed = true } }

    suspend fun releaseDevice() {
        if (synchronized(lock) { failed }) return // The owning process has already died.
        val host = withTimeout(10_000) { ready.await() }
        if (!host.binder.isBinderAlive) return
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        val pid = AtomicInteger()
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.data.getString(RenderHostProtocol.JOB) != id) return@Handler true
            when (message.what) {
                RenderHostProtocol.DEVICE_RELEASING -> pid.set(message.data.getInt("pid"))
                RenderHostProtocol.DEVICE_RELEASED -> {
                    val error = message.data.getString("error")
                    done.complete(error == null)
                }
            }
            true
        })
        try {
            host.send(Message.obtain(null, RenderHostProtocol.RELEASE_DEVICE).apply {
                replyTo = reply; data = Bundle().apply { putString(RenderHostProtocol.JOB, id) }
            })
        } catch (failure: RemoteException) {
            if (!host.binder.isBinderAlive) return
            throw failure
        }
        if (withTimeoutOrNull(8_000) { done.await() } == true) return
        if (!host.binder.isBinderAlive) return
        val process = pid.get()
        if (process > 0 && process != android.os.Process.myPid()) {
            android.os.Process.killProcess(process)
            if (withTimeoutOrNull(3_000) { while (host.binder.isBinderAlive) delay(25); true } == true) return
        }
        throw ExportFailure(ExportFailureCode.TIMEOUT, "Render device did not release; preview retained")
    }

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        synchronized(lock) { count--; if (count == 0) failed = false }
        if (bound) { context.unbindService(this); bound = false }
        // The service stops itself only after its last client unbinds.
    }

    companion object {
        private val lock = Any()
        private var count = 0
        private var failed = false
        val active: Boolean get() = synchronized(lock) { count > 0 }

        fun checkHealthy() = synchronized(lock) {
            if (count == 0) failed = false
            if (failed) throw ExportFailure(ExportFailureCode.HOST_DIED, "Render host disconnected; retry the export")
        }

        /** Called at the user's export action, while the activity is visible. */
        fun begin(context: Context): RenderHostLease {
            checkHealthy()
            val lease = RenderHostLease(context.applicationContext)
            try {
                lease.context.startForegroundService(lease.intent)
                lease.bound = lease.context.bindService(lease.intent, lease, Context.BIND_AUTO_CREATE)
                check(lease.bound) { "Cannot retain render host" }
                synchronized(lock) { count++ }
                return lease
            } catch (failure: Throwable) {
                lease.open.set(false)
                if (lease.bound) lease.context.unbindService(lease)
                if (!active) lease.context.stopService(lease.intent)
                throw failure
            }
        }
    }
}
