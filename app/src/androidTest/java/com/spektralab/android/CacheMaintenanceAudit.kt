package com.spektralab.android

import com.spektralab.android.data.cache.CacheMaintenance
import com.spektralab.android.data.cache.RenderDiskCache
import com.spektralab.android.data.cache.CacheGeneration
import com.spektralab.android.engine.Rgba16Source
import java.io.File

/** Only audit-owned scratch data. No system cache clearing or user source deletion. */
internal object CacheMaintenanceAudit {
    fun run(output: File): String {
        val root = File(output, "cache-maintenance-${System.nanoTime()}").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val old = now - 2 * 24 * 60 * 60 * 1000L
        fun file(path: String, age: Long = old) = File(root, path).apply {
            parentFile!!.mkdirs(); writeBytes(ByteArray(17) { 9 }); setLastModified(age)
        }
        try {
            val abandoned = listOf(file("raw_input_123.raw"), file("export_123.jpg"),
                file("table-export-123/source-0.png"), file("lighttable/Theia-viewing-123.png"))
            val preserved = listOf(file("DSC_5099.NEF"), file("sidecars/edit.json"),
                file("photo-library/saved-photos.db"), file("raw_preview_456.raw", now),
                file("lighttable/Theia-viewing-456.png", now), file("table-export-456/source-0.png", now))
            File(root, "table-export-123").setLastModified(old)
            File(root, "table-export-456").setLastModified(old)
            check(CacheMaintenance.pruneStale(root, now) == 17L * abandoned.size)
            check(abandoned.none(File::exists) && preserved.all(File::exists))

            val source = object : Rgba16Source {
                override val width = 17
                override val height = 7
                override fun readRows(y: Int, rows: Int, destination: ShortArray) {
                    repeat(width * rows * 4) { destination[it] = ((y * width * 4 + it) * 31).toShort() }
                }
            }
            val directory = File(root, "render")
            val bytes = 68L + 17 * 7 * 8
            val age = 7 * 24 * 60 * 60 * 1000L
            val cache = RenderDiskCache(directory, bytes * 2, age) { now }
            val a = "a".repeat(64); val b = "b".repeat(64)
            check(cache.put(a, source) && cache.put(b, source))
            val lease = checkNotNull(cache.acquire(a))
            File(directory, "$a.rgba16").setLastModified(now - age - 1)
            cache.trimToBudget()
            check(File(directory, "$a.rgba16").isFile) { "A leased frame was evicted" }
            val actual = ShortArray(source.width * source.height * 4)
            val expected = ShortArray(actual.size)
            lease.readRows(0, source.height, actual); source.readRows(0, source.height, expected)
            check(actual.contentEquals(expected)) { "Cache maintenance changed U16 data" }
            lease.close()
            check(!File(directory, "$a.rgba16").exists()) { "Expired released frame was retained" }
            for (key in listOf("c", "d", "e")) check(cache.put(key.repeat(64), source))
            check(directory.listFiles().orEmpty().sumOf(File::length) <= bytes * 2)
            cache.clear()
            check(directory.listFiles().orEmpty().isEmpty())
            val oldBudget = RenderDiskCache(directory, bytes * 4)
            for (key in listOf("1", "2", "3", "4")) check(oldBudget.put(key.repeat(64), source))
            RenderDiskCache(directory, bytes * 2).trimToBudget()
            check(directory.listFiles().orEmpty().sumOf(File::length) <= bytes * 2)
            val marker = File(root, "build-generation")
            var retired = 0
            val generation = CacheGeneration(marker)
            generation.ensure("install-A") { retired++ }
            generation.ensure("install-A") { retired++ }
            CacheGeneration(marker).ensure("install-A") { retired++ }
            check(retired == 1) { "Same-install cache was needlessly discarded" }
            CacheGeneration(marker).ensure("install-B") { retired++ }
            check(retired == 2) { "Unreachable prior-install cache was retained" }
            marker.writeBytes(ByteArray(1024 * 1024))
            CacheGeneration(marker).ensure("install-C") { retired++ }
            check(retired == 3) { "Malformed generation marker was not bounded/replaced" }
            check(preserved.all(File::exists))
            return "Expired scratch removed; recent shares/active staging, originals/edits/database preserved; expired/over-budget/prior-install U16 retired; valid same-install cache and leased exact pixels preserved; oversized generation marker bounded"
        } finally {
            // All paths here were created under this invocation's unique audit root.
            root.walkBottomUp().forEach(File::delete)
        }
    }
}
