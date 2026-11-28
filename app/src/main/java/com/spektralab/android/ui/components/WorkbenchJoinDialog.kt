package com.spektralab.android.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * 单击图库里一张不在当前工作台上的照片时先问一句：追加还是新建。
 * 工作台空着、或者这张本来就在工作台上，都不会走到这里（那时直接打开 / 只切换）。
 * request = 待处理的照片 + 问话那一刻工作台上的张数；为 null 时不显示。
 */
@Composable
fun WorkbenchJoinDialog(
    request: Pair<Uri, Int>?,
    onDismiss: () -> Unit,
    onAppend: () -> Unit,
    onNewWorkbench: () -> Unit,
) {
    val (_, count) = request ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("这张照片不在当前工作台上") },
        text = {
            Text(
                "工作台上已有 $count 张。\n\n" +
                    "「追加至工作台」把这张加到末尾，原来那些一张不动；\n" +
                    "「新建工作台」只留这一张——原来那 $count 张仍留在图库里，参数不会丢，" +
                    "只是当前工作台被这一张换掉。"
            )
        },
        confirmButton = { TextButton(onClick = onAppend) { Text("追加至工作台") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onNewWorkbench) { Text("新建工作台") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
