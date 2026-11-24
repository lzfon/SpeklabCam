package com.spektralab.android

import android.app.Instrumentation
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.*
import com.spektralab.android.data.cache.LightTablePreviews
import com.spektralab.android.data.export.TableExportSession
import com.spektralab.android.data.library.LightTableProjectStore
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.lighttable.FilmCanvasState
import com.spektralab.android.feature.lighttable.ViewingPhoto
import java.io.File
import java.util.UUID

/** A viewing original must not acquire hidden historical film/grade settings on export. */
internal class TableAppearanceAudit(private val test: Instrumentation, private val output: File) {
    fun run(): String {
        val context = test.targetContext
        val colors = listOf(0xff8b2a12.toInt(), 0xff124b29.toInt(), 0xff204b80.toInt())
        val files = colors.mapIndexed { index, color -> File(output, "appearance-${System.nanoTime()}-$index.png").also { file ->
            val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        } }
        val uris = files.map(Uri::fromFile)
        // Edge lettering is catalog-derived; initialize it before capturing any baseline.
        com.spektralab.android.engine.StockCatalog.load(context)
        uris.forEach { EditStore.save(context, it, EditorParams().apply {
            grainActive = false; glareActive = false; brightness = 35f; temperature = .7f
        }) }
        val originalEdits = uris.map { EditSnapshot.capture(EditStore.load(context, it)!!) }
        val originalRecords = uris.map { EditStore.loadForTransfer(context, it).json }
        fun assertEditsUnchanged(stage: String) {
            val current = uris.map { EditSnapshot.capture(EditStore.load(context, it)!!) }
            current.forEachIndexed { index, snapshot ->
                if (snapshot != originalEdits[index]) {
                    File(output, "appearance-edit-before-$index.json").writeText(originalEdits[index].json)
                    File(output, "appearance-edit-after-$index.json").writeText(snapshot.json)
                    val before = org.json.JSONObject(originalEdits[index].json)
                    val after = org.json.JSONObject(snapshot.json)
                    val keys = (before.keys().asSequence().toList() + after.keys().asSequence().toList()).distinct()
                    val differences = keys.filter { before.opt(it)?.toString() != after.opt(it)?.toString() }
                        .joinToString { "$it: ${before.opt(it)} -> ${after.opt(it)}" }
                    error("Source edit $index changed after $stage: $differences")
                }
            }
            check(uris.map { EditStore.loadForTransfer(context, it).json } == originalRecords) {
                "Stored source edit bytes changed after $stage"
            }
        }
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val media = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun ids(): Set<Long> = context.contentResolver.query(media, arrayOf("_id"), "_display_name LIKE ?",
            arrayOf("Theia_Project_%"), null)!!.use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
        val before = ids()
        val cache = LightTablePreviews(File(context.cacheDir, "lighttable-previews"))
        val originals = files.map { BitmapFactory.decodeFile(it.path)!! }
        try {
            await { !vm.busy && vm.engine != null }
            assertEditsUnchanged("activity initialization")
            val sources = uris.mapIndexed { index, uri -> EditStore.tableSource(context,
                TableSource(uri.toString(), files[index].name, pixelWidth = 640, pixelHeight = 480,
                    preview = cache.put(originals[index]), renderedPreview = cache.put(originals[index]),
                    appearance = if (index == 0) TableAppearance.EDITED else TableAppearance.ORIGINAL)) }
            val items = (0..8).map { index -> TablePlacement(UUID.randomUUID().toString(), sources[index % 3],
                (index % 3) * 200f, (index / 3) * 150f, 200f, 150f) }
            val scene = LightTableScene(items)
            check(LightTableScene.fromJson(scene.toJson()) == scene)
            val storeDir = File(output, "appearance-project-${System.nanoTime()}")
            val project = LightTableProjectStore(storeDir).use { it.save(null, "九张照片", scene) }
            val restored = LightTableProjectStore(storeDir).use { it.read(project.id).scene }
            check(restored == scene)
            // Capture the visible provenance through the real draft model, independent of export.
            val photos = uris.mapIndexed { index, uri -> ViewingPhoto(uri.toString(), originals[index], originals[index],
                files[index].name, appearance = sources[index].appearance) }
            val captured = vm.lightTableProjects.captureDraft(photos, FilmCanvasState())
            check(captured.items.map { it.source.appearance } == sources.map { it.appearance })
            val request = TableExportRequest(restored, TableExportOptions(mode = TableOutputMode.CONTACT, longEdge = 1600), null)
            TableExportSession.prepare(context, request, {}, { _, _ -> }).use { session ->
                check(session.inputs[0].params != null)
                check(session.inputs.drop(1).all { it.params == null && it.storedParams != null })
            }
            // Old projects had no appearance field and incorrectly labeled originals as rendered.
            val legacy = request.copy(scene = scene.copy(items = items.map { item ->
                item.copy(source = item.source.copy(appearance = null))
            }))
            TableExportSession.prepare(context, legacy, {}, { _, _ -> }).use { session ->
                check(session.inputs.all { it.params == null && it.storedParams != null }) {
                    "Historical original preview was reinterpreted as an edited photo"
                }
            }
            val small = Bitmap.createScaledBitmap(originals[1], 192, 144, true)
            val smallKey = try { cache.put(small) } finally { small.recycle() }
            val smallLegacy = legacy.copy(scene = legacy.scene.copy(items = listOf(
                legacy.scene.items[1].copy(source = sources[1].copy(appearance = null,
                    preview = smallKey, renderedPreview = smallKey)))))
            TableExportSession.prepare(context, smallLegacy, {}, { _, _ -> }).use {
                check(it.inputs.single().params == null) { "Small old original preview was not recognized" }
            }
            val altered = originals[0].copy(Bitmap.Config.ARGB_8888, true).apply { eraseColor(0xffeeeeee.toInt()) }
            val editedKey = try { cache.put(altered) } finally { altered.recycle() }
            val editedLegacy = legacy.copy(scene = legacy.scene.copy(items = listOf(
                legacy.scene.items[0].copy(source = sources[0].copy(appearance = null,
                    preview = editedKey, renderedPreview = editedKey)))))
            TableExportSession.prepare(context, editedLegacy, {}, { _, _ -> }).use {
                check(it.inputs.single().params != null) { "Edited old preview was mistaken for original" }
            }
            val latest = request.copy(options = request.options.copy(latestEdits = true))
            TableExportSession.prepare(context, latest, {}, { _, _ -> }).use { session ->
                check(session.inputs.all { it.params != null })
            }
            assertEditsUnchanged("session preparation")
            fun export(source: TableExportRequest): Bitmap {
                val seen = ids(); var outcome: Result<String>? = null
                test.runOnMainSync { check(vm.exportTableProject(source) { outcome = it }) }
                await { !vm.isExporting && outcome != null }
                check(outcome!!.isSuccess) { outcome!!.exceptionOrNull().toString() }
                return context.contentResolver.openInputStream(ContentUris.withAppendedId(media, (ids() - seen).single()))!!
                    .use { BitmapFactory.decodeStream(it)!! }
            }
            val bitmap = export(request)
            try {
                val plan = request.plan()
                for (item in items.filter { it.source.appearance == TableAppearance.ORIGINAL }) {
                    val index = sources.indexOf(item.source)
                    val x = (plan.offsetX + (item.x + item.width / 2) * plan.scale).toInt()
                    val y = (plan.offsetY + (item.y + item.height / 2) * plan.scale).toInt()
                    check(bitmap.getPixel(x, y) == colors[index]) { "Original appearance changed at row ${item.y}" }
                }
                File(output, "table-appearance-nine-export.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
            assertEditsUnchanged("visible-appearance export")
            val changed = export(latest)
            try {
                val plan = latest.plan(); val item = items[1]
                val color = changed.getPixel((plan.offsetX + (item.x + 100f) * plan.scale).toInt(),
                    (plan.offsetY + (item.y + 75f) * plan.scale).toInt())
                check(color != colors[1]) { "Explicit latest edit was ignored" }
            } finally { changed.recycle() }
            assertEditsUnchanged("latest-edit export")
            check(LightTableProjectStore(storeDir).use { it.read(project.id).scene } == scene)
            check(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("table-export-") })
            return "Nine mixed/repeated placements: original pixels preserved despite saved bright/warm edits; edited sources rendered; explicit latest opts into edits; old original-cache projects recognized; save/close/reopen provenance and params unchanged"
        } finally {
            originals.forEach(Bitmap::recycle)
            (ids() - before).forEach { context.contentResolver.delete(ContentUris.withAppendedId(media, it), null, null) }
            test.runOnMainSync { activity.finish() }
            files.forEach(File::delete)
        }
    }

    private fun await(condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) { check(SystemClock.elapsedRealtime() - start < 120000); Thread.sleep(30) }
    }
}
