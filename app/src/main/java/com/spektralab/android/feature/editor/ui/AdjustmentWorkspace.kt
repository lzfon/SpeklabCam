package com.spektralab.android.feature.editor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.android.R
import com.spektralab.android.ui.theme.TheiaShapes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*

/** Resizing and full-screen viewing only change layout; both reuse the current image. */
@Composable
internal fun AdjustmentWorkspace(
    mode: String,
    fileName: String,
    comparing: Boolean,
    canCompare: Boolean,
    onCompare: () -> Unit,
    onDone: () -> Unit,
    onExport: () -> Unit = {},
    onReset: () -> Unit = {},
    canReset: Boolean = false,
    onCrop: () -> Unit = {},
    canCrop: Boolean = false,
    onFinishedCrop: () -> Unit = {},
    canFinishedCrop: Boolean = false,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onEditRecipe: () -> Unit = {},
    onCopyRecipe: () -> Unit = {},
    canTransfer: Boolean = false,
    workbenchOpen: Boolean = true,
    onWorkbenchToggle: () -> Unit = {},
    onCapture: () -> Unit = {},
    modifier: Modifier = Modifier,
    preview: @Composable (Modifier) -> Unit,
    persistentControls: @Composable () -> Unit = {},
    menuBoard: @Composable ColumnScope.() -> Unit = {},
) {
    var toolMenuOpen by remember { mutableStateOf(false) }
    var panelFraction by rememberSaveable { mutableFloatStateOf(.5f) }
    var lastMode by rememberSaveable { mutableStateOf(mode) }
    val scrollStates = rememberSaveableStateHolder()
    LaunchedEffect(mode) { lastMode = mode }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val heightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val minimumPreview = minOf(192.dp, maxHeight * .35f)
        val minimumPanel = minOf(240.dp, maxHeight * .5f)
        // Give typing the available height without moving the controls to a different composition.
        // Keep the user's split fraction so hiding the keyboard restores the photo workspace.
        val typing = WindowInsets.ime.getBottom(density) > 0
        val panelHeight = if (typing) maxHeight else
            (maxHeight * panelFraction).coerceIn(minimumPanel, maxHeight - minimumPreview)
        val handleHeight = 48.dp
        val dragState = rememberDraggableState { delta ->
            panelFraction = (panelFraction - delta / heightPx).coerceIn(.3f, .72f)
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                preview(Modifier.fillMaxSize())
                // 预览工具只剩前后对比一颗：右上角、只有图标、不加圆底。
                // 全屏放大页已删除，放大改成双指直接在画面上缩放（见 PhotoCanvas）。
                Box(Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 4.dp)) {
                    PreviewComparisonButton(comparing, canCompare, onCompare)
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp, shape = TheiaShapes.sheet,
                modifier = Modifier.fillMaxWidth()) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f), thickness = .5.dp)
                    // 单行控制条：左（工作台 · 工具菜单）居中拖动条 右（撤销 · 重做 · 导出）。
                    // 三组各自相对整行中线对齐，保证图标/文字落在同一条水平线上。
                    Box(Modifier.fillMaxWidth().height(handleHeight).padding(horizontal = 8.dp)) {
                        Row(Modifier.align(Alignment.CenterStart),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            // 工作台按钮：文字 + 箭头。收起时是「上箭头」（点它把工作台拉上来），
                            // 弹出时是「下箭头」（点它收下去）。参数栏整体的隐藏仍由一级菜单负责。
                            Row(
                                Modifier.clip(RoundedCornerShape(10.dp))
                                    .clickable(role = Role.Button, onClick = onWorkbenchToggle)
                                    .semantics {
                                        contentDescription =
                                            if (workbenchOpen) "收起工作台" else "弹出工作台"
                                    }
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("工作台", fontSize = 14.sp, lineHeight = 18.sp, maxLines = 1,
                                    color = MaterialTheme.colorScheme.primary)
                                Icon(if (workbenchOpen) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                                    null, tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp))
                            }
                            // 工具菜单：重置、裁剪、编辑配方、复制配方收到一个入口里。
                            Box {
                                IconButton(onClick = { toolMenuOpen = true }, modifier = Modifier.size(34.dp)
                                    .semantics { contentDescription = "打开工具菜单" }) {
                                    Icon(Icons.Rounded.MoreHoriz, null, modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                                DropdownMenu(expanded = toolMenuOpen, onDismissRequest = { toolMenuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(painterResource(R.drawable.ic_spk_scissors), null, Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.onSurface)
                                            Spacer(Modifier.width(10.dp)); Text("裁剪")
                                        } },
                                        onClick = { toolMenuOpen = false; onCrop() }, enabled = canCrop)
                                    DropdownMenuItem(
                                        text = { Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(painterResource(R.drawable.ic_spk_scissors), null, Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.onSurface)
                                            Spacer(Modifier.width(10.dp)); Text("成片裁切")
                                        } },
                                        onClick = { toolMenuOpen = false; onFinishedCrop() }, enabled = canFinishedCrop)
                                    DropdownMenuItem(
                                        text = { Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(painterResource(R.drawable.ic_spk_reset), null, Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.onSurface)
                                            Spacer(Modifier.width(10.dp)); Text("重置")
                                        } },
                                        onClick = { toolMenuOpen = false; onReset() }, enabled = canReset)
                                    DropdownMenuItem(
                                        text = { Text("编辑配方") },
                                        onClick = { toolMenuOpen = false; onEditRecipe() }, enabled = canTransfer)
                                    DropdownMenuItem(
                                        text = { Text("复制配方") },
                                        onClick = { toolMenuOpen = false; onCopyRecipe() }, enabled = canTransfer)
                                }
                            }
                            IconButton(onClick = onCapture, modifier = Modifier.size(34.dp)
                                .semantics { contentDescription = "拍摄" }) {
                                Icon(painterResource(R.drawable.ic_spk_tab_capture), null,
                                    modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Box(Modifier.align(Alignment.Center).width(92.dp).fillMaxHeight()
                            .draggable(dragState, Orientation.Vertical)
                            .semantics {
                                contentDescription = "调整预览大小"
                                stateDescription = "上下拖动调整照片与参数的空间"
                                progressBarRangeInfo = ProgressBarRangeInfo(panelFraction, .3f.. .72f)
                                setProgress {
                                    panelFraction = it.coerceIn(.3f, .72f)
                                    true
                                }
                            }, contentAlignment = Alignment.Center) {
                            // 把手横线缩短到原来一半，拖动热区仍是 92dp 宽。
                            Box(Modifier.size(22.dp, 4.dp).background(MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(2.dp)))
                        }
                        Row(Modifier.align(Alignment.CenterEnd),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            ControlButton("撤销上一步", canUndo, onUndo) { tint ->
                                Icon(painterResource(R.drawable.ic_darkroom_undo_2), null, Modifier.size(20.dp), tint = tint)
                            }
                            ControlButton("重做下一步", canRedo, onRedo) { tint ->
                                Icon(painterResource(R.drawable.ic_darkroom_redo_2), null, Modifier.size(20.dp), tint = tint)
                            }
                            Row(Modifier.height(36.dp).clickable(role = Role.Button, onClick = onExport)
                                .padding(horizontal = 6.dp)
                                .semantics { contentDescription = "打开导出菜单" },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Icon(painterResource(R.drawable.ic_spk_tab_export), null, Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Text("导出", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                            }
                        }
                    }
                    // 工作台组：照片条在上、胶片 / 相纸栏在下，整组跟着工作台按钮弹出 / 收起。
                    AnimatedVisibility(visible = workbenchOpen, enter = MenuEnter, exit = MenuExit,
                        label = "workbench-pop") {
                        Column(Modifier.fillMaxWidth()) { persistentControls() }
                    }
                    Column(Modifier.fillMaxWidth().heightIn(max = (panelHeight - handleHeight).coerceAtLeast(140.dp))) {
                        scrollStates.SaveableStateProvider(mode) {
                            // 菜单板自己带列权重：二级药丸固定在顶上，三级参数在自己的空间里滚。
                            Column(Modifier.fillMaxWidth()) { menuBoard() }
                        }
                    }
                }
            }
        }
    }
}


/** 控制条里的单个图标按钮：内容由调用处决定，颜色统一跟随主题绿 / 禁用灰。 */
@Composable
private fun ControlButton(description: String, enabled: Boolean, onClick: () -> Unit,
                          content: @Composable (androidx.compose.ui.graphics.Color) -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)
        .semantics { contentDescription = description }) {
        val tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f)
        content(tint)
    }
}



@Composable
private fun PreviewComparisonButton(comparing: Boolean, enabled: Boolean, onCompare: () -> Unit) {
    IconToggleButton(checked = comparing, onCheckedChange = { onCompare() }, enabled = enabled,
        modifier = Modifier.size(48.dp)) {
        Icon(painterResource(R.drawable.ic_darkroom_columns_2),
            if (comparing) "关闭前后对比" else "前后对比", modifier = Modifier.size(20.dp),
            tint = if (comparing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
