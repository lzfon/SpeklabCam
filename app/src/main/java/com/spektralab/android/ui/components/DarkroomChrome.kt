package com.spektralab.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.android.R
import com.spektralab.android.ui.theme.TheiaShapes
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*

internal object DarkroomChrome {
    val navigation = Color(0xFF222222)
    val selected = Color(0xFF1B3A27)
}

@Composable
internal fun DarkroomTopBar(
    title: String,
    parameterView: Boolean,
    exportView: Boolean,
    enabled: Boolean,
    onBack: () -> Unit,
    onHelp: () -> Unit,
    onOpenTasks: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpenAbout: (() -> Unit)? = null,
) {
    var gearMenuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background) {
        // 编辑页顶栏（含「胶片暗房」参数态与普通编辑态）只占原来一半高，字号/图标同步缩，保证不出栏。
        // 导出页和胶片暗房共用一套栏高与字号（26dp 紧凑栏），不再单独放大。
        val barMinHeight = 40.dp
        val buttonSize = 32.dp
        val iconSize = 16.dp
        Box(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = barMinHeight)) {
        Row(Modifier.fillMaxWidth().heightIn(min = barMinHeight).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = enabled, modifier = Modifier.size(buttonSize)) {
                Icon(painterResource(R.drawable.ic_darkroom_arrow_left),
                    if (exportView) "返回暗房" else if (parameterView) "返回大图预览" else "返回首页",
                    modifier = Modifier.size(iconSize))
            }
            Box(Modifier.weight(1f)) {
                // 标题：点击等同返回上级，点击框只覆盖文字本身（不铺满整栏）。
                Text(title,
                    modifier = Modifier.heightIn(min = 24.dp)
                        .clickable(enabled = enabled, role = Role.Button, onClick = onBack)
                        .semantics { contentDescription = "返回上级" }
                        .wrapContentHeight(Alignment.CenterVertically),
                    fontSize = if (parameterView || exportView) 14.sp else 13.sp,
                    fontWeight = if (parameterView || exportView) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                exportView -> TextButton(onClick = onHelp, enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("帮助", fontSize = 11.sp) }
                // 冲印 / 调色：右上角齿轮点开与首页同一层级的菜单
                // ——中断的任务 / 设置 / 关于 / 参数说明（参数说明从设置抽屉里提到这一层）。
                parameterView -> Box {
                    IconButton(onClick = { gearMenuOpen = true }, enabled = enabled,
                        modifier = Modifier.size(buttonSize).semantics { contentDescription = "菜单" }) {
                        Icon(Icons.Rounded.Settings, null, Modifier.size(iconSize + 2.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(expanded = gearMenuOpen, onDismissRequest = { gearMenuOpen = false }) {
                        if (onOpenTasks != null) DropdownMenuItem(text = { Text("中断的任务") },
                            onClick = { gearMenuOpen = false; onOpenTasks() })
                        if (onOpenSettings != null) DropdownMenuItem(text = { Text("设置") },
                            onClick = { gearMenuOpen = false; onOpenSettings() })
                        if (onOpenAbout != null) DropdownMenuItem(text = { Text("关于") },
                            onClick = { gearMenuOpen = false; onOpenAbout() })
                        DropdownMenuItem(text = { Text("参数说明") },
                            onClick = { gearMenuOpen = false; onHelp() })
                    }
                }
                // 撤销 / 重做 / 前后对比已整体移除（撤销重做在参数行控制条里，前后对比在预览右上角）。
                else -> Spacer(Modifier.width(8.dp))
            }
        }
        // 顶部下拉菜单（●●●）已整体移除：裁剪/重置/编辑配方/复制配方都放在参数行的控制条里。
        }
    }
}

@Composable
internal fun DarkroomBottomBar(
    labels: List<String>,
    @androidx.annotation.DrawableRes icons: List<Int>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val navigationHeight = 64.dp + 24.dp * (LocalDensity.current.fontScale - 1f).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f), thickness = .5.dp)
        Row(Modifier.fillMaxWidth().height(navigationHeight).background(DarkroomChrome.navigation).selectableGroup()) {
            labels.forEachIndexed { index, label ->
                val active = selected == index
                val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.weight(1f).fillMaxHeight()
                    .selectable(selected = active, enabled = enabled, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(48.dp, 28.dp).background(
                        if (active) DarkroomChrome.selected else Color.Transparent, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(painterResource(icons[index]), null, tint = tint, modifier = Modifier.size(19.dp))
                    }
                    Text(label, color = tint, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun DarkroomChoiceStrip(options: List<String>, selected: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(DarkroomChrome.navigation, TheiaShapes.control).selectableGroup()) {
        options.forEach { option ->
            val active = option == selected
            TextButton(onClick = { onSelect(option) }, enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { this.selected = active },
                shape = TheiaShapes.compact, contentPadding = PaddingValues(4.dp)) {
              Box(Modifier.fillMaxWidth().heightIn(min = 40.dp)
                .background(if (active) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent, TheiaShapes.compact)
                .padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(option, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
            }
        }
    }
}
