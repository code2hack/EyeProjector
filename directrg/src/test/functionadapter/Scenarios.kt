package com.code2hack.eyeprojector.directrg

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.SystemClock

private fun reset() { SystemClock.time = 1000; Handler.tasks.clear() }
private fun action(name: String) = when (name) {
    "TRIPLE" -> "com.android.action.ACTION_BOLON_PAIRING"
    "DOUBLE" -> "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK"
    "LONG" -> "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS"
    else -> "com.android.action.ACTION_SPRITE_BUTTON_$name"
}
private fun send(context: Context, name: String, ordered: Boolean = true, flags: Int = 0, retired: Boolean = false) {
    val receiver = (if (retired) context.lastReceiver else context.receiver)!!
    receiver.isOrderedBroadcast = ordered; receiver.resultCode = 99; receiver.aborted = false
    receiver.onReceive(context, Intent(action(name)).addFlags(flags))
}
private fun press(context: Context) { send(context, "DOWN"); SystemClock.time += 25; send(context, "UP") }
private fun activity(projecting: Boolean): ActivityFunctionWiring {
    reset()
    val a = ActivityFunctionWiring()
    a.onResume(); a.onWindowFocusChanged(true)
    if (projecting) { a.session = Session(); a.streaming = true; a.presentation.streaming(); a.connectionView.visibility = 8 }
    return a
}

