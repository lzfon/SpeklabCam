package com.spektralab.android.feature.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spektralab.android.core.model.AppSettings
import com.spektralab.android.data.cache.ExportRenderCache
import com.spektralab.android.data.cache.RenderDiskCache
import com.spektralab.android.data.diagnostics.DiagnosticReport
import com.spektralab.android.data.local.AppSettingsStore
import com.spektralab.android.ui.components.SliderRow
import com.spektralab.android.ui.components.ToggleRow
import com.spektralab.android.ui.components.UiPrefs
import kotlinx.coroutines.*

// 首页显示方式的两个取值，与 UiPrefs.homeViewMode 对齐。
private const val HOME_PREVIEW_MODE = "preview"
private const val HOME_GRID_MODE = "grid"

/**
 * 全局设置（预览质量 / 界面与工作台 / 缓存预算 / 诊断）：持久化 + 立即生效缓存预算。
 * 「材料」（默认胶片 / 默认相纸）已按第三轮要求整体撤下，新照片用什么胶片完全交给智能显影按画面挑。
 */
internal class SettingsController(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val store = AppSettingsStore.get(app)
    var value by mutableStateOf(AppSettings()); private set
    var busy by mutableStateOf(true); private set
    var message by mutableStateOf<String?>(null); private set
    var storage by mutableStateOf<RenderDiskCache.Snapshot?>(null); private set
    private var job: Job? = null

    init { refresh(initial = true) }

    fun dismissMessage() { message = null }

    fun refresh(initial: Boolean = false) {
        if (busy && !initial) return
        run {
            val loaded = withContext(Dispatchers.IO) {
                val read = store.read()
                val cache = ExportRenderCache.get(app)
                cache.setBudget(read.value.renderCacheBytes)
                read to cache.storage()
            }
            value = loaded.first.value
            storage = loaded.second
        }
    }

    fun update(next: AppSettings) {
        if (busy || next == value) return
        run {
            withContext(Dispatchers.IO) { store.save(next) }
            value = next
            try {
                storage = withContext(Dispatchers.IO) {
                    ExportRenderCache.get(app).setBudget(next.renderCacheBytes)
                    ExportRenderCache.get(app).storage()
                }
                message = "已保存"
            } catch (c: CancellationException) { throw c }
            catch (_: Exception) { message = "已保存（缓存调整稍后生效）" }
        }
    }

    fun clearCache() {
        if (busy) return
        run {
            val result = withContext(Dispatchers.IO) {
                val task = currentCoroutineContext()
                ExportRenderCache.get(app).clearDisposable { task.ensureActive() }
            }
            storage = result.retained
            message = if (result.failedFiles == 0) "缓存已清空" else "缓存已部分清空"
        }
    }

    // 生成诊断报告 zip 并写入用户刚指定的位置。只含版本、设备、设置与缓存计数，不含任何照片与路径。
    fun exportDiagnostics(uri: android.net.Uri?, workspace: DiagnosticReport.Workspace) {
        if (uri == null) return
        val task = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val coroutine = currentCoroutineContext()
                    val cache = ExportRenderCache.get(app)
                    val bytes = DiagnosticReport.create(app, value, cache.storage(), cache.stats, workspace) { coroutine.ensureActive() }
                    coroutine.ensureActive()
                    val stream = requireNotNull(app.contentResolver.openOutputStream(uri, "wt"))
                    stream.use {
                        for (offset in bytes.indices step 8192) {
                            coroutine.ensureActive()
                            it.write(bytes, offset, minOf(8192, bytes.size - offset))
                        }
                        it.flush()
                    }
                }
                message = "诊断报告已保存"
            } catch (c: CancellationException) {
                message = "已取消"
            } catch (_: Exception) {
                message = "诊断报告保存失败"
            }
        }
        job = task
    }

    private fun run(action: suspend () -> Unit) {
        busy = true
        job = scope.launch {
            try { action() }
            catch (_: CancellationException) { message = "已取消" }
            catch (_: Exception) { message = "读取或保存设置失败" }
            finally { busy = false }
        }
    }
}

