package com.spektralab.android.feature.export

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import com.spektralab.android.data.export.ExportJobStore
import kotlinx.coroutines.*

/** Durable receipt browser; source files and output pixels are owned elsewhere. */
internal class ExportTasksController(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val store = ExportJobStore(app)
    var opened by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var entries by mutableStateOf<List<ExportJobStore.Entry>>(emptyList()); private set
    private var generation = 0L
    private var relinkJob: Job? = null
    fun open() { opened = true; error = null; refresh() }
    fun close() { opened = false; generation++; relinkJob?.cancel() }
    fun clearError() { error = null }
    fun refresh() {
        if (loading) return
        val token = ++generation; loading = true
        scope.launch {
            try {
                com.spektralab.android.data.export.ExportCoordinator.get(app).recover()
                val result = withContext(Dispatchers.IO) { store.entries() }
                if (token == generation) entries = result
            } catch (error: Exception) {
                if (token == generation) this@ExportTasksController.error = error.message ?: "任务记录读取失败"
            } finally { loading = false }
        }
    }
    fun failed(message: String) { error = message; refreshAfterError() }
    private fun refreshAfterError() {
        val token = ++generation
        scope.launch {
            runCatching {
                val result = withContext(Dispatchers.IO) { store.entries() }
                if (token == generation) entries = result
            }
        }
    }
    fun relink(id: String, uri: Uri, onPermission: (Uri) -> Unit) {
        if (loading) return
        loading = true; error = null
        relinkJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val task = currentCoroutineContext()
                    store.relink(id, uri) { task.ensureActive() }
                }
                onPermission(uri)
                entries = withContext(Dispatchers.IO) { store.entries() }
            } catch (error: Exception) {
                this@ExportTasksController.error = error.message ?: "原图授权恢复失败"
            } finally { loading = false }
        }
    }
    fun remove(id: String) {
        if (loading) return
        loading = true; error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.remove(id) }
                entries = withContext(Dispatchers.IO) { store.entries() }
            } catch (error: Exception) {
                this@ExportTasksController.error = error.message ?: "任务记录未移除"
            } finally { loading = false }
        }
    }
}
