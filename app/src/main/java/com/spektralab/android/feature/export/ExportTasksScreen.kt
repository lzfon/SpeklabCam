package com.spektralab.android.feature.export

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.spektralab.android.core.model.ExportArtifact
import com.spektralab.android.data.export.ExportJobStore
import com.spektralab.android.feature.editor.EditorViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExportTasksScreen(vm: EditorViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val controller = vm.exportTasks
    var relink by remember { mutableStateOf<String?>(null) }
    var remove by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = relink; relink = null
        if (uri != null && id != null) controller.relink(id, uri, vm::persistReadPermission)
    }
    val processing = controller.loading || vm.busy || vm.isExporting || vm.isBatchExporting
    BackHandler { controller.close(); onBack() }
    Scaffold(modifier = modifier, topBar = {
        TopAppBar(title = { Text("中断的任务") }, navigationIcon = {
            IconButton(onClick = { controller.close(); onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        }, actions = {
            TextButton(onClick = controller::refresh, enabled = !processing) { Text("刷新") }
        })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                item { Text("这里列出中断或失败的导出记录，可以重新导出、换回原图或移除记录。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (controller.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                controller.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium) } }
                if (vm.isExporting) item {
                    Column {
                        LinearProgressIndicator(Modifier.fillMaxWidth()); Text(vm.renderStage)
                        TextButton(onClick = vm::cancelRender, enabled = !vm.isExportCommitCompleted) { Text("取消导出") }
                    }
                }
                if (controller.entries.isEmpty() && !controller.loading) item {
                    Text("没有中断或失败的任务。", Modifier.padding(vertical = 28.dp))
                }
                items(controller.entries, key = { it.id }) { entry ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(entry.request?.sourceName ?: "无法读取的任务", style = MaterialTheme.typography.titleSmall)
                        Text(when (entry.state) {
                            ExportJobStore.State.SUCCEEDED -> "已完成"
                            ExportJobStore.State.FAILED -> "失败或已中断"
                            ExportJobStore.State.CANCELLED -> "已取消"
                            ExportJobStore.State.QUEUED -> "等待处理"
                            ExportJobStore.State.PUBLISHING -> "正在提交文件"
                            null -> "记录格式无法读取"
                            else -> "处理中"
                        }, color = if (entry.state == ExportJobStore.State.FAILED)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        entry.request?.options?.let { options ->
                            Text(
                                if (entry.request.artifact == ExportArtifact.DIGITAL_INTERMEDIATE)
                                    "DI TIFF · 16 bit · ${if (options.resolution == 0) "原始尺寸" else "${options.resolution} px"}"
                                else "${options.format} · ${options.colorSpace} · " +
                                    if (options.customSize != null) "自定义尺寸"
                                    else if (options.resolution == 0) "原始尺寸"
                                    else "${options.resolution} px",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(entry.updated)), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("尝试次数 ${entry.attempts}", style = MaterialTheme.typography.labelSmall)
                        entry.error?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (entry.request != null && entry.sourceReference == null) {
                            Text("旧版任务：未记录原图位置，需重新关联原图后才能重试。",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (entry.retryable) {
                            TextButton(onClick = { vm.retryExportTask(entry.id) }, enabled = !processing) { Text("重新导出") }
                        }
                        if (entry.request != null && entry.state in listOf(ExportJobStore.State.FAILED, ExportJobStore.State.CANCELLED)) {
                            TextButton(onClick = { relink = entry.id; picker.launch(arrayOf("*/*")) }, enabled = !processing) {
                                Text("换回原图")
                            }
                        }
                        if (entry.state == null || entry.state in listOf(ExportJobStore.State.SUCCEEDED,
                                ExportJobStore.State.FAILED, ExportJobStore.State.CANCELLED)) {
                            TextButton(onClick = { remove = entry.id }, enabled = !processing) { Text("移除记录") }
                        }
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
    remove?.let { id ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("移除记录") },
            text = { Text("只删除这条任务记录，不影响原图和已导出的文件。") },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("取消") } },
            confirmButton = { TextButton(onClick = { remove = null; controller.remove(id) }) { Text("移除记录") } })
    }
}
