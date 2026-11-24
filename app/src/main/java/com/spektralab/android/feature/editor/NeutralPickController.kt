package com.spektralab.android.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spektralab.android.R
import com.spektralab.android.core.color.NeutralWhiteBalance
import com.spektralab.android.core.model.EditSnapshot
import com.spektralab.android.data.export.ExportSourceSnapshot
import com.spektralab.android.engine.CameraWhiteBalance
import com.spektralab.android.engine.ColorPipeline
import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.RawDecodeCancellation
import com.spektralab.android.engine.RawDecoder
import com.spektralab.android.engine.RawNeutralPreview
import com.spektralab.android.engine.SpkResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One cancellable, frozen measurement session. Closing it releases source FDs and stage pixels. */
internal class NeutralPickController(private val context: Context, private val scope: CoroutineScope) {
    data class State(val target: NeutralWhiteBalance.Target, val image: Bitmap? = null,
        val working: Boolean = true, val point: Pair<Double, Double>? = null,
        val balance: NeutralWhiteBalance.Balance? = null, val note: String? = null)
    var state by mutableStateOf<State?>(null)
        private set
    private var job: Job? = null
    private var visit = 0L
    private var raw: RawNeutralPreview? = null
    private var print: SpkResult? = null
    private var source: ExportSourceSnapshot? = null
    private var current: () -> Boolean = { false }
    private var commit: (NeutralWhiteBalance.Balance) -> Unit = {}

    fun open(target: NeutralWhiteBalance.Target, uri: Uri?, edit: EditSnapshot, result: SpkResult?, colorSpace: String,
             isCurrent: () -> Boolean, onApply: (NeutralWhiteBalance.Balance) -> Unit) {
        close()
        val token = visit
        sourceUri = uri
        current = isCurrent; commit = onApply
        state = State(target)
        job = scope.launch {
            var staged: ExportSourceSnapshot? = null
            var image: Bitmap? = null
            try {
                val task = currentCoroutineContext()
                val checkActive = { check(token == visit && task.isActive) { "Neutral pick cancelled" } }
                val rawResult = withContext(Dispatchers.IO) {
                    if (target != NeutralWhiteBalance.Target.DECODE) null else {
                        staged = ExportSourceSnapshot.create(context, requireNotNull(uri), false, checkActive)
                        val path = Uri.fromFile(File("/proc/self/fd/${staged!!.descriptor.fd}"))
                        val cancellation = RawDecodeCancellation { checkActive(); false }
                        val params = edit.toParams()
                        val info = RawDecoder.readCameraInfo(context, path, cancellation)
                        var decoded = if (info == null || info.take(9).all { it == 0f }) RawDecoder.neutralPreview(context, path, null, cancellation) else null
                        val xyz = decoded?.cameraXyz ?: info!!.copyOfRange(0, 9)
                        val shot = decoded?.cameraMultipliers ?: info!!.copyOfRange(9, 13)
                        val multipliers = CameraWhiteBalance.resolve(params, xyz, shot)
                        if (multipliers != null || decoded == null) decoded = RawDecoder.neutralPreview(context, path, multipliers, cancellation)
                        decoded
                    }
                }
                image = withContext(Dispatchers.Default) {
                    if (rawResult != null) Bitmap.createBitmap(ColorPipeline.proPhotoLinearToSrgbArgb(rawResult.rgb,
                        rawResult.width, rawResult.height), rawResult.width, rawResult.height, Bitmap.Config.ARGB_8888)
                    else {
                        val frame = requireNotNull(result)
                        val neutral = Grade(1f,1f,1f,1f,0f,0f,1f,0f,0f,0f,0f,FloatArray(3),FloatArray(3),FloatArray(3))
                        val task = currentCoroutineContext()
                        ColorPipeline.applyGradeToBitmap(frame.rgba16, frame.width, frame.height, neutral, colorSpace) {
                            task.ensureActive()
                        }.first
                    }
                }
                check(token == visit && current()) { "Neutral source changed" }
                source = staged; staged = null
                raw = rawResult; print = if (rawResult == null) result else null
                state = State(target, image = image, working = false)
                image = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (token == visit) state = state?.copy(working = false, note = "无法读取原片，请重试")
            } catch (_: OutOfMemoryError) {
                if (token == visit) state = state?.copy(working = false, note = "无法读取原片，请重试")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { staged?.close() }
                image?.recycle()
            }
        }
    }

    fun pick(x: Double, y: Double) {
        val shown = state ?: return
        if (shown.working || shown.image == null) return
        if (!current()) { state = shown.copy(balance = null, note = "照片或编辑已更改，请重新拾色"); return }
        val width = shown.image.width; val height = shown.image.height
        val patch = NeutralWhiteBalance.sample(width, height, x, y) { px, py ->
            raw?.let { image -> DoubleArray(3) { image.rgb[(py * width + px) * 3 + it].toDouble() } }
                ?: print?.let { frame ->
                    val offset = (py * width + px) * 4
                    if ((frame.rgba16[offset + 3].toInt() and 65535) < 65500) null
                    else DoubleArray(3) { (frame.rgba16[offset + it].toInt() and 65535) / 65535.0 }
                }
        }
        val balance = patch?.let {
            if (shown.target == NeutralWhiteBalance.Target.PRINT) NeutralWhiteBalance.printBalance(it.rgb)
            else raw?.let { data -> CameraWhiteBalance.neutral(it.rgb, data.calibration, data.cameraXyz) }
                ?.let { found -> NeutralWhiteBalance.Balance(found.temperature, found.tint) }
        }
        state = shown.copy(point = x to y, balance = balance,
            note = if (balance == null) "这个点取不到可用颜色，换个位置" else null)
    }

    fun apply() {
        val shown = state ?: return
        val balance = shown.balance ?: return
        if (shown.working) return
        val token = visit
        state = shown.copy(working = true)
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val task = currentCoroutineContext()
                    source?.verifyOriginal(context, sourceUri ?: error("Neutral source missing"), false) {
                        check(token == visit && task.isActive) { "Neutral pick cancelled" }
                    }
                }
                check(token == visit && current()) { "Neutral source changed" }
                commit(balance)
                close()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (token == visit) state = shown.copy(working = false, balance = null, note = "照片或编辑已更改，请重新拾色")
            }
        }
    }

    private var sourceUri: Uri? = null

    fun close() {
        visit++
        job?.cancel(); job = null
        source?.close(); source = null
        raw = null; print = null
        sourceUri = null
        current = { false }; commit = {}
        // The last Compose frame may still reference this bitmap. Drop ownership;
        // Android releases it when that frame and this state become unreachable.
        state = null
    }
}
