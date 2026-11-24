package com.spektralab.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.spektralab.android.app.TheiaRoot
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.theme.TheiaTheme

/**
 * 启动即进入主界面：不再有启动动画、启动页和品牌 logo 动画。
 * 需要预热引擎时由 TheiaRoot 自己的进度提示承担，不再额外画一屏。
 */
class MainActivity : ComponentActivity() {

    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Preserve profiled original previews on capable screens; SDR devices keep their normal path.
        if (resources.configuration.isScreenWideColorGamut) {
            window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.rgb(21, 21, 21)
        val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
        vm.restoreUri(savedInstanceState?.getString("currentUri"))
        setContent {
            TheiaTheme {
                TheiaRoot(vm)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentUri", vm.currentUri?.toString())
    }
}
