package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.view.Surface
import com.code2hack.eyeprojector.directrg.localcamera.LocalPreviewOutput
import com.code2hack.eyeprojector.directrg.localcamera.LocalOutputReleaseResult
import com.code2hack.eyeprojector.directrg.localcamera.LocalReleaseDisposition
import com.code2hack.eyeprojector.directrg.localcamera.LocalReleaseStep
import com.code2hack.eyeprojector.directrg.localcamera.LocalProducerFence
import com.code2hack.eyeprojector.directrg.localcamera.RetainedOutputRetirement
import com.code2hack.eyeprojector.protocol.*
import java.io.Closeable
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.Executor
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
    private val cameraOwner: ReceiverCameraOwner? = null,
) : Closeable {
    companion object {
        @Volatile internal var windowPresentationObserver: WindowPresentationObserver? = null
    }

    private data class CallbackReceipt(val originalStartedMonoNs: Long?, val generation: Long,
        val receivedMonoNs: Long, val readEndMonoNs: Long)

    interface View {
        fun state(snapshot: Snapshot)
        fun input(source: ProjectionSession, event: ReceiverInput)
        fun anchor(source: ProjectionSession, message: AnchorMessage): Boolean = false
        fun anchorHeld(source: ProjectionSession, message: AnchorMessage.HeldState) = Unit
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
    @Volatile private var resourcesHeld = false
    private class MediaOriginal(val projection: ProjectionSession?, val video: ReceiverVideoOutput?) {
        var camera: ReceiverCameraProjection? = null
    }
    private class MediaRetirement(val original: MediaOriginal) {
        @Volatile var cameraDuty = false
        @Volatile var projectionClosed = false
        @Volatile var cameraClosed = false
        @Volatile var outputClosed = false
        @Volatile var outputClaimed = false
        @Volatile var legacyOutputWorkerClaimed = false
        @Volatile var outputResult: LocalOutputReleaseResult? = null
        val failures = ArrayList<Throwable>()
    }
    @Volatile private var mediaOriginal: MediaOriginal? = null
    private val retirementGate = Any()
    private val retiringMedia = IdentityHashMap<MediaOriginal, MediaRetirement>()
    private val resourcesReleased = ArrayList<() -> Unit>()
    private val directCompletion = Executor { it.run() }

    /** Includes original retirements; a replacement current session cannot erase their duties. */
    fun hasRetainedResources(): Boolean = resourcesHeld || synchronized(retirementGate) { retiringMedia.isNotEmpty() }
    fun hasCameraRetirementDuty(): Boolean = mediaOriginal?.camera?.hasPhysicalDuty == true ||
        synchronized(retirementGate) { retiringMedia.values.any { it.cameraDuty } }
    fun afterResourcesReleased(completion: () -> Unit) {
        val ready = synchronized(retirementGate) {
            if (hasRetainedResources()) { resourcesReleased.add(completion); false } else true
        }
        if (ready) completion()
    }
    private fun notifyResourcesReleased() {
        val callbacks = synchronized(retirementGate) {
            if (hasRetainedResources()) emptyList() else resourcesReleased.toList().also { resourcesReleased.clear() }
        }
        callbacks.forEach { runCatching { it() } }
    }
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
    /** The caller has allocated this exact chooser revision and has not published it yet. */
    fun openAnchorRegistration(source: ProjectionSession, offer: AnchorMessage.Offer, chooserRevision: Long): Boolean {
        val demand = feature?.demand() ?: return false
        if (closed || session !== source || chooserRevision != inputRevision ||
            offer.intent.context.usability.incarnation != demand.incarnation || attachment.epoch != demand.ownerEpoch) return false
        return source.openAnchorRegistration(offer, ReceiverUsability(demand.ownerEpoch, demand.incarnation,
            usabilityRevision.incrementAndGet(), chooserRevision, ReceiverUsabilityMode.LOCAL_PANEL))
    }

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
            } }, nextUsabilityRevision = usabilityRevision::incrementAndGet, expectedPhoneOwnerEpoch = epoch,
                onAnchor = { source, message -> deliver {
                    if (!closed && session === source && uiUsable) {
                        if (message is AnchorMessage.HeldState) listener?.anchorHeld(source, message)
                        val consumed = message is AnchorMessage.HeldState || listener?.anchor(source, message) == true
                        if (!consumed && message is AnchorMessage.Feedback) AnchorFeedbackUi.show(context, message.status)
                    }
                } })
            // This source record exists even with the optional window observer disabled.
            // A synchronous camera callback can retire it before attachProjection returns.
            val originalMedia = if (cameraOwner != null) MediaOriginal(next, video) else null
            mediaOriginal = originalMedia
            feature?.incarnation?.let { next.usability(ReceiverUsability(epoch, it, usabilityRevision.incrementAndGet(), inputRevision, ReceiverUsabilityMode.NONE)) }
            // Publish the immutable original reference before starting any frame-producing session work.
            originalSession?.set(next)
            next.presentation(false); session = next
            if (cameraOwner != null && video is LocalPreviewOutput) {
                originalMedia?.camera = cameraOwner.attachProjection(next, video, video)
                if (closed || mediaOriginal !== originalMedia || session !== next) {
                    cameraOwner.retireProjection(next, CameraEndReason.PROJECTION_CLOSED)
                    return
                }
            }
            startSession(next)
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
        if (cameraOwner != null) { releaseCameraMedia(); return }
        attachment.release()
        val old = session; session = null
        val video = output; output = null
        mediaOriginal = null
        displayed = false
        try { old?.close() }
        finally { try { video?.close() } finally {
            if (resourcesHeld) { resourcesHeld = false; resources(false) }
            notifyResourcesReleased()
        } }
    }

    private fun releaseCameraMedia() {
        attachment.release()
        val old = session; session = null
        val video = output; output = null
        val original = mediaOriginal ?: MediaOriginal(old, video)
        mediaOriginal = null; displayed = false
        val retiring = MediaRetirement(original)
        // Claim the exact original before any retire/close callback can reenter this owner.
        synchronized(retirementGate) { retiringMedia[original] = retiring }
        val camera = try {
            old?.let { cameraOwner?.retireProjection(it, CameraEndReason.PROJECTION_CLOSED) }
                ?: original.camera
        } catch (error: Throwable) {
            synchronized(retiring) { retiring.failures.add(error) }
            null
        }
        retiring.cameraDuty = camera?.hasPhysicalDuty == true || synchronized(retiring) { retiring.failures.isNotEmpty() }
        if (camera?.hasPhysicalDuty != true && synchronized(retiring) { retiring.failures.isEmpty() }) {
            // Ordinary projection still uses its original synchronous close/finally order.
            try { old?.close(); retiring.projectionClosed = true }
            finally {
                try { video?.close(); retiring.outputClosed = true }
                finally {
                    retiring.cameraClosed = true
                    // Preserve the historical no-camera finally semantics, including throws.
                    synchronized(retirementGate) { retiringMedia.remove(original) }
                    settleMediaRetirement(retiring)
                }
            }
            return
        }
        if (camera != null) try {
            camera.closeAsync(directCompletion) { observation ->
                retiring.cameraClosed = !observation.resourcesHeld
                retirementChanged(retiring)
            }
        } catch (error: Throwable) { synchronized(retiring) { retiring.failures.add(error) } }
        // ProjectionSession.close may join its decoder: keep that work off UI/GL/control lanes.
        launchRetirement(retiring) {
            try { old?.close(); retiring.projectionClosed = true }
            catch (error: Throwable) { synchronized(retiring) { retiring.failures.add(error) } }
            closeRetiredOutput(retiring)
            retirementChanged(retiring)
        }
    }

    private fun launchRetirement(original: MediaRetirement, action: () -> Unit) {
        try { Thread({ action() }, "eyeprojector-original-media-close").start() }
        catch (error: Throwable) { synchronized(original) { original.failures.add(error) } }
    }
    private fun closeRetiredOutput(original: MediaRetirement) {
        if (!original.projectionClosed) return
        val video = original.original.video
        val claim = synchronized(original) {
            if (original.outputClaimed || video !is RetainedOutputRetirement && !original.cameraClosed) false
            else { original.outputClaimed = true; true }
        }
        if (!claim) return
        if (video is RetainedOutputRetirement) try {
            video.closeAsync(directCompletion) { result ->
                original.outputResult = result
                original.outputClosed = outputClosureKnown(result)
                val projection = original.original.projection
                if (projection != null && video is LocalPreviewOutput) try {
                    cameraOwner?.outputReleased(projection, video, result)
                } catch (error: Throwable) { synchronized(original) { original.failures.add(error) } }
                retirementChanged(original)
            }
        } catch (error: Throwable) { synchronized(original) { original.failures.add(error) } }
        else try { video?.close(); original.outputClosed = true }
        catch (error: Throwable) { synchronized(original) { original.failures.add(error) } }
    }
    private fun releaseStepKnown(step: LocalReleaseStep?): Boolean = step != null &&
        (step.disposition == LocalReleaseDisposition.RETURNED ||
            step.disposition == LocalReleaseDisposition.NOT_ATTEMPTED && step.error == null)

    private fun outputClosureKnown(result: LocalOutputReleaseResult): Boolean {
        if (result.workerJoin.disposition != LocalReleaseDisposition.RETURNED || result.workerAlive != false) return false
        val required = listOf("surfaceRelease", "surfaceTextureRelease", "eglUnbind", "windowDestroy",
            "bufferDestroy", "contextDestroy", "displayTerminate", "eglReleaseThread", "quitSafely")
        if (!required.all { releaseStepKnown(result.steps[it]) }) return false
        val contextGone = releaseStepKnown(result.steps["contextDestroy"])
        // Failed observations stay recorded. Actual destruction of the owning context can
        // invalidate GL names even when explicit deletion was unavailable; it is not a delete return.
        return result.inputs.all { input ->
            input.producerFence != LocalProducerFence.PENDING &&
                releaseStepKnown(input.steps["surfaceRelease"]) && releaseStepKnown(input.steps["surfaceTextureRelease"]) &&
                (contextGone || releaseStepKnown(input.steps["programDelete"]) && releaseStepKnown(input.steps["textureDelete"]))
        } && (contextGone || releaseStepKnown(result.steps["programDelete"]) && releaseStepKnown(result.steps["textureDelete"]))
    }
    private fun retirementChanged(original: MediaRetirement) {
        // A legacy output cannot close while an original camera borrower is still unresolved.
        val startLegacy = synchronized(original) {
            if (original.projectionClosed && original.cameraClosed &&
                original.original.video !is RetainedOutputRetirement && !original.outputClaimed &&
                !original.legacyOutputWorkerClaimed) { original.legacyOutputWorkerClaimed = true; true } else false
        }
        if (startLegacy)
            launchRetirement(original) { closeRetiredOutput(original); retirementChanged(original) }
        try { deliver { settleMediaRetirement(original) } }
        catch (error: Throwable) { synchronized(original) { original.failures.add(error) } }
    }
    private fun settleMediaRetirement(original: MediaRetirement) {
        val held = synchronized(retirementGate) {
            if (original.projectionClosed && original.cameraClosed && original.outputClosed &&
                synchronized(original) { original.failures.isEmpty() }) retiringMedia.remove(original.original)
            mediaOriginal != null || session != null || output != null || retiringMedia.isNotEmpty()
        }
        if (resourcesHeld != held) { resourcesHeld = held; resources(held) }
        notifyResourcesReleased()
        changed()
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
        if (cameraOwner == null) { client.close(epoch); releaseMedia() }
        else try { client.close(epoch) } finally { releaseMedia() }
    }
}
