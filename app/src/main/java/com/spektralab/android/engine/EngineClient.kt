package com.spektralab.android.engine

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import org.json.JSONObject

/**
 * 引擎客户端：Kotlin 侧对 `spk_engine.h` C ABI 的封装。
 *
 * 原生指针以 [Long] 在 JNI 两侧传递（`reinterpret_cast` 回 `spk_engine*` /
 * `spk_session*`）。所有 native 方法都声明为 `private external`，由
 * `jni_bridge.cpp` 实现。
 *
 * 调用约定：
 * - 会失败的调用抛 [SpkException]（消息来自 `spk_last_error`）；
 * - 渲染返回 [SpkResult]，调用方用完即弃（像素已拷贝进 `ShortArray`）。
 */
class EngineClient private constructor(
    private var enginePtr: Long,
    private val resourcesDir: String,
) : Closeable {

    private var sessionPtr: Long = 0L
    private var sceneBinding = NativeSceneSession.Binding(false,0f,0f)
    private var boundJson = "{}"
    @Volatile private var sceneIngress: java.util.concurrent.atomic.AtomicBoolean? = null
    private inline fun <T> ingress(block: (java.util.concurrent.atomic.AtomicBoolean) -> T): T {
        val token = java.util.concurrent.atomic.AtomicBoolean(false)
        sceneIngress = token
        try { return block(token) } finally { sceneIngress = null }
    }
    internal fun requiresSourceReopen(json: String): Boolean = NativeSceneSession.binding(json) != sceneBinding
    // cancel() is the only JNI session call made from outside the serialized engine dispatcher.
    private val sessionLifetime = ReentrantReadWriteLock(true)

    @Volatile
    var isDeviceLost: Boolean = false
        private set

    @Volatile
    var deviceGeneration: Long = 1L
        private set

    private var closed = false

    /** Called on the serialized engine owner; progress/cancel only observe the latch. */
    internal fun recordFailure(error: Throwable) {
        if (EngineFailure.isDeviceLost(error)) isDeviceLost = true
    }

    // ---- 引擎自省 ---------------------------------------------------------

    fun capabilities(): String = withEngine { nativeCapabilities(enginePtr) }
    fun paramsSchema(): String = withEngine { nativeParamsSchema(enginePtr) }
    fun memoryReport(): String = if (isDeviceLost) {
        "{\"device_lost\":true,\"device_generation\":$deviceGeneration}"
    } else withEngine { nativeMemoryReport(enginePtr) }
    fun printLutCatalog(): String = withEngine { nativePrintLutCatalog(enginePtr) }
    fun lastError(): String = if (isDeviceLost) "VK_ERROR_DEVICE_LOST" else withEngine { nativeLastError(enginePtr) }

    fun warmUp(filmStock: String, printStock: String): String =
        withEngine { nativeWarmUp(enginePtr, filmStock, printStock) }

    // ---- 会话 -------------------------------------------------------------

    /**
     * 打开一帧。像素是线性 float（ProPhoto RGB），紧凑排列，行优先、首行为顶。
     * 已有会话时自动释放。
     */
    fun open(data: FloatArray, width: Int, height: Int, channels: Int, paramsJson: String? = null): Long {
        prepareOpen()
        val binding=NativeSceneSession.binding(paramsJson)
        require(!binding.required || channels==3)
        val opened = withEngine { if(binding.required) ingress { NativeSceneSession.open(enginePtr,data,width,height,0,null,paramsJson ?: "{}",binding,it) }
            else nativeOpen(enginePtr, data, width, height, channels, paramsJson) }
        sceneBinding=binding;boundJson=paramsJson ?: "{}"
        sessionLifetime.write { sessionPtr = opened }
        return sessionPtr
    }

    fun openBitmap(bitmap: Bitmap, paramsJson: String): Long {
        prepareOpen()
        val binding=NativeSceneSession.binding(paramsJson)
        val opened = withEngine { if(binding.required) ingress { NativeSceneSession.open(enginePtr,null,bitmap.width,bitmap.height,0,bitmap,paramsJson,binding,it) }
            else nativeOpenBitmap(enginePtr, bitmap, paramsJson) }
        sceneBinding=binding;boundJson=paramsJson
        sessionLifetime.write { sessionPtr = opened }
        return sessionPtr
    }

    fun openRaw(image: NativeRawImage, paramsJson: String): Long {
        check(image.handle != 0L) { "RAW image closed" }
        prepareOpen()
        val binding=NativeSceneSession.binding(paramsJson)
        val opened = withEngine { if(binding.required) ingress { NativeSceneSession.open(enginePtr,null,image.width,image.height,image.handle,null,paramsJson,binding,it) }
            else nativeOpenRaw(enginePtr, image.handle, paramsJson) }
        sceneBinding=binding;boundJson=paramsJson
        sessionLifetime.write { sessionPtr = opened }
        return sessionPtr
    }

    fun renderForExport(): NativeRenderResult = requireSession { if(sceneBinding.filmOff) NativeSceneSession.export(sessionPtr) else nativeRenderForExport(enginePtr, sessionPtr) }

    /** Consume an export-only session; the returned CPU pixels own their storage. */
    fun renderExportOnce(): NativeRenderResult {
        try {
            return requireSession { if(sceneBinding.filmOff) NativeSceneSession.export(sessionPtr) else nativeRenderExportOnce(enginePtr, sessionPtr) }
        } finally {
            closeSession()
        }
    }

    internal fun renderExportResolved(filmSeed: Long, printSeed: Long): NativeRenderResult {
        try {
            return requireSession { if(sceneBinding.filmOff) NativeSceneSession.export(sessionPtr) else nativeRenderExportResolved(enginePtr, sessionPtr, filmSeed, printSeed) }
        } finally { closeSession() }
    }

    fun closeSession() {
        sessionLifetime.write {
            releaseSessionLocked()
        }
    }

    private fun releaseSessionLocked() {
        val session = sessionPtr
        if (session != 0L) {
            sessionPtr = 0L
            if(sceneBinding.filmOff) NativeSceneSession.release(session) else nativeSessionRelease(session)
            sceneBinding=NativeSceneSession.Binding(false,0f,0f)
        }
    }

    /** Explicit source binding is the only recovery boundary; never retry an in-flight render. */
    private fun prepareOpen() = sessionLifetime.write {
        check(!closed) { "engine closed" }
        releaseSessionLocked()
        if (isDeviceLost || enginePtr == 0L) {
            val previous = enginePtr
            enginePtr = 0L
            if (previous != 0L) nativeEngineDestroy(previous)
            val recreated = nativeEngineCreate(resourcesDir)
            check(recreated != 0L) { "spk_engine_create failed during device recovery" }
            enginePtr = recreated
            deviceGeneration++
            isDeviceLost = false
        }
        check(enginePtr != 0L) { "engine not created" }
    }

    /** Release every GPU allocation before handing ownership to the isolated export host. */
    internal fun park() = sessionLifetime.write {
        check(!closed) { "engine closed" }
        releaseSessionLocked()
        val previous = enginePtr
        enginePtr = 0L
        if (previous != 0L) nativeEngineDestroy(previous)
    }

    fun setParams(deltaJson: String): String = requireSession {
        val merged = JSONObject(boundJson).apply {
            val delta = JSONObject(deltaJson)
            delta.keys().forEach { put(it, delta.get(it)) }
        }.toString()
        check(!requiresSourceReopen(merged)) { "Source geometry changed; reopen source" }
        if (sceneBinding.filmOff) { NativeSceneSession.update(sessionPtr, deltaJson); boundJson = merged; "{}" }
        else nativeSetParams(enginePtr, sessionPtr, checkNotNull(NativeSceneSession.engineJson(deltaJson)))
    }

    /** Apply a UI snapshot without making unchanged capture/material fields invalidate the negative.
     * Read native state inside the session transaction, so solve/open/export restores cannot leave
     * a stale host-side parameter cache. Low-level setParams keeps its explicit delta semantics.
     */
    fun setChangedParams(snapshotJson: String): String = requireSession {
        check(!requiresSourceReopen(snapshotJson)) { "Source geometry changed; reopen source" }
        if(sceneBinding.filmOff) {NativeSceneSession.update(sessionPtr,snapshotJson);boundJson=snapshotJson;return@requireSession "{}"}
        val requested = runCatching { JSONObject(checkNotNull(NativeSceneSession.engineJson(snapshotJson))) }.getOrNull()
            ?: return@requireSession nativeSetParams(enginePtr, sessionPtr, snapshotJson)
        val current = JSONObject(nativeGetParams(enginePtr, sessionPtr))
        val changed = JSONObject()
        requested.keys().forEach { key ->
            val value = requested.get(key)
            val previous = current.opt(key)
            val equal = when {
                value is Number && previous is Number -> value.toDouble() == previous.toDouble()
                value is String && previous is String -> value == previous
                value is Boolean && previous is Boolean -> value == previous
                else -> false // Preserve validation of unknown, null or incorrectly typed fields.
            }
            if (!equal) changed.put(key, value)
        }
        nativeSetParams(enginePtr, sessionPtr, changed.toString())
    }

    fun getParams(): String =
        requireSession { if(sceneBinding.filmOff) boundJson else nativeGetParams(enginePtr, sessionPtr) }

    /** [target] 为 "exposure"、"filter_pack" 或 "both"。 */
    fun solve(target: String): String =
        requireSession { check(!sceneBinding.filmOff) { "Film emulation is disabled" };nativeSolve(enginePtr, sessionPtr, target) }

    fun sceneLatitude(requestJson: String? = null): String =
        requireSession { check(!sceneBinding.filmOff) { "Film emulation is disabled" };nativeSceneLatitude(enginePtr, sessionPtr, requestJson) }

    fun overscanGeometry(): String =
        requireSession { if(sceneBinding.filmOff) "{}" else nativeOverscanGeometry(enginePtr, sessionPtr) }

    // ---- 渲染 -------------------------------------------------------------

    /** 从缓存负片重印（打印侧改动走这条路，交互滑杆的快路径）。 */
    fun render(tier: String): SpkResult =
        requireSession { if(sceneBinding.filmOff) NativeSceneSession.preview(sessionPtr,tier) else nativeRender(enginePtr, sessionPtr, tier) }

    /** 全量渲染（拍摄侧改动走这条路，重建负片）。 */
    fun renderFull(tier: String): SpkResult =
        requireSession { if(sceneBinding.filmOff) NativeSceneSession.preview(sessionPtr,tier) else nativeRenderFull(enginePtr, sessionPtr, tier) }

    fun previewStockLut(printStock: String, tier: String): SpkResult =
        requireSession { if(sceneBinding.filmOff) NativeSceneSession.preview(sessionPtr,tier) else nativePreviewStockLut(enginePtr, sessionPtr, printStock, tier) }

    fun exportDi(printStock: String? = null): SpkResult =
        requireSession { check(!sceneBinding.filmOff) { "DI requires film emulation" };nativeExportDi(enginePtr, sessionPtr, printStock) }

    fun renderDigitalIntermediate(): NativeRenderResult =
        requireSession { check(!sceneBinding.filmOff) { "DI requires film emulation" };nativeRenderDigitalIntermediate(enginePtr, sessionPtr) }

    internal fun renderDigitalIntermediateResolved(filmSeed:Long,printSeed:Long):NativeRenderResult {
        try {return requireSession {check(!sceneBinding.filmOff){"DI requires film emulation"}
            nativeRenderDigitalIntermediateResolved(enginePtr,sessionPtr,filmSeed,printSeed)}}
        finally {closeSession()}
    }

    // ---- 控制 / 色彩 ------------------------------------------------------

    /**
     * Cancel may run across threads, but it must not race the native session destructor.
     * The read lock lets it run beside an active render; closeSession/close take the write lock.
     */
    fun cancel(progressId: String? = null) = sessionLifetime.read {
        sceneIngress?.set(true)
        val session = sessionPtr
        if (!isDeviceLost && session != 0L) {if(sceneBinding.filmOff) NativeSceneSession.cancel(session) else nativeCancel(session, progressId)}
    }

    /** 读取当前（或指定 [progressId]）渲染的进度。返回 JSON（含 pct/stage）。 */
    fun progress(progressId: String? = null): String = sessionLifetime.read {
        if(sceneBinding.filmOff && sessionPtr!=0L) return@read NativeSceneSession.progress(sessionPtr).let { pct ->
            "{\"pct\":$pct,\"stage\":\"digital\",\"done\":${pct == 100}}"
        }
        withEngine { nativeProgress(enginePtr, sessionPtr, progressId) }
    }

    fun progressSnapshot(): SpkProgress {
        val reply = org.json.JSONObject(progress())
        return SpkProgress(reply.optString("progress_id"), reply.optDouble("pct", 0.0).toFloat().coerceIn(0f, 100f),
            reply.optString("stage", "rendering"), reply.optBoolean("done", false), reply.optBoolean("cancelled", false))
    }

    /** 取工作空间 → 目标空间的输出变换（CAM16 色域映射 + TRC），用于导出与显示。 */
    fun outputTransform(srcCs: String, dstCs: String, gamutCompressJson: String? = null): String =
        withEngine { nativeOutputTransform(enginePtr, srcCs, dstCs, gamutCompressJson) }

    // ---- 生命周期 ---------------------------------------------------------

    override fun close() {
        sessionLifetime.write {
            closed = true
            releaseSessionLocked()
            val engine = enginePtr
            if (engine != 0L) {
                enginePtr = 0L
                nativeEngineDestroy(engine)
            }
        }
    }

    private inline fun <T> withEngine(block: () -> T): T {
        if (isDeviceLost) throw SpkException("VK_ERROR_DEVICE_LOST: reopen the source to recreate the renderer")
        check(enginePtr != 0L) { "engine not created" }
        try {
            return block()
        } catch (error: SpkException) {
            recordFailure(error)
            throw error
        }
    }

    private inline fun <T> requireSession(block: () -> T): T = withEngine {
        check(sessionPtr != 0L) { "no session open" }
        block()
    }

    // ---- native 声明（实例方法，JNI 第二参是 jobject） ---------------------

    private external fun nativeCapabilities(enginePtr: Long): String
    private external fun nativeParamsSchema(enginePtr: Long): String
    private external fun nativeMemoryReport(enginePtr: Long): String
    private external fun nativePrintLutCatalog(enginePtr: Long): String
    private external fun nativeLastError(enginePtr: Long): String
    private external fun nativeWarmUp(enginePtr: Long, filmStock: String, printStock: String): String
    private external fun nativeEngineDestroy(enginePtr: Long)
    private external fun nativeOpen(
        enginePtr: Long,
        data: FloatArray,
        width: Int,
        height: Int,
        channels: Int,
        paramsJson: String?,
    ): Long
    private external fun nativeSessionRelease(sessionPtr: Long)
    private external fun nativeOpenBitmap(enginePtr: Long, bitmap: Bitmap, paramsJson: String): Long
    private external fun nativeOpenRaw(enginePtr: Long, pixels: Long, paramsJson: String): Long
    private external fun nativeRenderForExport(enginePtr: Long, sessionPtr: Long): NativeRenderResult
    private external fun nativeRenderExportOnce(enginePtr: Long, sessionPtr: Long): NativeRenderResult
    private external fun nativeRenderExportResolved(enginePtr: Long, sessionPtr: Long, filmSeed: Long, printSeed: Long): NativeRenderResult
    private external fun nativeSetParams(enginePtr: Long, sessionPtr: Long, deltaJson: String): String
    private external fun nativeGetParams(enginePtr: Long, sessionPtr: Long): String
    private external fun nativeSolve(enginePtr: Long, sessionPtr: Long, target: String): String
    private external fun nativeSceneLatitude(enginePtr: Long, sessionPtr: Long, requestJson: String?): String
    private external fun nativeOverscanGeometry(enginePtr: Long, sessionPtr: Long): String
    private external fun nativeRender(enginePtr: Long, sessionPtr: Long, tier: String): SpkResult
    private external fun nativeRenderFull(enginePtr: Long, sessionPtr: Long, tier: String): SpkResult
    private external fun nativePreviewStockLut(enginePtr: Long, sessionPtr: Long, printStock: String, tier: String): SpkResult
    private external fun nativeExportDi(enginePtr: Long, sessionPtr: Long, printStock: String?): SpkResult
    private external fun nativeRenderDigitalIntermediate(enginePtr: Long, sessionPtr: Long): NativeRenderResult
    private external fun nativeRenderDigitalIntermediateResolved(enginePtr:Long,sessionPtr:Long,filmSeed:Long,printSeed:Long):NativeRenderResult
    private external fun nativeCancel(sessionPtr: Long, progressId: String?)
    private external fun nativeProgress(enginePtr: Long, sessionPtr: Long, progressId: String?): String
    private external fun nativeOutputTransform(
        enginePtr: Long,
        srcCs: String,
        dstCs: String,
        gamutCompressJson: String?,
    ): String

    companion object {
        private const val TAG = "EngineClient"
        private val resourcesLock = Any()
        @Volatile private var preparedResources: String? = null

        init {
            System.loadLibrary("spektralab")
        }

        // ---- native 声明（静态方法，JNI 第二参是 jclass） -----------------
        // @JvmStatic 让它们成为 EngineClient 的静态方法，JNI 符号才会是
        // Java_..._EngineClient_nativeEngineCreate（否则会变成 ..._EngineClient$Companion_...）。

        @JvmStatic
        private external fun nativeEngineCreate(resourcesDir: String): Long

        @JvmStatic
        private external fun nativeBuildInfo(): String

        fun buildInfo(): String = nativeBuildInfo() + "; android-export=scalar-origins-v3-film-windows-1; material-catalog=1; scene-geometry=1; scene-curve=1; output-color=1"

        /**
         * 创建引擎。先把 `assets/engine/` 解压到 `filesDir/engine/`（引擎需要
         * 文件系统路径），再把该目录传给 `spk_engine_create`。
         */
        fun create(context: Context): EngineClient {
            val dir = prepareResources(context)
            val ptr = nativeEngineCreate(dir)
            check(ptr != 0L) { "spk_engine_create failed" }
            Log.i(TAG, "engine created, resources=$dir")
            return EngineClient(ptr, dir)
        }

        private fun prepareResources(context: Context): String {
            val target = File(context.filesDir, "engine")
            val path = File(target, "resources").absolutePath
            if (preparedResources == path && File(path).isDirectory) return path
            return synchronized(resourcesLock) {
                if (preparedResources == path && File(path).isDirectory) return@synchronized path
                check(target.mkdirs() || target.isDirectory)
                copyAssets(context.assets, "engine", target)
                // An app upgrade must not pair an old generated variant with a new kernel.
                // Only our derived shader variants are eligible for this cleanup.
                val packaged = context.assets.list("engine/resources/vulkan").orEmpty().toSet()
                File(path, "vulkan").listFiles()?.filter {
                    it.isFile && (it.name.endsWith(".bounded.spv") || it.name.endsWith(".windowed.spv") ||
                        it.name.endsWith(".windowed-v3.spv")) && it.name !in packaged
                }?.forEach { check(it.delete()) { "Cannot remove obsolete shader variant" } }
                preparedResources = path
                path
            }
        }

        private fun copyAssets(assets: AssetManager, srcPath: String, dest: File) {
            val children = assets.list(srcPath) ?: return
            for (name in children) {
                val childSrc = if (srcPath.isEmpty()) name else "$srcPath/$name"
                val childDest = File(dest, name)
                if (assets.list(childSrc).isNullOrEmpty()) {
                    assets.open(childSrc).use { input ->
                        childDest.parentFile?.mkdirs()
                        // A second process may still be reading the previous inode.
                        val temporary = File.createTempFile(".asset-copy-", ".tmp", childDest.parentFile)
                        try {
                            temporary.outputStream().use { output -> input.copyTo(output) }
                            check(temporary.renameTo(childDest)) { "Cannot install engine asset: $childSrc" }
                        } finally { temporary.delete() }
                    }
                } else {
                    copyAssets(assets, childSrc, childDest)
                }
            }
        }
    }
}
