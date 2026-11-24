package com.spektralab.android.feature.editor.ui

import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity

internal data class EditorWindowLayout(val landscape: Boolean, val expanded: Boolean)

/** Choose composition from the app window, before the IME reduces the control viewport. */
@Composable
internal fun rememberEditorWindowLayout(): EditorWindowLayout {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    return remember(context, configuration, density) {
        val manager = context.getSystemService(WindowManager::class.java)
        val size = if (Build.VERSION.SDK_INT >= 30) {
            manager.currentWindowMetrics.bounds.let { Point(it.width(), it.height()) }
        } else {
            // Legacy display metrics represent the app display, not the IME-resized content view.
            @Suppress("DEPRECATION")
            Point().also { manager.defaultDisplay.getSize(it) }
        }
        val width = size.x / density
        val height = size.y / density
        val landscape = width > height
        EditorWindowLayout(
            landscape = landscape,
            expanded = height >= 500f && (width >= 840f || (width >= 600f && landscape)),
        )
    }
}
