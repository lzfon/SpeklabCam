package com.spektralab.android.feature.editor.ui.panels

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.ui.editorOptionLabel
import com.spektralab.android.ui.components.ActionButton
import com.spektralab.android.ui.components.OptionsDropdown
import com.spektralab.android.ui.components.SliderRow
import com.spektralab.android.ui.components.ToggleRow

// 胶片画幅 → 帧长边毫米数（引擎用它缩放颗粒/光晕微米量）。
private val FILM_FORMAT_MM = linkedMapOf(
    "135 (36mm)" to 36f,
    "645 (56mm)" to 56f,
    "6×6 (56mm)" to 56f,
    "6×7 (69.5mm)" to 69.5f,
    "6×8 (76mm)" to 76f,
    "6×9 (84mm)" to 84f,
    "6×12 (112mm)" to 112f,
    "6×17 (168mm)" to 168f,
)

// ============================================================================
//  一级菜单「调参」的三级参数体
//
//  改版说明：原来这里是「冲印」页里一长串折叠章节（EditorSection）。
//  现在展开入口换成二级药丸，所以每个函数只画控件本身，不再自带折叠标题；
//  参数、量程、复位、调度（scheduleRender / scheduleCameraWb）与 1.0.2 一致。
//  「相机白平衡 (RAW)」按需求改名为「前白平衡」——它作用在胶片模拟之前，
//  与调色页「色彩 · 白平衡」（作用在成片上）是两件事。
// ============================================================================

