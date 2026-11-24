package com.spektralab.android.core.model

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 照片内的日期/参数印记，完全按像素坐标画，所以角落和排版是可控的。
 *
 * 引擎那边只在「有片边 + 位置=胶片边缘」时印字（那是真实胶片在片边上的样子，
 * 字随胶片方向旋转属于正常）。其余情况（位置=照片内，或者干脆没开片边）
 * 都走这里，保证正立在所选角落、不会被画面裁掉。
 *
 * 两种用法：
 * - [apply]：直接画进一张可变的 Bitmap（预览落地、位图回退导出）。
 * - [overlay]：只渲染日期所在的那一条带（[Overlay]），供逐行流式导出的
 *   PNG / JPEG / TIFF 写入器按像素混合 —— 不额外占一整张图的堆内存。
 */
internal object DateImprintPaint {

    private const val ARGB_R = 255
    private const val ARGB_G = 138
    private const val ARGB_B = 50

    /** 日期所在的一条带（含 alpha），[top] 是它在成片里的首行。 */
    internal class Overlay(val bitmap: Bitmap, val top: Int) {
        val height: Int get() = bitmap.height
        fun row(y: Int, out: IntArray) {
            val local = y - top
            if (local < 0 || local >= bitmap.height) return
            bitmap.getPixels(out, 0, bitmap.width, 0, local, bitmap.width, 1)
        }
        fun recycle() = bitmap.recycle()
    }

    /** 有片边 + 位置=胶片边缘时引擎自己印，这里不动。 */
    private fun paintParams(params: EditorParams): Boolean =
        params.dateImprintActive && params.imprintInFrame

    private fun linesOf(params: EditorParams): List<Pair<String, Float>> {
        val dateText = params.effectiveDateText()
        val dataText = params.effectiveDataText()
        // 样式=拍摄数据：只印参数（参数空就退回日期）；其余样式：日期为主，
        // 打开「打印拍摄参数」时在日期上方多一行小字参数。
        return when {
            params.dateImprintStyle == "data" ->
                listOfNotNull((dataText.ifBlank { dateText }).takeIf { it.isNotBlank() }?.let { it to 1f })
            dataText.isNotBlank() -> listOf(dataText to 0.62f, dateText to 1f)
            dateText.isNotBlank() -> listOf(dateText to 1f)
            else -> emptyList()
        }
    }

    private class Block(val image: Bitmap, val x: Int, val y: Int)

    /** 排出每一行字的位置（绝对坐标），失败返回 null。 */
    private fun layout(width: Int, height: Int, params: EditorParams, lines: List<Pair<String, Float>>): List<Block>? {
        if (width <= 0 || height <= 0) return null
        val base = width * 0.030f * params.dateImprintSize          // 主行字号
        val margin = width * 0.030f
        val gap = base * 0.28f
        val alpha = (0.55f + params.dateImprintEv.coerceIn(-2f, 8f) / 8f * 0.45f).coerceIn(0.25f, 1f)
        val color = Color.argb((alpha * 255).toInt(), ARGB_R, ARGB_G, ARGB_B)

        // 按引擎的排版习惯：字号固定、不为了塞下而缩字，超宽就由画面/片门裁掉；
        // 只把起点夹进画面内（否则右对齐的长行会整段跑出左边，等于什么都没印）。
        val right = params.dateImprintCorner == "br" || params.dateImprintCorner == "tr"
        val bottom = params.dateImprintCorner == "br" || params.dateImprintCorner == "bl"
        // 上角落时把日期放在最上、参数排到它下面，否则参数会被顶出画面外裁掉。
        val ordered = if (bottom) lines else lines.reversed()

        val rendered = ordered.map { (text, weight) ->
            val size = base * weight
            render(text, size, params, color) to (size * 1.28f)
        }
        val totalHeight = rendered.sumOf { it.second.toDouble() }.toFloat() + gap * (rendered.size - 1)

        val blocks = mutableListOf<Block>()
        var y = if (bottom) height - margin - totalHeight else margin
        try {
            rendered.forEach { (image, lineHeight) ->
                val x = if (right) width - margin - image.width else margin
                // 小字号的那一行在底部对齐时贴着自己的行高，避免上浮过大。
                val dy = (lineHeight - image.height) / 2f
                blocks += Block(image, x.toInt().coerceAtLeast(0), (y + dy).toInt())
                y += lineHeight + gap
            }
        } catch (failure: Throwable) {
            rendered.forEach { it.first.recycle() }
            throw failure
        }
        return blocks
    }

