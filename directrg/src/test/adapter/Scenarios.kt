package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import com.code2hack.eyeprojector.protocol.Touch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Actual production dataflow; only Android acquisition/scheduling and destination sinks are modeled. */
private class Fixture {
    enum class Route { LOCAL_PANEL, PROJECTED }
    val context = Context()
    val pointer = PointerOverlay(context)
    private val wiring = ActivityInputWiring(pointer)
    val heldInput get() = wiring.heldInput
    val local = mutableListOf<HeldPointerDispatcher.Event>()
    val projected = mutableListOf<HeldPointerDispatcher.Event>()
    private var route = Route.LOCAL_PANEL
    private lateinit var gestures: PadGestureRecognizer
    lateinit var padContact: NativePadContact
        private set
    private var observerDepth = 0
    var maximumObserverDepth = 0
        private set

    init {
        at(1000)
        wiring.cancel = ::cancelInput
        wiring.installObserver()
        val productionObserver = checkNotNull(pointer.onPositionChanged)
        pointer.onPositionChanged = {
            observerDepth++
            maximumObserverDepth = maxOf(maximumObserverDepth, observerDepth)
            try {
                // Test failure bound, not a product reentrancy guard or a way to skip notifications.
                check(observerDepth < 8) { "recursive pointer observer reached test depth bound 8" }
                productionObserver()
            } finally {
                observerDepth--
            }
        }
        wiring.heldInput = HeldPointerDispatcher(wiring.heldPosition()) {
            val captured = if (route == Route.LOCAL_PANEL) local else projected
            { event -> captured.add(event); true }
        }
        gestures = PadGestureRecognizer(300, 500) { action ->
            if (action is PadGestureRecognizer.Action.Contact) heldInput.accept(action.event)
        }
        padContact = NativePadContact(context, wiring.admissionPosition(), { event ->
            gestures.accept(event, SystemClock.uptimeMillis())
        }, ::cancelInput)
        pointer.bounds(0f, 0f, 1000f, 1000f, 0)
        padContact.start()
        pointer.start()
        sample(1001, 0.0)
    }

    fun at(timeMs: Long) { SystemClock.nowNs = timeMs * 1_000_000 }

    fun sample(timeMs: Long, yawDegrees: Double) {
        at(timeMs)
        val radians = Math.toRadians(yawDegrees) / 2
        SensorHeadPoseSource.latest.emit(RotationSample(SystemClock.elapsedRealtimeNanos(),
            0f, sin(radians).toFloat(), 0f, cos(radians).toFloat()))
    }

    fun frame(timeMs: Long) { at(timeMs); pointer.runAnimationFrame() }

    fun edge(action: Int, timeMs: Long, downTimeMs: Long = 1010) {
        at(timeMs)
        check(padContact.accept(MotionEvent(action, timeMs, downTimeMs)))
    }

    fun down(timeMs: Long = 1010) = edge(MotionEvent.ACTION_DOWN, timeMs, timeMs)

    fun cancelInput() {
        // Same cancellation ordering as ReceiverActivity's common hook, for the adapters under test.
        if (::gestures.isInitialized) gestures.cancel(SystemClock.uptimeMillis())
        if (wiring.heldInputInitialized) heldInput.cancel(SystemClock.uptimeMillis())
        if (::padContact.isInitialized) padContact.clear()
    }

    fun switchContext(timeMs: Long) {
        at(timeMs)
        cancelInput() // Existing route is cancelled before changing the route supplier.
        route = Route.PROJECTED
    }

    fun localPhases() = local.map { it.phase }
    fun projectedPhases() = projected.map { it.phase }
    fun checkNoRecursion() = check(maximumObserverDepth == 1) { "observer depth $maximumObserverDepth" }
}

private var failures = 0

private fun scenario(name: String, run: (Fixture) -> Unit) {
    try {
        run(Fixture())
        println("PASS $name")
    } catch (error: Throwable) {
        failures++
        println("FAIL $name: $error")
        error.printStackTrace()
    }
}

