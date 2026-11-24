package com.spektralab.android.feature.smartdevelop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterVintage
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spektralab.android.core.develop.SmartDevelopStyle
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.theme.TheiaShapes

@Composable
internal fun SmartDevelopEntry(vm: EditorViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors=MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().clip(TheiaShapes.control).background(colors.surfaceContainerLow)
        .clickable(role=Role.Button,onClick=onOpen).padding(horizontal=12.dp,vertical=10.dp)
        .semantics(mergeDescendants=true){contentDescription="选择智能显影风格"},
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).background(colors.primaryContainer,TheiaShapes.control),contentAlignment=Alignment.Center){
            Icon(Icons.Rounded.Palette,null,Modifier.size(21.dp),tint=colors.primary)
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text("智能配方 · ${vm.smartRecipe?.name ?: vm.smartStyle.title}",style=MaterialTheme.typography.titleSmall)
            Text(if(vm.smartImportRunning) "准备 ${vm.smartImportCompleted}/${vm.smartImportTotal} 张照片"
                else if(vm.smartAutoEnabled) "导入后逐张分析，自动选片与调色" else "自动处理已关闭，可手动应用",
                style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight,null,Modifier.size(20.dp),tint=colors.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmartDevelopSheet(vm: EditorViewModel, onDismiss: () -> Unit, onOpenRecipes: (() -> Unit)? = null) {
    val colors=MaterialTheme.colorScheme
    val processing=vm.busy || vm.isExporting || vm.isBatchExporting
    val recipes=vm.editPresets.items

    ModalBottomSheet(onDismissRequest=onDismiss,shape=TheiaShapes.sheet,
        sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=colors.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start=20.dp,end=20.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(colors.primaryContainer,TheiaShapes.control),contentAlignment=Alignment.Center){
                    Icon(Icons.Rounded.Palette,null,Modifier.size(23.dp),tint=colors.primary)
                }
                Column(Modifier.weight(1f).padding(start=12.dp)) {
                    Text("智能配方",style=MaterialTheme.typography.titleLarge)
                    Text("内置风格或自己的配方，每张照片单独判断",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                }
                IconButton(onClick=onDismiss){Icon(Icons.Rounded.Close,"关闭智能显影")}
            }
            // 配方选项区固定高度、内部滑动：我的配方再多也只滚这一块，外面的内容不动。
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                .selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 内置三档在这里列；「我的配方」是独立一组，不出现在内置列表里。
                SmartDevelopStyle.entries.filter { it != SmartDevelopStyle.Recipe }.forEach { style ->
                    val selected=vm.smartRecipe==null && vm.smartStyle==style
                    Row(Modifier.fillMaxWidth().clip(TheiaShapes.control)
                        .background(if(selected)colors.surfaceContainerHigh else colors.surfaceContainerLow)
                        .selectable(selected=selected,enabled=!processing,role=Role.RadioButton,onClick={vm.smartRecipe=null;vm.smartStyle=style})
                        .semantics(mergeDescendants=true){contentDescription="显影风格：${style.title}"}
                        .padding(horizontal=12.dp,vertical=12.dp).heightIn(min=48.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        val icon=when(style){SmartDevelopStyle.Natural -> Icons.Rounded.WbSunny
                            SmartDevelopStyle.Cinema -> Icons.Rounded.Theaters
                            SmartDevelopStyle.Vintage -> Icons.Rounded.FilterVintage
                            SmartDevelopStyle.Recipe -> Icons.Rounded.FilterVintage}
                        Icon(icon,null,Modifier.size(24.dp),tint=if(selected)colors.primary else colors.onSurfaceVariant)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(style.title,style=MaterialTheme.typography.titleSmall,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Medium)
                            Text(style.description,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                        }
                        RadioButton(selected=selected,onClick=null,enabled=!processing)
                    }
                }
                // 自己的配方与内置风格并列，同一组单选、同一颗应用按钮，走同一套模板校验。
                if(recipes.isNotEmpty()){
                    Row(Modifier.fillMaxWidth().padding(top=4.dp),verticalAlignment=Alignment.CenterVertically){
                        Text("我的配方",style=MaterialTheme.typography.labelMedium,color=colors.onSurfaceVariant,
                            modifier=Modifier.weight(1f))
                        Text("${recipes.size} 个",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
                    }
                }
                recipes.forEach { preset ->
                    val selected=vm.smartRecipe?.id==preset.id
                    Row(Modifier.fillMaxWidth().clip(TheiaShapes.control)
                        .background(if(selected)colors.surfaceContainerHigh else colors.surfaceContainerLow)
                        .selectable(selected=selected,enabled=!processing,role=Role.RadioButton,
                            onClick={vm.smartRecipe=preset})
                        .semantics(mergeDescendants=true){contentDescription="我的配方：${preset.name}"}
                        .padding(horizontal=12.dp,vertical=12.dp).heightIn(min=48.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(com.spektralab.android.R.drawable.ic_spk_recipe),null,Modifier.size(24.dp),
                            tint=if(selected)colors.primary else colors.onSurfaceVariant)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(preset.name,style=MaterialTheme.typography.titleSmall,
                                fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Medium)
                            Text(preset.note.ifBlank{"保存的 ${preset.template.groups.size} 组参数，可原样套用"},
                                style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant,maxLines=2,
                                overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        RadioButton(selected=selected,onClick=null,enabled=!processing)
                    }
                }
                if(onOpenRecipes!=null){
                    Row(Modifier.fillMaxWidth().clip(TheiaShapes.control)
                        .clickable(role=Role.Button,onClick={onOpenRecipes()})
                        .padding(horizontal=12.dp,vertical=10.dp).heightIn(min=44.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Icon(painterResource(com.spektralab.android.R.drawable.ic_spk_recipe),null,Modifier.size(20.dp),
                            tint=colors.onSurfaceVariant)
                        Column(Modifier.weight(1f)){
                            Text("增加我的配方",style=MaterialTheme.typography.bodyMedium)
                            Text("把当前调整存成自己的配方，之后和这里一起套用",style=MaterialTheme.typography.labelSmall,
                                color=colors.onSurfaceVariant)
                        }
                        Text("›",style=MaterialTheme.typography.titleMedium,color=colors.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min=56.dp)
                .toggleable(vm.smartAutoEnabled,enabled=!processing,role=Role.Switch,onValueChange={vm.smartAutoEnabled=it})
                .semantics(mergeDescendants=true){contentDescription="导入后自动显影"},verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end=12.dp)) {
                    Text("导入后自动显影",style=MaterialTheme.typography.bodyMedium)
                    Text("已有编辑照常恢复，不会被覆盖",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
                }
                Switch(vm.smartAutoEnabled,onCheckedChange=null,enabled=!processing)
            }
            if(vm.smartImportRunning) {
                LinearProgressIndicator(progress={vm.smartImportCompleted.toFloat()/vm.smartImportTotal.coerceAtLeast(1)},modifier=Modifier.fillMaxWidth())
                Text("正在准备 ${vm.smartImportCompleted}/${vm.smartImportTotal} 张照片",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
            }
            if(vm.params.smartDevelopStyle.isNotEmpty() && vm.hasPhoto) {
                HorizontalDivider(color=colors.outlineVariant.copy(alpha=.55f))
                // 这张照片是用哪份配方做的，就显示那份配方的名字与描述；
                // 只有内置风格时才是内置的名字。
                val style=SmartDevelopStyle.from(vm.params.smartDevelopStyle)
                val recipeName=vm.params.smartDevelopRecipe
                val recipeNote=recipes.firstOrNull{it.name==recipeName}?.note.orEmpty()
                if(style==SmartDevelopStyle.Recipe && recipeName.isNotBlank()) {
                    Text("当前照片 · $recipeName",style=MaterialTheme.typography.labelLarge,color=colors.primary)
                    Text(recipeNote.ifBlank{vm.params.smartDevelopSummary.ifBlank{"按这份配方显影，再按画面微调"}},
                        style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                } else {
                    Text("当前照片 · ${style.title}",style=MaterialTheme.typography.labelLarge,color=colors.primary)
                    Text(vm.params.smartDevelopSummary,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                }
                val film=vm.films.firstOrNull{it.id==vm.params.filmId}?.name.orEmpty()
                val paper=vm.papers.firstOrNull{it.id==vm.params.paperId}?.name.orEmpty()
                Text("$film / $paper",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
            }
            if(vm.smartMessage.isNotEmpty())Text(vm.smartMessage,style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
            Text("根据亮暗、反差与色彩给出本地建议。选好风格后可继续微调，使用撤销即可恢复。",
                style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
            if(vm.hasPhoto)Button(onClick={
                    val chosen=vm.smartRecipe
                    if(chosen!=null) vm.applyEditTemplate(chosen.template, chosen.name, chosen.note) else vm.applySmartDevelop()
                    onDismiss()
                },enabled=!processing,
                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=TheiaShapes.control) {
                Icon(Icons.Rounded.Palette,null,Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if(vm.smartRecipe==null) "应用到当前照片" else "应用我的配方到当前照片")
            }
            else Text("选择用于接下来导入的照片",style=MaterialTheme.typography.labelMedium,color=colors.primary)
        }
    }
}
