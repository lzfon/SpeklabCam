package com.spektralab.android.core.model

import kotlin.math.ceil
import kotlin.math.floor

/** Screen pixels per original finished-image pixel, independent of density or preview size. */
internal data class DetailViewport(val scale:Float=1f,val centerX:Float=.5f,val centerY:Float=.5f) {
    init {require(scale.isFinite() && scale>0 && centerX.isFinite() && centerY.isFinite())}
    data class Tile(val x:Int,val y:Int,val width:Int,val height:Int)
    fun bounded(width:Int,height:Int,screenWidth:Int,screenHeight:Int):DetailViewport {
        require(width>0 && height>0 && screenWidth>0 && screenHeight>0)
        fun center(value:Float,frame:Int,screen:Int):Float {
            val half=screen/(2f*scale*frame)
            return if(half>=.5f).5f else value.coerceIn(half,1f-half)
        }
        return copy(centerX=center(centerX,width,screenWidth),centerY=center(centerY,height,screenHeight))
    }
    fun tiles(width:Int,height:Int,screenWidth:Int,screenHeight:Int,tileSize:Int=512):List<Tile> {
        require(tileSize in 64..1024)
        val view=bounded(width,height,screenWidth,screenHeight)
        val left=(view.centerX*width-screenWidth/(2f*scale)).coerceAtLeast(0f)
        val top=(view.centerY*height-screenHeight/(2f*scale)).coerceAtLeast(0f)
        val right=(view.centerX*width+screenWidth/(2f*scale)).coerceAtMost(width.toFloat())
        val bottom=(view.centerY*height+screenHeight/(2f*scale)).coerceAtMost(height.toFloat())
        val result=mutableListOf<Tile>()
        for(y in floor(top/tileSize).toInt() until ceil(bottom/tileSize).toInt())
            for(x in floor(left/tileSize).toInt() until ceil(right/tileSize).toInt())
                result+=Tile(x*tileSize,y*tileSize,minOf(tileSize,width-x*tileSize),minOf(tileSize,height-y*tileSize))
        return result
    }
    fun gesture(width:Int,height:Int,screenWidth:Int,screenHeight:Int,panX:Float,panY:Float,zoom:Float,anchorX:Float,anchorY:Float):DetailViewport {
        require(listOf(panX,panY,zoom,anchorX,anchorY).all(Float::isFinite) && zoom>0)
        val next=(scale*zoom).coerceIn(.25f,8f)
        val dx=anchorX-screenWidth/2f;val dy=anchorY-screenHeight/2f
        return copy(scale=next,centerX=centerX+(dx/scale-dx/next-panX/next)/width,
            centerY=centerY+(dy/scale-dy/next-panY/next)/height).bounded(width,height,screenWidth,screenHeight)
    }
}
