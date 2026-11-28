package com.spektralab.android.feature.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spektralab.android.core.color.DisplayP3Icc
import com.spektralab.android.core.develop.SmartDevelop
import com.spektralab.android.core.develop.SmartDevelopStyle
import com.spektralab.android.core.model.EditPreset
import com.spektralab.android.core.model.EditSnapshot
import com.spektralab.android.core.model.EditGroup
import com.spektralab.android.core.model.EditTemplate
import com.spektralab.android.core.model.EditTransferProblem
import com.spektralab.android.core.model.SavedEditSession
import com.spektralab.android.R
import com.spektralab.android.core.model.ExportOptions
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.core.model.ExportRecipe
import com.spektralab.android.data.cache.EffectThumbnailStore
import com.spektralab.android.data.cache.ExportRenderCache
import com.spektralab.android.data.export.ExportMetadata
import com.spektralab.android.data.export.ExportPublisher
import com.spektralab.android.data.export.TiffWriter
import com.spektralab.android.data.export.measureExport
import com.spektralab.android.data.local.EditPresetStore
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.data.local.GeoTagStore
import com.spektralab.android.data.local.RecipeStore
import com.spektralab.android.data.metadata.DateImprintMetadata
import com.spektralab.android.feature.export.ExportTasksController
import com.spektralab.android.engine.CameraWhiteBalance
import com.spektralab.android.engine.ColorPipeline
import com.spektralab.android.engine.EngineTaskQueue
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.Histogram
import com.spektralab.android.engine.LensProfile
import com.spektralab.android.engine.LensProfileStore
import com.spektralab.android.engine.RenderAdmission
import com.spektralab.android.engine.Rgba16Source
import com.spektralab.android.engine.NativeRenderResult
import com.spektralab.android.engine.RawDecodeCancellation
import com.spektralab.android.engine.RawDecodeResult
import com.spektralab.android.engine.RawDecoder
import com.spektralab.android.engine.SpkException
import com.spektralab.android.engine.SpkResult
import com.spektralab.android.engine.Stock
import com.spektralab.android.engine.StockCatalog
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 编辑器状态与引擎句柄的宿主。用 [AndroidViewModel] 承载，跨配置变更（旋转屏幕）存活：
 * - 引擎句柄只在 onCleared() 释放，旋转不会泄漏 native 句柄、也不会重复创建；
 * - 参数/渲染结果/源图都留在这里，旋转后直接复用，不重新解码。
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    // ---------------------------------------------------------------
    // 片边随机身份：相机种子 / 过片种子 / 帧号，三项滑轨都是
    // 0 = 这一张随机一个（种子 1-999、帧号 1-36），1-N = 按设定值输出。
    // 原来的「整卷 36 帧自动递增」和「手动帧号开关」都已删除。
    // ---------------------------------------------------------------
    internal fun setOverscanCameraSeed(seed: Int) {
        val value = seed.coerceIn(0, EditorParams.SEED_MAX)
        params.overscanCameraSeed = value
        // 拖回 0 就当「再随机一次」，否则停在 0 却沿用上一次的号，看着像没反应。
        if (value == 0) params.rerollOverscanCameraSeed()
        scheduleRender()
    }

    internal fun setOverscanFrameSeed(seed: Int) {
        val value = seed.coerceIn(0, EditorParams.SEED_MAX)
        params.overscanFrameSeed = value
        if (value == 0) params.rerollOverscanFrameSeed()
        scheduleRender()
    }

    internal fun setOverscanFrameNumber(number: Int) {
        val value = number.coerceIn(0, EditorParams.FRAME_NUMBER_MAX)
        params.overscanFrameNumber = value
        if (value == 0) params.rerollOverscanFrameNumber()
        scheduleRender()
    }

    /** 批量导出前算出来的片边连号方案：哪些照片开了片边、各自排到第几号。 */
    data class BatchEdgePlan(val selected: Int, val edged: Int, val numbers: LinkedHashMap<Uri, Int>)
    var batchEdgePlan by mutableStateOf<BatchEdgePlan?>(null)
        private set
    var batchEdgeChecking by mutableStateOf(false)
        private set

    /**
     * 导出页（批量）的「带片边照片按帧号顺序导出」开关，默认关闭，进程内保持，冷启动回到关闭。
     * 关：批量导出就按每张照片自己的设定走（帧号 0 = 各自随机），不统计、不改写。
     * 开：点「导出所选照片」先统计这批里有几张开了片边，确认后这些照片按勾选顺序从 01 连号
     *     （排到 36 回到 01 继续，照片够多就循环往复），相机种子 / 过片种子同时强制按
     *     [EditorParams.SEQUENTIAL_SEED] 输出，让整批接起来像同一卷、同一次扫描；
     *     没开片边的照片不参与重新编号，两颗种子也不动。
     */
    var batchSequentialFrameNumber by mutableStateOf(false)

    fun cancelBatchEdgeNumbering() { batchEdgePlan = null }

    /**
     * 点「导出所选照片」先走这里：统计勾选照片里有多少张开了片边。
     * 开了片边的按勾选顺序从 01 开始连号，排到 36 再回到 01 继续；没开片边的不参与重新编号。
     * 算完先弹确认框，用户点「开始导出」才真正落编号并导出。
     */
    fun checkBatchEdgeNumbering() {
        if (isBatchExporting || isExporting || batchEdgeChecking) return
        val targets = selectedBatchExportPhotos
        if (targets.isEmpty()) { toast("先勾选要导出的照片"); return }
        val activeUri = currentUri
        // 当前这张用的是还没落盘的实时参数，先在主线程取一次片边状态，别在 IO 线程读 Compose 状态。
        val activeEdge = params.overscanActive && !params.filmOff
        batchEdgeChecking = true
        viewModelScope.launch {
            val plan = withContext(Dispatchers.IO) {
                val numbers = LinkedHashMap<Uri, Int>()
                targets.forEach { uri ->
                    val edge = if (uri == activeUri) activeEdge
                    else EditStore.load(app, uri)?.let { it.overscanActive && !it.filmOff } == true
                    if (edge) numbers[uri] = numbers.size % EditorParams.FRAME_NUMBER_MAX + 1
                }
                BatchEdgePlan(targets.size, numbers.size, numbers)
            }
            batchEdgeChecking = false
            batchEdgePlan = plan
        }
    }

    /** 确认框里的「开始导出」：先把新帧号和统一种子落到这些照片自己的参数里，再走原来的批量导出流程。 */
    fun startBatchExport() {
        val plan = batchEdgePlan ?: return
        batchEdgePlan = null
        val activeUri = currentUri
        val activeNumber = plan.numbers[activeUri]
        viewModelScope.launch {
            if (plan.edged > 0) {
                withContext(Dispatchers.IO) {
                    plan.numbers.forEach { (uri, number) ->
                        if (uri == activeUri) return@forEach      // 当前这张用实时参数，稍后在主线程改
                        val stored = EditStore.load(app, uri) ?: return@forEach
                        stored.overscanFrameNumber = number
                        // 连号导出要的是「同一颗机身、同一次扫描」：两颗种子一律按 1 输出，
                        // 不然每张各自随机一套磨损，接成一条还是散的。
                        stored.overscanCameraSeed = EditorParams.SEQUENTIAL_SEED
                        stored.overscanFrameSeed = EditorParams.SEQUENTIAL_SEED
                        EditStore.save(app, uri, stored)
                    }
                }
                if (activeNumber != null) {
                    params.overscanFrameNumber = activeNumber
                    params.overscanCameraSeed = EditorParams.SEQUENTIAL_SEED
                    params.overscanFrameSeed = EditorParams.SEQUENTIAL_SEED
                }
            }
            batchExport()
        }
    }

    private class ExportCancelled : CancellationException("Export cancelled")
    @Volatile private var exportCancelRequested = false
    @Volatile private var tableNativeRendering = false

    private fun checkExportCancellation() {
        if (exportCancelRequested || cleared) throw ExportCancelled()
    }

    private data class PendingFrame(
        val pixels: FloatArray,
        val width: Int,
        val height: Int,
        val inputColorSpace: String,
        val photo: Long,
        val revision: Long,
        val edit: EditSnapshot,
    )

    private val app get() = getApplication<Application>()

    internal val savedPhotos by lazy { com.spektralab.android.feature.gallery.SavedPhotosController(app, viewModelScope) }
    internal val lightTableProjects by lazy { com.spektralab.android.feature.lighttable.LightTableProjectsController(app, viewModelScope) }
    internal val tableOutput by lazy { com.spektralab.android.feature.lighttable.TableOutputController(app, viewModelScope) }
    internal fun prepareVersionBoundary() {
        if (!busy && !isExporting && !isBatchExporting && !isApplyingEdits) saveCurrentSidecar()
    }
    private val sessionPersistence by lazy { EditSessionPersistence(app, viewModelScope) }
    private var workbenchChanged = false

    private fun persistWorkbench() {
        workbenchChanged = true
        sessionPersistence.save(photos.map(Uri::toString),
            photos.getOrNull(currentIndex)?.toString(), batchExportSelection.mapTo(linkedSetOf(), Uri::toString))
    }

    internal fun restoreWorkbench(session: SavedEditSession) {
        if (busy || isExporting || isBatchExporting || isApplyingEdits || session.photos.isEmpty()) return
        saveCurrentSidecar()
        photos = session.photos.map(Uri::parse)
        currentIndex = session.photos.indexOf(session.current).coerceAtLeast(0)
        batchExportSelection = session.selected.mapTo(linkedSetOf(), Uri::parse)
        retainThumbnailRequests(photos.toSet())
        thumbnails = thumbnails.filterKeys { it in photos }
        developedPreviews = developedPreviews.filterKeys { it in photos }
        galleryPreviews = galleryPreviews.filterKeys { it in photos }
        openPhoto(photos[currentIndex])
        persistWorkbench()
    }

    /** Relocation has already proved byte equality. Keep the in-memory edit and its history. */
    internal fun relinkWorkbenchSource(previous: Uri, replacement: Uri) {
        if (previous == replacement) return
        photos = photos.map { if (it == previous) replacement else it }.distinct()
        batchExportSelection = batchExportSelection.mapTo(linkedSetOf()) { if (it == previous) replacement else it }
        undoHistory.remove(previous.toString())?.let { undoHistory[replacement.toString()] = it }
        redoHistory.remove(previous.toString())?.let { redoHistory[replacement.toString()] = it }
        runCatching { historyFile(previous.toString()).delete() }
        if (undoHistory.containsKey(replacement.toString()) || redoHistory.containsKey(replacement.toString()))
            scheduleHistoryFlush(replacement.toString())
        if (currentUri == previous) currentUri = replacement
        if (paramsUri == previous) paramsUri = replacement
        currentIndex = currentUri?.let(photos::indexOf)?.coerceAtLeast(0) ?: 0
        retainThumbnailRequests(photos.toSet())
        thumbnails = thumbnails - previous
        developedPreviews = developedPreviews - previous
        discardGalleryPreview(previous)
        lastHistoryKey = null
        refreshHistoryButtons()
        // The transaction already remapped the durable workbench. Avoid writing an empty,
        // unrelated in-memory session over it when relinking a historical library entry.
        if (photos.isNotEmpty()) persistWorkbench()
    }
    internal fun retrySavedEdit() {
        if (busy || isExporting || isBatchExporting || isApplyingEdits) return
        EditStore.dismissStorageError()
        currentUri?.takeIf { it == paramsUri }?.let { EditStore.save(app, it, committedParams()) }
        if (workbenchChanged) persistWorkbench()
    }
    internal val editPresets by lazy { EditPresetController(app, viewModelScope) }
    internal var isApplyingEdits by mutableStateOf(false)
        private set
    internal var editTransferError by mutableStateOf<String?>(null)
        private set
    internal var editTransferRevision by mutableStateOf(0)
        private set
    private var previewWorkRunning by mutableStateOf(false)
    internal val canTransferEdits: Boolean get() = hasPhoto && !busy && !isExporting && !isBatchExporting &&
        !isApplyingEdits && !isCropping && !previewWorkRunning

    var params by mutableStateOf(EditorParams())
        private set
    var engine by mutableStateOf<EngineClient?>(null)
        private set
    var rendered by mutableStateOf<Bitmap?>(null)
    var lastResult by mutableStateOf<SpkResult?>(null)
    var histogram by mutableStateOf<Histogram?>(null)
        private set
    var sourceBitmap by mutableStateOf<Bitmap?>(null)
    var films by mutableStateOf<List<Stock>>(emptyList())
        private set
    var papers by mutableStateOf<List<Stock>>(emptyList())
        private set
    var hasPhoto by mutableStateOf(false)
        private set
    var isCropping by mutableStateOf(false)
    // 成片裁切：在显影/调色/片边都完成后的成片上再裁一道，独立于源图裁剪。
    var croppingFinished by mutableStateOf(false)
        private set
    var cropWorkspaceBitmap by mutableStateOf<Bitmap?>(null)
        private set
    private var finishedCropStart = com.spektralab.android.core.geometry.FinishedCrop.FULL
    val cropSelection: FloatArray get() = if (croppingFinished) params.finishedCrop.let {
        floatArrayOf(it.x, it.y, it.width, it.height)
    } else floatArrayOf(params.cropX, params.cropY, params.cropW, params.cropH)

    fun updateCropSelection(x: Float, y: Float, w: Float, h: Float) {
        if (croppingFinished) params.finishedCrop = com.spektralab.android.core.geometry.FinishedCrop(x, y, w, h)
        else { params.cropX = x; params.cropY = y; params.cropW = w; params.cropH = h }
        if (cropAspect == "原始") cropAspect = "自定义"
    }

    // 进入成片裁切：先把当前成片（不含已有成片裁切）转成位图作为裁剪底图。
    fun beginFinishedCrop() {
        if (busy || isExporting || isBatchExporting || isCropping || isApplyingEdits) return
        val result = lastResult ?: return
        val edit = params.copy().apply { finishedCrop = com.spektralab.android.core.geometry.FinishedCrop.FULL }
        val photo = photoGeneration
        val revision = renderGeneration
        busy = true
        viewModelScope.launch {
            var bitmap: Bitmap? = null
            try {
                bitmap = withContext(Dispatchers.Default) { convertToBitmap(result, edit, resultColorSpace) }
                if (photo != photoGeneration || revision != renderGeneration) return@launch
                cropWorkspaceBitmap = bitmap; bitmap = null
                finishedCropStart = params.finishedCrop
                croppingFinished = true; cropAspect = "自定义"; isCropping = true
            } catch (error: Exception) {
                fail("成片裁切预览失败：${error.message.orEmpty()}")
            } finally {
                bitmap?.recycle()
                if (photo == photoGeneration) busy = false
            }
        }
    }

    private fun releaseCropBitmap() {
        val bitmap = cropWorkspaceBitmap
        cropWorkspaceBitmap = null
        if (bitmap !== sourceBitmap) bitmap?.recycle()
    }
    var cropAspect by mutableStateOf("原始")
    var isExporting by mutableStateOf(false)
        private set
    var isExportCommitCompleted by mutableStateOf(false)
        private set
    var exportFormat by mutableStateOf("JPEG")
    var exportRes by mutableStateOf(0)
    var exportColorSpace by mutableStateOf("sRGB")
    var exportTiffDeflate by mutableStateOf(false)
    var heifAvailable by mutableStateOf(false)
        private set
    var heifChecking by mutableStateOf(true)
        private set
    var jpegQuality by mutableStateOf(100)
    var exportCustomSize by mutableStateOf<com.spektralab.android.core.model.ExportSize?>(null)
    var exportNaming by mutableStateOf(com.spektralab.android.core.model.ExportNaming())
    private val exportPreferences = app.getSharedPreferences("export_metadata", android.content.Context.MODE_PRIVATE)
    // 自定义保存目录（SAF tree Uri；为空 = 系统相册 Pictures/Speklab）。
    var exportDirUri: Uri? = exportPreferences.getString("dir", null)?.let(Uri::parse)
        private set
    private var keepCaptureState by mutableStateOf(exportPreferences.getBoolean("capture", true))
    private var keepLocationState by mutableStateOf(exportPreferences.getBoolean("location", true))
    var exportKeepCapture: Boolean
        get() = keepCaptureState
        set(value) { keepCaptureState = value; exportPreferences.edit().putBoolean("capture", value).apply() }
    var exportKeepLocation: Boolean
        get() = keepLocationState
        set(value) {
            keepLocationState = value; exportPreferences.edit().putBoolean("location", value).apply()
            // 刚授权位置权限时，立刻把当前照片的位置补存一份。
            if (value) viewModelScope.launch(Dispatchers.IO) {
                currentUri?.let { uri -> captureGeo(uri) }
            }
        }

    /** 设置自定义导出目录（SAF tree Uri），传 null 恢复系统相册。 */
    fun setExportDir(uri: Uri?) {
        exportDirUri = uri
        if (uri != null) {
            try {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) { /* 部分 provider 不给持久权限，仍尝试本次写入 */ }
            exportPreferences.edit().putString("dir", uri.toString()).apply()
        } else {
            exportPreferences.edit().remove("dir").apply()
        }
    }
    var recipes by mutableStateOf(RecipeStore.load(app))
        private set
    private val smartPreferences = app.getSharedPreferences("smart_develop", android.content.Context.MODE_PRIVATE)
    private var smartAutoState by mutableStateOf(smartPreferences.getBoolean("automatic", true))
    private var smartStyleState by mutableStateOf(SmartDevelopStyle.from(smartPreferences.getString("style", "cinema").orEmpty()))
    internal var smartAutoEnabled: Boolean
        get() = smartAutoState
        set(value) {
            smartAutoState=value;smartPreferences.edit().putBoolean("automatic",value).apply()
            if(!value){smartQueueGeneration++;smartQueueJob?.cancel();smartImportRunning=false}
        }
    internal var smartStyle: SmartDevelopStyle
        get() = smartStyleState
        set(value) { smartStyleState=value;smartPreferences.edit().putString("style",value.id).apply() }
    private var smartRecipeId by mutableStateOf(smartPreferences.getString("recipe", "").orEmpty())
    /** 选中的自定义配方；为空时按 smartStyle 走内置三档。内置风格与我的配方互斥。 */
    internal var smartRecipe: EditPreset?
        get() = smartRecipeId.takeIf { it.isNotEmpty() }?.let { id -> editPresets.items.firstOrNull { it.id == id } }
        set(value) {
            smartRecipeId = value?.id.orEmpty()
            smartPreferences.edit().putString("recipe", smartRecipeId).apply()
        }
    var smartImportRunning by mutableStateOf(false); private set
    var smartImportCompleted by mutableStateOf(0); private set
    var smartImportTotal by mutableStateOf(0); private set
    var smartMessage by mutableStateOf(""); private set
    private var smartQueueJob: Job? = null
    private var smartApplyJob: Job? = null
    private var smartQueueGeneration = 0L
    private val smartAnalysisMutex = kotlinx.coroutines.sync.Mutex()
    private val freshlySmartDeveloped = mutableSetOf<Uri>()
    var log by mutableStateOf("（未创建引擎）\n")
        private set
    var currentUri by mutableStateOf<Uri?>(null)
        private set
    var photoInfo by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    var photos by mutableStateOf<List<Uri>>(emptyList())
        private set
    var batchExportSelection by mutableStateOf<Set<Uri>>(emptySet())
        private set
    val selectedBatchExportPhotos: List<Uri>
        get() = photos.filter { it in batchExportSelection }
    var currentIndex by mutableStateOf(0)
        private set
    var thumbnails by mutableStateOf<Map<Uri, Bitmap>>(emptyMap())
        private set
    /** Small, already-developed previews used by the viewing stand. */
    var developedPreviews by mutableStateOf<Map<Uri, Bitmap>>(emptyMap())
        private set
    /** Bounded, decoded previews for the Gallery's immersive viewer. */
    var galleryPreviews by mutableStateOf<Map<Uri, Bitmap>>(emptyMap())
        private set
    var galleryPreviewErrors by mutableStateOf<Map<Uri, String>>(emptyMap())
        private set
    var viewingPhotos by mutableStateOf<List<Pair<String, Bitmap>>>(emptyList())
        private set
    private var viewingImportRequests by mutableStateOf(0)
    private val viewingImportMutex = kotlinx.coroutines.sync.Mutex()
    val isImportingViewingPhotos: Boolean get() = viewingImportRequests > 0
    var viewingImportCompleted by mutableStateOf(0)
        private set
    var viewingImportTotal by mutableStateOf(0)
        private set
    var isBatchExporting by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var renderProgress by mutableStateOf<Float?>(null)   // 0..100，null = 无进行中渲染
        private set
    var renderStage by mutableStateOf("")
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var dirty by mutableStateOf(false)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    // 场景宽容度测量结果（P0-3）
    var latitudeReadout by mutableStateOf<String?>(null)
        private set
    var latitudeCanApply by mutableStateOf(false)
        private set
    private var latitudeDelta: JSONObject? = null

    private var previewLinear: FloatArray? = null
    private var previewW = 0
    private var previewH = 0
    private var previewInputColorSpace = "sRGB"
    private var resultColorSpace = "sRGB"
    var currentIsRaw by mutableStateOf(false)
        private set
    var lensCorrectionActive by mutableStateOf(true)
        private set
    var matchedLens by mutableStateOf<String?>(null)
        private set
    private var matchedLensProfile: LensProfile? = null
    private var sourceExif: ExifInterface? = null
    private var rawCamXyz: FloatArray? = null
    // 这张 RAW 的“拍摄白平衡”（由相机标定反算出来的色温/色调），只用于显示与“沿用拍摄值”。
    internal var cameraAsShot by mutableStateOf<CameraWhiteBalance.Balance?>(null)
        private set
    private val undoHistory = mutableMapOf<String, MutableList<String>>()
    private val redoHistory = mutableMapOf<String, MutableList<String>>()
    private var suppressHistory = false
    private var lastHistoryKey: String? = null
    private var lastHistoryEditMs = 0L
    // 跨会话撤销/重做：把每张照片的撤销/重做快照栈落到 filesDir/edit-history（不碰相册 SQLite 结构）。
    private val historyDir = File(app.filesDir, "edit-history").apply { mkdirs() }
    private val dirtyHistory = mutableSetOf<String>()
    private var historyFlushJob: Job? = null
    private var renderJob: Job? = null
        set(value) { field = value; trackPreviewWork(value) }
    private var photoLoadJob: Job? = null
    private var frameRenderJob: Job? = null
        set(value) { field = value; trackPreviewWork(value) }
    private var stockPreviewJob: Job? = null
    private var developedPreviewJob: Job? = null
    private var effectThumbJob: Job? = null
    private val thumbnailJobs = mutableMapOf<Uri, Job>()
    private val thumbnailDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(2)
    private val THUMBNAIL_CACHE_LIMIT = 160
    private val galleryPreviewJobs = mutableMapOf<Uri, Job>()
    private val galleryPreviewDecodeMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var photoGeneration = 0L
    private var paramsUri: Uri? = null
    @Volatile private var cleared = false
    private var wbJob: Job? = null
        set(value) { field = value; trackPreviewWork(value) }
    private data class PendingPreview(
        val result: SpkResult,
        val colorSpace: String,
        val photo: Long,
        val revision: Long,
        val renderRequest: Long? = null,
    )
    private var pendingPreview: PendingPreview? = null
    private var gradeGeneration = 0L
    private var gradeRequest = 0L
    private var renderRequest = 0L
    private var publishedRenderRequest = 0L
    @Volatile private var renderGeneration = 0L
    private var cameraWbJob: Job? = null
        set(value) { field = value; trackPreviewWork(value) }

    /** Job completion must notify Compose even when no new bitmap/state follows it. */
    private fun trackPreviewWork(job: Job?) {
        fun refresh() {
            previewWorkRunning = renderJob?.isActive == true || frameRenderJob?.isActive == true ||
                wbJob?.isActive == true || cameraWbJob?.isActive == true
        }
        refresh()
        job?.invokeOnCompletion { viewModelScope.launch(Dispatchers.Main.immediate) { refresh() } }
    }
    private var engineCreateJob: Job? = null
    private var pendingFrame: PendingFrame? = null
    private var cropEditStart: FloatArray? = null
    // One engine/session owns mutable native state. Decode/open/render/export transactions must not overlap.
    private val engineTasks = EngineTaskQueue()
    private val renderCache by lazy { ExportRenderCache.get(app.applicationContext) }
    internal val exportCacheStats get() = renderCache.stats
    internal val renderTaskState get() = engineTasks.snapshot.value
    private val engineDispatcher = engineTasks.dispatcher

    init {
        viewModelScope.launch {
            val (f, p) = StockCatalog.load(app)
            films = f
            papers = p
        }
        createEngine()
        // HEIF 是否可导出取决于系统编码器，开机探测一次，没有就不给这个格式。
        viewModelScope.launch {
            try {
                heifAvailable = withContext(Dispatchers.IO) {
                    com.spektralab.android.data.export.HeifOutput.capability(app).available
                }
            } catch (_: Exception) { heifAvailable = false }
            finally { heifChecking = false }
        }
    }

    fun append(s: String) {
        val line = (if (s.length > 2048) s.take(2047) + "…" else s) + "\n"
        log = (log + line).takeLast(32 * 1024)
    }

    fun dismissError() { errorMessage = null }

    internal var dateImprintMetadata by mutableStateOf(DateImprintMetadata())
        private set

    fun setDateImprintEnabled(enabled: Boolean) {
        params.dateImprintActive = enabled
        if (enabled) dateImprintMetadata.applyTo(params)
        scheduleRender()
    }

    /** 打开「打印拍摄参数」时顺手把这张照片读到过的 EXIF 参数补进去，不然开关像没反应。 */
    fun setDateImprintShowData(enabled: Boolean) {
        params.dateImprintShowData = enabled
        if (enabled) dateImprintMetadata.applyTo(params)
        scheduleRender()
    }

    /** Explicitly restore available photo values, retaining manual text for missing metadata. */
    fun usePhotoDateImprint() {
        val before = params.dateImprintText to params.dateImprintDataText
        dateImprintMetadata.applyTo(params, replace = true)
        if (before != (params.dateImprintText to params.dateImprintDataText)) scheduleRender()
    }

    // 错误：弹窗提示 + 记日志。引擎的英文异常在这里翻成用户能懂的中文。
    private fun fail(msg: String) {
        errorMessage = when {
            msg.contains("RAW open: Unsupported file format or not RAW file") ->
                "无法打开这张 RAW：文件格式不受支持，或拍摄后文件还没写完。原文件没有改动，稍后在相册里重新导入即可。"
            msg.contains("RAW open:") -> "无法打开这张 RAW（${msg.substringAfter("RAW open:").trim()}）。原文件没有改动。"
            else -> msg
        }
        append(msg)
    }

    // 轻提示（导出完成等），不打断操作。
    private fun toast(msg: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // 当前打开照片的源图尺寸（供裁剪预设按宽高比计算居中裁剪）。
    fun previewWidth(): Int = previewW
    fun previewHeight(): Int = previewH

    fun restoreUri(uriString: String?) {
        if (!uriString.isNullOrEmpty()) currentUri = Uri.parse(uriString)
    }

    fun persistReadPermission(uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Providers that do not offer persistable grants can still be used in this session.
        }
    }

    // 重置全部编辑参数到默认（保留当前照片）。
    fun resetAll() {
        if (isApplyingEdits) return
        finishCurveEdit(cancel = true)
        finishColorEdit(cancel = true)
        finishMixerEdit(cancel = true)
        finishWheelEdit(cancel = true)
        params = EditorParams()
        // 重置会把 params 整个换成默认值，日期/拍摄参数要按当前这张照片重新填一遍，
        // 否则日期后背会退回系统当天（和导入时那个 bug 同一类）。
        dateImprintMetadata.applyTo(params)
        cropAspect = "原始"
        scheduleRender()
    }

    private fun historyKey(uri: Uri? = currentUri): String = uri?.toString() ?: "session"
    /** A live gesture is preview-only until release, even if another operation saves. */
    private fun committedParams(): EditorParams {
        if (sceneCurveStart == null && curveStart == null && colorStart == null && mixerStart == null && wheelStart == null) return params
        return params.copy().apply {
            sceneCurveStart?.let {
                sceneCurves = it
                unreadableSceneCurves = sceneCurveStartUnreadable
            }
            curveStart?.let {
                toneCurves = it
                unreadableToneCurves = curveStartUnreadable
            }
            colorStart?.let {
                colorAdjustments = it
                unreadableColorAdjustments = colorStartUnreadable
            }
            wheelStart?.let {
                colorWheels = it
                unreadableColorWheels = wheelStartUnreadable
            }
            mixerStart?.let {
                channelMixer = it
                unreadableChannelMixer = mixerStartUnreadable
            }
        }
    }

    private fun historySnapshot(): String = committedParams().toJson().toString()

    private fun refreshHistoryButtons() {
        val key = historyKey()
        canUndo = (undoHistory[key]?.size ?: 0) > 1
        canRedo = !redoHistory[key].isNullOrEmpty()
    }

    private fun recordHistory() {
        if (suppressHistory) return
        val key = historyKey()
        val snapshot = historySnapshot()
        val history = undoHistory.getOrPut(key) { mutableListOf(snapshot) }
        if (history.lastOrNull() == snapshot) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastHistoryKey == key && now - lastHistoryEditMs < 350 && history.size > 1) {
            history[history.lastIndex] = snapshot
            redoHistory[key]?.clear()
            lastHistoryEditMs = now
            scheduleHistoryFlush(key)
            refreshHistoryButtons()
            return
        }
        history += snapshot
        if (history.size > 64) history.removeAt(0)
        redoHistory[key]?.clear()
        lastHistoryKey = key
        lastHistoryEditMs = now
        scheduleHistoryFlush(key)
        refreshHistoryButtons()
    }

    private fun historyFile(key: String): File = File(historyDir, historyKeyHash(key) + ".json")

    private fun historyKeyHash(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun writeHistoryFile(key: String) {
        val undo = undoHistory[key] ?: emptyList()
        val redo = redoHistory[key] ?: emptyList()
        val json = JSONObject().put("undo", org.json.JSONArray(undo)).put("redo", org.json.JSONArray(redo)).toString()
        runCatching { com.spektralab.android.data.local.writeAtomicJson(historyFile(key), json) }
    }

    private fun scheduleHistoryFlush(key: String) {
        dirtyHistory += key
        historyFlushJob?.cancel()
        historyFlushJob = viewModelScope.launch {
            delay(700)
            val keys = dirtyHistory.toList()
            dirtyHistory.clear()
            withContext(Dispatchers.IO) { keys.forEach(::writeHistoryFile) }
        }
    }

    private fun flushHistoryNow(key: String? = null) {
        val keys = if (key != null) listOf(key) else dirtyHistory.toList()
        if (key != null) dirtyHistory -= key else dirtyHistory.clear()
        keys.forEach(::writeHistoryFile)
    }

    private fun loadHistory(key: String): Pair<List<String>, List<String>>? = runCatching {
        val file = historyFile(key)
        if (!file.isFile) return@runCatching null
        val json = JSONObject(file.readText())
        val undo = (0 until json.getJSONArray("undo").length()).map { json.getJSONArray("undo").getString(it) }
        val redo = (0 until json.getJSONArray("redo").length()).map { json.getJSONArray("redo").getString(it) }
        undo to redo
    }.getOrNull()

    private fun seedHistory(uri: Uri, initial: EditorParams) {
        val key = historyKey(uri)
        val snapshot = initial.toJson().toString()
        val existing = undoHistory[key]
        if (existing != null) {
            if (existing.lastOrNull() != snapshot) {
                undoHistory[key] = if (freshlySmartDeveloped.remove(uri))
                    mutableListOf(EditorParams().toJson().toString(), snapshot) else mutableListOf(snapshot)
                redoHistory.remove(key)
                scheduleHistoryFlush(key)
            }
        } else {
            val loaded = loadHistory(key)
            if (loaded != null && loaded.first.lastOrNull() == snapshot) {
                undoHistory[key] = loaded.first.toMutableList()
                if (loaded.second.isNotEmpty()) redoHistory[key] = loaded.second.toMutableList()
            } else {
                undoHistory[key] = if (freshlySmartDeveloped.remove(uri))
                    mutableListOf(EditorParams().toJson().toString(), snapshot) else mutableListOf(snapshot)
                redoHistory.remove(key)
                scheduleHistoryFlush(key)
            }
        }
        lastHistoryKey = null
        refreshHistoryButtons()
    }

    fun undo() = restoreHistory(redo = false)
    fun redo() = restoreHistory(redo = true)

    private fun restoreHistory(redo: Boolean) {
        if (isApplyingEdits) return
        finishCurveEdit(cancel = true)
        finishColorEdit(cancel = true)
        finishMixerEdit(cancel = true)
        finishWheelEdit(cancel = true)
        val key = historyKey()
        val undo = undoHistory[key] ?: return
        val redoStack = redoHistory.getOrPut(key) { mutableListOf() }
        if (redo && redoStack.isEmpty()) return
        if (!redo && undo.size <= 1) return
        val previous = params.copy()
        val current = historySnapshot()
        if (redo) {
            val restored = redoStack.removeAt(redoStack.lastIndex)
            undo += restored
            params = EditorParams.fromJson(JSONObject(restored))
        } else {
            redoStack += current
            undo.removeAt(undo.lastIndex)
            params = EditorParams.fromJson(JSONObject(undo.last()))
        }
        if (previous.cropX != params.cropX || previous.cropY != params.cropY ||
            previous.cropW != params.cropW || previous.cropH != params.cropH) {
            cropAspect = if (params.cropW >= 1f && params.cropH >= 1f) "原始" else "自定义"
        }
        currentUri?.let { EditStore.save(app, it, committedParams()) }
        refreshHistoryButtons()
        scheduleHistoryFlush(key)
        lastHistoryKey = null
        suppressHistory = true
        if (currentIsRaw && currentUri != null &&
            (previous.cameraWbCustom != params.cameraWbCustom || previous.cameraWbKelvin != params.cameraWbKelvin || previous.cameraWbTint != params.cameraWbTint)) {
            scheduleCameraWb()
        }
        if (previous.deltaJson(previewW, previewH) == params.deltaJson(previewW, previewH) &&
            previous.cameraWbCustom == params.cameraWbCustom && previous.cameraWbKelvin == params.cameraWbKelvin &&
            previous.cameraWbTint == params.cameraWbTint) scheduleGrade()
        else scheduleRender()
        suppressHistory = false
    }

    fun syncSettingsToOtherPhotos() {
        if (isApplyingEdits) return
        val uri = currentUri ?: return
        val source = committedParams().copy()
        var count = 0
        photos.filter { it != uri }.forEach { targetUri ->
            val target = EditStore.load(app, targetUri) ?: EditorParams()
            target.copySettingsFrom(source)
            EditStore.save(app, targetUri, target)
            seedHistory(targetUri, target)
            count++
        }
        toast("已同步到 $count 张照片（保留各自裁剪）")
    }

    internal fun captureEditTemplate(groups: Set<EditGroup>): EditTemplate = EditTemplate.capture(committedParams(), groups)

    /** 把一张已保存的配方直接套到当前照片，走和「编辑配方」完全相同的模板/裁剪/素材校验路径。 */
    internal fun applyEditTemplate(template: EditTemplate, recipeName: String = "", recipeNote: String = "") {
        val uri = currentUri ?: return
        if (!canTransferEdits) return
        viewModelScope.launch {
            try {
                val preview = previewEditTransfer(template, listOf(uri))
                // 套完要记下"这张片用的是哪份我的配方"：智能配方面板照它显示配方名和描述，
                // 否则会继续显示上一次分析出来的内置风格名（和实际显影结果对不上）。
                val marked = EditTransferPreview(preview.template, preview.targets.map { target ->
                    val after = target.after ?: return@map target
                    val applied = after.toParams().apply {
                        smartDevelopStyle = SmartDevelopStyle.Recipe.id
                        smartDevelopRecipe = recipeName
                        smartDevelopSummary = recipeNote
                        // 片边的随机身份不属于任何配方（EditGroup.LOCAL_FIELDS），仓库里那 8 支自带配方
                        // 也确认一个 overscan_* 键都没写。这里显式复位一次，是为了清掉照片上可能残留的
                        // 老值：旧版本曾按整卷预排把帧号/种子存成非 0，套完配方还挂着那些号。
                        // 复位成 0 = 这一张自己随机一套，和新建照片的默认口径完全一致。
                        overscanCameraSeed = 0; overscanFrameSeed = 0; overscanFrameNumber = 0
                        rerollOverscanCameraSeed(); rerollOverscanFrameSeed(); rerollOverscanFrameNumber()
                    }
                    target.copy(after = EditSnapshot.capture(applied))
                })
                applyEditTransfer(marked)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 预览失败时错误已在 editTransferError 里，界面照常提示。
            }
        }
    }

    internal suspend fun previewEditTransfer(template: EditTemplate, targets: List<Uri>): EditTransferPreview {
        editTransferError = null
        val allowed = photos.toSet()
        val selected = currentUri
        val current = EditSnapshot.capture(committedParams())
        val catalog = films.toList() to papers.toList()
        val rows = withContext(Dispatchers.IO) {
            targets.distinct().filter { it in allowed }.map { uri ->
                val name = queryDisplayName(uri) ?: uri.lastPathSegment.orEmpty()
                try {
                    app.contentResolver.openAssetFileDescriptor(uri, "r")?.close()
                        ?: error("SOURCE_UNAVAILABLE")
                    val stored = EditStore.loadForTransfer(app, uri)
                    val before = if (uri == selected) current else EditSnapshot.capture(stored.params)
                    val raw = EditGroup.DECODE in template.groups && RawDecoder.isRaw(app, uri, name)
                    val result = template.applyTo(before.toParams(), raw, catalog.first, catalog.second)
                    val problem = result.problem?.let {
                        app.getString(when (it) {
                            EditTransferProblem.UNKNOWN_FILM -> R.string.edit_unknown_film
                            EditTransferProblem.UNKNOWN_PAPER -> R.string.edit_unknown_paper
                            EditTransferProblem.NO_COMPATIBLE_PAPER -> R.string.edit_no_paper
                            EditTransferProblem.INCOMPATIBLE_MATERIAL -> R.string.edit_bad_pair
                        })
                    }
                    EditTransferTarget(uri, name, before, stored.json, result.params?.let(EditSnapshot::capture), result.notices, problem,
                        catalog.second.firstOrNull { it.id == result.params?.paperId }?.name)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    EditTransferTarget(uri, name, EditSnapshot.capture(EditorParams()), null, null,
                        problem = app.getString(R.string.edit_source_unavailable))
                }
            }
        }
        return EditTransferPreview(template, rows)
    }

    internal fun applyEditTransfer(preview: EditTransferPreview) {
        if (!canTransferEdits || preview.changes.isEmpty()) return
        val changes = preview.changes
        if (changes.any { it.uri !in photos } || changes.firstOrNull { it.uri == currentUri }?.let {
                it.before != EditSnapshot.capture(committedParams())
            } == true) {
            editTransferError = app.getString(R.string.edit_targets_changed)
            return
        }
        finishCurveEdit(cancel = true)
        finishColorEdit(cancel = true)
        finishMixerEdit(cancel = true)
        finishWheelEdit(cancel = true)
        editTransferError = null
        isApplyingEdits = true
        busy = true
        renderStage = app.getString(R.string.edit_applying)
        renderProgress = null
        viewModelScope.launch {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    changes.forEach { change ->
                        app.contentResolver.openAssetFileDescriptor(change.uri, "r")?.close()
                            ?: error("SOURCE_UNAVAILABLE")
                    }
                    EditStore.saveBatch(app, changes.map { EditStore.BatchEdit(it.uri, it.expectedJson, it.after!!.json) })
                }
                for (change in changes) {
                    val key = historyKey(change.uri)
                    val before = change.before.toParams().toJson().toString()
                    val after = change.after!!.toParams().toJson().toString()
                    val history = undoHistory.getOrPut(key) { mutableListOf(before) }
                    if (history.lastOrNull() != before) history += before
                    if (history.lastOrNull() != after) history += after
                    while (history.size > 64) history.removeAt(0)
                    redoHistory.remove(key)
                    freshlySmartDeveloped.remove(change.uri)
                    developedPreviews = developedPreviews - change.uri
                    discardGalleryPreview(change.uri)
                }
                lastHistoryKey = null
                val currentChange = changes.firstOrNull { it.uri == currentUri }
                busy = false
                isApplyingEdits = false
                if (currentChange != null) {
                    val previous = params
                    params = currentChange.after!!.toParams()
                    cropAspect = if (params.cropW >= 1f && params.cropH >= 1f) "原始" else "自定义"
                    dirty = true
                    suppressHistory = true
                    try {
                        val cameraChanged = previous.cameraWbCustom != params.cameraWbCustom ||
                            previous.cameraWbKelvin != params.cameraWbKelvin || previous.cameraWbTint != params.cameraWbTint
                        when {
                            currentIsRaw && cameraChanged -> scheduleCameraWb()
                            previous.deltaJson(previewW, previewH) == params.deltaJson(previewW, previewH) -> scheduleGrade(commit = false)
                            else -> scheduleRender()
                        }
                    } finally { suppressHistory = false }
                }
                refreshHistoryButtons()
                editTransferRevision++
                toast(app.getString(R.string.edit_applied_count, changes.size))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                editTransferError = app.getString(R.string.edit_apply_error)
            } finally {
                if (isApplyingEdits) busy = false
                isApplyingEdits = false
            }
        }
    }

    // 新照片（无 sidecar）时重置几何/裁剪。
    fun resetGeometry() {
        if (isApplyingEdits) return
        params.cropX = 0f; params.cropY = 0f; params.cropW = 1f; params.cropH = 1f
        params.quarterTurns = 0; params.flipH = false; params.flipV = false; params.rotationDeg = 0f
        params.perspectiveVertical = 0f; params.perspectiveHorizontal = 0f
        params.finishedCrop = com.spektralab.android.core.geometry.FinishedCrop.FULL
        cropAspect = "原始"
    }

    /** Enter the dedicated crop workspace and remember the pre-edit frame. */
    fun beginCrop() {
        if (isApplyingEdits) return
        if (busy || isExporting || isBatchExporting || isCropping || sourceBitmap == null ||
            (params.overscanActive && !params.filmOff)) return
        croppingFinished = false
        // 裁剪底图先按当前透视校正一遍，所见即所得。
        cropWorkspaceBitmap = com.spektralab.android.core.geometry.Perspective.preview(checkNotNull(sourceBitmap),
            params.perspectiveVertical, params.perspectiveHorizontal)
        cropEditStart = floatArrayOf(params.cropX, params.cropY, params.cropW, params.cropH)
        isCropping = true
    }

    /** Discard the current crop gesture and restore the frame on entry. */
    fun cancelCrop() {
        if (croppingFinished) {
            params.finishedCrop = finishedCropStart
            croppingFinished = false; isCropping = false; releaseCropBitmap()
            return
        }
        val start = cropEditStart
        if (start != null && start.size == 4) {
            params.cropX = start[0]; params.cropY = start[1]
            params.cropW = start[2]; params.cropH = start[3]
            cropAspect = if (start[2] >= 1f && start[3] >= 1f) "原始" else "自定义"
        }
        cropEditStart = null
        isCropping = false
        releaseCropBitmap()
    }

    fun resetCropSelection() {
        if (croppingFinished) {
            params.finishedCrop = com.spektralab.android.core.geometry.FinishedCrop.FULL
            cropAspect = "自定义"; return
        }
        params.cropX = 0f; params.cropY = 0f; params.cropW = 1f; params.cropH = 1f
        cropAspect = "原始"
    }

    /** Commit the crop overlay as a bounded geometry edit and re-render it. */
    fun finishCrop() {
        if (!isCropping) return
        if (croppingFinished) {
            isCropping = false; croppingFinished = false; releaseCropBitmap()
            scheduleGrade(); return
        }
        releaseCropBitmap()
        // Match the overlay's 2% minimum; a second 10% clamp changed valid selections.
        params.cropW = params.cropW.coerceIn(0.02f, 1f)
        params.cropH = params.cropH.coerceIn(0.02f, 1f)
        params.cropX = params.cropX.coerceIn(0f, 1f - params.cropW)
        params.cropY = params.cropY.coerceIn(0f, 1f - params.cropH)
        isCropping = false
        croppingFinished = false
        cropEditStart = null
        scheduleRender()
    }

    // Layer 2 调色参数（应用侧）：白平衡 + 曝光 + 对比度/亮度 + 高光/阴影 + 黑点/白点 + 饱和度 + 色彩平衡。
    private fun grade(p: EditorParams = params): Grade {
        fun zone(temp: Float, tint: Float) = floatArrayOf(0.18f * temp, -0.12f * tint, -0.18f * temp)
        return Grade(
            1f + p.temperature * 0.18f,                      // Windows layer2Uniforms
            1f - p.tint * 0.12f,
            1f - p.temperature * 0.18f,
            Math.pow(2.0, (p.gradeExposure + p.exposureEv).toDouble()).toFloat(),  // exposureGain（调色曝光 + 手动曝光补偿叠加）
            p.contrast / 100f,
            p.brightness / 100f,
            1f + p.saturation / 100f,
            p.highlights / 100f,
            p.shadows / 100f,
            p.blackPoint / 100f,
            p.whitePoint / 100f,
            zone(p.cbShadowTemp / 100f, p.cbShadowTint / 100f),
            zone(p.cbMidTemp / 100f, p.cbMidTint / 100f),
            zone(p.cbHighTemp / 100f, p.cbHighTint / 100f),
            p.vignetteAmount / 100f, p.vignetteSize / 100f, p.vignetteFeather / 100f, p.toneCurves, p.colorAdjustments,
            p.channelMixer, p.colorWheels,
        )
    }

    // 把渲染结果（rgba16）过一遍 Layer 2 调色，转成显示用 Bitmap。
    private fun convertToBitmap(result: SpkResult, editParams: EditorParams = params, colorSpace: String = "sRGB"): Bitmap {
        return convertPreview(result, editParams, colorSpace).first
    }

    private fun convertPreview(result: SpkResult, editParams: EditorParams, colorSpace: String, checkCancelled: () -> Unit = {}): Pair<Bitmap, Histogram> {
        return if (editParams.finishedCrop.isFull) ColorPipeline.applyGradeToBitmap(result.rgba16, result.width, result.height,
            grade(editParams), colorSpace, checkCancelled)
        else com.spektralab.android.engine.FinishedPreview.render(result, grade(editParams),
            editParams.finishedCrop, colorSpace, checkCancelled)
    }

    // 更新渲染结果 + 直方图 + 显示位图（一次渲染落地后调用）。
    private suspend fun updateResult(
        result: SpkResult,
        colorSpace: String = "sRGB",
        photo: Long = photoGeneration,
        revision: Long = renderGeneration,
        editParams: EditorParams = params.copy(),
        request: Long? = null,
    ) {
        val pending = PendingPreview(result, colorSpace, photo, revision, request)
        val grading = withContext(Dispatchers.Main.immediate) {
            if (photo != photoGeneration || revision != renderGeneration) return@withContext null
            // Make the new base available to post edits that arrive while it is being converted.
            // A film request's earlier snapshot must not overwrite a newer curve or colour edit.
            wbJob?.cancel()
            val generation = ++gradeGeneration
            pendingPreview = pending
            generation to if (isExporting || isBatchExporting) editParams else params.copy()
        } ?: return
        var computed: Bitmap? = null
        var published = false
        try {
            val (bitmap, counts) = withContext(Dispatchers.Default) {
                val task = currentCoroutineContext()
                convertPreview(result, grading.second, colorSpace) { task.ensureActive() }
                    .also { computed = it.first }
            }
            withContext(Dispatchers.Main.immediate) {
                if (photo == photoGeneration && revision == renderGeneration &&
                    grading.first == gradeGeneration && pendingPreview === pending) {
                    resultColorSpace = colorSpace
                    rendered = com.spektralab.android.core.model.DateImprintPaint.apply(bitmap, grading.second)
                    histogram = counts
                    lastResult = result
                    pending.renderRequest?.let { publishedRenderRequest = it }
                    pendingPreview = null
                    rememberDevelopedPreview(bitmap)
                    published = true
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (pendingPreview === pending) pendingPreview = null
            }
            throw error
        } finally {
            if (!published) computed?.recycle()
        }
    }

    private fun rememberDevelopedPreview(bitmap: Bitmap) {
        val uri = currentUri ?: return
        val photo = photoGeneration
        // 曲线/颜色/混色/色轮的手势中途那一帧，参数还没提交，不能当成效果图。
        val gesturePending = sceneCurveStart != null || curveStart != null || colorStart != null ||
            mixerStart != null || wheelStart != null
        developedPreviewJob?.cancel()
        developedPreviewJob = viewModelScope.launch {
            // The working canvas publishes every completed frame. Its tray thumbnail can wait
            // for a brief pause instead of scaling another bitmap on every slider frame.
            delay(120)
            val preview = withContext(Dispatchers.Default) {
                val edge = max(bitmap.width, bitmap.height)
                if (edge > 960) {
                    val scale = 960f / edge
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                        (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
                } else bitmap
            }
            if (photo == photoGeneration && uri == currentUri && uri in photos && rendered === bitmap) {
                developedPreviews = developedPreviews + (uri to preview)
                if (!gesturePending) queueEffectThumbnail(uri, bitmap, photo)
            }
        }
    }

    /** 画面稳定下来后落一张长边 512 的效果图缩略图：首页宫格、图库和观片台据此直接显示调过参数的成品，
     *  不必为了缩略图重跑引擎。键是「记录身份 + 当时的参数修订号」，参数再改一版，旧的那张自动失效；
     *  写失败或缓存被系统清掉都不提示，界面退回原图。 */
    private fun queueEffectThumbnail(uri: Uri, bitmap: Bitmap, generation: Long) {
        effectThumbJob?.cancel()
        effectThumbJob = viewModelScope.launch {
            // 比内存缩略图多等一些：连续出帧时只留最后一次，省掉中间的重复编码。
            delay(600)
            if (generation != photoGeneration || uri != currentUri || rendered !== bitmap || isExporting || isBatchExporting) return@launch
            if (sceneCurveStart != null || curveStart != null || colorStart != null || mixerStart != null || wheelStart != null) return@launch
            if (bitmap.isRecycled) return@launch
            val key = withContext(Dispatchers.IO) { runCatching { EditStore.savedPhotoKey(app, uri) }.getOrNull() }
            if (key == null) {
                android.util.Log.i("SpekEffect", "skip: 库里查不到这条记录 $uri")
                return@launch
            }
            val written = withContext(Dispatchers.Default) { EffectThumbnailStore.put(app, key.first, key.second, bitmap) }
            if (!written) return@launch
            // 刚落盘就主动把旧画面摘掉：正在显示的首页宫格、图库行、观片台卡片下一帧就换成效果图，
            // 不等列表重新拉取，也不依赖从编辑页返回时有没有触发 refresh()。
            savedPhotos.invalidateThumbnail(key.first)
            refreshViewingEffect(uri, key)
        }
    }

    /** 观片台（独立导入）和工作台照片条里这张若已按原图解出来，就地换成刚落盘的效果图，顺序和数量都不动。
     *  旧位图不主动 recycle：它可能还挂在某一帧画面上，交给 GC，和图库那边一致。 */
    private suspend fun refreshViewingEffect(uri: Uri, key: Pair<String, Long>) {
        val name = uri.toString()
        val inStand = viewingPhotos.any { it.first == name }
        val inStrip = thumbnails.containsKey(uri)
        if (!inStand && !inStrip) return
        val bitmap = withContext(Dispatchers.IO) { EffectThumbnailStore.get(app, key.first, key.second) } ?: return
        if (inStand) viewingPhotos = viewingPhotos.map { if (it.first == name) name to bitmap else it }
        effectThumbKeys = effectThumbKeys + name
        // 照片条那张是按原图解的，重取一次；这次会命中刚落盘的效果图，到了才替换，不闪空白。
        if (inStrip) loadThumbnail(uri, replace = true)
    }

    /** 观片台／工作台的缩略图里，哪些是用落盘的效果图显示的：据此标「成品」并让放大也沿用效果。 */
    var effectThumbKeys by mutableStateOf(setOf<String>())
        private set

    fun isEffectThumbnail(uri: Uri) = uri.toString() in effectThumbKeys

    /** 缩略图优先取编辑页渲染过的成品；没渲染过、或缓存被系统清掉，交回原来的原图解码。
     *  只在后台线程调用；记到 effectThumbKeys 里由调用方回主线程后补。 */
    private fun effectThumbFor(uri: Uri): Bitmap? = runCatching {
        val key = EditStore.savedPhotoKey(app, uri) ?: return null
        EffectThumbnailStore.get(app, key.first, key.second)
    }.getOrNull()

    /** Add gallery photos to the independent viewing-stand queue. */
    fun loadViewingPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewingImportRequests += 1
        viewModelScope.launch {
            try {
                viewingImportMutex.lock()
                try {
                    val existing = viewingPhotos.map { it.first }.toSet()
                    val pending = uris.distinct().filter { it.toString() !in existing }
                    viewingImportTotal = pending.size
                    viewingImportCompleted = 0
                    var failed = 0
                    for (uri in pending) {
                        var fromEffect = false
                        val bitmap = try {
                            withContext(Dispatchers.IO) {
                                effectThumbFor(uri)?.also { fromEffect = true } ?: decodeSampled(uri, 640)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failed += 1
                            null
                        }
                        if (bitmap != null) {
                            if (cleared || viewingPhotos.any { it.first == uri.toString() }) bitmap.recycle()
                            else {
                                viewingPhotos = viewingPhotos + (uri.toString() to bitmap)
                                // 记下这张是效果图，观片台据此标「成品」，放大时也沿用效果而不是退回原图。
                                if (fromEffect) effectThumbKeys = effectThumbKeys + uri.toString()
                            }
                        }
                        viewingImportCompleted += 1
                    }
                    if (failed > 0) toast("有 $failed 张照片无法读取，请重新选择")
                } finally {
                    viewingImportMutex.unlock()
                }
            } finally {
                viewingImportRequests -= 1
            }
        }
    }

    /** Remove only the viewing-stand entry; the original photo is untouched. */
    fun removeViewingPhoto(key: String) {
        discardGalleryPreview(Uri.parse(key))
        effectThumbKeys = effectThumbKeys - key
        viewingPhotos = viewingPhotos.filterNot { it.first == key }
    }

    private fun discardGalleryPreview(uri: Uri) {
        galleryPreviewJobs.remove(uri)?.cancel()
        galleryPreviews = galleryPreviews - uri
        galleryPreviewErrors = galleryPreviewErrors - uri
    }

    /** Decode a detail-sized preview on demand without opening an editor render session. */
    fun loadGalleryPreview(uri: Uri) {
        if (galleryPreviews.containsKey(uri) || galleryPreviewJobs[uri]?.isActive == true) return
        if (uri !in photos && viewingPhotos.none { it.first == uri.toString() }) return
        galleryPreviewErrors = galleryPreviewErrors - uri
        val request = viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    galleryPreviewDecodeMutex.withLock { decodeSampled(uri, 1400) }
                }
                if (cleared || (uri !in photos && viewingPhotos.none { it.first == uri.toString() })) {
                    bitmap.recycle()
                } else if (!galleryPreviews.containsKey(uri)) {
                    val next = LinkedHashMap(galleryPreviews)
                    next[uri] = bitmap
                    val evicted = next.keys.take((next.size - GALLERY_PREVIEW_CACHE_SIZE).coerceAtLeast(0))
                    evicted.forEach(next::remove)
                    galleryPreviews = next
                } else {
                    bitmap.recycle()
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                if (!cleared && (uri in photos || viewingPhotos.any { it.first == uri.toString() })) {
                    galleryPreviewErrors = galleryPreviewErrors + (uri to "原照片可能已移走，或访问权限已失效。")
                }
            } finally {
                if (galleryPreviewJobs[uri] === currentCoroutineContext()[Job]) galleryPreviewJobs.remove(uri)
            }
        }
        galleryPreviewJobs[uri] = request
    }

    /** The wall requests at most four visible frames, in order of proximity.
     * Await each existing preview request instead of launching a decode per frame.
     * Leaving or moving the viewport cancels the remaining sequence.
     */
    suspend fun loadGalleryWallDetails(uris: List<Uri>) {
        for (uri in uris.distinct().take(4)) {
            if (!currentCoroutineContext().isActive) return
            if (uri == currentUri || developedPreviews.containsKey(uri)) continue
            loadGalleryPreview(uri)
            galleryPreviewJobs[uri]?.join()
        }
    }

    // Layer 2 调色只重转换缓存的渲染结果，不触发引擎重渲染。
    fun scheduleGrade(commit: Boolean = true) {
        if (isApplyingEdits) return
        if (!hasPhoto || isExporting || isBatchExporting) return
        if (commit) recordHistory()
        dirty = true
        if (commit) currentUri?.let { EditStore.save(app, it, committedParams()) }
        gradeRequest++
        if (wbJob?.isActive == true) return
        wbJob = viewModelScope.launch {
            // One worker finishes a frame while input replaces the next request. A trailing
            // debounce cancelled every frame during continuous drags, starving the preview.
            do {
                delay(16)
                if (!hasPhoto || isExporting || isBatchExporting) return@launch
                val request = gradeRequest
                val pending = pendingPreview?.takeIf { it.photo == photoGeneration && it.revision == renderGeneration }
                val result = pending?.result ?: lastResult ?: return@launch
                val editParams = EditSnapshot.capture(params).toParams()
                val colorSpace = pending?.colorSpace ?: resultColorSpace
                val generation = ++gradeGeneration
                val photo = photoGeneration
                val revision = renderGeneration
                var computed: Bitmap? = null
                var published = false
                try {
                    val (bitmap, counts) = withContext(Dispatchers.Default) {
                        val task = currentCoroutineContext()
                        convertPreview(result, editParams, colorSpace) { task.ensureActive() }.also { computed = it.first }
                    }
                    if (photo == photoGeneration && revision == renderGeneration && generation == gradeGeneration &&
                        !isExporting && !isBatchExporting &&
                        (lastResult === result || pendingPreview?.result === result)) {
                        resultColorSpace = colorSpace
                        rendered = com.spektralab.android.core.model.DateImprintPaint.apply(bitmap, editParams)
                        histogram = counts
                        lastResult = result
                        pending?.renderRequest?.let { publishedRenderRequest = it }
                        if (pendingPreview?.result === result) pendingPreview = null
                        rememberDevelopedPreview(bitmap)
                        published = true
                    }
                } finally {
                    if (!published) computed?.recycle()
                }
            } while (request != gradeRequest)
        }
    }

    private var wheelStart: com.spektralab.android.core.color.ColorWheels? = null
    private var wheelStartUnreadable: String? = null

    fun beginWheelEdit() {
        if (!hasPhoto || busy || isExporting || isBatchExporting) return
        if (wheelStart == null) {
            wheelStart = params.colorWheels
            wheelStartUnreadable = params.unreadableColorWheels
            lastHistoryKey = null
        }
    }

    fun changeWheels(wheels: com.spektralab.android.core.color.ColorWheels) {
        if (!hasPhoto || busy || isExporting || isBatchExporting) return
        params.colorWheels = wheels
        params.unreadableColorWheels = null
        scheduleGrade(commit = false)
    }

    fun finishWheelEdit(cancel: Boolean = false) {
        val start = wheelStart ?: return
        wheelStart = null
        if (cancel) {
            params.colorWheels = start
            params.unreadableColorWheels = wheelStartUnreadable
        }
        scheduleGrade(commit = !cancel && (params.colorWheels != start ||
            params.unreadableColorWheels != wheelStartUnreadable))
        wheelStartUnreadable = null
    }

    private var mixerStart: com.spektralab.android.core.color.ChannelMixer? = null
    private var mixerStartUnreadable: String? = null

    fun beginMixerEdit() {
        if (!hasPhoto || busy || isExporting || isBatchExporting) return
        if (mixerStart == null) {
            mixerStart = params.channelMixer
            mixerStartUnreadable = params.unreadableChannelMixer
            lastHistoryKey = null
        }
    }

    fun changeMixer(mixer: com.spektralab.android.core.color.ChannelMixer) {
        if (!hasPhoto || busy || isExporting || isBatchExporting) return
        params.channelMixer = mixer
        params.unreadableChannelMixer = null
        scheduleGrade(commit = false)
    }

    fun finishMixerEdit(cancel: Boolean = false) {
        val start = mixerStart ?: return
        mixerStart = null
        if (cancel) {
            params.channelMixer = start
            params.unreadableChannelMixer = mixerStartUnreadable
        }
        scheduleGrade(commit = !cancel && (params.channelMixer != start ||
            params.unreadableChannelMixer != mixerStartUnreadable))
        mixerStartUnreadable = null
    }

    private var colorStart: com.spektralab.android.core.color.ColorAdjustments? = null
    private var colorStartUnreadable: String? = null

    fun beginColorEdit() {
        if (colorStart == null) {
            colorStart = params.colorAdjustments
            colorStartUnreadable = params.unreadableColorAdjustments
            lastHistoryKey = null
        }
    }

    fun changeColors(colors: com.spektralab.android.core.color.ColorAdjustments) {
        if (busy || isExporting || isBatchExporting) return
        params.colorAdjustments = colors
        params.unreadableColorAdjustments = null
        scheduleGrade(commit = false)
    }

    fun finishColorEdit(cancel: Boolean = false) {
        val start = colorStart ?: return
        colorStart = null
        if (cancel) {
            params.colorAdjustments = start
            params.unreadableColorAdjustments = colorStartUnreadable
        }
        scheduleGrade(commit = !cancel && (params.colorAdjustments != start ||
            params.unreadableColorAdjustments != colorStartUnreadable))
        colorStartUnreadable = null
    }

    private var curveStartUnreadable: String? = null
    private var curveStart: com.spektralab.android.core.color.ToneCurves? = null

    fun beginCurveEdit() {
        if (curveStart == null) {
            curveStart = params.toneCurves
            curveStartUnreadable = params.unreadableToneCurves
            lastHistoryKey = null
        }
    }

    fun changeCurves(curves: com.spektralab.android.core.color.ToneCurves) {
        if (isExporting || isBatchExporting) return
        params.toneCurves = curves
        params.unreadableToneCurves = null
        scheduleGrade(commit = false)
    }

    fun finishCurveEdit(cancel: Boolean = false) {
        val start = curveStart ?: return
        curveStart = null
        if (cancel) { params.toneCurves = start; params.unreadableToneCurves = curveStartUnreadable }
        scheduleGrade(commit = !cancel && (params.toneCurves != start || params.unreadableToneCurves != curveStartUnreadable))
        curveStartUnreadable = null
    }

    // 胶片前曲线（Decode 场景曲线）：作用于胶片模拟之前，是曝光域操作，改动走全量渲染。
    private var sceneCurveStart: com.spektralab.android.core.color.SceneCurves? = null
    private var sceneCurveStartUnreadable: String? = null

    fun beginSceneCurveEdit() {
        if (sceneCurveStart == null && !params.filmOff && !isExporting && !isBatchExporting) {
            sceneCurveStart = params.sceneCurves
            sceneCurveStartUnreadable = params.unreadableSceneCurves
            lastHistoryKey = null
        }
    }

    fun changeSceneCurves(curves: com.spektralab.android.core.color.ToneCurves) {
        if (params.filmOff || isExporting || isBatchExporting || isApplyingEdits) return
        params.sceneCurves = com.spektralab.android.core.color.SceneCurves(curves)
        params.unreadableSceneCurves = null
        scheduleRender()
    }

    fun finishSceneCurveEdit(cancel: Boolean = false) {
        val start = sceneCurveStart ?: return
        sceneCurveStart = null
        if (cancel) {
            params.sceneCurves = start
            params.unreadableSceneCurves = sceneCurveStartUnreadable
        }
        sceneCurveStartUnreadable = null
        scheduleRender()
    }

    // 相机白平衡（解码侧，仅 RAW）：Kelvin/tint 变化后去抖重新解码 + 重开会话。
    fun scheduleCameraWb() {
        if (!currentIsRaw || !hasPhoto) return
        recordHistory()
        currentUri?.let { EditStore.save(app, it, committedParams()) }
        val uri = currentUri ?: return
        val photo = photoGeneration
        val editParams = EditSnapshot.capture(params).toParams()
        cameraWbJob?.cancel()
        cameraWbJob = viewModelScope.launch {
            delay(300)
            engineTasks.onEngine {
                if (photo != photoGeneration) return@onEngine
                try {
                    decodeRawAndOpen(uri, photo, editParams)
                    append("RAW 白平衡: ${if (params.cameraWbCustom) "${params.cameraWbKelvin.toInt()}K / tint ${params.cameraWbTint.toInt()}" else "as-shot"}")
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    withContext(Dispatchers.Main.immediate) {
                        if (photo == photoGeneration) fail("${ex::class.simpleName}: ${ex.message}")
                    }
                }
            }
        }
    }

    // 恢复 as-shot 白平衡。
    fun resetCameraWb() {
        params.cameraWbCustom = false
        params.cameraWbTemperatureAsShot = false
        params.cameraWbTintAsShot = false
        scheduleCameraWb()
    }

    // 直接用预设色温/色调覆盖相机白平衡（日光、阴天、钨丝灯等）。
    internal fun applyRawWhiteBalancePreset(kelvin: Float, tint: Float) {
        if (!currentIsRaw || !hasPhoto || busy || isExporting || isBatchExporting) return
        lastHistoryKey = null
        params.cameraWbCustom = true
        params.cameraWbTemperatureAsShot = false
        params.cameraWbTintAsShot = false
        params.cameraWbKelvin = kelvin
        params.cameraWbTint = tint
        scheduleCameraWb()
    }

    // 切换镜头校正（命中镜头配置时）。重新解码 + 应用/撤销 + 重开。
    fun toggleLensCorrection() {
        if (matchedLensProfile == null) {
            fail("未找到这支镜头的校正配置，已保留原始画面")
            return
        }
        lensCorrectionActive = !lensCorrectionActive
        val uri = currentUri ?: return
        if (!currentIsRaw || !hasPhoto) return
        val photo = photoGeneration
        val editParams = EditSnapshot.capture(params).toParams()
        cameraWbJob?.cancel()
        cameraWbJob = viewModelScope.launch {
            engineTasks.onEngine {
                if (photo != photoGeneration) return@onEngine
                try {
                    decodeRawAndOpen(uri, photo, editParams)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    withContext(Dispatchers.Main.immediate) {
                        if (photo == photoGeneration) fail("${ex::class.simpleName}: ${ex.message}")
                    }
                }
            }
        }
    }

    // 解码 RAW：应用自定义白平衡（若开启）+ 镜头校正（若命中且开启）。
    private suspend fun decodeRaw(uri: Uri, editParams: EditorParams = params, halfSize: Boolean = true, photo: Long = photoGeneration): RawDecodeResult {
        val cachedXyz = if (uri == currentUri && photo == photoGeneration) rawCamXyz else null
        val correctLens = lensCorrectionActive
        val task = currentCoroutineContext()
        val cancellation = RawDecodeCancellation { cleared || photo != photoGeneration || !task.isActive }
        val headerXyz = cachedXyz ?: if (editParams.cameraWbCustom) {
            RawDecoder.readCameraMatrix(app, uri, cancellation)
        } else null
        val initial = if (headerXyz == null) {
            RawDecoder.decode(app, uri, halfSize = halfSize, cancellation = cancellation)
        } else null
        val cameraXyz = headerXyz ?: initial!!.camXyz
        val userMul = if (editParams.cameraWbCustom) {
            val mul = FloatArray(4)
            if (CameraWhiteBalance.multipliersFor(cameraXyz, editParams.cameraWbKelvin.toDouble(), editParams.cameraWbTint.toDouble(), mul)) mul else null
        } else null
        val raw = if (userMul != null) {
            RawDecoder.decode(app, uri, halfSize = halfSize, userMul = userMul, cancellation = cancellation)
        } else initial ?: RawDecoder.decode(app, uri, halfSize = halfSize, cancellation = cancellation)
        LensProfileStore.load(app)
        val lensProfile = LensProfileStore.match(raw.make, raw.lens, raw.focal)
        withContext(Dispatchers.Main.immediate) {
            if (uri == currentUri && photo == photoGeneration) {
                rawCamXyz = raw.camXyz
                cameraAsShot = runCatching { CameraWhiteBalance.asShot(raw.camXyz, raw.camMul) }.getOrNull()
                matchedLensProfile = lensProfile
                matchedLens = lensProfile?.model
                // 自定义白平衡要靠文件里的 cam_xyz 才能算乘数；缺失时是静默 as-shot，
                // 用户会以为滑块没反应，这里明确说出来。
                if (editParams.cameraWbCustom && userMul == null) {
                    append("这枚 RAW 没有可用的相机色彩矩阵，自定义白平衡不生效（保持拍摄白平衡）")
                }
            }
        }
        if (correctLens && lensProfile != null) {
            RawDecoder.applyLensCorrection(raw.linearProPhoto, raw.width, raw.height, lensProfile)
        }
        return raw
    }

    // Native export input avoids full-frame Java arrays without reducing the requested resolution.
    private fun openExportSource(e: EngineClient, uri: Uri, editParams: EditorParams, size: Int,
                                 colorSpace: String, options: ExportOptions) {
        sourceExif = readExif(uri, options.keepLocation)
        // 授权还在时把位置存一份，导出时原片读不到就靠它。
        captureGeo(uri)
        // The interactive preview remains on screen, but its GPU session need not overlap source decoding.
        e.closeSession()
        if (RawDecoder.isRaw(app, uri, queryDisplayName(uri))) {
            val cancellation = RawDecodeCancellation { cleared || exportCancelRequested }
            val cachedXyz = (if (uri == currentUri) rawCamXyz else null) ?: if (editParams.cameraWbCustom) {
                RawDecoder.readCameraMatrix(app, uri, cancellation)
            } else null
            fun multipliers(xyz: FloatArray?): FloatArray? {
                if (!editParams.cameraWbCustom || xyz == null) return null
                val mul = FloatArray(4)
                return if (CameraWhiteBalance.multipliersFor(xyz, editParams.cameraWbKelvin.toDouble(),
                    editParams.cameraWbTint.toDouble(), mul)) mul else null
            }
            var raw = measureExport("raw_decode") {
                RawDecoder.decodeForExport(app, uri, size, multipliers(cachedXyz), cancellation)
            }
            try {
                if (editParams.cameraWbCustom && cachedXyz == null) {
                    val mul = multipliers(raw.camXyz)
                    if (mul != null) {
                        raw.close()
                        raw = RawDecoder.decodeForExport(app, uri, size, mul, cancellation)
                    }
                }
                LensProfileStore.load(app)
                val profile = LensProfileStore.match(raw.make, raw.lens, raw.focal)
                if (options.correctLens && profile != null) measureExport("raw_lens_correction") { raw.correctLens(profile) }
                raw.resize(size)
                checkExportCancellation()
                measureExport("engine_input") {
                    e.openRaw(raw, editParams.openJson(raw.width, raw.height, colorSpace, "ProPhoto RGB"))
                }
            } finally { raw.close() }
        } else {
            val bitmap = measureExport("image_decode") { decodeBitmapForExport(uri, size) }
            try {
                checkExportCancellation()
                e.openBitmap(bitmap, editParams.openJson(bitmap.width, bitmap.height, colorSpace))
            } finally { bitmap.recycle() }
        }
        android.util.Log.i("SpkExport", "input opened; ART limit=${Runtime.getRuntime().maxMemory()}; ${e.memoryReport()}")
    }

    // 解码 RAW → 显示源图 → 开会话。
    private suspend fun decodeRawAndOpen(uri: Uri, photo: Long = photoGeneration, editParams: EditorParams = params.copy()) {
        val raw = decodeRaw(uri, editParams, photo = photo)
        if (photo != photoGeneration || currentUri != uri) return
        val task = currentCoroutineContext()
        // Hold/split comparison shows the camera's display rendition, not untonemapped sensor RGB.
        // Keep it stable across white-balance and lens edits; only the film input follows those edits.
        val original = sourceBitmap ?: com.spektralab.android.data.image.OriginalPreview.raw(app, uri,
            RawDecodeCancellation { cleared || photo != photoGeneration || !task.isActive }) ?: run {
            val argb = ColorPipeline.proPhotoLinearToSrgbArgb(raw.linearProPhoto, raw.width, raw.height)
            Bitmap.createBitmap(argb, raw.width, raw.height, Bitmap.Config.ARGB_8888)
        }
        withContext(Dispatchers.Main.immediate) {
            if (photo != photoGeneration || currentUri != uri) {
                if (original !== sourceBitmap) original.recycle()
                return@withContext
            }
            sourceBitmap = original
            append("RAW 解码: ${raw.width}×${raw.height} · ${raw.make} ${raw.model}" +
                (matchedLens?.let { " · 镜头校正: $it" } ?: ""))
            openFrame(raw.linearProPhoto, raw.width, raw.height, "ProPhoto RGB")
        }
    }

    // Keep one render in flight; coalesce continuous input into the next complete frame.
    fun scheduleRender() = scheduleRender(immediate = false)

    private fun scheduleRender(immediate: Boolean) {
        if (isApplyingEdits) return
        if (isExporting || isBatchExporting) return
        recordHistory()
        val e = engine ?: return
        if (!hasPhoto) return
        dirty = true
        renderRequest++
        if (immediate) {
            renderJob?.cancel()
            e.cancel()
        } else if (renderJob?.isActive == true) return
        renderJob = viewModelScope.launch {
            do {
                if (!immediate) delay(16)
                if (!hasPhoto || isExporting || isBatchExporting) return@launch
                val request = renderRequest
                val generation = ++renderGeneration
                pendingPreview = null
                val photo = photoGeneration
                val uri = currentUri
                val editParams = EditSnapshot.capture(params).toParams()
                val delta = editParams.previewDeltaJson(previewW, previewH)
                stockPreviewJob?.cancel()
                wbJob?.cancel()
                gradeGeneration++
                engineTasks.onEngine {
                    if (photo != photoGeneration || generation != renderGeneration) return@onEngine
                    try {
                        // 胶片模拟开关 / 透视校正改变的是会话绑定，必须用同一份源像素重开会话，
                        // 不能走 setChangedParams（它会拒绝几何变更）。
                        if (e.requiresSourceReopen(delta)) {
                            val pixels = previewLinear
                            if (pixels != null) {
                                e.open(pixels, previewW, previewH, 3,
                                    editParams.previewOpenJson(previewW, previewH, inputColorSpace = previewInputColorSpace))
                            } else e.setChangedParams(delta)
                        } else e.setChangedParams(delta)
                        // UI post edits may be newer than this debounced native request. Keep saves
                        // ordered with those edits instead of writing the older render snapshot.
                        withContext(Dispatchers.Main.immediate) {
                            if (photo == photoGeneration && generation == renderGeneration && uri == currentUri) {
                                uri?.let { EditStore.save(app, it, committedParams()) }
                            }
                        }
                        // 拍摄侧参数（曝光补偿/胶片/画幅/滤镜等）改动必须走全量渲染重建负片，
                        // 否则 reprint 会复用旧负片，曝光补偿这类 SHOOT 参数就不生效。
                        val r = e.renderFull("live")
                        if (photo != photoGeneration || generation != renderGeneration) return@onEngine
                        withContext(Dispatchers.Main.immediate) { stockPreviewJob?.cancel() }
                        updateResult(r, photo = photo, revision = generation, editParams = editParams, request = request)
                        append("render: ${r.width}×${r.height}  ${r.elapsedMs}ms  reprint=${r.reprint}")
                        // 一帧落定后再读配光：只读展示，材料/裁剪变了才重算。
                        withContext(Dispatchers.Main.immediate) { refreshMetering() }
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (_: OutOfMemoryError) {
                        withContext(Dispatchers.Main.immediate) {
                            if (photo == photoGeneration && generation == renderGeneration) {
                                fail("编辑预览内存不足，已保留当前画面。请重新打开当前照片后重试")
                            }
                        }
                    } catch (ex: Exception) {
                        withContext(Dispatchers.Main.immediate) {
                            if (photo == photoGeneration && generation == renderGeneration &&
                                ex.message?.contains("cancel", ignoreCase = true) != true) fail("${ex::class.simpleName}: ${ex.message}")
                        }
                    }
                }
            } while (request != renderRequest)
        }
    }

    // Explicit stock picks follow the PC's declared paper; restoring an edit keeps its custom pair.
    fun selectFilm(stockId: String) {
        if (busy || isExporting || isBatchExporting) return
        val film = films.firstOrNull { it.id == stockId } ?: return
        fun compatiblePaper(id: String?) = papers.firstOrNull { it.id == id && StockCatalog.canPrint(stockId, it.id) }?.id
        val paper = compatiblePaper(film.targetPrint) ?: compatiblePaper(params.paperId)
            ?: papers.firstOrNull { StockCatalog.canPrint(stockId, it.id) }?.id ?: return
        val model = if (stockId == "cinestill_800t")
            com.spektralab.android.core.model.HalationModel.BACK_REFLECTION.version else params.halationModelVersion
        if (stockId == params.filmId && paper == params.paperId && model == params.halationModelVersion) return
        params.filmId = stockId
        params.paperId = paper
        params.halationModelVersion = model
        // 换了胶片就重新按这卷的上限收一次颗粒强度：超过上限的那段本来就没有效果，
        // 留在滑块外只会让人以为数值还生效。
        val ceiling = EditorParams.grainCeiling(stockId)
        if (params.grainAmount > ceiling) params.grainAmount = ceiling
        scheduleRender(immediate = true)
    }

    // Paper LUTs provide temporary feedback; the scheduled reprint remains authoritative.
    fun selectPaper(stockId: String) {
        if (busy || isExporting || isBatchExporting || stockId == params.paperId ||
            papers.none { it.id == stockId } || !StockCatalog.canPrint(params.filmId, stockId)) return
        params.paperId = stockId
        val e = engine ?: return
        if (!hasPhoto) return
        dirty = true
        scheduleRender()
        val photo = photoGeneration
        val revision = renderGeneration
        val editParams = EditSnapshot.capture(params).toParams()
        stockPreviewJob?.cancel()
        stockPreviewJob = viewModelScope.launch {
            engineTasks.onEngine {
                if (photo != photoGeneration || revision != renderGeneration) return@onEngine
                try {
                    val r = e.previewStockLut(stockId, "live")
                    updateResult(r, photo = photo, revision = revision, editParams = editParams)
                } catch (ex: SpkException) {
                    // 胶片不配对时 LUT 预览可能被拒；忽略，等 reprint。
                }
            }
        }
    }

    // 一键配光：solve("both") 求曝光 + 放大机中性滤色包。
    // 引擎默认 auto_exposure=true + neutral_print_filters_from_database=true，
    // 曝光与中性滤色包本就自动解析；这里只重新测光并把用户手动 shift 归零后重印
    // （与桌面版 solveNow 一致：不把求解 EV 写回 exposure_compensation_ev，避免双重曝光）。
    fun solve() = runNativeAnalysis("metering", { solve("both") }) { root ->
        // 在主线程写 Compose 状态：手动 shift 归零；曝光/中性滤色由引擎自动解析。
        val sp = root.optJSONObject("solved_params")
        val ev = sp?.optDouble("exposure_compensation_ev", 0.0) ?: 0.0
        val m = sp?.optDouble("m_filter_neutral", 0.0) ?: 0.0
        val y = sp?.optDouble("y_filter_neutral", 0.0) ?: 0.0
        params.mFilterShift = 0f
        params.yFilterShift = 0f
        append("配光: 测光 EV %+.2f · 中性滤色 M %.0f / Y %.0f".format(ev, m, y))
        scheduleRender()
    }
    // 配光读数（只读）：开图后自动测一次，材料/测光方式/裁剪/曝光补偿/手动偏移变了才重算。
    // 它只显示引擎算出的配光点，绝不写回任何参数，也不改画面。
    var meteringReadout by mutableStateOf<String?>(null)
    private var meteringJob: Job? = null
    private var meteringKey: String? = null

    private fun meteringSignature(): String = listOf(
        params.filmId, params.paperId, params.autoExposureMethod, params.autoExposure.toString(),
        params.filmFormatMm, params.cropX, params.cropY, params.cropW, params.cropH,
        params.exposureEv, params.mFilterShift, params.yFilterShift, params.overscanActive.toString(),
    ).joinToString("|")

    internal fun refreshMetering() {
        val target = engine ?: return
        if (params.filmOff) { meteringReadout = null; meteringKey = null; return }
        if (!hasPhoto || isExporting || isBatchExporting || isApplyingEdits) return
        if (previewW <= 0 || previewH <= 0) return
        val key = meteringSignature()
        if (key == meteringKey) return
        val photo = photoGeneration
        val uri = currentUri
        val width = previewW
        val height = previewH
        val snapshot = EditSnapshot.capture(params)
        val mShift = params.mFilterShift
        val yShift = params.yFilterShift
        val autoExposure = params.autoExposure
        meteringJob?.cancel()
        meteringJob = viewModelScope.launch {
            val reply = try {
                engineTasks.onEngine("metering") {
                    if (photo != photoGeneration || uri != currentUri || busy) return@onEngine null
                    target.setChangedParams(snapshot.toParams().deltaJson(width, height))
                    target.solve("both")
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (_: Exception) {
                return@launch
            } ?: return@launch
            if (photo != photoGeneration || uri != currentUri) return@launch
            val solved = runCatching { JSONObject(reply).optJSONObject("solved_params") }.getOrNull() ?: return@launch
            val ev = solved.optDouble("exposure_compensation_ev", 0.0)
            val m = solved.optDouble("m_filter_neutral", 0.0)
            val y = solved.optDouble("y_filter_neutral", 0.0)
            var text = "配光 %s · EV %+.2f · 中性滤色 M %02.0f / Y %02.0f"
                .format(if (autoExposure) "自动" else "手动测光", ev, m, y)
            if (mShift != 0f || yShift != 0f) {
                text += " · 手动偏移 M %+.1f / Y %+.1f".format(mShift, yShift)
            }
            meteringKey = key
            meteringReadout = text
        }
    }

    /** 只清手动滤色偏移（不碰曝光）；清完由下一帧重算读数。 */
    internal fun clearFilterShifts() {
        if (!hasPhoto) return
        if (params.mFilterShift == 0f && params.yFilterShift == 0f) {
            toast("手动滤色偏移已经是 0")
            return
        }
        params.mFilterShift = 0f
        params.yFilterShift = 0f
        append("已清除手动滤色偏移")
        scheduleRender()
    }

    // 测量场景宽容度：spk_scene_latitude 返回 medium/scene/suggested/fit。
    // 展示建议回拉量，并在 fit 有效时保存 params_delta 供「应用」提交。
    fun measureLatitude() {
        if (!nativeAnalysisAllowed()) return
        latitudeDelta = null
        latitudeCanApply = false
        runNativeAnalysis("latitude", { sceneLatitude(null) }) { root ->
            val suggested = root.optJSONObject("suggested")
            val scene = root.optJSONObject("scene")
            val fit = root.optJSONObject("fit")
            val hi = suggested?.optDouble("highlight_pull_back", 0.0) ?: 0.0
            val sh = suggested?.optDouble("shadow_pull_back", 0.0) ?: 0.0
            val p1 = scene?.optDouble("p1", 0.0) ?: 0.0
            val p99 = scene?.optDouble("p99", 0.0) ?: 0.0
            val valid = fit?.optBoolean("valid", false) ?: false
            val delta = fit?.optJSONObject("params_delta")
            latitudeDelta = if (valid) delta else null
            latitudeCanApply = valid && delta != null
            latitudeReadout = "高光回拉 %+.2f stop · 阴影回拉 %+.2f stop · 场景 p1 %.1f / p99 %.1f".format(hi, sh, p1, p99)
            // 介质自身的宽容度（引擎按当前胶片/相纸/放大机实测）：两端都在范围内时，
            // 置位是一条恒等曲线，应用后画面确实一个像素都不会变，这里提前说清楚。
            val mediumHigh = root.optJSONObject("medium")?.optDouble("highlight_ev", Double.NaN) ?: Double.NaN
            val mediumLow = root.optJSONObject("medium")?.optDouble("shadow_ev", Double.NaN) ?: Double.NaN
            if (!mediumHigh.isNaN() && !mediumLow.isNaN()) {
                latitudeReadout = "$latitudeReadout\n介质宽容度 %+.2f … %+.2f stop".format(mediumLow, mediumHigh)
            }
            if (valid && hi <= 0.0 && sh <= 0.0) {
                latitudeReadout = "$latitudeReadout\n场景两端都在介质内：无需置位，应用也不会改变画面"
            }
            if (!valid) {
                val issue = fit?.optJSONArray("issues")?.optJSONObject(0)?.optString("message")
                if (!issue.isNullOrEmpty()) latitudeReadout = "$latitudeReadout\n拟合失败: $issue"
            }
            append(latitudeReadout!!)
        }
    }

    private fun nativeAnalysisAllowed() = !params.filmOff && hasPhoto && !busy && !isExporting && !isBatchExporting && !isApplyingEdits
    /** Bind read-only native analysis to the exact photo and committed settings that requested it. */
    private fun runNativeAnalysis(kind: String, query: EngineClient.() -> String, apply: (JSONObject) -> Unit) {
        val target = engine ?: return
        if (!nativeAnalysisAllowed()) return
        val photo = photoGeneration
        val uri = currentUri
        val snapshot = EditSnapshot.capture(committedParams())
        val width = previewW
        val height = previewH
        busy = true
        viewModelScope.launch {
            try {
                val reply = engineTasks.onEngine(kind) {
                    if (photo != photoGeneration || uri != currentUri) return@onEngine null
                    // A slider's latest request may still be queued when analysis is tapped.
                    target.setChangedParams(snapshot.toParams().deltaJson(width, height))
                    target.query()
                } ?: return@launch
                if (photo == photoGeneration && uri == currentUri && !isExporting && !isBatchExporting &&
                    snapshot == EditSnapshot.capture(committedParams())) apply(JSONObject(reply))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (photo == photoGeneration && uri == currentUri) fail("${failure::class.simpleName}: ${failure.message}")
            } finally {
                // A previous photo must never clear the new photo loader's busy state.
                if (photo == photoGeneration && uri == currentUri) busy = false
            }
        }
    }

    // 应用测量得到的场景置位（fit.params_delta → EditorParams → set_params + 重显影）。
    fun applyLatitude() {
        val delta = latitudeDelta ?: return
        if (!nativeAnalysisAllowed()) return
        params.sceneLatitudeActive = delta.optBoolean("scene_latitude_active", false)
        params.sceneLatitudeNorm = delta.optString("scene_latitude_norm", "power")
        params.sceneLatitudeHighlightKnee = delta.optDouble("scene_latitude_highlight_knee", 2.0).toFloat()
        params.sceneLatitudeHighlightRoom = delta.optDouble("scene_latitude_highlight_room", 0.0).toFloat()
        params.sceneLatitudeShadowKnee = delta.optDouble("scene_latitude_shadow_knee", -2.0).toFloat()
        params.sceneLatitudeShadowRoom = delta.optDouble("scene_latitude_shadow_room", 0.0).toFloat()
        params.sceneLatitudeRolloff = delta.optDouble("scene_latitude_rolloff", 2.0).toFloat()
        params.sceneLatitudeMaxLift = delta.optDouble("scene_latitude_max_lift", 4.0).toFloat()
        latitudeDelta = null
        latitudeCanApply = false
        // 置位只在「场景两端超出这张相纸的宽容度」时才是一条真曲线；
        // 落在介质范围内的画面引擎会整节点跳过（恒等），这里必须说清楚，否则用户以为坏了。
        append(
            if (params.sceneLatitudeActive)
                "已应用场景置位：高光/阴影两端按实测回拉到介质边界内"
            else "本场景落在介质宽容度内，无需置位（曲线为恒等，画面不变）"
        )
        scheduleRender()
    }

    fun openFrame(linear: FloatArray, w: Int, h: Int, inputColorSpace: String = "sRGB") {
        previewLinear = linear
        previewW = w
        previewH = h
        previewInputColorSpace = inputColorSpace
        isCropping = false
        dirty = false
        val frame = PendingFrame(linear, w, h, inputColorSpace, photoGeneration, ++renderGeneration, EditSnapshot.capture(params))
        val currentEngine = engine
        if (currentEngine == null) {
            pendingFrame = frame
            if (engineCreateJob?.isActive != true) createEngine()
            return
        }
        renderFrame(currentEngine, frame)
    }

    private fun renderFrame(targetEngine: EngineClient, frame: PendingFrame) {
        if (frame.photo != photoGeneration || frame.revision != renderGeneration) return
        val editParams = frame.edit.toParams()
        busy = true
        frameRenderJob?.cancel()
        frameRenderJob = viewModelScope.launch {
            try {
                engineTasks.onEngine {
                    if (frame.photo != photoGeneration || frame.revision != renderGeneration) return@onEngine
                    targetEngine.open(
                        frame.pixels,
                        frame.width,
                        frame.height,
                        3,
                        editParams.openJson(frame.width, frame.height, inputColorSpace = frame.inputColorSpace),
                    )
                    if (frame.photo != photoGeneration || frame.revision != renderGeneration) return@onEngine
                    val r = targetEngine.render("live")
                    updateResult(r, photo = frame.photo, revision = frame.revision, editParams = editParams)
                    withContext(Dispatchers.Main.immediate) {
                        if (frame.photo == photoGeneration && frame.revision == renderGeneration) {
                            hasPhoto = true
                            append("渲染: ${r.width}×${r.height}  ${r.elapsedMs}ms")
                        }
                    }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                if (frame.photo == photoGeneration && frame.revision == renderGeneration) fail("${ex::class.simpleName}: ${ex.message}")
            } finally {
                if (frame.photo == photoGeneration && frame.revision == renderGeneration) busy = false
            }
        }
    }

    // 取消当前进行中的渲染（跨线程安全，渲染在下个节点边界回退并返回 cancelled）。
    fun cancelRender() {
        if (isApplyingEdits) return
        if (isExportCommitCompleted) return
        if (isExporting || isBatchExporting) {
            if (isExporting && (renderProgress ?: 0f) >= 100f) return
            exportCancelRequested = true
            renderStage = "正在取消…"
        } else {
            renderJob?.cancel()
            wbJob?.cancel()
            stockPreviewJob?.cancel()
            pendingPreview = null
        }
        engine?.cancel()
    }

    // 应用一条导出方案。
    fun applyRecipe(recipe: ExportRecipe) {
        exportFormat = recipe.format
        exportRes = recipe.res
        exportColorSpace = recipe.colorSpace
        jpegQuality = recipe.jpegQuality.coerceIn(50, 100)
        exportKeepCapture = recipe.keepCapture
        exportKeepLocation = recipe.keepLocation
        exportCustomSize = recipe.customSize
        exportNaming = recipe.naming.copy(tokens = recipe.naming.tokens.toList())
        exportTiffDeflate = recipe.tiffDeflate
    }

    // 把当前导出设置保存为命名配方（同名覆盖）。
    fun saveRecipe(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val recipe = ExportRecipe(trimmed, exportFormat, exportRes, exportColorSpace, jpegQuality, exportKeepCapture, exportKeepLocation,
            exportCustomSize, exportNaming.copy(tokens = exportNaming.tokens.toList()), exportTiffDeflate)
        recipes = recipes.filter { it.name != trimmed } + recipe
        RecipeStore.save(app, recipes)
        toast("已保存配方: $trimmed")
    }

    // 重命名一条已有配方：换了内容仍是同一条，只改名字。
    fun renameRecipe(recipe: ExportRecipe, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) { toast("配方名不能为空"); return false }
        if (recipes.any { it.name != recipe.name && it.name.equals(trimmed, true) }) {
            toast("已存在同名配方：$trimmed"); return false
        }
        recipes = recipes.map { if (it.name == recipe.name) it.copy(name = trimmed) else it }
        RecipeStore.save(app, recipes)
        toast("配方已重命名为 $trimmed")
        return true
    }

    // 删除一条配方。默认配方也可以删，删完还能重新保存出来。
    fun deleteRecipe(recipe: ExportRecipe) {
        recipes = recipes.filterNot { it.name == recipe.name }
        RecipeStore.save(app, recipes)
        toast("已删除配方: ${recipe.name}")
    }

    // 以导出分辨率重新解码原图 → 全分辨率渲染 → 存盘 → 恢复预览会话。
    // 渲染期间并发轮询进度，支持取消。
    private data class InterruptedPreview(val photo: Long, val render: Boolean, val grade: Boolean)

    @Volatile
    private var activeExportTask: com.spektralab.android.data.export.ExportCoordinator.Task? = null

    internal val exportTasks = ExportTasksController(app, viewModelScope)

    private fun exportStageLabel(stage: String) = when (stage) {
        "preparing" -> "准备导出"
        "decoding" -> "解码原图"
        "readback", "releasing" -> "准备保存"
        "encoding" -> "编码并保存文件"
        else -> "胶片冲印与相纸扫描"
    }

    private fun pendingPreviewWork() = InterruptedPreview(
        photoGeneration,
        renderJob?.isActive == true && publishedRenderRequest != renderRequest,
        wbJob?.isActive == true,
    )

    private fun resumePreview(work: InterruptedPreview) {
        if (cleared || work.photo != photoGeneration) return
        if (work.render) scheduleRender() else if (work.grade) scheduleGrade(commit = false)
    }

    fun export() {
        val e = engine ?: return
        if (!hasPhoto || isExporting || isBatchExporting || busy) return
        finishMixerEdit(cancel = true)
        finishWheelEdit(cancel = true)
        if (params.unreadableColorWheels != null) { fail(app.getString(com.spektralab.android.R.string.wheels_unsupported)); return }
        if (params.unreadableChannelMixer != null) { fail(app.getString(com.spektralab.android.R.string.mixer_unsupported)); return }
        if (params.unreadableColorAdjustments != null) { fail(app.getString(com.spektralab.android.R.string.color_unsupported)); return }
        if (params.unreadableToneCurves != null) { fail(app.getString(com.spektralab.android.R.string.curve_unsupported)); return }
        val interruptedPreview = pendingPreviewWork()
        val uri = currentUri ?: return
        val hostLease = try { com.spektralab.android.engine.host.RenderHostLease.begin(app) }
            catch (failure: Exception) { fail("无法启动导出服务: ${failure.message}"); return }
        isExporting = true
        exportCancelRequested = false
        isExportCommitCompleted = false
        val exportParams = EditSnapshot.capture(params).toParams()
        val options = captureExportOptions()
        renderProgress = 0f
        renderStage = "准备导出"
        viewModelScope.launch {
            engineTasks.run("export") {
                var needsRestore = false
                try {
                    val output = com.spektralab.android.data.export.PhotoExportPipeline(app).write(
                        uri, queryDisplayName(uri) ?: "photo", EditSnapshot.capture(exportParams), options,
                        ::checkExportCancellation,
                        beforeNative = {
                            withContext(engineDispatcher) {
                                needsRestore = true
                                e.park()
                                RenderAdmission.check(app)
                            }
                        },
                        progress = { stage, percent ->
                            renderStage = exportStageLabel(stage)
                            renderProgress = if (stage == "encoding") 96f
                                else max(renderProgress ?: 8f, 8f + percent * .86f).coerceAtMost(94f)
                        },
                    ) { result, metadata, task ->
                        activeExportTask = task
                        saveNativeResult(result, System.currentTimeMillis().toString(), exportParams, options, metadata)
                    }
                    // 导出后回读文件本身：位置到底写没写进去，立刻有结论。
                    val geoInFile = runCatching {
                        app.contentResolver.openInputStream(Uri.parse(output.path))?.use { ExifInterface(it) }
                            ?.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                    }.getOrNull()
                    append("导出成功: ${output.width}×${output.height} → ${output.path}" +
                        " · 位置=${if (!geoInFile.isNullOrBlank()) "有" else "无"}(开关=${exportKeepLocation})")
                    renderProgress = 100f
                    renderStage = "导出完成"
                    delay(250)
                } catch (_: ExportCancelled) {
                    append("导出已取消")
                } catch (_: OutOfMemoryError) {
                    fail("导出内存不足，请关闭其他应用或选择较小导出尺寸")
                } catch (ex: SpkException) {
                    if (ex.message?.contains("cancel", ignoreCase = true) == true) append("导出已取消")
                    else {
                        android.util.Log.e("SpkExport", "render/export failed; ${runCatching { e.memoryReport() }.getOrDefault("memory unavailable")}", ex)
                        fail("SpkException: ${ex.message}")
                    }
                } catch (ex: Exception) {
                    fail("${ex::class.simpleName}: ${ex.message}")
                } finally {
                    activeExportTask = null
                    val pl = previewLinear
                    if (pl != null && needsRestore) {
                        try {
                            withContext(NonCancellable) { hostLease.releaseDevice() }
                            withContext(NonCancellable + engineDispatcher) {
                                e.open(pl, previewW, previewH, 3, params.openJson(previewW, previewH, inputColorSpace = previewInputColorSpace))
                                // Keep the approved frame; rebuild the negative lazily on the next edit.
                            }
                        } catch (_: OutOfMemoryError) {
                            append("恢复编辑会话时内存不足，请重新打开当前照片")
                        } catch (ex: Exception) {
                            append("恢复预览失败: ${ex.message}")
                        }
                    }
                    renderProgress = null
                    renderStage = ""
                    exportCancelRequested = false
                    isExportCommitCompleted = false
                    isExporting = false
                    resumePreview(interruptedPreview)
                }
            }
        }.invokeOnCompletion { hostLease.close() }
    }

    internal fun retryExportTask(id: String) {
        val e = engine ?: return
        if (busy || isExporting || isBatchExporting || exportTasks.loading) return
        val original = exportTasks.entries.firstOrNull { it.id == id } ?: return
        if (!original.retryable) return
        val interrupted = pendingPreviewWork()
        exportTasks.clearError()
        val hostLease = try { com.spektralab.android.engine.host.RenderHostLease.begin(app) }
            catch (error: Exception) { exportTasks.failed(error.message ?: "无法启动导出服务"); return }
        isExporting = true; exportCancelRequested = false; isExportCommitCompleted = false
        renderProgress = 0f; renderStage = "校验原任务与原图"
        viewModelScope.launch {
            engineTasks.run("retry-export") {
                var needsRestore = false
                val store = com.spektralab.android.data.export.ExportJobStore(app)
                try {
                    com.spektralab.android.data.export.ExportCoordinator.get(app).recover()
                    val entry = withContext(Dispatchers.IO) { store.claimRetry(id) }
                    val frozen = checkNotNull(entry.request)
                    val uri = Uri.parse(checkNotNull(entry.sourceReference))
                    val output = com.spektralab.android.data.export.PhotoExportPipeline(app).write(
                        uri, frozen.sourceName, frozen.edit, frozen.options,
                        ::checkExportCancellation,
                        beforeNative = {
                            withContext(engineDispatcher) {
                                needsRestore = true
                                e.park()
                                RenderAdmission.check(app)
                            }
                        },
                        progress = { stage, percent ->
                            renderStage = exportStageLabel(stage)
                            renderProgress = max(renderProgress ?: 8f, 8f + percent * .86f).coerceAtMost(94f)
                        },
                        frozen = frozen,
                    ) { pixels, metadata, task ->
                        activeExportTask = task
                        saveNativeResult(pixels, frozen.submittedAt.toString(), frozen.edit.toParams(), frozen.options, metadata, uri)
                    }
                    append("任务重新导出: ${output.width}×${output.height} → ${output.path}")
                    renderProgress = 100f
                    renderStage = "导出完成"
                    delay(250)
                } catch (error: Throwable) {
                    if (error is kotlinx.coroutines.CancellationException) append("重新导出已取消")
                    else exportTasks.failed(if (exportCancelRequested) "重新导出已取消" else error.message ?: "重新导出失败")
                } finally {
                    activeExportTask = null
                    val pl = previewLinear
                    if (pl != null && needsRestore) {
                        try {
                            withContext(NonCancellable) { hostLease.releaseDevice() }
                            withContext(NonCancellable + engineDispatcher) {
                                e.open(pl, previewW, previewH, 3, params.openJson(previewW, previewH, inputColorSpace = previewInputColorSpace))
                            }
                        } catch (_: OutOfMemoryError) {
                            append("恢复编辑会话时内存不足，请重新打开当前照片")
                        } catch (ex: Exception) {
                            append("恢复预览失败: ${ex.message}")
                        }
                    }
                    renderProgress = null
                    renderStage = ""
                    exportCancelRequested = false
                    isExportCommitCompleted = false
                    isExporting = false
                    exportTasks.refresh()
                    resumePreview(interrupted)
                }
            }
        }.invokeOnCompletion { hostLease.close() }
    }

    internal fun cancelTableProjectExport() {
        if (!isExporting || isExportCommitCompleted) return
        exportCancelRequested = true
        renderStage = app.getString(com.spektralab.android.R.string.table_output_cancelling)
        if (tableNativeRendering) engine?.cancel()
    }

    /** Uses the same serialized owner, decoder, grade and cache as ordinary photo output. */
    internal fun exportTableProject(request: com.spektralab.android.core.model.TableExportRequest,
        onFinished: (Result<String>) -> Unit): Boolean {
        val e = engine ?: return false
        if (busy || isExporting || isBatchExporting || isApplyingEdits) return false
        prepareVersionBoundary()
        val interrupted = pendingPreviewWork()
        val activeUri = currentUri
        val activeParams = EditSnapshot.capture(committedParams()).toParams()
        val correctLens = lensCorrectionActive
        isExporting = true; exportCancelRequested = false; isExportCommitCompleted = false
        renderProgress = 0f; renderStage = app.getString(com.spektralab.android.R.string.table_output_preparing)
        viewModelScope.launch {
            engineTasks.run("export") {
                var session: com.spektralab.android.data.export.TableExportSession? = null
                var needsRestore = false
                var outcome: Result<String>? = null
                try {
                    withContext(Dispatchers.IO) {
                        session = com.spektralab.android.data.export.TableExportSession.prepare(app, request, ::checkExportCancellation) { index, total ->
                            renderStage = app.getString(com.spektralab.android.R.string.table_output_checking, index + 1, total)
                        }
                    }
                    val transaction = requireNotNull(session)
                    transaction.inputs.forEachIndexed { index, input ->
                        checkExportCancellation()
                        renderStage = app.getString(com.spektralab.android.R.string.table_output_rendering, index + 1, transaction.inputs.size)
                        renderProgress = index * 80f / transaction.inputs.size
                        var bitmap: Bitmap? = null
                        try {
                            if (input.params == null) {
                                withContext(Dispatchers.IO) {
                                    RenderAdmission.check(app)
                                    bitmap = decodeBitmapForExport(Uri.parse(input.source.uri), input.requestedEdge)
                                }
                            } else {
                                val p = input.params.toParams()
                                val geometry = JSONObject(p.openJson(input.evidence.width.coerceAtLeast(1), input.evidence.height.coerceAtLeast(1)))
                                val crop = minOf(geometry.optDouble("geometry_crop_w", 1.0), geometry.optDouble("geometry_crop_h", 1.0)).coerceAtLeast(.01)
                                val resolution = kotlin.math.ceil(input.requestedEdge / crop).toInt().coerceAtLeast(1)
                                check(resolution <= com.spektralab.android.data.export.TableExportSession.MAX_INPUT_EDGE) { "TABLE_EXPORT_CAPACITY" }
                                val options = ExportOptions("PNG", resolution, "sRGB", 100, false, false, correctLens)
                                val uri = Uri.parse(input.source.uri)
                                val cacheRequest = withContext(Dispatchers.IO) { renderCache.prepare(uri, p, options, ::checkExportCancellation) }
                                var lease: com.spektralab.android.data.cache.RenderDiskCache.Lease? = null
                                try {
                                    withContext(Dispatchers.IO) { lease = cacheRequest?.let { renderCache.acquire(it, ::checkExportCancellation) } }
                                    val cached = lease
                                    if (cached != null) withContext(Dispatchers.IO) {
                                        com.spektralab.android.data.export.TableExportSession.checkSourceSize(cached.width, cached.height)
                                        bitmap = com.spektralab.android.data.export.Rgba16Bitmap.convert(cached, grade(p), ::checkExportCancellation)
                                    } else withContext(engineDispatcher) {
                                        RenderAdmission.check(app); needsRestore = true
                                        openExportSource(e, uri, p, resolution, "sRGB", options)
                                        tableNativeRendering = true
                                        try { e.renderExportOnce().use { result ->
                                            checkExportCancellation(); e.closeSession()
                                            withContext(Dispatchers.IO) {
                                                if (cacheRequest != null) renderCache.store(cacheRequest, uri, result, ::checkExportCancellation)
                                                com.spektralab.android.data.export.TableExportSession.checkSourceSize(result.width, result.height)
                                                bitmap = com.spektralab.android.data.export.Rgba16Bitmap.convert(result, grade(p), ::checkExportCancellation)
                                            }
                                        } } finally { tableNativeRendering = false }
                                    }
                                } finally { lease?.close() }
                            }
                            withContext(Dispatchers.IO) { transaction.stage(input, requireNotNull(bitmap)) }
                        } finally { bitmap?.recycle() }
                    }
                    renderProgress = 85f; renderStage = app.getString(com.spektralab.android.R.string.table_output_composing)
                    val path = withContext(Dispatchers.IO) { transaction.publish {
                        isExportCommitCompleted = true; exportCancelRequested = false
                    } }
                    outcome = Result.success(path)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Throwable) {
                    if (failure !is Exception && failure !is OutOfMemoryError) throw failure
                    outcome = Result.failure(failure)
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { session?.close() }
                    if (needsRestore && !cleared) withContext(NonCancellable + engineDispatcher) {
                        runCatching {
                            e.closeSession()
                            previewLinear?.let { pl -> e.open(pl, previewW, previewH, 3,
                                activeParams.openJson(previewW, previewH, inputColorSpace = previewInputColorSpace)) }
                            sourceExif = activeUri?.let(::readExif)
                        }.onFailure { android.util.Log.e("TableExport", "Preview session restore failed", it) }
                    }
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        renderProgress = null; renderStage = ""; exportCancelRequested = false
                        isExportCommitCompleted = false; isExporting = false
                        if (!cleared) {
                            outcome?.let(onFinished)
                            resumePreview(interrupted)
                        }
                    }
                }
            }
        }
        return true
    }

    fun createEngine() {
        if (engine != null) return
        if (engineCreateJob?.isActive == true) return
        busy = true
        engineCreateJob = viewModelScope.launch {
            engineTasks.onEngine {
                try {
                    val created = EngineClient.create(app)
                    if (cleared) { created.close(); return@onEngine }
                    withContext(Dispatchers.Main.immediate) {
                        engine = created
                        append("引擎创建成功 · ${EngineClient.buildInfo()}")
                        val waitingFrame = pendingFrame
                        pendingFrame = null
                        if (waitingFrame != null) renderFrame(created, waitingFrame)
                        else if (photoLoadJob?.isActive != true) busy = false
                    }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    fail("${ex::class.simpleName}: ${ex.message}")
                    busy = false
                }
            }
        }
    }

    /** Invalidate before changing identity; cancellation alone cannot stop synchronous native work. */
    private fun invalidatePhotoWork(): Long {
        wheelStart?.let {
            params.colorWheels = it
            params.unreadableColorWheels = wheelStartUnreadable
        }
        wheelStart = null
        wheelStartUnreadable = null
        mixerStart?.let {
            params.channelMixer = it
            params.unreadableChannelMixer = mixerStartUnreadable
        }
        mixerStart = null
        mixerStartUnreadable = null
        colorStart?.let {
            params.colorAdjustments = it
            params.unreadableColorAdjustments = colorStartUnreadable
        }
        colorStart = null
        colorStartUnreadable = null
        curveStart?.let { params.toneCurves = it }
        curveStart = null
        smartApplyJob?.cancel()
        photoGeneration++
        renderGeneration++
        gradeGeneration++
        photoLoadJob?.cancel()
        pendingPreview = null
        frameRenderJob?.cancel()
        renderJob?.cancel()
        wbJob?.cancel()
        cameraWbJob?.cancel()
        stockPreviewJob?.cancel()
        developedPreviewJob?.cancel()
        effectThumbJob?.cancel()
        pendingFrame = null
        engine?.cancel()
        return photoGeneration
    }

    private fun clearPhotoState() {
        releaseCropBitmap()
        croppingFinished = false
        sceneCurveStart = null
        sceneCurveStartUnreadable = null
        paramsUri = null
        photoInfo = emptyList()
        dateImprintMetadata = DateImprintMetadata()
        rawCamXyz = null
        cameraAsShot = null
        sourceExif = null
        sourceBitmap = null
        rendered = null
        lastResult = null
        histogram = null
        previewLinear = null
        previewW = 0
        previewH = 0
        currentIsRaw = false
        matchedLensProfile = null
        matchedLens = null
        latitudeReadout = null
        latitudeDelta = null
        latitudeCanApply = false
        isCropping = false
        cropEditStart = null
        hasPhoto = false
        canUndo = false
        canRedo = false
        dirty = false
        errorMessage = null
        meteringReadout = null
        meteringKey = null
    }

    private fun retainThumbnailRequests(uris: Set<Uri>) {
        thumbnailJobs.keys.filterNot(uris::contains).forEach { uri ->
            thumbnailJobs.remove(uri)?.cancel()
        }
    }

    fun openPhoto(uri: Uri) {
        if (isApplyingEdits) return
        if (isExporting || isBatchExporting) return
        val photo = invalidatePhotoWork()
        currentUri = uri
        smartMessage=""
        clearPhotoState()
        params = EditorParams()
        busy = true
        val importStyle=smartStyle
        val autoDevelop=smartAutoEnabled
        photoLoadJob = viewModelScope.launch {
            try {
                engineTasks.onEngine {
                    if (photo != photoGeneration) return@onEngine
                    val saved = if(autoDevelop) ensureSmartParams(uri, importStyle, true) else EditStore.load(app, uri)
                    val rawName = queryDisplayName(uri)
                    val name = resolveSourceName(uri) ?: rawName
                    val exif = readExif(uri)
                    // 日期后背的数据源：EXIF 的拍摄时刻优先，读不到再用相册 DATE_TAKEN 兜底。
                    val imprintMetadata = DateImprintMetadata.from(exif, queryDateTakenMs(uri))
                    val imageBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    runCatching { app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, imageBounds) } }
                    val isRaw = RawDecoder.isRaw(app, uri, rawName)
                    withContext(Dispatchers.Main.immediate) metadata@{
                        if (photo != photoGeneration) return@metadata
                        dateImprintMetadata = imprintMetadata
                        photoInfo = buildList {
                            add("文件名" to (name ?: "未知"))
                            val width = (exif?.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) ?: 0).takeIf { it > 0 } ?: imageBounds.outWidth
                            val height = (exif?.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0) ?: 0).takeIf { it > 0 } ?: imageBounds.outHeight
                            if (width > 0 && height > 0) add("原始尺寸" to "$width × $height")
                            listOf("相机" to ExifInterface.TAG_MODEL, "镜头" to ExifInterface.TAG_LENS_MODEL,
                                "拍摄时间" to ExifInterface.TAG_DATETIME_ORIGINAL, "光圈" to ExifInterface.TAG_F_NUMBER,
                                "快门时间（秒）" to ExifInterface.TAG_EXPOSURE_TIME, "ISO" to ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                                "焦距（毫米）" to ExifInterface.TAG_FOCAL_LENGTH).forEach { (label, tag) ->
                                exif?.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { add(label to it) }
                            }
                        }
                        if (saved != null) {
                            params = saved
                            cropAspect = if (saved.cropW >= 1f && saved.cropH >= 1f) "原始" else "自定义"
                            append("已恢复上次编辑")
                        } else {
                            params = EditorParams()
                            resetGeometry()
                            // 片边默认关（和纯净原版一致）：新照片打开时不再擅自打开片边，日期由 App 画在画面内。
                            // 画幅编号也不再按整卷预排：留在 0 就是这一张自己随机到的那一号
                            // （EditorParams 新建时已经掷好，落在侧车里），用户之后开片边用的就是它。
                        }
                        // 日期后背默认开着：导入时就把这张照片的日期/参数填进去。
                        // 必须在上面 params 整对象被换掉（恢复侧车 / 新建默认参数）之后再做，
                        // 否则填进的是那个被丢弃的临时 params，首帧会退回「系统当天」，
                        // 关掉再打开日期后背才显示正确的拍摄日期。
                        imprintMetadata.applyTo(params)
                        seedHistory(uri, params.copy())
                        paramsUri = uri
                        sourceExif = exif
                        currentIsRaw = isRaw
                    }
                    if (photo != photoGeneration) return@onEngine
                    if (isRaw) {
                        decodeRawAndOpen(uri, photo)
                    } else {
                        val bmp = decodeSampled(uri, preserveColorSpace = true)
                        if (photo != photoGeneration) { bmp.recycle(); return@onEngine }
                        val linear = ColorPipeline.bitmapToLinearSrgb(bmp)
                        if (photo != photoGeneration) { bmp.recycle(); return@onEngine }
                        withContext(Dispatchers.Main.immediate) publish@{
                            if (photo != photoGeneration) { bmp.recycle(); return@publish }
                            sourceBitmap = bmp
                            append("解码: ${bmp.width}×${bmp.height}")
                            openFrame(linear, bmp.width, bmp.height)
                        }
                    }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: OutOfMemoryError) {
                if (photo == photoGeneration) {
                    fail("照片过大，当前设备内存不足以生成预览。请关闭其他应用后重试，或选择较小尺寸导出")
                    busy = false
                }
            } catch (ex: Exception) {
                if (photo == photoGeneration) { fail("${ex::class.simpleName}: ${ex.message}"); busy = false }
            }
        }
    }

    private fun decodeSmartPreview(uri: Uri): Bitmap {
        if (RawDecoder.isRaw(app, uri, queryDisplayName(uri))) {
            com.spektralab.android.data.image.OriginalPreview.raw(app, uri,
                RawDecodeCancellation { cleared || Thread.currentThread().isInterrupted }, 320)?.let { return it }
            RawDecoder.decodeForExport(app,uri,320).use { raw ->
                val argb=ColorPipeline.proPhotoLinearToSrgbArgb(raw.copySmallLinearRgb(),raw.width,raw.height)
                return Bitmap.createBitmap(argb,raw.width,raw.height,Bitmap.Config.ARGB_8888)
            }
        }
        return decodeSampled(uri,320)
    }


    /** No renderer/session work here. The import queue and first-photo load share this lock. */
    private suspend fun ensureSmartParams(uri: Uri, style: SmartDevelopStyle, enabled: Boolean): EditorParams? {
        if(!enabled)return null
        return try {
            smartAnalysisMutex.withLock {
                withContext(Dispatchers.IO) { EditStore.load(app,uri) }?.let { return@withLock it }
                val catalog=withContext(Dispatchers.Main.immediate) { films.toList() to papers.toList() }
                if(catalog.first.isEmpty() || catalog.second.isEmpty())return@withLock null
                // 选中「我的配方」时，导入/拍摄的新照片也按这份配方自动套用，再按画面微调。
                val chosenRecipe = smartRecipeId.takeIf { it.isNotEmpty() }?.let { id ->
                    withContext(Dispatchers.IO) { EditPresetStore.load(app).presets.firstOrNull { it.id == id } }
                }
                val rawSource = withContext(Dispatchers.IO) {
                    runCatching { RawDecoder.isRaw(app, uri, queryDisplayName(uri).orEmpty()) }.getOrDefault(false)
                }
                val bitmap=withContext(Dispatchers.IO) { decodeSmartPreview(uri) }
                val suggestion=try {
                    withContext(Dispatchers.Default) {
                        SmartDevelop.analyze(bitmap)?.let { analysis ->
                            if (chosenRecipe != null)
                                SmartDevelop.recommendWithRecipe(EditorParams(), analysis, chosenRecipe.template, rawSource, catalog.first, catalog.second)
                            else SmartDevelop.recommend(EditorParams(), analysis, style, catalog.first, catalog.second)
                        }
                    }
                } finally { bitmap.recycle() }
                if(suggestion==null)return@withLock null
                if(!currentCoroutineContext().isActive)return@withLock null
                if(chosenRecipe!=null) suggestion.smartDevelopRecipe=chosenRecipe.name
                // 没有选中「我的配方」时，材料完全交给分析结果，不再套默认胶片 / 相纸。
                val committed=withContext(Dispatchers.IO) {
                    // A manual/imported sidecar wins even if it appeared during analysis.
                    EditStore.saveIfAbsent(app, uri, suggestion)
                }
                if(committed===suggestion)withContext(Dispatchers.Main.immediate) { freshlySmartDeveloped.add(uri) }
                committed
            }
        } catch(cancelled:CancellationException){ throw cancelled }
        catch(error:Exception){
            if (error is EditStore.StorageException) throw error
            android.util.Log.w("SmartDevelop","Image analysis skipped; original editing remains available",error)
            null
        }
        catch(error:OutOfMemoryError){
            android.util.Log.w("SmartDevelop","Analysis memory budget unavailable; using manual settings")
            null
        }
    }

    private fun queueSmartDevelop(uris: List<Uri>) {
        if(!smartAutoEnabled || uris.isEmpty())return
        smartQueueJob?.cancel()
        val generation=++smartQueueGeneration
        val style=smartStyle
        smartImportTotal=uris.size;smartImportCompleted=0;smartImportRunning=true
        smartQueueJob=viewModelScope.launch {
            try {
                for(uri in uris){
                    if(uri !in photos)continue
                    try {
                        ensureSmartParams(uri,style,true)
                    } catch (error: EditStore.StorageException) {
                        // The foreground open reports the error. A retired/corrupt record must
                        // neither become defaults nor escape this background launch and crash.
                        android.util.Log.w("SmartDevelop", "Saved edit unavailable; automatic analysis preserved it", error)
                    }
                    if(generation==smartQueueGeneration)smartImportCompleted++
                }
            } finally { if(generation==smartQueueGeneration)smartImportRunning=false }
        }
    }

    internal fun applySmartDevelop() {
        if(busy || isExporting || isBatchExporting || !hasPhoto)return
        val bitmap=sourceBitmap ?: return
        val photo=photoGeneration
        val style=smartStyle
        val base=params.copy()
        val catalog=films.toList() to papers.toList()
        busy=true;smartMessage="正在分析当前照片…"
        smartApplyJob?.cancel()
        smartApplyJob=viewModelScope.launch {
            try {
                val result=withContext(Dispatchers.Default) {
                    SmartDevelop.analyze(bitmap)?.let { SmartDevelop.recommend(base,it,style,catalog.first,catalog.second) }
                }
                if(photo!=photoGeneration)return@launch
                if(result==null){smartMessage="这张照片暂时无法分析，可继续手动调整";return@launch}
                params=result
                smartMessage="已应用${style.title} · ${result.smartDevelopSummary}"
                scheduleRender()
            } catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){if(photo==photoGeneration)smartMessage="暂时无法完成分析，可继续手动调整"}
            finally { if(photo==photoGeneration)busy=false }
        }
    }

    /** Picker imports explicitly restore hidden library entries; session navigation does not. */
    fun importPhotos(uris: List<Uri>, addToSession: Boolean = false) {
        if (uris.isEmpty() || busy || isApplyingEdits || isExporting || isBatchExporting) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 刚导入时选择器授权还在，先把原片位置存下来（导出时多半已经读不到了）。
                    uris.forEach { uri -> captureGeo(uri) }
                    EditStore.restoreImportedPhotos(app, uris)
                }
                if (addToSession) addPhotos(uris, openSelected = true) else openPhotos(uris)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(app.getString(com.spektralab.android.R.string.photos_import_restore_error)) }
        }
    }

    // 打开多张照片（图库），第一张为当前。
    fun openPhotos(uris: List<Uri>) {
        if (isApplyingEdits) return
        if (uris.isEmpty() || isBatchExporting || isExporting) return
        saveCurrentSidecar()
        photos = uris.distinct()
        batchExportSelection = photos.toSet()
        val retained = photos.toSet()
        retainThumbnailRequests(retained)
        thumbnails = thumbnails.filterKeys { it in retained }
        developedPreviews = developedPreviews.filterKeys { it in retained }
        galleryPreviews = galleryPreviews.filterKeys { it in retained }
        currentIndex = 0
        queueSmartDevelop(photos)
        openPhoto(photos[0])
        persistWorkbench()
    }

    /** Queue-only callers can retain the edit; picker callers explicitly open the selected photo. */
    fun addPhotos(uris: List<Uri>, openSelected: Boolean = false) {
        if (isApplyingEdits) return
        if (uris.isEmpty() || isBatchExporting || isExporting) return
        saveCurrentSidecar()
        val merged = (photos + uris).distinct()
        val added = merged.filterNot { it in photos }
        val selected = currentUri
        photos = merged
        queueSmartDevelop(added)
        batchExportSelection = batchExportSelection.intersect(merged.toSet()) + added
        currentIndex = selected?.let { merged.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        if (openSelected) {
            selectPhoto(merged.indexOf(uris.first()))
        } else if (selected == null) openPhoto(merged.first())
        persistWorkbench()
    }

    // 切换到图库中的第 [index] 张。
    fun selectPhoto(index: Int) {
        if (isApplyingEdits) return
        if (index !in photos.indices || isBatchExporting || isExporting) return
        if (index == currentIndex && currentUri == photos[index] && (hasPhoto || photoLoadJob?.isActive == true)) return
        saveCurrentSidecar()
        currentIndex = index
        openPhoto(photos[index])
        persistWorkbench()
    }

    fun previousPhoto() {
        if (currentIndex > 0) selectPhoto(currentIndex - 1)
    }

    fun nextPhoto() {
        if (currentIndex < photos.lastIndex) selectPhoto(currentIndex + 1)
    }

    fun removeCurrentPhoto() {
        if (busy || isExporting || isBatchExporting || currentIndex !in photos.indices) return
        saveCurrentSidecar()
        invalidatePhotoWork()
        val removed = photos[currentIndex]
        val remaining = photos.filterIndexed { index, _ -> index != currentIndex }
        retainThumbnailRequests(remaining.toSet())
        thumbnails = thumbnails - removed
        developedPreviews = developedPreviews - removed
        discardGalleryPreview(removed)
        photos = remaining
        batchExportSelection = batchExportSelection - removed
        if (remaining.isNotEmpty()) {
            currentIndex = currentIndex.coerceAtMost(remaining.lastIndex)
            openPhoto(remaining[currentIndex])
        } else {
            currentUri = null
            currentIndex = 0
            clearPhotoState()
            busy = false
            viewModelScope.launch { engineTasks.onEngine { engine?.closeSession() } }
        }
        persistWorkbench()
    }

    /** Remove a photo from the temporary edit session without touching its source file. */
    fun removePhotoFromSession(uri: Uri) {
        if (busy || isExporting || isBatchExporting) return
        val index = photos.indexOf(uri)
        if (index < 0) return
        if (index == currentIndex) {
            removeCurrentPhoto()
            return
        }
        val remaining = photos.filterNot { it == uri }
        retainThumbnailRequests(remaining.toSet())
        thumbnails = thumbnails - uri
        developedPreviews = developedPreviews - uri
        discardGalleryPreview(uri)
        photos = remaining
        batchExportSelection = batchExportSelection - uri
        if (index < currentIndex) currentIndex -= 1
        persistWorkbench()
        toast("已从当前展厅移除；原始照片未删除")
    }

    // 切换前把当前照片的编辑落盘（避免 120ms 去抖未到就切换而丢编辑）。
    private fun saveCurrentSidecar() {
        finishCurveEdit(cancel = true)
        finishColorEdit(cancel = true)
        finishMixerEdit(cancel = true)
        finishWheelEdit(cancel = true)
        currentUri?.takeIf { it == paramsUri }?.let { EditStore.save(app, it, params) }
        flushHistoryNow()
    }

    // 懒加载单个缩略图（滚到才解码）。
    // 只允许 2 路并发解码：照片上百张时一次性并发解码会瞬间吃满内存和 IO，把整个界面卡死。
    /** replace = true：屏上已经是按原图解的那一张，现在要换成刚落盘的效果图。
     *  不先清空缓存，新图取到才替换，避免照片条整格闪一下白。 */
    fun loadThumbnail(uri: Uri, replace: Boolean = false) {
        if (uri !in photos || (!replace && thumbnails.containsKey(uri)) || thumbnailJobs[uri]?.isActive == true) return
        val request = viewModelScope.launch {
            try {
                var fromEffect = false
                val bmp = withContext(thumbnailDispatcher) {
                    effectThumbFor(uri)?.also { fromEffect = true } ?: decodeSampled(uri, 200)
                }
                if (uri in photos && (replace || !thumbnails.containsKey(uri))) {
                    if (fromEffect) effectThumbKeys = effectThumbKeys + uri.toString()
                    val next = LinkedHashMap(thumbnails)
                    next[uri] = bmp
                    // 缩略图缓存设上限，超出后按最早写入的顺序淘汰，避免原图多时内存无限增长。
                    while (next.size > THUMBNAIL_CACHE_LIMIT) {
                        val eldest = next.keys.firstOrNull { it != uri } ?: break
                        next.remove(eldest)
                    }
                    thumbnails = next
                } else bmp.recycle()
            } catch (ex: CancellationException) {
                throw ex
            } catch (e: Exception) {
                // 忽略单个缩略图失败
            } finally {
                if (thumbnailJobs[uri] === currentCoroutineContext()[Job]) thumbnailJobs.remove(uri)
            }
        }
        thumbnailJobs[uri] = request
    }

    fun setBatchExportPhotoSelected(uri: Uri, selected: Boolean) {
        if (busy || isExporting || isBatchExporting || uri !in photos) return
        batchExportSelection = if (selected) batchExportSelection + uri else batchExportSelection - uri
        persistWorkbench()
    }

    fun selectAllBatchExportPhotos(selected: Boolean) {
        if (busy || isExporting || isBatchExporting) return
        batchExportSelection = if (selected) photos.toSet() else emptySet()
        persistWorkbench()
    }

    // Snapshot selected session photos in queue order; preserve each photo's own edits.
    fun batchExport() {
        val e = engine ?: return
        if (busy || isBatchExporting || isExporting) return
        val batchPhotos = selectedBatchExportPhotos
        if (batchPhotos.isEmpty()) return
        val options = captureExportOptions()
        val batchSmartStyle = smartStyle
        val batchAutoDevelop = smartAutoEnabled
        val interruptedPreview = pendingPreviewWork()
        val hostLease = try { com.spektralab.android.engine.host.RenderHostLease.begin(app) }
            catch (failure: Exception) { fail("无法启动导出服务: ${failure.message}"); return }
        isBatchExporting = true
        exportCancelRequested = false
        isExportCommitCompleted = false
        renderProgress = 0f
        renderStage = "准备批量导出"
        saveCurrentSidecar()
        val activeUri = currentUri
        val activeParams = EditSnapshot.capture(params).toParams()
        val batchEdits = batchPhotos.associateWith { uri ->
            (if (uri == activeUri) activeParams else EditStore.load(app, uri))?.let(EditSnapshot::capture)
        }
        viewModelScope.launch {
            engineTasks.onEngine("batch-export") {
                val batchTs = System.currentTimeMillis()
                var done = 0
                var needsRestore = false
                try {
                    // Resolve missing smart edits before any output; later sidecar writes cannot change this batch.
                    val edits = batchPhotos.associateWith { uri ->
                        batchEdits[uri] ?: EditSnapshot.capture(
                            ensureSmartParams(uri, batchSmartStyle, batchAutoDevelop) ?: EditorParams()
                        )
                    }
                    for ((i, uri) in batchPhotos.withIndex()) {
                        checkExportCancellation()
                        isExportCommitCompleted = false
                        withContext(Dispatchers.Main.immediate) {
                            renderStage = "第 ${i + 1}/${batchPhotos.size} 张：胶片冲印与相纸扫描"
                        }
                        val photoParams = edits.getValue(uri).toParams()
                        check(photoParams.unreadableColorWheels == null) { app.getString(com.spektralab.android.R.string.wheels_unsupported) }
                        check(photoParams.unreadableChannelMixer == null) { app.getString(com.spektralab.android.R.string.mixer_unsupported) }
                        check(photoParams.unreadableColorAdjustments == null) { app.getString(com.spektralab.android.R.string.color_unsupported) }
                        check(photoParams.unreadableToneCurves == null) { app.getString(com.spektralab.android.R.string.curve_unsupported) }
                        val output = com.spektralab.android.data.export.PhotoExportPipeline(app).write(
                            uri, queryDisplayName(uri) ?: "photo", edits.getValue(uri), options,
                            ::checkExportCancellation,
                            beforeNative = { needsRestore = true; e.park(); RenderAdmission.check(app) },
                            progress = { stage, _ ->
                                renderStage = "第 ${i + 1}/${batchPhotos.size} 张：${exportStageLabel(stage)}"
                            },
                        ) { result, metadata, task ->
                            activeExportTask = task
                            saveNativeResult(result, "$batchTs-$i", photoParams, options, metadata, uri)
                        }
                        activeExportTask = null
                        val dimensions = "${output.width}×${output.height}"
                        val path = output.path
                        done++
                        withContext(Dispatchers.Main.immediate) {
                            renderProgress = done.toFloat() / batchPhotos.size * 100f
                            append("批量导出 $done/${batchPhotos.size}: $dimensions → $path")
                        }
                    }
                    withContext(Dispatchers.Main.immediate) {
                        toast("批量导出完成 ($done 张)")
                    }
                } catch (_: ExportCancelled) {
                    withContext(Dispatchers.Main.immediate) { append("批量导出已取消") }
                } catch (_: OutOfMemoryError) {
                    withContext(Dispatchers.Main.immediate) { fail("导出内存不足，请关闭其他应用或选择较小导出尺寸") }
                } catch (ex: SpkException) {
                    withContext(Dispatchers.Main.immediate) {
                        if (ex.message?.contains("cancel", ignoreCase = true) == true) append("批量导出已取消")
                        else fail("SpkException: ${ex.message}")
                    }
                } catch (ex: Exception) {
                    withContext(Dispatchers.Main.immediate) { fail("${ex::class.simpleName}: ${ex.message}") }
                } finally {
                    activeExportTask = null
                    // 恢复当前照片预览。
                    val pl = previewLinear
                    if (pl != null && needsRestore) {
                        try {
                            withContext(NonCancellable) { hostLease.releaseDevice() }
                            withContext(Dispatchers.Main.immediate) { params = activeParams }
                            e.open(pl, previewW, previewH, 3, activeParams.openJson(previewW, previewH, inputColorSpace = previewInputColorSpace))
                        } catch (_: OutOfMemoryError) {
                            withContext(Dispatchers.Main.immediate) { append("恢复编辑会话时内存不足，请重新打开当前照片") }
                        } catch (ex: Exception) {
                            withContext(Dispatchers.Main.immediate) { append("恢复预览失败: ${ex.message}") }
                        }
                    }
                    withContext(Dispatchers.Main.immediate) {
                        isBatchExporting = false
                        renderProgress = null
                        renderStage = ""
                        exportCancelRequested = false
                        isExportCommitCompleted = false
                        resumePreview(interruptedPreview)
                    }
                }
            }
        }.invokeOnCompletion { hostLease.close() }
    }

    private fun runExportAction(
        label: String,
        maxLongEdge: Int,
        outputColorSpace: String,
        action: suspend (EngineClient, EditorParams, Long, Long, ExportOptions) -> Unit,
    ) {
        val e = engine ?: return
        // 「有照片」和「没有别的导出在跑」是硬条件；busy 只说明上一次预览还在算，
        // 以前直接 return 会让这三个按钮看着像点了没反应，现在改成提示 + 等它算完。
        if (!hasPhoto) { toast("先导入或拍一张照片"); return }
        if (isExporting || isBatchExporting) { toast("正在导出，稍等一下"); return }
        if (busy) { toast("正在生成预览，稍等一下再试"); return }

        val uri = currentUri
        val editParams = EditSnapshot.capture(params).toParams()
        val options = captureExportOptions()
        val photo = photoGeneration
        val revision = renderGeneration
        val previewPixels = previewLinear
        val previewWidth = previewW
        val previewHeight = previewH
        val previewInputSpace = previewInputColorSpace
        val interruptedPreview = pendingPreviewWork()
        isExporting = true
        exportCancelRequested = false
        isExportCommitCompleted = false
        renderProgress = 0f
        renderStage = "准备$label"

        viewModelScope.launch {
            engineTasks.run("export-action") {
                var progressJob: Job? = null
                try {
                    renderStage = "读取原片"
                    withContext(engineDispatcher) {
                        if (uri != null) {
                            RenderAdmission.check(app)
                            openExportSource(e, uri, editParams, maxLongEdge, outputColorSpace, options)
                        } else {
                            e.setParams(JSONObject().put("output_color_space", outputColorSpace).toString())
                        }
                    }
                    checkExportCancellation()
                    renderProgress = 8f
                    renderStage = "正在$label"
                    val previousProgressId = withContext(engineDispatcher) { e.progressSnapshot().progressId }
                    progressJob = launch(Dispatchers.IO) {
                        while (isActive) {
                            val progress = runCatching { e.progressSnapshot() }.getOrNull()
                            if (exportCancelRequested) e.cancel(progress?.progressId)
                            if (progress != null && progress.progressId != previousProgressId) {
                                withContext(Dispatchers.Main.immediate) {
                                    renderProgress = max(renderProgress ?: 8f, 8f + progress.percent * .86f).coerceAtMost(94f)
                                    if (progress.done && !exportCancelRequested) renderStage = "完成渲染"
                                }
                            }
                            delay(120)
                        }
                    }

                    action(e, editParams, photo, revision, options)
                    checkExportCancellation()
                    renderProgress = 100f
                    renderStage = "$label 完成"
                    delay(250)
                } catch (_: ExportCancelled) {
                    append("$label 已取消")
                } catch (ex: CancellationException) {
                    throw ex
                } catch (_: OutOfMemoryError) {
                    fail("$label 内存不足，请选择较小尺寸后重试")
                } catch (ex: SpkException) {
                    if (exportCancelRequested || ex.message?.contains("cancel", ignoreCase = true) == true) {
                        append("$label 已取消")
                    } else {
                        fail("SpkException: ${ex.message}")
                    }
                } catch (ex: Exception) {
                    if (exportCancelRequested) append("$label 已取消")
                    else fail("${ex::class.simpleName}: ${ex.message}")
                } finally {
                    withContext(NonCancellable) {
                        try {
                            progressJob?.cancelAndJoin()
                            if (previewPixels != null && photo == photoGeneration) {
                                renderStage = "恢复编辑预览"
                                withContext(engineDispatcher) {
                                    e.open(
                                        previewPixels,
                                        previewWidth,
                                        previewHeight,
                                        3,
                                        editParams.openJson(previewWidth, previewHeight, inputColorSpace = previewInputSpace),
                                    )
                                }
                            }
                        } catch (_: OutOfMemoryError) {
                            fail("恢复编辑会话时内存不足，请重新打开当前照片")
                        } catch (ex: Exception) {
                            fail("恢复编辑预览失败: ${ex.message}")
                        } finally {
                            renderProgress = null
                            renderStage = ""
                            exportCancelRequested = false
                            isExportCommitCompleted = false
                            isExporting = false
                            resumePreview(interruptedPreview)
                        }
                    }
                }
            }
        }
    }

    // DI TIFF：按导出分辨率读取原片，输出 Cineon log 16-bit，不套 Layer 2、不嵌 ICC。
    fun exportDi() {
        if (params.filmOff) { toast("数字中间片需要开启胶片模拟"); return }
        runExportAction("DI TIFF", exportRes, "sRGB") { e, _, _, _, options ->
            val result = withContext(engineDispatcher) { e.renderDigitalIntermediate() }
            try {
                checkExportCancellation()
                renderProgress = 96f
                renderStage = "写入 DI TIFF"
                val path = withContext(Dispatchers.IO) {
                    saveDiTiff(result, System.currentTimeMillis().toString(), options)
                }
                append("DI 导出: ${result.width}×${result.height} → $path")
                toast("DI 已导出")
            } finally {
                result.close()
            }
        }
    }

    // 软打样：按导出尺寸/色彩空间重渲染并显示，不保存文件。
    fun softProof() {        val outputCs = if (exportFormat == "TIFF") exportColorSpace else "sRGB"
        val previewLongEdge = if (exportRes <= 0) 3400 else exportRes.coerceAtMost(3400)
        runExportAction("软打样", previewLongEdge, outputCs) { e, editParams, photo, revision, _ ->
            val result = withContext(engineDispatcher) { e.render("full") }
            checkExportCancellation()
            updateResult(result, outputCs, photo, revision, editParams)
            append("软打样: ${result.width}×${result.height}  ${result.elapsedMs}ms")
            toast("软打样完成：${result.width}×${result.height}，回「冲印」页看效果")
        }
    }

    // 高清预览：通过原片解码器直接限制到 3400px，避免 RAW 全尺寸 managed float buffer。
    fun previewTier() {
        val outputCs = if (exportFormat == "TIFF") exportColorSpace else "sRGB"
        runExportAction("高清预览", 3400, outputCs) { e, editParams, photo, revision, _ ->
            val result = withContext(engineDispatcher) { e.render("preview") }
            checkExportCancellation()
            updateResult(result, outputCs, photo, revision, editParams)
            append("高清预览: ${result.width}×${result.height}  ${result.elapsedMs}ms")
            toast("高清预览完成：${result.width}×${result.height}，回「冲印」页看效果")
        }
    }

    // ---- 效果细节检查：按 2048px 预览分辨率渲染成片后放大查看，只读不写文件 ----
    internal val detail = DetailController(viewModelScope)

    internal fun openFullDetail() {
        val e = engine ?: return
        val uri = currentUri ?: return
        if (!hasPhoto) { toast("先导入或拍一张照片"); return }
        if (isExporting || isBatchExporting) { toast("正在导出，稍等一下"); return }
        if (busy) { toast("正在生成预览，稍等一下再试"); return }
        val edit = EditSnapshot.capture(params)
        val exportParams = edit.toParams()
        val photo = photoGeneration
        // 检查后期效果不需要原尺寸全分辨率：限到 2048px，渲染快、平移缩放不卡。
        val options = captureExportOptions().copy(resolution = 2048, customSize = null)
        val lease = try { com.spektralab.android.engine.host.RenderHostLease.begin(app) }
            catch (failure: Exception) { fail("无法启动细节处理: ${failure.message}"); return }
        detail.begin()
        isExporting = true
        exportCancelRequested = false
        isExportCommitCompleted = false
        renderProgress = 0f
        renderStage = "准备效果细节"
        viewModelScope.launch {
            engineTasks.run("detail") {
                var made: com.spektralab.android.data.preview.FullDetailSession? = null
                var restore = false
                try {
                    com.spektralab.android.data.export.PhotoExportPipeline(app).write(
                        uri, queryDisplayName(uri) ?: "photo", edit, options, ::checkExportCancellation,
                        beforeNative = {
                            withContext(engineDispatcher) {
                                restore = true
                                e.park()
                                RenderAdmission.check(app)
                            }
                        },
                        progress = { stage, percent ->
                            renderStage = exportStageLabel(stage)
                            renderProgress = if (stage == "encoding") 96f
                                else max(renderProgress ?: 8f, 8f + percent * .86f).coerceAtMost(94f)
                        },
                        recordReceipt = false,
                    ) { pixels, _, task ->
                        made = com.spektralab.android.data.preview.FullDetailSession.create(
                            app, pixels,
                            if (pixels.gradeApplied) com.spektralab.android.engine.EditorGrade.identity else grade(exportParams),
                            options.outputColorSpace, task.request.toJson(), ::checkExportCancellation,
                            quantize8Bit = options.format != "TIFF",
                        )
                        task.verifySource()
                        "internal-detail-view"
                    }
                    checkExportCancellation()
                    check(photo == photoGeneration) { "照片已更换" }
                    val session = made ?: error("细节未生成")
                    if (detail.opened) { detail.ready(session); made = null }
                    append("效果细节: ${session.width}×${session.height} · ${session.colorSpace}")
                } catch (_: ExportCancelled) { detail.close(); append("效果细节已取消") }
                catch (cancelled: CancellationException) { detail.close(); throw cancelled }
                catch (_: OutOfMemoryError) { detail.failed("内存不足，请关闭其他应用后重试") }
                catch (error: Exception) { detail.failed(error.message ?: "效果细节失败") }
                finally {
                    withContext(NonCancellable + Dispatchers.IO) { made?.close() }
                    val linear = previewLinear
                    if (restore && linear != null) {
                        try {
                            withContext(NonCancellable) { lease.releaseDevice() }
                            withContext(NonCancellable + engineDispatcher) {
                                e.open(linear, previewW, previewH, 3,
                                    params.openJson(previewW, previewH, inputColorSpace = previewInputColorSpace))
                            }
                        } catch (ex: Exception) { append("恢复编辑预览: ${ex.message}") }
                    }
                    renderProgress = null; renderStage = ""
                    isExporting = false; exportCancelRequested = false; isExportCommitCompleted = false
                }
            }
        }.invokeOnCompletion { lease.close() }
    }

    internal fun closeFullDetail() {
        detail.close()
        if (isExporting) cancelRender()
    }

    // ---- 中性拾色：在画面上点取中性点，算出白平衡 ----
    internal val neutralPick by lazy { NeutralPickController(app, viewModelScope) }
    internal val canPickNeutral: Boolean get() = hasPhoto && lastResult != null && !busy && !isCropping &&
        !isExporting && !isBatchExporting && !isApplyingEdits && !previewWorkRunning

    internal fun beginNeutralPick(target: com.spektralab.android.core.color.NeutralWhiteBalance.Target) {
        if (!canPickNeutral) { toast("先等当前处理完成"); return }
        if (target == com.spektralab.android.core.color.NeutralWhiteBalance.Target.DECODE &&
            (!currentIsRaw || currentUri == null)) { toast("相机白平衡只对 RAW 有效"); return }
        finishMixerEdit(cancel = true); finishColorEdit(cancel = true)
        finishWheelEdit(cancel = true); finishCurveEdit(cancel = true)
        val uri = currentUri
        val photo = photoGeneration
        val frame = lastResult
        val edit = EditSnapshot.capture(params)
        neutralPick.open(target, uri, edit, frame, resultColorSpace, isCurrent = {
            !cleared && uri == currentUri && photo == photoGeneration &&
                edit.fingerprint == EditSnapshot.capture(params).fingerprint &&
                (target == com.spektralab.android.core.color.NeutralWhiteBalance.Target.DECODE || frame === lastResult)
        }) { balance ->
            lastHistoryKey = null
            if (target == com.spektralab.android.core.color.NeutralWhiteBalance.Target.DECODE) {
                params.cameraWbCustom = true
                params.cameraWbTemperatureAsShot = false
                params.cameraWbTintAsShot = false
                params.cameraWbKelvin = balance.temperature.toFloat()
                params.cameraWbTint = balance.tint.toFloat()
                scheduleCameraWb()
            } else {
                params.temperature = balance.temperature.toFloat()
                params.tint = balance.tint.toFloat()
                scheduleGrade()
            }
        }
    }

    fun release() {
        viewModelScope.launch {
            engineTasks.onEngine {
                engine?.close()
                engine = null
                rendered = null
                sourceBitmap = null
                hasPhoto = false
                isCropping = false
                append("已释放")
            }
        }
    }

    private fun captureExportOptions() = ExportOptions(
        exportFormat, exportRes, exportColorSpace, jpegQuality,
        exportKeepCapture, exportKeepLocation, lensCorrectionActive,
        customSize = exportCustomSize, naming = exportNaming, tiffDeflate = exportTiffDeflate,
    )

    /**
     * 导出文件名：[自定义前缀_] <这张照片的原文件名>_Speklab + 当前格式后缀。
     * 前缀在导出页「文件命名」里填，留空就完全不加；勾选了命名内容时前缀同样接在最前面。
     * 必须按照片自己传入的 uri 取名：批量导出时每张的照片都要用自己的源文件名，
     * 否则整批会写出同一个名字，相册只能加 (1)(2) 区分。
     */
    private fun exportBaseName(uri: Uri? = currentUri, options: ExportOptions = captureExportOptions(),
                               editParams: EditorParams = params): String {
        // 照片打开时已经用 ContentResolver 取到过真实文件名并存在 photoInfo 里，
        // 导出时若再次解析失败（退回时间戳兜底），优先回用打开时拿到的名字，避免丢失原相机文件名。
        val libraryName = photoInfo.firstOrNull { it.first == "文件名" }?.second?.takeIf { uri == currentUri }
        // 通过 usableSourceName 统一校验：纯数字 ID（如照片选择器返回的 "19663"）绝不能当成文件名。
        val libraryValid = usableSourceName(libraryName)
        val resolved = uri?.let { resolveSourceName(it) }
        val raw = resolved?.takeUnless { it.startsWith("SpekLab_") }
            ?: libraryValid
            ?: resolved
        val base = raw?.substringBeforeLast('.')?.trim()?.trim('.')?.takeIf { it.isNotBlank() } ?: "SpekLab"
        if (options.naming.tokens.isEmpty()) {
            return options.naming.withPrefix(base.replace(Regex("[\\\\/:*?\"<>|\u0000-\u001f]"), "_").take(80) + "_Speklab")
        }
        val (films, papers) = StockCatalog.load(app)
        val film = films.firstOrNull { it.id == editParams.filmId }?.name ?: editParams.filmId
        val paper = papers.firstOrNull { it.id == editParams.paperId }?.name ?: editParams.paperId
        val date = java.time.LocalDate.now().toString()
        return options.naming.stem(raw ?: base, film, paper, date, System.currentTimeMillis().toString())
    }

    /** 导出文件名示例：给「文件命名」设置页做实时预览。 */
    fun exportNameExample(): String {
        val ext = when (exportFormat) { "PNG" -> "png"; "TIFF" -> "tiff"; "HEIF" -> "heic"; else -> "jpg" }
        return exportBaseName(currentUri, captureExportOptions(), params) + ".$ext"
    }

    /** 纯数字（ContentResolver 只拿回 id）和占位串都不是真正的文件名。 */
    /** 趁授权还活着，把原片位置存一份（映射读原图 + 直接读选择器副本，两路都试）。 */
    private fun captureGeo(uri: Uri) {
        if (GeoTagStore.has(app, uri)) return
        GeoTagStore.capture(app, uri, readExif(uri, keepLocation = true))
        GeoTagStore.capture(app, uri, runCatching {
            app.contentResolver.openInputStream(uri)?.use(::ExifInterface)
        }.getOrNull())
    }

    private fun usableSourceName(raw: String?): String? {
        var name = raw?.substringBefore(" · ")?.trim().orEmpty()
        if (name.isEmpty() || name == "未知" || name.equals("null", true)) return null
        // document uri 的 lastPathSegment 形如 "primary:DCIM/Camera/IMG2026...dng"，只留文件名。
        name = Uri.decode(name).substringAfterLast('/').substringAfterLast(':').trim()
        if (name.isEmpty() || name.all { it.isDigit() }) return null
        // 照片选择器给的 "19663" / "19663.dng"：主干纯数字就是行号 ID，不是文件名。
        val stem = name.substringBeforeLast('.', name).trim()
        if (stem.isEmpty() || stem.all { it.isDigit() }) return null
        return name
    }

    /** 照片选择器 uri → 底层 MediaStore uri；其它 uri 原样返回。 */
    private fun mediaStoreUriOf(uri: Uri): Uri =
        runCatching { EditStore.mediaSourceUri(app, uri) }.getOrNull() ?: uri

    private fun mediaStoreNameById(id: Long): String? {
        // RAW/DNG 常落在 MediaStore.Files；部分机型 DISPLAY_NAME 为 null，此时退回 DATA 路径的 basename。
        val bases = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri("external"),
        )
        for (base in bases) {
            val item = android.content.ContentUris.withAppendedId(base, id)
            val found = runCatching {
                app.contentResolver.query(item, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA),
                    null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    cursor.getString(0)?.takeIf { it.isNotBlank() }
                        ?: cursor.getString(1)?.let { data ->
                            usableSourceName(data)?.takeIf { it.isNotBlank() }
                        }
                }
            }.getOrNull()
            if (!found.isNullOrBlank()) return found
        }
        return null
    }

    /**
     * 选择器只给了行号 ID 时，用原片的拍摄时间（EXIF DateTimeOriginal）在相册里找匹配的一条，
     * 取它的真实 DISPLAY_NAME：先找尺寸完全一致的 ±10 分钟窗口内唯一项，
     * 再退一步找时间最近、且差值不超过 2 分钟的一条。找不到就放弃，绝不猜错。
     */
    private fun mediaStoreNameByCapture(exif: ExifInterface?): String? {
        val taken = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: return null
        val stamp = runCatching {
            java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(taken)
        }.getOrNull() ?: return null
        val width = exif.getAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION)?.toIntOrNull()
        val height = exif.getAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION)?.toIntOrNull()
        data class Row(val name: String, val time: Long, val w: Int?, val h: Int?)
        fun collect(base: Uri): List<Row> = runCatching {
            val rows = mutableListOf<Row>()
            app.contentResolver.query(
                base,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT),
                "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} <= ?",
                arrayOf((stamp.time - 3_600_000L).toString(), (stamp.time + 3_600_000L).toString()),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = usableSourceName(cursor.getString(0)) ?: continue
                    val time = if (cursor.isNull(1)) 0L else cursor.getLong(1)
                    val w = if (cursor.isNull(2)) null else cursor.getInt(2)
                    val h = if (cursor.isNull(3)) null else cursor.getInt(3)
                    if (time > 0L) rows.add(Row(name, time, w, h))
                }
            }
            rows
        }.getOrDefault(emptyList())
        val rows = collect(MediaStore.Images.Media.EXTERNAL_CONTENT_URI) + collect(MediaStore.Files.getContentUri("external"))
        val sized = if (width != null && height != null) rows.filter { row ->
            row.w == null || row.h == null ||
                (minOf(row.w!!, row.h!!) == minOf(width, height) && maxOf(row.w!!, row.h!!) == maxOf(width, height))
        } else rows
        val inWindow = sized.filter { kotlin.math.abs(it.time - stamp.time) <= 600_000L }
        inWindow.map { it.name }.distinct().singleOrNull()?.let { return it }
        val nearest = sized.minByOrNull { kotlin.math.abs(it.time - stamp.time) }
        if (nearest != null && kotlin.math.abs(nearest.time - stamp.time) <= 120_000L) return nearest.name
        return null
    }

    /**
     * 取这张照片真正的原文件名，按可靠度逐层回退：
     * 导入时记进照片库的名字 → ContentResolver 的 DISPLAY_NAME → document 显示名 →
     * 纯数字 uri 用 id 反查 MediaStore → 最后用拍摄时间兜底（仍然每张不同，便于按时间归档）。
     */
    private fun resolveSourceName(uri: Uri): String? {
        val source = runCatching { EditStore.editSourceUri(app, uri) }.getOrNull() ?: uri
        usableSourceName(runCatching { EditStore.sourceDisplayName(app, source) }.getOrNull())?.let { return it }
        // 系统照片选择器（ColorOS 等）会给 DISPLAY_NAME 返回行号 ID，必须先映射回底层 MediaStore uri 再查。
        val mediaUri = mediaStoreUriOf(source)
        usableSourceName(runCatching { queryDisplayName(mediaUri) }.getOrNull())?.let { return it }
        usableSourceName(runCatching { queryFileName(mediaUri) }.getOrNull())?.let { return it }
        if (mediaUri != source) {
            usableSourceName(runCatching { queryDisplayName(source) }.getOrNull())?.let { return it }
        }
        usableSourceName(runCatching { androidx.documentfile.provider.DocumentFile.fromSingleUri(app, mediaUri)?.name }.getOrNull())
            ?.let { return it }
        val id = mediaUri.lastPathSegment?.toLongOrNull() ?: source.lastPathSegment?.toLongOrNull()
        if (id != null) usableSourceName(mediaStoreNameById(id))?.let { return it }
        val exif = if (uri == currentUri) sourceExif
        else runCatching { readExif(source) }.getOrNull()
        // 选择器 id 不是 MediaStore 的 _ID 时，用「拍摄时间 + 像素尺寸」在相册里找回那一条。
        usableSourceName(mediaStoreNameByCapture(exif))?.let { return it }
        val taken = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        val stamp = runCatching {
            java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(taken.orEmpty())
        }.getOrNull()
        val text = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(stamp ?: java.util.Date())
        return "SpekLab_$text"
    }

    /** 任意 content uri 的兜底取名：DISPLAY_NAME → DATA 路径 basename → TITLE。 */
    private fun queryFileName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.TITLE,
        ), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(0)?.takeIf { it.isNotBlank() }
                ?: cursor.getString(1)?.let { data ->
                    Uri.decode(data).substringAfterLast('/').substringAfterLast(':').trim().takeIf { it.isNotBlank() }
                }
                ?: cursor.getString(2)?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()
    private fun saveBitmap(bitmap: Bitmap, suffix: String, options: ExportOptions,                           metadata: ExportMetadata = ExportMetadata.from(sourceExif, options.keepCapture, options.keepLocation), imprintParams: EditorParams = params, sourceUri: Uri? = currentUri): String {
        val imprinted = com.spektralab.android.core.model.DateImprintPaint.apply(bitmap, imprintParams)
        val ext = if (options.format == "PNG") "png" else "jpg"
        val mime = if (options.format == "PNG") "image/png" else "image/jpeg"
        val name = "${exportBaseName(sourceUri, options, imprintParams)}.$ext"
        val fmt = if (options.format == "PNG") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG

        // 先压缩到临时文件（JPEG 需先落盘才能附加 EXIF）。
        val tmp = File(app.cacheDir, "export_${System.nanoTime()}.$ext")
        try {
            FileOutputStream(tmp).use { fos ->
                check(imprinted.compress(fmt, options.jpegQuality.coerceIn(1, 100), fos)) { "图片编码失败" }
            }
            metadata.attach(tmp, imprinted.width, imprinted.height)
            return publishExport(tmp, name, mime, metadata.takenMillis())
        } finally {
            tmp.delete()
        }
    }

    // 按当前格式导出渲染结果（TIFF 走 16-bit，其余走 8-bit JPEG/PNG）。
    private fun saveNativeResult(result: Rgba16Source, suffix: String, editParams: EditorParams, options: ExportOptions,
                                 metadata: ExportMetadata = ExportMetadata.from(sourceExif, options.keepCapture, options.keepLocation),
                                 sourceUri: Uri? = currentUri): String {
        // 原片这次读不到位置（选择器授权已失效/脱敏）时，用「导入/打开时读到并存下的那份」和
        // 「打开照片时读到的 EXIF」补上，保证界面上看到的坐标一定会写进文件。
        val sidecarGeo = if (options.keepLocation) ExportMetadata.from(sourceExif, false, true).attributes else emptyMap()
        val effective = if (options.keepLocation)
            metadata.withFallback(GeoTagStore.load(app, sourceUri)).withFallback(sidecarGeo)
        else metadata
        return measureExport("encode_publish_${options.format}") { encodeNativeResult(result, suffix, editParams, options, effective, sourceUri) }
    }

    private fun encodeNativeResult(result: Rgba16Source, suffix: String, editParams: EditorParams, options: ExportOptions, metadata: ExportMetadata,
                                   sourceUri: Uri? = currentUri): String {
        // 无片边（或全景片边）时日期由 App 按像素画：这里把同一份印记渲染成一条带，
        // 交给逐行写入器混合，导出成片才和预览一致（否则屏幕上有日期、文件里没有）。
        val imprint = com.spektralab.android.core.model.DateImprintPaint.overlay(result.width, result.height, editParams)
        try {
            return encodeNativeRows(result, suffix, editParams, options, metadata, imprint, sourceUri)
        } finally {
            imprint?.recycle()
        }
    }

    private fun encodeNativeRows(result: Rgba16Source, suffix: String, editParams: EditorParams, options: ExportOptions,
                                 metadata: ExportMetadata, imprint: com.spektralab.android.core.model.DateImprintPaint.Overlay?,
                                 sourceUri: Uri? = currentUri): String {
        // 除 sRGB 之外的文件色域，像素已由 FinishedPhoto 转好，这里只负责把对应 ICC 写进文件。
        val icc = options.colorSpace.takeUnless { it == "sRGB" }?.let { com.spektralab.android.core.color.OutputIcc.generate(it) }
        val taken = metadata.takenMillis()
        if (options.format == "HEIF") {
            return exportPublisher(taken).write("${exportBaseName(sourceUri, options, editParams)}.heic", "image/heif") {
                com.spektralab.android.data.export.HeifOutput.write(app, result, grade(editParams),
                    options.jpegQuality, it, ::checkExportCancellation, metadata)
            }
        }
        if (options.format == "PNG") {
            return exportPublisher(taken).write("${exportBaseName(sourceUri, options, editParams)}.png", "image/png") {
                com.spektralab.android.data.export.PngRowWriter.write(result, grade(editParams), it,
                    ::checkExportCancellation, metadata, imprint, icc)
            }
        }
        if (options.format == "JPEG" && com.spektralab.android.data.export.JpegRowWriter.available) {
            return exportPublisher(taken).write("${exportBaseName(sourceUri, options, editParams)}.jpg", "image/jpeg") {
                com.spektralab.android.data.export.JpegRowWriter.write(app, result, grade(editParams), options.jpegQuality,
                    it, ::checkExportCancellation, metadata, imprint, icc)
            }
        }
        if (options.format == "TIFF") {
            val profile = icc ?: readSrgbIcc()
            return exportPublisher(taken).write("${exportBaseName(sourceUri, options, editParams)}.tiff", "image/tiff") {
                if (options.tiffDeflate) {
                    com.spektralab.android.data.export.DeflatedTiffWriter.write(app, result, grade(editParams), profile,
                        it, ::checkExportCancellation, metadata, imprint)
                } else {
                    TiffWriter.writeNative(result, grade(editParams), profile, it, ::checkExportCancellation,
                        metadata, imprint)
                }
            }
        }
        val bitmap = com.spektralab.android.data.export.Rgba16Bitmap.convert(result, grade(editParams), ::checkExportCancellation)
        try { return saveBitmap(bitmap, suffix, options, metadata, editParams, sourceUri) } finally { bitmap.recycle() }
    }

    private fun exportPublisher(taken: Long? = null) = ExportPublisher(app, ::checkExportCancellation, {
        exportCancelRequested = false
        isExportCommitCompleted = true
    }, exportDirUri).also { publisher ->
        // 明确告诉媒体库这张的拍摄时刻，避免扫描器把无时区的 EXIF 时间当 UTC（相册会偏 +8 小时）。
        publisher.takenEpochMs = taken
        publisher.onPrepared = { activeExportTask?.publishing(it) }
        publisher.onReadyToCommit = { activeExportTask?.verifySource() }
    }

    private fun publishExport(tmp: File, name: String, mime: String, taken: Long?): String =
        exportPublisher(taken).write(name, mime) { destination ->
            tmp.inputStream().use { source ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkExportCancellation()
                    val count = source.read(buffer)
                    if (count < 0) break
                    destination.write(buffer, 0, count)
                }
            }
        }

    private fun saveResult(result: SpkResult, suffix: String, editParams: EditorParams = params,
                           options: ExportOptions = captureExportOptions(), sourceUri: Uri? = currentUri): String {
        return if (options.format == "TIFF") {
            saveTiff(result, suffix, editParams, options, sourceUri)
        } else {
            saveBitmap(convertToBitmap(result, editParams), suffix, options, sourceUri = sourceUri)
        }
    }

    private fun saveTiff(result: SpkResult, suffix: String, editParams: EditorParams, options: ExportOptions,
                         sourceUri: Uri? = currentUri): String {
        val g = grade(editParams)
        val icc = if (options.colorSpace == "Display P3") DisplayP3Icc.generate() else readSrgbIcc()
        val legacyMetadata = ExportMetadata.from(sourceExif, options.keepCapture, options.keepLocation)
        return exportPublisher(legacyMetadata.takenMillis()).write("${exportBaseName(sourceUri, options, editParams)}.tiff", "image/tiff") { out ->
            TiffWriter.writeRgba16(result.rgba16, result.width, result.height, g, icc, out, ::checkExportCancellation,
                legacyMetadata)
        }
    }

    // DI TIFF：无调色、无 ICC（设备 RGB 密度）。
    private fun saveDiTiff(result: NativeRenderResult, suffix: String, options: ExportOptions,
                           sourceUri: Uri? = currentUri): String {
        val name = "${exportBaseName(sourceUri, options)}_DI.tiff"
        val diMetadata = ExportMetadata.from(sourceExif, options.keepCapture, options.keepLocation)
        return exportPublisher(diMetadata.takenMillis()).write(name, "image/tiff") { out ->
            TiffWriter.writeNative(
                result,
                grade = null,
                icc = null,
                out = out,
                checkCancelled = ::checkExportCancellation,
                metadata = diMetadata,
            )
        }
    }

    private fun readSrgbIcc(): ByteArray? = try {
        app.assets.open("engine/resources/io/sRGB.icc").use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    // 取 MediaStore 的 DISPLAY_NAME（文件名），用于判断是否 RAW。
    private fun queryDisplayName(uri: Uri): String? = try {
        app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && c.columnCount > 0) c.getString(0) else null } ?: uri.lastPathSegment
    } catch (e: Exception) {
        uri.lastPathSegment
    }

    /**
     * 相册那一行的 `DATE_TAKEN`（拍摄时刻，毫秒）。EXIF 里读不到拍摄日期时
     * （部分 RAW 解析失败、或元数据被聊天工具剥掉），日期后背用它兜底，
     * 而不是退回系统当天冒充拍摄日期。拿不到就返回 null。
     */
    private fun queryDateTakenMs(uri: Uri): Long? = runCatching {
        val source = EditStore.mediaSourceUri(app, uri) ?: uri
        app.contentResolver.query(source, arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null)?.use { c ->
            val col = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            if (col < 0 || !c.moveToFirst() || c.isNull(col)) null
            else c.getLong(col).takeIf { it > 0L }
        }
    }.getOrNull()

    // 读取源 EXIF（仅 JPEG/TIFF/DNG；RAW 抛异常时返回 null）。
    private fun readExif(uri: Uri, keepLocation: Boolean = exportKeepLocation): ExifInterface? {
        val source = runCatching { EditStore.mediaSourceUri(app, uri) }.getOrNull() ?: return null
        if (Build.VERSION.SDK_INT >= 29 && keepLocation && source.authority == "media" &&
            androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.ACCESS_MEDIA_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { app.contentResolver.openInputStream(MediaStore.setRequireOriginal(source))?.use(::ExifInterface) }
                .getOrNull()?.let { return it }
        }
        return runCatching { app.contentResolver.openInputStream(source)?.use(::ExifInterface) }.getOrNull()
    }

    private fun decodeSampled(uri: Uri, maxLongEdge: Int = 2048, preserveColorSpace: Boolean = false): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (!preserveColorSpace) decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val edge = max(info.size.width, info.size.height)
                if (edge > maxLongEdge) {
                    val scale = maxLongEdge.toDouble() / edge
                    decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1))
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxLongEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        if (!preserveColorSpace) opts.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        val bitmap = app.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalArgumentException("无法解码此图片，请选择有效的 JPEG、PNG、WebP 或支持的 RAW 文件")
        val orientation = readExif(uri)?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun decodeBitmapForExport(uri: Uri, maxLongEdge: Int): Bitmap {
        val bitmap = decodeSampled(uri, if (maxLongEdge == 0) Int.MAX_VALUE else maxLongEdge)
        val edge = max(bitmap.width, bitmap.height)
        if (maxLongEdge == 0 || edge <= maxLongEdge) return bitmap
        val scale = maxLongEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
    }

    private fun resizeRaw(raw: RawDecodeResult, maxLongEdge: Int): RawDecodeResult {
        val edge = max(raw.width, raw.height)
        if (maxLongEdge == 0 || edge <= maxLongEdge) return raw
        val scale = maxLongEdge.toDouble() / edge
        val width = (raw.width * scale).roundToInt().coerceAtLeast(1)
        val height = (raw.height * scale).roundToInt().coerceAtLeast(1)
        val output = FloatArray(width * height * 3)
        for (y in 0 until height) {
            val sy = ((y + 0.5) * raw.height / height - 0.5).coerceIn(0.0, (raw.height - 1).toDouble())
            val y0 = sy.toInt(); val y1 = (y0 + 1).coerceAtMost(raw.height - 1); val fy = sy - y0
            for (x in 0 until width) {
                val sx = ((x + 0.5) * raw.width / width - 0.5).coerceIn(0.0, (raw.width - 1).toDouble())
                val x0 = sx.toInt(); val x1 = (x0 + 1).coerceAtMost(raw.width - 1); val fx = sx - x0
                val dst = (y * width + x) * 3
                for (c in 0..2) {
                    val a = raw.linearProPhoto[(y0 * raw.width + x0) * 3 + c]
                    val b = raw.linearProPhoto[(y0 * raw.width + x1) * 3 + c]
                    val d = raw.linearProPhoto[(y1 * raw.width + x0) * 3 + c]
                    val e = raw.linearProPhoto[(y1 * raw.width + x1) * 3 + c]
                    output[dst + c] = ((a * (1.0 - fx) + b * fx) * (1.0 - fy) + (d * (1.0 - fx) + e * fx) * fy).toFloat()
                }
            }
        }
        return RawDecodeResult(width, height, output, raw.make, raw.model, raw.lens, raw.focal, raw.camMul, raw.camXyz)
    }

    override fun onCleared() {
        smartQueueJob?.cancel();smartApplyJob?.cancel()
        cleared = true
        invalidatePhotoWork()
        galleryPreviewJobs.values.forEach(Job::cancel)
        galleryPreviewJobs.clear()
        (thumbnails.values + developedPreviews.values + galleryPreviews.values + viewingPhotos.map { it.second })
            .distinctBy(System::identityHashCode)
            .forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        renderJob?.cancel()
        wbJob?.cancel()
        cameraWbJob?.cancel()
        runCatching { dirtyHistory.toList().forEach { writeHistoryFile(it) } }
        val client = engine
        engine = null
        if (client != null) {
            CoroutineScope(SupervisorJob() + engineDispatcher).launch { engineTasks.onEngine("close") { client.close() } }
        }
        super.onCleared()
    }

    private companion object {
        const val GALLERY_PREVIEW_CACHE_SIZE = 6
        const val KEY_FRAME_ROLL = "frame_roll"
    const val KEY_FRAME_MANUAL = "frame_manual"
    const val KEY_MANUAL_FRAME = "frame_manual_number"
        const val FRAME_ROLL_LENGTH = 36
        const val KEY_PENDING_FRAMES = "pending_frames"
    }
}
