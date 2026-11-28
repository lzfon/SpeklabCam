package com.spektralab.android.feature.home

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.gallery.SavedPhotosScreen
import com.spektralab.android.feature.lighttable.FilmCanvasState
import com.spektralab.android.feature.lighttable.GalleryScreen

/** 首页滑入面板：0 全关、1 图库（左边滑入）、2 观片台（右边滑入）。 */
private const val PANEL_NONE = 0
private const val PANEL_LIBRARY = 1
private const val PANEL_STAND = 2

/**
 * 首页外壳。
 *
 * 第四轮把底栏「图库」「观片」两项从跳整页改成左右滑入的面板：
 *  - 图库从屏幕左边滑进来，观片台从屏幕右边滑进来；
 *  - 滑入 420ms、滑出 300ms，缓动 FastOutSlowIn，跟编辑页一级菜单展开收起是同一节拍；
 *  - 中间那颗「Speklab」不进面板，点它直接去编辑页。
 *
 * 原有的整页路由（AppRoute.SavedPhotos / AppRoute.ViewingStand）仍留在 TheiaRoot，
 * 只为兼容进程被回收前保存下来的导航状态，正常操作已经不会再走到。
 */
@Composable
internal fun HomeShell(
    vm: EditorViewModel,
    canvasState: FilmCanvasState,
    onImportPhotos: () -> Unit,
    onAddPhotos: () -> Unit,
    onPickForViewing: () -> Unit,
    onEditFromStand: (Uri) -> Unit,
    onOpenCamera: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var panel by rememberSaveable { mutableStateOf(PANEL_NONE) }
    BackHandler(enabled = panel != PANEL_NONE) { panel = PANEL_NONE }
    val busy = vm.busy || vm.isExporting || vm.isBatchExporting

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HomeScreen(
            vm = vm,
            onImportPhotos = onImportPhotos,
            onAddPhotos = onAddPhotos,
            onOpenCamera = onOpenCamera,
            onOpenEditor = onOpenEditor,
            onOpenViewingStand = { panel = PANEL_STAND },
            onOpenAbout = onOpenAbout,
            onOpenSavedPhotos = { vm.lightTableProjects.refresh(); panel = PANEL_LIBRARY },
            onOpenTasks = onOpenTasks,
            onOpenSettings = onOpenSettings,
            activePanel = when (panel) {
                PANEL_LIBRARY -> "library"
                PANEL_STAND -> "stand"
                else -> null
            },
        )

        AnimatedVisibility(
            visible = panel == PANEL_LIBRARY,
            enter = slideInHorizontally(tween(420, easing = FastOutSlowInEasing)) { full -> -full },
            exit = slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { full -> -full },
            label = "home-library-drawer",
        ) {
            SavedPhotosScreen(
                controller = vm.savedPhotos,
                enabled = !busy,
                onBack = { panel = PANEL_NONE },
                onImport = onImportPhotos,
                onOpen = { uri ->
                    if (!vm.requestLibraryOpen(uri)) {
                        panel = PANEL_NONE
                        onOpenEditor()
                    }
                },
                onAddToViewing = { uris ->
                    vm.loadViewingPhotos(uris)
                    panel = PANEL_STAND
                },
                onAddAllToWorkbench = { uris ->
                    uris.forEach(vm::persistReadPermission)
                    vm.importPhotos(uris, addToSession = true)
                    panel = PANEL_NONE
                    onOpenEditor()
                },
                projects = vm.lightTableProjects.projects,
                onAddToProject = { uris, project -> vm.lightTableProjects.addUrisToProject(project, uris) },
                onRestore = { session ->
                    vm.restoreWorkbench(session)
                    panel = PANEL_NONE
                    onOpenEditor()
                },
                onRelink = vm::relinkWorkbenchSource,
                prepareVersions = vm::prepareVersionBoundary,
                versionInUse = { it in vm.photos || it == vm.currentUri },
                onVersionOpen = { uri ->
                    vm.addPhotos(listOf(uri), openSelected = true)
                    panel = PANEL_NONE
                    onOpenEditor()
                },
            )
        }

        AnimatedVisibility(
            visible = panel == PANEL_STAND,
            enter = slideInHorizontally(tween(420, easing = FastOutSlowInEasing)) { full -> full },
            exit = slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { full -> full },
            label = "home-stand-drawer",
        ) {
            GalleryScreen(
                vm = vm,
                onImport = onPickForViewing,
                onBackToDarkroom = { panel = PANEL_NONE },
                onRemovePhoto = { uri ->
                    if (vm.viewingPhotos.any { it.first == uri.toString() }) vm.removeViewingPhoto(uri.toString())
                    if (uri in vm.photos) vm.removePhotoFromSession(uri)
                },
                onEditPhoto = { uri ->
                    val index = vm.photos.indexOf(uri)
                    if (index >= 0) vm.selectPhoto(index) else vm.addPhotos(listOf(uri), openSelected = true)
                    panel = PANEL_NONE
                    onEditFromStand(uri)
                },
                modifier = Modifier.fillMaxSize(),
                canvasState = canvasState,
                backDescription = "返回首页",
            )
        }

        // 首页路由里只有一个弹窗口：宫格单击和抽屉里图库单击共用它。
        com.spektralab.android.ui.components.WorkbenchJoinDialog(
            request = vm.pendingLibraryOpen,
            onDismiss = vm::dismissLibraryOpen,
            onAppend = {
                vm.resolveLibraryOpen(true)
                panel = PANEL_NONE
                onOpenEditor()
            },
            onNewWorkbench = {
                vm.resolveLibraryOpen(false)
                panel = PANEL_NONE
                onOpenEditor()
            },
        )
    }
}
