package com.spektralab.android.engine.host

import com.spektralab.android.engine.Rgba16Source
import kotlin.math.sqrt

/** Conservative catastrophic-frame guard, not a colour-match tolerance or a quality transformation. */
internal object RenderAppearance {
    private const val COLUMNS = 48
    private const val ROWS = 32

    data class Signature(val luma: DoubleArray, val opaque: Boolean)

    fun capture(source: Rgba16Source, check: () -> Unit): Signature {
        val cells = DoubleArray(COLUMNS * ROWS)
        val row = ShortArray(source.width * 4)
        var opaque = true
        repeat(ROWS) { cellY ->
            repeat(2) { subY ->
                check()
                val y = ((cellY + (subY + .5) / 2) * source.height / ROWS).toInt().coerceIn(0, source.height - 1)
                source.readRows(y, 1, row)
                repeat(COLUMNS) { cellX ->
                    repeat(2) { subX ->
                        val x = ((cellX + (subX + .5) / 2) * source.width / COLUMNS).toInt().coerceIn(0, source.width - 1)
                        val at = x * 4
                        cells[cellY * COLUMNS + cellX] += (
                            .2126 * (row[at].toInt() and 65535) +
                                .7152 * (row[at + 1].toInt() and 65535) +
                                .0722 * (row[at + 2].toInt() and 65535)) / (4 * 65535.0)
                        if (row[at + 3].toInt() and 65535 != 65535) opaque = false
                    }
                }
            }
        }
        return Signature(cells, opaque)
    }

    fun compatible(reference: Signature, actual: Signature): Boolean {
        if (!reference.opaque || !actual.opaque) return false
        val a = reference.luma; val b = actual.luma
        val ma = a.average(); val mb = b.average()
        var va = 0.0; var vb = 0.0; var covariance = 0.0
        for (i in a.indices) {
            val x = a[i] - ma; val y = b[i] - mb
            va += x * x; vb += y * y; covariance += x * y
        }
        // Uniform or almost-black references have no reliable spatial oracle.
        if (va / a.size < .0004) return true
        val correlation = if (vb == 0.0) 0.0 else covariance / sqrt(va * vb)
        var consecutive = 0; var longest = 0
        repeat(ROWS) { y ->
            var expected = 0.0; var rendered = 0.0
            repeat(COLUMNS) { x -> expected += a[y * COLUMNS + x]; rendered += b[y * COLUMNS + x] }
            consecutive = if (expected / COLUMNS > .025 && rendered / COLUMNS < .002) consecutive + 1 else 0
            longest = maxOf(longest, consecutive)
        }
        // Intact bright film perforations can dominate global correlation.
        // A quarter-frame of expected image rows becoming black is already
        // catastrophic evidence, independently of the surviving border.
        val missingBand = longest >= 8
        val lostStructure = vb < va * .005 && correlation < .25
        return !missingBand && !lostStructure
    }
}
