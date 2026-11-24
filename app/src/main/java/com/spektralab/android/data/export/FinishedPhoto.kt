package com.spektralab.android.data.export

import android.content.Context
import android.graphics.Bitmap
import com.spektralab.android.core.color.OutputIcc
import com.spektralab.android.core.model.ExportFrameRequest
import com.spektralab.android.engine.EditorGrade
import com.spektralab.android.engine.Rgba16Source
import java.io.File
import java.io.OutputStream

internal object FinishedPhoto {
    fun prepare(source: Rgba16Source, request: ExportFrameRequest, check: () -> Unit): Rgba16Source {
        val params = request.edit.toParams()
        val options = request.options
        val grade = EditorGrade.from(params)
        val finished = FinishedRgba16Source.wrap(source, grade, params.finishedCrop, check)
        val sized = SizedRgba16Source.wrap(finished, options.customSize, check)
        return OutputConvertedSource.wrap(sized, if (sized.gradeApplied) EditorGrade.identity else grade,
            options.renderColorSpace, options.outputColorSpace, check)
    }

    fun write(context: Context, source: Rgba16Source, request: ExportFrameRequest,
              metadata: ExportMetadata, output: OutputStream, check: () -> Unit) {
        val options = request.options
        val grade = if (source.gradeApplied) EditorGrade.identity else EditorGrade.from(request.edit.toParams())
        val icc = options.colorSpace.takeUnless { it == "sRGB" }?.let(OutputIcc::generate)
        when (options.format) {
            "HEIF" -> HeifOutput.write(context, source, grade, options.jpegQuality, output, check, metadata)
            "PNG" -> PngRowWriter.write(source, grade, output, check, metadata, icc = icc)
            "TIFF" -> {
                val profile = icc ?: runCatching {
                    context.assets.open("engine/resources/io/sRGB.icc").use { it.readBytes() }
                }.getOrNull()
                if (options.tiffDeflate) DeflatedTiffWriter.write(context, source, grade, profile, output, check, metadata)
                else TiffWriter.writeNative(source, grade, profile, output, check, metadata)
            }
            "JPEG" -> if (JpegRowWriter.available) {
                JpegRowWriter.write(context, source, grade, options.jpegQuality, output, check, metadata, icc = icc)
            } else {
                val file = File.createTempFile("export_", ".jpg", context.cacheDir)
                try {
                    val bitmap = Rgba16Bitmap.convert(source, grade, check)
                    try {
                        file.outputStream().use {
                            val destination = if (icc != null) jpegHeaders(it, null, icc, check) else it
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, options.jpegQuality, destination)) { "图片编码失败" }
                        }
                    } finally { bitmap.recycle() }
                    metadata.attach(file, source.width, source.height)
                    file.inputStream().use { input ->
                        val bytes = ByteArray(64 * 1024)
                        while (true) {
                            check()
                            val count = input.read(bytes)
                            if (count < 0) break
                            output.write(bytes, 0, count)
                        }
                    }
                } finally { file.delete() }
            }
        }
        check()
    }
}
