package com.spektralab.android.data.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** 效果图缩略图缓存。
 *
 *  编辑页每渲染出一帧成品，这里就按「记录身份 + 参数修订号」落一张长边 512 的 WebP；
 *  首页宫格、图库列表和观片台的缩略图先读这张图，读到就显示调过参数的画面，
 *  读不到（从没在编辑页打开过、或参数又改过一版）仍按原办法解原图，绝不空着。
 *
 *  目录放在 cacheDir：空间紧张时系统可以整目录回收，界面会自动退回原图，不影响导出和参数。
 *  任何一次读写失败都当作未命中静默处理，不向用户报错。
 */
internal object EffectThumbnailStore {
    /** 长边目标：1080 宽机器上宫格单格约 531 物理像素，512 已经贴住实际显示尺寸。 */
    const val MAX_EDGE = 512

    /** 有损 WebP 在 Android 9 上只能走已废弃但可用的 WEBP 常量，不能用 API 30 才有的 WEBP_LOSSY。 */
    private const val QUALITY = 85
    private const val DIR = "effect-thumbs-512-v1"
    /** 总量上限：约 500 张的体量，超了按最后写入时间淘汰最旧的效果图。 */
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    private fun directory(context: Context) = File(context.cacheDir, DIR)

    private fun fileFor(context: Context, assetId: String, revision: Long): File? {
        // assetId 是 UUID，仍校验一次字符集，异常值直接当未命中，免得拼出越界路径。
        if (assetId.isEmpty() || assetId.length > 64) return null
        if (!assetId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        return File(directory(context), "$assetId-$revision.webp")
    }

    /** 命中返回新解码的位图（归调用方回收），未命中、文件坏了或被系统清掉都返回 null。 */
    fun get(context: Context, assetId: String, revision: Long): Bitmap? = runCatching {
        val source = fileFor(context, assetId, revision) ?: return null
        if (!source.isFile || source.length() <= 0L) return null
        BitmapFactory.decodeFile(source.path)
    }.getOrNull()

    /** 落盘一张效果图：从渲染帧缩到长边 512 再编码，同键已存在就不重复写。 */
    fun put(context: Context, assetId: String, revision: Long, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        val target = fileFor(context, assetId, revision) ?: return
        if (target.isFile && target.length() > 0L) return
        runCatching {
            val scaled = scaleToTarget(bitmap)
            val directory = directory(context)
            if (!directory.isDirectory) directory.mkdirs()
            val temp = File(target.path + ".tmp")
            temp.outputStream().use {
                check(scaled.compress(Bitmap.CompressFormat.WEBP, QUALITY, it)) { "EFFECT_THUMB_WRITE_FAILED" }
            }
            if (scaled !== bitmap) scaled.recycle()
            if (temp.length() <= 0L || !temp.renameTo(target)) {
                temp.delete()
                return
            }
            // 同一张照片只留最新一版效果图：旧修订号不会再被库里的行读到，留着只是占额度。
            removeOtherRevisions(context, assetId, revision)
            trim(context)
        }
    }

    /** 参数又改过一版时清掉旧效果图，避免旧修订号的文件一直占着额度。 */
    fun removeOtherRevisions(context: Context, assetId: String, keepRevision: Long) {
        runCatching {
            directory(context).listFiles()?.forEach { file ->
                if (file.name == "$assetId-$keepRevision.webp") return@forEach
                val revision = file.name.removePrefix("$assetId-").removeSuffix(".webp").toLongOrNull() ?: return@forEach
                if (revision != keepRevision && file.name.startsWith("$assetId-")) file.delete()
            }
        }
    }

    private fun scaleToTarget(bitmap: Bitmap): Bitmap {
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge <= MAX_EDGE || edge <= 0) return bitmap
        val ratio = MAX_EDGE.toFloat() / edge
        return Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
    }

    private fun trim(context: Context) {
        runCatching {
            val files = directory(context).listFiles()?.filter { it.isFile && it.name.endsWith(".webp") } ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_TOTAL_BYTES) return
            files.sortedBy { it.lastModified() }.forEach { file ->
                if (total <= MAX_TOTAL_BYTES) return
                total -= file.length()
                file.delete()
            }
        }
    }
}
