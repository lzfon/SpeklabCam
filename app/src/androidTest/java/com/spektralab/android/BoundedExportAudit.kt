package com.spektralab.android

import android.app.Instrumentation
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.core.model.ExportRealisation
import com.spektralab.android.engine.EngineClient
import java.security.MessageDigest

/** Independent whole-dispatch path remains the oracle; use dimensions spanning many IIR batches. */
internal class BoundedExportAudit(private val test: Instrumentation) {
    fun run(): String {
        val width = 8193
        val height = 281
        val source = FloatArray(width * height * 3) { index ->
            val pixel = index / 3
            when (index % 3) {
                0 -> .001f + (pixel % width).toFloat() / width * 1.8f
                1 -> .003f + (pixel / width).toFloat() / height
                else -> .18f + (pixel % 47) * .003f
            }
        }
        val cases = listOf(
            "neutral" to EditorParams().apply { grainActive = false; glareActive = false },
            "stochastic" to EditorParams(),
            "glare-only" to EditorParams().apply { grainActive = false; glareActive = true },
            "cinestill-physical" to EditorParams().apply {
                filmId = "cinestill_800t"; paperId = "kodak_2383"
                halationModelVersion = 2; halationHeadroomEv = 4f; halationBoostEv = 2f
            },
        )
        val evidence = mutableListOf<String>()
        for ((name, params) in cases) {
            fun capture(mode: Int): ByteArray = EngineClient.create(test.targetContext).use { engine ->
                engine.open(source, width, height, 3, params.openJson(width, height))
                val identity = ExportRealisation.fresh(params)
                val pixels = when (mode) {
                    0 -> engine.renderForExport()
                    1 -> engine.renderExportOnce()
                    else -> engine.renderExportResolved(identity.film, identity.print)
                }
                pixels.use { result ->
                    check(result.width == width && result.height == height)
                    val digest = MessageDigest.getInstance("SHA-256")
                    val rows = ShortArray(width * 4)
                    val bytes = java.nio.ByteBuffer.allocate(width * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (y in 0 until height) {
                        result.readRows(y, 1, rows); bytes.clear(); bytes.asShortBuffer().put(rows)
                        digest.update(bytes.array())
                    }
                    digest.digest()
                }
            }
            val original = capture(0)
            val actual = capture(1)
            check(actual.contentEquals(original)) { "$name: batching changed complete U16 pixels" }
            check(capture(2).contentEquals(original)) { "$name: explicit random identity changed fresh-render pixels" }
            evidence += "$name=${actual.joinToString("") { "%02x".format(it.toInt() and 255) }}"
        }
        return "8193×281 whole vs bounded exact U16: ${evidence.joinToString()}; full recurrence lines preserved; ${geometryAndEdges()}"
    }

    private fun geometryAndEdges(): String {
        val width = 1537; val height = 1025
        val input = FloatArray(width * height * 3) { i ->
            .01f + (if (i % 3 == 1) i / 3 / width else i / 3 % width).toFloat() /
                (if (i % 3 == 1) height else width)
        }
        val cases = listOf(
            "crop-turn-flip" to EditorParams().apply {
                grainActive = false; glareActive = false
                cropX = .11f; cropY = .17f; cropW = .73f; cropH = .67f
                quarterTurns = 1; flipH = true
            },
            "edges-date" to EditorParams().apply {
                grainActive = false; glareActive = false
                overscanActive = true; dateImprintActive = true; dateImprintText = "'26 11 15"
            },
        )
        val evidence = mutableListOf<String>()
        for ((name, params) in cases) {
            fun capture(windowed: Boolean) = EngineClient.create(test.targetContext).use { engine ->
                engine.open(input, width, height, 3, params.openJson(width, height))
                val result = if (windowed) engine.renderExportOnce() else engine.renderForExport()
                result.use {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val row = ShortArray(it.width * 4)
                    val bytes = java.nio.ByteBuffer.allocate(it.width * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    repeat(it.height) { y ->
                        it.readRows(y, 1, row); bytes.clear(); bytes.asShortBuffer().put(row); digest.update(bytes.array())
                    }
                    Triple(it.width, it.height, digest.digest().toList())
                }
            }
            check(capture(false) == capture(true)) { "$name: windowing changed native dimensions/pixels" }
            evidence += name
        }
        return "odd-width ${evidence.joinToString()} complete pixels match legacy"
    }
}
