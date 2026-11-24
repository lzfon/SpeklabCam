package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.export.TiffWriter
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.*
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** Explicit camera-file regression. Originals and raw reference hashes remain local. */
internal class RawDecoderUpgradeAudit(private val test: Instrumentation, private val output: File) {
    private val context = test.targetContext
    private val directory = File(context.getExternalFilesDir(null), "raw-upgrade")

    fun legacy(baseline: Boolean): String {
        val sources = directory.listFiles { file -> file.name.startsWith("legacy-") }
            .orEmpty().sortedBy { it.name }
        check(sources.size >= 8) { "Install six NEFs and the two documented DNG fixtures" }
        val report = JSONArray()
        val reference = File(output, "raw-upgrade-legacy-baseline.json")
        val expected = if (baseline) null else JSONArray(reference.readText())
        for ((index, source) in sources.withIndex()) {
            val entry = JSONObject().put("file", source.name).put("source_sha256", sha(source))
            val uri = Uri.fromFile(source)
            for (custom in listOf(false, true)) {
                val mul = if (custom) floatArrayOf(2.1f, 1f, 1.5f, 1f) else null
                val times = JSONArray()
                var checksum = ""
                repeat(3) { sample ->
                    val start = SystemClock.elapsedRealtimeNanos()
                    val raw = RawDecoder.decode(context, uri, true, mul)
                    val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    val next = floats(raw.linearProPhoto)
                    if (sample > 0) check(next == checksum) { "Nondeterministic RAW decode" }
                    checksum = next
                    entry.put("${custom}_width", raw.width).put("${custom}_height", raw.height)
                    entry.put("${custom}_matrix", floats(raw.camXyz)).put("${custom}_wb", floats(raw.camMul))
                    if (sample > 0) times.put(elapsed) // First pass warms decoder and file cache.
                }
                entry.put("${custom}_pixels", checksum).put("${custom}_ms", times)
                RawDecoder.decodeForExport(context, uri, 320, mul).use { raw ->
                    entry.put("${custom}_export", floats(raw.copySmallLinearRgb()))
                }
                if (expected != null) {
                    val old = expected.getJSONObject(index)
                    check(old.getString("source_sha256") == entry.getString("source_sha256"))
                    for (field in listOf("width", "height", "matrix", "wb", "pixels", "export")) {
                        val key = "${custom}_$field"
                        check(old.get(key).toString() == entry.get(key).toString()) { "${source.name} changed $key" }
                    }
                }
            }
            report.put(entry)
            File(output, if (baseline) reference.name else "raw-upgrade-legacy-candidate.json").writeText(report.toString(2))
            test.sendStatus(0, android.os.Bundle().apply { putString("stream", "RAW ${source.name}: exact preview, metadata and bounded export verified\n") })
        }
        return "${sources.size} legacy RAWs: default/custom-WB previews, camera metadata and export samples ${if (baseline) "recorded" else "match old decoder bit for bit"}"
    }

