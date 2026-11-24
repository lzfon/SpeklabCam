package com.spektralab.android.feature.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.spektralab.android.R
import com.spektralab.android.core.color.NeutralWhiteBalance
import com.spektralab.android.feature.editor.NeutralPickController
import com.spektralab.android.ui.theme.TheiaShapes

/** A focused stage preview prevents sampling a graded screenshot or the wrong geometry. */
@Composable
internal fun NeutralPickView(controller: NeutralPickController) {
    val state = controller.state ?: return
    val decode = state.target == NeutralWhiteBalance.Target.DECODE
    // The parent editor may have already consumed the window's navigation inset.
    val navigationSpace = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().coerceAtLeast(24.dp)
    Dialog(onDismissRequest = controller::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(bottom = navigationSpace)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = controller::close) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "取消")
                    }
                    Text((if (decode) "拾取相机白平衡" else "拾取相纸白平衡"),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                Text((if (decode) "以 RAW 原片取样（未显影）" else "以当前成片取样"),
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
                StageImage(state, controller::pick, Modifier.weight(1f).fillMaxWidth())
                Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                    .heightIn(max = 224.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("在画面上点一处应为中性灰的位置（白墙、灰卡），按该点算色温与色调。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val balance = state.balance
                    if (balance != null) Text(
                        (if (decode) "相机白平衡 " else "相纸白平衡 ") + balance.temperature + " K · 色调 " + balance.tint,
                        style = MaterialTheme.typography.bodyMedium)
                    else if (!state.working && state.note == null) Text("还没选点", style = MaterialTheme.typography.bodyMedium)
                    if (balance?.limited == true) Text("受原片限制，只能调整到这个程度", style = MaterialTheme.typography.bodySmall)
                    state.note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                Row(Modifier.widthIn(max = 600.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                    .padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { controller.pick(.5, .5) }, enabled = !state.working && state.image != null,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = TheiaShapes.control) {
                        Text("取画面中心")
                    }
                    Button(onClick = controller::apply, enabled = !state.working && state.balance != null,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = TheiaShapes.control) {
                        Text("应用")
                    }
                }
            }
        }
    }
}

@Composable
private fun StageImage(state: NeutralPickController.State, onPick: (Double, Double) -> Unit, modifier: Modifier) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val image = state.image
    val latestPick by rememberUpdatedState(onPick)
    val ink = MaterialTheme.colorScheme.primary
    Box(modifier.clipToBounds().onSizeChanged { viewSize = it }, contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image.asImageBitmap(), "取样画面", Modifier.fillMaxSize()
                .pointerInput(image, state.working, viewSize) {
                    if (!state.working) detectTapGestures { offset ->
                        NeutralWhiteBalance.imagePoint(viewSize.width.toFloat(), viewSize.height.toFloat(), image.width, image.height,
                            offset.x, offset.y)?.let { latestPick(it.first, it.second) }
                    }
                }, contentScale = ContentScale.Fit)
            state.point?.let { point -> Canvas(Modifier.fillMaxSize()) {
                val scale = minOf(size.width / image.width.toFloat(), size.height / image.height.toFloat())
                val width = image.width * scale; val height = image.height * scale
                val center = Offset(((size.width - width) / 2 + point.first * width).toFloat(),
                    ((size.height - height) / 2 + point.second * height).toFloat())
                drawCircle(Color.Black, 13.dp.toPx(), center, style = Stroke(4.dp.toPx()))
                drawCircle(ink, 13.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                drawLine(ink, center - Offset(19.dp.toPx(), 0f), center + Offset(19.dp.toPx(), 0f), 1.dp.toPx())
                drawLine(ink, center - Offset(0f, 19.dp.toPx()), center + Offset(0f, 19.dp.toPx()), 1.dp.toPx())
            } }
        }
        if (state.working) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(28.dp))
            Text("正在读取原片…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
