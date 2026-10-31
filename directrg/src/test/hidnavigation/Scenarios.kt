package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.os.Handler
import android.view.InputDevice
import android.view.KeyEvent
import com.code2hack.eyeprojector.directhost.hid.*
import com.code2hack.eyeprojector.directhost.input.PhoneHidRoute
import com.code2hack.eyeprojector.directhost.input.PhoneInputMerger
import com.code2hack.eyeprojector.protocol.*
import java.io.*

private class Glasses(val activity: ActivityNavigationWiring) {
    private val adapter = AndroidHidInput(Context(), activity.hidInput)
    private val device = InputDevice(41, "original peripheral", 1, 2, "Remote")
    init {
        for (role in listOf(HidAction.BACK_HOME, HidAction.LOCK)) {
            activity.hidBindings.assign(role, HidBinding(HidButton(HidDeviceIdentity(device.descriptor, 1, 2),
                100 + role.ordinal, 200 + role.ordinal), "Remote", role.name))
        }
    }
    fun press(role: HidAction = HidAction.BACK_HOME) {
        val down = android.os.SystemClock.time
        val key = 100 + role.ordinal
        val scan = 200 + role.ordinal
        check(adapter.accept(KeyEvent(device = device, action = KeyEvent.ACTION_DOWN, keyCode = key, scanCode = scan,
            deviceId = device.id, downTime = down, eventTime = down)))
        android.os.SystemClock.time += 10
        check(adapter.accept(KeyEvent(device = device, action = KeyEvent.ACTION_UP, keyCode = key, scanCode = scan,
            deviceId = device.id, downTime = down, eventTime = android.os.SystemClock.time)))
    }
    fun double() { press(); Handler.advance(100); press() }
}

private class Phone(private val activity: ActivityNavigationWiring) {
    private val size = Geometry(480, 640)
    val frames = mutableListOf<Frame>()
    private val bindings = PhoneHidAction.entries.associateWith { action -> PhoneHidBinding(
        PhoneHidButton(PhoneHidDeviceIdentity("original phone peripheral", 1, 2), 100 + action.ordinal, 200 + action.ordinal),
        "Remote", action.name) }
    private val store = PhoneHidBindings({ null }) { true }
    private val originalRevision = activity.inputGeneration
    private val fact = ReceiverUsability("original-owner", ReceiverIncarnation("receiver", 1), 1, originalRevision,
        if (activity.projectionContext) ReceiverUsabilityMode.PROJECTED else ReceiverUsabilityMode.LOCAL_PANEL)
    private val merger = PhoneInputMerger(Any(), android.os.SystemClock::uptimeMillis, { at, action ->
        val task = Runnable(action)
        val handler = Handler(android.os.Looper.getMainLooper())
        handler.postAtTime(task, at)
        val cancel: () -> Unit = { handler.removeCallbacks(task) }; cancel
    }, { size }, { error("Unexpected touch") }, { error("Unexpected scroll") },
        { _, _ -> error("Phone projected navigation is checked at actual Session composition separately") },
        { Calls.events.add("phone-cancel") }, { input ->
            val bytes = ByteArrayOutputStream()
            Wire.writeFrame(DataOutputStream(bytes), Frame(Wire.RECEIVER_INPUT, payload = input.bytes()))
            val frame = Wire.readFrame(DataInputStream(bytes.toByteArray().inputStream())).also(frames::add)
            activity.receive(ReceiverInput.from(frame.payload))
        })
    private var nextRoute = 0L
    val controller = PhoneHidController(store, object : PhoneHidRoutes {
        override fun capture(down: PhoneHidEdge): PhoneHidRoute {
            val route = ++nextRoute
            val captured = PhoneHidRoute(down, 1) { edge -> merger.stampedEdge(route, edge) { point, source ->
                point.revision == originalRevision && activity.inputGeneration == originalRevision && source == fact
            } }
            captured.submit(down)
            return captured
        }
        override fun submit(route: PhoneHidRoute, edge: PhoneHidEdge) = route.submit(edge)
        override fun cancel(route: PhoneHidRoute, eventTimeMs: Long) = route.cancel(eventTimeMs)
    }, { operation, complete -> operation(); complete() }, android.os.SystemClock::uptimeMillis)
    init {
        bindings.forEach { (action, binding) -> store.assign(action, binding) }
        merger.pointer(HeadPointerState(originalRevision, 1, false, activity.projectionContext, -500f, 10000f,
            Fit(0, 0, 480, 640), size), fact)
        controller.connection(true)
    }
    fun press(role: PhoneHidAction = PhoneHidAction.BACK_HOME) {
        val down = android.os.SystemClock.time
        check(controller.accept(PhoneHidController.Event(7, bindings.getValue(role), PhoneHidPhase.DOWN, down, down)))
        android.os.SystemClock.time += 10
        check(controller.accept(PhoneHidController.Event(7, bindings.getValue(role), PhoneHidPhase.UP, down, android.os.SystemClock.time)))
    }
    fun double() { press(); Handler.advance(100); press() }
}

