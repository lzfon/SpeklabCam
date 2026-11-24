package com.spektralab.android.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.ui.EditToolsSheet
import com.spektralab.android.feature.smartdevelop.SmartDevelopEntry
import com.spektralab.android.feature.smartdevelop.SmartDevelopSheet
import com.spektralab.android.ui.components.captureClock
import com.spektralab.android.ui.components.captureReadouts
import com.spektralab.android.ui.components.rememberSourcePhotoInfo
import com.spektralab.android.ui.theme.TheiaShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    vm: EditorViewModel,
    onImportPhotos: () -> Unit,
    onAddPhotos: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenViewingStand: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSavedPhotos: () -> Unit = {},
    onOpenTasks: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    var moreExpanded by remember { mutableStateOf(false) }
    val preview = vm.rendered ?: vm.sourceBitmap

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 8.dp)) {
                    // 标题左对齐、去掉左侧绿竖线，集中用主题绿。
                    Text("SpekLab相机", Modifier.align(Alignment.CenterStart).padding(start = 12.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                    Box(Modifier.align(Alignment.CenterEnd)) {
                        IconButton(onClick = { moreExpanded = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Settings, "更多操作", modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("中断的任务") },
                                onClick = { moreExpanded = false; onOpenTasks() },
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
                                onClick = { moreExpanded = false; onOpenSettings() },
                            )
                            DropdownMenuItem(
                                text = { Text("关于") },
                                onClick = { moreExpanded = false; onOpenAbout() },
                            )
                        }
                    }
                }
            }
        },
    ) { insets ->
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(insets),
        ) {
            val expanded = maxWidth >= 840.dp || (maxWidth >= 600.dp && maxWidth > maxHeight)
            if (expanded) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    HomeLead(
                        vm = vm,
                        preview = preview,
                        onImportPhotos = onImportPhotos,
                        onOpenCamera = onOpenCamera,
                        onOpenEditor = onOpenEditor,
                        onOpenViewingStand = onOpenViewingStand,
                        onOpenSavedPhotos = onOpenSavedPhotos,
                        modifier = Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    )
                    Column(
                        Modifier.weight(0.85f).fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Spacer(Modifier.height(1.dp))
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    HomeLead(
                        vm = vm,
                        preview = preview,
                        onImportPhotos = onImportPhotos,
                        onOpenCamera = onOpenCamera,
                        onOpenEditor = onOpenEditor,
                        onOpenViewingStand = onOpenViewingStand,
                        onOpenSavedPhotos = onOpenSavedPhotos,
                        modifier = Modifier.fillMaxWidth(),
                    )

                }
            }
        }
    }
}

@Composable
private fun HomeLead(
    vm: EditorViewModel,
    preview: android.graphics.Bitmap?,
    onImportPhotos: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenViewingStand: () -> Unit,
    onOpenSavedPhotos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var smartVisible by rememberSaveable { mutableStateOf(false) }
    var recipesVisible by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 标题下方一行小字：没照片谈“捕捉”，有照片谈“观看”。
        Text(
            if (vm.hasPhoto) "一起来看光的影子。" else "一起捕捉光的踪迹。",
            modifier = Modifier.fillMaxWidth(),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Start,
        )

        if (vm.hasPhoto && preview != null) {
            val identity = rememberSourcePhotoInfo(vm.currentUri,
                vm.photoInfo.firstOrNull { it.first == "文件名" }?.second, vm.currentIsRaw)
            // 预览框固定为 1.4 比例，横竖版都占同一个框；竖版按框高度缩放居中，左右留黑边。
            Box(
                Modifier.fillMaxWidth()
                    .aspectRatio(1.4f)
                    .clip(TheiaShapes.panel)
                    .background(androidx.compose.ui.graphics.Color.Black)
                    .clickable(role = Role.Button, onClick = onOpenEditor),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "继续显影",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val film = vm.films.firstOrNull { it.id == vm.params.filmId }?.name ?: "胶片"
                val paper = vm.papers.firstOrNull { it.id == vm.params.paperId }?.name ?: "相纸"
                Text("$film / $paper", Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                captureClock(vm.photoInfo)?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
              }
              Column(Modifier.fillMaxWidth().semantics { contentDescription = "首页照片参数" },
                  verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                  Text(identity.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                  Spacer(Modifier.weight(1f))
                  val dimensions = vm.photoInfo.firstOrNull { it.first == "原始尺寸" }?.second
                      ?: "预览 ${preview.width} × ${preview.height}"
                  Text(dimensions, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (captureReadouts(vm.photoInfo).isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  captureReadouts(vm.photoInfo).forEach { value ->
                      Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                }
              }
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(min = 224.dp, max = 300.dp)
                    .clickable(enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting, onClick = onOpenCamera),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = TheiaShapes.control,
            ) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Rounded.CameraAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("打开系统相机拍摄", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "拍完回到这里，新照片按当前配方直接冲印。",
                        Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SmartDevelopEntry(vm,onOpen={smartVisible=true})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onImportPhotos,
            enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting,
            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            shape = TheiaShapes.control,
        ) {
            Icon(
                Icons.Rounded.AddPhotoAlternate,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text("导入", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        FilledTonalButton(
            onClick = onOpenCamera,
            enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting,
            modifier = Modifier.weight(1f).heightIn(min = 52.dp).semantics { contentDescription = "打开系统相机拍摄" },
            shape = TheiaShapes.control,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        ) {
            Icon(
                Icons.Rounded.CameraAlt,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("拍摄", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        }
        // 照片图库与观片台同一行：图库在左、观片台在右，两者同一套样式。
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = onOpenSavedPhotos,
                enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp).semantics { contentDescription = "打开照片图库" },
                shape = TheiaShapes.control,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) {
                Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(androidx.compose.ui.res.stringResource(com.spektralab.android.R.string.photos_title),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            FilledTonalButton(
                onClick = onOpenViewingStand,
                enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp).semantics { contentDescription = "打开观片台" },
                shape = TheiaShapes.control,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) {
                Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("观片台", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if(smartVisible)SmartDevelopSheet(vm,onDismiss={smartVisible=false},onOpenRecipes={smartVisible=false;recipesVisible=true})
    if(recipesVisible)EditToolsSheet(vm,presets=true,onDismiss={recipesVisible=false})
}
