package com.spektralab.android.engine

/** Classifies native failures without confusing -4 with -40 or allocation/cancellation errors. */
internal object EngineFailure {
    private val deviceLostCode = Regex("\\bVkResult\\s*[:=]?\\s*-4(?!\\d)", RegexOption.IGNORE_CASE)

    fun isDeviceLost(error: Throwable): Boolean {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = error
        while (cause != null && visited.add(cause)) {
            if (cause is ExportFailure && cause.code == ExportFailureCode.DEVICE_LOST) return true
            val message = cause.message.orEmpty()
            if (message.contains("VK_ERROR_DEVICE_LOST", ignoreCase = true) || deviceLostCode.containsMatchIn(message)) {
                return true
            }
            cause = cause.cause
        }
        return false
    }
}
