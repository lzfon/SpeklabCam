package com.spektralab.android.engine

/** Bounded, EXIF-oriented linear ProPhoto pixels before lens correction/crop; never export input. */
internal class RawNeutralPreview(
    val width: Int,
    val height: Int,
    val rgb: FloatArray,
    val calibration: FloatArray,
    val cameraXyz: FloatArray,
    val cameraMultipliers: FloatArray,
)
