package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import java.io.Closeable
import java.io.File

/** Serialized on the service's main thread; no Activity is a transport/session owner. */
internal class ReceiverSessionOwner(
    private val directory: File,
    private val context: Context?,
    private val deliver: (() -> Unit) -> Unit,
    private val changed: () -> Unit,
    private val resources: (Boolean) -> Unit,
    private val authenticatedControl: (ProjectionSession, ReceiverInput) -> Unit,
    private val outputFactory: ((Surface, Long) -> Unit, (Exception) -> Unit) -> ReceiverVideoOutput = { shown, failed ->
        RetainedVideoOutput(shown, failed)
    },
    private val startSession: (ProjectionSession) -> Unit = { it.start() },
    clientFactory: ((SessionState) -> Unit, () -> Unit) -> PairedSessionClient = { state, unavailable ->
        PairedSessionClient(directory, state, unavailable, deliver = deliver)
    },
) : Closeable {
    interface View {
        fun state(snapshot: Snapshot)
        fun input(source: ProjectionSession, event: ReceiverInput)
    }
    data class Snapshot(val session: ProjectionSession?, val epoch: String?, val peer: SessionState?,
        val requested: Boolean, val state: String, val geometry: Geometry?)

    private val attachment = SessionAttachment()
    private var listener: View? = null
    private var window: Surface? = null
    private var windowGeneration = 0L
    private var output: ReceiverVideoOutput? = null
    private var closed = false
    private var peer: SessionState? = null
    private var requested = false
    private var mediaState = "DISCONNECTED"
    private var displayed = false
    private var resourcesHeld = false
    var inputRevision = 0L
        private set
    fun advanceInputRevision(): Long = ++inputRevision
    var session: ProjectionSession? = null
        private set
    private val client = clientFactory(::observePeer) {
        if (!closed && session == null) {
            requested = false; mediaState = "PEER_UNAVAILABLE"; publish()
        }
    }

    fun start() = client.start()
    fun snapshot() = Snapshot(session, attachment.epoch, peer, requested,
        if (session != null && mediaState == "STREAMING" && !displayed) "DISPLAY_DETACHED" else mediaState, session?.geometry)
    private fun publish() { changed(); listener?.state(snapshot()) }

    fun attach(view: View, surface: Surface?) {
        if (closed) return
        listener = view
        attachSurface(view, surface)
    }

    fun attachSurface(view: View, surface: Surface?) {
        if (closed || listener !== view) return
        session?.presentation(false)
        displayed = false; window = surface; windowGeneration++
        try { output?.attach(surface, windowGeneration) }
        catch (error: Exception) { failOutput(); return }
        publish()
    }

    fun detach(view: View) {
        if (listener !== view) return
        attachSurface(view, null)
        listener = null
    }

    fun connect() {
        if (closed || session != null || requested) return
        requested = true; mediaState = "CONNECTING"; publish(); client.connect()
    }

    private fun observePeer(state: SessionState) {
        if (closed) return
        if (state.phase == SessionPhase.UNKNOWN) return
        peer = state
        if (session != null && (attachment.epoch != state.epoch || !state.phase.active)) disconnect(false)
        if (state.phase.active && session == null) { requested = true; mediaState = "CONNECTING" }
        if (state.phase == SessionPhase.STOPPING) { requested = false; mediaState = "STOPPING" }
        if (state.phase in setOf(SessionPhase.INACTIVE, SessionPhase.FAILED) && session == null) {
            requested = false; mediaState = if (state.phase == SessionPhase.FAILED) "PEER_FAILED" else "DISCONNECTED"
        }
        if (session != null && mediaState == "STREAMING" && state.phase != SessionPhase.STREAMING)
            client.command(SessionControl.Operation.RENDERED, state.epoch)
        if (attachment.available(state)) startProjection(state.epoch)
        publish()
    }

    private fun startProjection(epoch: String) {
        requested = false; mediaState = "CONNECTING"; displayed = false
        client.command(SessionControl.Operation.CONNECTING, epoch)
        var nextOutput: ReceiverVideoOutput? = null
        try {
            resourcesHeld = true; resources(true)
            val video = outputFactory({ surface, generation -> deliver {
                if (!closed && !displayed && output === nextOutput && window === surface && windowGeneration == generation && mediaState == "STREAMING") {
                    displayed = true; session?.presentation(true); publish()
                }
            } }, { error -> deliver { if (output === nextOutput) {
                if (error is PresentationSurfaceLost) {
                    if (window === error.surface && windowGeneration == error.generation) {
                        displayed = false; window = null; windowGeneration++
                        session?.presentation(false); publish()
                    }
                } else failOutput()
            } } })
            nextOutput = video; output = video
            video.attach(window, windowGeneration)
            val next = ProjectionSession(directory, video.surface, { source, value -> deliver {
                if (!closed && session === source) {
                    mediaState = value
                    if (value == "STREAMING") {
                        client.command(SessionControl.Operation.RENDERED, epoch)
                        video.redraw()
                    } else { displayed = false; source.presentation(false) }
                    if (value !in setOf("CONNECTING", "WAITING_FOR_VIDEO", "STREAMING")) {
                        client.command(SessionControl.Operation.FAILED, epoch)
                        releaseMedia()
                    }
                    publish()
                }
            } }, { source, _ ->
                // Synchronous invalidation follows old decoder release, before the new decoder allocation.
                video.invalidate()
                deliver { if (!closed && session === source) { displayed = false; source.presentation(false); publish() } }
            }, context, onReceiverInput = { source, event -> deliver {
                if (!closed && session === source) {
                    authenticatedControl(source, event) // Background authenticated seam for activation/wake work.
                    if (displayed && window != null) listener?.input(source, event)
                }
            } })
            next.presentation(false); session = next; startSession(next)
        } catch (error: Exception) {
            android.util.Log.e("EyeProjector", "Retained renderer setup failed", error)
            mediaState = "RENDER_SETUP_FAILED"
            client.command(SessionControl.Operation.FAILED, epoch)
            releaseMedia()
        }
    }

    private fun failOutput() {
        if (session == null) return
        attachment.epoch?.let { client.command(SessionControl.Operation.FAILED, it) }
        mediaState = "RENDER_FAILED"; releaseMedia(); publish()
    }

    private fun releaseMedia() {
        attachment.release()
        val old = session; session = null
        val video = output; output = null
        displayed = false
        try { old?.close() }
        finally { try { video?.close() } finally { if (resourcesHeld) { resourcesHeld = false; resources(false) } } }
    }

    fun disconnect(notifyPeer: Boolean = true, complete: (() -> Unit)? = null) {
        if (closed) { complete?.invoke(); return }
        val epoch = attachment.epoch ?: peer?.takeIf { it.phase.active }?.epoch
        epoch?.let(attachment::suppress)
        if (notifyPeer) client.disconnect(epoch, complete?.let { done -> { deliver(done) } }).forEach(attachment::suppress)
        else complete?.invoke()
        requested = false; mediaState = "DISCONNECTED"; releaseMedia(); publish()
    }

    override fun close() {
        if (closed) return
        val epoch = attachment.epoch ?: peer?.takeIf { it.phase.active }?.epoch
        closed = true; listener = null; window = null; windowGeneration++
        client.close(epoch); releaseMedia()
    }
}
