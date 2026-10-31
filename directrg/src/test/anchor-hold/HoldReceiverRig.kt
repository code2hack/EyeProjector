package com.code2hack.eyeprojector.directrg

import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import java.io.*
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Actual owner/Session/grammar/Activity methods; only renderer, TLS and Android widgets are boundaries. */
internal class HoldReceiverRig : AutoCloseable {
    val directory = Files.createTempDirectory("eyeprojector-held-ui-").toFile()
    val feature = ReceiverActivationStore(File(directory, "feature.properties"))
    val wiring: HeldActivityWiring
    val owner: ReceiverSessionOwner
    private var original: ProjectionSession? = null
    val session get() = original ?: owner.session!!
    val output = ByteArrayOutputStream()
    private val queue = LinkedBlockingQueue<() -> Unit>()
    private var offset = 0
    init {
        feature.startIncarnation()
        val scope = ReceiverLaunchScope("held-ui-scope", "held-ui-trigger", 7)
        feature.launch(scope, "held-ui-admission")
        feature.accept(ActivationReply(ActivationDirective("held-ui-H", true, 1, "session-owner"),
            TriggerReceipt(scope.trigger, "held-ui-H", TriggerDisposition.MAPPED)))
        wiring = HeldActivityWiring(feature, directory)
        lateinit var observe: (SessionState) -> Unit
        owner = ReceiverSessionOwner(File(directory, "session"), null, { queue.add(it) }, {}, {}, { _, _ -> },
            outputFactory = { _, _ -> object : ReceiverVideoOutput {
                override val surface = Surface()
                override fun attach(next: Surface?, generation: Long) = Unit
                override fun invalidate() = Unit
                override fun redraw() = Unit
                override fun close() = Unit
            } }, startSession = { source ->
                set(source, "output", DataOutputStream(output)); set(source, "geometry", Geometry(480, 640))
                set(source, "controlReady", true)
                val readiness = get(source, "videoReadiness") as VideoReadiness
                val epoch = readiness.beginReplacement()!!
                check(readiness.awaitFrame(epoch, System.nanoTime()) && readiness.rendered(epoch))
            }, feature = feature, clientFactory = { state, unavailable ->
                observe = state
                PairedSessionClient(directory, state, unavailable, { _, _ -> null }, { queue.add(it) }, feature = feature)
            })
        wiring.owner = owner
        try {
            owner.attach(wiring.view, Surface())
            observe(SessionState("session-owner", SessionPhase.AVAILABLE)); pump()
            original = checkNotNull(owner.session)
            set(owner, "mediaState", "STREAMING"); set(owner, "displayed", true)
            set(owner, "displayedGeneration", (get(owner, "windowGeneration") as AtomicLong).get())
            wiring.snapshot(owner.snapshot())
            owner.presentation(wiring.view, true, false); wiring.publish(); drain()
            check(session.observationUsability()?.mode == ReceiverUsabilityMode.PROJECTED)
        } catch (failure: Throwable) {
            runCatching { owner.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
    private fun get(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun set(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    fun drain() {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < until) {
            pump()
            val busy = listOf("controls", "scaleControls").any {
                (get(session, it) as OrderedControlDispatcher).snapshot().let { it.pending > 0 || it.inFlight > 0 }
            }
            if (!busy) return
            Thread.yield()
        }
        error("Original Receiver writers did not finish")
    }
    fun pump() { while (true) (queue.poll() ?: return).invoke() }
    fun frames(): List<Frame> {
        drain()
        return synchronized(get(session, "outputLock")) {
            val bytes = output.toByteArray()
            val input = DataInputStream(bytes.copyOfRange(offset, bytes.size).inputStream())
            buildList { while (input.available() > 0) add(Wire.readFrame(input)); offset = bytes.size }
        }
    }
    fun receive(frame: Frame) { session.receive(frame); pump() }
    override fun close() { owner.close(); pump(); directory.deleteRecursively() }
}
