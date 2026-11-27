package com.spektralab.android.feature.editor.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.animation.AnimatedVisibility
import com.spektralab.android.feature.editor.ui.MenuEnter
import com.spektralab.android.feature.editor.ui.MenuExit
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.RequestDisallowInterceptTouchEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.spektralab.android.R
import com.spektralab.android.core.color.CurveChannel
import com.spektralab.android.core.color.ToneCurve
import com.spektralab.android.core.color.ToneCurves
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.ui.PhotoCanvas
import com.spektralab.android.ui.theme.TheiaShapes
import kotlin.math.sqrt

/** 把「成片曲线」与「胶片前曲线（Decode 场景曲线）」统一到同一套编辑界面。
 *  decode=false 走 params.toneCurves（成片，应用侧）；decode=true 走 params.sceneCurves（胶片前，引擎侧）。 */
private class CurveBinding(val vm: EditorViewModel, val decode: Boolean) {
    val curves: ToneCurves get() = if (decode) vm.params.sceneCurves.curves else vm.params.toneCurves
    val unsupported: String? get() = if (decode) vm.params.unreadableSceneCurves else vm.params.unreadableToneCurves
    fun begin() { if (decode) vm.beginSceneCurveEdit() else vm.beginCurveEdit() }
    fun change(curves: ToneCurves) { if (decode) vm.changeSceneCurves(curves) else vm.changeCurves(curves) }
    fun finish(cancel: Boolean = false) { if (decode) vm.finishSceneCurveEdit(cancel) else vm.finishCurveEdit(cancel) }
}

/**
 * 曲线：不再跳独立页面。点「编辑曲线」在原位展开，再点收回；
 * 上面的预览实时跟着走，撤销 / 重做用控制条那一排。
 * decode=false 调成片曲线（调色 · 影调里），decode=true 调胶片前曲线（调参里）。
 */
