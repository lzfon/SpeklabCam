package com.spektralab.android.feature.editor.crop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.spektralab.android.core.geometry.computeCenteredCrop
import com.spektralab.android.core.geometry.cropAspectRatio
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.components.OptionsDropdown

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun CropWorkspace(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val source = vm.cropWorkspaceBitmap ?: vm.sourceBitmap ?: return
    Column(modifier.background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { vm.cancelCrop() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("取消")
            }
            Text(if (vm.croppingFinished) "成片裁切" else "裁剪照片", Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = { vm.finishCrop() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("完成裁剪", color = MaterialTheme.colorScheme.primary)
            }
        }
        val selection = vm.cropSelection
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color.Black)
                .semantics { contentDescription = "照片预览" },
            contentAlignment = Alignment.Center
        ) {
            CropOverlay(
                bitmap = source,
                cropX = selection[0], cropY = selection[1],
                cropW = selection[2], cropH = selection[3],
                onCropChange = vm::updateCropSelection,
                modifier = Modifier.fillMaxSize(),
                lockedAspect = cropAspectRatio(vm.cropAspect),
            )
        }
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OptionsDropdown(
                "比例", listOf("自定义", "原始", "1:1", "4:3", "3:2", "16:9", "2.39:1", "1.85:1"), vm.cropAspect,
                Modifier.weight(1f)
            ) { aspect ->
                val crop = if (aspect == "自定义") vm.cropSelection
                    else computeCenteredCrop(aspect, source.width, source.height)
                vm.cropAspect = aspect
                vm.updateCropSelection(crop[0], crop[1], crop[2], crop[3])
            }
            TextButton(onClick = { vm.resetCropSelection() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("重置")
            }
        }
        Text("拖动框内移动选区，拖动四角调整大小", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
