package com.spektralab.android.data.cache

import java.io.File

/** Retire abandoned scratch files, never project databases, edits, originals or published exports. */
internal object CacheMaintenance {
    private const val SCRATCH_AGE = 24L * 60 * 60 * 1000
    private val scratch = Regex("(?:raw_(?:input|export|preview)_.+\\.raw|export_[0-9]+\\.(?:jpg|png)|export-fd-[0-9]+\\.tmp)")
    private val tableFolder = Regex("table-export-[0-9]+")
    private val tableSource = Regex("source-[0-9]+\\.png")
    private val sharePreview = Regex("Theia-viewing-[0-9]+\\.png")

    fun pruneStale(cache: File, now: Long = System.currentTimeMillis()): Long {
        if (!cache.isDirectory) return 0
        val root = cache.canonicalFile
        fun stale(file: File) = now >= file.lastModified() && now - file.lastModified() > SCRATCH_AGE
        fun owned(file: File) = file.canonicalFile.parentFile == root
        fun remove(file: File): Long {
            val bytes = file.length()
            return if (file.delete()) bytes else 0
        }
        var freed = 0L
        root.listFiles().orEmpty().forEach { file ->
            if (!owned(file) || !stale(file)) return@forEach
            when {
                file.isFile && scratch.matches(file.name) -> freed += remove(file)
                file.isDirectory && tableFolder.matches(file.name) -> {
                    val files = file.listFiles().orEmpty()
                    if (files.all { it.isFile && stale(it) && it.canonicalFile.parentFile == file.canonicalFile && tableSource.matches(it.name) }) {
                        files.forEach { freed += remove(it) }
                        file.delete()
                    }
                }
            }
        }
        val shares = File(root, "lighttable")
        if (shares.canonicalFile.parentFile == root && shares.isDirectory) {
            shares.listFiles().orEmpty().filter {
                it.isFile && it.canonicalFile.parentFile == shares.canonicalFile &&
                    sharePreview.matches(it.name) && stale(it)
            }.forEach { freed += remove(it) }
        }
        return freed
    }
}