    fun highEfficiency(): String {
        check(RawDecoder.decoderIdentity.contains("0.23.0-Snapshot202611"))
        check(RawDecoder.decoderIdentity.contains("a9a14b1970d188c25dade81f8326ff00a6e33649"))
        val source = File(directory, "z5ii-he-star.NEF")
        val reference = JSONObject(File(directory, "z5ii-reference.json").readText())
        check(sha(source) == reference.getString("source_sha256"))
        val uri = Uri.fromFile(source)
        val report = JSONObject().put("decoder", RawDecoder.decoderIdentity)
        RawDecoder.decodeForExport(context, uri, 320).use { raw ->
            check(raw.width == reference.getInt("width") && raw.height == reference.getInt("height"))
            check(floats(raw.copySmallLinearRgb()) == reference.getString("linear_sha256")) {
                "HE output differs from the independently built official decoder"
            }
        }
        // Cancellation must occur inside HE tile processing, not merely before opening a file.
        val executor = Executors.newSingleThreadExecutor()
        val callbacks = AtomicInteger()
        try {
            val future = executor.submit<Throwable?> {
                runCatching {
                    RawDecoder.decodeForExport(context, uri, 0,
                        cancellation = RawDecodeCancellation { callbacks.incrementAndGet() >= 20 }).close()
                }.exceptionOrNull()
            }
            val started = SystemClock.elapsedRealtime()
            val error = future.get(8, TimeUnit.SECONDS)
            check(error is SpkException && error.message.orEmpty().contains("cancel", true)) { "$error" }
            check(callbacks.get() >= 20)
            report.put("cancel_ms", SystemClock.elapsedRealtime() - started).put("cancel_callbacks", callbacks.get())
        } finally { executor.shutdownNow() }
        val broken = File(directory, "he-truncated.raw")
        try {
            source.inputStream().use { input -> broken.outputStream().use { target ->
                val bytes = ByteArray(4 * 1024 * 1024)
                val count = input.read(bytes)
                target.write(bytes, 0, count)
            } }
            check(runCatching { RawDecoder.decodeForExport(context, Uri.fromFile(broken), 320).close() }
                .exceptionOrNull() is SpkException) { "Truncated HE was accepted" }
        } finally { broken.delete() }
        val engine = EngineClient.create(context)
        try {
            val params = EditorParams().apply { grainActive = false; glareActive = false }
            val decodeStart = SystemClock.elapsedRealtime()
            RawDecoder.decodeForExport(context, uri, 0).use { raw ->
                check(raw.width == 6064 && raw.height == 4040)
                report.put("full_decode_ms", SystemClock.elapsedRealtime() - decodeStart)
                report.put("pss_after_decode_kb", Debug.getPss())
                engine.openRaw(raw, params.openJson(raw.width, raw.height, "sRGB", "ProPhoto RGB"))
            }
            val renderStart = SystemClock.elapsedRealtime()
            engine.renderForExport().use { image ->
                check(image.width == 6064 && image.height == 4040)
                report.put("full_render_ms", SystemClock.elapsedRealtime() - renderStart)
                report.put("pss_after_render_kb", Debug.getPss())
                engine.closeSession()
                val digest = MessageDigest.getInstance("SHA-256")
                val destination = File(output, "raw-upgrade-he.tiff")
                try {
                    val grade = Grade(1f, 1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f,
                        FloatArray(3), FloatArray(3), FloatArray(3))
                    DigestOutputStream(destination.outputStream().buffered(), digest).use {
                        TiffWriter.writeNative(image, grade, null, it)
                    }
                    check(destination.length() > 6064L * 4040L * 6L)
                    report.put("tiff_bytes", destination.length()).put("tiff_sha256", hex(digest.digest()))
                } finally { destination.delete() }
            }
        } finally { engine.close() }
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val old = EditStore.load(context, uri)
        val auto = vm.smartAutoEnabled
        try {
            await { !vm.busy && vm.engine != null }
            EditStore.save(context, uri, EditorParams().apply { grainActive = false; glareActive = false })
            test.runOnMainSync { vm.smartAutoEnabled = false; vm.openPhotos(listOf(uri)) }
            await { !vm.busy && (vm.hasPhoto || vm.errorMessage != null) }
            check(vm.errorMessage == null && vm.currentIsRaw && vm.rendered != null)
            check(vm.sourceBitmap != null)
            test.waitForIdleSync()
            test.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(output, "raw-upgrade-he-editor.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally {
            if (old != null) EditStore.save(context, uri, old)
            test.runOnMainSync { vm.smartAutoEnabled = auto; activity.finish() }
        }
        File(output, "raw-upgrade-he-report.json").writeText(report.toString(2))
        return "Z5II HE*: independent RAW pixel reference, mid-decode cancellation, truncated-input rejection, full 6064x4040 TIFF16 and real editor import passed; $report"
    }

    private fun await(condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) {
            check(SystemClock.elapsedRealtime() - start < 120_000)
            Thread.sleep(30)
        }
    }

    private fun floats(values: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) {
            check(value.isFinite())
            if (!bytes.hasRemaining()) { digest.update(bytes.array()); bytes.clear() }
            bytes.putFloat(value)
        }
        digest.update(bytes.array(), 0, bytes.position())
        return hex(digest.digest())
    }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
