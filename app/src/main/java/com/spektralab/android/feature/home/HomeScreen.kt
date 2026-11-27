package com.spektralab.android.feature.home

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.spektralab.android.R
import com.spektralab.android.core.model.PhotoLibraryFilter
import com.spektralab.android.data.library.SavedPhoto
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.feature.editor.ui.EditToolsSheet
import com.spektralab.android.feature.editor.ui.ParamHelpScreen
import com.spektralab.android.feature.smartdevelop.SmartDevelopSheet
import com.spektralab.android.ui.components.DarkroomChrome
import com.spektralab.android.ui.components.captureDay
import com.spektralab.android.ui.components.captureReadouts
import com.spektralab.android.ui.components.rememberSourcePhotoInfo
import com.spektralab.android.ui.components.UiPrefs
import com.spektralab.android.ui.theme.TheiaShapes

/**
 * 首页（第五轮版式，参照手机版 Lightroom 图库）。
 *
 *  - 顶栏只剩标题 + 齿轮（中断的任务 / 设置 / 关于 / 参数说明，与编辑页齿轮同一层级）；副标题栏已删。
 *  - 中间一整块显示区，从顶栏下面一直铺到底栏上边缘，两种形式（齿轮设置「首页」那一节切换）：
 *    当前照片的预览框（照片只在上半部分居中显示，参数条压在预览框下部、悬浮按钮正上方，
 *    30% 黑底白字），或图库照片的两列瀑布流。
 *  - 瀑布流长按勾选；勾选后底栏三格缓慢换成两格「上次工作台 / 加入工作台」。
 *  - 右下悬浮一列（左边缘对齐）：已选数量、智能配方（带所选配方全名与状态）、导入 / 拍摄胶囊，半透明主题绿底白字，按下谁谁翻成白底绿字。
 *
 * 图库与观片台由 [HomeShell] 以左右滑入的面板承载。
 */