@Composable
internal fun CurveEditor(vm: EditorViewModel, decode: Boolean = false) {
    val target = remember(vm, decode) { CurveBinding(vm, decode) }
    var opened by rememberSaveable(vm.currentUri, decode) { mutableStateOf(false) }
    val toolState = rememberSaveableStateHolder()
    val enabled = (!decode || !vm.params.filmOff) && vm.hasPhoto && !vm.busy && !vm.isCropping && !vm.isExporting && !vm.isBatchExporting
    val entryTitle = if (decode) "胶片前曲线" else stringResource(R.string.curve_title)
    val action = if (decode) "胶片前曲线" else "曲线"
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                // 标题与其它参数标题同档字号（bodyMedium），不再另起一套字体。
                Text(entryTitle, style = MaterialTheme.typography.bodyMedium)
                Text(if (decode) {
                        if (target.unsupported != null) "来自更高版本，暂不支持编辑"
                        else if (target.curves.isDefault) "未调整 · 作用于胶片模拟之前" else "已调整 · 曝光域场景曲线"
                    } else stringResource(if (vm.params.unreadableToneCurves != null) R.string.curve_unsupported
                        else if (target.curves.isDefault) R.string.curve_entry_summary else R.string.curve_entry_changed),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }
            OutlinedButton(onClick = { opened = !opened }, enabled = enabled, shape = TheiaShapes.control,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (opened) "收回$action" else "编辑$action", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
        // 就地弹出：动画与二 / 三级同一节拍，收回即撤销未提交的拖拽（CurveControls 自带）。
        AnimatedVisibility(visible = opened, enter = MenuEnter, exit = MenuExit, label = "curve-inline-pop") {
            toolState.SaveableStateProvider(if (decode) "scene-curve-inline" else "curve-inline") {
                Surface(Modifier.fillMaxWidth().clip(TheiaShapes.panel),
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    CurveControls(vm, target, 216.dp, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
    }
}


@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun CurveControls(vm: EditorViewModel, target: CurveBinding, graphHeight: Dp, modifier: Modifier = Modifier) {
    val decode = target.decode
    val plotHeight = if (decode) (graphHeight - 48.dp).coerceAtLeast(100.dp) else graphHeight
    val titles =
        listOf(
            stringResource(R.string.curve_rgb),
            stringResource(R.string.curve_luma),
            stringResource(R.string.curve_red),
            stringResource(R.string.curve_green),
            stringResource(R.string.curve_blue),
        )
    var channelIndex by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable(vm.currentUri, channelIndex) { mutableIntStateOf(0) }
    var precision by rememberSaveable { mutableStateOf(false) }
    var resetMenu by remember { mutableStateOf(false) }
    var plotSize by remember { mutableStateOf(IntSize.Zero) }
    var dragging: ToneCurve? by remember { mutableStateOf(null) }
    var dragIndex by remember { mutableIntStateOf(0) }
    var grabOffset by remember { mutableStateOf(Offset.Zero) }
    var pendingTap by remember { mutableStateOf<Offset?>(null) }
    var virtualDrag by remember { mutableStateOf(false) }
    var moved by remember { mutableStateOf(false) }
    var down by remember { mutableStateOf(Offset.Zero) }
    val dragOwnership = remember { RequestDisallowInterceptTouchEvent() }
    val channel = CurveChannel.entries[channelIndex]
    val curves = target.curves
    val curve = curves[channel]
    val enabled = (!decode || !vm.params.filmOff) && vm.hasPhoto && !vm.busy && !vm.isExporting && !vm.isBatchExporting
    val pointIndex = selected.coerceIn(curve.points.indices)
    val point = curve.points[pointIndex]
    val plotLabel = stringResource(R.string.curve_plot, titles[channelIndex])
    if (target.unsupported != null) {
        Column {
            Text(if (decode) "这条胶片前曲线来自更高版本，暂不支持编辑。" else stringResource(R.string.curve_unsupported),
                style = MaterialTheme.typography.bodyMedium)
            Text(
                if (decode) "可以重置为直线，或关闭胶片模拟后查看。" else stringResource(R.string.curve_unsupported_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = {
                    target.begin()
                    target.change(ToneCurves.IDENTITY)
                    target.finish()
                },
                enabled = enabled,
            ) {
                Text(stringResource(R.string.curve_reset_all))
            }
        }
        return
    }
    val addLabel = stringResource(R.string.curve_add_midpoint)
    val previousLabel = stringResource(R.string.curve_previous_point)
    val nextLabel = stringResource(R.string.curve_next_point)
    DisposableEffect(vm.currentUri, channel) { onDispose { dragOwnership(false); target.finish(cancel = true) } }
    val accent =
        when (channel) {
            CurveChannel.RED -> Color(0xFFED9691)
            CurveChannel.GREEN -> Color(0xFF9DCB9E)
            CurveChannel.BLUE -> Color(0xFF98B6ED)
            else -> MaterialTheme.colorScheme.primary
        }
    val line = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val touchSlop = LocalViewConfiguration.current.touchSlop
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            titles.forEachIndexed { index, label ->
                TextButton(
                    enabled = enabled,
                    onClick = {
                        target.finish()
                        channelIndex = index
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { this.selected = channelIndex == index },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (channelIndex == index) accent else muted,
                        containerColor = if (channelIndex == index) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent),
                    shape = TheiaShapes.control,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) { Text(label, maxLines = 1, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (!precision) {
            Canvas(
                Modifier.fillMaxWidth()
                    .height(plotHeight)
                    .clip(TheiaShapes.panel)
                    .background(surface)
                    .semantics {
                        contentDescription = plotLabel
                        stateDescription = "${curve.points.size}"
                        if (enabled)
                            customActions =
                                listOf(
                                    CustomAccessibilityAction(addLabel) {
                                        target.begin()
                                        val gap =
                                            curve.points.zipWithNext().maxBy { (a, b) -> b.x - a.x }
                                        val x = (gap.first.x + gap.second.x) / 2f
                                        val next = curve.add(x, curve.evaluate(x))
                                        target.change(curves.with(channel, next))
                                        selected =
                                            next.points.indexOfFirst { it.x == x }.coerceAtLeast(0)
                                        target.finish()
                                        true
                                    },
                                    CustomAccessibilityAction(previousLabel) {
                                        selected = (pointIndex - 1).coerceAtLeast(0)
                                        true
                                    },
                                    CustomAccessibilityAction(nextLabel) {
                                        selected = (pointIndex + 1).coerceAtMost(curve.points.lastIndex)
                                        true
                                    },
                                )
                    }
                    .onSizeChanged { plotSize = it }
                    .pointerInteropFilter(requestDisallowInterceptTouchEvent = dragOwnership) { event ->
                        if (!enabled) return@pointerInteropFilter false
                        val inset = 18f * density
                        val w = (plotSize.width - 2 * inset).coerceAtLeast(1f)
                        val h = (plotSize.height - 2 * inset).coerceAtLeast(1f)
                        val point =
                            Offset(
                                ((event.x - inset) / w).coerceIn(0f, 1f),
                                (1f - (event.y - inset) / h).coerceIn(0f, 1f),
                            )
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                down = Offset(event.x, event.y)
                                moved = false
                                virtualDrag = false
                                val base = target.curves[channel]
                                val nearest =
                                    base.points.indices.minBy { i ->
                                        val p = base.points[i]
                                        val dx = inset + p.x * w - event.x
                                        val dy = inset + (1 - p.y) * h - event.y
                                        dx * dx + dy * dy
                                    }
                                val hit =
                                    base.points[nearest].let { p ->
                                        val dx = inset + p.x * w - event.x
                                        val dy = inset + (1 - p.y) * h - event.y
                                        sqrt(dx * dx + dy * dy) <= 28f * density
                                    }
                                pendingTap = if (hit) null else Offset(event.x, event.y)
                                if (hit) {
                                    dragOwnership(true)
                                    dragIndex = nearest
                                    selected = nearest
                                    grabOffset = Offset(base.points[nearest].x - point.x, base.points[nearest].y - point.y)
                                    dragging = base
                                    target.begin()
                                } else {
                                    // Unused tonal handles ride the existing curve. They only become
                                    // control points when used, so opening the editor cannot change pixels.
                                    val anchor = listOf(.25f, .5f, .75f).firstOrNull { x ->
                                        base.points.none { kotlin.math.abs(it.x - x) < .04f } &&
                                            (Offset(inset + x * w, inset + (1f - base.evaluate(x)) * h) -
                                                Offset(event.x, event.y)).getDistance() <= 18f * density
                                    }
                                    if (anchor != null) {
                                        val next = base.add(anchor, base.evaluate(anchor))
                                        if (next != base) {
                                            dragOwnership(true)
                                            virtualDrag = true
                                            pendingTap = null
                                            dragIndex = next.points.indexOfFirst { it.x == anchor }
                                            dragging = next
                                            grabOffset = Offset(anchor - point.x, base.evaluate(anchor) - point.y)
                                            target.begin()
                                        }
                                    }
                                }
                                true
                            }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                moved = moved || (Offset(event.x, event.y) - down).getDistance() > touchSlop
                                dragging?.let { work ->
                                    if (!moved) return@let
                                    val p = point + grabOffset
                                    val next = work.move(dragIndex, p.x, p.y)
                                    dragging = next
                                    selected = dragIndex
                                    target.change(target.curves.with(channel, next))
                                }
                                if (dragging == null && pendingTap?.let { (Offset(event.x, event.y) - it).getDistance() > touchSlop } == true) {
                                    pendingTap = null
                                    dragOwnership(false)
                                    return@pointerInteropFilter false
                                }
                                true
                            }
                            android.view.MotionEvent.ACTION_UP -> {
                                if (dragging != null) {
                                    if (virtualDrag && !moved) {
                                        target.change(target.curves.with(channel, dragging!!))
                                        selected = dragIndex
                                    }
                                    target.finish()
                                }
                                else if (pendingTap != null) {
                                    val base = target.curves[channel]
                                    val next = base.add(point.x, point.y)
                                    if (next != base) {
                                        target.begin()
                                        target.change(target.curves.with(channel, next))
                                        selected = next.points.indices.minBy { kotlin.math.abs(next.points[it].x - point.x) }
                                        target.finish()
                                    }
                                }
                                pendingTap = null
                                dragging = null
                                virtualDrag = false
                                dragOwnership(false)
                                true
                            }
                            android.view.MotionEvent.ACTION_CANCEL,
                            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                                target.finish(cancel = true)
                                pendingTap = null
                                dragging = null
                                virtualDrag = false
                                dragOwnership(false)
                                true
                            }
                            else -> dragging != null
                        }
                    }
            ) {
                val inset = 18.dp.toPx()
                val w = size.width - 2 * inset
                val h = size.height - 2 * inset
                // The histogram already exists for the displayed frame; drawing it needs no new render.
                // 胶片前曲线在曝光域，成片直方图不适用，故不画。
                if (!decode) vm.histogram?.let { counts ->
                    val bins = when (channel) {
                        CurveChannel.RED -> counts.r
                        CurveChannel.GREEN -> counts.g
                        CurveChannel.BLUE -> counts.b
                        else -> IntArray(256) { (counts.r[it] + counts.g[it] + counts.b[it]) / 3 }
                    }
                    val peak = bins.maxOrNull()?.coerceAtLeast(1) ?: 1
                    val distribution = Path().apply {
                        moveTo(inset, inset + h)
                        bins.forEachIndexed { i, count ->
                            lineTo(inset + i * w / 255f, inset + h - h * .85f * sqrt(count.toFloat() / peak))
                        }
                        lineTo(inset + w, inset + h); close()
                    }
                    drawPath(distribution, accent.copy(alpha = .10f))
                }
                for (i in 0..4) {
                    val t = i / 4f
                    drawLine(
                        line.copy(alpha = if (i == 0 || i == 4) .75f else .35f),
                        Offset(inset + w * t, inset),
                        Offset(inset + w * t, inset + h),
                        1.dp.toPx(),
                    )
                    drawLine(
                        line.copy(alpha = if (i == 0 || i == 4) .75f else .35f),
                        Offset(inset, inset + h * t),
                        Offset(inset + w, inset + h * t),
                        1.dp.toPx(),
                    )
                }
                drawLine(
                    muted.copy(alpha = .35f),
                    Offset(inset, inset + h),
                    Offset(inset + w, inset),
                    1.dp.toPx(),
                )
                val path = Path()
                for (i in 0..256) {
                    val x = i / 256f
                    val y = curve.evaluate(x)
                    if (i == 0) path.moveTo(inset + x * w, inset + (1 - y) * h)
                    else path.lineTo(inset + x * w, inset + (1 - y) * h)
                }
                drawPath(path, accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                for (x in listOf(.25f, .5f, .75f)) {
                    if (curve.points.none { kotlin.math.abs(it.x - x) < .04f }) {
                        val at = Offset(inset + x * w, inset + (1f - curve.evaluate(x)) * h)
                        drawCircle(surface, 5.dp.toPx(), at)
                        drawCircle(accent.copy(alpha = .55f), 5.dp.toPx(), at, style = Stroke(1.5.dp.toPx()))
                    }
                }
                curve.points.forEachIndexed { i, p ->
                    val at = Offset(inset + p.x * w, inset + (1 - p.y) * h)
                    if (i == pointIndex) drawCircle(accent.copy(alpha = .2f), 11.dp.toPx(), at)
                    drawCircle(if (i == pointIndex) accent else surface, 6.dp.toPx(), at)
                    drawCircle(accent, 6.dp.toPx(), at, style = Stroke(2.dp.toPx()))
                }
            }
            if (decode) Text("横轴 / 纵轴为曝光档（EV），中心 0 为不变。",
                style = MaterialTheme.typography.labelSmall, color = muted,
                modifier = Modifier.padding(top = 2.dp))
        } else Column(Modifier.height(graphHeight).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = (pointIndex - 1).coerceAtLeast(0) }, enabled = enabled && pointIndex > 0) {
                    Icon(Icons.Rounded.ChevronLeft, previousLabel)
                }
                Text(stringResource(R.string.curve_point, pointIndex + 1, curve.points.size),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { selected = (pointIndex + 1).coerceAtMost(curve.points.lastIndex) }, enabled = enabled && pointIndex < curve.points.lastIndex) {
                    Icon(Icons.Rounded.ChevronRight, nextLabel)
                }
            }
            CurveValueSlider(stringResource(R.string.curve_input), point.x,
                enabled && pointIndex in 1 until curve.points.lastIndex, decode = decode,
                resetValue = pointIndex.toFloat() / curve.points.lastIndex.coerceAtLeast(1),
                onStart = target::begin, onFinish = { target.finish() },
                onCancel = { target.finish(cancel = true) }) {
                target.change(target.curves.with(channel,
                    target.curves[channel].move(pointIndex, it, point.y)))
            }
            CurveValueSlider(stringResource(R.string.curve_output), point.y, enabled, decode = decode,
                resetValue = point.x,
                onStart = target::begin, onFinish = { target.finish() },
                onCancel = { target.finish(cancel = true) }) {
                target.change(target.curves.with(channel,
                    target.curves[channel].move(pointIndex, point.x, it)))
            }
        }
        Text(
            if (decode) "在胶片模拟之前调整场景明暗与色彩，按曝光档计算。" else stringResource(R.string.curve_hint),
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = muted,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(stringResource(R.string.curve_point, pointIndex + 1, curve.points.size),
                    style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1)
                Text(if (decode) "输入 %+.1f EV · 输出 %+.1f EV".format(point.x * 16 - 8, point.y * 16 - 8)
                    else stringResource(R.string.curve_coordinates, (point.x * 255 + .5f).toInt(), (point.y * 255 + .5f).toInt()),
                    style = MaterialTheme.typography.labelMedium, color = accent, maxLines = 1)
            }
            IconButton(enabled = enabled && pointIndex in 1 until curve.points.lastIndex, onClick = {
                target.begin()
                target.change(curves.with(channel, curve.remove(pointIndex)))
                selected = (pointIndex - 1).coerceAtLeast(0)
                target.finish()
            }) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.curve_delete)) }
            IconToggleButton(checked = precision, onCheckedChange = { precision = it }, enabled = enabled) {
                Icon(if (precision) Icons.Rounded.ShowChart else Icons.Rounded.Tune,
                    stringResource(if (precision) R.string.curve_hide_precision else R.string.curve_show_precision))
            }
            Box {
                IconButton(onClick = { resetMenu = true }, enabled = enabled) {
                    Icon(Icons.Rounded.MoreVert, stringResource(R.string.curve_more))
                }
                DropdownMenu(expanded = resetMenu, onDismissRequest = { resetMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.curve_reset_channel)) },
                        enabled = enabled && curve != ToneCurve.IDENTITY, onClick = {
                            resetMenu = false
                            target.begin()
                            target.change(curves.with(channel, ToneCurve.IDENTITY))
                            selected = 0
                            target.finish()
                        })
                    DropdownMenuItem(text = { Text(stringResource(R.string.curve_reset_all)) },
                        enabled = enabled && !curves.isDefault, onClick = {
                            resetMenu = false
                            target.begin()
                            target.change(ToneCurves.IDENTITY)
                            selected = 0
                            target.finish()
                        })
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CurveValueSlider(
    label: String,
    value: Float,
    enabled: Boolean,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    decode: Boolean = false,
    resetValue: Float? = null,
    onChange: (Float) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val cancel by rememberUpdatedState(onCancel)
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is DragInteraction.Cancel) cancel() }
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (decode) "%+.1f EV".format(value * 16 - 8) else "${(value*255+.5f).toInt()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val accent = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
        val trackColor = MaterialTheme.colorScheme.outlineVariant
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = value,
            onValueChange = {
                onStart()
                onChange(it)
            },
            onValueChangeFinished = onFinish,
            interactionSource = interaction,
            valueRange = 0f..1f,
            enabled = enabled,
            thumb = { Box(Modifier.size(16.dp).background(accent, CircleShape)) },
            track = { state ->
                Canvas(Modifier.fillMaxWidth().heightIn(min = 4.dp)) {
                    drawLine(trackColor, Offset(0f, center.y), Offset(size.width, center.y), 3.dp.toPx(), StrokeCap.Round)
                    drawLine(accent, Offset(0f, center.y), Offset(size.width * state.value.coerceIn(0f, 1f), center.y), 3.dp.toPx(), StrokeCap.Round)
                }
            },
            modifier =
                Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                    contentDescription = label
                },
        )
        if (resetValue != null) {
            val changed = kotlin.math.abs(value - resetValue) > 1e-4f
            IconButton(onClick = { onStart(); onChange(resetValue); onFinish() },
                enabled = enabled && changed, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.RestartAlt, "复位$label", Modifier.size(18.dp),
                    tint = if (enabled && changed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f))
            }
        }
        }
    }
}
