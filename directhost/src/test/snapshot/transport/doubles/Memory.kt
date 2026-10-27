package android.os

import java.nio.ByteBuffer
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

/** SharedMemory/FD/map boundary only: shared direct Java storage, not a real kernel RO mapping. */
object MemoryBoundary {
 var beforeDuplicate:(()->Unit)?=null
 val closedHandles=AtomicInteger();val maps=AtomicInteger();val unmaps=AtomicInteger()
 private val live=Collections.synchronizedMap(IdentityHashMap<ByteBuffer,Boolean>())
 fun mapped(buffer:ByteBuffer):ByteBuffer{live[buffer]=true;maps.incrementAndGet();return buffer}
 fun unmap(buffer:ByteBuffer){check(live.remove(buffer)==true){"Double or unknown boundary unmap"};unmaps.incrementAndGet()}
 fun requireMapped(buffer:ByteBuffer){check(live.containsKey(buffer)){"Native boundary read after unmap"}}
}
class SharedMemory private constructor(private val data:ByteBuffer):Parcelable {
 private var closed=false;private var readOnly=false
 fun mapReadWrite():ByteBuffer{check(!closed && !readOnly);return MemoryBoundary.mapped(data.duplicate())}
 fun mapReadOnly():ByteBuffer{check(!closed);return MemoryBoundary.mapped(data.asReadOnlyBuffer())}
 fun setProtect(value:Int):Boolean{check(!closed);readOnly=true;return true}
 fun close(){if(!closed){closed=true;MemoryBoundary.closedHandles.incrementAndGet()}}
 override fun describeContents()=Parcelable.CONTENTS_FILE_DESCRIPTOR
 override fun writeToParcel(dest:Parcel,flags:Int){check(!closed);MemoryBoundary.beforeDuplicate?.invoke();dest.writeBoundary(data)}
 companion object {
  fun create(name:String,size:Int)=SharedMemory(ByteBuffer.allocateDirect(size))
  fun unmap(buffer:ByteBuffer)=MemoryBoundary.unmap(buffer)
  @JvmField val CREATOR=object:Parcelable.Creator<SharedMemory>{
   override fun createFromParcel(source:Parcel)=SharedMemory(source.readBoundary() as ByteBuffer).also{it.readOnly=true}
   override fun newArray(size:Int)=arrayOfNulls<SharedMemory>(size)
  }
 }
}
