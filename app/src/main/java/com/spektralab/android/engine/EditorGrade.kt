package com.spektralab.android.engine

import com.spektralab.android.core.model.EditorParams

/** Shared post-development controls for preview and every finished output. */
internal object EditorGrade {
    val identity = Grade(1f, 1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f,
        FloatArray(3), FloatArray(3), FloatArray(3))

    fun from(p: EditorParams): Grade {
        fun zone(temp: Float, tint: Float) = floatArrayOf(.18f * temp, -.12f * tint, -.18f * temp)
        return Grade(1f + p.temperature * .18f, 1f - p.tint * .12f, 1f - p.temperature * .18f,
            Math.pow(2.0, p.gradeExposure.toDouble()).toFloat(), p.contrast / 100f, p.brightness / 100f,
            1f + p.saturation / 100f, p.highlights / 100f, p.shadows / 100f, p.blackPoint / 100f, p.whitePoint / 100f,
            zone(p.cbShadowTemp / 100f, p.cbShadowTint / 100f), zone(p.cbMidTemp / 100f, p.cbMidTint / 100f),
            zone(p.cbHighTemp / 100f, p.cbHighTint / 100f), p.vignetteAmount / 100f, p.vignetteSize / 100f,
            p.vignetteFeather / 100f, p.toneCurves, p.colorAdjustments, p.channelMixer, p.colorWheels)
    }
}
