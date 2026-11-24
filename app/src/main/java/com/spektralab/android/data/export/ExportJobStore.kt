package com.spektralab.android.data.export

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.core.net.toUri
import com.spektralab.android.core.model.ExportFrameRequest
import org.json.JSONObject
import java.io.File

/** Private request/receipt journal; source pixels and copied EXIF/GPS fields are never stored here. */
internal class ExportJobStore(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "export-jobs").apply { mkdirs() }

    enum class State { QUEUED, PREPARING, RENDERING, ENCODING, PUBLISHING, SUCCEEDED, FAILED, CANCELLED }
    data class Entry(val id:String,val request:ExportFrameRequest?,val state:State?,val updated:Long,
        val destination:String?,val error:String?,val sourceReference:String?,val attempts:Int) {
        val retryable get()=request!=null && sourceReference!=null && state in setOf(State.FAILED,State.CANCELLED)
    }

    private fun file(id:String):File {require(java.util.UUID.fromString(id).toString()==id);return File(directory,"$id.json")}
    private fun read(file:File):JSONObject {
        return AtomicFile(file).openRead().use {input->
            require(input.channel.size() in 1..256L*1024){"Export receipt is too large"}
            JSONObject(input.bufferedReader().readText())
        }.also {
            require(it.optInt("journal_version",1) in 1..2){"Unsupported export receipt"}
        }
    }
    private fun persist(file:File,json:JSONObject) {
        val bytes=json.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=256*1024)
        val atomic=AtomicFile(file);val out=atomic.startWrite()
        try{out.write(bytes);atomic.finishWrite(out)}catch(error:Throwable){atomic.failWrite(out);throw error}
    }
    @Synchronized fun entries():List<Entry> = directory.listFiles().orEmpty()
        .map {if(it.name.endsWith(".json.bak"))File(it.path.removeSuffix(".bak"))else it}.distinctBy{it.name}
        .filter{it.name.matches(Regex("[a-f0-9-]{36}\\.json"))}.sortedByDescending{it.lastModified()}.take(128).map {file->
            try {
                val j=read(file);val request=ExportFrameRequest.fromJson(j.getJSONObject("request").toString())
                require(request.jobId==file.nameWithoutExtension)
                val reference=j.optString("source_reference").takeUnless{it.isBlank() || it=="null"}
                require(reference==null || reference.length<=8192 && reference.toUri().scheme in listOf("file","content"))
                if(j.optInt("journal_version",1)>=2)require(j.getJSONObject("request").has("film_seed") && j.getJSONObject("request").has("print_seed"))
                Entry(request.jobId,request,State.valueOf(j.getString("state")),j.getLong("updated"),
                    j.optString("destination").takeUnless{it.isBlank() || it=="null"},j.optString("error").takeUnless{it.isBlank() || it=="null"},
                    reference,j.optInt("attempts",1).also{require(it in 1..1000)})
            }catch(error:Exception){Entry(file.nameWithoutExtension,null,null,file.lastModified(),null,
                "此任务记录无法读取，原文件已保留",null,0)}
        }

    /** Called only after process recovery and explicit user retry. An already committed output wins. */
    @Synchronized fun claimRetry(id:String):Entry = synchronized(RETRY_LOCK) {
        val entry=entries().single{it.id==id};require(entry.retryable){"此任务不能直接重试，请从原图重新导出"}
        if(committed(entry.destination)) {
            write(checkNotNull(entry.request),State.SUCCEEDED,entry.destination)
            error("此任务已经导出成功，无需重复发布")
        }
        entry.destination?.takeIf{it.startsWith("content://media/") && Build.VERSION.SDK_INT>=29}?.let {value->
            val uri=value.toUri()
            app.contentResolver.query(uri,arrayOf(MediaStore.Images.Media.IS_PENDING),null,null,null)?.use {
                if(it.moveToFirst() && it.getInt(0)==1)check(app.contentResolver.delete(uri,null,null)>0){"尚未清理上次未完成文件，请稍后重试"}
            }
        }
        val j=read(file(id));require(j.getString("state")==entry.state!!.name)
        j.put("state",State.QUEUED.name).put("updated",System.currentTimeMillis()).put("attempts",entry.attempts+1)
            .put("destination",JSONObject.NULL).put("error",JSONObject.NULL)
        persist(file(id),j)
        entry
    }
    @Synchronized fun relink(id:String,reference:Uri,check:()->Unit={}) {
        require(reference.scheme in listOf("file","content") && reference.toString().length<=8192)
        val entry=entries().single{it.id==id};require(entry.request!=null && entry.state in setOf(State.FAILED,State.CANCELLED))
        ExportSourceSnapshot.create(app,reference,entry.request.options.keepLocation,check).use{
            require(it.sha256==entry.request.sourceHash){"所选文件与此任务的原图不同，请选择同一原图"}
        }
        check();val j=read(file(id));j.put("journal_version",2).put("source_reference",reference.toString()).put("updated",System.currentTimeMillis())
        persist(file(id),j)
    }
    @Synchronized fun remove(id:String) {
        val entry=entries().single{it.id==id};require(entry.state==null || entry.state in TERMINAL)
        // Task metadata only; never removes the original or a published destination.
        AtomicFile(file(id)).delete()
    }
    private fun committed(destination:String?):Boolean {
        if(destination==null)return false
        if(destination.startsWith("content://media/") && Build.VERSION.SDK_INT>=29)
            return app.contentResolver.query(destination.toUri(),arrayOf(MediaStore.Images.Media.IS_PENDING),null,null,null)?.use {
                it.moveToFirst() && it.getInt(0)==0
            }==true
        val result=File(destination)
        val expected=(app.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)?:app.filesDir).canonicalFile
        return result.parentFile?.canonicalFile==expected && result.name.startsWith("Theia_") && result.isFile && !result.name.endsWith(".pending")
    }

    @Synchronized
    fun write(request: ExportFrameRequest, state: State, destination: String? = null, error: String? = null,
              sourceReference: String? = null) {
        val target=file(request.jobId)
        val data = (if(target.isFile || File(target.path+".bak").isFile)read(target)else JSONObject()).put("journal_version",2)
            .put("request", JSONObject(request.toJson())).put("state", state.name)
            .put("updated", System.currentTimeMillis()).put("destination", destination ?: JSONObject.NULL)
            .put("error", error?.take(2048) ?: JSONObject.NULL)
        if(sourceReference!=null){require(sourceReference.length<=8192);data.put("source_reference",sourceReference)}
        if(!data.has("attempts"))data.put("attempts",1)
        persist(target,data)
        if (state in TERMINAL) prune()
    }

    /** Called before accepting this process's first job. Never retries rendering or guesses by filename. */
    @Synchronized
    fun recover() {
        entries() // AtomicFile also restores legacy .bak-only records before recovery scans them.
        directory.listFiles { file -> file.name.matches(Regex("[a-f0-9-]{36}\\.json")) }?.forEach { file ->
            runCatching {
                val json = read(file)
                val state = State.valueOf(json.getString("state"))
                if (state in TERMINAL) return@runCatching
                val request = ExportFrameRequest.fromJson(json.getJSONObject("request").toString())
                val destination = json.optString("destination").takeUnless { it.isEmpty() || it == "null" }
                var completed = false
                if (destination?.startsWith("content://media/") == true && Build.VERSION.SDK_INT >= 29) {
                    val uri = destination.toUri()
                    app.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            if (cursor.getInt(0) == 1) app.contentResolver.delete(uri, null, null)
                            else completed = true // Commit finished before the process could record its receipt.
                        }
                    }
                } else if (destination != null && !destination.contains("://")) {
                    val pending = File(destination)
                    val expected = (app.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: app.filesDir).canonicalFile
                    if (pending.parentFile?.canonicalFile == expected && pending.name.startsWith("Theia_") && pending.name.endsWith(".pending")) {
                        if (pending.isFile) pending.delete()
                        else completed = File(expected, pending.name.removeSuffix(".pending")).isFile
                    }
                }
                write(request, if (completed) State.SUCCEEDED else State.FAILED, destination, if (completed) null else "Export interrupted by process exit")
            }.onFailure { android.util.Log.w("TheiaExport", "Cannot recover export receipt", it) }
        }
        prune()
    }

    private fun prune() {
        // Keep bounded diagnostics; active records are never pruned.
        directory.listFiles { file -> file.extension == "json" }?.sortedByDescending { it.lastModified() }?.drop(32)?.forEach { file ->
            runCatching {
                val json = read(file)
                if (State.valueOf(json.getString("state")) in TERMINAL) file.delete()
            }
        }
    }

    private companion object {
        val TERMINAL = setOf(State.SUCCEEDED, State.FAILED, State.CANCELLED)
        val RETRY_LOCK=Any()
    }
}
