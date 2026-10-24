package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import java.util.concurrent.*
import kotlin.concurrent.thread

/** Actual Session hook and release; stock socket/Binder/platform remain explicit fixture boundaries. */
internal fun receiverUsabilityScenarios() {
    PanelFixture("usability-owner").use { f ->
        val captured = f.session as CapturedProjection
        val type = f.session.javaClass
        val receive = type.getDeclaredMethod("receiveControl", Frame::class.java).apply { isAccessible = true }
        fun fact(rev: Long, mode: ReceiverUsabilityMode, owner: String = f.ownerEpoch) =
            ReceiverUsability(owner, ReceiverIncarnation("receiver", 1), rev, 4, mode)
        f.blockOff = CountDownLatch(1)
        val stock = thread { f.off() }
        check(f.offEntered.await(2, TimeUnit.SECONDS))
        val published = CountDownLatch(1)
        val consumer = thread {
            receive.invoke(f.session, Frame(Wire.RECEIVER_USABILITY, payload = fact(1, ReceiverUsabilityMode.LOCAL_PANEL).bytes()))
            published.countDown()
        }
        check(published.await(1, TimeUnit.SECONDS)) { "Actual explicit consumer waited on blocked stock/control lock" }
        check(captured.receiverUsability().usable && f.blockOff!!.count == 1L)
        receive.invoke(f.session, Frame(Wire.RECEIVER_USABILITY, payload = fact(3, ReceiverUsabilityMode.NONE).bytes()))
        receive.invoke(f.session, Frame(Wire.RECEIVER_USABILITY, payload = fact(2, ReceiverUsabilityMode.PROJECTED).bytes()))
        receive.invoke(f.session, Frame(Wire.RECEIVER_USABILITY, payload = fact(9, ReceiverUsabilityMode.PROJECTED, "other-owner").bytes()))
        check(!captured.receiverUsability().usable && captured.receiverUsability().fact!!.revision == 3L)
        val ended = CountDownLatch(1)
        val closing = thread { try { f.close(SessionStopReason.HOST_STOP) } finally { ended.countDown() } }
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (!captured.receiverUsability().retired && System.nanoTime() < until) Thread.yield()
        check(captured.receiverUsability().retired && f.blockOff!!.count == 1L)
        f.blockOff!!.countDown(); stock.join(2000); consumer.join(1000); check(ended.await(3, TimeUnit.SECONDS)); closing.join(1000)
        println("PASS actual Session publishes/reads/retire usability before blocked stock/control release; stale/wrong-owner facts rejected")
    }
    PanelFixture("reader-order-owner").use { f ->
        val receive = f.session.javaClass.getDeclaredMethod("receiveControl", Frame::class.java).apply { isAccessible = true }
        f.blockOff = CountDownLatch(1)
        val stock = thread { f.off() }; check(f.offEntered.await(2, TimeUnit.SECONDS))
        val parsed = CountDownLatch(1)
        val reader = thread {
            receive.invoke(f.session, Frame(Wire.HEAD_POINTER, payload = HeadPointerState(1,1,false,true,0f,0f,null,null).bytes()))
            receive.invoke(f.session, Frame(Wire.RECEIVER_USABILITY, payload = ReceiverUsability(f.ownerEpoch,
                ReceiverIncarnation("reader",1),1,1,ReceiverUsabilityMode.NONE).bytes()))
            parsed.countDown()
        }
        check(!parsed.await(100, TimeUnit.MILLISECONDS))
        check((f.session as CapturedProjection).receiverUsability().fact == null)
        println("READER_BOUNDARY: actual single control consumer cannot parse a later mode while a prior pointer record waits on stock/control; network arrival is not publication")
        f.blockOff!!.countDown(); stock.join(2000); check(parsed.await(2, TimeUnit.SECONDS)); reader.join(1000)
    }
}
