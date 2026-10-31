package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

internal fun Fixture.fusionGeometry() {
    val size = Geometry(480, 640)
    @Suppress("UNCHECKED_CAST")
    (get("geometry") as AtomicReference<Geometry?>).set(size)
    (get("inputControls") as PhoneTouchDispatcher).geometry(size)
}

internal fun Fixture.receive(frame: Frame) {
    val bytes = ByteArrayOutputStream().also { Wire.writeFrame(DataOutputStream(it), frame) }.toByteArray()
    val actual = Wire.readFrame(DataInputStream(bytes.inputStream()))
    type.getDeclaredMethod("receiveControl", Frame::class.java).apply { isAccessible = true }.invoke(session, actual)
}

internal fun Fixture.pointer(projected: Boolean = true, revision: Long = 1, sequence: Long = 1) {
    // Matched Receiver producer: explicit source authority precedes its pointer record.
    val original=(session as CapturedProjection).receiverUsability().fact
    val mode=if(projected) ReceiverUsabilityMode.PROJECTED else ReceiverUsabilityMode.LOCAL_PANEL
    if(original==null || original.mode!=mode || original.inputRevision!=revision)
        receive(Frame(Wire.RECEIVER_USABILITY,payload=ReceiverUsability(ownerEpoch,
            original?.incarnation ?: ReceiverIncarnation("launch-fixture",1),(original?.revision ?: 0)+1,revision,mode).bytes()))
    receive(Frame(Wire.HEAD_POINTER, payload = HeadPointerState(revision, sequence, true, projected,
        120f, 230f, Fit(20, 30, 480, 640), Geometry(480, 640)).bytes()))
}

internal fun Fixture.hid(id: Long, edge: PhoneHidEdge) = (session as CapturedProjection).phoneHidEdge(id, edge)

fun fusionScenarios() {
    Fixture().use { f ->
        f.fusionGeometry(); f.pointer()
        val at = android.os.SystemClock.uptimeMillis()
        val d = PhoneHidEdge(PhoneHidAction.TAP, PhoneHidPhase.DOWN, 1, 66, 88, at, at)
        f.hid(10, d)
        f.receive(Frame(Wire.HEAD_POINTER, payload = HeadPointerState(1, 2, true, true,
            130f, 240f, Fit(20, 30, 480, 640), Geometry(480, 640)).bytes()))
        f.hid(10, d.copy(phase = PhoneHidPhase.UP, eventTimeMs = at + 30))
        val bytes = (f.control.sink as ByteArrayOutputStream).toByteArray()
        check(bytes.size == 32 * 3)
        check(listOf(bytes[1].toInt(), bytes[33].toInt(), bytes[65].toInt()) == listOf(0, 2, 1))
        check(DataInputStream(bytes.copyOfRange(10, 18).inputStream()).run { readInt() == 100 && readInt() == 200 })
        var rejected = false
        try { f.receive(Frame(Wire.RECEIVER_INPUT, payload = ReceiverInput(ReceiverInputKind.PANEL, 1).bytes())) }
        catch (_: java.lang.reflect.InvocationTargetException) { rejected = true }
        check(rejected)
        println("PASS actual Session authenticated-frame consumer plus captured Phone edges completes exact stock DOWN/MOVE/UP and rejects reversed direction")
    }
    for (stockBack in listOf(false, true)) Fixture().use { f ->
        f.fusionGeometry(); f.pointer(projected = stockBack)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocked = object : OutputStream() {
            override fun write(value: Int) {
                entered.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                if (f.peer.released || f.control.released) throw IOException("old closed transport")
            }
        }
        (f.session as com.code2hack.eyeprojector.directhost.input.FunctionIntentRoute).functionIntents.activate = { blocked.write(1) }
        try {
            val at = android.os.SystemClock.uptimeMillis()
            repeat(if (stockBack) 1 else 2) { n ->
                val d = PhoneHidEdge(PhoneHidAction.FUNCTION, PhoneHidPhase.DOWN, 1, 67, 90, at + n * 50, at + n * 50)
                f.hid(n.toLong(), d); f.hid(n.toLong(), d.copy(phase = PhoneHidPhase.UP, eventTimeMs = d.eventTimeMs + 1))
            }
            check(entered.await(2, TimeUnit.SECONDS))
            // The same actual Bridge-supplied deadline timer remains available while Function I/O is stuck.
            check(f.timer.submit<Boolean> { true }.get(1, TimeUnit.SECONDS))
            val close = Executors.newSingleThreadExecutor()
            try { close.submit { f.closeSession() }.get(2, TimeUnit.SECONDS) } finally { close.shutdownNow() }
            check(f.peer.released && f.video.released && f.control.released && !f.child.alive)
            check(release.count == 1L)
            println("PASS actual Session Function typed consumer (${if (stockBack) "projected" else "local"}) blockage leaves shared timer and existing cleanup reachable")
        } finally { release.countDown() }
    }
}
