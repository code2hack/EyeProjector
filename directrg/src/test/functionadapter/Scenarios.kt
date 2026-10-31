package com.code2hack.eyeprojector.directrg

import android.content.Intent
import android.os.Handler
import android.os.SystemClock
import com.code2hack.eyeprojector.protocol.*

private fun reset() { SystemClock.time = 1000; Handler.tasks.clear() }
private fun send(a: ActivityFunctionWiring, name: String) {
    val action = if (name == "TRIPLE") "com.android.action.ACTION_BOLON_PAIRING" else "com.android.action.ACTION_SPRITE_BUTTON_$name"
    a.receiver!!.apply { isOrderedBroadcast = true; onReceive(a, Intent(action)) }
}
private fun press(a: ActivityFunctionWiring) { send(a, "DOWN"); SystemClock.time += 20; send(a, "UP") }
fun main() {
    var checks = 0
    fun scenario(name: String, run: () -> Unit) { reset(); run(); checks++; println("PASS $name") }
    scenario("actual Activity native initializer and callbacks retain two originals and first hover") {
        val a = ActivityFunctionWiring(); val source = a.session!!
        send(a, "DOWN"); a.pointer.position = PointerPosition(300f, 400f, true); send(a, "UP")
        press(a); send(a, "DOUBLE_CLICK"); send(a, "CLICK"); Handler.advance(500)
        check(source.received.map { it.intent.kind } == listOf(FunctionIntentKind.ACTIVATE, FunctionIntentKind.ACTIVATE))
        check(source.received.all { it.original === source && it.intent.source == FunctionSource.GLASSES_NATIVE })
        check(source.received.map { it.intent.context.pointer.x } == listOf(120f, 300f))
        check(a.ordinaryActions == 0 && a.heldOwner.calls == 0)
    }
    scenario("actual Activity long registration callback consumes physical release") {
        val a = ActivityFunctionWiring(); val source = a.session!!
        send(a, "DOWN"); Handler.advance(800); send(a, "LONG_PRESS"); send(a, "UP"); send(a, "CLICK"); Handler.advance(500)
        check(source.received.map { it.intent.kind } == listOf(FunctionIntentKind.REGISTER))
        check(a.ordinaryActions == 0 && a.heldOwner.calls == 0)
    }
    scenario("actual Activity Glasses HID initializer emits each matching short UP through typed callback") {
        val a = ActivityFunctionWiring(); val source = a.session!!
        val binding = HidBinding(HidButton(HidDeviceIdentity("fixture", 1, 2), 30, 40), "Fixture", "Function")
        check(a.hidBindings.assign(HidAction.FUNCTION, binding))
        repeat(3) { n ->
            val at = 1000L + n * 100
            SystemClock.time = at
            check(a.hidInput.accept(HidKeyInput.Event(7, binding, HidKeyInput.Phase.DOWN, at, at)))
            SystemClock.time += 20
            check(a.hidInput.accept(HidKeyInput.Event(7, binding, HidKeyInput.Phase.UP, at, at + 20)))
        }
        Handler.advance(1000)
        check(source.received.map { it.intent.kind } == List(3) { FunctionIntentKind.ACTIVATE })
        check(source.received.all { it.original === source && it.intent.source == FunctionSource.GLASSES_HID })
        check(a.ordinaryActions == 0 && a.heldOwner.calls == 0)
    }
    scenario("actual Activity local Function remains an intention without Back Home or sleep") {
        val a = ActivityFunctionWiring(); val source = a.session!!
        a.connectionView.visibility = android.view.View.VISIBLE
        press(a); Handler.advance(500)
        check(source.received.single().intent.context.usability.mode == ReceiverUsabilityMode.LOCAL_PANEL)
        check(!source.received.single().intent.context.pointer.projected && a.ordinaryActions == 0)
    }
    scenario("actual Activity source replacement retires pending native original instead of refreshing it") {
        val a = ActivityFunctionWiring(); val old = a.session!!
        press(a); a.session = ProjectionSession(); Handler.advance(500)
        check(old.received.isEmpty() && a.session!!.received.isEmpty())
    }
    scenario("actual capture rechecks ownership changed by pointer callback") {
        val a = ActivityFunctionWiring(); val source = a.session!!
        a.pointer.onPosition = { a.inputGeneration++ }
        press(a); Handler.advance(500)
        check(source.received.isEmpty())
    }
    scenario("unsized projected root produces no invalid fitted context") {
        val a = ActivityFunctionWiring(); val source = a.session!!
        a.root.width = 0; press(a); Handler.advance(500)
        check(source.received.isEmpty())
    }
    scenario("unavailable original hover or absent Session never falls back to navigation") {
        for (missing in listOf(false, true)) {
            val a = ActivityFunctionWiring(); val old = a.session!!
            if (missing) a.session = null else a.pointer.position = PointerPosition(0f, 0f, false)
            press(a); Handler.advance(500)
            check(old.received.isEmpty() && a.ordinaryActions == 0 && a.heldOwner.calls == 0)
        }
    }
    println("PASS $checks actual Activity Function initializer/capture/dispatch scenarios; Session/UI/Android boundaries only")
}
