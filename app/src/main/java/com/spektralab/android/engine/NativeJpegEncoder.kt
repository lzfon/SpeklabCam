package com.spektralab.android.engine

import android.os.Build
import java.io.OutputStream

internal object NativeJpegEncoder {
    init { System.loadLibrary("spektralab") }
    val available: Boolean by lazy { Build.VERSION.SDK_INT >= 30 && nativeAvailable() }
    fun encode(fd: Int, width: Int, height: Int, quality: Int, output: OutputStream) =
        nativeEncode(fd, width, height, quality, output)
    private external fun nativeAvailable(): Boolean
    private external fun nativeEncode(fd: Int, width: Int, height: Int, quality: Int, output: OutputStream)
}
