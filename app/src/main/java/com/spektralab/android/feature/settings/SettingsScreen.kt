package com.spektralab.android.feature.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spektralab.android.core.model.AppSettings
import com.spektralab.android.data.cache.ExportRenderCache
import com.spektralab.android.data.cache.RenderDiskCache
import com.spektralab.android.data.diagnostics.DiagnosticReport
import com.spektralab.android.data.local.AppSettingsStore
import com.spektralab.android.engine.Stock
import com.spektralab.android.engine.StockCatalog
import kotlinx.coroutines.*

/** 全局设置（预览质量 / 缓存预算 / 默认胶片相纸）：持久化 + 立即生效缓存预算。 */
internal class SettingsController(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val store = AppSettingsStore.get(app)
    var value by mutableStateOf(AppSettings()); private set
    var busy by mutableStateOf(true); private set
    var message by mutableStateOf<String?>(null); private set
    var films by mutableStateOf<List<Stock>>(emptyList()); private set
    var papers by mutableStateOf<List<Stock>>(emptyList()); private set
    var storage by mutableStateOf<RenderDiskCache.Snapshot?>(null); private set
    private var job: Job? = null

    init { refresh(initial = true) }

    fun dismissMessage() { message = null }

    fun refresh(initial: Boolean = false) {
        if (busy && !initial) return
        run {
            val loaded = withContext(Dispatchers.IO) {
                val read = store.read()
                val catalog = StockCatalog.load(app)
                val cache = ExportRenderCache.get(app)
                cache.setBudget(read.value.renderCacheBytes)
                Triple(read, catalog, cache.storage())
            }
            value = loaded.first.value
            films = loaded.second.first; papers = loaded.second.second
            storage = loaded.third
        }
    }

    fun update(next: AppSettings) {
        if (busy || next == value) return
        // 「自动」是合法哨兵值：交给自动显影按画面选片；具体型号才校验匹配。
        val filmOk = next.defaultFilm == "auto" || films.any { it.id == next.defaultFilm }
        val paperOk = next.defaultPaper == "auto" || papers.any { it.id == next.defaultPaper }
        val pairOk = next.defaultFilm == "auto" || next.defaultPaper == "auto" ||
            StockCatalog.canPrint(next.defaultFilm, next.defaultPaper)
        if (!filmOk || !paperOk || !pairOk) {
            message = "所选胶片与相纸不匹配"; return
        }
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

    fun chooseFilm(id: String) {
        if (id == "auto") { update(value.copy(defaultFilm = "auto", defaultPaper = "auto")); return }
        val film = films.firstOrNull { it.id == id } ?: return
        if (value.defaultPaper == "auto") { update(value.copy(defaultFilm = id)); return }
        val compatible = papers.filter { StockCatalog.canPrint(id, it.id) }
        val paper = compatible.firstOrNull { it.id == film.targetPrint } ?: compatible.firstOrNull() ?: return
        update(value.copy(defaultFilm = id, defaultPaper = paper.id))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(controller: SettingsController, onBack: () -> Unit,
                            workspace: DiagnosticReport.Workspace = DiagnosticReport.Workspace()) {
    LaunchedEffect(Unit) { controller.refresh() }
    LaunchedEffect(controller.message) {
        controller.message?.let { /* shown inline */ }
    }
    BackHandler(onBack = onBack)
    val context = androidx.compose.ui.platform.LocalContext.current
    val latestWorkspace by rememberUpdatedState(workspace)
    val diagnosticsLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        controller.exportDiagnostics(uri, latestWorkspace)
    }
    val editable = !controller.busy
    val value = controller.value
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
                .padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (controller.busy) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp)); Text("正在读取…", style = MaterialTheme.typography.bodyMedium)
                }
                controller.message?.let { Text(it, color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium) }

                Section("预览")
                Choice("预览质量（长边）", AppSettings.PREVIEW_EDGES.map { it.toString() to "${it} px" },
                    value.previewLongEdge.toString(), editable) {
                    controller.update(value.copy(previewLongEdge = it.toInt()))
                }
                Text("预览渲染的长边像素上限。数值越小越快、越省内存。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Section("材料")
                Choice("默认胶片", listOf("auto" to "自动 · 按画面智能选片") + controller.films.map { it.id to it.name },
                    value.defaultFilm, editable) {
                    controller.chooseFilm(it)
                }
                Choice("默认相纸", listOf("auto" to "自动 · 跟随胶片搭配") +
                    (if (value.defaultFilm == "auto") controller.papers
                     else controller.papers.filter { StockCatalog.canPrint(value.defaultFilm, it.id) })
                        .map { it.id to it.name }, value.defaultPaper, editable) {
                    controller.update(value.copy(defaultPaper = it))
                }
                Text("新照片的起始胶片与相纸。选定具体型号后，自动显影只调整影调、不再更换材料；" +
                    "选「自动」才让自动显影按画面挑胶片。已保存过编辑的照片不受影响。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
