package com.spektralab.android.feature.editor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spektralab.android.R
import com.spektralab.android.core.model.EditPreset
import com.spektralab.android.core.model.EditTemplate
import com.spektralab.android.data.local.EditPresetStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Small metadata operations stay off the preview thread and out of the renderer. */
internal class EditPresetController(private val context: Context, private val scope: CoroutineScope) {
    var items by mutableStateOf<List<EditPreset>>(emptyList())
        private set
    var unsupported by mutableStateOf(0)
        private set
    var ready by mutableStateOf(false)
        private set
    var pending by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var revision by mutableStateOf(0)
        private set
    /** 配方文件导入/导出的结果提示，由弹窗显示。 */
    var transferMessage by mutableStateOf<String?>(null)
        private set

    fun clearTransferMessage() { transferMessage = null }

    init { reload() }

    fun reload() = update { EditPresetStore.load(context) }
    fun save(name: String, note: String, template: EditTemplate, replaceId: String? = null) =
        update { EditPresetStore.save(context, name, note, template, replaceId) }
    fun rename(preset: EditPreset, name: String, note: String) = save(name, note, preset.template, preset.id)
    fun delete(id: String) = update { EditPresetStore.delete(context, id) }

    /** 把「我的配方」全部写成一个 JSON 文件，位置由用户选。 */
    fun exportPresets(uri: android.net.Uri?) {
        if (uri == null || pending) return
        val snapshot = items
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val root = org.json.JSONObject()
                        .put("version", 1).put("kind", "spektralab-edit-presets")
                        .put("presets", org.json.JSONArray().apply { snapshot.forEach { put(it.toJson()) } })
                    val stream = requireNotNull(context.contentResolver.openOutputStream(uri, "wt"))
                    stream.use { it.write(root.toString().toByteArray(Charsets.UTF_8)); it.flush() }
                }
                transferMessage = "已导出 ${snapshot.size} 条配方"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { transferMessage = "导出配方失败，请换个保存位置重试" }
        }
    }

    /** 读入配方 JSON：同名更新、新名新增、坏记录跳过，不动其它配方。 */
    fun importPresets(uri: android.net.Uri?) {
        if (uri == null || pending) return
        scope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    val text = requireNotNull(context.contentResolver.openInputStream(uri))
                        .bufferedReader().use { it.readText() }
                    require(text.length <= 4_000_000) { "配方文件过大" }
                    val root = org.json.JSONObject(text)
                    require(root.optString("kind") == "spektralab-edit-presets") { "不是 SpekLab 配方文件" }
                    val entries = root.getJSONArray("presets")
                    var added = 0; var updated = 0; var skipped = 0
                    for (index in 0 until entries.length()) {
                        val preset = runCatching { EditPreset.fromJson(entries.getJSONObject(index)) }.getOrNull()
                        if (preset == null) { skipped++; continue }
                        val existing = EditPresetStore.load(context).presets.firstOrNull {
                            EditPreset.nameKey(it.name) == EditPreset.nameKey(preset.name)
                        }
                        val ok = runCatching {
                            EditPresetStore.save(context, preset.name, preset.note, preset.template, existing?.id)
                        }.isSuccess
                        if (!ok) skipped++ else if (existing != null) updated++ else added++
                    }
                    "导入完成：新增 $added · 更新 $updated · 跳过 $skipped"
                }
                reload()
                transferMessage = summary
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { transferMessage = "导入失败：不是有效的 SpekLab 配方文件" }
        }
    }

    private fun update(operation: () -> EditPresetStore.Catalog) {
        if (pending) return
        pending = true
        error = null
        scope.launch {
            try {
                val catalog = withContext(Dispatchers.IO) { operation() }
                items = catalog.presets
                unsupported = catalog.unsupported
                ready = true
                revision++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = context.getString(R.string.edit_presets_storage_error)
            } finally { pending = false }
        }
    }
}
