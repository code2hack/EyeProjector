package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import com.code2hack.eyeprojector.directhost.anchor.execution.*
import com.code2hack.eyeprojector.directhost.anchor.preferences.*
import com.code2hack.eyeprojector.directhost.anchor.target.*
import com.code2hack.eyeprojector.directhost.uiaccess.*
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
    check(flow.source.uiInputSnapshot() != null) { "required managed input mapping is absent" }
    check(flow.phoneInput.nativeAction { flow.inputControls.tap(Tap(30, 40, size)) }) { "native tap rejected" }
    flow.phoneInput.pointer(point(), fact)
    val down = PhoneHidEdge(PhoneHidAction.TAP, PhoneHidPhase.DOWN, 1, 96, 304, 100, 100)
    flow.phoneInput.edge(8, down)
    flow.phoneInput.edge(8, down.copy(phase = PhoneHidPhase.UP, eventTimeMs = 140))
    check(flow.taps == 1 && flow.events.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.UP)) {
        "native/Phone HID output did not reach the original mapping: taps=${flow.taps} events=${flow.events}"
    }
}

private fun hold(flow: LayoutFlow, work: InputWork, beforeSubmit: () -> Unit = {}): Pair<AnchorSettingContext, ManagedUiSource> {
    val mapping = flow.source.uiInputSnapshot() ?: error("No source for Anchor registration/activation")
    val source = ManagedUiSource(ProjectionContext("owner", "token"), "generation", mapping.displayId,
        mapping.displayUniqueId, "app", null, mapping.geometry)
    val window = UiWindowMetadata(63, 4, 1, 0, true, true, UiBounds(0, 0, 480, 640))
    val target = ResolvedAnchorTarget(source, window,
        UiTargetObservation(1, 4, "app", "app:id/hold", "Button", UiBounds(20, 30, 40, 50),
            emptyList(), false, false, true, true))
    val setting = AnchorSettingContext("store", "app", 1)
    check(flow.inputControls.anchor.reconcile(setting))
    val intent = AnchorIntent(AnchorIntentKind.ACTIVATE, AnchorSource.PHONE_HID, 1, 1, AnchorIntentContext(fact, point()))
    val admitted = flow.inputControls.anchor.capture(intent)!!
    beforeSubmit()
    check(flow.inputControls.hold(admitted, setting, target, source, { true }, flow.phoneInput::nativeAction,
        AnchorHoldOutput(work) { _, _ -> }) == AnchorExecutionStatus.HOLD_SUBMITTED)
    return setting to source
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
            check(source.uiInputSnapshot() == null)
            flow.layout(observation)
            assertTapPaths(flow)
        }
    }
    scenario("known layout with never-bound input still initializes") {
        LayoutFlow(display()).use { flow ->
            check(flow.source.uiInputSnapshot() == null)
            flow.layout(observation)
            assertTapPaths(flow)
        }
    }
    scenario("equal-scale request clears then restores actual original mapping") {
        val source = display()
        LayoutFlow(source).use { flow ->
            flow.inputControls.geometry(size)
            check(source.uiInputSnapshot() != null)
            source.resize(1.0) // Actual production resize clears inputMapping even for the same scale.
            check(source.uiInputSnapshot() == null)
            flow.layout(observation)
            assertTapPaths(flow)
        }
    }
    scenario("captured Anchor DOWN uses original mapping after geometry changes before submission") {
        for(nextVideo in listOf(size,Geometry(640,480))) LayoutFlow(display()).use { flow ->
            flow.inputControls.geometry(size)
            val work=InputWork("queued-anchor-state");val ended=CountDownLatch(1);work.whenTerminated{ended.countDown()}
            try {
                val (setting,source)=hold(flow,work) {
                    flow.geometry.set(nextVideo)
                    flow.layout(observation.copy(rotation=1))
                }
                val down=flow.managed.submitted.single()
                check(down.point==source.geometry.videoToInput(UiPoint(30.0,40.0)))
                val second=flow.inputControls.anchor.capture(AnchorIntent(AnchorIntentKind.ACTIVATE,
                    AnchorSource.PHONE_HID,2,2,AnchorIntentContext(fact,point())))!!
                check(flow.inputControls.anchor.releaseHeld(second,setting,true,source){true}==AnchorReleaseResult.RELEASE_SUBMITTED)
                check(flow.managed.submitted.map{it.action}==listOf(0,1))
                check(flow.managed.submitted.last().point==down.point)
            }finally{work.close();check(ended.await(3,TimeUnit.SECONDS))}
        }
    }
    scenario("unchanged observations retain stationary Anchor until explicit second activation") {
        LayoutFlow(display()).use { flow ->
            flow.inputControls.geometry(size)
            val work = InputWork("layout-hold-state")
            val ended = CountDownLatch(1); work.whenTerminated { ended.countDown() }
            try {
                val (setting, source) = hold(flow, work)
                repeat(4) { flow.layout(observation) }
                check(flow.inputControls.anchor.holding)
                check(flow.events.map { it.phase } == listOf(Touch.Phase.DOWN))
                val intent = AnchorIntent(AnchorIntentKind.ACTIVATE, AnchorSource.PHONE_HID, 2, 2, AnchorIntentContext(fact, point()))
                val admitted = flow.inputControls.anchor.capture(intent)!!
                check(flow.inputControls.anchor.releaseHeld(admitted, setting, true, source) { true } == AnchorReleaseResult.RELEASE_SUBMITTED)
                check(flow.events.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.UP))
                check(flow.events[0].target == flow.events[1].target)
            } finally { work.close(); check(ended.await(3, TimeUnit.SECONDS)) }
        }
    }
    scenario("real mapping change retains original Hold coordinates and refreshes future input") {
        LayoutFlow(display()).use { flow ->
            flow.inputControls.geometry(size)
            val work = InputWork("layout-change-state")
            val ended = CountDownLatch(1); work.whenTerminated { ended.countDown() }
            try {
                val (setting, originalSource) = hold(flow, work)
                val down = flow.managed.submitted.single()
                val rotated = observation.copy(rotation = 2)
                check(rotated.layout == observation.layout)
                flow.layout(rotated)
                check(flow.inputControls.anchor.holding)
                check(flow.events.map { it.phase } == listOf(Touch.Phase.DOWN))
                check(flow.source.uiInputSnapshot()?.geometry?.rotation == 2)
                flow.inputControls.anchor.reconcile(setting.copy(revision=2))
                val intent = AnchorIntent(AnchorIntentKind.ACTIVATE, AnchorSource.PHONE_HID, 2, 2, AnchorIntentContext(fact, point()))
                val admitted = flow.inputControls.anchor.capture(intent)!!
                check(flow.inputControls.anchor.releaseHeld(admitted, setting, false,
                    originalSource.copy(geometry=flow.source.uiInputSnapshot()!!.geometry)) { false } == AnchorReleaseResult.RELEASE_SUBMITTED)
                val up = flow.managed.submitted.last()
                check(up.action == 1 && up.point == down.point && up.display == down.display && up.down == down.down)
                check(flow.managed.submitted.map { it.action } == listOf(0,1))
                check(flow.inputControls.tap(Tap(30,40,size)))
                check(flow.managed.submitted[2].point != down.point)
            } finally { work.close(); check(ended.await(3, TimeUnit.SECONDS)) }
        }
    }
    scenario("explicit other control queues original Cancel and is consumed") {
        LayoutFlow(display()).use { flow ->
            flow.inputControls.geometry(size)
            val work=InputWork("explicit-cancel-state");val ended=CountDownLatch(1);work.whenTerminated{ended.countDown()}
            try {
                hold(flow,work);val down=flow.managed.submitted.single()
                flow.layout(observation.copy(rotation=2))
                var actionRan=false
                check(!flow.phoneInput.nativeAction { actionRan=true;flow.inputControls.tap(Tap(100,100,size)) })
                flow.drainCancellation()
                check(!actionRan && flow.failures.isEmpty())
                check(flow.managed.submitted.map{it.action}==listOf(0,3))
                check(flow.managed.submitted.last().point==down.point)
            }finally{work.close();check(ended.await(3,TimeUnit.SECONDS))}
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
