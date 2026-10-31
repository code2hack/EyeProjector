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
    scenario("Back cancels registration before app navigation and repeated Back resumes normal route") {
        val a = ActivityNavigationWiring(); a.showChooser(); Calls.events.clear()
        a.dispatchNavigation(ContextualNavigationAction.BACK)
        check(a.choices == listOf(AnchorRegistrationAction.CANCEL))
        check(a.anchorOverlay.visibility == View.GONE && !a.anchorRegistration.active)
        check(a.session!!.navigations.isEmpty())
        a.dispatchNavigation(ContextualNavigationAction.BACK)
        check(a.session!!.navigations == listOf(Navigation.BACK))
    }
    scenario("Home and Lock retire registration before their existing destination") {
        for (action in listOf(ContextualNavigationAction.HOME, ContextualNavigationAction.LOCK)) {
            val a = ActivityNavigationWiring(); a.showChooser(); Calls.events.clear()
            a.dispatchNavigation(action)
            val destination = if (action == ContextualNavigationAction.HOME) "navigate" else "sleep-request"
            check(Calls.events.indexOf("anchor-CANCEL") >= 0)
            check(Calls.events.indexOf("anchor-CANCEL") < Calls.events.indexOf(destination))
            check(!a.anchorRegistration.active)
        }
    }
    scenario("Phone completed Tap remains local and direct app Tap Scroll are consumed during chooser") {
        val a = ActivityNavigationWiring(); a.showChooser(); Calls.events.clear()
        val revision = a.inputGeneration
        a.receive(ReceiverInput(ReceiverInputKind.DOWN, revision, 9, 200f, 100f))
        a.receive(ReceiverInput(ReceiverInputKind.UP, revision, 9, 200f, 100f))
        check("local-DOWN" in Calls.events && "local-UP" in Calls.events)
        a.receive(ReceiverInput(ReceiverInputKind.SCROLL, revision, x = 200f, y = 100f, pixels = 100))
        check("local-scroll" in Calls.events)
        check(a.directTap() && a.directScroll())
        check(a.session!!.taps == 0 && a.session!!.scrolls == 0 && a.choices.isEmpty())
    }
    scenario("genuine input retirement cancels original registration and stale completed Tap cannot click through") {
        val a = ActivityNavigationWiring(); a.showChooser(); val revision = a.inputGeneration
        a.retireInput(); Calls.events.clear()
        a.receive(ReceiverInput(ReceiverInputKind.DOWN, revision, 9, 200f, 100f))
        a.receive(ReceiverInput(ReceiverInputKind.UP, revision, 9, 200f, 100f))
        check(a.choices == listOf(AnchorRegistrationAction.CANCEL))
        check(!a.anchorRegistration.active && a.anchorOverlay.visibility == View.GONE)
        check("local-DOWN" !in Calls.events && a.session!!.taps == 0)
    }
    scenario("reserves original transaction before OPEN and publishes only its exact allocated chooser revision") {
        val a = ActivityNavigationWiring()
        a.owner!!.onOpen = { check(a.anchorRegistration.active) }
        a.showChooser()
        check(a.anchorRegistration.offer!!.chooserRevision == a.inputGeneration)
        check(a.session!!.fact.inputRevision == a.inputGeneration)
        check(Calls.events.indexOf("OPEN") >= 0 && Calls.events.indexOf("OPEN") < Calls.events.indexOf("publish"))
        check(a.anchorOverlay.visibility == View.VISIBLE && !a.canCaptureFunction())
    }
    scenario("failed original OPEN cancels and restores local state without any app output") {
        val a = ActivityNavigationWiring(); a.owner!!.openAccepted = false; a.showChooser()
        check(!a.anchorRegistration.active && a.anchorOffer == null && a.anchorOverlay.visibility == View.GONE)
        check(a.choices == listOf(AnchorRegistrationAction.CANCEL))
        check(a.session!!.taps == 0 && a.session!!.scrolls == 0)
    }
    scenario("duplicate Offer keeps the original chooser revision without another OPEN or cancel") {
        val a = ActivityNavigationWiring(); a.showChooser()
        val offer = a.anchorOffer!!; val revision = a.inputGeneration; Calls.events.clear()
        a.message(a.session!!, offer)
        check(a.inputGeneration == revision && a.anchorRegistration.active && Calls.events.isEmpty())
    }
    scenario("Click Hold submit locally and confirmation waits for matching committed result") {
        for ((action, result) in listOf(AnchorRegistrationAction.CLICK to AnchorRegistrationResult.SAVED_CLICK,
            AnchorRegistrationAction.HOLD to AnchorRegistrationResult.SAVED_HOLD)) {
            val a = ActivityNavigationWiring(); a.showChooser(); val offer = a.anchorOffer!!; Calls.events.clear()
            a.choose(action); a.choose(action)
            check(a.choices == listOf(action) && a.anchorRegistration.active)
            check(Calls.events.none { it.startsWith("toast:") })
            a.message(a.session!!, AnchorMessage.Result(offer.transactionId, offer.intent, result))
            check(!a.anchorRegistration.active && a.anchorOverlay.visibility == View.GONE)
            check(Calls.events.count { it.startsWith("toast:Anchor saved:") } == 1)
            check(a.session!!.taps == 0 && a.session!!.scrolls == 0)
            Calls.events.clear()
        }
    }
    scenario("late previous Session result cannot dismiss or confirm a successor chooser with the same id") {
        val a = ActivityNavigationWiring(); a.showChooser(); val old = a.session!!; val offer = a.anchorOffer!!
        a.retireInput(); a.session = Session(); a.showChooser(); Calls.events.clear()
        a.message(old, AnchorMessage.Result(offer.transactionId, offer.intent, AnchorRegistrationResult.SAVED_HOLD))
        check(a.anchorRegistration.active && a.anchorOverlay.visibility == View.VISIBLE)
        check(Calls.events.none { it.startsWith("toast:") })
    }
    scenario("stale original source or fit Offer is canceled before entering local chooser") {
        val a = ActivityNavigationWiring(); a.showChooser(); val old = a.session!!; val offer = a.anchorOffer!!
        a.retireInput(); a.session = Session(); Calls.events.clear()
        a.message(old, offer)
        check(!a.anchorRegistration.active && "OPEN" !in Calls.events && a.anchorOffer == null)
        val current = a.session!!
        val changed = offer.intent.context.copy(pointer = offer.intent.context.pointer.copy(fit = Fit(10, 0, 1000, 800)))
        a.message(current, AnchorMessage.Offer(2, offer.intent.copy(context = changed), offer.highlight))
        check(!a.anchorRegistration.active && "OPEN" !in Calls.events)
    }
    scenario("genuine loss during pointer bounds callback cancels the reserved chooser") {
        val a = ActivityNavigationWiring()
        a.pointer.onBounds = { a.pointer.onBounds = null; a.retireInput() }
        a.showChooser()
        check(!a.anchorRegistration.active && a.anchorOffer == null && a.anchorOverlay.visibility == View.GONE)
        check(a.choices == listOf(AnchorRegistrationAction.CANCEL))
        check(a.session!!.taps == 0 && a.session!!.scrolls == 0)
    }
    scenario("new projected demand retires registration before republishing projected presentation") {
        val a = ActivityNavigationWiring(); a.showChooser(); Calls.events.clear()
        a.owner!!.onPresentation = { check(!a.anchorRegistration.active) }
        a.demand(ReceiverDemand("activation-two", "owner", 2, ReceiverIncarnation("receiver", 1), ActivationGoal.PROJECTED))
        check(a.choices == listOf(AnchorRegistrationAction.CANCEL) && !a.anchorRegistration.active)
        check(Calls.events.indexOf("anchor-CANCEL") < Calls.events.indexOf("presentation"))
    }
    println("PASS $checks actual Activity navigation scenarios; modeled Android/session boundaries, no physical proof")
}
