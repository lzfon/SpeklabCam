package com.spektralab.android.feature.editor

import android.graphics.Bitmap
import androidx.compose.runtime.*
import com.spektralab.android.core.model.DetailViewport
import com.spektralab.android.data.preview.FullDetailSession
import kotlinx.coroutines.*

/** Screen-owned immutable render with latest-request-wins tile publication. */
internal class DetailController(private val scope:CoroutineScope) {
    var opened by mutableStateOf(false);private set
    var loading by mutableStateOf(false);private set
    var error by mutableStateOf<String?>(null);private set
    var session by mutableStateOf<FullDetailSession?>(null);private set
    var view by mutableStateOf(DetailViewport());private set
    data class Frame(val view:DetailViewport,val screenWidth:Int,val screenHeight:Int,val tiles:Map<DetailViewport.Tile,Bitmap>,val displaySpace:String)
    var frame by mutableStateOf<Frame?>(null);private set
    private var revision=0L
    private var tileJob:Job?=null
    private var width=0;private var height=0
    private var display="sRGB"
    fun begin() {close();opened=true;loading=true;error=null}
    fun ready(value:FullDetailSession) {
        if(!opened){scope.launch(NonCancellable+Dispatchers.IO){value.close()};return}
        session=value;loading=false;view=DetailViewport();request()
    }
    fun failed(message:String) {if(opened){loading=false;error=message}}
    fun resize(w:Int,h:Int,displaySpace:String) {
        if(w<=0 || h<=0)return
        if(width==w && height==h && display==displaySpace)return
        width=w;height=h;display=displaySpace;request()
    }
    fun gesture(panX:Float,panY:Float,zoom:Float,x:Float,y:Float) {
        val s=session?:return;if(width<=0 || height<=0)return
        view=view.gesture(s.width,s.height,width,height,panX,panY,zoom,x,y).let{
            if(it.scale<1f)it.copy(scale=1f).bounded(s.width,s.height,width,height)else it
        };request()
    }
    fun scale(value:Float) {val s=session?:return;view=view.copy(scale=value.coerceIn(1f,8f)).bounded(s.width,s.height,width.coerceAtLeast(1),height.coerceAtLeast(1));request()}
    private fun request() {
        val s=session?:return;if(width<=0 || height<=0)return
        val generation=++revision;tileJob?.cancel()
        val viewport=view.bounded(s.width,s.height,width,height);view=viewport
        val w=width;val h=height;val target=display
        tileJob=scope.launch {
            try {
                delay(32)
                val tiles=withContext(Dispatchers.Default){val task=currentCoroutineContext();s.visible(viewport,w,h,target){task.ensureActive()}}
                if(opened && generation==revision && session===s){frame=Frame(viewport,w,h,tiles,target);error=null}
            }catch(cancelled:CancellationException){throw cancelled}
            catch(_:OutOfMemoryError){if(generation==revision)error="细节显示内存不足，请关闭后重试"}
            catch(error:Exception){if(generation==revision)this@DetailController.error=error.message?:"细节加载失败"}
        }
    }
    fun close() {
        revision++;opened=false;loading=false;tileJob?.cancel();tileJob=null;frame=null
        val old=session;session=null
        if(old!=null)scope.launch(NonCancellable+Dispatchers.IO){old.close()}
    }
}
