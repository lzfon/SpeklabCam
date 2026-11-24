package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Process
import com.spektralab.android.core.model.*
import com.spektralab.android.data.export.ExportSourceDecoder
import com.spektralab.android.data.export.ExportSourceSnapshot
import com.spektralab.android.data.export.EphemeralExportFile
import com.spektralab.android.engine.*
import com.spektralab.android.engine.host.RenderHostClient
import com.spektralab.android.engine.host.RenderHostLease
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Actual Binder/process/descriptor tests; no pretend service or pixel transport. */
internal class RenderHostAudit(private val test: Instrumentation, private val output: File) {
    fun run(): String = runBlocking {
        val context = test.targetContext
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // A cancelled connection must ignore late progress and close a late result descriptor.
        val clientForLateReply = RenderHostClient(context)
        val connectionType = RenderHostClient::class.java.declaredClasses.single { it.simpleName == "Connection" }
        val constructor = connectionType.declaredConstructors.single().apply { isAccessible = true }
        val lateProgress = java.util.concurrent.atomic.AtomicInteger()
        val callback: (String, Float) -> Unit = { _, _ -> lateProgress.incrementAndGet() }
        val connection = constructor.newInstance(clientForLateReply, "late-reply-audit", callback)
        val completed = connectionType.getDeclaredField("result").apply { isAccessible = true }.get(connection) as kotlinx.coroutines.CompletableDeferred<*>
        val receiver = connectionType.getDeclaredField("receiver").apply { isAccessible = true }.get(connection) as android.os.Messenger
        completed.cancel()
        receiver.send(android.os.Message.obtain(null, 4).apply { data = android.os.Bundle().apply {
            putString("job", "late-reply-audit"); putString("stage", "rendering"); putFloat("percent", 50f)
        } })
        EphemeralExportFile.create(context).use { late ->
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(late.writer).use { it.write(ByteArray(8)) }
            receiver.send(android.os.Message.obtain(null, 5).apply { data = android.os.Bundle().apply {
                putString("job", "late-reply-audit"); putInt("width", 1); putInt("height", 1); putParcelable("descriptor", late.reader)
            } })
            test.runOnMainSync { }
            check(lateProgress.get() == 0) { "A retired connection changed UI progress" }
            check(!late.reader.fileDescriptor.valid()) { "A retired connection retained a result FD" }
        }
        val source = File(output, "host-input.png")
        val image = Bitmap.createBitmap(193, 127, Bitmap.Config.ARGB_8888)
        try {
            image.setPixels(IntArray(image.width * image.height) { index ->
                0xff000000.toInt() or ((index * 37 and 255) shl 16) or ((index * 11 and 255) shl 8) or (index * 5 and 255)
            }, 0, image.width, 0, 0, image.width, image.height)
            source.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
        val params = EditorParams().apply { grainActive = false; glareActive = false }
        val frozen = EditSnapshot.capture(params)
        val options = ExportOptions("TIFF", 0, "sRGB", 100, false, false, true)
        val expected = EngineClient.create(context).use { engine ->
            ExportSourceDecoder.open(context, engine, Uri.fromFile(source), false, params, options) {}
            engine.renderExportOnce().use { result -> ShortArray(result.width * result.height * 4).also { result.readRows(0, result.height, it) } }
        }
        try {
            ExportSourceSnapshot.create(context, Uri.fromFile(source), false) {}.use { snapshot ->
                val request = ExportFrameRequest(UUID.randomUUID().toString(), snapshot.sha256, source.name, false,
                    frozen, options, RawDecoder.decoderIdentity, EngineClient.buildInfo())
                params.exposureEv = 3f
                val roundTrip = ExportFrameRequest.fromJson(request.toJson())
                check(roundTrip.edit == frozen && roundTrip.edit.toParams().exposureEv == 0f)
                check(android.system.Os.readlink("/proc/self/fd/${snapshot.descriptor.fd}").endsWith(" (deleted)")) {
                    "Current source snapshot is not anonymous"
                }
                // SIGKILL between allocating an empty inode and unlinking it may leave a
                // zero-byte allocation. Photo bytes are written only after successful unlink.
                check(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("export-fd-") && it.length() > 0L }) {
                    "A named temporary file contains photo bytes"
                }
                var hostPid = 0
                val client = RenderHostClient(context).apply { onHostStarted = { hostPid = it } }
                client.render(roundTrip, snapshot.descriptor, {}, { _, _ -> }).use { result ->
                    check(hostPid > 0 && hostPid != Process.myPid()) { "Renderer ran in the UI process" }
                    check(result.width == 193 && result.height == 127)
                    val actual = ShortArray(expected.size)
                    result.readRows(0, result.height, actual)
                    check(actual.contentEquals(expected)) { "IPC changed U16 pixels" }
                    val row = ShortArray(result.width * 4)
                    result.readRows(result.height - 1, 1, row)
                    check(row.contentEquals(expected.copyOfRange(expected.size - row.size, expected.size)))
                }
                // A killed worker must report failure, and a new connection must recover.
                val crash = RenderHostClient(context).apply { onHostStarted = { pid -> check(pid != Process.myPid()); Process.killProcess(pid) } }
                check(runCatching { crash.render(request, snapshot.descriptor, {}, { _, _ -> }).close() }.isFailure)
                RenderHostClient(context).render(request, snapshot.descriptor, {}, { _, _ -> }).use { result ->
                    val actual = ShortArray(expected.size); result.readRows(0, result.height, actual)
                    check(actual.contentEquals(expected)) { "Host restart changed output" }
                }
                val cancelled = AtomicBoolean(false)
                val cancel = RenderHostClient(context).apply { onHostStarted = { cancelled.set(true) } }
                val failure = runCatching {
                    cancel.render(request, snapshot.descriptor, { if (cancelled.get()) throw SpkException("render cancelled") }, { _, _ -> }).close()
                }.exceptionOrNull()
                check(failure != null && failure.message.orEmpty().contains("cancel", true)) { "Cancellation did not terminate host job: $failure" }
                val wrong = request.copy(decoderIdentity = "not-the-loaded-decoder")
                val identityFailure = runCatching { RenderHostClient(context).render(wrong, snapshot.descriptor, {}, { _, _ -> }).close() }.exceptionOrNull()
                check(identityFailure?.message.orEmpty().contains("identity", true)) { "Mismatched renderer was accepted: $identityFailure" }
                val lease = RenderHostLease.begin(context)
                try {
                    test.runOnMainSync { check(activity.moveTaskToBack(true)) }
                    fun shell(command: String): String = test.uiAutomation.executeShellCommand(command).let { descriptor ->
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
                    }
                    val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                    while (!shell("dumpsys power").contains("Theia:Export")) {
                        check(android.os.SystemClock.elapsedRealtime() < deadline) { "Export CPU lease was not acquired" }
                        Thread.sleep(100)
                    }
                    shell("input keyevent 223") // screen off; CPU is held only for the foreground export
                    // The second frame must not try to start a new foreground service from the background.
                    repeat(2) {
                        RenderHostClient(context).render(request, snapshot.descriptor, {}, { _, _ -> }).use { result ->
                            val actual = ShortArray(expected.size); result.readRows(0, result.height, actual)
                            check(actual.contentEquals(expected)) { "Background batch changed output" }
                        }
                    }
                    lease.releaseDevice()
                    RenderHostClient(context).render(request, snapshot.descriptor, {}, { _, _ -> }).use { result ->
                        val actual = ShortArray(expected.size); result.readRows(0, result.height, actual)
                        check(actual.contentEquals(expected)) { "Explicit device hand-back changed output" }
                    }
                    lease.releaseDevice()
                } finally {
                    test.uiAutomation.executeShellCommand("input keyevent 224").close()
                    lease.close()
                }
            }
            "Separate PID; exact U16/arbitrary rows; immutable request/anonymous descriptors; worker death/restart/cancel/identity rejection; late reply/progress cleanup; screen-off frames and acknowledged device release/reacquire passed"
        } finally {
            source.delete()
            test.runOnMainSync { activity.finish() }
        }
    }
}
