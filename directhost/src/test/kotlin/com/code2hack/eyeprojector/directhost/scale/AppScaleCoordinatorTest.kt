package com.code2hack.eyeprojector.directhost.scale

import com.code2hack.eyeprojector.directhost.*
import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test

class AppScaleCoordinatorTest {
    private val context = ProjectionContext("owner", "session")
    private class Storage : AppScaleStorage {
        val values = mutableMapOf<String, Double>()
        override fun read(packageName: String) = values[packageName]
        override fun write(packageName: String, scale: Double): Boolean { values[packageName] = scale; return true }
    }
    private class Backend : AppScaleBackend {
        override fun launchNotification(carrier: com.code2hack.eyeprojector.directhost.notifications.NotificationLaunchCarrier,
            complete: (com.code2hack.eyeprojector.directhost.notifications.NotificationDispatchOutcome) -> Unit) {
            error("Notification route is outside this legacy profile fixture")
        }
        data class Call(val kind: String, val pkg: String? = null, val value: Double? = null,
            val context: ProjectionContext, val finish: (Any?) -> Unit)
        val calls = ArrayDeque<Call>()
        var foreground: String? = null
        var holdDisplay = false
        var pendingDisplay: (() -> Unit)? = null
        override fun display(expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit) {
            val reply = { complete(ProjectionDisplayState(context = expected, foregroundPackage = foreground)) }
            if (holdDisplay) pendingDisplay = reply else reply()
        }
        override fun launch(packageName: String, expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit) {
            calls.add(Call("launch", pkg = packageName, context = expected) { complete(it as ProjectionDisplayState?) })
        }
        override fun request(value: Double, expected: ProjectionContext, complete: (ScaleState?) -> Unit) {
            calls.add(Call("request", value = value, context = expected) { complete(it as ScaleState?) })
        }
        override fun read(expected: ProjectionContext, complete: (ScaleState?) -> Unit) {
            calls.add(Call("read", context = expected) { complete(it as ScaleState?) })
        }
        fun launchResult() {
            val call = calls.removeFirst(); assertEquals("launch", call.kind)
            foreground = call.pkg
            call.finish(ProjectionDisplayState(requestedPackage = call.pkg, foregroundPackage = call.pkg,
                status = ProjectionLaunchStatus.STARTED, context = call.context))
        }
        fun scaleResult(status: ScaleStatus = ScaleStatus.PENDING, geometry: Geometry? = Geometry(480, 640)): Call {
            val call = calls.removeFirst(); assertTrue(call.kind in setOf("request", "read"))
            call.finish(ScaleState(7, call.value ?: 1.0, geometry, null, status, false))
            return call
        }
    }

