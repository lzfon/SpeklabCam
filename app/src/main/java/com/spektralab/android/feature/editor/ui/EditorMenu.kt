package com.spektralab.android.feature.editor.ui

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.spektralab.android.EditorTab
import com.spektralab.android.R
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.ui.panels.CameraWhiteBalanceContent
import com.spektralab.android.feature.editor.ui.panels.ChannelMixerEditor
import com.spektralab.android.feature.editor.ui.panels.ColorEditor
import com.spektralab.android.feature.editor.ui.panels.ColorImbalanceContent
import com.spektralab.android.feature.editor.ui.panels.ColorWheelEditor
import com.spektralab.android.feature.editor.ui.panels.DateImprintContent
import com.spektralab.android.feature.editor.ui.panels.EmulsionContent
import com.spektralab.android.feature.editor.ui.panels.EnlargerContent
import com.spektralab.android.feature.editor.ui.panels.FilmFormatContent
import com.spektralab.android.feature.editor.ui.panels.FilmSimulationContent
import com.spektralab.android.feature.editor.ui.panels.LatitudeContent
import com.spektralab.android.feature.editor.ui.panels.LensFilterContent
import com.spektralab.android.feature.editor.ui.panels.MeteringContent
import com.spektralab.android.feature.editor.ui.panels.OverscanContent
import com.spektralab.android.feature.editor.ui.panels.PerspectiveCropContent
import com.spektralab.android.feature.editor.ui.panels.PreCurveContent
import com.spektralab.android.feature.editor.ui.panels.ToneContent
import com.spektralab.android.feature.editor.ui.panels.VignetteContent
import com.spektralab.android.feature.editor.ui.panels.WhiteBalanceContent

/**
 * 编辑页的三级菜单模型，结构对齐手机版 Lightroom：
 *
 *  - 一级菜单：底栏页签（拍摄 / 调参 / 暗房 / 调色）；
 *  - 二级菜单：页签上方那一排药丸，点一个弹一个，再点同一个收回；
 *  - 三级参数：药丸展开后的滑轨区，不再另起一行标题。
 *    「色彩」下面还有一小排子药丸（白平衡 / 混合 / 分级 / 通道 / 平衡），点哪颗就切哪组。
 */
internal data class MenuSubTab(
    val key: String,
    val chip: String,
    val title: String,
    @DrawableRes val icon: Int,
    val content: @Composable (EditorViewModel) -> Unit,
)

internal data class MenuSection(
    val tab: EditorTab,
    val key: String,
    val chip: String,
    val title: String,
    @DrawableRes val icon: Int,
    val available: (EditorViewModel) -> Boolean = { true },
    val subTabs: List<MenuSubTab> = emptyList(),
    val content: (@Composable (EditorViewModel) -> Unit)? = null,
)

internal object EditorMenu {

    private val filmOn: (EditorViewModel) -> Boolean = { !it.params.filmOff }

    /**
     * 一级「调参」：原「冲印」里曝光链路与画幅片基的部分。
     * 顺序：胶片模拟 → 画幅与配光 → 前曲线 → 测光曝光 → 宽容度 → 前白平衡 → 镜头滤镜 →
     * 片边 → 日期（第三轮要求：片边、日期放到最后面）。
     */
    private val adjust = listOf(
        MenuSection(EditorTab.Adjust, "film", "胶片模拟", "胶片模拟", R.drawable.ic_spk_menu_film,
            content = { FilmSimulationContent(it) }),
        MenuSection(EditorTab.Adjust, "format", "画幅与配光", "画幅与配光", R.drawable.ic_spk_menu_ratio,
            available = filmOn, content = { FilmFormatContent(it) }),
        MenuSection(EditorTab.Adjust, "curve", "前曲线", "胶片前曲线", R.drawable.ic_spk_menu_curve,
            available = filmOn, content = { PreCurveContent(it) }),
        MenuSection(EditorTab.Adjust, "meter", "测光曝光", "测光曝光", R.drawable.ic_spk_menu_meter,
            available = filmOn, content = { MeteringContent(it) }),
        MenuSection(EditorTab.Adjust, "latitude", "宽容度", "场景宽容度", R.drawable.ic_spk_menu_latitude,
            available = filmOn, content = { LatitudeContent(it) }),
        MenuSection(EditorTab.Adjust, "camwb", "前白平衡", "前白平衡", R.drawable.ic_spk_menu_camwb,
            available = { it.currentIsRaw }, content = { CameraWhiteBalanceContent(it) }),
        MenuSection(EditorTab.Adjust, "filter", "镜头滤镜", "镜头滤镜", R.drawable.ic_spk_menu_filter,
            available = filmOn, content = { LensFilterContent(it) }),
        MenuSection(EditorTab.Adjust, "overscan", "片边", "胶片片边", R.drawable.ic_spk_menu_frame,
            content = { OverscanContent(it) }),
        MenuSection(EditorTab.Adjust, "date", "日期", "日期后背", R.drawable.ic_spk_menu_date,
            content = { DateImprintContent(it) }),
    )

    /** 一级「暗房」：放大机与乳剂反应。 */
    private val darkroom = listOf(
        MenuSection(EditorTab.Darkroom, "enlarger", "放大机", "放大机", R.drawable.ic_spk_menu_enlarger,
            content = { EnlargerContent(it) }),
        MenuSection(EditorTab.Darkroom, "emulsion", "乳剂暗房反应", "乳剂与暗房反应", R.drawable.ic_spk_menu_emulsion,
            content = { EmulsionContent(it) }),
    )

