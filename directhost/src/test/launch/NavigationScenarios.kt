package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun Fixture.receiverEvents(): List<ReceiverInput> {
    val input = DataInputStream(ByteArrayInputStream(peer.sink.toByteArray()))
    val events = mutableListOf<ReceiverInput>()
    while (input.available() > 0) {
        val frame = Wire.readFrame(input)
        if (frame.channel == Wire.RECEIVER_INPUT) events.add(ReceiverInput.from(frame.payload))
    }
    return events
}

fun main() {
    val size = Geometry(480, 640)
    Fixture().use { f ->
        f.fusionGeometry(); f.pointer()
        var component = "example.app/.Main"
        val a = object : Adapter() {
            override fun foregroundComponent(displayId: Int): String {
                check(displayId == 47)
                return component
            }
        }
        val launches = f.adapter(a)
        launches.initialize(47); launches.snapshot(true)
        fun hold(id: Long) {
            f.receive(Frame(Wire.TOUCH, payload = Touch(Touch.Phase.DOWN, id, Tap(70, 80, size)).bytes()))
            check((f.get("inputControls") as PhoneTouchDispatcher).holding)
        }
        fun home() {
            f.receive(Frame(Wire.NAVIGATE, payload = Navigate(Navigation.CONTEXTUAL_HOME, size).bytes()))
            launches.snapshot(true)
            check(!(f.get("inputControls") as PhoneTouchDispatcher).holding)
            check(!f.peer.released && !f.control.released && !f.video.released && f.child.alive)
        }
        hold(71); home()
        check(a.calls == listOf(47, 47) && f.receiverEvents().isEmpty())
        component = ProjectionLaunchCommands.HOME_COMPONENT
        hold(72); home()
        check(a.calls == listOf(47, 47))
        check(f.receiverEvents() == listOf(ReceiverInput(ReceiverInputKind.PANEL, 1)))
        println("PASS actual Session v8 contextual Home app->grid->PANEL on source display 47; cancels original touch and retains transport/workspace")
    }
    for (replacement in listOf("revision", "incarnation", "mode", "closed")) Fixture().use { f ->
        f.fusionGeometry(); f.pointer()
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val a = object : Adapter() {
            override fun foregroundComponent(displayId: Int): String {
                entered.countDown()
                while (finish.count > 0) try { finish.await() } catch (_: InterruptedException) { }
                return ProjectionLaunchCommands.HOME_COMPONENT
            }
            override fun close() { super.close(); finish.countDown() }
        }
        val launches = f.adapter(a)
        launches.initialize(47); launches.snapshot(true)
        try {
            f.receive(Frame(Wire.NAVIGATE, payload = Navigate(Navigation.CONTEXTUAL_HOME, size).bytes()))
            check(entered.await(2, TimeUnit.SECONDS))
            val original = (f.session as CapturedProjection).receiverUsability().fact!!
            when (replacement) {
                "revision" -> f.pointer(revision = 2, sequence = 2)
                "incarnation" -> f.receive(Frame(Wire.RECEIVER_USABILITY, payload = original.copy(
                    incarnation = ReceiverIncarnation("replacement", 2), revision = original.revision + 1).bytes()))
                "mode" -> f.pointer(projected = false, revision = 1, sequence = 2)
                "closed" -> f.closeSession()
            }
            finish.countDown()
            if (replacement != "closed") launches.snapshot(true)
            check(f.receiverEvents().isEmpty())
            println("PASS actual Session delayed grid receipt cannot reach $replacement context")
        } finally { finish.countDown() }
    }
    println("PASS 5 actual Session navigation scenarios; host API doubles, no device proof")
}
