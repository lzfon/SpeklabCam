package com.spektralab.android.data.local

import android.content.Context
import android.util.AtomicFile
import com.spektralab.android.core.model.EditPreset
import com.spektralab.android.core.model.EditTemplate
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Edit templates deliberately live outside export recipes. Unsupported records survive edits. */
internal object EditPresetStore {
    data class Catalog(val presets: List<EditPreset>, val unsupported: Int)
    private const val FILE = "edit-presets.json"

    private fun read(context: Context): JSONObject {
        val file = AtomicFile(File(context.filesDir, FILE))
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
            return JSONObject().put("version", 1).put("presets", JSONArray())
        return JSONObject(file.openRead().bufferedReader().use { it.readText() }).also {
            require(it.get("version") is Number && it.getDouble("version") == 1.0)
            it.getJSONArray("presets")
        }
    }

    /** 出厂配方只补一次：写过标记后不再自动添加，用户删掉也不会复活；同名内置项会按新版本就地更新。 */
    private fun ensureBuiltin(context: Context) {
        val root = read(context)
        if (root.optInt("builtin_version", 0) >= BuiltinPresets.VERSION) return
        val old = root.getJSONArray("presets")
        val entries = JSONArray()
        val slotByKey = HashMap<String, Int>()
        val retired = BuiltinPresets.retiredNames().mapNotNull { runCatching { EditPreset.nameKey(it) }.getOrNull() }.toSet()
        for (index in 0 until old.length()) {
            val value = old.get(index)
            // 撤掉的旧种子就地删除，不认识/解析不了的记录原样留下。
            val key = runCatching { EditPreset.nameKey(EditPreset.fromJson(value as JSONObject).name) }.getOrNull()
            if (key != null && key in retired) continue
            entries.put(value)
            if (key != null) slotByKey[key] = entries.length() - 1
        }
        runCatching {
            BuiltinPresets.seeds().forEach { seed ->
                val key = runCatching { EditPreset.nameKey(seed.name) }.getOrNull() ?: return@forEach
                val slot = slotByKey[key]
                if (slot == null) {
                    entries.put(EditPreset(UUID.randomUUID().toString(), EditPreset.normalizedName(seed.name),
                        seed.template, EditPreset.normalizedNote(seed.note)).toJson())
                    slotByKey[key] = entries.length() - 1
                } else {
                    val existing = runCatching { EditPreset.fromJson(entries.getJSONObject(slot)) }.getOrNull()
                    if (existing != null && existing.template.toJson().toString() != seed.template.toJson().toString()) {
                        entries.put(slot, EditPreset(existing.id, EditPreset.normalizedName(seed.name), seed.template,
                            EditPreset.normalizedNote(seed.note)).toJson())
                    }
                }
            }
        }
        root.put("presets", entries)
        root.put("builtin_version", BuiltinPresets.VERSION)
        writeAtomicJson(File(context.filesDir, FILE), root.toString())
    }

    @Synchronized
    fun load(context: Context): Catalog {
        ensureBuiltin(context)
        val entries = read(context).getJSONArray("presets")
        val supported = (0 until entries.length()).mapNotNull { index ->
            runCatching { EditPreset.fromJson(entries.getJSONObject(index)) }.getOrNull()
        }
        require(supported.map { it.id }.distinct().size == supported.size)
        require(supported.map { EditPreset.nameKey(it.name) }.distinct().size == supported.size)
        return Catalog(supported, entries.length() - supported.size)
    }

    @Synchronized
    fun save(context: Context, name: String, note: String, template: EditTemplate, replaceId: String? = null): Catalog {
        val normalized = EditPreset.normalizedName(name)
        val supported = load(context).presets
        require(supported.none { it.id != replaceId && EditPreset.nameKey(it.name) == EditPreset.nameKey(normalized) })
        if (replaceId != null) require(supported.any { it.id == replaceId })
        val preset = EditPreset(replaceId ?: UUID.randomUUID().toString(), normalized, template,
            EditPreset.normalizedNote(note))
        rewrite(context, replaceId, preset)
        return load(context)
    }

    @Synchronized
    fun delete(context: Context, id: String): Catalog {
        require(load(context).presets.any { it.id == id })
        rewrite(context, id, null)
        return load(context)
    }

    private fun rewrite(context: Context, removeId: String?, insert: EditPreset?) {
        val root = read(context)
        val old = root.getJSONArray("presets")
        val entries = JSONArray()
        for (index in 0 until old.length()) {
            val value = old.get(index)
            // Remove only readable records, never an unknown entry that happens to share an id.
            val parsed = runCatching { EditPreset.fromJson(value as JSONObject) }.getOrNull()
            if (parsed == null || parsed.id != removeId) entries.put(value)
        }
        insert?.let { entries.put(it.toJson()) }
        root.put("presets", entries)
        writeAtomicJson(File(context.filesDir, FILE), root.toString())
    }
}
