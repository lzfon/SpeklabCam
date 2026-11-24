package com.spektralab.android.engine

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RAW 解码（LibRaw，JNI）。
 *
 * LibRaw 接受文件路径：完整可寻址描述符直接读取；分段/管道或无法原生重开的来源暂存到 cacheDir。
 * 解码输出线性 ProPhoto float RGB（已应用相机白平衡），以 input_color_space="ProPhoto RGB" 交给引擎。
 */
object RawDecoder {

    private const val DNG_VERSION_TAG = 50706

    init { System.loadLibrary("spektralab") }

    /** Bound to the native library and fixed source pin, never a manually duplicated cache tag. */
    internal val decoderIdentity: String by lazy { nativeDecoderIdentity() }
    private external fun nativeDecoderIdentity(): String


    private external fun nativeDecodeRaw(path: String, halfSize: Boolean, userMul: FloatArray?,
                                         cancellation: RawDecodeCancellation?): RawDecodeResult
    private external fun nativeDecodeForExport(path: String, maxLongEdge: Int, userMul: FloatArray?,
                                               cancellation: RawDecodeCancellation?): NativeRawImage
    private external fun nativeReadCameraMatrix(path: String): FloatArray?
    private external fun nativeReadCameraInfo(path: String): FloatArray?
    private external fun nativeDecodeNeutral(path: String, userMul: FloatArray?, cancellation: RawDecodeCancellation?): RawNeutralPreview
    private external fun nativeCameraPreview(path: String, cancellation: RawDecodeCancellation?): ByteArray?
    private external fun nativeDisplayPreview(path: String, maxLongEdge: Int, cancellation: RawDecodeCancellation?): android.graphics.Bitmap?

    private external fun nativeApplyLensCorrection(
        rgb: FloatArray, width: Int, height: Int,
        distModel: String, terms: FloatArray, vigK: FloatArray, tcaVr: Float, tcaVb: Float,
    ): Boolean

    /** 在已解码的线性 ProPhoto float RGB 上就地做镜头校正（畸变 + 暗角 + 简易 TCA）。 */
    fun applyLensCorrection(rgb: FloatArray, width: Int, height: Int, profile: LensProfile): Boolean =
        nativeApplyLensCorrection(
            rgb, width, height,
            profile.distModel, profile.distTerms, profile.vigK, profile.tcaVr, profile.tcaVb,
        )

    private val RAW_EXTENSIONS = setOf(
        "cr2", "cr3", "nef", "nrw", "arw", "dng", "raf", "orf", "rw2", "pef",
        "srw", "rwl", "3fr", "erf", "mef", "mos", "raw", "x3f", "kdc", "dcr", "sr2",
    )

    /** 按文件名（小写）判断是否是受支持的 RAW。 */
    fun isRaw(displayName: String?): Boolean {
        if (displayName == null) return false
        val dot = displayName.lastIndexOf('.')
        if (dot < 0) return false
        return displayName.substring(dot + 1).lowercase() in RAW_EXTENSIONS
    }

    /** Also recognize DNG data mislabeled with .tif/.tiff by inspecting only its TIFF IFD header. */
    fun isRaw(context: Context, uri: Uri, displayName: String?): Boolean {
        if (isRaw(displayName)) return true
        val extension = displayName?.substringAfterLast('.', "")?.lowercase()
            ?: uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        if (extension !in setOf("tif", "tiff")) return false
        return try {
            val header = ByteArray(64 * 1024)
            val count = context.contentResolver.openInputStream(uri)?.use { input ->
                var total = 0
                while (total < header.size) {
                    val read = input.read(header, total, header.size - total)
                    if (read <= 0) break
                    total += read
                }
                total
            } ?: return false
            if (count < 8) return false
            val order = when {
                header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
                header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
                else -> return false
            }
            val buffer = ByteBuffer.wrap(header, 0, count).order(order)
            if ((buffer.getShort(2).toInt() and 0xffff) != 42) return false
            val ifd = buffer.getInt(4).toLong() and 0xffff_ffffL
            if (ifd + 2 > count) return false
            val entries = buffer.getShort(ifd.toInt()).toInt() and 0xffff
            if (ifd + 2L + entries * 12L > count) return false
            (0 until entries).any { index ->
                (buffer.getShort((ifd + 2L + index * 12L).toInt()).toInt() and 0xffff) == DNG_VERSION_TAG
            }
        } catch (_: Exception) { false }
    }

    /** 解码一个 content URI 指向的 RAW。[halfSize] 用半分辨率（预览省内存）。
     *  [userMul] 非空时用它替代相机 as-shot 白平衡（R, G, B, G2）。失败抛 [SpkException]。 */
    fun decode(context: Context, uri: Uri, halfSize: Boolean, userMul: FloatArray? = null,
               cancellation: RawDecodeCancellation? = null): RawDecodeResult {
        return withRawInput(context, uri, "raw_input_", cancellation) { path ->
            nativeDecodeRaw(path, halfSize, userMul, cancellation)
        }
    }

    fun decodeForExport(context: Context, uri: Uri, maxLongEdge: Int, userMul: FloatArray? = null,
                        cancellation: RawDecodeCancellation? = null): NativeRawImage {
        return withRawInput(context, uri, "raw_export_", cancellation) { path ->
            nativeDecodeForExport(path, maxLongEdge, userMul, cancellation)
        }
    }

