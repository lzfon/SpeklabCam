package com.spektralab.android.feature.gallery

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.android.R
import com.spektralab.android.core.model.PhotoLibraryFilter
import com.spektralab.android.core.model.PhotoAlbum
import com.spektralab.android.core.model.TableProjectSummary
import com.spektralab.android.ui.components.DarkroomChrome
import com.spektralab.android.ui.theme.TheiaShapes

@Composable
private fun SelectionBarItem(
    modifier: Modifier,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    enabled: Boolean,
    iconRes: Int = 0,
    onClick: () -> Unit,
) {
    Column(
        modifier.fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(48.dp, 28.dp), contentAlignment = Alignment.Center) {
            val tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
            else if (iconRes != 0) Icon(androidx.compose.ui.res.painterResource(iconRes), null,
                tint = tint, modifier = Modifier.size(19.dp))
        }
        // 五格平分 411dp 屏：11sp 中文五字会互相顶着，收到 10sp 并居中。
        Text(label, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
internal fun PhotoSelectionBar(selection: PhotoSelectionController, filter: PhotoLibraryFilter,
                               count: Long, enabled: Boolean, albums: List<PhotoAlbum>,
                               projects: List<TableProjectSummary>, onAddToViewing: () -> Unit,
                               onAddToProject: (TableProjectSummary) -> Unit) {
    var showAlbums by remember { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 860.dp).fillMaxWidth().navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selection.pending) LinearProgressIndicator(Modifier.fillMaxWidth())
            // 一行五格，与首页底栏同一套：上图标下短名，可用时主题绿。
            Row(Modifier.fillMaxWidth().height(64.dp).background(DarkroomChrome.navigation)) {
                SelectionBarItem(Modifier.weight(1f), stringResource(R.string.photos_select_all),
                    Icons.Rounded.SelectAll, enabled && !selection.pending && count > 0) {
                    selection.selectAll(filter)
                }
                SelectionBarItem(Modifier.weight(1f), stringResource(R.string.photos_remove_count),
                    Icons.Rounded.DeleteOutline, enabled && !selection.pending && selection.selected.isNotEmpty(),
                    onClick = selection::requestRemoval)
                SelectionBarItem(Modifier.weight(1f), stringResource(R.string.photos_add_album),
                    Icons.Rounded.LibraryAdd, enabled && !selection.pending && selection.selected.isNotEmpty()) {
                    showAlbums = true
                }
                SelectionBarItem(Modifier.weight(1f), stringResource(R.string.photos_add_viewing), null,
                    enabled && !selection.pending && selection.selected.isNotEmpty(),
                    iconRes = R.drawable.ic_spk_menu_enlarger, onClick = onAddToViewing)
                SelectionBarItem(Modifier.weight(1f), stringResource(R.string.photos_add_project),
                    Icons.Rounded.Dashboard,
                    enabled && !selection.pending && selection.selected.isNotEmpty() && projects.isNotEmpty()) {
                    showProjects = true
                }
            }
        }
      }
    }
    if (showAlbums) {
        AlertDialog(onDismissRequest = { showAlbums = false },
            title = { Text(stringResource(R.string.photos_choose_album)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (albums.isEmpty()) Text("请先创建一个相册")
                    albums.forEach { album ->
                        TextButton(onClick = { showAlbums = false; selection.addToAlbum(album.id, album.name) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(album.name) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { showAlbums = false }) { Text(stringResource(R.string.library_cancel)) } })
    }
    if (showProjects) {
        AlertDialog(onDismissRequest = { showProjects = false },
            title = { Text(stringResource(R.string.photos_choose_project)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                projects.forEach { project ->
                    TextButton(onClick = { showProjects = false; onAddToProject(project) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(project.name) }
                }
            } }, confirmButton = { TextButton(onClick = { showProjects = false }) { Text(stringResource(R.string.library_cancel)) } })
    }
}

@Composable
internal fun PhotoRemovalDialog(selection: PhotoSelectionController, enabled: Boolean) {
    if (!selection.confirming) return
    AlertDialog(onDismissRequest = selection::dismissRemoval,
        title = { Text(stringResource(R.string.photos_remove_title, selection.selected.size)) },
        text = { Text(stringResource(R.string.photos_remove_hint)) },
        confirmButton = { TextButton(onClick = selection::remove, enabled = enabled && !selection.pending) {
            Text(stringResource(if (selection.pending) R.string.photos_removing else R.string.photos_remove))
        } },
        dismissButton = { TextButton(onClick = selection::dismissRemoval, enabled = !selection.pending) {
            Text(stringResource(R.string.library_cancel))
        } })
}