fun main() {
    var checks = 0
    fun scenario(name: String, run: () -> Unit) {
        Calls.events.clear(); Handler.tasks.clear(); android.os.SystemClock.time = 1000
        run(); checks++; println("PASS $name")
    }
    scenario("Glasses actual HID initializer single Back reaches actual ordered navigation wire writer without aim") {
        val a = ActivityNavigationWiring(); val session = a.session!!
        try {
            Glasses(a).press(); Handler.advance(600)
            check(session.transport.awaitNavigation() == Navigation.BACK)
            check(a.pointer.positionReads == 0 && session.navigations == listOf(Navigation.BACK))
            check(Calls.events.indexOf("session-cancel") < Calls.events.indexOf("navigate"))
        } finally { session.transport.close() }
    }
    scenario("Glasses HID double uses contextual Home wire operation and retains projection") {
        val a = ActivityNavigationWiring(); val session = a.session!!; val decoder = session.decoder
        try {
            Glasses(a).double()
            check(session.transport.awaitNavigation() == Navigation.CONTEXTUAL_HOME)
            check(a.session === session && session.decoder === decoder && !session.closed && !a.finished)
            check(a.pointer.positionReads == 0)
        } finally { session.transport.close() }
    }
    scenario("Glasses HID settings Home returns to panel then next Home exits with original activation") {
        val a = ActivityNavigationWiring(); val session = a.session!!
        try {
            a.showControlPanel(); a.mainControls.visibility = View.GONE; a.hidPanel.visibility = View.VISIBLE
            val source = Glasses(a); source.double()
            check(a.hidPanel.visibility == View.GONE && a.mainControls.visibility == View.VISIBLE && !a.finished)
            Handler.advance(100); source.double()
            check(a.finished && a.owner!!.canceled == listOf(a.launchScope to a.visibleActivation))
            check(session.navigations.isEmpty() && a.pointer.positionReads == 0)
        } finally { session.transport.close() }
    }
    scenario("Glasses HID Lock cancels pending Back then requests existing sleep while retaining session") {
        val a = ActivityNavigationWiring(); val session = a.session!!
        try {
            val source = Glasses(a); source.press(); Handler.advance(100); source.press(HidAction.LOCK); Handler.advance(600)
            check(a.shellPadContact.requests == 1 && session.navigations.isEmpty())
            check(session.transport.frames.isEmpty() && !session.closed && !a.finished && a.owner!!.canceled.isEmpty())
            check(Calls.events.indexOf("session-cancel") < Calls.events.indexOf("sleep-request"))
            check(a.pointer.positionReads == 0)
        } finally { session.transport.close() }
    }
    scenario("Glasses original pending navigation cannot survive actual Activity input-context retirement") {
        val a = ActivityNavigationWiring(); val session = a.session!!
        try {
            Glasses(a).press(); a.showControlPanel(); Handler.advance(600)
            check(session.navigations.isEmpty() && session.transport.frames.isEmpty() && !a.finished)
        } finally { session.transport.close() }
    }
    scenario("Phone actual controller route merger and completed wire Home reach actual settings and exit destinations") {
        val a = ActivityNavigationWiring(); val session = a.session!!
        try {
            a.showControlPanel(); a.mainControls.visibility = View.GONE; a.hidPanel.visibility = View.VISIBLE
            val first = Phone(a); first.double()
            check(first.frames.map { ReceiverInput.from(it.payload).kind } == listOf(ReceiverInputKind.HOME))
            check(a.hidPanel.visibility == View.GONE && !a.finished && a.session === session)
            Handler.advance(100)
            val second = Phone(a); second.double()
            check(a.finished && a.pendingExit == a.launchScope to a.visibleActivation)
            check(a.owner!!.canceled == listOf(a.launchScope to a.visibleActivation))
        } finally { session.transport.close() }
    }
    scenario("Phone completed Lock uses actual cancellation and sleep destination in projected and local modes") {
        for (local in listOf(false, true)) {
            val a = ActivityNavigationWiring(); val session = a.session!!
            try {
                if (local) a.showControlPanel()
                val source = Phone(a); source.press(PhoneHidAction.LOCK)
                check(source.frames.map { ReceiverInput.from(it.payload).kind } == listOf(ReceiverInputKind.LOCK))
                check(a.shellPadContact.requests == 1 && !a.finished && !session.closed && session.navigations.isEmpty())
            } finally { session.transport.close() }
        }
    }
    scenario("Phone controller cancel after UP prevents completed action and stale completed wire cannot reach successor") {
        val a = ActivityNavigationWiring(); val session = a.session!!
        try {
            a.showControlPanel()
            val source = Phone(a); source.press(); source.controller.presentationLost(); Handler.advance(600)
            check(source.frames.isEmpty())
            val old = a.inputGeneration
            a.showControlPanel()
            a.receive(ReceiverInput(ReceiverInputKind.LOCK, old))
            check(a.shellPadContact.requests == 0 && !a.finished)
        } finally { session.transport.close() }
    }
    println("PASS $checks actual HID/destination/transport scenarios; named host boundaries, no physical proof")
}