    /** Header metadata is enough to choose custom white balance before demosaicing. */
    fun readCameraMatrix(context: Context, uri: Uri, cancellation: RawDecodeCancellation? = null): FloatArray? =
        withRawInput(context, uri, "raw_input_", cancellation, ::nativeReadCameraMatrix)

    internal fun readCameraInfo(context: Context, uri: Uri, cancellation: RawDecodeCancellation? = null): FloatArray? =
        withRawInput(context, uri, "raw_input_", cancellation, ::nativeReadCameraInfo)

    internal fun neutralPreview(context: Context, uri: Uri, userMul: FloatArray?, cancellation: RawDecodeCancellation?): RawNeutralPreview =
        withRawInput(context, uri, "raw_input_", cancellation) { nativeDecodeNeutral(it, userMul, cancellation) }

    /** Camera-rendered JPEG only. It is an original-view reference, never an engine input. */
    internal fun cameraPreview(context: Context, uri: Uri, cancellation: RawDecodeCancellation?): ByteArray? =
        withRawInput(context, uri, "raw_preview_", cancellation) { nativeCameraPreview(it, cancellation) }

    /** Standard as-shot display rendition for a RAW without a usable embedded camera JPEG. */
    internal fun displayPreview(context: Context, uri: Uri, maxLongEdge: Int, cancellation: RawDecodeCancellation?): android.graphics.Bitmap? =
        withRawInput(context, uri, "raw_preview_", cancellation) { nativeDisplayPreview(it, maxLongEdge, cancellation) }

    /** Descriptor lifetime encloses LibRaw's synchronous read; no decoded pixels are cached here. */
    internal fun <T> withRawInput(context: Context, uri: Uri, prefix: String,
                                 cancellation: RawDecodeCancellation?, decode: (String) -> T): T {
        val started = SystemClock.elapsedRealtime()
        checkCancellation(cancellation)
        if (uri.scheme == "file") {
            val path = uri.path ?: throw SpkException("无法读取 RAW 文件")
            return decode(path).also { logDecode("file", started) }
        }

        val descriptor = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r") }.getOrNull()
        descriptor?.use { asset ->
            val opened = asset.parcelFileDescriptor
            val stat = runCatching { Os.fstat(opened.fileDescriptor) }.getOrNull()
            val regular = stat != null && OsConstants.S_ISREG(stat.st_mode)
            val seekable = regular && runCatching {
                Os.lseek(opened.fileDescriptor, asset.startOffset, OsConstants.SEEK_SET) == asset.startOffset
            }.getOrDefault(false)
            val wholeFile = asset.startOffset == 0L &&
                (asset.declaredLength < 0 || asset.declaredLength == stat?.st_size)
            if (seekable && wholeFile) {
                try {
                    checkCancellation(cancellation)
                    return decode("/proc/self/fd/${opened.fd}").also { logDecode("descriptor", started) }
                } catch (failure: SpkException) {
                    checkCancellation(cancellation)
                    // Fixed LibRaw pin reports open_file IO failures at these two stages.
                    // Never retry unpack/process errors, absent optional previews, or cancellation.
                    if (failure.message != "RAW open: Input/output error" &&
                        failure.message != "RAW metadata: Input/output error") throw failure
                    try {
                        // dup/reopen can have changed the shared offset. Reuse the granted fd;
                        // fetching the URI again can repeat a cloud download or lose permission.
                        Os.lseek(opened.fileDescriptor, 0, OsConstants.SEEK_SET)
                        return stageInput(context, prefix, asset.createInputStream(), cancellation,
                            asset.declaredLength, decode).also { logDecode("descriptor-staged", started) }
                    } catch (fallback: Exception) {
                        if (fallback !== failure) fallback.addSuppressed(failure)
                        throw fallback
                    }
                }
            }
            return stageInput(context, prefix, asset.createInputStream(), cancellation,
                asset.declaredLength, decode).also { logDecode("staged", started) }
        }

        val input = context.contentResolver.openInputStream(uri) ?: throw SpkException("无法读取 RAW 文件")
        return stageInput(context, prefix, input, cancellation, -1, decode).also { logDecode("staged", started) }
    }

    private fun <T> stageInput(context: Context, prefix: String, input: InputStream,
                               cancellation: RawDecodeCancellation?, length: Long, decode: (String) -> T): T = input.use { source ->
        checkCancellation(cancellation)
        val temporary = File.createTempFile(prefix, ".raw", context.cacheDir)
        try {
            temporary.outputStream().use { target ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    checkCancellation(cancellation)
                    if (length >= 0 && copied == length) break
                    val count = source.read(buffer, 0,
                        if (length >= 0) minOf(buffer.size.toLong(), length - copied).toInt() else buffer.size)
                    if (count < 0) {
                        if (length >= 0) throw EOFException("RAW input ended before its declared length")
                        break
                    }
                    target.write(buffer, 0, count)
                    copied += count
                }
            }
            checkCancellation(cancellation)
            decode(temporary.absolutePath)
        } finally {
            temporary.delete()
        }
    }

    private fun logDecode(input: String, started: Long) {
        Log.i("SpkRaw", "input=$input decode_ms=${SystemClock.elapsedRealtime() - started}")
    }

    private fun checkCancellation(cancellation: RawDecodeCancellation?) {
        if (cancellation?.isCancelled() == true) throw SpkException("RAW decode cancelled")
    }
}
