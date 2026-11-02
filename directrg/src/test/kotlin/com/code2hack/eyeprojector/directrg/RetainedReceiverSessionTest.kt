package com.code2hack.eyeprojector.directrg

import android.media.MediaCodec
import android.opengl.EGL14
import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.DataInputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual owner, session, decoder and GL adapter. Android/GPU/TLS boundaries are doubles. */
class RetainedReceiverSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val size = Geometry(480, 640)
    internal class View : ReceiverSessionOwner.View {
        val states = mutableListOf<ReceiverSessionOwner.Snapshot>()
        val inputs = mutableListOf<ReceiverInput>()
        override fun state(snapshot: ReceiverSessionOwner.Snapshot) { states.add(snapshot) }
        override fun input(source: ProjectionSession, event: ReceiverInput) { inputs.add(event) }
    }
    internal inner class Fixture(withFeature: Boolean = false) : AutoCloseable {
        val feature = if (withFeature) ReceiverActivationStore(temporary.newFolder().resolve("feature.properties")).also {
            it.startIncarnation()
            val first = ReceiverLaunchScope("old-scope", "old-trigger", 7)
            it.launch(first, "old-admission")
            it.accept(ActivationReply(ActivationDirective("H1", true, 1, "old-owner"), TriggerReceipt("old-trigger", "H1", TriggerDisposition.MAPPED)))
            val current = ReceiverLaunchScope("current-scope", "current-trigger", 8)
            it.launch(current, "current-admission")
            it.accept(ActivationReply(ActivationDirective("H2", true, 1, "retained-phone-owner"), TriggerReceipt("current-trigger", "H2", TriggerDisposition.MAPPED)))
        } else null
        val queue = LinkedBlockingQueue<() -> Unit>()
        val commands = LinkedBlockingQueue<SessionControl.Request>()
        val resources = mutableListOf<Boolean>()
        val background = mutableListOf<ReceiverInput>()
        lateinit var observation: (SessionState) -> Unit
        lateinit var output: RetainedVideoOutput
        val codec = MediaCodec()
        val bytes = ByteArrayOutputStream()
        val owner: ReceiverSessionOwner
        val view = View()
        val first = Surface()
        init {
            EGL14.failCurrent = false; EGL14.failSwap = false; EGL14.failQuery = false; EGL14.error = 0
            EGL14.swaps.clear()
            MediaCodec.created = codec
            val pts = LinkedBlockingQueue<Long>()
            var renderedPts = 0L
            codec.queuedInput = { value, flags -> if (flags == 0) pts.add(value) }
            codec.output = { info -> pts.poll()?.let { renderedPts = it; info.presentationTimeUs = it; 0 } ?: -1 }
            codec.releasedOutput = { render -> if (render) {
                codec.configuredSurface!!.texture!!.frame(renderedPts)
                codec.frameListener!!.onFrameRendered(codec, renderedPts, 0)
            } }
            owner = ReceiverSessionOwner(temporary.newFolder().resolve("session"), null,
                { queue.add(it) }, {}, resources::add, { _, event -> background.add(event) },
                { presented, failed -> try { RetainedVideoOutput(presented, failed).also { output = it } }
                    catch (error: Exception) { throw AssertionError("renderer construction", error) } },
                startSession = { session ->
                    session.javaClass.getDeclaredField("output").apply { isAccessible = true }.set(session, DataOutputStream(bytes))
                    try { session.receive(Frame(Wire.GEOMETRY, payload = size.bytes())) }
                    catch (error: Exception) { throw AssertionError("decoder construction", error) }
                }, feature = feature, clientFactory = { state, unavailable ->
                    observation = state
                    PairedSessionClient(temporary.newFolder(), state, unavailable,
                        { request, _ -> commands.add(request); null }, { queue.add(it) }, feature = feature)
                })
            owner.attach(view, first)
            observation(SessionState("retained-phone-owner", SessionPhase.AVAILABLE))
            assertNotNull("owner failed setup: ${owner.snapshot()}", owner.session)
        }
        fun pumpUntil(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!condition() && System.nanoTime() < deadline) queue.poll(20, TimeUnit.MILLISECONDS)?.invoke()
            assertTrue(message, condition())
        }
        fun pump() { while (true) (queue.poll() ?: return).invoke() }
        fun frame(pts: Long = 0) { owner.session!!.receive(Frame(Wire.VIDEO, pts, byteArrayOf(1))) }
        fun streaming() { frame(); pumpUntil("current window presented") { owner.snapshot().state == "STREAMING" } }
        override fun close() { EGL14.failCurrent = false; EGL14.failSwap = false; EGL14.failQuery = false; owner.close(); pump() }
    }

    private fun mode(session: ProjectionSession): ReceiverUsability = session.javaClass.getDeclaredField("sourceUsability").apply { isAccessible = true }.get(session) as ReceiverUsability

    private fun drainControls(session: ProjectionSession) {
        for(name in listOf("controls","scaleControls")) {
            val lane=session.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(session) as OrderedControlDispatcher
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(lane.snapshot().let{it.pending>0||it.inFlight>0}&&System.nanoTime()<until)Thread.yield()
            assertEquals(0L,lane.snapshot().pending);assertEquals(0L,lane.snapshot().inFlight)
        }
    }
    private fun outputFrames(bytes: ByteArrayOutputStream)=DataInputStream(bytes.toByteArray().inputStream()).use { input ->
        buildList{while(input.available()>0)add(Wire.readFrame(input))}
    }

}
