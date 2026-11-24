package com.spektralab.android

import android.app.Instrumentation
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import com.spektralab.android.core.model.*
import com.spektralab.android.data.export.*
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.engine.RawDecoder
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal class ExportLifecycleAudit(private val test: Instrumentation) {
    fun run(): String = runBlocking {
        val context = test.targetContext
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val store = ExportJobStore(context)
        val records = mutableListOf<File>()
        val media = mutableListOf<Uri>()
        val source = File(context.cacheDir, "source-version-audit-${System.nanoTime()}.png")
        fun request(): ExportFrameRequest {
            val request = ExportFrameRequest(UUID.randomUUID().toString(), "0".repeat(64), "audit.png", false,
                EditSnapshot.capture(EditorParams()), ExportOptions("PNG", 0, "sRGB", 100, false, false, true),
                RawDecoder.decoderIdentity, EngineClient.buildInfo())
            records += File(context.filesDir, "export-jobs/${request.jobId}.json")
            return request
        }
        fun reserve(pending: Boolean) = requireNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Theia_Audit_journal_${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.IS_PENDING, if (pending) 1 else 0)
        })).also { media += it }
        fun exists(uri: Uri) = resolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { it.moveToFirst() } == true
        try {
            val interrupted = request()
            val pending = reserve(true)
            store.write(interrupted, ExportJobStore.State.PUBLISHING, pending.toString())
            val committed = request()
            val finished = reserve(false)
            store.write(committed, ExportJobStore.State.PUBLISHING, finished.toString())
            store.recover()
            check(!exists(pending)) { "Interrupted pending output survived recovery" }
            check(exists(finished)) { "Recovery removed a committed file" }
            check(JSONObject(records[0].readText()).getString("state") == "FAILED")
            check(JSONObject(records[1].readText()).getString("state") == "SUCCEEDED")

            val image = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
            try { source.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { image.recycle() }
            val options = ExportOptions("PNG", 0, "sRGB", 100, false, false, true)
            var rejected: Uri? = null
            val failure = runCatching {
                ExportCoordinator.get(context).run(Uri.fromFile(source), source.name, EditSnapshot.capture(EditorParams()), options, {}) { task, snapshot ->
                    records += File(context.filesDir, "export-jobs/${task.request.jobId}.json")
                    check(snapshot.descriptor.statSize == source.length())
                    task.phase(ExportJobStore.State.ENCODING)
                    val publisher = ExportPublisher(context, {}, {}).apply {
                        onPrepared = { value -> rejected = Uri.parse(value); task.publishing(value) }
                        onReadyToCommit = task::verifySource
                    }
                    publisher.write("Theia_Audit_changed_source.png", "image/png") { stream ->
                        stream.write(byteArrayOf(1, 2, 3, 4))
                        source.appendBytes(byteArrayOf(99)) // Change the original during encoding.
                    }
                }
            }.exceptionOrNull()
            check(failure != null && failure.message.orEmpty().contains("更改")) { "Changed source was published: $failure" }
            check(rejected != null && !exists(rejected!!)) { "Rejected output remained in MediaStore" }
            "Pending output removed after interrupted publication; committed receipt preserved; changed original rejected at commit; private journal states verified"
        } finally {
            media.forEach { runCatching { resolver.delete(it, null, null) } }
            records.forEach { it.delete(); File(it.path + ".bak").delete() }
            source.delete()
        }
    }
}
