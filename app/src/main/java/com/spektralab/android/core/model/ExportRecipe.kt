package com.spektralab.android.core.model


/** 一条导出方案（界面上叫「方案」）：格式 + 长边分辨率 + 色彩空间 + 质量 + 保留开关 + 命名。 */
data class ExportRecipe(
    val name: String,
    val format: String,      // "JPEG" / "PNG" / "TIFF"
    val res: Int,            // 0 = 原始；其它值为长边像素
    val colorSpace: String,  // "sRGB" / "Display P3"
    val jpegQuality: Int = 95,
    val keepCapture: Boolean = true,
    val keepLocation: Boolean = false,
    val customSize: ExportSize? = null,
    val naming: ExportNaming = ExportNaming(),
    val tiffDeflate: Boolean = false,
)
