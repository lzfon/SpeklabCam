package com.spektralab.android.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.launch
import com.spektralab.android.AppRoute
import com.spektralab.android.feature.about.AboutScreen
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.ui.EditorScreen
import com.spektralab.android.feature.export.ExportTasksScreen
import com.spektralab.android.feature.home.HomeScreen
import com.spektralab.android.feature.library.FilmStockScreen
import com.spektralab.android.feature.lighttable.GalleryScreen
import com.spektralab.android.feature.lighttable.ViewingStand
import com.spektralab.android.feature.lighttable.rememberFilmCanvasState
import com.spektralab.android.feature.settings.SettingsController
import com.spektralab.android.feature.settings.SettingsScreen

@Composable
internal fun TheiaRoot(vm: EditorViewModel) {
    val storageError by com.spektralab.android.data.local.EditStore.storageError.collectAsState()
    var route by rememberSaveable { mutableStateOf(AppRoute.Home) }
    var addToHomeSession by rememberSaveable { mutableStateOf(false) }
    var libraryStartsWithPaper by rememberSaveable { mutableStateOf(false) }
    var libraryVisit by rememberSaveable { mutableStateOf(0) }
    var homeStandEditRequest by rememberSaveable { mutableStateOf(0) }
    val screenStates = rememberSaveableStateHolder()
    val viewingCanvasState=rememberFilmCanvasState()
    val mediaLocationContext = LocalContext.current
    var pendingPick by remember { mutableStateOf<(() -> Unit)?>(null) }
    val mediaLocationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 授予与否都继续打开选择器；授予了才能在导入那一刻读到原片位置。
        val action = pendingPick
        pendingPick = null
        action?.invoke()
    }

    /** 导入前先要「照片位置信息」权限：导入那一刻选择器授权还在，是唯一能读到原片 GPS 的时机。 */
    fun withMediaLocation(action: () -> Unit) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            mediaLocationContext, android.Manifest.permission.ACCESS_MEDIA_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) action() else {
            pendingPick = action
            mediaLocationPermission.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }

    val homeViewingPicker=rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems=24)
    ){uris ->
        if(uris.isNotEmpty()){
            uris.forEach(vm::persistReadPermission)
            vm.loadViewingPhotos(uris)
        }
    }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(vm::persistReadPermission)
            vm.importPhotos(uris, addToSession = addToHomeSession)
            route = AppRoute.Editor
        }
        addToHomeSession = false
    }

    fun launchPhotoPicker(add: Boolean) {
        addToHomeSession = add
        withMediaLocation { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }

    // ---- 系统相机：拍完把新照片带回暗房，直接按当前配方冲印 ----
    val context = LocalContext.current
    com.spektralab.android.ui.components.UiPrefs.attach(context)
    val cameraScope = rememberCoroutineScope()
    val settingsController = remember { SettingsController(context.applicationContext, cameraScope) }
    var cameraMinId by rememberSaveable { mutableStateOf(0L) }
    var cameraArmed by rememberSaveable { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!cameraArmed) return@rememberLauncherForActivityResult
        cameraArmed = false
        val bookmark = cameraMinId
        cameraMinId = 0L
        cameraScope.launch {
            // 系统相机写库有延迟，MediaStore 常常要过一两秒才索引到新照片，所以轮询几次再放弃。
            var fresh = emptyList<android.net.Uri>()
            for (attempt in 0 until 6) {
                fresh = scanNewShots(context, bookmark)
                if (fresh.isNotEmpty()) break
                kotlinx.coroutines.delay(700L + attempt * 300L)
            }
            if (fresh.isNotEmpty()) {
                // 相机往往先落 JPG、DNG 晚一两秒才写库；再等一轮把同一次拍摄的 RAW 一起带回来。
                kotlinx.coroutines.delay(1500L)
                fresh = (fresh + scanNewShots(context, bookmark)).distinctBy { it.toString() }
                // 上一张还在冲印时 importPhotos 会被静默丢掉，等引擎空闲再入队。
                var waited = 0
                while ((vm.busy || vm.isExporting || vm.isBatchExporting) && waited < 20) {
                    kotlinx.coroutines.delay(500L)
                    waited++
                }
                fresh.forEach(vm::persistReadPermission)
                vm.importPhotos(fresh, addToSession = true)
                route = AppRoute.Editor
            }
            // 没拍、取消拍摄或照片还没进相册时静默返回，不再弹提示。
        }
    }

    fun openSystemCamera() {
        cameraMinId = currentMaxImageId(context)
        cameraArmed = true
        val intent = android.content.Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        runCatching { cameraLauncher.launch(intent) }.onFailure {
            cameraArmed = false
            android.widget.Toast.makeText(context, "这台设备没有可用的系统相机", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openSystemCamera()
        else android.widget.Toast.makeText(context, "需要相册读取权限，才能把刚拍的照片带回暗房", android.widget.Toast.LENGTH_LONG).show()
    }

    fun requestSystemCamera() {
        val permission = if (android.os.Build.VERSION.SDK_INT >= 33)
            android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) openSystemCamera() else cameraPermission.launch(permission)
    }

    BackHandler(enabled = route != AppRoute.Home) {
        route = when (route) {
            AppRoute.Home -> AppRoute.Home
            AppRoute.Editor, AppRoute.About, AppRoute.ViewingStand, AppRoute.SavedPhotos,
            AppRoute.ExportTasks, AppRoute.Settings -> AppRoute.Home
            AppRoute.FilmStocks -> AppRoute.Editor
        }
    }

    Box(Modifier.fillMaxSize()) {
        screenStates.SaveableStateProvider(route) {
          when (route) {
            AppRoute.Home -> HomeScreen(
                vm = vm,
                onImportPhotos = { launchPhotoPicker(add = false) },
                onAddPhotos = { launchPhotoPicker(add = true) },
                onOpenCamera = { requestSystemCamera() },
                onOpenEditor = { route = AppRoute.Editor },
                onOpenViewingStand = { route = AppRoute.ViewingStand },
                onOpenAbout = { route = AppRoute.About },
                onOpenSavedPhotos = { vm.lightTableProjects.refresh(); route = AppRoute.SavedPhotos },
                onOpenTasks = { route = AppRoute.ExportTasks },
                onOpenSettings = { route = AppRoute.Settings },
            )
            AppRoute.SavedPhotos -> com.spektralab.android.feature.gallery.SavedPhotosScreen(
                controller = vm.savedPhotos, enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting,
                onBack = { route = AppRoute.Home }, onImport = { launchPhotoPicker(false) },
                onOpen = { uri -> vm.openPhotos(listOf(uri)); route = AppRoute.Editor },
                onAddToViewing = { uris -> vm.loadViewingPhotos(uris); route = AppRoute.ViewingStand },
                onAddAllToWorkbench = { uris ->
                    uris.forEach(vm::persistReadPermission)
                    vm.importPhotos(uris, addToSession = true)
                    route = AppRoute.Editor
                },
                projects = vm.lightTableProjects.projects,
                onAddToProject = { uris, project -> vm.lightTableProjects.addUrisToProject(project, uris) },
                onRestore = { session -> vm.restoreWorkbench(session); route = AppRoute.Editor },
                onRelink = vm::relinkWorkbenchSource,
                prepareVersions = vm::prepareVersionBoundary,
                versionInUse = { it in vm.photos || it == vm.currentUri },
                onVersionOpen = { uri -> vm.addPhotos(listOf(uri), openSelected = true); route = AppRoute.Editor },
            )
            AppRoute.Editor -> EditorScreen(
                vm = vm,
                onBackHome = { route = AppRoute.Home },
                onOpenFilmStocks = {
                    libraryStartsWithPaper = false; libraryVisit++; route = AppRoute.FilmStocks
                },
                onOpenPaperStocks = {
                    libraryStartsWithPaper = true; libraryVisit++; route = AppRoute.FilmStocks
                },
                viewingCanvasState=viewingCanvasState,
                homeStandEditRequest=homeStandEditRequest,
                onOpenCamera = { requestSystemCamera() },
            )
            AppRoute.ViewingStand -> GalleryScreen(
                vm=vm,
                onImport={ withMediaLocation { homeViewingPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
                onBackToDarkroom={route=AppRoute.Home},
                onRemovePhoto={uri ->
                    if(vm.viewingPhotos.any{it.first==uri.toString()})vm.removeViewingPhoto(uri.toString())
                    if(uri in vm.photos)vm.removePhotoFromSession(uri)
                },
                onEditPhoto={uri ->
                    val index=vm.photos.indexOf(uri)
                    if(index>=0)vm.selectPhoto(index)else vm.addPhotos(listOf(uri),openSelected=true)
                    homeStandEditRequest++
                    route=AppRoute.Editor
                },
                modifier=Modifier.fillMaxSize(),canvasState=viewingCanvasState,backDescription="返回首页",
            )
            AppRoute.FilmStocks -> key(libraryVisit) {
                FilmStockScreen(vm, onBack = { route = AppRoute.Editor }, startWithPaper = libraryStartsWithPaper)
            }
            AppRoute.ExportTasks -> ExportTasksScreen(vm, onBack = { route = AppRoute.Home }, modifier = Modifier.fillMaxSize())
            AppRoute.Settings -> SettingsScreen(settingsController, onBack = { route = AppRoute.Home },
                workspace = com.spektralab.android.data.diagnostics.DiagnosticReport.Workspace(
                    photos = vm.photos.size, hasPhoto = vm.hasPhoto, raw = vm.currentIsRaw,
                    previewWidth = vm.lastResult?.width ?: 0, previewHeight = vm.lastResult?.height ?: 0,
                    exporting = vm.isExporting || vm.isBatchExporting,
                ))
            AppRoute.About -> AboutScreen(onBack = { route = AppRoute.Home }, modifier = Modifier.fillMaxSize())
          }
        }

        storageError?.let { message ->
            AlertDialog(onDismissRequest = com.spektralab.android.data.local.EditStore::dismissStorageError,
                title = { Text(androidx.compose.ui.res.stringResource(com.spektralab.android.R.string.photos_save_error)) },
                text = { Text(message) },
                dismissButton = { TextButton(onClick = vm::retrySavedEdit, enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting) {
                    Text(androidx.compose.ui.res.stringResource(com.spektralab.android.R.string.photos_retry_save))
                } }, confirmButton = { TextButton(onClick = com.spektralab.android.data.local.EditStore::dismissStorageError) {
                    Text(androidx.compose.ui.res.stringResource(com.spektralab.android.R.string.photos_understood))
                } })
        }
        vm.errorMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { vm.dismissError() },
                title = { Text("出错了") },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = { vm.dismissError() }) { Text("确定") } },
            )
        }
    }
    BackHandler(enabled = vm.busy || vm.isExporting || vm.isBatchExporting) { }
}

