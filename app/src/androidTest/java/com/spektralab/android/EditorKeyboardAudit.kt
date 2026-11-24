package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.ui.EditorScreen
import com.spektralab.android.feature.lighttable.rememberFilmCanvasState
import com.spektralab.android.ui.theme.TheiaTheme
import java.io.File

/** Real IME resize, production editor, text entry, keyboard Back, and persisted edit recovery. */
internal class EditorKeyboardAudit(private val test: Instrumentation, private val output: File) {
    private val context get() = test.targetContext
    private fun main(action: () -> Unit) = test.runOnMainSync { action() }

    fun run(): String {
        val source = File(output, "keyboard-owned.png")
        CineStillAudit.fixture(640, 480).let { image ->
            try { source.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { image.recycle() }
        }
        val uri = Uri.fromFile(source)
        EditStore.save(context, uri, EditorParams().apply {
            dateImprintActive = true
            dateImprintText = "26 11 15"
            dateImprintDataText = "ISO 200"
            grainActive = false
            glareActive = false
        })
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        main { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        fun imeVisible(): Boolean {
            var visible = false
            main { visible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            return visible
        }
        try {
            await("engine") { vm.engine != null && !vm.busy }
            main { vm.openPhotos(listOf(uri)) }
            await("photo") { vm.rendered != null && !vm.busy }
            main { activity.setContent { TheiaTheme {
                EditorScreen(vm, {}, {}, {}, rememberFilmCanvasState(), 0)
            } } }
            await("editor accessibility tree") { test.uiAutomation.rootInActiveWindow != null }
            click(EditorTab.Print.label)
            click("日期后背")
            val date = reveal { it.isEditable && it.text?.contains("26 11 15") == true }
            check(date.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("real soft keyboard") { imeVisible() }
            SystemClock.sleep(700) // Include the entire IME animation and any recomposition.
            capture("keyboard-date-focused.png")
            val focused = find { it.isEditable && it.isFocused }
            check(focused?.isEditable == true && focused.text?.contains("26 11 15") == true) {
                "IME resize dismissed the date input / rebuilt its parameter panel"
            }
            fun enter(value: String) {
                val field = checkNotNull(find { it.isEditable && it.isFocused })
                check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
            }
            enter("2026-11-15")
            await("date edit") { vm.params.dateImprintText == "2026-11-15" && !vm.busy }
            check(imeVisible() && find { it.isEditable && it.isFocused } != null)
            val data = reveal { it.isEditable && it.text?.contains("ISO 200") == true }
            check(data.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("data focus") { find { it.isEditable && it.isFocused }?.text?.contains("ISO 200") == true }
            enter("ISO 800  F2.8")
            await("data edit") { vm.params.dateImprintDataText == "ISO 800  F2.8" && !vm.busy }
            check(imeVisible())
            capture("keyboard-data-focused.png")
            test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await("keyboard back") { !imeVisible() }
            SystemClock.sleep(700) // Insets visibility changes before the closing animation finishes.
            reveal { it.isEditable && it.text?.contains("ISO 800  F2.8") == true }
            val stored = checkNotNull(EditStore.load(context, uri))
            check(stored.dateImprintText == "2026-11-15" && stored.dateImprintDataText == "ISO 800  F2.8")
            check(vm.params.dateImprintActive && vm.errorMessage == null)
            capture("keyboard-dismissed.png")
            return "Real IME retained date/data focus; edits and rendering completed; Back hid keyboard without leaving Print; both texts persisted"
        } catch (failure: Throwable) {
            File(output, "keyboard-failure.txt").writeText(failure.stackTraceToString())
            runCatching { capture("keyboard-failure.png") }
            runCatching {
                fun describe(node: AccessibilityNodeInfo?, depth: Int = 0): String {
                    if (node == null) return ""
                    return "${" ".repeat(depth)}${node.className} text=${node.text} desc=${node.contentDescription} editable=${node.isEditable} focused=${node.isFocused} clickable=${node.isClickable}\n" +
                        (0 until node.childCount).joinToString("") { describe(node.getChild(it), depth + 1) }
                }
                File(output, "keyboard-failure-tree.txt").writeText(describe(root()))
            }
            throw failure
        } finally { main { activity.finish() } }
    }

    private fun root(): AccessibilityNodeInfo {
        repeat(50) {
            test.uiAutomation.clearCache()
            test.uiAutomation.rootInActiveWindow?.let { return it }
            SystemClock.sleep(30)
        }
        error("Editor accessibility tree unavailable")
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return visit(root())
    }

    private fun reveal(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(40) {
            find(predicate)?.let { return it }
            // Restrict scrolling to the parameters, never pan the photo canvas.
            find { it.className?.toString() == "android.widget.ScrollView" }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(120)
        }
        error("Missing keyboard audit control")
    }

    private fun click(label: String) {
        var node = reveal { it.text?.toString()?.lineSequence()?.firstOrNull() == label || it.contentDescription?.toString() == label }
        while (!node.isClickable) node = checkNotNull(node.parent) { "No clickable ancestor: $label" }
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        test.waitForIdleSync()
        SystemClock.sleep(300)
    }

    private fun capture(name: String) {
        test.waitForIdleSync()
        SystemClock.sleep(200)
        val image = checkNotNull(test.uiAutomation.takeScreenshot())
        try { File(output, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { image.recycle() }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) {
            check(SystemClock.elapsedRealtime() - start < 60_000) { "Keyboard audit timeout: $label" }
            SystemClock.sleep(30)
        }
        test.waitForIdleSync()
    }
}
