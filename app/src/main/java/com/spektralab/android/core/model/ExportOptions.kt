package com.spektralab.android.core.model

/** Frozen at submission, including decode-side choices and metadata access. */
internal data class ExportOptions(
    val format: String,
    val resolution: Int,
    val colorSpace: String,
    val jpegQuality: Int,
    val keepCapture: Boolean,
    val keepLocation: Boolean,
    val correctLens: Boolean,
    val customSize: ExportSize? = null,
    val naming: ExportNaming = ExportNaming(),
    val tiffDeflate: Boolean = false,
) {
    init {
        require(format in listOf("JPEG", "PNG", "TIFF", "HEIF"))
        require(resolution >= 0 && jpegQuality in 1..100)
        require(colorSpace in com.spektralab.android.core.color.OutputColorSpaces.names)
        require(format != "HEIF" || colorSpace == "sRGB") { "HEIF_SRGB_ONLY" }
    }

    // Legacy presets retain their decode-side sizing. Custom limits use the original
    // rendered raster so that crop, turn and border dimensions are accounted for once.
    val renderResolution: Int get() = if (customSize == null) resolution else 0
    // Keep existing SDR grading/working-space arithmetic. New file spaces convert after grading.
    val renderColorSpace: String get() = if (format == "TIFF" && colorSpace == "Display P3") "Display P3" else "sRGB"
    val outputColorSpace: String
        get() = colorSpace
}
