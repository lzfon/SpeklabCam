package com.spektralab.android.data.export

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Attach an ICC property to the primary HEIF item without moving any encoded media offsets. */
internal object HeifIcc {
    private data class Box(val at:Int,val size:Int,val type:String) {val end get()=at+size}
    private fun int(bytes:ByteArray,at:Int)=ByteBuffer.wrap(bytes,at,4).order(ByteOrder.BIG_ENDIAN).int
    private fun short(bytes:ByteArray,at:Int)=((bytes[at].toInt() and 255)shl 8) or (bytes[at+1].toInt() and 255)
    private fun boxes(bytes:ByteArray,start:Int,end:Int):List<Box> {
        val result=mutableListOf<Box>();var at=start
        while(at<end) {
            require(end-at>=8);val size=int(bytes,at)
            require(size>=8 && size.toLong()+at<=end)
            result+=Box(at,size,String(bytes,at+4,4,Charsets.US_ASCII));at+=size
        }
        require(at==end);return result
    }
    private fun box(type:String,payload:ByteArray):ByteArray=ByteArrayOutputStream().also {bytes->
        DataOutputStream(bytes).apply {writeInt(payload.size+8);write(type.toByteArray(Charsets.US_ASCII));write(payload)}
    }.toByteArray()
    private fun replace(bytes:ByteArray,container:Box,start:Int,changes:Map<Int,ByteArray>):ByteArray =
        box(container.type,bytes.copyOfRange(container.at+8,start)+boxes(bytes,start,container.end).fold(ByteArray(0)) {all,child->
            all+(changes[child.at]?:bytes.copyOfRange(child.at,child.end))
        })

    fun attach(file:File,icc:ByteArray) {
        require(icc.size in 128..1024*1024 && String(icc,36,4,Charsets.US_ASCII)=="acsp")
        RandomAccessFile(file,"rw").use {io->
            val originalLength=io.length();var at=0L;var metaOffset=-1L;var meta:ByteArray?=null
            while(at<originalLength) {
                require(originalLength-at>=8);io.seek(at)
                var size=io.readInt().toLong() and 0xffffffffL
                val type=ByteArray(4).also(io::readFully).toString(Charsets.US_ASCII)
                if(size==1L)size=io.readLong()
                else if(size==0L)size=originalLength-at
                require(size>=8 && size<=originalLength-at)
                if(type=="meta") {
                    require(meta==null && size in 12..4L*1024*1024)
                    metaOffset=at;meta=ByteArray(size.toInt());io.seek(at);io.readFully(meta)
                }
                at+=size
            }
            val bytes=requireNotNull(meta){"HEIF metadata missing"}
            require(int(bytes,0)==bytes.size && int(bytes,8)==0){"Unsupported HEIF metadata version"}
            val children=boxes(bytes,12,bytes.size)
            val primary=children.single {it.type=="pitm"}
            val primaryVersion=bytes[primary.at+8].toInt() and 255
            require(primaryVersion in 0..1)
            val primaryId=if(primaryVersion==0)short(bytes,primary.at+12).toLong() else int(bytes,primary.at+12).toLong() and 0xffffffffL
            // File offsets (method0) stay unchanged. Method1 remains relative to the copied idat.
            val locations=children.single{it.type=="iloc"}
            val lv=bytes[locations.at+8].toInt() and 255;require(lv in 0..2)
            val iprp=children.single{it.type=="iprp"};val properties=boxes(bytes,iprp.at+8,iprp.end)
            val ipco=properties.single{it.type=="ipco"};val existing=boxes(bytes,ipco.at+8,ipco.end)
            require(existing.none{it.type=="colr"}){"Unexpected existing HEIF colour property"}
            val index=existing.size+1;require(index<=32767)
            val colour=box("colr","prof".toByteArray(Charsets.US_ASCII)+icc)
            val newIpco=box("ipco",bytes.copyOfRange(ipco.at+8,ipco.end)+colour)
            val ipma=properties.single{it.type=="ipma"}
            val version=bytes[ipma.at+8].toInt() and 255;require(version in 0..1)
            val flags=int(bytes,ipma.at+8) and 0xffffff;require(flags and 0xfffffe==0)
            var cursor=ipma.at+12;val entries=int(bytes,cursor);cursor+=4
            require(entries in 1..65535)
            var attached=false
            val associations=ByteArrayOutputStream()
            DataOutputStream(associations).apply {
                writeInt((version shl 24) or 1);writeInt(entries)
                repeat(entries) {
                    val id=if(version==0)short(bytes,cursor).toLong().also{cursor+=2} else
                        (int(bytes,cursor).toLong() and 0xffffffffL).also{cursor+=4}
                    if(version==0)writeShort(id.toInt())else writeInt(id.toInt())
                    val count=bytes[cursor++].toInt() and 255
                    val add=id==primaryId;require(!add || count<255)
                    writeByte(count+if(add)1 else 0)
                    repeat(count) {
                        val value=if(flags and 1!=0)short(bytes,cursor).also{cursor+=2}
                            else (bytes[cursor++].toInt() and 255).let{((it and 128)shl 8) or (it and 127)}
                        require((value and 32767)<=existing.size);writeShort(value)
                    }
                    if(add){writeShort(index);attached=true}
                }
            }
            require(attached && cursor==ipma.end)
            val newIprp=replace(bytes,iprp,iprp.at+8,mapOf(ipco.at to newIpco,ipma.at to box("ipma",associations.toByteArray())))
            val patched=replace(bytes,Box(0,bytes.size,"meta"),12,mapOf(iprp.at to newIprp))
            // Append first, then retire only the old metadata box; mdat bytes/absolute offsets do not move.
            io.seek(originalLength);io.write(patched)
            io.seek(metaOffset+4);io.write("free".toByteArray(Charsets.US_ASCII))
        }
    }
}
