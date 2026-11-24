package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.data.metadata.DateImprintMetadata
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.ui.panels.PrintPanel
import com.spektralab.android.ui.theme.TheiaTheme
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Job
import org.json.JSONObject

/** Metadata-bearing images through the real import, controls, undo, per-photo storage and RAW path. */
internal class DateImprintAudit(private val test: Instrumentation, private val output: File) {
    private val context get() = test.targetContext
    private fun main(action: () -> Unit) = test.runOnMainSync { action() }

    fun run(): String {
        val full = fixture("date-full.jpg", mapOf(
            ExifInterface.TAG_DATETIME_ORIGINAL to "2023:02:28 23:59:10",
            ExifInterface.TAG_DATETIME to "2026:11:15 12:00:00",
            ExifInterface.TAG_FOCAL_LENGTH to "50/1",
            ExifInterface.TAG_F_NUMBER to "2.8",
            ExifInterface.TAG_EXPOSURE_TIME to "0.008",
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "800",
        ))
        val partial = fixture("date-partial.jpg", mapOf(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "3200"))
        val empty = fixture("date-no-exif.png", emptyMap())
        val expected = DateImprintMetadata("'23 2 28", "50mm F2.8 1/125s ISO800")
        val beforeHashes = listOf(full, partial, empty).associate { it.path to hash(it) }
        val exif = ExifInterface(full)
        check(DateImprintMetadata.from(exif) == expected) { "JPEG projection: ${DateImprintMetadata.from(exif)}" }
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            check(DateImprintMetadata.from(exif) == expected) { "Date imprint depends on device number locale" }
        } finally { Locale.setDefault(previousLocale) }
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2023:02:29 12:00:00")
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, "2020:02:29 12:00:00")
        check(DateImprintMetadata.from(exif).dateText == "'20 2 29") { "Leap fallback: ${DateImprintMetadata.from(exif)}" }
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, "0000:00:00 00:00:00")
        check(DateImprintMetadata.from(exif).dateText.isEmpty()) { "Invalid date fell back to modification date" }
        exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.3")
        check("0.3s" in DateImprintMetadata.from(exif).dataText) { "Fractional shutter: ${DateImprintMetadata.from(exif)}" }
        check(!DateImprintMetadata.from(null).available)

        val photos = listOf(full, partial, empty).map(Uri::fromFile)
        photos.forEach { EditStore.save(context, it, EditorParams().apply { grainActive = false; glareActive = false }) }
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        main { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        await { vm.engine != null && !vm.busy }
        val oldAuto = vm.smartAutoEnabled
        try {
            main { vm.smartAutoEnabled = false; vm.openPhotos(photos) }; idle(vm)
            check(vm.dateImprintMetadata == expected && vm.params.dateImprintText.isEmpty())
            main { activity.setContent { TheiaTheme { Surface {
                Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) { PrintPanel(vm) }
            } } } }
            click("日期后背")
            val toggle = reveal { it.isCheckable }
            check(!toggle.isChecked && toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            idle(vm)
            check(vm.params.dateImprintActive && vm.params.dateImprintText == expected.dateText &&
                vm.params.dateImprintDataText == expected.dataText)
            check(!vm.params.grainActive && !vm.params.glareActive && !vm.params.overscanActive)
            reveal { it.isEditable && it.text?.contains("ISO800") == true }
            capture("date-imprint-auto.png")
            main { vm.undo() }; idle(vm)
            check(!vm.params.dateImprintActive && vm.params.dateImprintText.isEmpty() && vm.params.dateImprintDataText.isEmpty())
            main { vm.redo() }; idle(vm)
            check(vm.params.dateImprintText == expected.dateText && vm.params.dateImprintDataText == expected.dataText)
            val dateField = reveal { it.isEditable && it.text?.contains(expected.dateText) == true }
            check(dateField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "CUSTOM DATE") }))
            idle(vm)
            main { vm.setDateImprintEnabled(false) }; idle(vm)
            main { vm.setDateImprintEnabled(true) }; idle(vm)
            check(vm.params.dateImprintText == "CUSTOM DATE") { "Re-enabling overwrote a manual edit" }
            main { vm.selectPhoto(1) }; idle(vm)
            check(vm.dateImprintMetadata == DateImprintMetadata(dataText = "ISO3200"))
            main { vm.setDateImprintEnabled(true) }; idle(vm)
            check(vm.params.dateImprintText.isEmpty() && vm.params.dateImprintDataText == "ISO3200")
            main { vm.params.dateImprintText = "MANUAL"; vm.scheduleRender() }; idle(vm)
            main { vm.usePhotoDateImprint() }; idle(vm)
            check(vm.params.dateImprintText == "MANUAL") { "Reading missing date removed manual text" }
            main { vm.selectPhoto(2) }; idle(vm)
            main { vm.setDateImprintEnabled(true) }; idle(vm)
            check(!vm.dateImprintMetadata.available && vm.params.dateImprintText.isEmpty() && vm.params.dateImprintDataText.isEmpty())
            main { vm.params.dateImprintText = "'19 6 1"; vm.params.dateImprintDataText = "ISO100 F8"; vm.scheduleRender() }; idle(vm)
            check(EditStore.load(context, photos[2])!!.dateImprintDataText == "ISO100 F8")
            main { vm.selectPhoto(0) }; idle(vm)
            check(vm.params.dateImprintText == "CUSTOM DATE" && vm.params.dateImprintDataText == expected.dataText)
            click(context.getString(R.string.date_imprint_use_photo)); idle(vm)
            check(vm.params.dateImprintText == expected.dateText && vm.params.dateImprintDataText == expected.dataText)
            main { vm.openPhoto(photos[0]) }; idle(vm)
            check(vm.params.dateImprintText == expected.dateText && vm.params.dateImprintDataText == expected.dataText)
            val raw = File(output, "pc-parity-DSC_5099.NEF")
            check(raw.isFile) { "RAW metadata fixture missing" }
            main { vm.openPhoto(Uri.fromFile(raw)) }; idle(vm)
            check(vm.currentIsRaw && vm.dateImprintMetadata.available) { "Real NEF capture metadata unavailable" }
            val rawMetadata = vm.dateImprintMetadata
            main {
                vm.params.dateImprintText = ""; vm.params.dateImprintDataText = ""
                vm.setDateImprintEnabled(true)
            }; idle(vm)
            check(vm.params.dateImprintText == rawMetadata.dateText && vm.params.dateImprintDataText == rawMetadata.dataText)
            check(vm.errorMessage == null)
            check(beforeHashes.all { (path, digest) -> hash(File(path)) == digest }) { "Source metadata was modified" }
            File(output, "date-imprint-report.json").writeText(JSONObject()
                .put("jpeg_date", expected.dateText).put("jpeg_data", expected.dataText)
                .put("raw_date", rawMetadata.dateText).put("raw_data", rawMetadata.dataText)
                .put("source_files_unchanged", true).toString(2))
            return "JPEG and real NEF date/exposure auto-fill; absent/partial/invalid/date fallback and locale; manual edits, undo/redo, per-photo reopen, explicit re-read, original byte hashes retained"
        } catch (failure: Throwable) {
            File(output, "date-imprint-failure.txt").writeText(failure.stackTraceToString())
            runCatching { capture("date-imprint-failure.png") }
            throw failure
        } finally { main { vm.smartAutoEnabled = oldAuto; activity.finish() } }
    }

    private fun fixture(name: String, tags: Map<String, String>): File {
        val file = File(output, name)
        val image = CineStillAudit.fixture(640, 480)
        try { file.outputStream().use { check(image.compress(if (name.endsWith("png")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 100, it)) } }
        finally { image.recycle() }
        if (tags.isNotEmpty()) ExifInterface(file).apply { tags.forEach { (tag, value) -> setAttribute(tag, value) }; saveAttributes() }
        return file
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        test.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return visit(test.uiAutomation.rootInActiveWindow)
    }

    private fun reveal(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) repeat(30) {
            find(predicate)?.let { node ->
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                SystemClock.sleep(100)
                find(predicate)?.takeIf { it.isVisibleToUser }?.let { return it }
            }
            find { it.className?.toString() == "android.widget.ScrollView" }?.performAction(direction)
            SystemClock.sleep(100)
        }
        error("Date imprint control missing")
    }

    private fun click(text: String) {
        var node = reveal { it.text?.toString() == text || it.contentDescription?.toString() == text }
        while (!node.isClickable) node = checkNotNull(node.parent)
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        test.waitForIdleSync(); SystemClock.sleep(200)
    }

    private fun capture(name: String) {
        test.waitForIdleSync(); SystemClock.sleep(200)
        val image = checkNotNull(test.uiAutomation.takeScreenshot())
        try { File(output, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { image.recycle() }
    }

    private fun idle(vm: EditorViewModel) = await {
        vm.hasPhoto && !vm.busy && vm.lastResult != null && vm.renderTaskState.active == null &&
            listOf("photoLoadJob", "renderJob", "wbJob", "frameRenderJob", "cameraWbJob").none { name ->
                (EditorViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as? Job)?.isActive == true
            }
    }

    private fun await(condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) { check(SystemClock.elapsedRealtime() - start < 120_000); SystemClock.sleep(30) }
        test.waitForIdleSync()
    }
}
