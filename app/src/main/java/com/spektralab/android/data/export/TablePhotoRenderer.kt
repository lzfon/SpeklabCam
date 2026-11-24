package com.spektralab.android.data.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.spektralab.android.core.model.ExportOptions
import com.spektralab.android.engine.EditorGrade
import org.json.JSONObject
import kotlin.math.ceil

/** Render an edited table source through the same immutable host path as photo output. */
internal class TablePhotoRenderer(private val context: Context) {
    suspend fun render(
        input: TableExportInput,
        correctLens: Boolean,
        check: () -> Unit,
        beforeNative: suspend () -> Unit,
        progress: (String, Float) -> Unit = { _, _ -> },
    ): Bitmap {
        val edit = requireNotNull(input.params)
        val params = edit.toParams()
        val options = options(input, correctLens)
        var bitmap: Bitmap? = null
        try {
            PhotoExportPipeline(context).write(
                Uri.parse(input.source.uri), input.evidence.displayName, edit, options, check,
                beforeNative = beforeNative,
                progress = progress,
                recordReceipt = false,
                validateRendered = { TableExportSession.checkSourceSize(it.width, it.height) },
            ) { pixels, _, task ->
                check(task.request.sourceHash == input.evidence.sha256) { "TABLE_EXPORT_CHANGED" }
                bitmap = Rgba16Bitmap.convert(pixels,
                    if (pixels.gradeApplied) EditorGrade.identity else EditorGrade.from(params), check)
                task.verifySource()
                "internal-table-input"
            }
            return checkNotNull(bitmap).also { bitmap = null }
        } finally { bitmap?.recycle() }
    }

    internal fun options(input: TableExportInput, correctLens: Boolean): ExportOptions {
        val params = requireNotNull(input.params).toParams()
        val geometry = JSONObject(params.openJson(
            input.evidence.width.coerceAtLeast(1), input.evidence.height.coerceAtLeast(1)))
        val crop = (minOf(geometry.optDouble("geometry_crop_w", 1.0),
            geometry.optDouble("geometry_crop_h", 1.0)) *
            minOf(params.finishedCrop.width, params.finishedCrop.height)).coerceAtLeast(.0001)
        val resolution = ceil(input.requestedEdge / crop).toInt().coerceAtLeast(1)
        check(resolution <= TableExportSession.MAX_INPUT_EDGE) { "TABLE_EXPORT_CAPACITY" }
        return ExportOptions("PNG", resolution, "sRGB", 100, false, false, correctLens)
    }
}
