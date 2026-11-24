package com.spektralab.android.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.android.EditorTab
import com.spektralab.android.R
import com.spektralab.android.ui.components.DarkroomChrome

/** 底栏页签：冲印 / 调色 / 观片台（导出走控制条，不进底栏）。 */
private val barTabs: List<EditorTab> = EditorTab.entries.filter { it != EditorTab.Export }

/** 底栏顺序：冲印 · 调色 · 拍摄 · 观片台。拍摄是一次性动作，不是页面。 */
private const val CAPTURE_INDEX = 2

@Composable
internal fun EditorNavigationBar(
    selected: EditorTab,
    enabled: Boolean,
    onSelect: (EditorTab) -> Unit,
    onCapture: () -> Unit = {},
) {
    // 与 DarkroomBottomBar 同一套尺寸与配色，四个格子等宽等距。
    val selectedIndex = barTabs.indexOf(selected).let { if (it < 0) 0 else it + if (it >= CAPTURE_INDEX) 1 else 0 }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f), thickness = .5.dp)
        Row(Modifier.fillMaxWidth().height(64.dp).background(DarkroomChrome.navigation).selectableGroup()) {
            fun slot(index: Int): Modifier = Modifier.weight(1f).fillMaxHeight()
            // 冲印、调色
            barTabs.take(CAPTURE_INDEX).forEachIndexed { i, tab ->
                BottomSlot(slot(i), tab.label, tab.icon, selectedIndex == i, enabled, onClick = { onSelect(tab) })
            }
            // 拍摄（动作）
            BottomSlot(slot(CAPTURE_INDEX), "拍摄", R.drawable.ic_spk_tab_capture, false, enabled, onClick = onCapture)
            // 观片台
            barTabs.drop(CAPTURE_INDEX).forEachIndexed { i, tab ->
                val position = CAPTURE_INDEX + 1 + i
                BottomSlot(slot(position), tab.label, tab.icon, selectedIndex == position, enabled, onClick = { onSelect(tab) })
            }
        }
    }
}

@Composable
private fun BottomSlot(
    modifier: Modifier,
    label: String,
    icon: Int,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier
        .selectable(selected = active, enabled = enabled, role = Role.Tab, onClick = onClick)
        .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(48.dp, 28.dp).background(
            if (active) DarkroomChrome.selected else Color.Transparent, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(19.dp))
        }
        Text(label, color = tint, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1)
    }
}

@Composable
internal fun EditorNavigationRail(
    selected: EditorTab,
    onSelect: (EditorTab) -> Unit,
    onExport: () -> Unit = {},
    onCapture: () -> Unit = {},
) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
        barTabs.take(CAPTURE_INDEX).forEach { tab ->
            railItem(tab, selected, onSelect)
        }
        NavigationRailItem(
            selected = false,
            onClick = onCapture,
            icon = { Icon(painterResource(R.drawable.ic_spk_tab_capture), contentDescription = null) },
            label = { Text("拍摄") },
            alwaysShowLabel = true,
            colors = NavigationRailItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        barTabs.drop(CAPTURE_INDEX).forEach { tab ->
            railItem(tab, selected, onSelect)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
        NavigationRailItem(
            selected = selected == EditorTab.Export,
            onClick = onExport,
            icon = { Icon(painterResource(EditorTab.Export.icon), contentDescription = null) },
            label = { Text(EditorTab.Export.label) },
            alwaysShowLabel = true,
            colors = NavigationRailItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

@Composable
private fun railItem(tab: EditorTab, selected: EditorTab, onSelect: (EditorTab) -> Unit) {
    NavigationRailItem(
        selected = selected == tab,
        onClick = { onSelect(tab) },
        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
        label = { Text(tab.label) },
        alwaysShowLabel = true,
        colors = NavigationRailItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}
