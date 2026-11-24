package com.spektralab.android.feature.editor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.spektralab.android.feature.editor.EditorViewModel
import kotlin.math.roundToInt

/** Full-screen native pixel view; scale is physical screen pixels per finished pixel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailWorkspace(vm:EditorViewModel,modifier:Modifier=Modifier) {
    val detail=vm.detail
    val configuration=LocalConfiguration.current
    val display=if(configuration.isScreenWideColorGamut)"Display P3" else "sRGB"
    var viewportSize by remember {mutableStateOf(IntSize.Zero)}
    LaunchedEffect(viewportSize,display) {detail.resize(viewportSize.width,viewportSize.height,display)}
    BackHandler(onBack=vm::closeFullDetail)
    Scaffold(modifier=modifier,topBar={TopAppBar(title={Text("效果细节检查",style=MaterialTheme.typography.titleMedium)},
        navigationIcon={IconButton(onClick=vm::closeFullDetail){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"关闭效果细节检查")}})},
        bottomBar={Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding().padding(horizontal=16.dp,vertical=6.dp)) {
            Text(detail.session?.let{"${it.width} × ${it.height} · 文件 ${it.colorSpace} → 显示 $display"}?:"正在按预览分辨率渲染成片…",style=MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                for(scale in listOf(1f,2f,4f))TextButton(onClick={detail.scale(scale)},enabled=detail.session!=null) {Text("${(scale*100).toInt()}%")}
            }
            Text("拖动平移 · 双指缩放 · 当前 ${(detail.view.scale*100).roundToInt()}%",style=MaterialTheme.typography.labelSmall)
            Text("显示的是调好色的成片，用于检查后期效果；100% 为一个成片像素对应一个屏幕像素，查看不发布文件。",style=MaterialTheme.typography.labelSmall)
        }}) {padding->
        Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            val frame=detail.frame
            Canvas(Modifier.fillMaxSize().clipToBounds().semantics {contentDescription="效果细节画布"}
                .onSizeChanged{viewportSize=it}
                .pointerInput(detail.session){detectTransformGestures {centroid,pan,zoom,_->detail.gesture(pan.x,pan.y,zoom,centroid.x,centroid.y)}}) {
                if(frame!=null) {
                    val session=detail.session
                    if(session!=null) {
                        val left=size.width/2f-frame.view.centerX*session.width*frame.view.scale
                        val top=size.height/2f-frame.view.centerY*session.height*frame.view.scale
                        for((tile,bitmap)in frame.tiles) {
                            val x=(left+tile.x*frame.view.scale).roundToInt();val y=(top+tile.y*frame.view.scale).roundToInt()
                            val right=(left+(tile.x+tile.width)*frame.view.scale).roundToInt()
                            val bottom=(top+(tile.y+tile.height)*frame.view.scale).roundToInt()
                            drawImage(bitmap.asImageBitmap(),dstOffset=IntOffset(x,y),
                                dstSize=IntSize(right-x,bottom-y),filterQuality=FilterQuality.None)
                        }
                    }
                }
            }
            if(detail.loading)Column(Modifier.fillMaxWidth().padding(24.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(vm.renderStage,Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodyMedium)
                TextButton(onClick=vm::closeFullDetail){Text("取消")}
            }
            detail.error?.let{message->Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(message,color=MaterialTheme.colorScheme.error)
                TextButton(onClick=vm::closeFullDetail){Text("关闭并返回编辑")}
            }}
        }
    }
}
