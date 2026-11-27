package com.spektralab.android.feature.editor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.spektralab.android.EditorTab
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.components.DarkroomChrome
import com.spektralab.android.ui.theme.TheiaShapes

/**
 * 弹出节拍：每一级都缓慢弹出（420ms、FastOutSlowIn），收起利落（300ms）。
 * 与手机版 Lightroom 的「一级页签 → 二级药丸 → 三级参数」三段动效对齐。
 */
internal val MenuEnter = expandVertically(tween(420, easing = FastOutSlowInEasing)) +
    fadeIn(tween(320, easing = FastOutSlowInEasing)) +
    slideInVertically(tween(420, easing = FastOutSlowInEasing)) { it / 9 }
internal val MenuExit = shrinkVertically(tween(300, easing = FastOutSlowInEasing)) +
    fadeOut(tween(150)) + slideOutVertically(tween(220)) { it / 14 }

/**
 * 手机竖屏的菜单板：二级药丸固定在顶部，三级参数在自己的空间里滚动。
 *
 * 两个开关都由 [MenuBoardState] 管：
 *  - [MenuBoardState.collapsed]：一级页签再点一次时整块折叠；
 *  - [MenuBoardState.openKey]：当前展开的二级菜单。
 */
@Composable
internal fun PhoneMenuBoard(
    vm: EditorViewModel,
    tab: EditorTab,
    state: MenuBoardState,
    modifier: Modifier = Modifier,
) {
    val sections = EditorMenu.visibleSections(tab, vm)
    val open = state.currentSection(tab, vm)
    // 收起动画播完之前内容还得画得出来，否则会先空一帧再往下缩。
    var shown by remember { mutableStateOf<MenuSection?>(null) }
    if (open != null) shown = open
    // 二级、三级是同一个 Column 里的两个兄弟节点，各自缓慢弹出：
    // 外层不套 AnimatedVisibility、也不用 weight —— 之前套了两层动画容器，
    // 三级参数拿不到剩余高度，药丸点开了却不显示内容。Column 会把上一级用掉的高度
    // 减掉后再把剩下的给三级，参数区自己在里面滚。
    Column(Modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = !state.collapsed && sections.isNotEmpty(),
            enter = MenuEnter, exit = MenuExit, label = "menu-level2-pop",
        ) {
            MenuSectionChips(sections, state.openKey, onSection = { state.toggleSection(it) })
        }
        AnimatedVisibility(
            visible = !state.collapsed && open != null,
            enter = MenuEnter, exit = MenuExit, label = "menu-level3-pop",
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                shown?.let { section ->
                    MenuParameterPanel(vm, section)
                }
            }
        }
    }
}

/** 平板 / 横屏：整页滚动里直接摆药丸行与展开的面板，不需要再套一层动画。 */
@Composable
internal fun EditorMenuBoard(
    vm: EditorViewModel,
    tab: EditorTab,
    state: MenuBoardState,
    modifier: Modifier = Modifier,
) {
    val sections = EditorMenu.visibleSections(tab, vm)
    val open = state.currentSection(tab, vm)
    Column(modifier.fillMaxWidth()) {
        if (sections.isEmpty()) return
        MenuSectionChips(sections, if (state.collapsed) null else state.openKey,
            onSection = { state.toggleSection(it) })
        AnimatedVisibility(visible = !state.collapsed && open != null,
            enter = MenuEnter, exit = MenuExit, label = "menu-level3-pop-wide") {
            open?.let { section -> MenuParameterPanel(vm, section) }
        }
    }
}

/** 平板 / 横屏的菜单板：跟竖屏一套药丸与面板，只是整列跟着页面滚，不再自己占高度。 */
@Composable
internal fun WideMenuBoard(
    vm: EditorViewModel,
    tab: EditorTab,
    state: MenuBoardState,
    modifier: Modifier = Modifier,
) {
    val sections = EditorMenu.visibleSections(tab, vm)
    val open = state.currentSection(tab, vm)
    var shown by remember { mutableStateOf<MenuSection?>(null) }
    if (open != null) shown = open
    Column(modifier.fillMaxWidth()) {
        if (sections.isEmpty()) return
        MenuSectionChips(sections, if (state.collapsed) null else state.openKey,
            onSection = { state.toggleSection(it) })
        AnimatedVisibility(visible = !state.collapsed && open != null,
            enter = MenuEnter, exit = MenuExit, label = "menu-level3-wide") {
            shown?.let { section -> MenuParameterPanel(vm, section) }
        }
    }
}

/** 二级菜单那一排药丸：图标在上、短名在下，横向可滑；点开的那颗用主题绿。 */
@Composable
internal fun MenuSectionChips(
    sections: List<MenuSection>,
    openKey: String?,
    modifier: Modifier = Modifier,
    onSection: (MenuSection) -> Unit,
) {
    ChipsScrollFrame(
        modifier = modifier,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
        gap = 4.dp, barInset = 12.dp,
    ) {
        sections.forEach { section -> MenuChip(section, section.key == openKey) { onSection(section) } }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f), thickness = .5.dp)
}