fun main() {
    var checks = 0
    fun scenario(name: String, run: () -> Unit) { reset(); run(); checks++; println("PASS $name") }
    scenario("real receiver registration and ordered suppression API32/API33") {
        for (api in listOf(32, 33, 35)) {
            Build.VERSION.SDK_INT = api
            val c = Context(); val got = mutableListOf<FunctionGestureRecognizer.Action>()
            val input = NativeFunctionInput(c, { "context" }, got::add)
            input.start(); input.start()
            check(c.registrations == 1 && c.filter!!.priority == Int.MAX_VALUE && c.filter!!.actions.size == 6)
            check(c.registerFlags == if (api >= 33) Context.RECEIVER_EXPORTED else null)
            send(c, "CLICK"); check(c.receiver!!.aborted && c.receiver!!.resultCode == Activity.RESULT_CANCELED)
            Handler.advance(500); check(got == listOf(FunctionGestureRecognizer.Action.SINGLE))
            input.stop(); input.stop(); check(c.unregistrations == 1)
        }
    }
    scenario("unordered and NO_ABORT do not claim suppression") {
        for ((ordered, flags) in listOf(false to 0, true to Intent.FLAG_RECEIVER_NO_ABORT)) {
            val c = Context(); val got = mutableListOf<FunctionGestureRecognizer.Action>()
            val input = NativeFunctionInput(c, { "context" }, got::add); input.start()
            send(c, "DOUBLE", ordered, flags)
            check(!c.receiver!!.aborted && c.receiver!!.resultCode == 99)
            Handler.advance(500); check(got == listOf(FunctionGestureRecognizer.Action.DOUBLE)); input.stop()
        }
    }
    scenario("ordinary AI/pad/unrelated broadcasts are untouched") {
        val c = Context(); val got = mutableListOf<FunctionGestureRecognizer.Action>()
        NativeFunctionInput(c, { "context" }, got::add).start()
        for (name in listOf("com.android.action.ACTION_AI_START", "ordinary")) {
            c.receiver!!.isOrderedBroadcast = true
            c.receiver!!.onReceive(c, Intent(name))
            check(!c.receiver!!.aborted && c.receiver!!.resultCode == 99)
        }
        Handler.advance(2000); check(got.isEmpty())
    }
    scenario("stop retires callbacks and queued delivery then restart admits a fresh edge") {
        val c = Context(); val got = mutableListOf<FunctionGestureRecognizer.Action>()
        val input = NativeFunctionInput(c, { "context" }, got::add); input.start(); send(c, "DOWN")
        val stale = Handler.tasks.toList(); input.stop()
        send(c, "TRIPLE", retired = true); stale.forEach { it.run.run() }; check(got.isEmpty())
        input.start(); press(c); Handler.advance(500); check(got == listOf(FunctionGestureRecognizer.Action.SINGLE))
    }
    scenario("actual Activity callback projects Home/Back with no pad fallback") {
        val a = activity(true); val s = a.session!!
        send(a, "CLICK"); Handler.advance(499); check(s.navigations.isEmpty())
        send(a, "DOUBLE"); Handler.advance(500); check(s.navigations == listOf(Navigation.HOME))
        send(a, "CLICK"); Handler.advance(500); check(s.navigations.size == 1)
        press(a); Handler.advance(500); check(s.navigations == listOf(Navigation.HOME, Navigation.BACK))
    }
    scenario("actual triple panel and local Back retain boundary session decoder Surface") {
        val a = activity(true); val s = a.session!!; val decoder = s.decoder; val surface = a.screen.surface
        send(a, "CLICK"); send(a, "DOUBLE"); send(a, "TRIPLE"); Handler.advance(500)
        check(!a.projectionContext && a.session === s && s.decoder === decoder && a.screen.surface === surface && !s.closed)
        check(s.navigations.isEmpty() && a.heldInput.calls > 0 && a.localInput.calls > 0)
        send(a, "TRIPLE"); send(a, "UP"); Handler.advance(500); check(!a.finished && !s.closed)
        press(a); Handler.advance(500)
        check(a.projectionContext && a.session === s && s.decoder === decoder && a.screen.surface === surface)
    }
    scenario("actual local double restores main panel and long consumes") {
        val a = activity(false)
        send(a, "DOUBLE"); Handler.advance(500); check(a.connectionView.scrollY == 0 && !a.finished)
        press(a); send(a, "LONG"); Handler.advance(1000); check(!a.finished && a.launch == null)
    }
    scenario("actual binding view single and Back restore main before the retained projection") {
        val a = activity(true); val s = a.session!!; val decoder = s.decoder; val surface = a.screen.surface
        a.showControlPanel(); a.openBindings()
        check(a.hidPanel.visibility == 0 && a.mainControls.visibility == 8)
        val canceled = a.hidInput.calls
        press(a); Handler.advance(500)
        check(a.hidPanel.visibility == 8 && a.mainControls.visibility == 0 && a.hidInput.calls > canceled)
        check(a.session === s && !s.closed && s.decoder === decoder && a.screen.surface === surface)
        a.openBindings(); a.contextualBack()
        check(a.hidPanel.visibility == 8 && !a.projectionContext && a.session === s)
        a.contextualBack()
        check(a.projectionContext && a.session === s && !s.closed)
    }
    scenario("actual local triple releases and explicitly requests Home before late peer callback") {
        val a = activity(true); val s = a.session!!; a.showControlPanel()
        val client = a.sessionClient!!; val observer = client.observer
        // A fresh physical sequence bypasses the previous sequence's duplicate retirement.
        repeat(3) { press(a) }; Handler.advance(500)
        check(a.finished && !a.resumed && a.session == null && s.closed && client.closed && a.sessionClient == null)
        check(client.closeEpoch == "epoch")
        check(a.receiver == null && a.unregistrations == 1)
        check(a.launch!!.action == Intent.ACTION_MAIN && Intent.CATEGORY_HOME in a.launch!!.categories)
        check(a.launch!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        observer(Peer()); check(a.observed == 0)
        send(a, "CLICK", retired = true); Handler.advance(2000); check(a.session == null && a.observed == 0)
    }
    scenario("actual focus loss cancellation and fast fresh input after focus return") {
        val a = activity(true); val s = a.session!!
        send(a, "DOWN"); val stale = Handler.tasks.toList()
        a.onWindowFocusChanged(false); check(a.receiver == null)
        stale.forEach { it.run.run() }; check(s.navigations.isEmpty())
        a.onWindowFocusChanged(true); press(a); Handler.advance(500)
        check(s.navigations == listOf(Navigation.BACK))
    }
    scenario("actual pause destroy disconnect and late callbacks") {
        for (end in listOf("pause", "destroy", "disconnect")) {
            val a = activity(true); val s = a.session!!
            send(a, "DOUBLE"); val stale = Handler.tasks.toList()
            when (end) { "pause" -> a.onPause(); "destroy" -> a.onDestroy(); else -> a.disconnect() }
            stale.forEach { it.run.run() }; check(s.closed && s.navigations.isEmpty() && a.session == null)
            if (end != "disconnect") check(a.receiver == null)
        }
    }
    println("PASS $checks Function adapter/Activity source scenarios; host boundaries only")
}
