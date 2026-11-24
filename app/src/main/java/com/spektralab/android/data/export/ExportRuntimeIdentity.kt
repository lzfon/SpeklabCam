package com.spektralab.android.data.export

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Retry must not silently adopt a different APK, material catalog, codec or native implementation. */
internal object ExportRuntimeIdentity {
    @Volatile private var cached:String?=null
    @Synchronized fun current(context:Context):String {
        cached?.let{return it}
        val info=context.applicationInfo
        val digest=MessageDigest.getInstance("SHA-256")
        val paths=listOf(info.sourceDir)+info.splitSourceDirs.orEmpty().sorted()
        for(path in paths){val file=File(path);digest.update(file.length().toString().toByteArray(Charsets.US_ASCII));digest.update(0)
            file.inputStream().use {input->val buffer=ByteArray(128*1024);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}}
        return digest.digest().joinToString(""){"%02x".format(it)}.also{cached=it}
    }
}
