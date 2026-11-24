package com.spektralab.android

import androidx.annotation.DrawableRes

/** Keep enum class identities stable for existing Compose saved navigation state. */
internal enum class AppRoute { Home, Editor, FilmStocks, About, ViewingStand, SavedPhotos, ExportTasks, Settings }

/** 底栏图标全部使用本项目自绘的矢量图形（ic_spk_tab_*），不依赖第三方图标库素材。 */
internal enum class EditorTab(val label: String, @DrawableRes val icon: Int) {
    Develop("冲印", R.drawable.ic_spk_tab_shoot),
    Grade("调色", R.drawable.ic_spk_tab_tune),
    Export("导出", R.drawable.ic_spk_tab_export),
    Gallery("观片台", R.drawable.ic_spk_tab_gallery),
}

internal enum class FilmFilter(val label: String) {
    All("全部"), Negative("彩色负片"), Positive("正片"), Monochrome("黑白"), Cinema("电影胶片")
}
