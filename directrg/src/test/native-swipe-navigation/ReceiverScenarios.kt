package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.content.Intent
import android.os.Build

private const val BACK = "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"
private const val FORWARD = "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"

fun main() {
    var checks = 0
    fun scenario(name: String, run: () -> Unit) { run(); checks++; println("PASS $name") }
    for (api in listOf(32, 33, 35)) {
        Build.VERSION.SDK_INT = api
        scenario("API$api registers exactly two actions with the platform overload") {
            val c = Context(); val actions = mutableListOf<ContextualNavigationAction>()
            val input = NativeSwipeNavigationInput(c, actions::add)
            input.start(); input.start()
            check(c.registrations == 1 && c.receivers.size == 1)
            check(c.filter!!.actions == listOf(BACK, FORWARD) && c.filter!!.priority == 0)
            check(c.flags == if (api >= 33) Context.RECEIVER_EXPORTED else null)
            val receiver = c.receivers.single()
            receiver.onReceive(c, Intent(BACK)); receiver.onReceive(c, Intent(FORWARD))
            check(actions == listOf(ContextualNavigationAction.BACK, ContextualNavigationAction.HOME))
            input.stop(); input.stop(); check(c.unregistrations == 1 && c.receivers.isEmpty())
        }
    }
    scenario("unknown null case-changed and ordinary/Function/Lock actions do not dispatch") {
        val c = Context(); val actions = mutableListOf<ContextualNavigationAction>()
        val input = NativeSwipeNavigationInput(c, actions::add); input.start()
        for (action in listOf(null, "", BACK.lowercase(), BACK + "_UP", "com.android.action.ACTION_SPRITE_BUTTON_DOWN",
                "com.android.action.ACTION_SPRITE_BUTTON_CLICK", "com.android.action.ACTION_AI_START",
                "com.android.action.ACTION_SPRITE_SINGLE_TAP", "com.android.action.ACTION_SPRITE_DOUBLE_TAP",
                "com.android.action.ACTION_TWO_FINGER_LOCK")) c.receivers.single().onReceive(c, Intent(action))
        check(actions.isEmpty()); input.stop()
    }
    scenario("unordered current sender never aborts or writes a result for any flags") {
        val c = Context(); var callbacks = 0
        val input = NativeSwipeNavigationInput(c) { callbacks++ }; input.start()
        val receiver = c.receivers.single()
        for (flags in listOf(0, 0x50000000, 0x50000008, Intent.FLAG_RECEIVER_NO_ABORT)) {
            receiver.onReceive(c, Intent(BACK).also { it.flags = flags })
            receiver.onReceive(c, Intent(FORWARD).also { it.flags = flags })
            check(receiver.aborts == 0 && receiver.resultWrites == 0 && receiver.resultCode == 91)
        }
        check(callbacks == 8); input.stop()
    }
    scenario("unexpected ordered delivery also leaves other consumers and result unchanged") {
        val c = Context(); val input = NativeSwipeNavigationInput(c) {}; input.start()
        val receiver = c.receivers.single(); receiver.isOrderedBroadcast = true
        receiver.onReceive(c, Intent(BACK)); check(receiver.aborts == 0 && receiver.resultWrites == 0)
        input.stop()
    }
    scenario("same action repeated is two receipts without invented physical deduplication") {
        val c = Context(); var callbacks = 0
        val input = NativeSwipeNavigationInput(c) { callbacks++ }; input.start()
        repeat(2) { c.receivers.single().onReceive(c, Intent(BACK)) }
        check(callbacks == 2); input.stop()
    }
    scenario("delivery during registration is ignored before successful start") {
        val c = Context(); var callbacks = 0
        c.beforeRegister = { it.onReceive(c, Intent(BACK)) }
        val input = NativeSwipeNavigationInput(c) { callbacks++ }; input.start()
        check(callbacks == 0); c.receivers.single().onReceive(c, Intent(BACK)); check(callbacks == 1); input.stop()
    }
    for (partial in listOf(false, true)) scenario("registration failure partial=$partial cleans and permits fresh start") {
        val c = Context(); var callbacks = 0; val failure = SecurityException("registration failed")
        val input = NativeSwipeNavigationInput(c) { callbacks++ }
        c.failAfterInstall = partial; c.registerFailure = failure
        check(runCatching { input.start() }.exceptionOrNull() === failure)
        val old = c.attempts.single(); old.onReceive(c, Intent(BACK))
        check(c.receivers.isEmpty() && callbacks == 0 && c.unregistrations == 1)
        if (!partial) check(failure.suppressed.single() is IllegalArgumentException)
        input.stop(); c.registerFailure = null; input.start()
        check(c.receivers.single() !== old)
        old.onReceive(c, Intent(FORWARD)); check(callbacks == 0)
        c.receivers.single().onReceive(c, Intent(BACK)); check(callbacks == 1); input.stop()
    }
    scenario("stop invalidates before unregister callback and after a restart") {
        val c = Context(); var callbacks = 0
        val input = NativeSwipeNavigationInput(c) { callbacks++ }; input.start(); val old = c.receivers.single()
        c.beforeUnregister = { it.onReceive(c, Intent(BACK)) }
        input.stop(); old.onReceive(c, Intent(FORWARD)); check(callbacks == 0)
        input.start(); old.onReceive(c, Intent(BACK)); check(callbacks == 0)
        c.receivers.single().onReceive(c, Intent(FORWARD)); check(callbacks == 1); input.stop()
    }
    scenario("failed unregister remains observable and old callback remains inactive") {
        val c = Context(); var callbacks = 0; val failure = IllegalStateException("unregister failed")
        val input = NativeSwipeNavigationInput(c) { callbacks++ }; input.start(); val old = c.receivers.single()
        c.unregisterFailure = failure
        check(runCatching { input.stop() }.exceptionOrNull() === failure)
        old.onReceive(c, Intent(BACK)); check(callbacks == 0)
        c.unregisterFailure = null; c.unregisterReceiver(old)
        input.start(); old.onReceive(c, Intent(BACK)); check(callbacks == 0); input.stop()
    }
    scenario("navigation may synchronously stop and restart without activating its old receiver") {
        val c = Context(); var callbacks = 0
        lateinit var input: NativeSwipeNavigationInput
        input = NativeSwipeNavigationInput(c) { callbacks++; input.stop(); input.start() }
        input.start(); val old = c.receivers.single(); old.onReceive(c, Intent(BACK))
        check(callbacks == 1 && c.receivers.single() !== old)
        old.onReceive(c, Intent(FORWARD)); check(callbacks == 1); input.stop()
    }
    println("PASS $checks actual receiver scenarios; Android boundary only, no physical/suppression proof")
}