    /** 一级「调色」：影调（含曲线）、色彩、暗角、透视裁剪。 */
    private val grade = listOf(
        MenuSection(EditorTab.Grade, "tone", "影调", "影调", R.drawable.ic_spk_menu_tone,
            content = { ToneContent(it) }),
        MenuSection(EditorTab.Grade, "color", "色彩", "色彩", R.drawable.ic_spk_menu_color,
            subTabs = listOf(
                MenuSubTab("wb", "白平衡", "白平衡", R.drawable.ic_spk_menu_wb) { WhiteBalanceContent(it) },
                MenuSubTab("mix", "混合", "色彩混合", R.drawable.ic_spk_menu_blend) { ColorEditor(it, embedded = true) },
                MenuSubTab("grade", "分级", "颜色分级", R.drawable.ic_spk_menu_grading) { ColorWheelEditor(it, embedded = true) },
                MenuSubTab("channel", "通道", "通道混合", R.drawable.ic_spk_menu_channels) { ChannelMixerEditor(it, embedded = true) },
                MenuSubTab("balance", "平衡", "色彩平衡", R.drawable.ic_spk_menu_balance) { ColorImbalanceContent(it) },
            )),
        MenuSection(EditorTab.Grade, "vignette", "暗角", "暗角", R.drawable.ic_spk_menu_vignette,
            content = { VignetteContent(it) }),
        MenuSection(EditorTab.Grade, "perspective", "透视裁剪", "透视裁剪", R.drawable.ic_spk_menu_perspective,
            content = { PerspectiveCropContent(it) }),
    )

    fun sections(tab: EditorTab): List<MenuSection> = when (tab) {
        EditorTab.Adjust -> adjust
        EditorTab.Darkroom -> darkroom
        EditorTab.Grade -> grade
        EditorTab.Export, EditorTab.Gallery -> emptyList()
    }

    fun visibleSections(tab: EditorTab, vm: EditorViewModel): List<MenuSection> =
        sections(tab).filter { it.available(vm) }

    fun isMenuTab(tab: EditorTab): Boolean = tab == EditorTab.Adjust || tab == EditorTab.Darkroom || tab == EditorTab.Grade
}

/**
 * 菜单只有开合状态，不碰任何参数：
 *  - [collapsed]：整块菜单（二级 + 三级）收起，只留常驻的胶片/相纸栏和导出那一栏；
 *  - [openKey]：当前展开的二级菜单；
 *  - [lastByTab]：每个一级页签上次用的是哪个二级，供「再点一级菜单直接打开上次的参数菜单」。
 */
internal class MenuBoardState {
    // 默认展开：进编辑页先看到二级药丸那一排，点药丸才出三级参数（跟 LR 一样）。
    var collapsed by mutableStateOf(false)
        private set
    var openKey by mutableStateOf<String?>(null)
    var lastByTab by mutableStateOf<Map<String, String>>(emptyMap())

    fun lastKeyOf(tab: EditorTab): String? = lastByTab[tab.name]

    /** 切到另一个一级页签：展开菜单板，并接回该页签上次用的二级菜单。 */
    fun openTab(tab: EditorTab) {
        collapsed = false
        openKey = lastKeyOf(tab)
    }

    /** 再点一次当前一级页签：参数弹着就整体折叠，折叠着就直接打开上次那个参数菜单。 */
    fun retapTab(tab: EditorTab) {
        if (collapsed) {
            collapsed = false
            openKey = openKey ?: lastKeyOf(tab)
        } else {
            collapsed = true
        }
    }

    fun toggleSection(section: MenuSection) {
        collapsed = false
        val next = if (openKey == section.key) null else section.key
        setSection(next, section.tab)
    }

    fun setSection(key: String?, tab: EditorTab) {
        openKey = key
        if (key != null) lastByTab = lastByTab + (tab.name to key)
    }


    /** 当前该展开哪个二级；如果它已经不可用（比如非 RAW 时的前白平衡），自动退回收起。 */
    fun currentSection(tab: EditorTab, vm: EditorViewModel): MenuSection? {
        val found = EditorMenu.visibleSections(tab, vm).firstOrNull { it.key == openKey }
        if (found == null && openKey != null) openKey = null
        return found
    }

    companion object {
        /** 折叠状态、当前二级、每个一级上次的二级，全部拼成一个字符串存进 rememberSaveable。 */
        val Saver: Saver<MenuBoardState, String> = Saver(
            save = { state ->
                buildString {
                    append(if (state.collapsed) 1 else 0)
                    append('|'); append(state.openKey ?: "")
                    append('|')
                    state.lastByTab.forEach { (tab, key) -> append("$tab=$key;") }
                }
            },
            restore = { text ->
                val parts = text.split('|')
                MenuBoardState().apply {
                    collapsed = parts.getOrElse(0) { "0" } == "1"
                    openKey = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
                    lastByTab = parts.getOrNull(2).orEmpty().splitToSequence(';')
                        .mapNotNull { entry -> entry.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                        .toMap()
                }
            },
        )
    }
}

@Composable
internal fun rememberMenuBoardState(): MenuBoardState =
    rememberSaveable(saver = MenuBoardState.Saver) { MenuBoardState() }