fun main() {
    scenario("held head publication/frame reaches MOVE and UP without recursive observation") { f ->
        f.down()
        val origin = f.local.single().position
        f.sample(1020, 6.0)
        f.frame(1060)
        check(f.local.any { it.phase == Touch.Phase.MOVE && it.position != origin })
        f.edge(MotionEvent.ACTION_UP, 1080)
        check(f.localPhases().first() == Touch.Phase.DOWN && f.localPhases().last() == Touch.Phase.UP)
        check(f.localPhases().count { it == Touch.Phase.DOWN } == 1)
        check(f.localPhases().count { it == Touch.Phase.UP } == 1)
        check(f.local.all { it.contactId == 1010L })
        check(!f.heldInput.holding && f.projected.isEmpty())
        f.checkNoRecursion()
    }
    scenario("synthetic pad MOVE and UP advance final head coordinates at admission without a display frame") { f ->
        f.down()
        val origin = f.local.single().position
        f.sample(1020, 8.0)
        f.edge(MotionEvent.ACTION_MOVE, 1040)
        val beforeUp = f.local.last().position
        check(beforeUp != origin && f.local.last().phase == Touch.Phase.MOVE)
        f.sample(1050, 8.0)
        f.edge(MotionEvent.ACTION_UP, 1080)
        val up = f.local.last()
        check(up.phase == Touch.Phase.UP && up.position == f.pointer.position)
        check(up.position != beforeUp)
        check(abs(up.position.x - origin.x) > abs(beforeUp.x - origin.x))
        check(f.local.all { it.contactId == 1010L })
        check(f.local.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs })
        check(!f.heldInput.holding)
        f.checkNoRecursion()
    }
    scenario("stationary synthetic pad contact emits exactly one DOWN and final UP") { f ->
        f.down()
        f.edge(MotionEvent.ACTION_UP, 1030)
        check(f.localPhases() == listOf(Touch.Phase.DOWN, Touch.Phase.UP))
        check(f.local.first().position == f.local.last().position)
        check(f.padContact.downs == 1L && f.padContact.releases == 1L)
        check(!f.heldInput.holding)
        f.checkNoRecursion()
    }
    scenario("delayed expiry callback still cancels on synthetic pad UP admission and never replays") { f ->
        f.down()
        f.at(1300)
        check(f.pointer.position.available) // No expiry/frame callback has run yet.
        f.edge(MotionEvent.ACTION_UP, 1300)
        check(f.localPhases() == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL))
        check(!f.heldInput.holding && !f.pointer.position.available)
        f.sample(1310, 0.0)
        f.frame(1320)
        f.edge(MotionEvent.ACTION_UP, 1330)
        check(f.localPhases() == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL))
        check(!f.heldInput.holding)
        f.checkNoRecursion()
    }
    scenario("registered device removal cancels captured contact once without stale UP replay") { f ->
        f.down()
        f.at(1040)
        f.context.inputManager.removeDevice(7)
        check(f.padContact.deviceLosses == 1L && !f.padContact.holding)
        check(f.localPhases() == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL))
        f.sample(1050, 4.0)
        f.frame(1060)
        f.edge(MotionEvent.ACTION_UP, 1070)
        check(f.localPhases() == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL))
        check(f.projected.isEmpty() && !f.heldInput.holding)
        f.checkNoRecursion()
    }
    scenario("context switch cancels old captured route and only next DOWN uses new route") { f ->
        f.down()
        f.switchContext(1040)
        check(f.localPhases() == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL))
        check(f.projected.isEmpty())
        f.edge(MotionEvent.ACTION_UP, 1050)
        f.sample(1060, 5.0)
        f.frame(1100)
        check(f.projected.isEmpty() && !f.heldInput.holding)
        f.down(1110)
        f.edge(MotionEvent.ACTION_UP, 1130, 1110)
        check(f.localPhases() == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL))
        check(f.projectedPhases().first() == Touch.Phase.DOWN && f.projectedPhases().last() == Touch.Phase.UP)
        check(f.projected.all { it.contactId == 1110L })
        check(f.projectedPhases().count { it == Touch.Phase.DOWN } == 1)
        check(f.projectedPhases().count { it == Touch.Phase.UP } == 1)
        check(!f.heldInput.holding)
        f.checkNoRecursion()
    }
    check(failures == 0) { "$failures continuous-input adapter scenarios failed" }
    println("6 continuous-input adapter scenarios passed; host API doubles, no full Activity/device/firmware claim")
}
