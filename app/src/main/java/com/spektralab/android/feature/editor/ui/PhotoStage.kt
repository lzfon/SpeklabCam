package com.spektralab.android.feature.editor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.components.rememberSourcePhotoInfo
import com.spektralab.android.ui.theme.TheiaShapes
import kotlinx.coroutines.delay

/** Always-visible edit queue. Management stays separate from one-tap switching. */
@Composable
internal fun EditorPhotoStrip(
    vm: EditorViewModel,
    onAddPhotos: () -> Unit,
    onSmartDevelop: () -> Unit,
    modifier: Modifier = Modifier,
    // 挪进工作台组后由控制条的工作台按钮统一折叠，这里就不再自己收。
    allowCollapse: Boolean = true,
) {
    val enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting
    val strip = rememberLazyListState()
    val colors = MaterialTheme.colorScheme
    val shortWindow = LocalConfiguration.current.screenHeightDp < 500
    LaunchedEffect(vm.currentIndex, vm.photos.size) {
        if (vm.currentIndex in vm.photos.indices) {
            val visible = strip.layoutInfo.visibleItemsInfo.firstOrNull { it.index == vm.currentIndex }
            if (visible == null || visible.offset < strip.layoutInfo.viewportStartOffset ||
                visible.offset + visible.size > strip.layoutInfo.viewportEndOffset) {
                strip.animateScrollToItem(vm.currentIndex)
            }
        }
    }
    // 折叠：点一下展开；闲置到设置里的秒数（3–10 秒，可在设置关闭）自动收成一窄条。
    var collapsed by rememberSaveable { mutableStateOf(false) }
    var activity by remember { mutableStateOf(0) }
    LaunchedEffect(collapsed, activity, com.spektralab.android.ui.components.UiPrefs.workbenchAutoHide,
        com.spektralab.android.ui.components.UiPrefs.workbenchHideSeconds) {
        if (allowCollapse && !collapsed && com.spektralab.android.ui.components.UiPrefs.workbenchAutoHide) {
            delay(com.spektralab.android.ui.components.UiPrefs.workbenchHideSeconds * 1000L)
            collapsed = true
        }
    }
    val observeTouches = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                activity++
            }
        }
    }
    if (allowCollapse && collapsed) {
        // 收起成一条把手，显示「上箭头 + 工作台」：点一下向上展开照片列表。
        Row(modifier.fillMaxWidth().height(24.dp).background(colors.surfaceContainerLow)
            .clickable { collapsed = false; activity++ }
            .semantics { contentDescription = "展开照片列表" },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.KeyboardArrowUp, null, Modifier.size(16.dp), tint = colors.primary)
            Spacer(Modifier.width(2.dp))
            Text("工作台", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        return
    }
    Row(modifier.fillMaxWidth().background(colors.surfaceContainerLow).then(observeTouches)
        .padding(start = 2.dp, end = 2.dp, top = if (shortWindow) 4.dp else 6.dp, bottom = if (shortWindow) 4.dp else 6.dp)
        .semantics { contentDescription = "顶部照片工作台" }, verticalAlignment = Alignment.CenterVertically) {
        // 照片工作台（弹层）已移除，这里只留当前序号指示。
        Column(Modifier.width(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("照片", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Text("${vm.currentIndex + 1}/${vm.photos.size}", fontSize = 10.sp,
                color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyRow(state = strip, modifier = Modifier.weight(1f).selectableGroup()
            .semantics { contentDescription = "编辑照片列表" },
            contentPadding = PaddingValues(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(vm.photos, key = { _, uri -> uri.toString() }) { index, uri ->
                val current = index == vm.currentIndex
                val thumbnail = vm.developedPreviews[uri] ?: vm.thumbnails[uri]
                LaunchedEffect(uri, thumbnail == null) { if (thumbnail == null) vm.loadThumbnail(uri) }
                Box(Modifier.size(if (shortWindow) 46.dp else 52.dp, if (shortWindow) 40.dp else 46.dp).clip(TheiaShapes.compact)
                    .background(if (current) colors.surfaceContainerHigh else colors.surface)
                    .then(if (current) Modifier.border(2.dp, colors.primary, TheiaShapes.compact) else Modifier)
                    .selectable(selected = current, enabled = enabled, role = Role.RadioButton,
                        onClick = { if (!current) vm.selectPhoto(index) })
                    .semantics(mergeDescendants = true) {
                        contentDescription = "编辑照片 ${index + 1}"
                        selected = current
                        stateDescription = if (current) "正在编辑" else "点击切换编辑"
                    }, contentAlignment = Alignment.Center) {
                    if (thumbnail != null) Image(thumbnail.asImageBitmap(), null,
                        Modifier.fillMaxSize().padding(if (current) 3.dp else 1.dp), contentScale = ContentScale.Fit)
                    else Icon(Icons.Rounded.Image, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                    Text("${index + 1}", Modifier.align(Alignment.BottomStart).padding(3.dp)
                        .background(colors.surface.copy(alpha = .9f), TheiaShapes.badge).padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (current) colors.primary else colors.onSurface)
                    // 当前照片右上角的 ✕：把这照片从编辑列表里移出（原片与已存调整都保留）。
                    if (current) Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp)
                        .clip(CircleShape).background(colors.surface.copy(alpha = .92f))
                        .clickable(enabled = enabled && vm.photos.size >= 0) {
                            vm.removePhotoFromSession(uri)
                        }
                        .semantics { contentDescription = "从编辑列表移出这张照片" },
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, null, Modifier.size(12.dp), tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
        IconButton(onClick=onSmartDevelop,modifier=Modifier.size(40.dp)) {
            Icon(Icons.Rounded.Palette,"智能显影",Modifier.size(20.dp),
                tint=if(vm.smartAutoEnabled)colors.primary else colors.onSurfaceVariant)
        }
        IconButton(onClick = onAddPhotos, enabled = enabled, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.Add, "添加编辑照片", Modifier.size(20.dp),
                tint = if (enabled) colors.primary else colors.onSurfaceVariant)
        }
        // 行尾的「收起工作台」按钮已删：收起交给控制条上的工作台按钮，右侧只剩智能显影与添加两颗。
    }
}

