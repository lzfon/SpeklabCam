package com.spektralab.android.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.android.R
import com.spektralab.android.ui.theme.TheiaShapes

/** Resizing and full-screen viewing only change layout; both reuse the current image. */
@Composable
internal fun AdjustmentWorkspace(
    mode: String,
    fileName: String,
    comparing: Boolean,
    canCompare: Boolean,
    onCompare: () -> Unit,
    onFullScreen: () -> Unit,
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
    modifier: Modifier = Modifier,
    preview: @Composable (Modifier) -> Unit,
    controls: @Composable () -> Unit,
) {
    var controlsCollapsed by rememberSaveable { mutableStateOf(false) }
    // 预览右上角默认只是一颗半透明绿悬浮点，点开才出现前后对比/全屏。
    var previewToolsOpen by rememberSaveable { mutableStateOf(false) }
    var toolMenuOpen by remember { mutableStateOf(false) }
    var panelFraction by rememberSaveable { mutableFloatStateOf(.5f) }
    var lastMode by rememberSaveable { mutableStateOf(mode) }
    val scrollStates = rememberSaveableStateHolder()
    LaunchedEffect(mode) {
        if (lastMode != mode) {
            controlsCollapsed = false
            lastMode = mode
        }
    }
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
            controlsCollapsed = false
            panelFraction = (panelFraction - delta / heightPx).coerceIn(.3f, .72f)
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                preview(Modifier.fillMaxSize())
                if (previewToolsOpen) {
                    Row(Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = .85f), TheiaShapes.control)) {
                        PreviewComparisonButton(comparing, canCompare, onCompare)
                        IconButton(onClick = onFullScreen, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.OpenInFull, "全屏预览", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { previewToolsOpen = false }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.ExpandLess, "收起预览工具", modifier = Modifier.size(20.dp))
                        }
                    }
                } else {
                    // 悬浮点尽量不挡照片：26dp 半透明绿圆，点一下展开。
                    Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(26.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = .32f))
                        .border(.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = .75f), CircleShape)
                        .clickable(role = Role.Button) { previewToolsOpen = true }
                        .semantics { contentDescription = "展开预览工具" },
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.size(8.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = .9f)))
                    }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp, shape = TheiaShapes.sheet,
                modifier = Modifier.fillMaxWidth()) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f), thickness = .5.dp)
                    // 单行控制条：左（收起 · 重置 · 裁剪）居中拖动条 右（撤销 · 重做 · 导出）。
                    // 三组各自相对整行中线对齐，保证图标/文字落在同一条水平线上。
                    Box(Modifier.fillMaxWidth().height(handleHeight).padding(horizontal = 8.dp)) {
                        Row(Modifier.align(Alignment.CenterStart)) {
                            IconButton(onClick = { controlsCollapsed = !controlsCollapsed }, modifier = Modifier.size(36.dp)) {
                                Icon(if (controlsCollapsed) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = if (controlsCollapsed) "展开参数" else "收起参数",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp))
                            }
                            // 工具菜单：重置、裁剪、编辑配方、复制配方收到一个入口里。
                            Box {
                                IconButton(onClick = { toolMenuOpen = true }, modifier = Modifier.size(36.dp)
                                    .semantics { contentDescription = "打开工具菜单" }) {
                                    Icon(Icons.Rounded.Build, null, modifier = Modifier.size(16.dp),
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
                        }
                        Box(Modifier.align(Alignment.Center).width(92.dp).fillMaxHeight()
                            .draggable(dragState, Orientation.Vertical)
                            .semantics {
                                contentDescription = "调整预览大小"
                                stateDescription = "上下拖动调整照片与参数的空间"
                                progressBarRangeInfo = ProgressBarRangeInfo(panelFraction, .3f.. .72f)
                                setProgress {
                                    controlsCollapsed = false
                                    panelFraction = it.coerceIn(.3f, .72f)
                                    true
                                }
                            }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(44.dp, 4.dp).background(MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(2.dp)))
                        }
                        Row(Modifier.align(Alignment.CenterEnd)) {
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
                    if (!controlsCollapsed) {
                      scrollStates.SaveableStateProvider(mode) {
                        Column(Modifier.fillMaxWidth().heightIn(max = (panelHeight - handleHeight).coerceAtLeast(96.dp))
                            .verticalScroll(rememberScrollState())) {
                            controls()
                        }
                      }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AdjustmentFullscreen(
    mode: String,
    fileName: String,
    comparing: Boolean,
    canCompare: Boolean,
    onCompare: () -> Unit,
    onClose: () -> Unit,
    preview: @Composable (Modifier) -> Unit,
) {
    // 全屏预览的双指缩放/平移：只改显示变换，不重渲染、不动任何参数。
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var panX by rememberSaveable { mutableFloatStateOf(0f) }
    var panY by rememberSaveable { mutableFloatStateOf(0f) }
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Close, "关闭全屏预览", modifier = Modifier.size(22.dp))
                        }
                        Text(fileName, Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (zoom != 1f) TextButton(onClick = { zoom = 1f; panX = 0f; panY = 0f }) {
                            Text("回正 ${(zoom * 100).toInt()}%", fontSize = 12.sp)
                        }
                        PreviewComparisonButton(comparing, canCompare, onCompare)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()
                        .semantics { contentDescription = "全屏照片预览" }
                        .pointerInput(Unit) {
                            detectTransformGestures { centroid, pan, gestureZoom, _ ->
                                val newZoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                                // 回缩到 1 倍时同步把平移归零，画面一定回到居中。
                                if (newZoom == 1f) { panX = 0f; panY = 0f; zoom = 1f } else {
                                    val fx = centroid.x - size.width / 2f
                                    val fy = centroid.y - size.height / 2f
                                    panX = (panX + fx * (1f - gestureZoom)).coerceIn(-size.width * (newZoom - 1f) / 2f, size.width * (newZoom - 1f) / 2f)
                                    panY = (panY + fy * (1f - gestureZoom)).coerceIn(-size.height * (newZoom - 1f) / 2f, size.height * (newZoom - 1f) / 2f)
                                    zoom = newZoom
                                }
                            }
                        }) {
                        preview(Modifier.fillMaxSize().graphicsLayer {
                            scaleX = zoom; scaleY = zoom
                            translationX = if (zoom > 1f) panX else 0f
                            translationY = if (zoom > 1f) panY else 0f
                        })
                    }
                    Text("$mode · 原片始终保留 · 双指缩放查看细节", Modifier.align(Alignment.CenterHorizontally)
                        .padding(horizontal = 12.dp, vertical = 8.dp).semantics { contentDescription = "全屏预览说明" },
                        fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
