package com.code2hack.eyeprojector.directrg

import android.content.Intent as BroadcastIntent
import com.code2hack.eyeprojector.protocol.*

private const val BACK = "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"
private const val HOME = "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"

fun main() {
    var passed = 0
    val failures = mutableListOf<String>()
    fun scenario(name: String, test: () -> Unit) {
        Calls.events.clear()
        try { test(); passed++; println("PASS $name") }
        catch (failure: Throwable) { failures.add(name); println("FAIL $name: $failure"); failure.printStackTrace(System.out) }
    }
    fun swipe(a: ActivityNavigationWiring, action: String) = a.receivers.single().onReceive(a, BroadcastIntent(action))
    for ((action, expected) in listOf(BACK to Navigation.BACK, HOME to Navigation.CONTEXTUAL_HOME)) {
        scenario("$expected cancels original actual held owner before existing navigation") {
            val a = ActivityNavigationWiring(); val original = a.session!!
            val events = mutableListOf<HeldPointerDispatcher.Event>()
            a.onHeldEvent = { events.add(it); true }; a.down()
            swipe(a, action)
            check(events.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL))
            check(events[0].contactId == events[1].contactId && !a.heldOwner.holding)
            check(original.navigations == listOf(expected))
        }
        scenario("$expected cannot navigate a successor installed by original CANCEL callback") {
            val a = ActivityNavigationWiring(); val original = a.session!!; val successor = Session()
            a.onHeldEvent = { if (it.phase == Touch.Phase.CANCEL) a.session = successor; true }; a.down()
            swipe(a, action)
            check(!a.heldOwner.holding)
            check(original.pendingCancellations > 0 && successor.pendingCancellations == 0) {
                "Pending cancellation original=${original.pendingCancellations} successor=${successor.pendingCancellations}"
            }
            check(original.navigations.isEmpty())
            check(successor.navigations.isEmpty()) { "Original swipe navigated successor: ${successor.navigations}" }
            check(a.publishedSessions.none { it === successor }) { "Original cancellation published to successor" }
        }
        for (change in listOf("focus", "panel", "geometry", "revision", "owner")) {
            scenario("$expected stops after original CANCEL changes $change") {
                val a = ActivityNavigationWiring(); val original = a.session!!
                a.onHeldEvent = {
                    if (it.phase == Touch.Phase.CANCEL) when (change) {
                        "focus" -> a.focus = false
                        "panel" -> a.showControlPanel()
                        "geometry" -> a.currentGeometry = Geometry(640, 480)
                        "revision" -> a.retireInput()
                        "owner" -> a.owner = Owner()
                    }
                    true
                }
                a.down(); swipe(a, action)
                check(original.navigations.isEmpty() && !a.finished) { "Old navigation survived $change" }
                if (change == "panel") check(a.presentation.panelVisible) { "Old navigation changed the new panel" }
            }
        }
    }
    scenario("registration Back cancels chooser only; next swipe uses existing contextual Back") {
        val a = ActivityNavigationWiring(); a.showChooser(); swipe(a, BACK)
        check(!a.anchorRegistration.active && a.session!!.navigations.isEmpty())
        swipe(a, BACK); check(a.session!!.navigations == listOf(Navigation.BACK))
    }
    scenario("registration Home cancels chooser then uses existing Home destination") {
        val a = ActivityNavigationWiring(); a.showChooser(); swipe(a, HOME)
        check(!a.anchorRegistration.active && a.session!!.navigations == listOf(Navigation.CONTEXTUAL_HOME))
    }
    scenario("chooser Home cancellation callback cannot adopt or publish to a successor") {
        val a = ActivityNavigationWiring(); a.showChooser(); val old = a.session!!; val next = Session()
        old.onChoice = { if (it == AnchorChoice.CANCEL) a.session = next }
        swipe(a, HOME)
        check(next.navigations.isEmpty() && next.pendingCancellations == 0)
        check(a.publishedSessions.none { it === next }) { "Chooser cancellation published to successor" }
    }
    for (action in listOf(BACK, HOME)) scenario("chooser cancel $action preserves successor revision contact and pending work") {
        val a = ActivityNavigationWiring(); a.showChooser(); val old = a.session!!
        val next = Session(); val nextOwner = Owner().apply { revision = 700 }
        next.pendingWork.add("successor-work")
        old.onChoice = {
            if (it == AnchorChoice.CANCEL) {
                a.session = next; a.owner = nextOwner; a.inputGeneration = 901
                check(a.down() && a.heldOwner.holding)
            }
        }
        swipe(a, action)
        check(a.session === next && a.owner === nextOwner)
        check(a.inputGeneration == 901L && nextOwner.revision == 700L) {
            "Successor revision overwritten: local=${a.inputGeneration}, owner=${nextOwner.revision}"
        }
        check(a.heldOwner.holding && next.pendingWork == listOf("successor-work")) { "Successor contact/work canceled" }
        check(next.pendingCancellations == 0 && next.navigations.isEmpty() && a.publishedSessions.none { it === next })
    }
    for (action in listOf(BACK, HOME)) scenario("chooser cancel $action preserves later same-Session nested work") {
        val a = ActivityNavigationWiring(); a.showChooser(); val original = a.session!!
        var nestedRevision = -1L; var nestedCancellations = -1
        original.onChoice = {
            if (it == AnchorChoice.CANCEL) {
                a.retireInput()
                nestedRevision = a.inputGeneration; nestedCancellations = original.pendingCancellations
                original.pendingWork.add("later-work"); check(a.down())
            }
        }
        swipe(a, action)
        check(a.inputGeneration == nestedRevision && original.pendingCancellations == nestedCancellations)
        check(a.heldOwner.holding && original.pendingWork == listOf("later-work") && original.navigations.isEmpty())
    }
    scenario("actual focus pause resume destroy bodies retire receiver lifetime") {
        val a = ActivityNavigationWiring(); val old = a.receivers.single()
        a.onWindowFocusChanged(false); check(a.receivers.isEmpty())
        old.onReceive(a, BroadcastIntent(BACK)); check(a.session!!.navigations.isEmpty())
        a.onWindowFocusChanged(true); check(a.receivers.single() !== old)
        old.onReceive(a, BroadcastIntent(HOME)); check(a.session!!.navigations.isEmpty())
        a.onPause(); check(a.receivers.isEmpty())
        a.onResume(); check(a.receivers.size == 1)
        a.onDestroy(); check(a.receivers.isEmpty())
    }
    scenario("focused receiver callback checks actual usable before navigating") {
        val a = ActivityNavigationWiring(); val receiver = a.receivers.single(); a.focus = false
        receiver.onReceive(a, BroadcastIntent(BACK)); receiver.onReceive(a, BroadcastIntent(HOME))
        check(a.session!!.navigations.isEmpty()); a.nativeSwipeInput.stop()
    }
    scenario("settings Home uses main panel and next Home unregisters before launcher exit") {
        val a = ActivityNavigationWiring(); a.showControlPanel(); a.hidPanel.visibility = View.VISIBLE
        swipe(a, HOME); check(a.hidPanel.visibility == View.GONE && !a.finished)
        swipe(a, HOME); check(a.finished && a.receivers.isEmpty() && !a.resumed)
    }
    println("RESULT $passed passed; ${failures.size} failed; actual Activity/held-owner source with named Android/Session/UI boundaries")
    check(failures.isEmpty()) { failures.joinToString() }
}