@Composable
internal fun HomeScreen(
    vm: EditorViewModel,
    onImportPhotos: () -> Unit,
    onAddPhotos: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenViewingStand: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSavedPhotos: () -> Unit = {},
    onOpenTasks: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    activePanel: String? = null,
) {
    var moreExpanded by remember { mutableStateOf(false) }
    var smartVisible by rememberSaveable { mutableStateOf(false) }
    var recipesVisible by rememberSaveable { mutableStateOf(false) }
    var helpVisible by rememberSaveable { mutableStateOf(false) }
    // 瀑布流里的长按勾选集合（存 assetId）。
    var picked by rememberSaveable { mutableStateOf(setOf<String>()) }
    // 预览框形式里没有勾选入口，切换显示形式时把勾选丢掉，免得底栏停在两格「幽灵勾选」态。
    LaunchedEffect(UiPrefs.homeShowLibraryGrid) { picked = emptySet() }
    val preview = vm.rendered ?: vm.sourceBitmap
    val busy = vm.busy || vm.isExporting || vm.isBatchExporting

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 8.dp)) {
                    Text("SpeklabCam", Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                    // 齿轮菜单与编辑页齿轮同一层级：中断的任务 / 设置 / 关于 / 参数说明。
                    Box(Modifier.align(Alignment.CenterEnd)) {
                        IconButton(onClick = { moreExpanded = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Settings, "更多操作", modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                            DropdownMenuItem(text = { Text("中断的任务") },
                                onClick = { moreExpanded = false; onOpenTasks() })
                            DropdownMenuItem(text = { Text("设置") },
                                onClick = { moreExpanded = false; onOpenSettings() })
                            DropdownMenuItem(text = { Text("关于") },
                                onClick = { moreExpanded = false; onOpenAbout() })
                            DropdownMenuItem(text = { Text("参数说明") },
                                onClick = { moreExpanded = false; helpVisible = true })
                        }
                    }
                }
            }
        },
        bottomBar = {
            HomeBottomBar(
                enabled = !busy,
                pickedCount = picked.size,
                activePanel = activePanel,
                onLibrary = onOpenSavedPhotos,
                onEditor = onOpenEditor,
                onStand = onOpenViewingStand,
                onRestoreLast = {
                    val uris = pickedUris(vm, picked)
                    // 直接读已加载好的历史工作台：restoreSession 走异步，没有会话时回调根本不发，
                    // 老写法会把勾选清空又什么都不做。这里改成有会话就回工作台，
                    // 没会话就退一步把勾选的照片开成工作台，两条路都只在真的做了事时才清勾选。
                    val session = vm.savedPhotos.savedSession
                    if (session != null && session.photos.isNotEmpty()) {
                        picked = emptySet()
                        vm.restoreWorkbench(session)
                        if (uris.isNotEmpty()) vm.addPhotos(uris, openSelected = true)
                        onOpenEditor()
                    } else if (uris.isNotEmpty()) {
                        picked = emptySet()
                        uris.forEach(vm::persistReadPermission)
                        vm.importPhotos(uris, addToSession = true)
                        onOpenEditor()
                    }
                },
                onAddToWorkbench = {
                    val uris = pickedUris(vm, picked)
                    if (uris.isNotEmpty()) {
                        picked = emptySet()
                        uris.forEach(vm::persistReadPermission)
                        vm.importPhotos(uris, addToSession = true)
                        onOpenEditor()
                    }
                },
            )
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            // 显示区整块到底：预览框或瀑布流。
            Box(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                if (UiPrefs.homeShowLibraryGrid) {
                    HomeLibraryWaterfall(vm, picked, onToggle = { id ->
                        picked = if (picked.contains(id)) picked - id else picked + id
                    }, onOpen = { uri ->
                        if (!busy) {
                            vm.openPhotos(listOf(uri))
                            onOpenEditor()
                        }
                    })
                } else {
                    HomePreviewPane(vm, preview, busy, onOpenEditor, onOpenCamera)
                }
            }
            // 悬浮区：智能配方靠左、导入 · 拍摄靠右，同一行；勾选时「已选xx张」在它上面居中。
            HomeFloatingCluster(
                vm = vm,
                enabled = !busy,
                pickedCount = picked.size,
                onClearPicked = { picked = emptySet() },
                onSmart = { smartVisible = true },
                onImport = onImportPhotos,
                onCamera = onOpenCamera,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            )
            if (helpVisible) {
                ParamHelpScreen(onBack = { helpVisible = false },
                    modifier = Modifier.fillMaxSize().zIndex(5f))
            }
        }

        if (smartVisible) SmartDevelopSheet(vm, onDismiss = { smartVisible = false },
            onOpenRecipes = { smartVisible = false; recipesVisible = true })
        if (recipesVisible) EditToolsSheet(vm, presets = true, onDismiss = { recipesVisible = false })
    }
}

/** 勾选集合（assetId）换算成 Uri 列表，保持图库里的顺序。 */
private fun pickedUris(vm: EditorViewModel, picked: Set<String>): List<Uri> =
    vm.savedPhotos.photos.filter { picked.contains(it.assetId) }.map { Uri.parse(it.uri) }

/**
 * 首页底栏：平时三格「图库 / Speklab / 观片」（上图标下文字）；
 * 一旦瀑布流里有长按勾选的照片，整行缓慢淡出，换成两格「上次工作台 / 加入工作台」。
 */
