package com.code2hack.eyeprojector.directhost.activation.snapshot

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import android.os.MemoryBoundary

/** Primitive JNI boundary double. Actual publication/region/lease algorithms are production Kotlin. */
internal object NativeSnapshotAccess {
 private data class Cell(val buffer:ByteBuffer,val offset:Int)
 private val memory=Any()
 private val next=AtomicLong(8);private val cells=ConcurrentHashMap<Long,Cell>()
 @Volatile var beforePrepare:(()->Unit)?=null
 val prepareThreads=CopyOnWriteArrayList<String>();val reads=AtomicLong();val writes=AtomicLong()
 fun prepare(){beforePrepare?.invoke();prepareThreads.add(Thread.currentThread().name)}
 fun initializeCell(buffer:ByteBuffer,offset:Int):Long=bindCell(buffer,offset).also{releaseCell(it,0)}
 fun bindCell(buffer:ByteBuffer,offset:Int):Long{
  require(buffer.isDirect && offset>=0 && offset<=buffer.capacity()-8)
  return next.getAndAdd(8).also{cells[it]=Cell(buffer,offset)}
 }
 fun acquireCell(address:Long):Long{
  val c=cells[address]!!;MemoryBoundary.requireMapped(c.buffer);reads.incrementAndGet()
  return synchronized(memory){c.buffer.duplicate().order(ByteOrder.nativeOrder()).getLong(c.offset)}
 }
 fun releaseCell(address:Long,word:Long){
  val c=cells[address]!!;MemoryBoundary.requireMapped(c.buffer);writes.incrementAndGet()
  synchronized(memory){c.buffer.duplicate().order(ByteOrder.nativeOrder()).putLong(c.offset,word)}
 }
}
