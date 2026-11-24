package com.spektralab.android.engine

internal enum class ExportFailureCode { CANCELLED, DEVICE_LOST, HOST_DIED, MEMORY, STORAGE, REQUEST, TIMEOUT, INTERNAL }

/** Stable category; JNI's existing SpkException(String) constructor stays unchanged. */
internal class ExportFailure(val code: ExportFailureCode, message: String) : SpkException(message)
