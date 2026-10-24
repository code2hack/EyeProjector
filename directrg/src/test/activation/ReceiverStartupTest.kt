package com.code2hack.eyeprojector.directrg
import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
/** Actual request/welcome/start loop; TLS socket is an explicit controlled stream boundary. */
class ReceiverStartupTest {
 @get:Rule val temporary=TemporaryFolder()
 @After fun reset(){Tls.connector=null}
 private fun fact(r:Long,mode:ReceiverUsabilityMode)=ReceiverUsability("owner",ReceiverIncarnation("startup",1),r,1,mode)
 private fun await(s:ProjectionSession){
  val field=s.javaClass.getDeclaredField("scaleControls").apply{isAccessible=true}
  val lane=field.get(s) as OrderedControlDispatcher
  val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
  while((lane.snapshot().pending>0 || lane.snapshot().inFlight>0) && System.nanoTime()<end)Thread.yield()
  assertEquals(0L,lane.snapshot().pending);assertEquals(0L,lane.snapshot().inFlight)
 }
    @Test fun actualStartWritesRequestBeforeWelcomeAndFlushesLatestModeAfterWelcome() {
        val peer=StartupPeer();Tls.connector={peer}
        val revisions=AtomicLong()
        val session=ProjectionSession(temporary.root,Surface(),{_,_->},{_,_->},nextUsabilityRevision=revisions::incrementAndGet)
        try {
            session.usability(fact(revisions.incrementAndGet(),ReceiverUsabilityMode.NONE));await(session)
            session.start()
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(peer.bytes.size()<9 && System.nanoTime()<deadline)Thread.yield()
            assertEquals(9,peer.bytes.size())
            assertEquals(Wire.STREAM,Wire.readRequest(DataInputStream(peer.bytes.toByteArray().inputStream())))
            session.usability(fact(revisions.incrementAndGet(),ReceiverUsabilityMode.LOCAL_PANEL));await(session)
            assertEquals("No metadata can enter the real request/welcome boundary",9,peer.bytes.size())
            Wire.writeWelcome(DataOutputStream(peer.server))
            val limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            var received:Frame?=null
            while(received==null && System.nanoTime()<limit){
                received=runCatching{Wire.readFrame(DataInputStream(peer.bytes.toByteArray().drop(9).toByteArray().inputStream()))}.getOrNull()
                Thread.yield()
            }
            assertEquals(Wire.RECEIVER_USABILITY,received?.channel)
            assertEquals(ReceiverUsabilityMode.LOCAL_PANEL,ReceiverUsability.from(received!!.payload).mode)
        } finally {session.close();Tls.connector=null}
    }

}
