package com.spektralab.android.data.diagnostics

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.spektralab.android.core.model.AppSettings
import com.spektralab.android.data.cache.ExportRenderCache
import com.spektralab.android.data.cache.RenderDiskCache
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.engine.RawDecoder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.json.JSONArray

/** Deliberate field allowlist: never accepts a URI, filename, EXIF, account identifier or raw log. */
internal object DiagnosticReport {
    data class Workspace(val photos: Int = 0, val hasPhoto: Boolean = false, val raw: Boolean = false,
        val previewWidth: Int = 0, val previewHeight: Int = 0, val exporting: Boolean = false)

    fun create(context: Context, settings: AppSettings, cache: RenderDiskCache.Snapshot,
               counters: ExportRenderCache.Stats, workspace: Workspace, settingsUnavailable: Boolean = false,
               check: () -> Unit = {}): ByteArray {
        check()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val root = JSONObject().put("format", 1)
            .put("app", JSONObject().put("version", info.versionName.orEmpty().take(40))
                .put("version_code", PackageInfoCompat.getLongVersionCode(info))
                .put("engine", EngineClient.buildInfo().take(240)).put("raw_decoder", RawDecoder.decoderIdentity.take(200)))
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER.take(80))
                .put("model", Build.MODEL.take(80)).put("android_api", Build.VERSION.SDK_INT)
                .put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.take(8))).put("heap_limit_bytes", Runtime.getRuntime().maxMemory()))
            .put("settings", JSONObject().put("using_defaults_after_read_error", settingsUnavailable).put("preview_long_edge", settings.previewLongEdge)
                .put("render_cache_mib", settings.renderCacheMiB).put("default_film", settings.defaultFilm)
                .put("default_paper", settings.defaultPaper))
            .put("render_cache", JSONObject().put("bytes", cache.bytes).put("entries", cache.entries)
                .put("leased_bytes", cache.leasedBytes).put("active_readers", cache.readers)
                .put("budget_bytes", cache.budget).put("hits_this_process", counters.hits)
                .put("misses_this_process", counters.misses).put("writes_this_process", counters.writes)
                .put("bypasses_this_process", counters.bypasses))
            .put("workspace", JSONObject().put("photos", workspace.photos).put("has_photo", workspace.hasPhoto)
                .put("raw", workspace.raw).put("preview_width", workspace.previewWidth)
                .put("preview_height", workspace.previewHeight).put("exporting", workspace.exporting))
        val json = root.toString(2).toByteArray(Charsets.UTF_8)
        require(json.size <= 32 * 1024)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            check(); zip.putNextEntry(ZipEntry("report.json")); zip.write(json); zip.closeEntry()
            check(); zip.putNextEntry(ZipEntry("README.txt"))
            zip.write("Theia diagnostic report, format 1.\nContains app/device versions, app preferences, cache counters and workspace counts.\nNo photographs, source names/paths/URIs, EXIF, location, account identifiers or raw logs are included.\nCounters cover this UI process only. Generated for a user-requested local save; no automatic upload.\n".toByteArray())
            zip.closeEntry()
        }
        check(); return output.toByteArray().also { require(it.size <= 64 * 1024) }
    }
}
