package com.spektralab.android

import android.app.Application
import android.app.ActivityManager
import android.os.Build
import android.os.Process
import com.spektralab.android.data.cache.CacheMaintenance
import com.spektralab.android.data.cache.ExportRenderCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-scoped startup; disk maintenance runs only in the UI process and off the UI thread. */
class TheiaApplication : Application() {
    private val maintenance = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val process = if (Build.VERSION.SDK_INT >= 28) getProcessName() else
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }?.processName
        if (process != packageName) return
        maintenance.launch {
            runCatching {
                CacheMaintenance.pruneStale(cacheDir)
                ExportRenderCache.get(this@TheiaApplication).maintain()
            }.onFailure { android.util.Log.w("TheiaCache", "Deferred cache maintenance", it) }
        }
    }
}
