package com.spektralab.android.feature.editor.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.components.ActionButton
import com.spektralab.android.ui.components.SliderRow

// ============================================================================
//  一级菜单「调色」的三级参数体
//
//  改版后的分组（对齐手机版 Lightroom）：
//    影调   = 原「影调」滑轨 + 原「曲线」（两者合并成一个二级菜单）
//    色彩   = 白平衡 / 混合（色彩混合）/ 分级（颜色分级）/ 通道（通道混合）/ 平衡（色彩平衡）
//    暗角   = 原「暗角」
//    透视裁剪 = 原「几何与裁剪」改名
//  展开入口改成二级药丸，这里不再自带折叠标题；量程与调度与 1.0.2 一致。
// ============================================================================

/** 色彩 · 白平衡（成片上的扫描白平衡，与调参页的「前白平衡」不是一件事）。 */
@Composable
internal fun WhiteBalanceContent(vm: EditorViewModel) {
    SliderRow("色温(暖/冷)", vm.params.temperature, -1f..1f, "%+.2f",
        hint = "左滑更冷（偏蓝） · 右滑更暖（偏黄）") { vm.params.temperature = it; vm.scheduleGrade() }
    SliderRow("色调(绿/品红)", vm.params.tint, -1f..1f, "%+.2f",
        hint = "左滑偏绿 · 右滑偏品红") { vm.params.tint = it; vm.scheduleGrade() }
    ActionButton("画面上拾取白平衡", enabled = vm.canPickNeutral) {
        vm.beginNeutralPick(com.spektralab.android.core.color.NeutralWhiteBalance.Target.PRINT)
    }
}

/** 二级「影调」：曲线入口排在最前（曝光之前），再是影调滑轨。 */
@Composable
internal fun ToneContent(vm: EditorViewModel) {
    CurveEditor(vm)
    SliderRow("曝光", vm.params.gradeExposure, -5f..5f, "%+.2f EV",
        hint = "左滑更暗 · 右滑更亮") { vm.params.gradeExposure = it; vm.scheduleGrade() }
    SliderRow("对比度", vm.params.contrast, -100f..100f, "%+.0f",
        hint = "左滑更平（发灰） · 右滑反差更大") { vm.params.contrast = it; vm.scheduleGrade() }
    SliderRow("亮度", vm.params.brightness, -100f..100f, "%+.0f",
        hint = "左滑更暗 · 右滑更亮") { vm.params.brightness = it; vm.scheduleGrade() }
    SliderRow("高光", vm.params.highlights, -100f..100f, "%+.0f",
        hint = "左滑压高光（更暗） · 右滑提亮高光") { vm.params.highlights = it; vm.scheduleGrade() }
    SliderRow("阴影", vm.params.shadows, -100f..100f, "%+.0f",
        hint = "左滑压暗部（更黑） · 右滑提亮暗部") { vm.params.shadows = it; vm.scheduleGrade() }
    SliderRow("饱和度", vm.params.saturation, -100f..100f, "%+.0f",
        hint = "左滑降饱和（偏灰） · 右滑加饱和") { vm.params.saturation = it; vm.scheduleGrade() }
    SliderRow("黑点", vm.params.blackPoint, -100f..100f, "%+.0f",
        hint = "左滑抬黑（暗部发灰） · 右滑压黑（暗部更实）") { vm.params.blackPoint = it; vm.scheduleGrade() }
    SliderRow("白点", vm.params.whitePoint, -100f..100f, "%+.0f",
        hint = "左滑收白（高光变灰） · 右滑提白（高光更亮）") { vm.params.whitePoint = it; vm.scheduleGrade() }
}

