package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import com.code2hack.eyeprojector.directhost.input.InputWork
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val size = Geometry(480, 640)
private val observation = ManagedProjectionDisplay.SourceObservation(size, size, 160, 0, false)
private val fact = ReceiverUsability("owner", ReceiverIncarnation("receiver", 1), 1, 1, ReceiverUsabilityMode.PROJECTED)
private fun point() = HeadPointerState(1, 1, true, true, 30f, 40f, Fit(0, 0, 480, 640), size)
private fun display() = ManagedProjectionDisplay().apply { settled(observation) }

/** Both real ordinary-input producers reach the actual dispatcher/mapping admission. */
private fun assertTapPaths(flow: LayoutFlow) {
    check(flow.source.withInputMapping(size) { _,_->true }) { "required managed input mapping is absent" }
    check(flow.phoneInput.nativeAction { flow.inputControls.tap(Tap(30, 40, size)) }) { "native tap rejected" }
    flow.phoneInput.pointer(point(), fact)
    val down = PhoneHidEdge(PhoneHidAction.TAP, PhoneHidPhase.DOWN, 1, 96, 304, 100, 100)
    flow.phoneInput.edge(8, down)
    flow.phoneInput.edge(8, down.copy(phase = PhoneHidPhase.UP, eventTimeMs = 140))
    check(flow.taps == 1 && flow.events.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.UP)) {
        "native/Phone HID output did not reach the original mapping: taps=${flow.taps} events=${flow.events}"
    }
}

fun main() {
    var failures = 0
    fun scenario(name: String, body: () -> Unit) {
        try { body(); println("PASS $name") }
        catch (error: Throwable) { failures++; println("FAIL $name: $error"); error.printStackTrace() }
    }
    scenario("deferred initial mapping is restored by unchanged layout") {
        val source = display()
        LayoutFlow(source).use { flow ->
            source.displayChanged.set(true)
            flow.inputControls.geometry(size) // Actual initial binding defers behind the display callback.
            check(!source.withInputMapping(size) { _,_->true })
            flow.layout(observation)
            assertTapPaths(flow)
        }
    }
    scenario("known layout with never-bound input still initializes") {
        LayoutFlow(display()).use { flow ->
            check(!flow.source.withInputMapping(size) { _,_->true })
            flow.layout(observation)
            assertTapPaths(flow)
        }
    }
    scenario("equal-scale request clears then restores actual original mapping") {
        val source = display()
        LayoutFlow(source).use { flow ->
            flow.inputControls.geometry(size)
            check(source.withInputMapping(size) { _,_->true })
            source.resize(1.0) // Actual production resize clears inputMapping even for the same scale.
            check(!source.withInputMapping(size) { _,_->true })
            flow.layout(observation)
            assertTapPaths(flow)
        }
    }
    scenario("ordinary MOVE remains live and UP uses last submitted position") {
        LayoutFlow(display()).use { flow ->
            flow.inputControls.geometry(size)
            check(flow.inputControls.touch(Touch(Touch.Phase.DOWN,40,Tap(10,20,size))))
            check(flow.inputControls.touch(Touch(Touch.Phase.MOVE,40,Tap(100,200,size))))
            flow.layout(observation.copy(rotation=2))
            check(flow.inputControls.touch(Touch(Touch.Phase.MOVE,40,Tap(120,220,size))))
            val move=flow.managed.submitted.last()
            check(flow.inputControls.touch(Touch(Touch.Phase.UP,40,Tap(120,220,size))))
            check(flow.managed.submitted.map{it.action}==listOf(0,2,2,1))
            check(flow.managed.submitted.last().point==move.point && move.point!=flow.managed.submitted.first().point)
        }
    }
    scenario("Click DOWN and UP retain their destination across an intervening layout observation") {
        LayoutFlow(display()).use { flow ->
            flow.inputControls.geometry(size)
            flow.managed.onSubmission={ if(it.action==0)flow.layout(observation.copy(rotation=2)) }
            check(flow.inputControls.tap(Tap(30,40,size)))
            val events=flow.managed.submitted
            check(events.map{it.action}==listOf(0,1))
            check(events[0].point==events[1].point && events[0].down==events[1].down)
        }
    }
    scenario("lost original destination cannot send UP or Cancel to a successor") {
        LayoutFlow(display()).use { flow ->
            flow.inputControls.geometry(size)
            check(flow.inputControls.touch(Touch(Touch.Phase.DOWN,50,Tap(30,40,size))))
            flow.source.originalDisplay=Any()
            val result=runCatching { flow.inputControls.touch(Touch(Touch.Phase.UP,50,Tap(30,40,size))) }
            check(result.isFailure || result.getOrNull()==false)
            check(flow.managed.submitted.map{it.action}==listOf(0))
            flow.inputControls.close()
            check(flow.managed.submitted.map{it.action}==listOf(0))
        }
    }
    check(failures == 0) { "$failures managed-layout regression failures" }
}
