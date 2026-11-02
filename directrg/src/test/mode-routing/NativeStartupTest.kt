package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.content.ComponentName
import android.net.LocalServerSocket
import android.net.LocalSocket
import com.code2hack.eyeprojector.protocol.Touch
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Verbatim actual startup/lifecycle callers plus the full native reader adapter; host socket boundary. */
class NativeStartupTest {
    private fun await(message:String,test:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
        while(!test() && System.nanoTime()<end) Thread.yield()
        assertTrue(message,test())
    }
    private fun bind(ui:ActivityNativeCaller,owner:ReceiverSessionOwner) =
        ui.onServiceConnected(ComponentName(),ReceiverSessionService.LocalBinder(owner))
    private fun resume(ui:ActivityNativeCaller) { ui.onWindowFocusChanged(true);ui.onResume() }
    private fun packet(socket:LocalSocket,id:Long) {
        socket.send("FUNCTION_SOURCE\nFUNCTION_DOWN $id 1000\nFUNCTION_UP $id 1010\nDOWN $id\nUP $id\n")
    }
    private fun assertStartedAndDelivered(ui:ActivityNativeCaller,owner:ReceiverSessionOwner,context:Context) {
        assertEquals(1,context.receivers.size)
        val server=LocalServerSocket.servers[NativePadReader.SOCKET_NAME] ?: error("Actual startup did not create listener")
        val socket=server.connect()
        val session=owner.session!!
        packet(socket,1)
        await("Function packet reaches the selected retained receiver") {session.cycles.get()==1}
        await("ordinary raw hold has both terminals") {ui.touches.size==2}
        assertEquals(listOf(Touch.Phase.DOWN,Touch.Phase.UP),ui.touches.map {it.phase})
    }
    @Test fun actualBindBeforeResumeStartsTheRetainedListenerAndSelectedFunction() {
        val context=Context();val owner=ReceiverSessionOwner(context);val ui=ActivityNativeCaller()
        try {bind(ui,owner);resume(ui);assertStartedAndDelivered(ui,owner,context)} finally {ui.onPause();ui.onDestroy();owner.close()}
        assertTrue(context.receivers.isEmpty());assertTrue(LocalServerSocket.servers.isEmpty())
    }
    @Test fun actualBindAfterResumeAndFocusNeedsNoFurtherLifecycleEvent() {
        val context=Context();val owner=ReceiverSessionOwner(context);val ui=ActivityNativeCaller()
        try {
            resume(ui);assertTrue(context.receivers.isEmpty());assertTrue(LocalServerSocket.servers.isEmpty())
            bind(ui,owner);assertStartedAndDelivered(ui,owner,context)
        } finally {ui.onPause();ui.onDestroy();owner.close()}
        assertTrue(context.receivers.isEmpty());assertTrue(LocalServerSocket.servers.isEmpty())
    }
    @Test fun actualDetachRecreationAndLateOldDestructorKeepOriginalListenerAndRebindRawPad() {
        val context=Context();val owner=ReceiverSessionOwner(context);val first=ActivityNativeCaller();val next=ActivityNativeCaller()
        try {
            resume(first);bind(first,owner)
            val retained=first.selectedPad();val function=first.selectedFunction()
            val server=LocalServerSocket.servers[NativePadReader.SOCKET_NAME]!!;val socket=server.connect()
            packet(socket,1);await("first raw contact") {first.touches.size==2}
            first.onPause()
            assertSame(server,LocalServerSocket.servers[NativePadReader.SOCKET_NAME]);assertFalse(socket.closed)
            resume(next);bind(next,owner)
            assertSame(retained,next.selectedPad());assertSame(function,next.selectedFunction())
            assertSame(server,LocalServerSocket.servers[NativePadReader.SOCKET_NAME]);assertEquals(1,context.receivers.size)
            first.onDestroy()
            packet(socket,2)
            await("rebound view receives raw pad contact") {next.touches.size==2}
            await("retained Function receives next packet") {owner.session!!.cycles.get()==2}
            assertEquals(2,first.touches.size);assertFalse(socket.closed)
            assertEquals(listOf(Touch.Phase.DOWN,Touch.Phase.UP),next.touches.map {it.phase})
        } finally {next.onPause();next.onDestroy();owner.close()}
        assertTrue(context.receivers.isEmpty());assertTrue(LocalServerSocket.servers.isEmpty())
    }
}
