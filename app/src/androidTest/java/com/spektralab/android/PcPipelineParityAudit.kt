package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.engine.RawDecoder
import com.spektralab.android.engine.StockCatalog
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject

/** Independent PC binary reference; fixture and its SHA are supplied explicitly by the runner. */
internal class PcPipelineParityAudit(private val test: Instrumentation, private val output: File) {
    fun selection(): String {
        val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        try {
            val start = SystemClock.elapsedRealtime()
            while (vm.engine == null || vm.busy || vm.films.isEmpty()) {
                check(SystemClock.elapsedRealtime() - start < 60000)
                SystemClock.sleep(30)
            }
            // Like PC, an explicit film click follows its declared paper, even when
            // the current colour paper happens to be chemically compatible.
            for (film in vm.films) {
                val target = vm.papers.firstOrNull { it.id == film.targetPrint && StockCatalog.canPrint(film.id, it.id) }
                    ?: continue
                test.runOnMainSync {
                    vm.params.filmId = "kodak_portra_400"
                    vm.params.paperId = "kodak_portra_endura"
                    vm.selectFilm(film.id)
                }
                check(vm.params.paperId == target.id) {
                    "${film.id}: expected PC paper ${target.id}, retained ${vm.params.paperId}"
                }
            }
            test.runOnMainSync {
                vm.params.filmId = "cinestill_800t"; vm.params.paperId = "kodak_2383"
                vm.params.halationModelVersion = 1; vm.params.halationHeadroomEv = 4f
                vm.selectFilm("cinestill_800t")
            }
            check(vm.params.halationModelVersion == 2) { "Explicit CineStill re-pick retained a legacy Gaussian model" }
            val json = com.spektralab.android.core.model.EditSnapshot.capture(vm.params).json
            test.runOnMainSync { vm.selectFilm("cinestill_800t") }
            check(com.spektralab.android.core.model.EditSnapshot.capture(vm.params).json == json)
            check(!vm.hasPhoto && vm.lastResult == null) { "Material-only selection decoded or rendered a photo" }
            return "All declared compatible film/paper targets match PC; same-film CineStill explicitly selects physical optics; repeated standard selection is a no-op; no decode/render"
        } finally { test.runOnMainSync { activity.finish() } }
    }

    fun capture(): String {
        val source = File(output, "pc-parity-DSC_5099.NEF")
        check(source.isFile) { "Explicit PC parity RAW fixture missing" }
        val report = JSONArray()
        StockCatalog.load(test.targetContext)
        EngineClient.create(test.targetContext).use { engine ->
            for ((name, size, space) in listOf(
                Triple("android-full-srgb", 0, "sRGB"),
                Triple("android-2560-srgb", 2560, "sRGB"),
                Triple("android-full-prophoto", 0, "ProPhoto RGB"),
                Triple("android-legacy-pair", 2560, "sRGB"),
                Triple("android-classic-2383", 2560, "sRGB"),
                Triple("android-physical-grain", 2560, "sRGB"),
            )) {
                val params = EditorParams().apply {
                    filmId = "cinestill_800t"; paperId = "kodak_2383"
                    filmFormatMm = 36.023762f; halationModelVersion = 2
                    grainActive = false; glareActive = false
                    if (name in setOf("android-legacy-pair", "android-classic-2383", "android-physical-grain")) {
                        grainActive = true; glareActive = true
                        if (name != "android-physical-grain") halationModelVersion = 1
                        if (name == "android-legacy-pair") paperId = "kodak_portra_endura"
                    }
                }
                val start = SystemClock.elapsedRealtime()
                RawDecoder.decodeForExport(test.targetContext, Uri.fromFile(source), size).use { raw ->
                    engine.openRaw(raw, params.openJson(raw.width, raw.height, space, "ProPhoto RGB"))
                }
                val native = JSONObject(engine.getParams())
                engine.renderExportOnce().use { result ->
                    val row = ShortArray(result.width * 4)
                    val bytes = ByteBuffer.allocate(row.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    File(output, "$name.rgba16").outputStream().buffered().use { stream ->
                        for (y in 0 until result.height) {
                            result.readRows(y, 1, row)
                            bytes.clear(); bytes.asShortBuffer().put(row); stream.write(bytes.array())
                        }
                    }
                    report.put(JSONObject().put("name", name).put("width", result.width).put("height", result.height)
                        .put("ms", SystemClock.elapsedRealtime() - start).put("native_params", native))
                    File(output, "pc-parity-capture.json").writeText(report.toString(2))
                }
            }
        }
        return "Captured explicit DSC_5099.NEF full/sized native outputs for independent PC comparison; not a parity verdict"
    }
}
