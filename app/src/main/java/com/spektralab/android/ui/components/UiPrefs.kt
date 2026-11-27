package com.spektralab.android.ui.components

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 界面偏好：折叠菜单是否互斥、照片预览是否显示直方图，改动即刻生效。 */
object UiPrefs {
    private const val FILE = "theia_ui"
    private const val KEY_AUTO_COLLAPSE = "section_auto_collapse"
    private const val KEY_SHOW_HISTOGRAM = "preview_histogram"
    private const val KEY_WORKBENCH_AUTO_HIDE = "workbench_auto_hide"
    private const val KEY_WORKBENCH_HIDE_SECONDS = "workbench_hide_seconds"
    private const val KEY_HOME_VIEW = "home_view_mode"
    private const val HOME_PREVIEW = "preview"
    private const val HOME_GRID = "grid"
    private var prefs: SharedPreferences? = null

    var autoCollapseSections by mutableStateOf(true)
        private set

    /** 照片预览左上角的直方图，默认显示，可在设置里关闭。 */
    var showHistogram by mutableStateOf(true)
        private set

    /** 工作台（照片条）闲置后是否自动收起成一条把手。 */
    var workbenchAutoHide by mutableStateOf(true)
        private set

    /** 工作台自动收起的等待秒数，3–10 秒。 */
    var workbenchHideSeconds by mutableStateOf(3)
        private set

    /** 首页预览区显示方式：preview = 当前照片预览框；grid = 图库照片两列宫格。 */
    var homeViewMode by mutableStateOf(HOME_PREVIEW)
        private set

    /** 首页预览区走的是不是图库宫格。 */
    val homeShowLibraryGrid: Boolean get() = homeViewMode == HOME_GRID

    fun attach(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        autoCollapseSections = p.getBoolean(KEY_AUTO_COLLAPSE, true)
        showHistogram = p.getBoolean(KEY_SHOW_HISTOGRAM, true)
        workbenchAutoHide = p.getBoolean(KEY_WORKBENCH_AUTO_HIDE, true)
        workbenchHideSeconds = p.getInt(KEY_WORKBENCH_HIDE_SECONDS, 3).coerceIn(3, 10)
        homeViewMode = normalizeHomeView(p.getString(KEY_HOME_VIEW, HOME_PREVIEW))
    }

    fun setAutoCollapse(value: Boolean) {
        autoCollapseSections = value
        prefs?.edit()?.putBoolean(KEY_AUTO_COLLAPSE, value)?.apply()
    }

    fun updateHistogramVisible(value: Boolean) {
        showHistogram = value
        prefs?.edit()?.putBoolean(KEY_SHOW_HISTOGRAM, value)?.apply()
    }

    fun updateWorkbenchAutoHide(value: Boolean) {
        workbenchAutoHide = value
        prefs?.edit()?.putBoolean(KEY_WORKBENCH_AUTO_HIDE, value)?.apply()
    }

    fun updateHomeViewMode(value: String) {
        homeViewMode = normalizeHomeView(value)
        prefs?.edit()?.putString(KEY_HOME_VIEW, homeViewMode)?.apply()
    }

    private fun normalizeHomeView(value: String?): String =
        if (value == HOME_GRID) HOME_GRID else HOME_PREVIEW

    fun updateWorkbenchHideSeconds(value: Int) {
        workbenchHideSeconds = value.coerceIn(3, 10)
        prefs?.edit()?.putInt(KEY_WORKBENCH_HIDE_SECONDS, workbenchHideSeconds)?.apply()
    }
}