/**
 * 横向药丸行的通用外壳（二级药丸、色彩里的三级子药丸共用）：
 *  - 右侧还有没滑出来的选项时，右端压一条竖线 + 向右箭头（主题绿），提示还能继续滑；
 *  - 手指按住滑动时，底部出现一条主题绿的滚动进度条，松手即消失。
 */
@Composable
internal fun ChipsScrollFrame(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    gap: Dp = 4.dp,
    barInset: Dp = 12.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val state = rememberScrollState()
    var dragging by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(state).padding(contentPadding)
                .pointerInput(Unit) {
                    // 只做「手指按下 / 抬起」的观察者，不消费任何事件：
                    // 滑动本身交给 horizontalScroll。
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        dragging = true
                        while (true) {
                            if (awaitPointerEvent().changes.none { it.pressed }) break
                        }
                        dragging = false
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        val max = state.maxValue
        val more = max > 0 && state.value < max - 4
        if (more) {
            Row(
                Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(Modifier.width(1.5.dp).height(20.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .6f),
                        RoundedCornerShape(1.dp)))
                Icon(Icons.Rounded.ChevronRight, "右边还有更多选项，可继续左滑",
                    Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .85f))
            }
        }
        // 进度条只在按住滑动时出现：宽度 = 可见宽度占比，位置 = 已滑比例。
        if (max > 0) {
            val ratio = (state.viewportSize.toFloat() / (state.viewportSize + max)).coerceIn(.15f, 1f)
            val track = (maxWidth - barInset * 2).coerceAtLeast(0.dp)
            val barWidth = (maxWidth * ratio).coerceAtMost(track)
            val shift = (track - barWidth) * (state.value.toFloat() / max)
            AnimatedVisibility(
                visible = dragging,
                modifier = Modifier.align(Alignment.BottomStart),
                enter = fadeIn(tween(120)), exit = fadeOut(tween(150)),
                label = "chips-scroll-progress",
            ) {
                Box(Modifier.fillMaxWidth().padding(horizontal = barInset, vertical = 3.dp),
                    contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.offset(x = shift).width(barWidth).height(3.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = .85f),
                            RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}

@Composable
private fun MenuChip(section: MenuSection, active: Boolean, onClick: () -> Unit) {
    val tint = animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(220), label = "chip-tint",
    ).value
    Column(
        Modifier.widthIn(min = 64.dp)
            .clip(TheiaShapes.compact)
            .background(if (active) DarkroomChrome.selected else Color.Transparent, TheiaShapes.compact)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics {
                contentDescription = section.chip
                selected = active
                stateDescription = if (active) "已展开" else "已收起"
                role = Role.Tab
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(painterResource(section.icon), null, Modifier.size(20.dp), tint = tint)
        Text(section.chip, color = tint, fontSize = 11.sp, lineHeight = 14.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 三级参数区：不再有「标题 + 折叠箭头」这一行 —— 展开与收回完全由二级药丸控制。
 * 「色彩」展开后仍给一小排子药丸（白平衡 / 混合 / 分级 / 通道 / 平衡），点哪颗就切哪组。
 */
@Composable
internal fun MenuParameterPanel(
    vm: EditorViewModel,
    section: MenuSection,
    modifier: Modifier = Modifier,
) {
    var subKey by rememberSaveable(section.key) { mutableStateOf<String?>(null) }
    val sub = section.subTabs.firstOrNull { it.key == subKey } ?: section.subTabs.firstOrNull()
    Column(modifier.fillMaxWidth()) {
        if (section.subTabs.isNotEmpty()) {
            MenuSubChips(section.subTabs, selectedKey = sub?.key ?: section.subTabs.first().key) { subKey = it.key }
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            if (section.subTabs.isEmpty()) section.content?.invoke(vm) else sub?.content?.invoke(vm)
            Spacer(Modifier.height(12.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
    }
}

/** 「色彩」里面的那一小排：白平衡 / 混合 / 分级 / 通道 / 平衡。 */
@Composable
private fun MenuSubChips(tabs: List<MenuSubTab>, selectedKey: String, onSelect: (MenuSubTab) -> Unit) {
    ChipsScrollFrame(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
        gap = 6.dp, barInset = 16.dp,
    ) {
        tabs.forEach { tab ->
            val active = tab.key == selectedKey
            val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                Modifier.height(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = .14f)
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    .clickable(role = Role.Tab) { onSelect(tab) }
                    .semantics { contentDescription = tab.title; selected = active; role = Role.Tab }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(painterResource(tab.icon), null, Modifier.size(16.dp), tint = tint)
                Text(tab.chip, color = tint, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}
