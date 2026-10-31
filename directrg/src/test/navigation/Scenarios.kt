package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.*

fun main() {
    var checks = 0
    fun scenario(name: String, run: () -> Unit) { Calls.events.clear(); run(); checks++; println("PASS $name") }
    scenario("dedicated Home uses projected contextual route after actual input cancellation") {
        val a = ActivityNavigationWiring(); val s = a.session!!; val decoder = s.decoder; val surface = a.screen
        a.dispatchNavigation(ContextualNavigationAction.HOME)
        check(s.navigations == listOf(Navigation.CONTEXTUAL_HOME))
        check(Calls.events.indexOf("session-cancel") < Calls.events.indexOf("navigate"))
        check(a.session === s && s.decoder === decoder && a.screen === surface && !s.closed && !a.finished)
        check(a.owner!!.canceled.isEmpty())
    }
    scenario("Phone PANEL receipt retains media and Back restores projection") {
        val a = ActivityNavigationWiring(); val s = a.session!!; val decoder = s.decoder
        a.completed(ReceiverInputKind.PANEL)
        check(a.presentation.panelVisible && a.connectionView.visibility == View.VISIBLE)
        check(a.session === s && s.decoder === decoder && !s.closed && !a.finished)
        a.dispatchNavigation(ContextualNavigationAction.BACK)
        check(!a.presentation.panelVisible && s.navigations.isEmpty())
        check(a.session === s && a.owner!!.canceled.isEmpty())
        a.dispatchNavigation(ContextualNavigationAction.BACK)
        check(s.navigations == listOf(Navigation.BACK))
    }
    scenario("settings Home restores main panel; next Home retires original activation and exits") {
        val a = ActivityNavigationWiring(); val s = a.session!!; val owner = a.owner!!
        a.showControlPanel(); a.mainControls.visibility = View.GONE; a.hidPanel.visibility = View.VISIBLE
        a.completed(ReceiverInputKind.HOME)
        check(a.hidPanel.visibility == View.GONE && a.mainControls.visibility == View.VISIBLE && !a.finished)
        check(a.session === s && !s.closed && owner.canceled.isEmpty())
        val original = a.launchScope to a.visibleActivation
        a.completed(ReceiverInputKind.HOME)
        check(a.finished && !a.resumed && !a.bindingValid)
        check(owner.canceled == listOf(original) && a.pendingExit == original)
        val request = a.launch!!
        check(request.action == Intent.ACTION_MAIN && Intent.CATEGORY_HOME in request.categories)
        check(request.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        check(Calls.events.indexOf("session-cancel") < Calls.events.indexOf("owner-cancel"))
        check(Calls.events.indexOf("owner-cancel") < Calls.events.indexOf("launcher"))
    }
    scenario("exit without bound owner carries original scope through service cancellation") {
        val a = ActivityNavigationWiring(); a.showControlPanel(); a.owner = null
        val original = a.launchScope to a.visibleActivation
        a.dispatchNavigation(ContextualNavigationAction.HOME)
        check(a.serviceCancel == original && a.pendingExit == original && a.finished)
    }
    scenario("dedicated Lock requests existing sleep after cancellation and preserves projection") {
        for (submitted in listOf(true, false)) {
            val a = ActivityNavigationWiring(); val s = a.session!!; val decoder = s.decoder
            a.shellPadContact.submitted = submitted
            a.completed(ReceiverInputKind.LOCK)
            check(a.shellPadContact.requests == 1)
            check(Calls.events.indexOf("session-cancel") < Calls.events.indexOf("sleep-request"))
            check(a.session === s && s.decoder === decoder && !s.closed && !a.finished)
            check(a.owner!!.canceled.isEmpty() && a.launch == null)
            Calls.events.clear()
        }
    }
    scenario("stale completed actions cannot navigate a replacement input context") {
        val a = ActivityNavigationWiring()
        val revision = a.inputGeneration
        a.showControlPanel()
        a.receive(ReceiverInput(ReceiverInputKind.HOME, revision))
        a.receive(ReceiverInput(ReceiverInputKind.LOCK, revision))
        check(!a.finished && a.shellPadContact.requests == 0 && a.owner!!.canceled.isEmpty())
    }
    scenario("focus loss prevents dedicated Home and Lock submission") {
        val a = ActivityNavigationWiring(); a.focus = false
        a.dispatchNavigation(ContextualNavigationAction.HOME)
        a.dispatchNavigation(ContextualNavigationAction.LOCK)
        check(a.session!!.navigations.isEmpty() && a.shellPadContact.requests == 0 && !a.finished)
    }
    println("PASS $checks actual Activity navigation scenarios; modeled Android/session boundaries, no physical proof")
}