/** 系统相机返回后，取比 [afterId] 更新的照片（最多 9 张，跳过本应用自己的导出）。
 *  只收「已写完」的条目：IS_PENDING=0 且大小>0，并读文件头确认不是半截文件，
 *  否则相机还在写 DNG 时抢跑导入会报 "Unsupported file format or not RAW file"。 */
private suspend fun scanNewShots(context: android.content.Context, afterId: Long, limit: Int = 9): List<android.net.Uri> =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            android.provider.MediaStore.Images.Media._ID,
            android.provider.MediaStore.Images.Media.RELATIVE_PATH,
        )
        val selection = if (android.os.Build.VERSION.SDK_INT >= 29)
            "${android.provider.MediaStore.Images.Media.IS_PENDING}=0 AND ${android.provider.MediaStore.Images.Media.SIZE}>0"
        else "${android.provider.MediaStore.Images.Media.SIZE}>0"
        val result = mutableListOf<android.net.Uri>()
        runCatching {
            context.contentResolver.query(
                collection, projection, selection, null,
                "${android.provider.MediaStore.Images.Media._ID} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                val pathColumn = cursor.getColumnIndex(android.provider.MediaStore.Images.Media.RELATIVE_PATH)
                while (cursor.moveToNext() && result.size < limit) {
                    val id = cursor.getLong(idColumn)
                    if (id <= afterId) break
                    val path = if (pathColumn >= 0) cursor.getString(pathColumn).orEmpty() else ""
                    if (path.contains("spektralab") || path.contains("Theia")) continue
                    val uri = android.content.ContentUris.withAppendedId(collection, id)
                    if (readableImageHeader(context, uri)) result += uri
                }
            }
        }
        result.reversed()
    }

