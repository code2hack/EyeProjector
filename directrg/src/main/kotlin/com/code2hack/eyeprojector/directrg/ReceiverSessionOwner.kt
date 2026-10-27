package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
    private val feature: ReceiverActivationStore? = null,
    private val wake: (ActivationDirective) -> Unit = {},
    clientFactory: ((SessionState) -> Unit, () -> Unit) -> PairedSessionClient = { state, unavailable ->
        PairedSessionClient(directory, state, unavailable, deliver = deliver, feature = feature)
    },
) : Closeable {
    companion object {
        @Volatile internal var windowPresentationObserver: WindowPresentationObserver? = null
    }

    private data class CallbackReceipt(val originalStartedMonoNs: Long?, val generation: Long,
        val receivedMonoNs: Long, val readEndMonoNs: Long)

    interface View {
        fun state(snapshot: Snapshot)
        fun input(source: ProjectionSession, event: ReceiverInput)
    }
    data class Snapshot(val session: ProjectionSession?, val epoch: String?, val peer: SessionState?,
        val requested: Boolean, val state: String, val geometry: Geometry?, val activationId: String? = null)

    private val attachment = SessionAttachment()
    private var listener: View? = null
    @Volatile private var window: Surface? = null
    private val windowGeneration = AtomicLong()
    private val usabilityRevision = AtomicLong()
    @Volatile private var output: ReceiverVideoOutput? = null
    private var closed = false
    private var peer: SessionState? = null
    private var requested = false
    private var mediaState = "DISCONNECTED"
    private var displayed = false
    private var displayedGeneration = -1L
    private val currentDisplayed get() = displayed && displayedGeneration == windowGeneration.get()
    private var resourcesHeld = false
    private var uiUsable = false
    private var localPanel = true
    private var reportRevision = 0L
    private var lastReport: List<Any?>? = null
    private var lastPublishedInputRevision = -1L
    private var lastWake: ReceiverDemand? = null
    private val pendingCancellations = linkedMapOf<Pair<String?, String?>, () -> Unit>()
    private var pendingLaunch: ReceiverLaunchScope? = null
    var inputRevision = 0L
        private set
    fun advanceInputRevision(): Long = ++inputRevision
    @Volatile var session: ProjectionSession? = null
        private set
    private val client = clientFactory(::observePeer) {
        if (!closed && session == null) {
            requested = feature?.intent() != null || feature?.directive?.desired == true
            mediaState = if (requested) "RETRYING_PEER" else "PEER_UNAVAILABLE"; publish()
        }
    }

    fun start() = client.start()
    fun snapshot() = Snapshot(session, attachment.epoch, peer, requested,
        if (session != null && mediaState == "STREAMING" && !currentDisplayed) "DISPLAY_DETACHED" else mediaState, session?.geometry, feature?.directive?.activationId)
    /** Diagnostic reads only: no locks, publication, revision allocation, I/O or authority export. */
    private fun windowObservation(original: ProjectionSession?, originalOutput: ReceiverVideoOutput?,
        callbackWindow: Surface, originalEpoch: String): OwnerWindowSnapshot? = try {
        val begin = System.nanoTime()
        val generationBefore = windowGeneration.get()
        val current = session
        val currentOutput = output
        val currentWindow = window
        val report = feature?.presentation
        val directive = feature?.directive
        val incarnation = feature?.incarnation
        val usability = current?.observationUsability()
        val geometry = current?.geometry
        val currentClosed = closed
        val state = mediaState
        val displayedAt = displayedGeneration
        val shown = currentDisplayed
        val usable = uiUsable
        val panel = localPanel
        val revision = inputRevision
        val generationAfter = windowGeneration.get()
        val stable = current === session && currentOutput === output && currentWindow === window &&
            report === feature?.presentation && directive === feature?.directive && incarnation === feature?.incarnation &&
            usability === current?.observationUsability() && geometry === current?.geometry &&
            generationBefore == generationAfter && revision == inputRevision
        OwnerWindowSnapshot(begin, System.nanoTime(), generationBefore, generationAfter,
            current?.observationStartedMonoNs, original?.let { current === it },
            originalOutput?.let { currentOutput === it }, currentWindow === callbackWindow,
            currentClosed, state, displayedAt, shown, usable, panel, revision,
            report?.mode?.name, report?.revision, report?.let { it.ownerEpoch == originalEpoch },
            report?.let { fact -> directive?.let { demand ->
                fact.activationId == demand.activationId && fact.ownerEpoch == demand.ownerEpoch &&
                    fact.wakeRevision == demand.wakeRevision && fact.incarnation == incarnation
            } }, usability?.mode?.name, usability?.revision, usability?.inputRevision,
            usability?.let { it.ownerEpoch == originalEpoch }, geometry?.width, geometry?.height, stable)
    } catch (_: Throwable) { null } // Diagnostics never replace a product failure.

    private fun publish(notifyView: Boolean = true) {
        val d = feature?.demand()
        if (d != null) {
            // Allocate source authority before selecting eligibility. A concurrent replacement
            // then has a newer NONE even if this selected fact waits before application.
            val sourceRevision = usabilityRevision.incrementAndGet()
            val shown = session != null && attachment.epoch == d.ownerEpoch && uiUsable && (localPanel || currentDisplayed)
            val marker = listOf(d.activationId, d.wakeRevision, d.ownerEpoch, d.goal, windowGeneration.get(), inputRevision, listener, uiUsable, localPanel, displayed, mediaState)
            if (marker != lastReport) {
                lastReport = marker
                lastPublishedInputRevision = inputRevision
                attachment.epoch?.let { originalEpoch -> session?.usability(ReceiverUsability(originalEpoch, d.incarnation,
                    sourceRevision, inputRevision,
                    if (!shown) ReceiverUsabilityMode.NONE else if (localPanel) ReceiverUsabilityMode.LOCAL_PANEL else ReceiverUsabilityMode.PROJECTED)) }
                feature.report(d, ++reportRevision, if (!shown) ReceiverUsabilityMode.NONE else if (localPanel) ReceiverUsabilityMode.LOCAL_PANEL else ReceiverUsabilityMode.PROJECTED, if (shown) { if (localPanel) "LOCAL_PANEL" else "PROJECTED" } else "DISPLAY_DETACHED")
                client.presentationChanged()
            }
            session?.presentation(shown)
        }
        changed(); if (notifyView) listener?.state(snapshot())
    }
    fun presentation(view: View, usable: Boolean, panel: Boolean) {
        if (closed || listener !== view) return
        if (uiUsable == usable && localPanel == panel && lastPublishedInputRevision == inputRevision) return
        val changedPresentation = uiUsable != usable || localPanel != panel
        uiUsable = usable; localPanel = panel
        publish(notifyView = changedPresentation)
    }
    fun launch(scope: ReceiverLaunchScope) {
        if (closed) return
        if (feature?.state?.triggers?.get(scope.trigger)?.canceled == true) return
        try {
            client.launch(scope); pendingLaunch = null
            if (session == null) { requested = true; mediaState = "CONNECTING" }
        }
        catch (_: Exception) { pendingLaunch = scope; requested = true; mediaState = "ACTIVATION_STORAGE_PENDING" }
        publish()
    }
    fun retryCancellation() { pendingCancellations.values.toList().forEach { it() }; pendingLaunch?.let(::launch) }
    fun activationId(): String? = feature?.directive?.activationId
    fun currentDemand(): ReceiverDemand? = feature?.demand()


    fun attach(view: View, surface: Surface?) {
        if (closed) return
        listener = view; uiUsable = false
        attachSurface(view, surface)
    }

    fun attachSurface(view: View, surface: Surface?) {
        if (closed || listener !== view) return
        session?.presentation(false)
        displayed = false; window = surface; windowGeneration.incrementAndGet()
        try { output?.attach(surface, windowGeneration.get()) }
        catch (error: Exception) { failOutput(); return }
        publish()
    }

    fun detach(view: View) {
        if (listener !== view) return
        uiUsable = false
        attachSurface(view, null)
        listener = null
        publish()
    }

    fun connect() {
        if (feature != null) { feature.currentScope()?.let(::launch); return }
        if (closed || session != null || requested) return
        requested = true; mediaState = "CONNECTING"; publish(); client.connect()
    }

    private fun observePeer(state: SessionState) {
        if (closed) return
        val demand = feature?.directive
        if (demand != null && feature.permits(demand)) {
            val key = feature.demand()
            if (lastWake != key) { lastWake = key; wake(demand) }
        }
        if (state.phase == SessionPhase.UNKNOWN) { peer = state; publish(); return }
        peer = state
        if (session != null && (attachment.epoch != state.epoch || !state.phase.active)) disconnect(false)
        if (state.phase.active && session == null) { requested = true; mediaState = "CONNECTING" }
        if (state.phase == SessionPhase.STOPPING) { requested = false; mediaState = "STOPPING" }
        if (state.phase in setOf(SessionPhase.INACTIVE, SessionPhase.FAILED) && session == null) {
            requested = feature?.intent() != null || feature?.directive?.desired == true
            mediaState = if (requested) "RETRYING_PEER" else if (state.phase == SessionPhase.FAILED) "PEER_FAILED" else "DISCONNECTED"
        }
        if (session != null && mediaState == "STREAMING" && state.phase != SessionPhase.STREAMING)
            client.command(SessionControl.Operation.RENDERED, state.epoch)
        if (attachment.available(state)) startProjection(state.epoch)
        if (feature != null && requested && state.detail in setOf("PAIRING_REQUIRED", "MISSING", "UNAVAILABLE", "UPDATE_REQUIRED", "AUTHORIZATION_REQUIRED", "SHELL_REQUIRED"))
            mediaState = "PHONE_SETUP_REQUIRED"
        publish()
    }

    private fun startProjection(epoch: String) {
        requested = false; mediaState = "CONNECTING"; displayed = false
        client.command(SessionControl.Operation.CONNECTING, epoch)
        var nextOutput: ReceiverVideoOutput? = null
        try {
            resourcesHeld = true; resources(true)
            // No diagnostic origin allocation while disabled. A later installation cannot invent
            // the original reference of an output that predates observation: its origin stays unknown.
            val originalSession = if (windowPresentationObserver == null) null else AtomicReference<ProjectionSession?>()
            val video = outputFactory({ surface, generation ->
                val observer = windowPresentationObserver
                val source = if (observer == null) null else originalSession?.get()
                val receipt = if (observer == null) null else try {
                    val begin = System.nanoTime()
                    CallbackReceipt(source?.observationStartedMonoNs, generation, begin, System.nanoTime())
                } catch (_: Throwable) { null }
                deliver {
                    if (receipt == null) {
                        if (!closed && !currentDisplayed && output === nextOutput && window === surface && windowGeneration.get() == generation && mediaState == "STREAMING") {
                            displayedGeneration = generation; displayed = true; session?.presentation(true); publish()
                        }
                    } else {
                        val before = windowObservation(source, nextOutput, surface, epoch)
                        var entered = false
                        var completed = false
                        try {
                            if (!closed && !currentDisplayed && output === nextOutput && window === surface && windowGeneration.get() == generation && mediaState == "STREAMING") {
                                entered = true
                                displayedGeneration = generation; displayed = true; session?.presentation(true); publish()
                            }
                            completed = true
                        } finally {
                            // Existing callback/effects always run; token matching controls evidence only.
                            if (windowPresentationObserver === observer) try {
                                observer?.presented(SuccessfulWindowReceipt(receipt.originalStartedMonoNs,
                                    receipt.generation, receipt.receivedMonoNs, before, entered, completed,
                                    windowObservation(source, nextOutput, surface, epoch), receipt.readEndMonoNs))
                            } catch (_: Throwable) { /* An observer cannot mask the callback's primary failure. */ }
                        }
                    }
                }
            }, { error -> deliver { if (output === nextOutput) {
                if (error is PresentationSurfaceLost) {
                    if (window === error.surface && windowGeneration.get() == error.generation) {
                        displayed = false; window = null; windowGeneration.incrementAndGet()
                        session?.presentation(false); publish()
                    }
                } else failOutput()
            } } })
            nextOutput = video; output = video
            video.attach(window, windowGeneration.get())
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
                // Fence old queued window callbacks and publish NONE before replacement input facts.
                windowGeneration.incrementAndGet()
                source.presentation(false)
                video.invalidate()
                deliver { if (!closed && session === source) {
                    displayed = false
                    video.attach(window, windowGeneration.get())
                    source.presentation(false); publish()
                } }
            }, context, onReceiverInput = { source, event -> deliver {
                if (!closed && session === source) {
                    authenticatedControl(source, event) // Background authenticated seam for activation/wake work.
                    if (currentDisplayed && window != null || feature != null && uiUsable && localPanel) listener?.input(source, event)
                }
            } }, nextUsabilityRevision = usabilityRevision::incrementAndGet, expectedPhoneOwnerEpoch = epoch)
            feature?.incarnation?.let { next.usability(ReceiverUsability(epoch, it, usabilityRevision.incrementAndGet(), inputRevision, ReceiverUsabilityMode.NONE)) }
            // Publish the immutable original reference before starting any frame-producing session work.
            originalSession?.set(next)
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

    fun disconnect(notifyPeer: Boolean = true, complete: (() -> Unit)? = null,
        scope: ReceiverLaunchScope? = feature?.currentScope(), activationId: String? = feature?.directive?.activationId) {
        if (closed) { complete?.invoke(); return }
        if (notifyPeer && pendingLaunch?.id == scope?.id) pendingLaunch = null
        val originalH = scope?.let { feature?.state?.triggers?.get(it.trigger)?.activation }
        val mediaH = peer?.activation?.directive?.takeIf { it.ownerEpoch == attachment.epoch }?.activationId
            ?: feature?.directive?.takeIf { it.ownerEpoch == attachment.epoch }?.activationId
        val affectsCurrent = !notifyPeer || feature == null ||
            mediaH != null && (mediaH == activationId || mediaH == originalH) ||
            session == null && feature.cancellationTargetsCurrent(scope, activationId)
        if (affectsCurrent && notifyPeer) uiUsable = false
        val epoch = if (affectsCurrent) attachment.epoch ?: peer?.takeIf { it.phase.active }?.epoch else null
        if (affectsCurrent) epoch?.let(attachment::suppress)
        if (notifyPeer) {
            try {
                client.disconnect(epoch, complete?.let { done -> { deliver(done) } }, scope, activationId).forEach(attachment::suppress)
                pendingCancellations.remove(scope?.id to activationId)
            } catch (_: Exception) {
                pendingCancellations[scope?.id to activationId] = { disconnect(notifyPeer, complete, scope, activationId) }
                if (affectsCurrent) { requested = true; mediaState = "CANCEL_PENDING"; publish() }
                return
            }
        } else complete?.invoke()
        if (affectsCurrent) { requested = false; mediaState = "DISCONNECTED"; releaseMedia(); publish() }
    }

    override fun close() {
        if (closed) return
        val epoch = attachment.epoch ?: peer?.takeIf { it.phase.active }?.epoch
        closed = true; listener = null; window = null; windowGeneration.incrementAndGet()
        client.close(epoch); releaseMedia()
    }
}