/** 二级「胶片模拟」：总开关，关掉以后直接走数字调色。 */
@Composable
internal fun FilmSimulationContent(vm: EditorViewModel) {
    ToggleRow("启用胶片模拟", !vm.params.filmOff) { vm.params.filmOff = !it; vm.scheduleRender() }
    if (vm.params.filmOff) Text(
        "已关闭胶片模拟：当前为数字调色。胶片、相纸、片边等设置原样保留，重新开启即恢复。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 二级「前曲线」：胶片模拟之前的场景曲线（曝光域）。 */
@Composable
internal fun PreCurveContent(vm: EditorViewModel) {
    CurveEditor(vm, decode = true)
}

/** 二级「画幅与配光」：胶片物理画幅与自动配光读数。 */
@Composable
internal fun FilmFormatContent(vm: EditorViewModel) {
    OptionsDropdown(
        "胶片画幅",
        FILM_FORMAT_MM.keys.toList(),
        FILM_FORMAT_MM.entries.firstOrNull { it.value == vm.params.filmFormatMm }?.key ?: "135 (36mm)",
        Modifier.fillMaxWidth()
    ) { label ->
        FILM_FORMAT_MM[label]?.let { vm.params.filmFormatMm = it }
        vm.scheduleRender()
    }

    vm.meteringReadout?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    ActionButton("清除手动滤色偏移", enabled = vm.hasPhoto && !vm.busy, onClick = { vm.clearFilterShifts() })
}

/** 二级「测光曝光」：原「曝光与测光」改名，内容不变。 */
@Composable
internal fun MeteringContent(vm: EditorViewModel) {
    SliderRow("曝光补偿", vm.params.exposureEv, -3f..3f, "%+.1f EV",
        hint = "左滑减曝（成片更暗） · 右滑加曝（成片更亮）") {
        vm.params.exposureEv = it; vm.scheduleRender()
    }
    OptionsDropdown("测光方法", listOf("balanced", "center", "protect_highlights", "protect_shadows"), vm.params.autoExposureMethod, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) {
        vm.params.autoExposureMethod = it; vm.scheduleRender()
    }
    ToggleRow("自动测光", vm.params.autoExposure) { vm.params.autoExposure = it; vm.scheduleRender() }
}

/** 二级「宽容度」：原「场景宽容度」改名，内容不变。 */
@Composable
internal fun LatitudeContent(vm: EditorViewModel) {
    ActionButton("测量宽容度", enabled = vm.hasPhoto && !vm.busy, onClick = { vm.measureLatitude() })
    vm.latitudeReadout?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (vm.latitudeCanApply) {
        ActionButton("应用场景置位", onClick = { vm.applyLatitude() })
    }
}

/** 二级「前白平衡」：原「相机白平衡 (RAW)」，只有 RAW 才出这一项。 */
@Composable
internal fun CameraWhiteBalanceContent(vm: EditorViewModel) {
    ToggleRow("镜头校正", vm.lensCorrectionActive && vm.matchedLens != null) { vm.toggleLensCorrection() }
    vm.matchedLens?.let { Text("已匹配镜头: $it", style = MaterialTheme.typography.bodySmall) }
        ?: Text("未找到镜头配置，保留原始画面", style = MaterialTheme.typography.bodySmall)
    ToggleRow("自定义白平衡", vm.params.cameraWbCustom) { vm.params.cameraWbCustom = it; vm.scheduleCameraWb() }
    val presetEntries = listOf(
        "拍摄白平衡" to null,
        "日光 5500K" to (5500f to 0f),
        "阴天 6500K" to (6500f to 0f),
        "阴影 7500K" to (7500f to 0f),
        "钨丝灯 2850K" to (2850f to 0f),
        "荧光灯 4000K" to (4000f to 20f),
        "闪光灯 6000K" to (6000f to 0f),
    )
    val currentPreset = when {
        !vm.params.cameraWbCustom -> "拍摄白平衡"
        vm.params.cameraWbTemperatureAsShot || vm.params.cameraWbTintAsShot -> "拍摄白平衡（部分沿用）"
        else -> presetEntries.firstOrNull { entry ->
            val value = entry.second
            value != null && kotlin.math.abs(value.first - vm.params.cameraWbKelvin) < 1f &&
                kotlin.math.abs(value.second - vm.params.cameraWbTint) < 0.5f
        }?.first ?: "自定义 ${"%.0f".format(vm.params.cameraWbKelvin)}K"
    }
    OptionsDropdown("白平衡预设", presetEntries.map { it.first }, currentPreset, Modifier.fillMaxWidth()) { label ->
        val value = presetEntries.firstOrNull { it.first == label }?.second
        if (value == null) vm.resetCameraWb() else vm.applyRawWhiteBalancePreset(value.first, value.second)
    }
    ActionButton("画面上拾取相机白平衡", enabled = vm.canPickNeutral) {
        vm.beginNeutralPick(com.spektralab.android.core.color.NeutralWhiteBalance.Target.DECODE)
    }
    if (vm.params.cameraWbCustom) {
        val shot = vm.cameraAsShot
        Text(
            if (shot == null) "拍摄白平衡不可用（原片缺少相机标定）"
            else "拍摄白平衡 ${"%.0f".format(shot.temperature)} K · 色调 ${"%+.0f".format(shot.tint)}",
            style = MaterialTheme.typography.bodySmall
        )
        ToggleRow("色温沿用拍摄值", vm.params.cameraWbTemperatureAsShot && shot != null) {
            if (shot == null) return@ToggleRow
            vm.params.cameraWbTemperatureAsShot = it; vm.scheduleCameraWb()
        }
        ToggleRow("色调沿用拍摄值", vm.params.cameraWbTintAsShot && shot != null) {
            if (shot == null) return@ToggleRow
            vm.params.cameraWbTintAsShot = it; vm.scheduleCameraWb()
        }
        SliderRow("色温 K", vm.params.cameraWbKelvin, 2000f..12000f, "%.0f K", resetValue = 5500f,
            enabled = !vm.params.cameraWbTemperatureAsShot,
            hint = "左滑更冷（偏蓝） · 右滑更暖（偏黄）") { vm.params.cameraWbKelvin = it; vm.scheduleCameraWb() }
        SliderRow("色调", vm.params.cameraWbTint, -150f..150f, "%+.0f",
            enabled = !vm.params.cameraWbTintAsShot,
            hint = "左滑偏绿 · 右滑偏品红") { vm.params.cameraWbTint = it; vm.scheduleCameraWb() }
        ActionButton("恢复拍摄白平衡", onClick = { vm.resetCameraWb() })
    }
}

/** 二级「镜头滤镜」：Wratten 彩色滤镜。 */
@Composable
internal fun LensFilterContent(vm: EditorViewModel) {
    OptionsDropdown("镜头滤镜", listOf("无", "w8", "w11", "w12", "w15", "w21", "w25", "w47", "w58"), vm.params.cameraFilter.ifEmpty { "无" }, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) {
        vm.params.cameraFilter = if (it == "无") "" else it; vm.scheduleRender()
    }
}