/** 色彩 · 平衡：点开后标题「色彩平衡」。 */
@Composable
internal fun ColorImbalanceContent(vm: EditorViewModel) {
    SliderRow("阴影 色温", vm.params.cbShadowTemp, -100f..100f, "%+.0f") { vm.params.cbShadowTemp = it; vm.scheduleGrade() }
    SliderRow("阴影 色调", vm.params.cbShadowTint, -100f..100f, "%+.0f") { vm.params.cbShadowTint = it; vm.scheduleGrade() }
    SliderRow("中间调 色温", vm.params.cbMidTemp, -100f..100f, "%+.0f") { vm.params.cbMidTemp = it; vm.scheduleGrade() }
    SliderRow("中间调 色调", vm.params.cbMidTint, -100f..100f, "%+.0f") { vm.params.cbMidTint = it; vm.scheduleGrade() }
    SliderRow("高光 色温", vm.params.cbHighTemp, -100f..100f, "%+.0f") { vm.params.cbHighTemp = it; vm.scheduleGrade() }
    SliderRow("高光 色调", vm.params.cbHighTint, -100f..100f, "%+.0f") { vm.params.cbHighTint = it; vm.scheduleGrade() }
}

/** 二级「暗角」。 */
@Composable
internal fun VignetteContent(vm: EditorViewModel) {
    SliderRow("暗角强度", vm.params.vignetteAmount, 0f..100f, "%.0f%%",
        hint = "左滑更弱 · 右滑更强") {
        vm.params.vignetteAmount = it; vm.scheduleGrade()
    }
    SliderRow("暗角范围", vm.params.vignetteSize, 0f..100f, "%.0f%%",
        hint = "左滑暗角越小（压到中间） · 右滑暗角越大（只压边角）") {
        vm.params.vignetteSize = it; vm.scheduleGrade()
    }
    SliderRow("暗角羽化", vm.params.vignetteFeather, 0f..100f, "%.0f%%",
        hint = "左滑更硬（分界明显） · 右滑更柔") {
        vm.params.vignetteFeather = it; vm.scheduleGrade()
    }
}

/** 二级「透视裁剪」：原「几何与裁剪」改名，内容不变。 */
@Composable
internal fun PerspectiveCropContent(vm: EditorViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = { vm.params.quarterTurns = (vm.params.quarterTurns + 3) % 4; vm.scheduleRender() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.RotateLeft, contentDescription = "左转90度")
        }
        IconButton(onClick = { vm.params.quarterTurns = (vm.params.quarterTurns + 1) % 4; vm.scheduleRender() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.RotateRight, contentDescription = "右转90度")
        }
        IconToggleButton(checked = vm.params.flipH, onCheckedChange = { vm.params.flipH = it; vm.scheduleRender() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.SwapHoriz, contentDescription = "水平翻转")
        }
        IconToggleButton(checked = vm.params.flipV, onCheckedChange = { vm.params.flipV = it; vm.scheduleRender() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.SwapVert, contentDescription = "垂直翻转")
        }
    }
    SliderRow("旋转/拉直", vm.params.rotationDeg, -45f..45f, "%+.1f°") { vm.params.rotationDeg = it; vm.scheduleRender() }
    OutlinedButton(onClick = vm::beginCrop, enabled = vm.hasPhoto && !vm.busy && (!vm.params.overscanActive || vm.params.filmOff),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(Icons.Rounded.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("自由裁剪")
    }
    SliderRow("垂直透视", vm.params.perspectiveVertical, -30f..30f, "%+.1f°",
        hint = "校正仰拍/俯拍造成的竖直线条汇聚") { vm.params.perspectiveVertical = it; vm.scheduleRender() }
    SliderRow("水平透视", vm.params.perspectiveHorizontal, -30f..30f, "%+.1f°",
        hint = "校正侧拍造成的水平线条汇聚") { vm.params.perspectiveHorizontal = it; vm.scheduleRender() }
    OutlinedButton(onClick = { vm.params.perspectiveVertical = 0f; vm.params.perspectiveHorizontal = 0f; vm.scheduleRender() },
        enabled = !vm.busy && (vm.params.perspectiveVertical != 0f || vm.params.perspectiveHorizontal != 0f),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重置透视") }
}