/**
 * 合并后的设置正文：首页设置页与编辑页齿轮共用这一份，两处看到的内容完全一样。
 * 分节标题一律主题绿（与原来首页设置同一格式）；onOpenHelp 非空时多一节「参数说明」入口
 * （全文说明挂在编辑页里，首页那份没有这个入口）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsBody(controller: SettingsController,
                          workspace: DiagnosticReport.Workspace = DiagnosticReport.Workspace(),
                          onOpenHelp: (() -> Unit)? = null) {
    LaunchedEffect(Unit) { controller.refresh() }
    val context = LocalContext.current
    val latestWorkspace by rememberUpdatedState(workspace)
    val diagnosticsLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        controller.exportDiagnostics(uri, latestWorkspace)
    }
    val editable = !controller.busy
    val value = controller.value
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (controller.busy) Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp)); Text("正在读取…", style = MaterialTheme.typography.bodyMedium)
        }
        controller.message?.let { Text(it, color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium) }

        // 首页显示方式放在最上面：齿轮设置的第一个选项位。
        Section("首页")
        Choice("首页显示", listOf(HOME_PREVIEW_MODE to "预览框 · 当前照片", HOME_GRID_MODE to "宫格 · 图库照片"),
            UiPrefs.homeViewMode, true) { UiPrefs.updateHomeViewMode(it) }
        Text("预览框显示上次打开的照片，点一下继续显影；宫格把图库里的照片两列平铺，只放缩略图，" +
            "不带星级 / 色标 / 相册分组，可以一直往下滑。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Section("预览")
        Choice("预览质量（长边）", AppSettings.PREVIEW_EDGES.map { it.toString() to "${it} px" },
            value.previewLongEdge.toString(), editable) {
            controller.update(value.copy(previewLongEdge = it.toInt()))
        }
        Text("预览渲染的长边像素上限，最大 4096 px。数值越小越快、越省内存；导出成片不受它影响。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Section("界面")
        ToggleRow("显示直方图", UiPrefs.showHistogram) { UiPrefs.updateHistogramVisible(it) }
        Text("关闭后，照片预览左上角不再显示直方图，画面更干净。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ToggleRow("工作台自动隐藏", UiPrefs.workbenchAutoHide) { UiPrefs.updateWorkbenchAutoHide(it) }
        SliderRow(
            label = "隐藏等待",
            value = UiPrefs.workbenchHideSeconds.toFloat(),
            range = 3f..10f,
            format = "%.0f 秒",
            resetValue = 3f,
            enabled = UiPrefs.workbenchAutoHide,
            hint = "左滑更快收起 · 右滑更久才收起（3–10 秒）",
        ) { UiPrefs.updateWorkbenchHideSeconds(it.toInt()) }
        Text("开启后，工作台（照片条 + 胶片 / 相纸栏）闲置到设定秒数会自动收起；点控制条左端的工作台按钮即可重新弹出。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (onOpenHelp != null) {
            Section("帮助")
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(onClick = onOpenHelp).padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("参数说明", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }

        Section("缓存")
        Choice("渲染缓存预算", AppSettings.CACHE_LIMITS.map {
            it.toString() to if (it == 0) "关闭" else "${it} MB"
        }, value.renderCacheMiB.toString(), editable) {
            controller.update(value.copy(renderCacheMiB = it.toInt()))
        }
        controller.storage?.let { storage ->
            Text("已用 ${android.text.format.Formatter.formatShortFileSize(context, storage.bytes)} · ${storage.entries} 项 · 预算 ${storage.budget / (1024 * 1024)} MB",
                style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = controller::clearCache, enabled = editable, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("清空渲染缓存") }
        Text("只清除可重新生成的临时渲染数据，不影响原图与已保存的编辑。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Section("诊断")
        OutlinedButton(onClick = {
            diagnosticsLauncher.launch("SpekLab诊断-" +
                java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
                    .format(java.util.Date()) + ".zip")
        }, enabled = editable, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("导出诊断报告（.zip）") }
        Text("只有版本号、设备型号、当前设置和缓存统计，用来排查渲染异常。" +
            "不含照片、文件名、路径、定位信息或日志，也不会自动上传。保存位置由你选择。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 首页的整页设置：外壳是 Scaffold + 返回，内容与编辑页齿轮同一份。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(controller: SettingsController, onBack: () -> Unit,
                            workspace: DiagnosticReport.Workspace = DiagnosticReport.Workspace()) {
    BackHandler(onBack = onBack)
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = { Text("设置", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                    }
                })
        }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp)) {
                SettingsBody(controller, workspace)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Choice(label: String, choices: List<Pair<String, String>>, selected: String,
                   enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = choices.firstOrNull { it.first == selected }?.second ?: "未知",
            onValueChange = {}, readOnly = true, enabled = enabled, label = { Text(label) }, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            choices.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onChange(id) })
            }
        }
    }
}
