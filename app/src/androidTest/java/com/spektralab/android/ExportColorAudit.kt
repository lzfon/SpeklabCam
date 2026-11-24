package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.core.model.ExportRealisation
import com.spektralab.android.data.export.ExportSourceDecoder
import com.spektralab.android.data.export.PngRowWriter
import com.spektralab.android.data.export.TiffWriter
import com.spektralab.android.data.cache.ExportRenderCache
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.ColorPipeline
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.engine.StockCatalog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Same decoded input and dimensions isolate source, compute and encoder differences. */
internal class ExportColorAudit(private val test: Instrumentation, private val output: File) {
    fun application(matrix: Boolean): String {
        val context = test.targetContext
        val original = File(output, "user-sea.jpg")
        check(original.isFile)
        val owned = File(output, "export-colour-owned.jpg").also { original.copyTo(it, overwrite = true) }
        val records = JSONArray()
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        fun main(action: () -> Unit) = test.runOnMainSync { action() }
        fun waitUntil(condition: () -> Boolean) {
            val start = SystemClock.elapsedRealtime()
            while (!condition()) { check(SystemClock.elapsedRealtime() - start < 240_000) { vm.errorMessage.orEmpty() }; SystemClock.sleep(30) }
            test.waitForIdleSync()
        }
        main { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val created = mutableListOf<Uri>()
        try {
            waitUntil { vm.engine != null && !vm.busy }
            val (films, papers) = StockCatalog.load(context)
            val inputs = if (matrix) listOf(owned, File(output, "pc-parity-DSC_5099.NEF")) else listOf(owned)
            for (input in inputs) {
                check(input.isFile)
                val raw = input.extension == "NEF"
                val choices = if (matrix && !raw) listOf("fujifilm_velvia_100", "fujifilm_provia_100f", "kodak_ektachrome_100", "kodak_portra_400", "cinestill_800t", "ilford_hp5_plus_400")
                    else listOf("fujifilm_velvia_100")
                for (film in choices) {
                    val stock = films.single { it.id == film }
                    val paper = papers.firstOrNull { it.id == stock.targetPrint && StockCatalog.canPrint(film, it.id) }
                        ?: papers.first { StockCatalog.canPrint(film, it.id) }
                    val edit = EditorParams().apply { filmId = film; paperId = paper.id }
                    // openPhotos saves the outgoing edit before loading the next source.
                    // Give every stock an independent photo so this cannot overwrite the fixture edit.
                    val photo = File(output, "colour-stock-$film-${input.name}").also { input.copyTo(it, overwrite = true) }
                    val uri = Uri.fromFile(photo)
                    EditStore.save(context, uri, edit)
                    main { vm.smartAutoEnabled = false; vm.openPhotos(listOf(uri)) }
                    waitUntil { vm.rendered != null && !vm.busy && vm.renderTaskState.active == null }
                    check(vm.params.filmId == film && vm.params.paperId == paper.id) { "Fixture restored the wrong material pair" }
                    val grade = EditorViewModel::class.java.getDeclaredMethod("grade", EditorParams::class.java)
                        .apply { isAccessible = true }.invoke(vm, edit) as Grade
                    val sizes = if (matrix && film == "fujifilm_velvia_100") listOf(2048, 2560, 3840, 0) else listOf(2048)
                    for (size in sizes) {
                        val prefix = "matrix-${if (raw) "raw" else "jpg"}-$film-$size"
                        // A local complete-dispatch reference sees the same frozen params, source and dimensions.
                        val options = com.spektralab.android.core.model.ExportOptions("PNG", size, "sRGB", 100, false, false, true)
                        EngineClient.create(context).use { engine ->
                            ExportSourceDecoder.open(context, engine, uri, raw, edit, options) {}
                            engine.renderForExport().use { frame ->
                                File(output, "$prefix-reference.png").outputStream().use { PngRowWriter.write(frame, grade, it, {}) }
                                File(output, "$prefix-reference.tiff").outputStream().use { TiffWriter.writeNative(frame, grade, null, it, {}) }
                            }
                        }
                        ExportRenderCache.get(context).clearDisposable()
                        for (format in listOf("JPEG", "PNG", "TIFF")) {
                            val start = SystemClock.elapsedRealtime()
                            main { vm.dismissError(); vm.exportFormat = format; vm.exportRes = size; vm.exportColorSpace = "sRGB"; vm.jpegQuality = 100; vm.export() }
                            waitUntil { !vm.isExporting && !vm.busy }
                            val error = vm.errorMessage
                            if (error != null) {
                                records.put(JSONObject().put("input", input.name).put("film", film).put("size", size).put("format", format).put("error", error))
                            } else {
                                val saved = Uri.parse(vm.log.substringAfterLast("相册: ").lineSequence().first().trim())
                                check(saved.scheme == "content"); created += saved
                                val destination = File(output, "$prefix.${format.lowercase()}")
                                context.contentResolver.openInputStream(saved)!!.use { source -> destination.outputStream().use { source.copyTo(it) } }
                                val record = JSONObject().put("input", input.name).put("film", film).put("paper", paper.id)
                                    .put("size", size).put("format", format).put("file", destination.name)
                                    .put("elapsed_ms", SystemClock.elapsedRealtime() - start).put("reference", "$prefix-reference")
                                if (format != "TIFF") {
                                    val actual = checkNotNull(BitmapFactory.decodeFile(destination.path))
                                    val reference = checkNotNull(BitmapFactory.decodeFile(File(output, "$prefix-reference.png").path))
                                    try {
                                        check(actual.width == reference.width && actual.height == reference.height)
                                        val a = IntArray(actual.width * 16); val b = IntArray(a.size)
                                        var sum = 0L; var max = 0
                                        for (y in 0 until actual.height step 16) {
                                            val count = minOf(16, actual.height - y)
                                            actual.getPixels(a, 0, actual.width, 0, y, actual.width, count)
                                            reference.getPixels(b, 0, reference.width, 0, y, reference.width, count)
                                            for (i in 0 until actual.width * count) for (channel in 0..2) {
                                                val shift = channel * 8
                                                val d = kotlin.math.abs((a[i] ushr shift and 255) - (b[i] ushr shift and 255))
                                                sum += d; max = maxOf(max, d)
                                            }
                                        }
                                        record.put("mean_u8", sum.toDouble() / (actual.width.toLong() * actual.height * 3)).put("max_u8", max)
                                            .put("width", actual.width).put("height", actual.height)
                                    } finally { actual.recycle(); reference.recycle() }
                                }
                                records.put(record)
                            }
                            File(output, "colour-application-matrix.json").writeText(records.toString(2))
                        }
                        if (matrix && film == "fujifilm_velvia_100") {
                            EngineClient.create(context).use { engine ->
                                ExportSourceDecoder.open(context, engine, uri, raw, edit, options.copy(format = "TIFF", colorSpace = "Display P3")) {}
                                engine.renderForExport().use { frame ->
                                    File(output, "$prefix-p3-reference.tiff").outputStream().use { TiffWriter.writeNative(frame, grade, null, it, {}) }
                                }
                            }
                            val start = SystemClock.elapsedRealtime()
                            main { vm.dismissError(); vm.exportFormat = "TIFF"; vm.exportRes = size; vm.exportColorSpace = "Display P3"; vm.export() }
                            waitUntil { !vm.isExporting && !vm.busy }
                            val record = JSONObject().put("input", input.name).put("film", film).put("size", size)
                                .put("format", "TIFF-P3").put("reference", "$prefix-p3-reference").put("elapsed_ms", SystemClock.elapsedRealtime() - start)
                            if (vm.errorMessage != null) record.put("error", vm.errorMessage)
                            else {
                                val saved = Uri.parse(vm.log.substringAfterLast("相册: ").lineSequence().first().trim())
                                check(saved.scheme == "content"); created += saved
                                val destination = File(output, "$prefix-p3.tiff")
                                context.contentResolver.openInputStream(saved)!!.use { source -> destination.outputStream().use { source.copyTo(it) } }
                                record.put("file", destination.name)
                            }
                            records.put(record)
                            File(output, "colour-application-matrix.json").writeText(records.toString(2))
                        }
                    }
                }
            }
            check((0 until records.length()).all { i -> records.getJSONObject(i).let {
                !it.has("error") && if (it.getString("format") == "PNG") it.getInt("max_u8") == 0 else it.optDouble("mean_u8", 0.0) < 3.0
            } }) {
                "Real export differs from same-size whole-render reference; see colour-application-matrix.json"
            }
            return "${records.length()} actual application exports compared with same-size native references; TIFF external full-sample verification required"
        } finally {
            created.forEach { context.contentResolver.delete(it, null, null) }
            main { activity.finish() }
        }
    }

    fun diagnose(): String {
        val source = File(output, "user-sea.jpg")
        check(source.isFile)
        val records = JSONArray()
        val (films, papers) = StockCatalog.load(test.targetContext)
        for (film in listOf("fujifilm_velvia_100", "kodak_portra_400", "cinestill_800t")) {
            val stock = films.single { it.id == film }
            val paper = papers.firstOrNull { it.id == stock.targetPrint && StockCatalog.canPrint(film, it.id) }
                ?: papers.first { StockCatalog.canPrint(film, it.id) }
            val params = EditorParams().apply {
                filmId = film; paperId = paper.id
                grainActive = false; glareActive = false
            }
            val bitmap = ExportSourceDecoder.bitmap(test.targetContext, Uri.fromFile(source), 2048)
            try {
                val linear = ColorPipeline.bitmapToLinearSrgb(bitmap)
                fun render(windowed: Boolean, fromBitmap: Boolean): ShortArray = EngineClient.create(test.targetContext).use { engine ->
                    if (fromBitmap) engine.openBitmap(bitmap, params.openJson(bitmap.width, bitmap.height))
                    else engine.open(linear, bitmap.width, bitmap.height, 3, params.openJson(bitmap.width, bitmap.height))
                    val seed = ExportRealisation.fresh(params)
                    val result = if (windowed) engine.renderExportResolved(seed.film, seed.print) else engine.renderForExport()
                    result.use {
                        val pixels = ShortArray(it.width * it.height * 4)
                        it.readRows(0, it.height, pixels)
                        val name = "colour-$film-${if (windowed) "window" else "whole"}-${if (fromBitmap) "bitmap" else "float"}.png"
                        val image = Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888)
                        try {
                            val argb = IntArray(it.width * it.height) { i ->
                                fun c(n: Int) = ((pixels[i * 4 + n].toInt() and 65535) * 255 + 32767) / 65535
                                (255 shl 24) or (c(0) shl 16) or (c(1) shl 8) or c(2)
                            }
                            image.setPixels(argb, 0, it.width, 0, 0, it.width, it.height)
                            File(output, name).outputStream().use { file -> image.compress(Bitmap.CompressFormat.PNG, 100, file) }
                        } finally { image.recycle() }
                        pixels
                    }
                }
                val reference = render(false, false)
                for ((windowed, fromBitmap) in listOf(false to true, true to true, true to false)) {
                    val actual = render(windowed, fromBitmap)
                    var max = 0; var different = 0L; var sum = 0L
                    for (i in reference.indices) {
                        val d = kotlin.math.abs((reference[i].toInt() and 65535) - (actual[i].toInt() and 65535))
                        max = maxOf(max, d); if (d > 0) different++; sum += d
                    }
                    records.put(JSONObject().put("film", film).put("paper", paper.id)
                        .put("windowed", windowed).put("bitmap_input", fromBitmap)
                        .put("max_u16", max).put("different", different).put("mean_u16", sum.toDouble() / reference.size))
                }
                File(output, "colour-diagnostic.json").writeText(records.toString(2))
            } finally { bitmap.recycle() }
        }
        return records.toString()
    }
}