    fun apply(bitmap: Bitmap, params: EditorParams): Bitmap {
        if (!paintParams(params)) return bitmap
        val lines = linesOf(params)
        if (lines.isEmpty()) return bitmap

        val target = try {
            if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return bitmap
        } catch (_: Exception) { return bitmap }

        return runCatching {
            val blocks = layout(target.width, target.height, params, lines) ?: return@runCatching bitmap
            val canvas = Canvas(target)
            blocks.forEach { block ->
                canvas.drawBitmap(block.image, block.x.toFloat(), block.y.toFloat(), null)
                block.image.recycle()
            }
            target
        }.getOrDefault(bitmap)
    }

    /**
     * 只渲染日期占用的那一条带，供流式导出按行混合。
     * 调用方负责 [Overlay.recycle]。
     */
    fun overlay(width: Int, height: Int, params: EditorParams): Overlay? {
        if (!paintParams(params)) return null
        val lines = linesOf(params)
        if (lines.isEmpty()) return null
        return runCatching {
            val blocks = layout(width, height, params, lines) ?: return@runCatching null
            if (blocks.isEmpty()) return@runCatching null
            val first = blocks.minOf { it.y }.coerceIn(0, height - 1)
            val last = blocks.maxOf { min(height, it.y + it.image.height) }.coerceIn(first + 1, height)
            val band = Bitmap.createBitmap(width, max(1, last - first), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(band)
            blocks.forEach { block ->
                canvas.drawBitmap(block.image, block.x.toFloat(), (block.y - first).toFloat(), null)
                block.image.recycle()
            }
            Overlay(band, first)
        }.getOrNull()
    }

    /** 把带 alpha 的印记像素叠到 8-bit ARGB 输出上。 */
    fun blend(base: Int, top: Int): Int {
        val a = Color.alpha(top)
        if (a == 0) return base
        val t = a / 255f
        val br = (base ushr 16) and 255; val bg = (base ushr 8) and 255; val bb = base and 255
        val r = (br + (Color.red(top) - br) * t + 0.5f).toInt().coerceIn(0, 255)
        val g = (bg + (Color.green(top) - bg) * t + 0.5f).toInt().coerceIn(0, 255)
        val b = (bb + (Color.blue(top) - bb) * t + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun textPaint(size: Float, style: String, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = if (style == "lcd") Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        textSize = size
        this.color = color
        letterSpacing = if (style == "lcd") 0.04f else 0.02f
    }

    /** 点阵样式：小字号栅格化后再按点距铺圆点，得到老式后背的点阵观感。 */
    private fun render(text: String, size: Float, params: EditorParams, color: Int): Bitmap {
        if (params.dateImprintStyle != "dots") {
            val paint = textPaint(size, params.dateImprintStyle, color)
            val w = ceil(paint.measureText(text)).toInt().coerceIn(1, 4096)
            val h = ceil(size * 1.30f).toInt().coerceIn(1, 2048)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).drawText(text, 0f, h * 0.80f, paint)
            return out
        }

        val maskHeight = 9
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = maskHeight.toFloat()
            this.color = Color.WHITE
        }
        val maskWidth = ceil(maskPaint.measureText(text)).toInt().coerceIn(1, 512)
        val mask = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawText(text, 0f, maskHeight - 1f, maskPaint)

        val pitch = (size / 9f).coerceAtLeast(1.4f)
        val outW = ceil(maskWidth * pitch).toInt().coerceAtLeast(1)
        val outH = ceil(maskHeight * pitch).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        for (row in 0 until maskHeight) for (col in 0 until maskWidth) {
            if (Color.alpha(mask.getPixel(col, row)) < 90) continue
            canvas.drawCircle((col + 0.5f) * pitch, (row + 0.5f) * pitch, pitch * 0.46f, dot)
        }
        mask.recycle()
        return out
    }
}
