package com.spektralab.android.data.local

import android.content.Context
import android.util.AtomicFile
import com.spektralab.android.core.model.AppSettings
import java.io.File
import java.io.ByteArrayOutputStream
import org.json.JSONObject

/** Bounded, atomic preferences. Unreadable/future documents are preserved and never overwritten. */
internal class AppSettingsStore(directory: File) {
    data class Read(val value: AppSettings, val unavailable: Boolean = false)
    private val file = AtomicFile(File(directory, "app-settings.json"))
    @Volatile private var loaded: Read? = null

    @Synchronized
    fun snapshot(): Read = loaded ?: read()

    @Synchronized
    fun read(): Read = try {
        Read(decode(document())).also { loaded = it }
    } catch (_: Exception) {
        Read(AppSettings(), unavailable = true).also { loaded = it }
    }

    @Synchronized
    fun save(value: AppSettings) {
        val json = document() ?: JSONObject()
        decode(json) // Validate what is on disk before replacing it.
        json.put("version", 1).put("preview_long_edge", value.previewLongEdge)
            .put("render_cache_mib", value.renderCacheMiB)
            .put("default_film", value.defaultFilm).put("default_paper", value.defaultPaper)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (failure: Throwable) { file.failWrite(stream); throw failure }
        loaded = Read(value)
    }

    private fun document(): JSONObject? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= MAX_BYTES)
                bytes.write(buffer, 0, count)
            }
            JSONObject(bytes.toByteArray().toString(Charsets.UTF_8))
        }
    }

    private fun decode(json: JSONObject?): AppSettings {
        if (json == null) return AppSettings()
        fun integer(key: String, default: Int): Int {
            if (!json.has(key)) return default
            val value = json.get(key)
            require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble())
            return value.toInt()
        }
        // Accept an unversioned document as v0; an absent file keeps the pre-settings defaults.
        require(integer("version", 0) in 0..1)
        val defaults = AppSettings()
        fun stock(key: String, default: String): String {
            if (!json.has(key)) return default
            require(json.get(key) is String)
            return json.getString(key)
        }
        return AppSettings(integer("preview_long_edge", defaults.previewLongEdge),
            integer("render_cache_mib", defaults.renderCacheMiB),
            stock("default_film", defaults.defaultFilm), stock("default_paper", defaults.defaultPaper))
    }

    companion object {
        private const val MAX_BYTES = 64 * 1024
        @Volatile private var instance: AppSettingsStore? = null
        fun get(context: Context): AppSettingsStore = instance ?: synchronized(this) {
            instance ?: AppSettingsStore(context.applicationContext.filesDir).also { instance = it }
        }
    }
}
