package com.spektralab.android.data.export

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.spektralab.android.core.model.EditSnapshot
import com.spektralab.android.core.model.ExportOptions
import com.spektralab.android.data.cache.ExportRenderCache
import com.spektralab.android.data.cache.RenderDiskCache
import com.spektralab.android.engine.Rgba16Source
import com.spektralab.android.engine.host.DescriptorRgba16Source
import com.spektralab.android.engine.host.EncodedPhoto
import com.spektralab.android.engine.host.RenderHostClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared single/batch output path; the feature owns only preview hand-off and presentation. */
internal class PhotoExportPipeline(private val context: Context) {
    data class Output(val path: String, val width: Int, val height: Int)

    suspend fun write(uri: Uri, name: String, edit: EditSnapshot, options: ExportOptions,
                      check: () -> Unit, beforeNative: suspend () -> Unit,
                      progress: (String, Float) -> Unit,
                      recordReceipt: Boolean = true,
                      frozen: com.spektralab.android.core.model.ExportFrameRequest? = null,
                      artifact: com.spektralab.android.core.model.ExportArtifact = frozen?.artifact ?: com.spektralab.android.core.model.ExportArtifact.FINISHED,
                      validateRendered: (Rgba16Source) -> Unit = {},
                      publish: ((EncodedPhoto, ExportCoordinator.Task) -> String)? = null,
                      encode: (Rgba16Source, ExportMetadata, ExportCoordinator.Task) -> String): Output =
        ExportCoordinator.get(context).run(uri, name, edit, options, check, recordReceipt, frozen, artifact) { task, source ->
            val metadata = withContext(Dispatchers.IO) {
                val exif = runCatching { ExifInterface(source.descriptor.fileDescriptor) }.getOrNull()
                ExportMetadata.from(exif, options.keepCapture, options.keepLocation)
            }
            val cache = ExportRenderCache.get(context)
            val request = withContext(Dispatchers.IO) {
                if(artifact==com.spektralab.android.core.model.ExportArtifact.DIGITAL_INTERMEDIATE)null else
                    cache.prepareResolved(uri, task.request.edit.toParams(), options, task.request.realisation, check)?.takeIf { it.sourceHash == source.sha256 }
            }
            var lease: RenderDiskCache.Lease? = null
            try {
                withContext(Dispatchers.IO) { lease = request?.let { cache.acquire(it, check) } }
                suspend fun save(result: Rgba16Source): Output {
                    check(); progress("encoding", 96f)
                    return withContext(Dispatchers.IO) {
                        // Apply caller capacity limits before cropping can hide an oversized raster.
                        validateRendered(result)
                        task.phase(ExportJobStore.State.ENCODING)
                        if(artifact==com.spektralab.android.core.model.ExportArtifact.DIGITAL_INTERMEDIATE) {
                            val path=encode(result,metadata,task);task.succeeded(path)
                            return@withContext Output(path,result.width,result.height)
                        }
                        if (publish != null) {
                            val client = RenderHostClient(context).also { it.onHostStarted = task::hostStarted }
                            val (descriptor, offset) = when (result) {
                                is DescriptorRgba16Source -> result.duplicateDescriptor() to 0L
                                is RenderDiskCache.Lease -> result.duplicateDescriptor() to result.pixelOffset
                                else -> error("Finished export requires a file-backed source")
                            }
                            descriptor.use {
                                client.encode(task.request, it, result.width, result.height, offset, metadata, check).use { encoded ->
                                    val path = publish(encoded, task)
                                    task.succeeded(path)
                                    return@withContext Output(path, encoded.width, encoded.height)
                                }
                            }
                        }
                        val finished = FinishedPhoto.prepare(result, task.request, check)
                        val path = encode(finished, metadata, task)
                        task.succeeded(path)
                        Output(path, finished.width, finished.height)
                    }
                }
                val cached = lease
                if (cached != null) save(cached)
                else {
                    beforeNative(); check()
                    task.render(source, check, progress).use { result ->
                        if (request != null) withContext(Dispatchers.IO) { cache.store(request, uri, result, check) }
                        save(result)
                    }
                }
            } finally { lease?.close() }
        }
}
