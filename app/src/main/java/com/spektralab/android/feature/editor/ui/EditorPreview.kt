package com.spektralab.android.feature.editor.ui

import kotlin.math.sqrt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.android.R
import com.spektralab.android.engine.Histogram
import com.spektralab.android.engine.StockCatalog
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.crop.CropOverlay
import com.spektralab.android.ui.components.BeforeAfterPreview
import com.spektralab.android.ui.components.DarkroomChrome
import com.spektralab.android.ui.components.captureReadouts
import com.spektralab.android.ui.components.rememberSourcePhotoInfo
import com.spektralab.android.ui.theme.TheiaShapes

@Composable
internal fun PhotoMetadataStrip(vm: EditorViewModel) {
    if (!vm.hasPhoto) return
    val identity = rememberSourcePhotoInfo(vm.currentUri,
        vm.photoInfo.firstOrNull { it.first == "文件名" }?.second, vm.currentIsRaw)
    val values = captureReadouts(vm.photoInfo).ifEmpty {
        listOfNotNull(vm.photoInfo.firstOrNull { it.first == "原始尺寸" }?.second)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(horizontal = 12.dp)
            .semantics { contentDescription = "照片参数信息" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            values.forEach { value ->
                Text(
                    value,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Text(if (vm.currentUri == null) "当前照片" else identity.detail,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun EditorStockSelection(vm: EditorViewModel, onBrowseFilms: () -> Unit, onBrowsePapers: () -> Unit) {
    val film = vm.films.firstOrNull { it.id == vm.params.filmId }
    val papers = vm.papers.filter { StockCatalog.canPrint(vm.params.filmId, it.id) }
    val paper = papers.firstOrNull { it.id == vm.params.paperId }
    Row(Modifier.fillMaxWidth().background(DarkroomChrome.navigation).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1.15f).heightIn(min = 64.dp).clickable(onClick = onBrowseFilms)
            .semantics { contentDescription = "胶片与相纸" }.padding(end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("胶片 / ${if (StockCatalog.isMonochrome(vm.params.filmId)) "黑白" else if (StockCatalog.isPositive(vm.params.filmId)) "彩色正片" else "彩色负片"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(2.dp).height(22.dp).background(MaterialTheme.colorScheme.primary))
                Text(film?.name ?: "选择胶片", Modifier.weight(1f).padding(start = 8.dp), fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.size(14.dp))
            }
        }
        Box(Modifier.width(1.dp).height(48.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)))
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onBrowsePapers)
                .semantics { contentDescription = "相纸" }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("相纸", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(paper?.name ?: "选择相纸", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PhotoCanvas(
    vm: EditorViewModel,
    comparing: Boolean,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    showHistogram: Boolean = com.spektralab.android.ui.components.UiPrefs.showHistogram,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    zoomable: Boolean = false,
) {
    val canCompare = vm.sourceBitmap != null && vm.rendered != null && !vm.isCropping
    val canHoldOriginal = canCompare && !comparing
    var holdingOriginal by remember(vm.currentUri, vm.sourceBitmap) { mutableStateOf(false) }
    // 双指缩放只在编辑预览开：pinching 期间不响应「按住看原图」，两个手势不打架。
    var zoom by remember(vm.currentUri) { mutableFloatStateOf(1f) }
    var panX by remember(vm.currentUri) { mutableFloatStateOf(0f) }
    var panY by remember(vm.currentUri) { mutableFloatStateOf(0f) }
    var pinching by remember { mutableStateOf(false) }
    val showingOriginal = canHoldOriginal && holdingOriginal && !pinching
    BoxWithConstraints(
        modifier.fillMaxWidth().background(backgroundColor)
            .semantics {
                contentDescription = "照片预览"
                if (canCompare) stateDescription = when {
                    comparing -> "分割对比"
                    showingOriginal -> "原图"
                    else -> "效果图"
                }
            }
            .pointerInput(vm.currentUri, vm.sourceBitmap, canHoldOriginal) {
                if (canHoldOriginal) detectTapGestures(onPress = {
                    holdingOriginal = true
                    try {
                        tryAwaitRelease()
                    } finally {
                        // Release, cancellation and leaving this preview must all restore the edit.
                        holdingOriginal = false
                    }
                })
            }
            .then(if (zoomable && !vm.isCropping) Modifier.clipToBounds().pointerInput(vm.currentUri) {
                // 两指捏合缩放 + 平移：只改显示变换，不重渲染；缩回 1 倍自动回中。
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var twoDown = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.changes.count { it.pressed } >= 2) {
                            if (!twoDown) { twoDown = true; pinching = true; holdingOriginal = false }
                            val gestureZoom = event.calculateZoom()
                            val panDelta = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val newZoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            if (newZoom <= 1.02f) { zoom = 1f; panX = 0f; panY = 0f } else {
                                val fx = centroid.x - w / 2f
                                val fy = centroid.y - h / 2f
                                panX = (panX + fx * (1f - gestureZoom) + panDelta.x)
                                    .coerceIn(-w * (newZoom - 1f) / 2f, w * (newZoom - 1f) / 2f)
                                panY = (panY + fy * (1f - gestureZoom) + panDelta.y)
                                    .coerceIn(-h * (newZoom - 1f) / 2f, h * (newZoom - 1f) / 2f)
                                zoom = newZoom
                            }
                            event.changes.forEach { if (it.pressed) it.consume() }
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                    if (twoDown) pinching = false
                }
            } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        val source = vm.sourceBitmap
        val rendered = vm.rendered
        val zoomLayer = if (zoomable && !comparing) Modifier.graphicsLayer {
            scaleX = zoom; scaleY = zoom
            translationX = if (zoom > 1f) panX else 0f
            translationY = if (zoom > 1f) panY else 0f
        } else Modifier
        if (vm.isCropping && source != null) {
            CropOverlay(
                bitmap = source,
                cropX = vm.params.cropX, cropY = vm.params.cropY,
                cropW = vm.params.cropW, cropH = vm.params.cropH,
                onCropChange = { x, y, w, h ->
                    vm.params.cropX = x; vm.params.cropY = y
                    vm.params.cropW = w; vm.params.cropH = h
                    vm.cropAspect = "自定义"
                },
                modifier = Modifier.fillMaxSize()
            )
        } else if (comparing && canCompare) {
            BeforeAfterPreview(source!!, rendered!!, Modifier.fillMaxSize())
        } else if (showingOriginal) {
            Image(source!!.asImageBitmap(), null, Modifier.fillMaxSize().then(zoomLayer), contentScale = ContentScale.Fit)
        } else if (rendered != null) {
            Image(rendered.asImageBitmap(), null, Modifier.fillMaxSize().then(zoomLayer), contentScale = ContentScale.Fit)
        } else if (source != null) {
            Image(source.asImageBitmap(), null, Modifier.fillMaxSize().then(zoomLayer), contentScale = ContentScale.Fit)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.AddPhotoAlternate, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
                Text("导入照片", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Button(onClick = onImport) { Text("选择照片") }
            }
        }
        val displayed = rendered ?: source
        if (showHistogram && !showingOriginal && (!comparing || !canCompare) && displayed != null) {
            val aspect = displayed.width.toFloat() / displayed.height
            val photoWidth = minOf(maxWidth, maxHeight * aspect)
            val photoHeight = photoWidth / aspect
            if (photoWidth >= 144.dp && photoHeight >= 120.dp) {
                vm.histogram?.let { histogram ->
                    HistogramView(histogram, Modifier.align(Alignment.TopStart)
                        .padding(start = (maxWidth - photoWidth) / 2 + 12.dp,
                            top = (maxHeight - photoHeight) / 2 + 12.dp)
                        .size(112.dp, 46.dp)
                        .background(Color.Black.copy(alpha = .5f), TheiaShapes.badge)
                        .padding(4.dp))
                }
            }
        }

        // 「查看原图」角标已删：按住画面即原图、松手即恢复，动作本身不变。

        // 右下角「下张胶片」：同「查看原图」风格，点一下切到工作台里的下一张。
        if (!vm.isCropping && vm.currentIndex < vm.photos.lastIndex && vm.photos.isNotEmpty()) {
            Text(
                "下张胶片",
                Modifier.align(Alignment.BottomEnd).padding(10.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .55f), TheiaShapes.badge)
                    .clickable(enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting) { vm.nextPhoto() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .semantics { contentDescription = "下张胶片" },
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 导出入口移到照片展示栏右端，画面内不再放按钮。

    }
}

@Composable
private fun HistogramView(histogram: Histogram, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        // 有些渲染分支统计不到就交回空数组，别拿它当 256 个桶来读。
        if (histogram.r.size < 256 || histogram.g.size < 256 || histogram.b.size < 256) return@Canvas
        val maxCount = maxOf(
            histogram.r.maxOrNull() ?: 1,
            histogram.g.maxOrNull() ?: 1,
            histogram.b.maxOrNull() ?: 1,
        ).toFloat().coerceAtLeast(1f)
        val binWidth = size.width / 256f
        fun drawChannel(bins: IntArray, color: Color) {
            val path = Path()
            path.moveTo(0f, size.height)
            for (i in 0 until 256) {
                // 开根号再映射：一个主峰动辄占走两三成像素，线性刻度会把其余 255 个桶全压成
                // 贴底的一条线，看着跟没画一样（之前就是这样）。Lightroom 也不是线性刻度。
                val h = sqrt((bins[i] / maxCount).coerceIn(0f, 1f)) * size.height
                path.lineTo(i * binWidth, size.height - h)
            }
            path.lineTo(size.width, size.height)
            path.close()
            drawPath(path, color.copy(alpha = 0.4f))
        }
        drawChannel(histogram.r, Color.Red)
        drawChannel(histogram.g, Color.Green)
        drawChannel(histogram.b, Color.Blue)
    }
}
