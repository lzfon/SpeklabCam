package com.spektralab.android.data.preview

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.Half
import com.spektralab.android.core.color.OutputColorSpaces
import com.spektralab.android.core.model.DetailViewport
import com.spektralab.android.data.export.EphemeralExportFile
import com.spektralab.android.engine.Rgba16Source
import com.spektralab.android.engine.Grade
import com.spektralab.android.engine.host.DescriptorRgba16Source
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Immutable final U16 frame in unlinked storage; only requested visible tiles live in RAM. */
internal class FullDetailSession private constructor(
    private val storage:EphemeralExportFile,
    private val source:DescriptorRgba16Source,
    val colorSpace:String,
    val stamp:String,
    val budget:Long,
) : Closeable {
    val width get()=source.width
    val height get()=source.height
    private val tiles=LinkedHashMap<DetailViewport.Tile,Bitmap>(16,.75f,true)
    private var closed=false
    val residentBytes:Long @Synchronized get()=tiles.values.sumOf {it.allocationByteCount.toLong()}

    @Synchronized fun preview(longEdge:Int,displaySpace:String,check:()->Unit):Bitmap {
        check(!closed)
        val sized=com.spektralab.android.data.export.SizedRgba16Source.wrap(source,
            com.spektralab.android.core.model.ExportSize(com.spektralab.android.core.model.ExportSize.Kind.LONG_EDGE,longEdge.toDouble()),check)
        return raster(sized,DetailViewport.Tile(0,0,sized.width,sized.height),displaySpace,check)
    }

    @Synchronized
    fun visible(view:DetailViewport,screenWidth:Int,screenHeight:Int,displaySpace:String,check:()->Unit):Map<DetailViewport.Tile,Bitmap> {
        check(!closed);require(displaySpace in listOf("sRGB","Display P3"))
        val wanted=view.tiles(width,height,screenWidth,screenHeight)
        val bytes=wanted.sumOf {it.width.toLong()*it.height*8}
        require(bytes<=budget){"Detail viewport exceeds memory budget; zoom in"}
        // Display space is a bitmap tag; source values are transformed once into that space.
        val key="$colorSpace->$displaySpace"
        if(tag!=key){tiles.clear();tag=key}
        tiles.keys.filter{it !in wanted}.forEach{tiles.remove(it)}
        for(tile in wanted) {
            check()
            if(tile !in tiles)tiles[tile]=make(tile,displaySpace,check)
        }
        check(residentBytes<=budget)
        return wanted.associateWith{tiles.getValue(it)}
    }
    private var tag=""

    private fun make(tile:DetailViewport.Tile,displaySpace:String,check:()->Unit):Bitmap {
        return raster(source,tile,displaySpace,check)
    }

    private fun raster(pixels:Rgba16Source,tile:DetailViewport.Tile,displaySpace:String,check:()->Unit):Bitmap {
        val cs=OutputColorSpaces.named(displaySpace)
        val transform=if(colorSpace==displaySpace)null else android.graphics.ColorSpace.connect(OutputColorSpaces.named(colorSpace),cs)
        val bitmap=Bitmap.createBitmap(tile.width,tile.height,Bitmap.Config.RGBA_F16,false,cs)
        try {
            val buffer=ByteBuffer.allocate(tile.width*tile.height*8).order(ByteOrder.nativeOrder())
            val row=ShortArray(pixels.width*4);val rgb=FloatArray(3)
            for(y in 0 until tile.height) {
                check();pixels.readRows(tile.y+y,1,row)
                for(x in 0 until tile.width) {
                    val at=(tile.x+x)*4
                    for(c in 0..2)rgb[c]=(row[at+c].toInt() and 65535)/65535f
                    transform?.transform(rgb)
                    // Raw storage bits, not arithmetic on half-valued Kotlin Shorts.
                    for(c in 0..2)putHalfBits(buffer,rgb[c])
                    buffer.putShort(0x3c00.toShort())
                }
            }
            buffer.flip();bitmap.copyPixelsFromBuffer(buffer);check();return bitmap
        }catch(error:Throwable){bitmap.recycle();throw error}
    }

    // HalfFloat lint treats ByteBuffer.putShort's fluent return as numeric widening.
    // This is only raw F16 transport, tested against every TIFF sample; no half arithmetic occurs.
    @android.annotation.SuppressLint("HalfFloat")
    private fun putHalfBits(buffer:ByteBuffer,value:Float) {buffer.putShort(Half.toHalf(value))}

    @Synchronized override fun close() {
        if(closed)return
        closed=true
        // Compose may still reference an old frame for one draw. Release references, never recycle a displayed tile.
        tiles.clear();try{source.close()}finally{storage.close()}
    }

    companion object {
        fun create(context:Context,source:Rgba16Source,grade:Grade,colorSpace:String,stamp:String,check:()->Unit,budget:Long=64L*1024*1024,quantize8Bit:Boolean=false):FullDetailSession {
            require(colorSpace in OutputColorSpaces.names && budget in 8L*1024*1024..64L*1024*1024)
            check(context.cacheDir.usableSpace>source.width.toLong()*source.height*8+64L*1024*1024){"Insufficient detail staging space"}
            val storage=EphemeralExportFile.create(context)
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(storage.writer).use {out->
                    val row=ShortArray(source.width*4);val bytes=ByteBuffer.allocate(source.width*8).order(ByteOrder.LITTLE_ENDIAN)
                    val graded=if(grade.usesNativeGrade)FloatArray(source.width*3)else null;val rgb=FloatArray(3)
                    for(y in 0 until source.height){
                        check();source.readRows(y,1,row)
                        if(!source.gradeApplied && !grade.isIdentity) {
                            graded?.let{grade.nativeRows(row,0,source.width,source.height,y,1,it)}
                            for(x in 0 until source.width){
                                if(graded!=null)for(c in 0..2)rgb[c]=graded[x*3+c]
                                else grade.gradeAt(row[x*4],row[x*4+1],row[x*4+2],rgb,x,y,source.width,source.height)
                                for(c in 0..2)row[x*4+c]=(rgb[c]*65535f).roundToInt().coerceIn(0,65535).toShort()
                            }
                        }
                        if(quantize8Bit)for(x in 0 until source.width)for(c in 0..2){val at=x*4+c
                            row[at]=((((row[at].toInt() and 65535)*255+32767)/65535)*257).toShort()}
                        bytes.clear();bytes.asShortBuffer().put(row);out.write(bytes.array())
                    }
                    out.fd.sync()
                }
                check()
                return FullDetailSession(storage,DescriptorRgba16Source(ParcelFileDescriptor.dup(storage.reader.fileDescriptor),source.width,source.height),colorSpace,stamp,budget)
            }catch(error:Throwable){storage.close();throw error}
        }
    }
}
