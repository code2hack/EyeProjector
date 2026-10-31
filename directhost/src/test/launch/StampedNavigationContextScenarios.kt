package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.directhost.input.FunctionIntentRoute
import com.code2hack.eyeprojector.directhost.input.FunctionWork
import com.code2hack.eyeprojector.protocol.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun Fixture.events(): List<ReceiverInput> = DataInputStream(peer.sink.toByteArray().inputStream()).use { input ->
    buildList { while (input.available() > 0) Wire.readFrame(input).let {
        if (it.channel == Wire.RECEIVER_INPUT) add(ReceiverInput.from(it.payload))
    } }
}
private fun Fixture.presses(): Int {
    val merger = get("phoneInput")!!
    return (merger.javaClass.getDeclaredField("presses").apply { isAccessible = true }.get(merger) as Map<*, *>).size
}
private fun Fixture.noPoint(projected: Boolean = false, revision: Long = 1, pointRevision: Long = revision,
    pointerProjected: Boolean = projected): SessionUsabilitySnapshot {
    fusionGeometry()
    val old = (session as CapturedProjection).receiverUsability().fact
    val mode = if (projected) ReceiverUsabilityMode.PROJECTED else ReceiverUsabilityMode.LOCAL_PANEL
    receive(Frame(Wire.RECEIVER_USABILITY, payload = ReceiverUsability(ownerEpoch,
        old?.incarnation ?: ReceiverIncarnation("stamped-nav", 1), (old?.revision ?: 0) + 1, revision, mode).bytes()))
    receive(Frame(Wire.HEAD_POINTER, payload = HeadPointerState(pointRevision, 10, false, pointerProjected,
        0f, 0f, Fit(0, 0, 480, 640), Geometry(480, 640)).bytes()))
    type.getDeclaredMethod("prepareHidRegion").apply { isAccessible = true }.invoke(session)
    return (session as CapturedProjection).receiverUsability().also { check(it.regionId != null) }
}
private fun Fixture.edge(route: Long, action: PhoneHidAction, context: SessionUsabilitySnapshot,
    phase: PhoneHidPhase = PhoneHidPhase.DOWN, at: Long = android.os.SystemClock.uptimeMillis(),
    down: Long = at, region: String = context.regionId!!, word: Long = context.word) {
    (session as CapturedProjection).phoneHidStampedEdge(route,
        PhoneHidEdge(action, phase, 7, 44, 55, down, at), region, word)
}
private fun Fixture.drain() {
    val done = CountDownLatch(1)
    (get("functionWork") as FunctionWork).submit { done.countDown() }
    check(done.await(2, TimeUnit.SECONDS))
}

fun main(args: Array<String>) {
    val paired = args.single() == "paired"
    val label = if (paired) "PAIRED117+116 SOURCE JOIN" else "STANDALONE117 BASELINE"
    for (projected in listOf(false, true)) for (action in listOf(PhoneHidAction.BACK_HOME, PhoneHidAction.LOCK)) {
        Fixture().use { f ->
            val context = f.noPoint(projected)
            val at = android.os.SystemClock.uptimeMillis()
            f.edge(1, action, context, at = at)
            check(f.presses() == if (paired) 1 else 0)
            f.edge(1, action, context, PhoneHidPhase.UP, at + 1, at)
            if (paired && action == PhoneHidAction.BACK_HOME) {
                // The actual shared Back/Home recognizer's existing500ms interval, not a new product timer.
                val passed = CountDownLatch(1)
                f.timer.schedule({ passed.countDown() }, 600, TimeUnit.MILLISECONDS)
                check(passed.await(2, TimeUnit.SECONDS)); f.drain()
            }
            val stock = (f.control.sink as ByteArrayOutputStream).size()
            val received = f.events().map { it.kind }
            if (!paired) check(stock == 0 && received.isEmpty())
            else if (action == PhoneHidAction.LOCK) check(received == listOf(ReceiverInputKind.LOCK) && stock == 0)
            else if (projected) check(stock > 0 && received.isEmpty())
            else check(received == listOf(ReceiverInputKind.BACK) && stock == 0)
            println("PASS $label actual stamped Session $action no-position projected=$projected ${if (paired) "admitted/completed" else "retains inherited merger rejection"}")
        }
    }
    if (!paired) return
    Fixture().use { f ->
        val context = f.noPoint()
        var functions = 0
        (f.session as FunctionIntentRoute).functionIntents.activate = { functions++ }
        (f.session as FunctionIntentRoute).functionIntents.register = { functions++ }
        for ((index, action) in listOf(PhoneHidAction.TAP, PhoneHidAction.SCROLL_DOWN,
            PhoneHidAction.SCROLL_UP, PhoneHidAction.FUNCTION).withIndex()) {
            val at = android.os.SystemClock.uptimeMillis()
            f.edge(index.toLong(), action, context, at = at)
            check(f.presses() == 0)
            f.edge(index.toLong(), action, context, PhoneHidPhase.UP, at + 1, at)
        }
        f.drain()
        check(functions == 0 && f.events().isEmpty() && (f.control.sink as ByteArrayOutputStream).size() == 0)
        println("PASS $label actual stamped Session no-position Tap/Scroll/Function remain rejected with no contact/action output")
    }
    for (wrong in listOf("region", "stamp", "zero-stamp", "point-revision", "point-mode", "stale-word", "NONE", "retired")) {
        Fixture().use { f ->
            val original = f.noPoint(pointRevision = if (wrong == "point-revision") 2 else 1,
                pointerProjected = wrong == "point-mode")
            val fact = original.fact!!
            if (wrong == "stale-word" || wrong == "NONE") f.receive(Frame(Wire.RECEIVER_USABILITY, payload = fact.copy(
                revision = fact.revision + 1, inputRevision = 2,
                mode = if (wrong == "NONE") ReceiverUsabilityMode.NONE else fact.mode).bytes()))
            if (wrong == "retired") f.closeSession()
            val at = android.os.SystemClock.uptimeMillis()
            for ((index, action) in listOf(PhoneHidAction.BACK_HOME, PhoneHidAction.LOCK).withIndex()) {
                f.edge(index.toLong(), action, original, at = at,
                    region = if (wrong == "region") "unrelated-original" else original.regionId!!,
                    word = when (wrong) { "stamp" -> original.word + 4; "zero-stamp" -> original.word and 3; else -> original.word })
                check(f.presses() == 0)
            }
            check(f.events().isEmpty() && (f.control.sink as ByteArrayOutputStream).size() == 0)
        }
        println("PASS $label actual stamped Session rejects $wrong context for Back/Home and Lock")
    }
    Fixture("owner").use { old -> Fixture("owner").use { fresh ->
        val wrong = old.noPoint()
        fresh.noPoint()
        fresh.edge(1, PhoneHidAction.LOCK, wrong)
        check(fresh.presses() == 0 && fresh.events().isEmpty())
        println("PASS $label same-owner successor Session rejects the original region/word identity")
    } }
    println("PASS $label precise stamped navigation composition; immutable116 Merger/BackHome test inputs only; final116 merged-pair recheck required; no device/permission proof")
}
