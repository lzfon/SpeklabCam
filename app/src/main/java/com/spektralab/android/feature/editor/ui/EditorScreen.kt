package com.spektralab.android.feature.editor.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import com.spektralab.android.EditorTab
import com.spektralab.android.feature.about.AboutScreen
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.crop.CropWorkspace
import com.spektralab.android.feature.export.ExportProgressBar
import com.spektralab.android.feature.export.ExportScreen
import com.spektralab.android.feature.lighttable.FilmCanvasState
import com.spektralab.android.feature.lighttable.GalleryScreen
import com.spektralab.android.feature.smartdevelop.SmartDevelopSheet
import com.spektralab.android.ui.components.DarkroomTopBar
import com.spektralab.android.ui.theme.TheiaShapes
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorScreen(
    vm: EditorViewModel,
    onBackHome: () -> Unit,
    onOpenFilmStocks: () -> Unit,
    onOpenPaperStocks: () -> Unit,
    viewingCanvasState: FilmCanvasState,
    homeStandEditRequest: Int,
    onOpenCamera: () -> Unit = {},
    onOpenTasks: () -> Unit = {},
    settingsController: com.spektralab.android.feature.settings.SettingsController,
) {
    var addToStage by rememberSaveable { mutableStateOf(false) }
    var smartDevelopVisible by rememberSaveable { mutableStateOf(false) }
    var aboutVisible by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(EditorTab.Adjust) }
    var previousTab by rememberSaveable { mutableStateOf(EditorTab.Adjust) }
    var showAllShootControls by rememberSaveable { mutableStateOf(false) }
    // 三级菜单的开合状态（一级整体折叠、二级当前展开项、每个一级上次用的二级）。
    val menu = rememberMenuBoardState()
    LaunchedEffect(homeStandEditRequest) {
        if(homeStandEditRequest>0){selectedTab=EditorTab.Adjust;showAllShootControls=false}
    }
    var galleryReturnTab by rememberSaveable { mutableStateOf(EditorTab.Adjust) }
    var exportHelpVisible by remember { mutableStateOf(false) }
    var paramHelpVisible by rememberSaveable { mutableStateOf(false) }
    var paramMenuVisible by rememberSaveable { mutableStateOf(false) }
    val editorLayoutStates = rememberSaveableStateHolder()
    val fullParameters = selectedTab !in setOf(EditorTab.Export, EditorTab.Gallery)
    val panelStates = rememberSaveableStateHolder()
    // 工作台组（照片条 + 胶片 / 相纸栏）的弹出状态：控制条上的按钮切换，
    // 闲置到设置里的秒数自动收起，碰到工作台就重置计时（设置里可关自动隐藏）。
    var workbenchOpen by rememberSaveable { mutableStateOf(true) }
    var workbenchActivity by remember { mutableStateOf(0) }
    LaunchedEffect(workbenchOpen, workbenchActivity,
        com.spektralab.android.ui.components.UiPrefs.workbenchAutoHide,
        com.spektralab.android.ui.components.UiPrefs.workbenchHideSeconds) {
        if (workbenchOpen && com.spektralab.android.ui.components.UiPrefs.workbenchAutoHide) {
            delay(com.spektralab.android.ui.components.UiPrefs.workbenchHideSeconds * 1000L)
            workbenchOpen = false
        }
    }
    BackHandler(enabled = aboutVisible || paramHelpVisible || vm.isCropping || vm.detail.opened || selectedTab in setOf(EditorTab.Export, EditorTab.Gallery) || fullParameters) {
        when {
            vm.detail.opened -> vm.closeFullDetail()
            aboutVisible -> aboutVisible = false
            paramHelpVisible -> paramHelpVisible = false
            vm.isCropping -> vm.cancelCrop()
            selectedTab == EditorTab.Gallery -> selectedTab = galleryReturnTab
            selectedTab == EditorTab.Export -> selectedTab = previousTab
            else -> onBackHome()
        }
    }
    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 24)
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(vm::persistReadPermission)
            vm.loadViewingPhotos(uris)
        }
    }
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(vm::persistReadPermission)
            vm.importPhotos(uris, addToSession = addToStage)
        }
        addToStage = false
    }

    var showSaveRecipeDialog by remember { mutableStateOf(false) }
    var editToolsMode by rememberSaveable { mutableStateOf<String?>(null) }
    var recipeName by remember { mutableStateOf("") }
    var comparing by rememberSaveable(vm.currentUri?.toString()) { mutableStateOf(false) }
    val windowLayout = rememberEditorWindowLayout()
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    fun selectEditorTab(tab: EditorTab) {
        // 再点一次当前一级页签：参数弹着就整体折叠，折叠着就直接打开上次那个参数菜单。
        if (tab == selectedTab && EditorMenu.isMenuTab(tab)) {
            menu.retapTab(tab)
            return
        }
        if (tab == EditorTab.Gallery) {
            if (selectedTab != EditorTab.Gallery) galleryReturnTab = selectedTab
        } else if (tab != EditorTab.Export) {
            previousTab = tab
            if (EditorMenu.isMenuTab(tab)) menu.openTab(tab)
        }
        selectedTab = tab
    }

    // 导出不再占底栏，改由画面上的入口打开整页导出菜单。
    fun openExportMenu() {
        if (selectedTab != EditorTab.Export) previousTab = selectedTab
        selectedTab = EditorTab.Export
    }

    Box(Modifier.fillMaxSize()) {
        // A phone held sideways is wide but too short for all five rail destinations.
        val expanded = windowLayout.expanded
        editorLayoutStates.SaveableStateProvider("editor") {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = if (selectedTab == EditorTab.Gallery) androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
                else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
            topBar = {
                if (!vm.isCropping && selectedTab != EditorTab.Gallery) Column {
                    // 顶栏不再有「●●●」下拉菜单：裁剪/重置/编辑配方/复制配方都挪到参数行的控制条里。
                    DarkroomTopBar(
                        // 顶栏跟着菜单走：折叠时只显示一级，展开二级时写成「调参 · 测光曝光」。
                        title = when {
                            selectedTab == EditorTab.Export -> "导出照片"
                            EditorMenu.isMenuTab(selectedTab) && !menu.collapsed ->
                                menu.currentSection(selectedTab, vm)?.chip
                                    ?.let { "${selectedTab.label} · $it" } ?: selectedTab.label
                            EditorMenu.isMenuTab(selectedTab) -> selectedTab.label
                            fullParameters -> "胶片暗房"
                            else -> vm.photoInfo.firstOrNull { it.first == "文件名" }?.second ?: "Theia"
                        },
                        parameterView = fullParameters, exportView = selectedTab == EditorTab.Export,
                        enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting,
                        onBack = { when { selectedTab == EditorTab.Export -> selectedTab = previousTab
                            fullParameters -> onBackHome()
                            else -> onBackHome() } },
                        onHelp = { if (selectedTab == EditorTab.Export) exportHelpVisible = true
                            else paramHelpVisible = true },
                        // 齿轮菜单与首页一致：中断的任务 / 设置 / 关于 / 参数说明。
                        onOpenTasks = { paramMenuVisible = false; onOpenTasks() },
                        onOpenSettings = { paramMenuVisible = true },
                        onOpenAbout = { aboutVisible = true },
                    )
                }
            },
            bottomBar = {
                if (!keyboardVisible && !expanded && !vm.isCropping && selectedTab != EditorTab.Gallery && selectedTab != EditorTab.Export) {
                    EditorNavigationBar(selectedTab, !vm.busy && !vm.isExporting && !vm.isBatchExporting,
                        ::selectEditorTab, onRetap = ::selectEditorTab)
                }
            }
        ) { contentPadding ->
            BoxWithConstraints(
                Modifier.fillMaxSize().padding(contentPadding).imePadding()
            ) {
                val landscape = windowLayout.landscape
                val parameterWidth = (maxWidth * .44f).coerceIn(280.dp, 440.dp)
                if (vm.isCropping && (vm.cropWorkspaceBitmap != null || vm.sourceBitmap != null)) {
                    CropWorkspace(vm, Modifier.fillMaxSize())
                } else AnimatedContent(
                    targetState = selectedTab,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        (fadeIn(tween(250, easing = FastOutSlowInEasing)) +
                            slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 24 }) togetherWith
                            (fadeOut(tween(165, easing = FastOutSlowInEasing)) +
                                slideOutHorizontally(tween(190, easing = FastOutSlowInEasing)) { -it / 32 })
                    },
                    label = "editor-destination-transition",
                ) { destination ->
                    when {
                    destination == EditorTab.Export -> ExportScreen(vm, Modifier.fillMaxSize(), onCopySettings = { editToolsMode = "copy" }, onSaveRecipe = { showSaveRecipeDialog = true })
                    destination == EditorTab.Gallery -> {
                        GalleryScreen(
                            vm = vm,
                            onImport = { galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            onBackToDarkroom = { selectedTab = galleryReturnTab },
                            onRemovePhoto = { uri ->
                                if (vm.viewingPhotos.any { it.first == uri.toString() }) vm.removeViewingPhoto(uri.toString())
                                if (uri in vm.photos) vm.removePhotoFromSession(uri)
                            },
                            onEditPhoto = { uri ->
                                val index = vm.photos.indexOf(uri)
                                if (index >= 0) vm.selectPhoto(index) else vm.addPhotos(listOf(uri), openSelected = true)
                                selectedTab = EditorTab.Adjust
                                showAllShootControls = false
                            },
                            modifier = Modifier.fillMaxSize(),
                            canvasState=viewingCanvasState,
                        )
                    }
                    expanded -> Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EditorNavigationRail(destination, ::selectEditorTab, onRetap = ::selectEditorTab,
                            onExport = { openExportMenu() }, onCapture = onOpenCamera)
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            if (!keyboardVisible && destination != EditorTab.Export && vm.photos.isNotEmpty()) EditorPhotoStrip(
                                vm = vm,
                                onAddPhotos = {
                                    addToStage = true
                                    pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                onSmartDevelop = { smartDevelopVisible = true },
                            )
                            PhotoCanvas(vm, comparing, onImport = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                modifier = Modifier.weight(1f).fillMaxWidth(), zoomable = true)
                        }
                        Column(Modifier.width(parameterWidth).fillMaxHeight()) {
                            if (!keyboardVisible) {
                                PhotoMetadataStrip(vm)
                                EditorStockSelection(vm, onOpenFilmStocks, onOpenPaperStocks)
                            }
                            EditorPanel(vm, destination, Modifier.weight(1f).fillMaxWidth(), panelStates,
                                menu = menu, onSaveRecipe = { showSaveRecipeDialog = true })
                        }
                    }
                    landscape -> Row(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1.08f).fillMaxHeight()) {
                            if (!keyboardVisible && destination != EditorTab.Export && vm.photos.isNotEmpty()) EditorPhotoStrip(
                                vm = vm,
                                onAddPhotos = {
                                    addToStage = true
                                    pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                onSmartDevelop = { smartDevelopVisible = true },
                            )
                            PhotoCanvas(vm, comparing, onImport = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                modifier = Modifier.weight(1f).fillMaxWidth(), zoomable = true)
                        }
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            if (!keyboardVisible && destination != EditorTab.Grade) {
                                EditorStockSelection(vm, onOpenFilmStocks, onOpenPaperStocks)
                            }
                            EditorPanel(vm, destination, Modifier.weight(1f).fillMaxWidth(), panelStates,
                                showHeader = false, menu = menu, onSaveRecipe = { showSaveRecipeDialog = true })
                        }
                    }
                    else -> Column(Modifier.fillMaxSize()) {
                        AdjustmentWorkspace(
                        mode = destination.label,
                        fileName = vm.photoInfo.firstOrNull { it.first == "文件名" }?.second ?: "当前照片",
                        comparing = comparing, canCompare = vm.sourceBitmap != null && vm.rendered != null,
                        onCompare = { comparing = !comparing },
                        onDone = { selectedTab = EditorTab.Adjust; showAllShootControls = false },
                        onExport = { openExportMenu() },
                        onReset = vm::resetAll, canReset = vm.hasPhoto,
                        onCrop = { vm.beginCrop() }, canCrop = vm.hasPhoto && (!vm.params.overscanActive || vm.params.filmOff),
                        onFinishedCrop = { vm.beginFinishedCrop() }, canFinishedCrop = vm.hasPhoto,
                        canUndo = vm.canUndo, canRedo = vm.canRedo,
                        onUndo = vm::undo, onRedo = vm::redo,
                        onEditRecipe = { editToolsMode = "presets" },
                        onCopyRecipe = { editToolsMode = "copy" },
                        canTransfer = vm.canTransferEdits,
                        workbenchOpen = workbenchOpen,
                        onWorkbenchToggle = { workbenchOpen = !workbenchOpen; workbenchActivity++ },
                        onCapture = onOpenCamera,
                        modifier = Modifier.weight(1f),
                        preview = { previewModifier ->
                            PhotoCanvas(vm, comparing,
                                onImport = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                modifier = previewModifier, zoomable = true)
                        },
                        // 工作台组：照片条在上、胶片 / 相纸栏在下，整组由控制条上的工作台按钮弹出 / 收起。
                        persistentControls = {
                            if (EditorMenu.isMenuTab(destination) && !keyboardVisible) Column(
                                Modifier.fillMaxWidth().pointerInput(Unit) {
                                    // 碰到工作台就算有活动，重置自动隐藏计时。
                                    awaitPointerEventScope {
                                        while (true) {
                                            awaitPointerEvent(PointerEventPass.Initial)
                                            workbenchActivity++
                                        }
                                    }
                                }) {
                                if (vm.photos.isNotEmpty()) EditorPhotoStrip(
                                    vm = vm,
                                    onAddPhotos = {
                                        addToStage = true
                                        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                    onSmartDevelop = { smartDevelopVisible = true },
                                    allowCollapse = false,
                                )
                                EditorStockSelection(vm, onOpenFilmStocks, onOpenPaperStocks)
                            }
                        },
                        // 一级 = 底栏页签，二级 = 药丸行，三级 = 点开药丸后的参数。
                        menuBoard = {
                            if (EditorMenu.isMenuTab(destination)) {
                                panelStates.SaveableStateProvider(destination) {
                                    PhoneMenuBoard(vm, destination, menu, Modifier.fillMaxWidth())
                                }
                            }
                        },
                    )
                        // 照片条已挪进工作台组（照片条在上、胶片 / 相纸栏在下），这里不再单独摆。
                    }
                }
                }
            }
        }
        }

        if(smartDevelopVisible)SmartDevelopSheet(vm,onDismiss={smartDevelopVisible=false})

        NeutralPickView(vm.neutralPick)

        if (aboutVisible) {
            AboutScreen(onBack = { aboutVisible = false }, modifier = Modifier.fillMaxSize().zIndex(5f))
        }

        if (paramHelpVisible) {
            ParamHelpScreen(onBack = { paramHelpVisible = false }, modifier = Modifier.fillMaxSize().zIndex(5f))
        }

        if (paramMenuVisible) {
            // 齿轮里显示的就是首页那一份设置（同一个 SettingsBody），不再是单独一套选项。
            EditorSettingsSheet(
                controller = settingsController,
                vm = vm,
                onDismiss = { paramMenuVisible = false },
            )
        }

        if ((vm.busy || vm.isExporting || vm.isBatchExporting) && selectedTab !in setOf(EditorTab.Export, EditorTab.Gallery)) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }
                    .zIndex(2f),
                contentAlignment = Alignment.Center
            ) {
                        Surface(shape = TheiaShapes.panel, color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    Column(Modifier.widthIn(min = 280.dp, max = 360.dp).padding(horizontal = 22.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val pct = vm.renderProgress
                        val progressAccent = MaterialTheme.colorScheme.primary
                        val progressTrack = MaterialTheme.colorScheme.outlineVariant
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(vm.renderStage.ifBlank { if (vm.isBatchExporting) "逐张导出照片" else "处理中" },
                                Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
                            if (pct != null) Text("${pct.roundToInt()}%", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                        if (pct != null) ExportProgressBar(pct / 100f, progressAccent, progressTrack)
                        else LinearProgressIndicator(Modifier.fillMaxWidth(), color = progressAccent, trackColor = progressTrack)
                        if (vm.isExporting || vm.isBatchExporting) {
                            FilledTonalButton(onClick = { vm.cancelRender() },
                                enabled = vm.renderStage != "正在取消…" && (pct ?: 0f) < 100f &&
                                    !vm.isExportCommitCompleted,
                                modifier = Modifier.fillMaxWidth()) {
                                Text(when {
                                    vm.renderStage == "正在取消…" -> "正在取消…"
                                    vm.isExporting && !vm.isBatchExporting -> "取消处理"
                                    else -> "取消导出"
                                })
                            }
                        }
                    }
                }
            }
        }

    }

    editToolsMode?.let { mode -> EditToolsSheet(vm, presets = mode == "presets", onDismiss = { editToolsMode = null }) }

    if (exportHelpVisible) {
        AlertDialog(onDismissRequest = { exportHelpVisible = false }, title = { Text("导出帮助") },
            text = { Text("JPEG / PNG 使用 sRGB。TIFF 保留 16 位输出，并可选择 Display P3。\n\n原始尺寸会从原文件重新解码导出；需要放大检查效果时，用预览右上角绿点展开工具进入全屏，双指即可缩放。导出文件保存到系统相册。\n\n大尺寸照片建议：如果照片像素较大，尽量避免使用「原始尺寸」全尺寸导出。建议优先选择长边 2048、2560 或 3840 px，以减少内存占用和等待时间；确有需要时再尝试全尺寸导出。",
                modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { exportHelpVisible = false }) { Text("关闭") } })
    }

    // ---- 保存方案对话框（导出方案，与「我的配方」不是一回事）----
    if (showSaveRecipeDialog) {
        AlertDialog(
            onDismissRequest = { showSaveRecipeDialog = false },
            title = { Text("保存方案") },
            text = {
                OutlinedTextField(value = recipeName, onValueChange = { recipeName = it }, label = { Text("方案名") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveRecipe(recipeName)
                    recipeName = ""
                    showSaveRecipeDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showSaveRecipeDialog = false }) { Text("取消") } }
        )
    }

}

/** 编辑页齿轮里的「设置」：与首页同一份设置正文（SettingsBody），用底部抽屉呈现。
 *   「参数说明」已提到齿轮菜单那一层，抽屉里不再重复。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorSettingsSheet(
    controller: com.spektralab.android.feature.settings.SettingsController,
    vm: EditorViewModel,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            Text("设置", style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            com.spektralab.android.feature.settings.SettingsBody(
                controller,
                workspace = com.spektralab.android.data.diagnostics.DiagnosticReport.Workspace(
                    photos = vm.photos.size, hasPhoto = vm.hasPhoto, raw = vm.currentIsRaw,
                    previewWidth = vm.lastResult?.width ?: 0, previewHeight = vm.lastResult?.height ?: 0,
                    exporting = vm.isExporting || vm.isBatchExporting,
                ),
                onOpenHelp = null,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

