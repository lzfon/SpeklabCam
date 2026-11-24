package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.data.cache.ExportRenderCache
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/** Real editor/host/encoder path. Dimensions alone cannot detect a silently damaged GPU frame. */
internal class FullSizeCineStillAudit(private val test: Instrumentation, private val output: File) {
    fun run(): String {
        val context = test.targetContext
        val source = File(output, "pc-parity-DSC_5099.NEF")
        check(source.isFile) { "Explicit DSC_5099.NEF fixture missing" }
        val uri = Uri.fromFile(source)
        EditStore.save(context, uri, EditorParams().apply {
            filmId = "cinestill_800t"; paperId = "kodak_2383"
            halationModelVersion = 2; halationHeadroomEv = 4f
            // Leave grain and glare enabled, as in a normal explicit film selection.
        })
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val report = JSONArray()
        val created = mutableListOf<Uri>()
        try {
            await { vm.engine != null && !vm.busy }
            test.runOnMainSync { vm.openPhotos(listOf(uri)) }
            await { vm.errorMessage != null || vm.rendered != null && !vm.busy }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            val reference = checkNotNull(vm.rendered)
            val width = 128
            val height = (reference.height.toLong() * width / reference.width).toInt()
            val expected = sample(reference, width, height)
            // Explicit audit: force a cold render so a prior unrestricted
            // frame cannot mask a restricted-descriptor execution failure.
            ExportRenderCache.get(context).clearDisposable()
            for ((resolution, format) in listOf(2560 to "JPEG", 0 to "JPEG", 0 to "PNG")) {
                test.runOnMainSync {
                    vm.dismissError(); vm.exportFormat = format; vm.exportRes = resolution
                    vm.exportColorSpace = "sRGB"; vm.export()
                }
                await { !vm.isExporting && !vm.busy }
                check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
                val saved = Uri.parse(vm.log.substringAfterLast("相册: ").lineSequence().first().trim())
                check(saved.scheme == "content") { "Missing completed export: ${vm.log.takeLast(500)}" }
                created += saved
                val file = File(output, "cinestill-$resolution.${format.lowercase()}")
                context.contentResolver.openInputStream(saved)!!.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                if (resolution == 0) check(bounds.outWidth == 6064 && bounds.outHeight == 4040)
                else check(maxOf(bounds.outWidth, bounds.outHeight) == resolution)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = Integer.highestOneBit((maxOf(bounds.outWidth, bounds.outHeight) / 512).coerceAtLeast(1))
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = checkNotNull(BitmapFactory.decodeFile(file.path, options))
                val actual = try { sample(decoded, width, height) } finally { decoded.recycle() }
                val correlation = correlation(expected, actual)
                check(correlation > .90) { "$format/$resolution corrupt appearance: correlation=$correlation" }
                report.put(JSONObject().put("resolution", resolution).put("format", format)
                    .put("width", bounds.outWidth).put("height", bounds.outHeight)
                    .put("preview_correlation", correlation).put("file", file.name))
                File(output, "full-cinestill-report.json").writeText(report.toString(2))
            }
            return "CineStill 800T/Vision 2383 physical 4EV, grain/glare on; 2560 JPEG and original 6064x4040 JPEG/PNG match editor appearance; $report"
        } finally {
            created.forEach { context.contentResolver.delete(it, null, null) }
            test.runOnMainSync { activity.finish() }
        }
    }

    private fun sample(bitmap: Bitmap, width: Int, height: Int): DoubleArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        return try {
            val argb = IntArray(width * height)
            scaled.getPixels(argb, 0, width, 0, 0, width, height)
            DoubleArray(argb.size) { i ->
                val p = argb[i]
                .2126 * (p ushr 16 and 255) + .7152 * (p ushr 8 and 255) + .0722 * (p and 255)
            }
        } finally { if (scaled !== bitmap) scaled.recycle() }
    }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var covariance = 0.0; var va = 0.0; var vb = 0.0
        for (i in a.indices) {
            val x = a[i] - ma; val y = b[i] - mb
            covariance += x * y; va += x * x; vb += y * y
        }
        return covariance / sqrt(va * vb)
    }

    private fun await(condition: () -> Boolean) {
        val started = SystemClock.elapsedRealtime()
        while (!condition()) {
            check(SystemClock.elapsedRealtime() - started < 240_000) { "CineStill full-size export timed out" }
            SystemClock.sleep(30)
        }
    }
}
