package com.spektralab.android.data.export

import android.content.Context
import android.net.Uri
import com.spektralab.android.core.model.*
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.engine.RawDecoder
import com.spektralab.android.engine.host.DescriptorRgba16Source
import com.spektralab.android.engine.host.RenderHostClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** One process-wide export coordinator; the caller retains its existing interactive task transaction. */
internal class ExportCoordinator private constructor(context: Context) {
    private val app = context.applicationContext
    private val store = ExportJobStore(app)
    private val gate = Mutex()
    private var recovered = false

    suspend fun recover() = gate.withLock {
        withContext(Dispatchers.IO) { if (!recovered) { store.recover(); recovered = true } }
    }

    inner class Task internal constructor(val request: ExportFrameRequest, private val verify: () -> Unit,
        private val recordReceipt: Boolean = true) {
        private var state = ExportJobStore.State.QUEUED
        private var destination: String? = null
        private val hostPids = mutableSetOf<Int>()

        fun phase(next: ExportJobStore.State) {
            check(state !in setOf(ExportJobStore.State.SUCCEEDED, ExportJobStore.State.FAILED, ExportJobStore.State.CANCELLED))
            check(next.ordinal >= state.ordinal)
            state = next; if (recordReceipt) store.write(request, next, destination)
        }

        fun publishing(value: String) { destination = value; phase(ExportJobStore.State.PUBLISHING) }
        fun succeeded(value: String) { destination = value; phase(ExportJobStore.State.SUCCEEDED) }
        fun verifySource() = verify()

        internal fun hostStarted(pid: Int) {
            check(pid != android.os.Process.myPid()) { "Export host must not share the editor process" }
            synchronized(hostPids) { hostPids += pid }
        }

        internal fun hasIsolatedHost() = synchronized(hostPids) { hostPids.isNotEmpty() }

        suspend fun render(source: ExportSourceSnapshot, check: () -> Unit,
                           progress: (String, Float) -> Unit): DescriptorRgba16Source {
            phase(ExportJobStore.State.RENDERING)
            return RenderHostClient(app).also { it.onHostStarted = ::hostStarted }
                .render(request, source.descriptor, check, progress)
        }

        internal fun failed(failure: Throwable) {
            if (state == ExportJobStore.State.SUCCEEDED) return
            val cancelled = failure is kotlinx.coroutines.CancellationException || failure.message.orEmpty().contains("cancel", true)
            if (recordReceipt) store.write(request, if (cancelled) ExportJobStore.State.CANCELLED else ExportJobStore.State.FAILED, destination,
                "${failure.javaClass.simpleName}: ${failure.message}")
        }
    }

    suspend fun <T> run(uri: Uri, name: String, edit: EditSnapshot, options: ExportOptions,
                        check: () -> Unit, recordReceipt: Boolean = true, frozen: ExportFrameRequest? = null,
                        artifact: ExportArtifact = ExportArtifact.FINISHED,
                        block: suspend (Task, ExportSourceSnapshot) -> T): T = gate.withLock {
        withContext(Dispatchers.IO) { if (!recovered) { store.recover(); recovered = true } }
        check()
        var staged: ExportSourceSnapshot? = null
        try {
            withContext(Dispatchers.IO) { staged = measureExport("source_snapshot") { ExportSourceSnapshot.create(app, uri, options.keepLocation, check) } }
            val source = requireNotNull(staged)
            val runtimeIdentity=withContext(Dispatchers.IO){ExportRuntimeIdentity.current(app)}
            val request = frozen ?: ExportFrameRequest(UUID.randomUUID().toString(), source.sha256, name.take(512),
                RawDecoder.isRaw(app, uri, name), edit, options, RawDecoder.decoderIdentity, EngineClient.buildInfo(),
                appIdentity=runtimeIdentity,artifact=artifact)
            require(request.sourceHash==source.sha256){"源照片已经改变，此任务未重新导出"}
            require(request.decoderIdentity==RawDecoder.decoderIdentity && request.rendererIdentity==EngineClient.buildInfo()) {
                "解码器或显影引擎已更新，请打开原图重新导出"
            }
            require(request.appIdentity==runtimeIdentity){"应用或材料资源已更新，请打开原图重新导出"}
            require(request.edit==edit && request.options==options)
            require(request.artifact==artifact)
            val task = Task(request, { source.verifyOriginal(app, uri, options.keepLocation, check) }, recordReceipt)
            withContext(Dispatchers.IO) { if (recordReceipt) store.write(request, ExportJobStore.State.QUEUED,
                sourceReference=com.spektralab.android.data.local.EditStore.editSourceUri(app,uri).toString());task.phase(ExportJobStore.State.PREPARING) }
            try { block(task, source) }
            catch (failure: Throwable) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { task.failed(failure) }
                throw failure
            }
        } finally { staged?.close() }
    }

    companion object {
        @Volatile private var instance: ExportCoordinator? = null
        fun get(context: Context): ExportCoordinator = instance ?: synchronized(this) {
            instance ?: ExportCoordinator(context).also { instance = it }
        }
    }
}