@Composable
private fun HomeBottomBar(
    enabled: Boolean,
    pickedCount: Int,
    activePanel: String?,
    onLibrary: () -> Unit,
    onEditor: () -> Unit,
    onStand: () -> Unit,
    onRestoreLast: () -> Unit,
    onAddToWorkbench: () -> Unit,
) {
    val navigationHeight = 64.dp + 24.dp * (LocalDensity.current.fontScale - 1f).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f), thickness = .5.dp)
        AnimatedContent(
            targetState = pickedCount > 0,
            transitionSpec = {
                // 「缓慢弹出」：淡入 + 轻微上移 420ms，淡出 240ms。
                (fadeIn(tween(420, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(420, easing = FastOutSlowInEasing)) { it / 3 }) togetherWith
                    fadeOut(tween(240))
            },
            label = "home-bottom-bar-mode",
            modifier = Modifier.fillMaxWidth().height(navigationHeight).background(DarkroomChrome.navigation),
        ) { selecting ->
            Row(Modifier.fillMaxSize()) {
                if (selecting) {
                    HomeActionBarItem(Modifier.weight(1f), "上次工作台",
                        R.drawable.ic_spk_tab_restore, enabled, onRestoreLast)
                    HomeActionBarItem(Modifier.weight(1f), "加入工作台",
                        R.drawable.ic_spk_tab_addwork, enabled, onAddToWorkbench)
                } else {
                    HomeBarItem(Modifier.weight(1f), "图库", R.drawable.ic_spk_tab_gallery, enabled,
                        lit = activePanel == "library", onClick = onLibrary)
                    // 中间这颗就是应用本体：图标直接用 app 图标，尺寸与左右一致。
                    HomeAppItem(Modifier.weight(1f), "Speklab", enabled, onEditor)
                    HomeBarItem(Modifier.weight(1f), "观片", R.drawable.ic_spk_menu_enlarger, enabled,
                        lit = activePanel == "stand", onClick = onStand)
                }
            }
        }
    }
}

