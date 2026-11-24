package com.spektralab.android.core.model

/** App preferences never become part of an existing photograph's edit or history. */
internal data class AppSettings(
    val previewLongEdge: Int = 2048,
    val renderCacheMiB: Int = 256,
    val defaultFilm: String = "kodak_portra_400",
    val defaultPaper: String = "kodak_portra_endura",
) {
    init {
        require(previewLongEdge in PREVIEW_EDGES && renderCacheMiB in CACHE_LIMITS)
        require(defaultFilm.matches(STOCK_ID) && defaultPaper.matches(STOCK_ID))
    }

    val renderCacheBytes: Long get() = renderCacheMiB * 1024L * 1024L

    companion object {
        val PREVIEW_EDGES = listOf(1024, 1600, 2048)
        val CACHE_LIMITS = listOf(0, 64, 128, 256, 512, 1024)
        private val STOCK_ID = Regex("[a-z0-9_]{1,100}")
    }
}