    @Test fun packageProfilesSurviveReloadAbsenceAndCorruptValuesIndependently() {
        val storage = Storage(); val store = AppScaleStore(storage)
        assertEquals(1.0, store.read("absent"), 0.0)
        assertTrue(store.write("a", 1.5)); assertTrue(store.write("b", .75))
        storage.values["bad"] = Double.NaN
        val reload = AppScaleStore(storage)
        assertEquals(1.5, reload.read("a"), 0.0); assertEquals(.75, reload.read("b"), 0.0)
        assertEquals(1.0, reload.read("bad"), 0.0)
        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, .01, 2000.0)) assertFalse(store.write("a", invalid))
        assertEquals(1.5, reload.read("a"), 0.0)
    }

    @Test fun storageReadFailuresAndFailedWritesRemainActionable() {
        val store = AppScaleStore(object : AppScaleStorage {
            override fun read(packageName: String): Double? = throw IllegalStateException("corrupt preference type")
            override fun write(packageName: String, scale: Double): Boolean = false
        })
        assertEquals(1.0, store.read("a"), 0.0)
        val backend = Backend(); val coordinator = AppScaleCoordinator(store, backend)
        val target = coordinator.launch("a", context) {}; backend.launchResult(); backend.scaleResult()
        coordinator.attach(target); coordinator.pinch(target, 1.25); backend.scaleResult()
        assertEquals("SAVE_FAILED", coordinator.state()!!.detail)
        coordinator.poll(target); backend.scaleResult(ScaleStatus.OBSERVED)
        assertEquals("SAVE_FAILED", coordinator.state()!!.detail)
        assertEquals(1.0, coordinator.state()!!.applied!!, 0.0)
        assertEquals(1.0, store.read("a"), 0.0)
    }

    // EP12-R1: regression for Reviewer12's matching-foreground FAILED/TIMED_OUT finding.
    @Test fun matchingForegroundRestoresSavedProfileAndKeepsOriginalCommandResult() {
        for (status in listOf(ProjectionLaunchStatus.FAILED, ProjectionLaunchStatus.TIMED_OUT)) {
            val storage = Storage().apply { values["a"] = 1.75 }
            val backend = Backend(); val coordinator = AppScaleCoordinator(AppScaleStore(storage), backend)
            val replies = mutableListOf<ProjectionDisplayState?>()
            coordinator.launch("a", context) { replies.add(it) }
            val launch = backend.calls.removeFirst()
            backend.foreground = "a"
            val result = ProjectionDisplayState(requestedPackage = "a", foregroundPackage = "a", status = status, context = context)
            launch.finish(result)
            assertEquals(listOf(result), replies)
            assertEquals(1.75, backend.calls.single().value!!, 0.0)
            assertEquals(context, backend.calls.single().context)
            backend.scaleResult(ScaleStatus.PENDING, Geometry(480, 640))
            assertEquals(1.0, coordinator.state()!!.applied!!, 0.0)
            assertEquals(1.75, storage.values["a"]!!, 0.0)
            assertTrue(backend.calls.isEmpty())
        }
    }

    @Test fun failedCommandStillRequiresMatchingContextRequestedPackageAndForeground() {
        for (status in listOf(ProjectionLaunchStatus.FAILED, ProjectionLaunchStatus.TIMED_OUT)) {
            val valid = ProjectionDisplayState(requestedPackage = "a", foregroundPackage = "a", status = status, context = context)
            for (invalid in listOf(valid.copy(context = context.copy(projectionToken = "other")),
                valid.copy(requestedPackage = "b"), valid.copy(foregroundPackage = "b"), valid.copy(foregroundPackage = null))) {
                val backend = Backend(); val coordinator = AppScaleCoordinator(AppScaleStore(Storage()), backend)
                coordinator.launch("a", context) {}
                backend.calls.removeFirst().finish(invalid)
                assertTrue(backend.calls.isEmpty())
                assertFalse(coordinator.state()!!.canPinch)
            }
        }
    }

    @Test fun matchingFailedLaunchStillRechecksFreshForegroundBeforeRestoration() {
        val storage = Storage().apply { values["a"] = 1.75 }
        val backend = Backend(); val coordinator = AppScaleCoordinator(AppScaleStore(storage), backend)
        coordinator.launch("a", context) {}
        val launch = backend.calls.removeFirst()
        backend.holdDisplay = true; backend.foreground = "a"
        launch.finish(ProjectionDisplayState(requestedPackage = "a", foregroundPackage = "a",
            status = ProjectionLaunchStatus.FAILED, context = context))
        assertNotNull(backend.pendingDisplay)
        backend.foreground = "b"; backend.pendingDisplay!!()
        assertTrue(backend.calls.isEmpty())
        assertEquals("TARGET_CHANGED", coordinator.state()!!.detail)
        assertEquals(1.75, storage.values["a"]!!, 0.0)
    }

    @Test fun oldDispatchedResizeDrainsBeforeNewAppLaunchAndCannotSaveAsTheNewPackage() {
        val storage = Storage(); val backend = Backend(); val coordinator = AppScaleCoordinator(AppScaleStore(storage), backend)
        val a = coordinator.launch("a", context) {}; backend.launchResult(); backend.scaleResult()
        coordinator.attach(a); coordinator.pinch(a, 1.5)
        assertEquals("request", backend.calls.first().kind)
        coordinator.launch("b", context) {}
        assertEquals(1, backend.calls.size) // B is not dispatched over A's outstanding resize.
        backend.scaleResult()
        assertEquals("b", backend.calls.first().pkg)
        backend.launchResult()
        assertEquals(1.0, backend.calls.first().value!!, 0.0)
        backend.scaleResult()
        assertTrue(storage.values.isEmpty()) // Stale A callback cannot write B's profile or A's preference.
        assertEquals("b", coordinator.state()!!.target.packageName)
    }

    @Test fun rapidSwitchDropsQueuedOldIntentsAndCompletesTheirCallbacksOnce() {
        val backend = Backend(); val coordinator = AppScaleCoordinator(AppScaleStore(Storage()), backend)
        val completed = mutableListOf<Pair<String, ProjectionDisplayState?>>()
        coordinator.launch("a", context) { completed.add("a" to it) }
        coordinator.launch("b", context) { completed.add("b" to it) }
        coordinator.launch("c", context) { completed.add("c" to it) }
        backend.launchResult()
        assertEquals(listOf("a", "b"), completed.map { it.first })
        assertTrue(completed.all { it.second == null })
        assertEquals("c", backend.calls.first().pkg)
        backend.launchResult(); backend.scaleResult()
        assertEquals(listOf("a", "b", "c"), completed.map { it.first })
    }

    @Test fun replacementSessionInvalidatesInFlightCallbacksAndQueuedPinches() {
        val storage = Storage(); val backend = Backend(); val coordinator = AppScaleCoordinator(AppScaleStore(storage), backend)
        val old = coordinator.launch("a", context) {}; backend.launchResult(); backend.scaleResult()
        coordinator.attach(old); coordinator.pinch(old, 1.5); coordinator.pinch(old, 1.1)
        val replacement = context.copy(projectionToken = "replacement-on-same-binder")
        coordinator.contextChanged(replacement)
        val next = coordinator.launch("b", replacement) {}
        backend.scaleResult()
        assertEquals(replacement, backend.calls.first().context)
        backend.launchResult(); backend.scaleResult()
        coordinator.pinch(old, 2.0); assertTrue(backend.calls.isEmpty())
        assertEquals(next, coordinator.state()!!.target); assertTrue(storage.values.isEmpty())
    }

    @Test fun pendingAndIntermediateGeometryAreNotExactAcknowledgmentsOrSavedObservations() {
        val storage = Storage(); val backend = Backend(); val coordinator = AppScaleCoordinator(AppScaleStore(storage), backend)
        val target = coordinator.launch("a", context) {}; backend.launchResult(); backend.scaleResult()
        coordinator.attach(target); coordinator.pinch(target, 2.0)
        backend.scaleResult(ScaleStatus.PENDING)
        assertEquals(1.0, coordinator.state()!!.applied!!, 0.0)
        assertEquals(2.0, storage.values["a"]!!, 0.0)
        coordinator.poll(target); backend.scaleResult(ScaleStatus.OBSERVED, Geometry(384, 512))
        assertEquals(1.25, coordinator.state()!!.applied!!, 0.0)
        assertEquals(2.0, storage.values["a"]!!, 0.0)
        coordinator.poll(target); backend.scaleResult(ScaleStatus.INTERRUPTED, Geometry(640, 480))
        assertEquals(1.0, coordinator.state()!!.applied!!, 0.0)
        assertEquals("INTERRUPTED", coordinator.state()!!.detail)
        assertEquals(2.0, storage.values["a"]!!, 0.0)
    }

    @Test fun rejectedUnavailableAndInvalidPinchesDoNotOverwriteTheSavedPreference() {
        val storage = Storage(); storage.values["a"] = 1.25
        val backend = Backend(); val coordinator = AppScaleCoordinator(AppScaleStore(storage), backend)
        val target = coordinator.launch("a", context) {}; backend.launchResult(); backend.scaleResult()
        coordinator.attach(target)
        for (factor in listOf(Double.NaN, 0.0, -1.0, Double.POSITIVE_INFINITY, 1e9)) coordinator.pinch(target, factor)
        assertTrue(backend.calls.isEmpty()); assertEquals("INVALID_SCALE", coordinator.state()!!.detail)
        coordinator.pinch(target, 1.2); backend.scaleResult(ScaleStatus.REJECTED)
        assertEquals(1.25, storage.values["a"]!!, 0.0)
        coordinator.pinch(target, 1.2); backend.scaleResult(ScaleStatus.UNAVAILABLE, null)
        assertNull(coordinator.state()!!.applied); assertFalse(coordinator.state()!!.canPinch)
        assertEquals(1.25, storage.values["a"]!!, 0.0)
    }

    @Test fun recreatedPanelReattachesClosingDropsQueuedPinchesAndKeepsProjectionTarget() {
        val backend = Backend(); val coordinator = AppScaleCoordinator(AppScaleStore(Storage()), backend)
        val target = coordinator.launch("a", context) {}; backend.launchResult(); backend.scaleResult()
        assertTrue(coordinator.attach(target)); coordinator.poll(target)
        coordinator.pinch(target, 1.25)
        coordinator.detach(target); backend.scaleResult()
        assertTrue(backend.calls.isEmpty())
        assertEquals(target, coordinator.state()!!.target)
        assertTrue(coordinator.attach(target)); coordinator.poll(target); backend.scaleResult()
        assertTrue(coordinator.state()!!.canPinch)
    }

    @Test fun replacementProceedsWithoutWaitingForRetiredCallbackAndLateResultCannotPublishOrSave() {
        val storage = Storage(); val backend = Backend(); val coordinator = AppScaleCoordinator(AppScaleStore(storage), backend)
        val old = coordinator.launch("a", context) {}; backend.launchResult(); backend.scaleResult()
        coordinator.attach(old); coordinator.pinch(old, 1.5)
        val held = backend.calls.removeFirst() // Deliberately never complete until the new session is usable.
        val fresh = context.copy(projectionToken = "fresh")
        coordinator.contextChanged(fresh)
        val target = coordinator.launch("b", fresh) {}
        backend.launchResult(); backend.scaleResult()
        assertEquals(target, coordinator.state()!!.target)
        val before = coordinator.state()
        held.finish(ScaleState(9, 1.5, Geometry(320, 427), null, ScaleStatus.PENDING, false))
        assertEquals(before, coordinator.state()); assertTrue(storage.values.isEmpty())
    }

    @Test fun canceledUnsentPinchDoesNotBecomeTheBasisForTheNextGesture() {
        val backend = Backend(); val coordinator = AppScaleCoordinator(AppScaleStore(Storage()), backend)
        val target = coordinator.launch("a", context) {}; backend.launchResult(); backend.scaleResult()
        coordinator.attach(target); coordinator.poll(target); coordinator.pinch(target, 1.25)
        coordinator.detach(target); backend.scaleResult()
        coordinator.attach(target); coordinator.pinch(target, 1.2)
        assertEquals(1.2, backend.calls.first().value!!, 1e-12)
    }

    @Test fun actualHomeOrAppExitBeforeResizeCannotSaveOrResizeTheOldTarget() {
        val storage = Storage(); val backend = Backend(); val coordinator = AppScaleCoordinator(AppScaleStore(storage), backend)
        val target = coordinator.launch("a", context) {}; backend.launchResult(); backend.scaleResult()
        coordinator.attach(target); backend.foreground = "projected-home"
        coordinator.pinch(target, 1.5)
        assertTrue(backend.calls.isEmpty()); assertTrue(storage.values.isEmpty())
        assertEquals("TARGET_CHANGED", coordinator.state()!!.detail)
        assertNull(coordinator.state()!!.applied)
    }

    @Test fun replacementNotificationCanReentrantlyLaunchWithoutLosingTheNewWork() {
        val backend = Backend(); val coordinator = AppScaleCoordinator(AppScaleStore(Storage()), backend)
        coordinator.launch("a", context) {}; backend.launchResult(); backend.scaleResult()
        val fresh = context.copy(projectionToken = "fresh")
        coordinator.observe { if (it == null) coordinator.launch("b", fresh) {} }
        coordinator.contextChanged(fresh)
        assertEquals("b", backend.calls.first().pkg)
        backend.launchResult(); backend.scaleResult()
        assertEquals("b", coordinator.state()!!.target.packageName)
    }

    @Test fun throwingCompletionStillReleasesTheSameSessionLane() {
        val backend = Backend(); val coordinator = AppScaleCoordinator(AppScaleStore(Storage()), backend)
        coordinator.launch("a", context) { throw IllegalStateException("client failure") }
        coordinator.launch("b", context) {}
        try { backend.launchResult(); fail("expected client exception") } catch (_: IllegalStateException) { }
        assertEquals("b", backend.calls.first().pkg)
        backend.launchResult(); backend.scaleResult()
    }

    @Test fun oldPanelCloseCannotDetachItsRecreatedReplacement() {
        val backend = Backend(); val coordinator = AppScaleCoordinator(AppScaleStore(Storage()), backend)
        val target = coordinator.launch("a", context) {}; backend.launchResult(); backend.scaleResult()
        val first = Any(); val recreated = Any()
        coordinator.attach(target, first); coordinator.attach(target, recreated)
        coordinator.detach(target, first)
        coordinator.pinch(target, 2.0, first); assertTrue(backend.calls.isEmpty())
        coordinator.pinch(target, 1.25, recreated)
        assertEquals(1.25, backend.calls.first().value!!, 0.0)
    }

    @Test fun closedPanelForegroundCallbackCannotStartANewRead() {
        val backend = Backend(); val coordinator = AppScaleCoordinator(AppScaleStore(Storage()), backend)
        val target = coordinator.launch("a", context) {}; backend.launchResult(); backend.scaleResult()
        val owner = Any(); coordinator.attach(target, owner); backend.holdDisplay = true
        coordinator.poll(target, owner); coordinator.detach(target, owner)
        backend.pendingDisplay!!()
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun idleRetiredSessionsReleaseTheirLogicalLanes() {
        val backend = Backend(); val coordinator = AppScaleCoordinator(AppScaleStore(Storage()), backend)
        repeat(20) { index ->
            val next = context.copy(projectionToken = "session-$index")
            coordinator.contextChanged(next)
            coordinator.launch("a", next) {}; backend.launchResult(); backend.scaleResult()
        }
        val lanes = AppScaleCoordinator::class.java.getDeclaredField("lanes").apply { isAccessible = true }.get(coordinator) as Map<*, *>
        assertEquals(1, lanes.size)
    }

    @Test fun homeBeforeTokenPollIsAllowedButStopWithSameOwnerInvalidatesPendingLaunch() {
        val backend = Backend(); val coordinator = AppScaleCoordinator(AppScaleStore(Storage()), backend)
        val completed = mutableListOf<ProjectionDisplayState?>()
        coordinator.launch("a", context) { completed.add(it) }
        coordinator.contextChanged(context.copy(projectionToken = ""), projectionActive = true)
        assertNotNull(coordinator.state())
        coordinator.contextChanged(context.copy(projectionToken = ""), projectionActive = false)
        assertNull(coordinator.state())
        backend.launchResult()
        assertEquals(listOf<ProjectionDisplayState?>(null), completed)
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun unconfirmedOrWrongForegroundLaunchNeverAppliesAProfile() {
        for (result in listOf<ProjectionDisplayState?>(null,
            ProjectionDisplayState(requestedPackage = "a", status = ProjectionLaunchStatus.STARTED, context = context),
            ProjectionDisplayState(requestedPackage = "a", foregroundPackage = "b", status = ProjectionLaunchStatus.STARTED, context = context))) {
            val backend = Backend(); val coordinator = AppScaleCoordinator(AppScaleStore(Storage()), backend)
            val target = coordinator.launch("a", context) {}
            backend.calls.removeFirst().finish(result)
            coordinator.attach(target); coordinator.poll(target); coordinator.pinch(target, 2.0)
            assertTrue(backend.calls.isEmpty()); assertFalse(coordinator.state()!!.canPinch)
        }
    }
}
