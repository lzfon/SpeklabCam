package com.spektralab.android.feature.export

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.spektralab.android.core.model.ExportNaming
import com.spektralab.android.core.model.ExportRecipe
import com.spektralab.android.core.model.ExportSize
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.components.DarkroomChoiceStrip
import com.spektralab.android.ui.components.SliderRow
import com.spektralab.android.ui.theme.TheiaShapes
import kotlin.math.roundToInt

/** Export settings, progress and pinned action share the original ViewModel export paths. */
@Composable
internal fun ExportScreen(vm: EditorViewModel, modifier: Modifier = Modifier, onCopySettings: () -> Unit = vm::syncSettingsToOtherPhotos, onSaveRecipe: () -> Unit) {
    val context = LocalContext.current
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.exportKeepLocation = granted
    }
    val dirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setExportDir(uri)
    }
    var batch by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var customSizeDialog by rememberSaveable { mutableStateOf(false) }
    var namingDialog by rememberSaveable { mutableStateOf(false) }
    var recipeManager by rememberSaveable { mutableStateOf(false) }
    // 导出页分栏折叠状态：四栏默认为展开，收起后本次会话内保持。
    var formatOpen by rememberSaveable { mutableStateOf(true) }
    var metadataOpen by rememberSaveable { mutableStateOf(true) }
    var recipeOpen by rememberSaveable { mutableStateOf(true) }
    val processing = vm.busy || vm.isExporting || vm.isBatchExporting
    val exporting = vm.isExporting || vm.isBatchExporting
    val selectedCount = vm.selectedBatchExportPhotos.size
    val singleLabel = "单张导出"
    val batchLabel = "批量导出 · ${vm.photos.size}"
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).widthIn(max = 600.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            ExportPhotoIdentity(vm)
            DarkroomChoiceStrip(listOf(singleLabel, batchLabel), if (batch) batchLabel else singleLabel, !processing) {
                batch = it == batchLabel
            }
            if (batch) BatchExportSelection(vm, enabled = !processing)
            Spacer(Modifier.height(12.dp))
            ExportSectionRow("文件格式", exportFormatSummary(vm), formatOpen, !processing) { formatOpen = !formatOpen }
            // 四个格式选项按用户要求不折进折叠区，任何时候都直接可点。
            DarkroomChoiceStrip(if (vm.heifAvailable) listOf("JPEG", "PNG", "TIFF", "HEIF") else listOf("JPEG", "PNG", "TIFF"),
                vm.exportFormat, !processing) {
                vm.exportFormat = it
                if (it == "HEIF") vm.exportColorSpace = "sRGB"
            }
            if (!vm.heifAvailable) Text(
                if (vm.heifChecking) "正在检查本机 HEIF 编码能力…" else "本机不支持 HEIF 导出，已隐藏该格式",
                fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp))
            if (formatOpen) {
                if (vm.exportFormat == "HEIF") Text("HEIF 仅支持 sRGB 8 bit",
                    fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
                ExportSettingRow("分辨率",
                    exportSizeSummary(vm.exportRes, vm.exportCustomSize),
                    listOf("2048", "2560", "3840", "原始", "自定义尺寸"), !processing,
                    optionLabel = { when (it) {
                        "原始" -> "原始尺寸"
                        "自定义尺寸" -> "自定义尺寸…"
                        else -> "长边 $it px"
                    } }) {
                    if (it == "自定义尺寸") customSizeDialog = true
                    else { vm.exportCustomSize = null; vm.exportRes = it.toIntOrNull() ?: 0 }
                }
                ExportSettingRow("色彩空间", vm.exportColorSpace,
                    if (vm.exportFormat == "HEIF") emptyList()
                    else com.spektralab.android.core.color.OutputColorSpaces.names, !processing) {
                    vm.exportColorSpace = it
                }
                Text("sRGB 兼容最广；Display P3、Adobe RGB (1998)、ProPhoto RGB 按对应 ICC 写进文件，看图软件需支持色彩管理。",
                    fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                if (vm.exportFormat in listOf("JPEG", "HEIF")) {
                    SliderRow("${vm.exportFormat} 质量", vm.jpegQuality.toFloat(), 50f..100f, "%.0f%%", compact = true,
                        resetValue = 100f, enabled = !processing) { vm.jpegQuality = it.roundToInt() }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f), thickness = .5.dp)
                }
                if (vm.exportFormat == "TIFF") {
                    com.spektralab.android.ui.components.ToggleRow("Deflate 无损压缩", vm.exportTiffDeflate, enabled = !processing) {
                        vm.exportTiffDeflate = it
                    }
                    Text("开启后 16 bit 数据用 Deflate 压缩，体积更小，仍是无损。",
                        fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                }
            }
            ExportSectionRow("保留信息",
                listOfNotNull(
                    if (vm.exportKeepCapture) "拍摄" else null,
                    if (vm.exportKeepLocation) "位置" else null,
                ).joinToString(" · ").ifBlank { "都不保留" }, metadataOpen, !processing) { metadataOpen = !metadataOpen }
            if (metadataOpen) {
                ExportMetadataOption("保留拍摄信息", "相机、镜头、拍摄时间及曝光参数", vm.exportKeepCapture, !processing) { vm.exportKeepCapture = it }
                ExportMetadataOption("保留位置信息", "仅复制原片已有、可读取的 GPS 信息", vm.exportKeepLocation, !processing) { keep ->
                    if (keep && Build.VERSION.SDK_INT >= 29 && vm.currentUri?.authority == "media" &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                    } else vm.exportKeepLocation = keep
                }
            }
            val currentRecipe = vm.recipes.firstOrNull { it.format == vm.exportFormat && it.res == vm.exportRes && it.colorSpace == vm.exportColorSpace && it.jpegQuality == vm.jpegQuality && it.keepCapture == vm.exportKeepCapture && it.keepLocation == vm.exportKeepLocation && it.tiffDeflate == vm.exportTiffDeflate && it.customSize == vm.exportCustomSize }
            ExportSectionRow("导出配方", currentRecipe?.name ?: "自定义", recipeOpen, !processing) { recipeOpen = !recipeOpen }
            if (recipeOpen) {
                ExportSettingRow("当前配方", currentRecipe?.name ?: "自定义", vm.recipes.map { it.name }, !processing) { name ->
                    vm.recipes.firstOrNull { it.name == name }?.let { vm.applyRecipe(it) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onSaveRecipe,
                        enabled = !processing,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = TheiaShapes.control,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Rounded.SaveAlt, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("保存配方", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FilledTonalButton(
                        onClick = onCopySettings,
                        enabled = vm.photos.size > 1 && vm.currentUri != null && !processing,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = TheiaShapes.control,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Rounded.Sync, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(androidx.compose.ui.res.stringResource(com.spektralab.android.R.string.edit_copy_title), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(androidx.compose.ui.res.stringResource(com.spektralab.android.R.string.edit_copy_short_hint), fontSize = 12.sp, lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = { recipeManager = true }, enabled = !processing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                    Text("管理配方（重命名 / 删除）", fontSize = 12.sp)
                }
            }

            ExportSectionRow("高级设置", "位深 · 文件命名 · 保存位置", advanced, !processing) { advanced = !advanced }
            if (advanced) {
                ExportInfoRow("位深", if (vm.exportFormat == "TIFF") "RGB · 16 bit" else "RGB · 8 bit")
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(enabled = !processing, role = Role.Button, onClick = { namingDialog = true })
                    .semantics { contentDescription = "文件命名" }
                    .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("文件命名", modifier = Modifier.padding(end = 16.dp), fontSize = 12.sp, lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(vm.exportNameExample(), modifier = Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End)
                    Icon(Icons.Rounded.ChevronRight, null, modifier = Modifier.padding(start = 6.dp).size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ExportSettingDivider()
                val dirName = vm.exportDirUri?.let { runCatching { DocumentFile.fromTreeUri(context, it)?.name }.getOrNull() }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(enabled = !processing, role = Role.Button, onClick = { dirLauncher.launch(null) })
                    .semantics { contentDescription = "选择保存位置" }
                    .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("保存位置", modifier = Modifier.padding(end = 16.dp), fontSize = 12.sp, lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(dirName ?: "相册 / Pictures/spektralab", modifier = Modifier.weight(1f), fontSize = 12.sp,
                        lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                    Icon(Icons.Rounded.ChevronRight, null, modifier = Modifier.padding(start = 6.dp).size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ExportSettingDivider()
                if (vm.exportDirUri != null) {
                    TextButton(onClick = { vm.setExportDir(null) }, enabled = !processing) {
                        Text("恢复系统相册", fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (batch && selectedCount == 0) Text("请选择至少一张照片", fontSize = 12.sp, lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            Spacer(Modifier.height(8.dp))
        }
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (exporting) ExportInlineProgress(vm)
            Button(onClick = { if (batch) vm.batchExport() else vm.export() },
                enabled = !processing && (if (batch) selectedCount > 0 else vm.hasPhoto),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = TheiaShapes.control) {
                Icon(Icons.Rounded.SaveAlt, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (batch) "导出所选照片 ($selectedCount 张)" else "导出当前照片", fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (recipeManager) {
        RecipeManagerDialog(vm, onDismiss = { recipeManager = false })
    }

    if (customSizeDialog) {
        CustomSizeDialog(vm.exportCustomSize, onDismiss = { customSizeDialog = false }) {
            vm.exportCustomSize = it
            vm.exportRes = 0
            customSizeDialog = false
        }
    }

    if (namingDialog) {
        NamingDialog(vm.exportNaming, onDismiss = { namingDialog = false }, onApply = {
            vm.exportNaming = it
            namingDialog = false
        })
    }
}

@Composable
private fun RecipeManagerDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf<ExportRecipe?>(null) }
    var deleting by remember { mutableStateOf<ExportRecipe?>(null) }
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("导出配方") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (vm.recipes.isEmpty()) Text("还没有任何配方，先在导出页保存一条。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            vm.recipes.forEach { recipe ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(recipe.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text("${recipe.format} · ${exportSizeSummary(recipe.res, recipe.customSize)} · ${recipe.colorSpace}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { renaming = recipe; name = recipe.name },
                        modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Edit, "重命名", Modifier.size(18.dp))
                    }
                    IconButton(onClick = { deleting = recipe }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.DeleteOutline, "删除", Modifier.size(18.dp))
                    }
                }
            }
        }
    }, confirmButton = {
        TextButton(onClick = onDismiss) { Text("关闭") }
    })

    renaming?.let { target ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("重命名配方") }, text = {
            OutlinedTextField(name, { name = it.take(60) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), label = { Text("配方名") })
        }, confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                if (vm.renameRecipe(target, name)) renaming = null
            }) { Text("确定") }
        }, dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } })
    }

    deleting?.let { target ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除配方") },
            text = { Text("确定删除「${target.name}」？只删这条配方，导出设置和照片都不受影响。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteRecipe(target); deleting = null }) { Text("删除") }
            }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@Composable
private fun exportSizeSummary(resolution: Int, custom: ExportSize?): String {
    if (custom == null) return if (resolution == 0) "原始尺寸" else "长边 $resolution px"
    return when (custom.kind) {
        ExportSize.Kind.LONG_EDGE -> "长边 ${custom.value.toInt()} px"
        ExportSize.Kind.SHORT_EDGE -> "短边 ${custom.value.toInt()} px"
        ExportSize.Kind.MEGAPIXELS -> "${java.math.BigDecimal.valueOf(custom.value).stripTrailingZeros().toPlainString()} MP"
    }
}

@Composable
private fun CustomSizeDialog(current: ExportSize?, onDismiss: () -> Unit, onApply: (ExportSize) -> Unit) {
    var kindName by rememberSaveable { mutableStateOf((current?.kind ?: ExportSize.Kind.LONG_EDGE).name) }
    var value by rememberSaveable { mutableStateOf(current?.value?.let {
        java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() } ?: "2048") }
    var touched by rememberSaveable { mutableStateOf(false) }
    val kind = ExportSize.Kind.valueOf(kindName)
    val parsed = value.replace(',', '.').toDoubleOrNull()?.let { runCatching { ExportSize(kind, it) }.getOrNull() }
    val labels = listOf("长边", "短边", "像素总数")
    AlertDialog(onDismissRequest = onDismiss, title = { Text("自定义尺寸") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DarkroomChoiceStrip(labels, labels[kind.ordinal]) {
                    val next = ExportSize.Kind.entries[labels.indexOf(it)]
                    if (kind != next) { kindName = next.name; value = if (next == ExportSize.Kind.MEGAPIXELS) "12" else "2048"; touched = false }
                }
                OutlinedTextField(value, { value = it.take(16); touched = true }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (kind == ExportSize.Kind.MEGAPIXELS) "百万像素" else "像素") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = if (kind == ExportSize.Kind.MEGAPIXELS)
                            androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Number),
                    isError = touched && parsed == null,
                    supportingText = {
                        if (touched && parsed == null) Text(if (kind == ExportSize.Kind.MEGAPIXELS)
                            "0.01–200 之间" else "1–65535 的整数")
                    })
                Text("导出成片按此限制缩放，不会放大原图。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onApply) }, enabled = parsed != null) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun NamingDialog(current: ExportNaming, onDismiss: () -> Unit, onApply: (ExportNaming) -> Unit) {
    val tokenOptions = listOf(
        ExportNaming.Token.ORIGINAL to "原文件名",
        ExportNaming.Token.FILM to "胶片",
        ExportNaming.Token.PAPER to "相纸",
        ExportNaming.Token.DATE to "日期",
    )
    val separators = listOf("_" to "下划线 _", "-" to "短横线 -", " " to "空格")
    var selectedTokens by rememberSaveable { mutableStateOf(current.tokens.map { it.name }) }
    var separator by rememberSaveable { mutableStateOf(current.separator) }
    fun toggled() = ExportNaming(selectedTokens.map { ExportNaming.Token.valueOf(it) }, separator)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("文件命名") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("选择要写进文件名的内容，按选择的先后顺序拼接：", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                tokenOptions.forEach { (token, label) ->
                    val selected = token.name in selectedTokens
                    Row(Modifier.fillMaxWidth()
                        .toggleable(value = selected, role = Role.Checkbox) { on ->
                            selectedTokens = if (on) selectedTokens + token.name
                            else selectedTokens - token.name
                        }
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = selected, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("分隔符", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DarkroomChoiceStrip(separators.map { it.second }, separators.firstOrNull { it.first == separator }?.second.orEmpty()) {
                    separator = separators.first { s -> s.second == it }.first
                }
                TextButton(onClick = {
                    selectedTokens = emptyList(); separator = "_"
                }) { Text("恢复默认（原文件名_spektralab）", fontSize = 12.sp) }
                Text("默认不勾选时，沿用「原文件名_spektralab」。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onApply(toggled()) }) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ExportInfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.padding(end = 16.dp), fontSize = 12.sp, lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurface)
        Text(value, modifier = Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    ExportSettingDivider()
}

@Composable
private fun ExportPhotoIdentity(vm: EditorViewModel) {
    val fileName = vm.photoInfo.firstOrNull { it.first == "文件名" }?.second ?: vm.currentUri?.lastPathSegment ?: "当前照片"
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        (vm.rendered ?: vm.sourceBitmap)?.let {
            Image(it.asImageBitmap(), "导出照片预览", Modifier.size(56.dp, 64.dp).clip(TheiaShapes.thumbnail), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(fileName.substringBeforeLast('.'), fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$fileName · ${if (vm.exportRes == 0) "原始尺寸" else "${vm.exportRes} px"}",
                fontSize = 12.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("当前会话 · 共 ${vm.photos.size.coerceAtLeast(1)} 张", fontSize = 12.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ExportSettingRow(label: String, value: String, options: List<String>, enabled: Boolean,
    optionLabel: (String) -> String = { it }, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(enabled = enabled && options.isNotEmpty(), role = Role.Button, onClick = { expanded = true })
            .semantics(mergeDescendants = true) { contentDescription = label; stateDescription = value },
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(end = 16.dp))
            Text(value, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
            if (options.isNotEmpty()) Icon(Icons.Rounded.ChevronRight, null, modifier = Modifier.padding(start = 6.dp).size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { onSelect(option); expanded = false }) }
        }
    }
    ExportSettingDivider()
}

@Composable
private fun ExportSettingDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f), thickness = .5.dp)
}

/** Two-line settings share the same label scale and leading edge as the single-line rows. */
/** 分栏标题：主题绿的箭头 + 折叠时右侧显示当前值摘要。 */
@Composable
private fun ExportSectionRow(title: String, summary: String, expanded: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
        .clickable(enabled = enabled, role = Role.Switch, onClick = onToggle)
        .semantics(mergeDescendants = true) {
            contentDescription = title
            stateDescription = if (expanded) "已展开" else "已收起"
        }
        .padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.padding(end = 12.dp), fontSize = 12.sp, lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Text(summary, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null,
            modifier = Modifier.padding(start = 8.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f), thickness = .5.dp)
}

@Composable
private fun exportFormatSummary(vm: EditorViewModel): String =
    "${vm.exportFormat} · ${exportSizeSummary(vm.exportRes, vm.exportCustomSize)} · ${vm.exportColorSpace}"

@Composable
private fun ExportOptionText(label: String, detail: String, modifier: Modifier = Modifier,
    labelColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 12.sp, lineHeight = 16.sp, color = labelColor, fontWeight = FontWeight.SemiBold)
        Text(detail, fontSize = 12.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExportInlineProgress(vm: EditorViewModel) {
    val taskLabel = when {
        vm.isBatchExporting -> "批量导出中"
        vm.renderStage.contains("DI TIFF") -> "DI TIFF 导出中"
        vm.renderStage.contains("软打样") -> "软打样中"
        vm.renderStage.contains("高清预览") -> "高清预览中"
        else -> "导出中"
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = TheiaShapes.control) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(taskLabel, fontSize = 12.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(vm.renderStage.ifBlank { "正在保存照片" }, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = vm::cancelRender,
                    enabled = vm.renderStage != "正在取消…" && (vm.renderProgress ?: 0f) < 100f &&
                        !vm.isExportCommitCompleted) {
                    Text(if (vm.isExporting && !vm.isBatchExporting) "取消处理" else "取消导出", fontSize = 12.sp)
                }
            }
            val progress = vm.renderProgress
            if (progress != null) ExportProgressBar(progress / 100f, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.outlineVariant)
            else LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
            Row {
                Text("保持应用开启", Modifier.weight(1f), fontSize = 12.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (progress != null) Text("${progress.roundToInt()}%", fontSize = 12.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ExportMetadataOption(label: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .semantics(mergeDescendants = true) { contentDescription = label }
        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ExportOptionText(label, detail, Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
    ExportSettingDivider()
}
