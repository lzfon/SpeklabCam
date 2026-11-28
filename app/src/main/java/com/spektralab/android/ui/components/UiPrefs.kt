package com.spektralab.android.ui.components

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 界面偏好：照片预览是否显示直方图、工作台怎么收起、首页用哪种显示方式，改动即刻生效。 */
object UiPrefs {
    private const val FILE = "theia_ui"
    private const val KEY_SHOW_HISTOGRAM = "preview_histogram"
    private const val KEY_WORKBENCH_AUTO_HIDE = "workbench_auto_hide"
    private const val KEY_WORKBENCH_HIDE_SECONDS = "workbench_hide_seconds"
    private const val KEY_HOME_VIEW = "home_view_mode"
    private const val HOME_PREVIEW = "preview"
    private const val HOME_GRID = "grid"
    /** 出厂默认改过一次（工作台不再自动收起），用这个号决定要不要把老设备的存档对齐到新一套默认。 */
    private const val DEFAULTS_SYNC = 1
    private const val KEY_DEFAULTS_SYNCED = "ui_defaults_synced"
    private var prefs: SharedPreferences? = null

    /** 照片预览左上角的直方图，默认显示，可在设置里关闭。 */
    var showHistogram by mutableStateOf(true)
        private set

    /** 工作台（照片条）闲置后是否自动收起成一条把手。默认关：收起反而让照片条找不到，想要的人在设置里开。 */
    var workbenchAutoHide by mutableStateOf(false)
        private set

    /** 工作台自动收起的等待秒数，3–10 秒。 */
    var workbenchHideSeconds by mutableStateOf(3)
        private set

    /** 首页预览区显示方式：preview = 当前照片预览框（默认）；grid = 图库照片两列宫格。 */
    var homeViewMode by mutableStateOf(HOME_PREVIEW)
        private set

    /** 首页预览区走的是不是图库宫格。 */
    val homeShowLibraryGrid: Boolean get() = homeViewMode == HOME_GRID

    fun attach(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        if (p.getInt(KEY_DEFAULTS_SYNCED, 0) < DEFAULTS_SYNC)
            // 只对齐这三项，别的偏好（隐藏秒数）不动；对齐过一次就不再覆盖用户后续的手动选择。
            p.edit()
                .putBoolean(KEY_WORKBENCH_AUTO_HIDE, false)
                .putBoolean(KEY_SHOW_HISTOGRAM, true)
                .putString(KEY_HOME_VIEW, HOME_PREVIEW)
                .putInt(KEY_DEFAULTS_SYNCED, DEFAULTS_SYNC)
                .apply()
        showHistogram = p.getBoolean(KEY_SHOW_HISTOGRAM, true)
        workbenchAutoHide = p.getBoolean(KEY_WORKBENCH_AUTO_HIDE, false)
        workbenchHideSeconds = p.getInt(KEY_WORKBENCH_HIDE_SECONDS, 3).coerceIn(3, 10)
        homeViewMode = normalizeHomeView(p.getString(KEY_HOME_VIEW, HOME_PREVIEW))
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
