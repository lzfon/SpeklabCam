package com.spektralab.android.feature.editor.ui.panels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.android.R
import com.spektralab.android.engine.StockCatalog
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.ui.editorOptionLabel
import com.spektralab.android.ui.components.EditorSection
import com.spektralab.android.ui.components.OptionsDropdown
import com.spektralab.android.ui.components.SliderRow
import com.spektralab.android.ui.components.ToggleRow
import kotlin.math.roundToInt

@Composable
internal fun PrintPanel(vm: EditorViewModel) {
    EditorSection("放大机", "打印曝光 · Y / M 滤色", initiallyExpanded = true) {
        SliderRow("打印曝光", vm.params.printExposure, 0.25f..4f, "%.2f", resetValue = 1f,
            hint = "左滑更暗 · 右滑更亮（数值越小越亮）", reverse = true) {
            vm.params.printExposure = it; vm.scheduleRender()
        }
        SliderRow("滤镜 Y(黄/蓝)", vm.params.yFilterShift, -1f..1f, "%+.0f CC", displayScale = 40f,
            hint = "左滑 Y 减小（黄滤色更少） · 右滑 Y 增大（黄滤色更多）") {
            vm.params.yFilterShift = it; vm.scheduleRender()
        }
        SliderRow("滤镜 M(品红/绿)", vm.params.mFilterShift, -1f..1f, "%+.0f CC", displayScale = 40f,
            hint = "左滑 M 减小（品红滤色更少） · 右滑 M 增大（品红滤色更多）") {
            vm.params.mFilterShift = it; vm.scheduleRender()
        }
    }

    EditorSection("乳剂与暗房效应", "颗粒 · 光晕 · DIR · 纸面光晕 · 预闪 · EDR", initiallyExpanded = false) {
        ToggleRow("颗粒", vm.params.grainActive) { vm.params.grainActive = it; vm.scheduleRender() }
        // 滑块上限就是这卷胶片的真实上限：发送时会乘 ownGrain，引擎又封顶 2.0。
        val grainCeiling = com.spektralab.android.core.model.EditorParams.grainCeiling(vm.params.filmId)
        SliderRow("颗粒强度", vm.params.grainAmount.coerceIn(0f, grainCeiling), 0f..grainCeiling, "%.2f×") {
            vm.params.grainAmount = it; vm.scheduleRender()
        }
        Text(
            "这卷胶片的上限是 %.2f×（按 %.2f 倍折算后引擎封顶 2.0，再高不会更浓）".format(
                grainCeiling, com.spektralab.android.core.model.EditorParams.ownGrain(vm.params.filmId)
            ),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        ToggleRow("分层颗粒", vm.params.grainLayered) { vm.params.grainLayered = it; vm.scheduleRender() }
        HalationControls(vm)
        ToggleRow("DIR 成色剂耦合", vm.params.dirCouplersActive) { vm.params.dirCouplersActive = it; vm.scheduleRender() }
        SliderRow("DIR 强度", vm.params.dirCouplersAmount, 0f..1.5f, "%.1f") { vm.params.dirCouplersAmount = it; vm.scheduleRender() }
        ToggleRow("纸面光晕", vm.params.glareActive) { vm.params.glareActive = it; vm.scheduleRender() }
        SliderRow("纸面光晕强度", vm.params.glareAmount, 0f..30f, "%.1f") { vm.params.glareAmount = it; vm.scheduleRender() }
        SliderRow("预闪", vm.params.preflashExposure, 0f..0.03f, "%.2f×") { vm.params.preflashExposure = it; vm.scheduleRender() }
        val supportsEdr = !StockCatalog.isPositive(vm.params.filmId) && !StockCatalog.isMonochrome(vm.params.paperId)
        ToggleRow("扩展动态范围", vm.params.extendedDynamicRange && supportsEdr, enabled = supportsEdr) {
            vm.params.extendedDynamicRange = it; vm.scheduleRender()
        }
        if (!supportsEdr) Text(stringResource(R.string.print_edr_unavailable), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
    }

    EditorSection("片边", "胶片格式 · 齿孔 · 雾化 · 漏光 · 帧号", initiallyExpanded = false) {
        ToggleRow("片边(整条胶片)", vm.params.overscanActive) { vm.params.overscanActive = it; vm.scheduleRender() }
        if (vm.params.overscanActive) {
            OptionsDropdown("画幅", listOf("135", "135_half", "135_xpan", "120_645", "120_6x6", "120_6x7", "120_6x8", "120_6x9", "120_6x12", "120_6x17"), vm.params.overscanFormat, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) {
                vm.params.overscanFormat = it; vm.scheduleRender()
            }
            OptionsDropdown("片边模式", listOf("strip", "filed"), vm.params.overscanMode, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) { vm.params.overscanMode = it; vm.scheduleRender() }
            OptionsDropdown("门形状", listOf("auto", "square", "rounded", "eared", "shouldered", "kicked"), vm.params.overscanGate, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) { vm.params.overscanGate = it; vm.scheduleRender() }
            OptionsDropdown("齿孔", listOf("white", "black"), vm.params.overscanHoles, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) { vm.params.overscanHoles = it; vm.scheduleRender() }
            OptionsDropdown("边缘外", listOf("none", "black", "open"), vm.params.overscanCarrier, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) { vm.params.overscanCarrier = it; vm.scheduleRender() }
            SliderRow("边缘雾化", vm.params.overscanFog, 0f..4f, "%.1f") { vm.params.overscanFog = it; vm.scheduleRender() }
            SliderRow("漏光", vm.params.overscanLeaks, 0f..4f, "%.1f") { vm.params.overscanLeaks = it; vm.scheduleRender() }
            SliderRow("光圈(门边光晕)", vm.params.overscanFNumber, 0f..64f, "%.1f") { vm.params.overscanFNumber = it; vm.scheduleRender() }
            // 帧号：0 = 这一张随机排一个（1-36），1-36 = 按设定输出。
            // 原来的「按导入数量 36 帧一轮回自动递增」和「手动帧号」开关都删了。
            SliderRow(
                label = "帧号",
                value = vm.params.overscanFrameNumber.toFloat(),
                range = 0f..36f,
                format = "%.0f / 36",
                resetValue = 0f,
                hint = if (vm.params.overscanFrameNumber == 0)
                    "0 = 随机：这一张排到 ${"%02d".format(vm.params.effectiveOverscanFrameNumber())}，拖回 0 再随机一次"
                else "按设定输出；批量导出页打开「带片边照片按帧号顺序导出」时会被重新连号",
            ) { vm.setOverscanFrameNumber(it.roundToInt()) }
            Text(
                "片边文字（自动）: ${StockCatalog.edgeText(vm.params.filmId, vm.params.overscanFormat)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
            // 种子：0 = 这一张随机一套（1-999），1-999 = 按设定输出。走 vm 的 setter，
            // 拖回 0 会重掷一次，否则停在 0 用的还是上一次那套磨损，看着像滑了没反应。
            SliderRow(
                label = "相机种子",
                value = vm.params.overscanCameraSeed.toFloat(),
                range = 0f..999f,
                format = "%.0f",
                resetValue = 0f,
                hint = if (vm.params.overscanCameraSeed == 0)
                    "0 = 随机：这一张用种子 ${vm.params.effectiveOverscanCameraSeed()}，拖回 0 再随机一次"
                else "按设定输出：同一颗机身得到同一套磨损，别的照片填同一个数就能复现",
            ) { vm.setOverscanCameraSeed(it.roundToInt()) }
            SliderRow(
                label = "过片种子",
                value = vm.params.overscanFrameSeed.toFloat(),
                range = 0f..999f,
                format = "%.0f",
                resetValue = 0f,
                hint = if (vm.params.overscanFrameSeed == 0)
                    "0 = 随机：这一张用种子 ${vm.params.effectiveOverscanFrameSeed()}，拖回 0 再随机一次"
                else "按设定输出：过片与扫描（漏光位置、编号抖动）跟着这个种子走",
            ) { vm.setOverscanFrameSeed(it.roundToInt()) }
        }
    }

    EditorSection("日期后背", "日期文字 · 拍摄数据 · 位置 · 曝光", initiallyExpanded = false) {
        ToggleRow("日期后背", vm.params.dateImprintActive, onChecked = vm::setDateImprintEnabled)
        if (vm.params.dateImprintActive) {
            OptionsDropdown("日期样式", listOf("lcd", "dots", "data"), vm.params.dateImprintStyle, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) {
                vm.params.dateImprintStyle = it
                vm.scheduleRender()
            }
            ToggleRow("打印拍摄参数", vm.params.dateImprintShowData) { vm.setDateImprintShowData(it) }
            if (vm.params.dateImprintShowData) {
                Text(
                    when {
                        vm.params.dateImprintStyle == "data" && vm.params.dateImprintDataText.isBlank() ->
                            "日期样式选的是“拍摄数据”，但这张照片没读到参数（EXIF 缺失），会退回印日期文字。"
                        vm.params.dateImprintStyle == "data" ->
                            "日期样式选的是“拍摄数据”：只印这一行参数，不印日期。"
                        vm.params.dateImprintDataText.isBlank() ->
                            "这张照片没读到拍摄参数（EXIF 缺失），打开也不会多印一行。"
                        else -> "会在日期下面多印一行：${vm.params.dateImprintDataText}"
                    },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            // 没开片边时引擎的「胶片边缘」无处可印，位置只能按画面内的角落算：
            // 这里把位置项收起来并说明，避免选了「胶片边缘」却还是画在画面里。
            if (vm.params.overscanActive && vm.params.overscanFormat !in com.spektralab.android.core.model.EditorParams.PANORAMIC_FORMATS) {
                OptionsDropdown("位置", listOf("frame", "rebate"), vm.params.dateImprintPlacement, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) { vm.params.dateImprintPlacement = it; vm.scheduleRender() }
            } else {
                Text(
                    if (vm.params.overscanActive) "全景画幅的片边不印日期，日期固定在画面内的角落。"
                    else "未开片边：日期只画在画面内，位置按下面的角落来。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            OptionsDropdown("角落", listOf("br", "bl", "tr", "tl"), vm.params.dateImprintCorner, Modifier.fillMaxWidth(), optionLabel = ::editorOptionLabel) { vm.params.dateImprintCorner = it; vm.scheduleRender() }
            SliderRow("大小", vm.params.dateImprintSize, 0.4f..3f, "%.2f", resetValue = 1f) { vm.params.dateImprintSize = it; vm.scheduleRender() }
            SliderRow("曝光", vm.params.dateImprintEv, -2f..8f, "%+.1f") { vm.params.dateImprintEv = it; vm.scheduleRender() }
            DateImprintFields(vm)
        }
    }
}
