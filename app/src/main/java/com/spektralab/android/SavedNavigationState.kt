package com.spektralab.android

import androidx.annotation.DrawableRes

/** Keep enum class identities stable for existing Compose saved navigation state. */
internal enum class AppRoute { Home, Editor, FilmStocks, About, ViewingStand, SavedPhotos, ExportTasks, Settings }

/** 底栏图标全部使用本项目自绘的矢量图形（ic_spk_tab_*），不依赖第三方图标库素材。 */
internal enum class EditorTab(val label: String, @DrawableRes val icon: Int) {
    /** 一级菜单「调参」：原「冲印」里跟曝光链路、画幅片基有关的部分。 */
    Adjust("调参", R.drawable.ic_spk_tab_adjust),

    /** 一级菜单「暗房」：放大机与乳剂反应。 */
    Darkroom("暗房", R.drawable.ic_spk_tab_darkroom),

    Grade("调色", R.drawable.ic_spk_tab_tune),

    /** 导出走控制条那一栏，不占底栏。 */
    Export("导出", R.drawable.ic_spk_tab_export),

    /** 观片台的入口已从编辑页底栏删除，只保留枚举给老的跳转状态用。 */
    Gallery("观片台", R.drawable.ic_spk_tab_gallery),
}

internal enum class FilmFilter(val label: String) {
    All("全部"), Negative("彩色负片"), Positive("正片"), Monochrome("黑白"), Cinema("电影胶片")
}