/** 三格平时的样式：上图标下文字，面板开着的那格点亮成主题绿。 */
@Composable
private fun HomeBarItem(
    modifier: Modifier,
    label: String,
    icon: Int,
    enabled: Boolean,
    lit: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(48.dp, 28.dp)
            .background(if (lit) DarkroomChrome.selected else Color.Transparent, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(19.dp))
        }
        Text(label, color = tint, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** 中间那格用 app 图标本体（不上色），放大一号以与左右两格线条图标视觉等重。 */
@Composable
private fun HomeAppItem(modifier: Modifier, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier.fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(48.dp, 28.dp), contentAlignment = Alignment.Center) {
            // app 图标四周自带留白，画得比线条图标大一号才跟左右两格视觉等重。
            Image(painterResource(R.drawable.ic_launcher_foreground), label, Modifier.size(28.dp))
        }
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
            lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 勾选态的两格：图标 + 文字，主题绿字，按下有深绿底。 */
@Composable
private fun HomeActionBarItem(
    modifier: Modifier,
    label: String,
    icon: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier.fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(if (pressed) DarkroomChrome.selected else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled,
                role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(48.dp, 28.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp))
        }
        Text(label, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, lineHeight = 16.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 悬浮区两行：
 *  1. 勾选时上面一行居中出现「已选xx张」，点它清空勾选，与下面那行隔 16dp，不会误触；
 *  2. 下面一行同一水平线：智能配方（带所选配方全名与状态说明）贴屏幕左边，导入 | 拍摄胶囊贴右边。
 * 同一套形态：半透明主题绿底、白色图标文字；按下谁，谁翻成白底 + 主题绿。
 */
@Composable
private fun HomeFloatingCluster(
    vm: EditorViewModel,
    enabled: Boolean,
    pickedCount: Int,
    onClearPicked: () -> Unit,
    onSmart: () -> Unit,
    onImport: () -> Unit,
    onCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val green = MaterialTheme.colorScheme.primary
    val recipe = vm.smartRecipe?.name ?: vm.smartStyle.title
    val recipeState = when {
        vm.smartImportRunning -> "准备 ${vm.smartImportCompleted}/${vm.smartImportTotal} 张照片"
        vm.smartAutoEnabled -> "导入后逐张分析，自动选片与调色"
        else -> "自动处理已关闭，可手动应用"
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (pickedCount > 0) {
            Surface(shape = TheiaShapes.badge, color = green.copy(alpha = .55f), contentColor = Color.White,
                tonalElevation = 0.dp, shadowElevation = 3.dp) {
                Row(Modifier.clip(TheiaShapes.badge)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClearPicked)
                    .semantics { contentDescription = "取消选择" }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("已选${pickedCount}张，点击取消", color = Color.White, fontSize = 12.sp,
                        lineHeight = 15.sp, maxLines = 1)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(30.dp), color = green.copy(alpha = .72f),
                contentColor = Color.White, tonalElevation = 0.dp, shadowElevation = 4.dp) {
                HomeClusterHalf(enabled = enabled, green = green, height = null, onClick = onSmart,
                    description = "智能配方 $recipe") { pressed ->
                    val fg = if (pressed) green else Color.White
                    Icon(Icons.Rounded.Palette, null, Modifier.size(21.dp), tint = fg)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.widthIn(max = 186.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("智能配方 · $recipe", fontSize = 12.sp, lineHeight = 15.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = fg)
                        Text(recipeState, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (pressed) green.copy(alpha = .85f) else Color.White.copy(alpha = .85f))
                    }
                }
            }
            Surface(shape = RoundedCornerShape(30.dp), color = green.copy(alpha = .72f),
                contentColor = Color.White, tonalElevation = 0.dp, shadowElevation = 4.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HomeFabHalf(Icons.Rounded.AddPhotoAlternate, "导入照片", enabled, green, onImport)
                    Box(Modifier.width(1.dp).height(28.dp).background(Color.White.copy(alpha = .55f)))
                    HomeFabHalf(Icons.Rounded.CameraAlt, "打开系统相机拍摄", enabled, green, onCamera)
                }
            }
        }
    }
}

/** 悬浮簇里可按下的一块：按下翻白底，松开回半透明主题绿。 */
@Composable
private fun HomeClusterHalf(enabled: Boolean, green: Color, onClick: () -> Unit, description: String,
                            height: Dp? = 52.dp,
                            content: @Composable (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier.then(if (height == null) Modifier else Modifier.height(height))
            .clip(RoundedCornerShape(30.dp))
            .background(if (pressed) Color.White.copy(alpha = .92f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled,
                role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 14.dp, vertical = if (height == null) 9.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(pressed) }
}

@Composable
private fun HomeFabHalf(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String,
                        enabled: Boolean, green: Color, onClick: () -> Unit) {
    HomeClusterHalf(enabled = enabled, green = green, onClick = onClick, description = description) { pressed ->
        Icon(icon, null, Modifier.size(23.dp),
            tint = if (pressed) green else Color.White.copy(alpha = if (enabled) 1f else .55f))
    }
}

/**
 * 预览框形式：照片只在显示区的上半部分按比例居中（横版竖版都不会被压住），
 * 参数条压在预览框下部（30% 黑底白字），下面再留出一段正好给悬浮按钮那一行。
 */
@Composable
private fun HomePreviewPane(
    vm: EditorViewModel,
    preview: Bitmap?,
    busy: Boolean,
    onOpenEditor: () -> Unit,
    onOpenCamera: () -> Unit,
) {
    if (vm.hasPhoto && preview != null) {
        val identity = rememberSourcePhotoInfo(vm.currentUri,
            vm.photoInfo.firstOrNull { it.first == "文件名" }?.second, vm.currentIsRaw)
        val film = vm.films.firstOrNull { it.id == vm.params.filmId }?.name ?: "胶片"
        val paper = vm.papers.firstOrNull { it.id == vm.params.paperId }?.name ?: "相纸"
        Box(
            Modifier.fillMaxSize()
                .clip(TheiaShapes.panel)
                .background(Color.Black)
                .clickable(role = Role.Button, onClick = onOpenEditor),
        ) {
            Column(Modifier.fillMaxSize()) {
                // 上半部分：照片按自身比例居中，横版竖版都完整可见。
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(bitmap = preview.asImageBitmap(), contentDescription = "继续显影",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                }
            // 参数条：压在预览框下部，位置就是悬浮按钮上面那一格。
            Column(
                Modifier.fillMaxWidth()
                    .background(Color.Black.copy(alpha = .3f))
                    .padding(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 6.dp)
                    .semantics { contentDescription = "首页照片参数" },
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("$film / $paper", Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall,
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(identity.detail, style = MaterialTheme.typography.labelSmall, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    val dimensions = vm.photoInfo.firstOrNull { it.first == "原始尺寸" }?.second
                        ?: "预览 ${preview.width} × ${preview.height}"
                    Text(dimensions, style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
                val readouts = captureReadouts(vm.photoInfo) + listOfNotNull(captureDay(vm.photoInfo))
                if (readouts.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    readouts.forEach { value ->
                        Text(value, style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
            }
                // 悬浮那一簇 52dp + 上下间距，正好空出来不压参数。
                Spacer(Modifier.height(74.dp))
            }
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxSize().clickable(enabled = !busy, onClick = onOpenCamera),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = TheiaShapes.control,
        ) {
            Column(Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.CameraAlt, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp))
                Spacer(Modifier.size(14.dp))
                Text("打开系统相机拍摄", style = MaterialTheme.typography.titleMedium)
                Text("拍完回到这里，新照片按当前配方直接冲印。", Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 瀑布流形式：图库照片两列错落铺开（每格保持原片比例，参照 Lightroom），只显示缩略图本身。
 * 单击进编辑页，长按进入勾选。
 */
@Composable
private fun HomeLibraryWaterfall(
    vm: EditorViewModel,
    picked: Set<String>,
    onToggle: (String) -> Unit,
    onOpen: (Uri) -> Unit,
) {
    val controller = vm.savedPhotos
    val state = rememberLazyStaggeredGridState()
    LaunchedEffect(Unit) {
        // 只看整个图库：把图库页可能留下的筛选条件清掉。
        if (controller.filter.active) controller.applyFilter(PhotoLibraryFilter())
        controller.refresh()
    }
    val atEnd by remember(state) {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()
            last != null && last.index >= state.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(atEnd) { if (atEnd) controller.more() }

    if (controller.photos.isEmpty() && !controller.loading) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.Image, null, Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.primary)
            Text("图库里还没有照片", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall)
            Text("先用右下角胶囊按钮导入，或去「图库」里整理。", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(top = 2.dp, bottom = 74.dp),
        verticalItemSpacing = 6.dp,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(controller.photos, key = { it.assetId }) { photo ->
            DisposableEffect(photo.assetId, photo.uri) {
                controller.thumbnail(photo)
                onDispose { controller.cancelThumbnail(photo.assetId) }
            }
            HomeWaterfallTile(photo, controller.thumbnails[photo.assetId], picked.contains(photo.assetId),
                onClick = { if (picked.isEmpty()) onOpen(Uri.parse(photo.uri)) else onToggle(photo.assetId) },
                onLongClick = { onToggle(photo.assetId) })
        }
        if (controller.loading) item {
            Box(Modifier.fillMaxWidth().heightIn(min = 96.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HomeWaterfallTile(photo: SavedPhoto, bitmap: Bitmap?, selected: Boolean,
                              onClick: () -> Unit, onLongClick: () -> Unit) {
    // 每格按缩略图的真实比例决定高度，形成错落；还没解码出来时先占 1:1。
    val ratio = if (bitmap != null && bitmap.height > 0) {
        bitmap.width.toFloat() / bitmap.height.toFloat()
    } else 1f
    Box(
        Modifier.fillMaxWidth()
            .aspectRatio(ratio.coerceIn(0.45f, 2.4f))
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                  else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .semantics { contentDescription = photo.name; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        } else {
            Icon(Icons.Rounded.Image, null, Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) {
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = Color.White)
            }
        }
    }
}