/** 读前 12 字节确认是完整可读的 JPEG / TIFF(DNG) / PNG / HEIF 头；读不到或全零视为还没写完。 */
private fun readableImageHeader(context: android.content.Context, uri: android.net.Uri): Boolean = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val head = ByteArray(12)
        var read = 0
        while (read < head.size) {
            val n = stream.read(head, read, head.size - read)
            if (n <= 0) break
            read += n
        }
        if (read < 4) return@runCatching false
        val b0 = head[0].toInt() and 0xff; val b1 = head[1].toInt() and 0xff
        val jpeg = b0 == 0xff && b1 == 0xd8
        val tiff = (b0 == 0x49 && b1 == 0x49) || (b0 == 0x4d && b1 == 0x4d)
        val png = b0 == 0x89 && b1 == 0x50
        val heif = read >= 8 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
        jpeg || tiff || png || heif
    } ?: false
}.getOrDefault(false)

/** 当前相册里最大的图片 _ID，作为「拍摄前」的书签。 */
private fun currentMaxImageId(context: android.content.Context): Long = runCatching {
    context.contentResolver.query(
        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(android.provider.MediaStore.Images.Media._ID), null, null,
        "${android.provider.MediaStore.Images.Media._ID} DESC"
    )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
}.getOrDefault(0L)
