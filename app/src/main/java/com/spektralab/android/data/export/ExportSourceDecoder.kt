package com.spektralab.android.data.export

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.core.model.ExportOptions
import com.spektralab.android.engine.*
import kotlin.math.roundToInt

/** Source binding shared by the local compatibility path and the isolated host. */
internal object ExportSourceDecoder {
    fun open(context: Context, engine: EngineClient, source: Uri, raw: Boolean,
             params: EditorParams, options: ExportOptions, check: () -> Unit) {
        engine.closeSession()
        val size = options.renderResolution
        if (raw) {
            val cancellation = RawDecodeCancellation { check(); false }
            val info = if (params.cameraWbCustom && (params.cameraWbTemperatureAsShot || params.cameraWbTintAsShot))
                RawDecoder.readCameraInfo(context, source, cancellation) else null
            val xyz = info?.copyOfRange(0, 9) ?: if (params.cameraWbCustom) RawDecoder.readCameraMatrix(context, source, cancellation) else null
            fun multipliers(matrix: FloatArray?): FloatArray? {
                if (!params.cameraWbCustom || matrix == null) return null
                return CameraWhiteBalance.resolve(params, matrix, info?.copyOfRange(9, 13))
            }
            var image = measureExport("raw_decode") { RawDecoder.decodeForExport(context, source, size, multipliers(xyz), cancellation) }
            try {
                if (params.cameraWbCustom && xyz == null) multipliers(image.camXyz)?.let { values ->
                    image.close(); image = RawDecoder.decodeForExport(context, source, size, values, cancellation)
                }
                LensProfileStore.load(context)
                if (options.correctLens) LensProfileStore.match(image.make, image.lens, image.focal)?.let {
                    measureExport("raw_lens_correction") { image.correctLens(it) }
                }
                image.resize(size); check()
                measureExport("engine_input") { engine.openRaw(image, params.openJson(image.width, image.height,
                    options.renderColorSpace, "ProPhoto RGB")) }
            } finally { image.close() }
        } else {
            val image = measureExport("image_decode") { bitmap(context, source, size) }
            try { check(); engine.openBitmap(image, params.openJson(image.width, image.height, options.renderColorSpace)) }
            finally { image.recycle() }
        }
    }

    fun bitmap(context: Context, uri: Uri, size: Int): Bitmap {
        val limit = if (size == 0) Int.MAX_VALUE else size
        if (Build.VERSION.SDK_INT >= 28) return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val edge = maxOf(info.size.width, info.size.height)
            if (edge > limit) {
                val scale = limit.toDouble() / edge
                decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1))
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= limit) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        var image = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Cannot decode export image")
        val orientation = context.contentResolver.openInputStream(uri).use { input ->
            input?.let(::ExifInterface)?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
            if (rotated !== image) image.recycle()
            image = rotated
        }
        val edge = maxOf(image.width, image.height)
        if (edge > limit) {
            val scale = limit.toFloat() / edge
            val resized = Bitmap.createScaledBitmap(image, (image.width * scale).roundToInt().coerceAtLeast(1),
                (image.height * scale).roundToInt().coerceAtLeast(1), true)
            if (resized !== image) image.recycle()
            image = resized
        }
        return image
    }
}
