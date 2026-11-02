package com.code2hack.eyeprojector.directrg

import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import java.io.*
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Real owner/Session/writer/grammar; external renderer, TLS connection and widgets are doubled. */
internal class RegistrationReceiverRig : AutoCloseable {
    val directory = Files.createTempDirectory("eyeprojector-registration-receiver-").toFile()
    val feature = ReceiverActivationStore(File(directory, "feature.properties"))
    val wiring: RegistrationActivityWiring
    val owner: ReceiverSessionOwner
    val session get() = owner.session!!
    val output = ByteArrayOutputStream()
    private val queue = LinkedBlockingQueue<() -> Unit>()
    private var offset = 0
    val produced = mutableListOf<FunctionIntentKind>()
    private lateinit var grammar: FunctionGestureRecognizer
    init {
        feature.startIncarnation()
        val scope = ReceiverLaunchScope("registration-scope", "registration-trigger", 7)
        feature.launch(scope, "registration-admission")
        feature.accept(ActivationReply(ActivationDirective("registration-H", true, 1, "session-owner"),
            TriggerReceipt(scope.trigger, "registration-H", TriggerDisposition.MAPPED)))
        wiring = RegistrationActivityWiring(feature)
        lateinit var observe: (SessionState) -> Unit
        owner = ReceiverSessionOwner(File(directory, "session"), null, { queue.add(it) }, {}, {}, { _, _ -> },
            outputFactory = { _, _ -> object : ReceiverVideoOutput {
                override val surface = Surface()
                override fun attach(next: Surface?, generation: Long) = Unit
                override fun invalidate() = Unit
                override fun redraw() = Unit
                override fun close() = Unit
            } }, startSession = { s ->
                set(s, "output", DataOutputStream(output)); set(s, "geometry", Geometry(480, 640)); set(s, "controlReady", true)
                val readiness = get(s, "videoReadiness") as VideoReadiness
                val epoch = readiness.beginReplacement()!!
                check(readiness.awaitFrame(epoch, System.nanoTime()) && readiness.rendered(epoch))
            }, feature = feature, clientFactory = { state, unavailable ->
                observe = state
                PairedSessionClient(directory, state, unavailable, { _, _ -> null }, { queue.add(it) }, feature = feature)
            })
        wiring.owner = owner
        try {
            owner.attach(wiring.sessionView, Surface())
            observe(SessionState("session-owner", SessionPhase.AVAILABLE)); pump()
            check(owner.session != null)
            set(owner, "mediaState", "STREAMING"); set(owner, "displayed", true)
            set(owner, "displayedGeneration", (get(owner, "windowGeneration") as AtomicLong).get())
            wiring.session = session; wiring.inputGeneration = owner.inputRevision
            owner.presentation(wiring.sessionView, true, false); wiring.publish(); drain()
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
        error("Owned Receiver test writers did not finish")
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
    fun register() {
        val context = session.captureFunctionContext(HeadPointerState(wiring.inputGeneration, 1, true, true,
            320f, 420f, Fit(0, 0, 480, 640), Geometry(480, 640)))!!
        val original = session
        grammar = FunctionGestureRecognizer({ 1000 }, wiring::captureContext, { _, _ -> {} }, { gesture ->
            produced.add(gesture.kind)
            val intent = FunctionIntent(gesture.kind, FunctionSource.GLASSES_NATIVE, gesture.gestureId, gesture.sequence,
                gesture.original as FunctionIntentContext)
            check(original.functionIntent(CapturedFunctionIntent(intent, original)))
        }, capture = { context })
        grammar.accept(FunctionGestureRecognizer.Report.DOWN, 1000)
        grammar.accept(FunctionGestureRecognizer.Report.LONG, 1800)
        drain()
        check(produced == listOf(FunctionIntentKind.REGISTER))
    }
    fun releaseFunction() {
        grammar.accept(FunctionGestureRecognizer.Report.UP, 1810)
        grammar.accept(FunctionGestureRecognizer.Report.CLICK, 1820)
        drain()
        check(produced == listOf(FunctionIntentKind.REGISTER))
    }
    override fun close() { owner.close(); pump(); directory.deleteRecursively() }
}
