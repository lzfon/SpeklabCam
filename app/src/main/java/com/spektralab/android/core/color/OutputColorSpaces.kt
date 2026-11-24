package com.spektralab.android.core.color

import android.graphics.ColorSpace

/** Exact native names; every encoded raster and its ICC identify the same space. */
internal object OutputColorSpaces {
    val names = listOf("sRGB", "Display P3", "Adobe RGB (1998)", "ProPhoto RGB")

    private val adobe by lazy {
        ColorSpace.Rgb("Adobe RGB (1998)", floatArrayOf(.64f,.33f,.21f,.71f,.15f,.06f),
            floatArrayOf(.3127f,.3290f), 563.0 / 256.0)
    }
    fun named(name: String): ColorSpace = if (name == "Adobe RGB (1998)") adobe else ColorSpace.get(when (name) {
        "sRGB" -> ColorSpace.Named.SRGB
        "Display P3" -> ColorSpace.Named.DISPLAY_P3
        "Adobe RGB (1998)" -> ColorSpace.Named.ADOBE_RGB
        "ProPhoto RGB" -> ColorSpace.Named.PRO_PHOTO_RGB
        else -> throw IllegalArgumentException("Unsupported output colour space: $name")
    })

    fun displayTransform(name: String): ColorSpace.Connector? =
        if (name == "sRGB") null else ColorSpace.connect(named(name), named("sRGB"))
}
