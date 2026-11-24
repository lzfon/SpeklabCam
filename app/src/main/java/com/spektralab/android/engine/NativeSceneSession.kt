package com.spektralab.android.engine

import android.graphics.Bitmap
import com.spektralab.android.core.geometry.Perspective
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Host-owned source geometry and explicit digital bypass; ordinary film requests keep the old JNI path. */
internal object NativeSceneSession {
    data class Binding(val filmOff: Boolean, val vertical: Float, val horizontal: Float) {
        val required: Boolean get() = filmOff || vertical != 0f || horizontal != 0f
    }

    private val keys = setOf("android_film_off", "android_perspective_vertical", "android_perspective_horizontal")

    fun binding(json: String?): Binding {
        val values = json?.let(::JSONObject) ?: JSONObject()
        require(!values.has("android_film_off") || values.get("android_film_off") is Boolean)
        fun angle(key: String): Float {
            if (!values.has(key)) return 0f
            val raw = values.get(key)
            require(raw is Number && raw.toDouble().isFinite() && raw.toDouble() in -30.0..30.0)
            return raw.toFloat()
        }
        return Binding(values.optBoolean("android_film_off", false),
            angle("android_perspective_vertical"), angle("android_perspective_horizontal"))
    }

    fun engineJson(json: String?): String? = json?.let { text ->
        JSONObject(text).apply { keys.forEach(::remove) }.toString()
    }

    fun open(engine: Long, data: FloatArray?, width: Int, height: Int, raw: Long, bitmap: Bitmap?,
             json: String, binding: Binding, cancellation: AtomicBoolean): Long {
        val inverse = Perspective.make(width, height, binding.vertical, binding.horizontal).inverse
        return nativeOpen(engine, data, width, height, raw, bitmap, inverse,
            checkNotNull(engineJson(json)), binding.filmOff, cancellation)
    }

    fun update(pointer: Long, json: String) = nativeUpdate(pointer, checkNotNull(engineJson(json)))
    fun preview(pointer: Long, tier: String): SpkResult {
        val limit = when (tier) { "live" -> 1600; "preview" -> 3400; else -> 0 }
        return nativeRender(pointer, limit, true) as SpkResult
    }
    fun export(pointer: Long): NativeRenderResult = nativeRender(pointer, 0, false) as NativeRenderResult
    fun release(pointer: Long) = nativeRelease(pointer)
    fun cancel(pointer: Long) = nativeCancel(pointer)
    fun progress(pointer: Long) = nativeProgress(pointer)

    private external fun nativeOpen(engine: Long, pixels: FloatArray?, width: Int, height: Int, raw: Long,
        bitmap: Bitmap?, inverse: DoubleArray, json: String, digital: Boolean, cancellation: AtomicBoolean): Long
    private external fun nativeUpdate(pointer: Long, json: String)
    private external fun nativeRender(pointer: Long, limit: Int, managed: Boolean): Any
    private external fun nativeRelease(pointer: Long)
    private external fun nativeCancel(pointer: Long)
    private external fun nativeProgress(pointer: Long): Int
}
