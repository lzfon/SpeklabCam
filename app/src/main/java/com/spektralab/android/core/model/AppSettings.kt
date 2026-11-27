package com.spektralab.android.core.model

/** App preferences never become part of an existing photograph's edit or history. */
internal data class AppSettings(
    val previewLongEdge: Int = 2048,
    val renderCacheMiB: Int = 256,
) {
    init {
        require(previewLongEdge in PREVIEW_EDGES && renderCacheMiB in CACHE_LIMITS)
    }

    val renderCacheBytes: Long get() = renderCacheMiB * 1024L * 1024L

    companion object {
        // 4096 是第三轮加的最大档：大屏看细节用，手机上更慢也更吃内存。
        val PREVIEW_EDGES = listOf(1024, 1600, 2048, 4096)
        val CACHE_LIMITS = listOf(0, 64, 128, 256, 512, 1024)
    }
}
