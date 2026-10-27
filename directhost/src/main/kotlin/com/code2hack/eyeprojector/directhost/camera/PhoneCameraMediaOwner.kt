package com.code2hack.eyeprojector.directhost.camera

import com.code2hack.eyeprojector.protocol.*
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Local references, rather than equal wire tuples, own native resources. */
class CapturedPhoneDemandGroup(val originalSession: Any, val registration: OwnedPhoneRegistration) : OriginalPhoneDemandGroup
class PhoneDecoderEpoch(
    val group: CapturedPhoneDemandGroup,
    val role: PendingCameraRole,
    val stream: CameraStreamRef,
    val description: CameraStreamDescription,
)

data class PhoneMediaStartSubmission(val fact: CameraFact, val failure: Throwable? = null)
data class PhoneMediaStopSubmission(val fact: CameraFact)
data class PhoneMediaCloseObservation(val snapshot: PhoneCameraMediaSnapshot, val failure: Throwable? = null)
/** Phone-local consumer receipt/queue-return observations; never subtract remote PTS from this clock. */
data class PhoneCameraIngressObservation(
    val original: PhoneDecoderEpoch,
    val receivedAccessUnits: Long = 0,
    val acceptedAccessUnits: Long = 0,
    val waitingSequence: Long? = null,
    val lastReceivedSequence: Long? = null,
    val lastReceivedPtsUs: Long? = null,
    val lastReceiveMonoNs: Long? = null,
    val lastAcceptedSequence: Long? = null,
    val lastAcceptedPtsUs: Long? = null,
    val lastAcceptanceReturnMonoNs: Long? = null,
    val lastAcceptanceWaitNs: Long? = null,
    val maxAcceptanceWaitNs: Long = 0,
    val totalAcceptanceWaitNs: Long = 0,
    val lastSubmissionFact: CameraFact? = null,
    val outputObservations: Long = 0,
    val lastOutputPtsUs: Long? = null,
    val lastOutputObservationMonoNs: Long? = null,
) {
    fun json(): JSONObject = JSONObject().put("localEpoch", System.identityHashCode(original))
        .put("receivedAccessUnits", receivedAccessUnits).put("acceptedAccessUnits", acceptedAccessUnits)
        .put("waitingSequence", waitingSequence ?: JSONObject.NULL)
        .put("lastReceivedSequence", lastReceivedSequence ?: JSONObject.NULL)
        .put("lastReceivedPtsUs", lastReceivedPtsUs ?: JSONObject.NULL)
        .put("lastReceiveMonoNs", lastReceiveMonoNs ?: JSONObject.NULL)
        .put("lastAcceptedSequence", lastAcceptedSequence ?: JSONObject.NULL)
        .put("lastAcceptedPtsUs", lastAcceptedPtsUs ?: JSONObject.NULL)
        .put("lastAcceptanceReturnMonoNs", lastAcceptanceReturnMonoNs ?: JSONObject.NULL)
        .put("lastAcceptanceWaitNs", lastAcceptanceWaitNs ?: JSONObject.NULL)
        .put("maxAcceptanceWaitNs", maxAcceptanceWaitNs).put("totalAcceptanceWaitNs", totalAcceptanceWaitNs)
        .put("lastSubmissionFact", lastSubmissionFact?.name ?: JSONObject.NULL)
        .put("outputObservations", outputObservations).put("lastOutputPtsUs", lastOutputPtsUs ?: JSONObject.NULL)
        .put("lastOutputObservationMonoNs", lastOutputObservationMonoNs ?: JSONObject.NULL)
}
data class PhoneCameraStatusProgress(
    val requests: Long,
    val snapshots: Long,
    val publications: Long,
    val lastSnapshotDurationNs: Long,
    val maxSnapshotDurationNs: Long,
    val lastPublicationDurationNs: Long,
    val maxPublicationDurationNs: Long,
    val sealed: Boolean,
) {
    fun json(): JSONObject = JSONObject().put("requests", requests).put("snapshots", snapshots)
        .put("publications", publications).put("lastSnapshotDurationNs", lastSnapshotDurationNs)
        .put("maxSnapshotDurationNs", maxSnapshotDurationNs)
        .put("lastPublicationDurationNs", lastPublicationDurationNs)
        .put("maxPublicationDurationNs", maxPublicationDurationNs).put("sealed", sealed)
}
data class PhoneMediaGroupSnapshot(
    val original: CapturedPhoneDemandGroup,
    val use: CameraUseRef?,
    val liveInputs: List<PhoneCameraLease>,
    val retired: Boolean,
    val decoders: List<PhoneDecoderObservation>,
    val fanoutEvents: List<PhoneFanoutObservation>,
    val fanout: PhoneFanoutCloseObservation,
    val ingress: List<PhoneCameraIngressObservation> = emptyList(),
)
data class PhoneCameraMediaSnapshot(
    val originalSession: Any,
    val revision: Long,
    val startSubmitted: Boolean,
    val closeRequested: Boolean,
    val registration: PhoneCameraRegistrationSnapshot?,
    val inputs: List<PhoneInputSnapshot>,
    val groups: List<PhoneMediaGroupSnapshot>,
    val primaryFailure: Throwable?,
    val observerFailures: List<Throwable>,
    val worker: PhoneWorkerSnapshot,
    val statusWorker: PhoneWorkerSnapshot,
    val statusWorkers: List<PhoneWorkerSnapshot>,
    val callbackQuiescence: String = "UNOBSERVED",
    val fullH: String = "UNKNOWN",
    val sampleMonoNs: Long = 0,
    val statusProgress: PhoneCameraStatusProgress? = null,
)

/**
 * One Session's callable camera path. Construction performs no Binder, codec, GL or thread work.
 * Control callers only close admission/enroll work; all native operations have original owned lanes.
 */
class PhoneCameraMediaOwner(
    private val originalSession: Any,
    private val roles: PhoneCameraMediaRole,
    private val backend: Qpr2VirtualCameraOwner,
    private val registrationProfile: PhoneCameraProfile,
    private val sourceRequest: CameraStreamRequest,
    private val observations: (PhoneCameraMediaSnapshot) -> Unit,
) {
    private val ownership = PhoneCameraOwnership()
    private val gate get() = ownership.lock
    private class RegistrationSlot {
        var original: OwnedPhoneRegistration? = null
        var invocationStarted = false
    }
    private val slot = RegistrationSlot() // Exists before register() can publish a callback.
    private var started = false
    private var closing = false
    private var observedRegistration: PhoneCameraRegistrationSnapshot? = null
    private var primaryFailure: Throwable? = null
    private val observerFailures = ArrayList<Throwable>()
    private val targets = IdentityHashMap<PhoneCameraLease, PhoneOutputTarget>()
    private val groups = ArrayList<Group>()
    private var active: Group? = null
    private val worker = PhoneCameraWorker("phone-camera-media", ownership, ::recordFailure)
    private val status = PhoneCameraWorker("phone-camera-status", ownership, ::recordObserverFailure)
    private var statusDirty = false
    private var statusDrainOwned = false
    private var statusSealed = false
    private var statusCloseClaimed = false
    private var statusCloseFailure: Throwable? = null
    private val statusCloseFinished = CountDownLatch(1)
    private var statusRequests = 0L
    private var statusSnapshots = 0L
    private var statusPublications = 0L
    private var lastSnapshotDurationNs = 0L
    private var maxSnapshotDurationNs = 0L
    private var lastPublicationDurationNs = 0L
    private var maxPublicationDurationNs = 0L
    private val ownerDrained = CountDownLatch(1)

    private class EpochRecord(val original: PhoneDecoderEpoch) {
        val ready = Object()
        var decoder: PhoneCameraDecoder? = null
        var decoderReady = false
        var closed = false
        var failure: Throwable? = null
        var observation: PhoneDecoderObservation? = null
        var ingress = PhoneCameraIngressObservation(original)
        var formatPublished: PhoneDecodedFormat? = null
        var fencePublished = false
        var joinRequested = false
        val closeObserved = CountDownLatch(1)
        fun stopAdmission() = synchronized(ready) { closed = true; ready.notifyAll() }
        fun install(value: PhoneCameraDecoder): Boolean = synchronized(ready) {
            decoder = value; !closed
        }
        fun activated() = synchronized(ready) { decoderReady = true; ready.notifyAll() }
        // Only the already-owned media reader may wait here. Stop never needs the native lane.
        fun awaitDecoder(): PhoneCameraDecoder? = synchronized(ready) {
            while ((decoder == null || !decoderReady) && !closed && failure == null) ready.wait()
            if (closed) null else decoder
        }
        fun failed(error: Throwable) = synchronized(ready) { failure = error; ready.notifyAll() }
    }
    private inner class Group(val original: CapturedPhoneDemandGroup) {
        var use: PublishedCameraUse? = null
        var retired = false
        val closeObserved = CountDownLatch(1)
        val members = IdentityHashMap<PhoneCameraLease, PhoneOutputTarget>()
        val epochs = ArrayList<EpochRecord>()
        val endedRoles = IdentityHashMap<PendingCameraRole, Boolean>()
        var current: EpochRecord? = null
        var lastFanout: PhoneFanoutObservation? = null
        val fanout = PhoneCameraSurfaceFanout(original, PhoneCameraFraming()) { event ->
            synchronized(gate) { lastFanout = event; ownership.changedLocked() }
            val failure = event.failure
            if (failure != null) {
                val affected = synchronized(gate) {
                    if (event.target != null) emptyList() else epochs.filter { event.decoder == null || it.original === event.decoder }
                }
                affected.forEach {
                    it.failed(failure); stopEpoch(this, it, CameraEndReason.LOCAL_RELEASE_FAILURE)
                    it.original.role.requestStop(CameraEndReason.LOCAL_RELEASE_FAILURE)
                }
                event.target?.let { target ->
                    retireInput(target.lease, CameraEndReason.LOCAL_RELEASE_FAILURE)
                    target.lease.requestClose()
                }
                recordFailure(failure)
            }
            publishStatus()
        }
        val consumer = object : PhoneCameraMediaConsumer {
            override fun description(original: PublishedCameraUse, role: PendingCameraRole,
                stream: CameraStreamRef, value: CameraStreamDescription) = describe(this@Group, original, role, stream, value)
            override fun accessUnit(original: PublishedCameraUse, role: PendingCameraRole,
                stream: CameraStreamRef, value: CameraAccessUnit) = access(this@Group, original, role, stream, value)
            override fun remoteEnd(original: PublishedCameraUse, role: PendingCameraRole, value: CameraMediaEnd) {
                if (value.stream == null) endRole(this@Group, original, role, value.reason)
                else endStream(this@Group, original, role, value)
            }
            override fun roleEnded(original: PublishedCameraUse, role: PendingCameraRole, reason: CameraEndReason) {
                endRole(this@Group, original, role, reason)
            }
            override fun requestRetire(original: PublishedCameraUse, reason: CameraEndReason) {
                if (synchronized(gate) { matchesUse(this@Group, original) }) retireGroup(this@Group, reason)
            }
        }
    }

    private val events = object : PhoneCameraEvents {
        override fun onRegistrationState(original: OwnedPhoneRegistration, state: PhoneCameraRegistrationSnapshot) {
            acceptEvent(original) {
                synchronized(gate) {
                    if (observedRegistration == null || state.revision >= observedRegistration!!.revision) {
                        observedRegistration = state; ownership.changedLocked()
                    }
                }
                publishStatus()
            }
        }
        override fun onObservation(original: OwnedPhoneRegistration, observation: PhoneCameraObservation) {
            acceptEvent(original) { observation.failure?.let(::recordFailure); publishStatus() }
        }
        override fun onInputConfigured(original: PhoneCameraLease) {
            // The original is retained even when its callback beats register() returning.
            acceptEvent(original.registration) { configured(original) }
        }
        override fun onStreamRetired(original: PhoneCameraLease) { retireInput(original, CameraEndReason.ORIGINAL_INPUT_CLOSED) }
    }

    fun startRegistration(profileOverride: PhoneCameraProfile? = null): PhoneMediaStartSubmission {
        val selectedProfile: PhoneCameraProfile
        synchronized(gate) {
            if (closing) return PhoneMediaStartSubmission(CameraFact.RETIRED)
            if (started) return PhoneMediaStartSubmission(CameraFact.STALE_OR_DUPLICATE)
            selectedProfile = profileOverride ?: registrationProfile
            started = true; ownership.changedLocked()
        }
        val enrolled = worker.post {
            val invoke = synchronized(gate) {
                if (closing) false else { slot.invocationStarted = true; ownership.changedLocked(); true }
            }
            if (invoke) {
                try {
                    val returned = backend.register(selectedProfile, events)
                    val close = synchronized(gate) {
                        check(slot.original == null || slot.original === returned)
                        slot.original = returned; ownership.changedLocked(); closing
                    }
                    if (close) returned.requestClose()
                } catch (error: Throwable) { recordFailure(error) }
            }
            publishStatus()
        }
        if (!enrolled) recordFailure(IllegalStateException("Original media owner did not admit registration"))
        return PhoneMediaStartSubmission(if (enrolled) CameraFact.PENDING else CameraFact.FAILED)
    }

    private fun acceptEvent(original: OwnedPhoneRegistration, action: () -> Unit) {
        val close = synchronized(gate) { closing }
        if (close) {
            original.requestClose() // Asynchronous original cleanup, never a replacement token.
            // Closed admission makes configured() cleanup-only. These observer actions perform no native wait.
            action()
            return
        }
        if (!worker.post {
                val matches = synchronized(gate) { slot.original === original }
                if (matches) action()
                else recordFailure(IllegalStateException("Callback registration differs from returned original"))
            }) recordFailure(IllegalStateException("Original event remains retained after owner admission ended"))
    }

    private fun configured(lease: PhoneCameraLease) {
        if (synchronized(gate) { targets.containsKey(lease) }) return // Same original callback is idempotent.
        val target = PhoneOutputTarget(lease, PhoneInputProducerIdentity())
        val enroll = synchronized(gate) {
            if (closing || slot.original !== lease.registration || targets.containsKey(lease)) false
            else { targets[lease] = target; ownership.changedLocked(); true }
        }
        if (!enroll) { lease.requestClose(); return }
        val bound = lease.bindProducer(target.producer) {
            target.closeAdmission(CameraEndReason.ORIGINAL_INPUT_CLOSED)
            retireInput(lease, CameraEndReason.ORIGINAL_INPUT_CLOSED)
        }
        target.finishBinding(bound)
        val borrowed = if (bound) lease.surfaceFor(target.producer) else null
        if (!bound || borrowed == null || !target.installBorrowed(borrowed)) {
            // Enroll on an actual fanout so even bound/no-native-use has one real fence owner.
            val group = synchronized(gate) {
                groupFor(lease.registration).also { it.fanout.addTarget(target) }
            }
            group.fanout.retireTarget(target, CameraEndReason.ORIGINAL_INPUT_CLOSED)
            if (synchronized(gate) { closing && group.members.isEmpty() }) retireGroup(group, CameraEndReason.PROJECTION_CLOSED)
            lease.requestClose(); publishStatus(); return
        }
        val admission = synchronized(gate) {
            val group = groupFor(lease.registration)
            // addTarget only enrolls/starts its owned lane; no GL/native wait occurs here.
            // Retirement cannot close/exit that lane between group membership and enrollment.
            val result = group.fanout.addTarget(target)
            if (result.accepted && !closing && !group.retired && !target.snapshot().admissionClosed)
                group.members[lease] = target
            ownership.changedLocked()
            group to result
        }
        val group = admission.first
        if (!admission.second.accepted) recordFailure(IllegalStateException("Original bound target enrollment remains pending"))
        if (target.snapshot().admissionClosed || synchronized(gate) { closing || group.members[lease] !== target }) {
            group.fanout.retireTarget(target, CameraEndReason.ORIGINAL_INPUT_CLOSED)
            if (synchronized(gate) { closing && group.members.isEmpty() }) retireGroup(group, CameraEndReason.PROJECTION_CLOSED)
            lease.requestClose(); publishStatus(); return
        }
        val shouldPublish = synchronized(gate) { !closing && !group.retired && group.use == null && group.members.isNotEmpty() }
        if (shouldPublish) {
            try {
                val use = roles.publishConfigured(group.original, sourceRequest, group.consumer)
                val stillLive = synchronized(gate) {
                    check(group.use == null || group.use === use)
                    group.use = use; ownership.changedLocked(); !closing && !group.retired && group.members.isNotEmpty()
                }
                if (stillLive) roles.start(use, sourceRequest) else roles.retire(use, CameraEndReason.ORIGINAL_RETIRED)
            } catch (error: Throwable) { recordFailure(error); retireGroup(group, CameraEndReason.PROTOCOL_FAILURE) }
        }
        publishStatus()
    }

    /** Caller holds gate; construction is inert. Each reopen after last retirement gets a fresh use. */
    private fun groupFor(registration: OwnedPhoneRegistration): Group {
        val old = active
        if (old != null && !old.retired && old.original.registration === registration) return old
        return Group(CapturedPhoneDemandGroup(originalSession, registration)).also { groups.add(it); active = it }
    }

    private fun retireInput(lease: PhoneCameraLease, reason: CameraEndReason) {
        val pair = synchronized(gate) {
            val target = targets[lease] ?: return
            target.closeAdmission(reason)
            val group = groups.firstOrNull { it.members[lease] === target }
            if (group != null) group.members.remove(lease)
            ownership.changedLocked()
            target to group
        }
        // No native waits, even when a GL call on that original is blocked.
        pair.second?.fanout?.retireTarget(pair.first, reason)
        pair.second?.let { retireGroup(it, reason, onlyIfEmpty = true) }
        publishStatus()
    }

    private fun retireGroup(group: Group, reason: CameraEndReason, onlyIfEmpty: Boolean = false) {
        val captured = synchronized(gate) {
            if (group.retired || (onlyIfEmpty && group.members.isNotEmpty())) return
            group.retired = true
            if (active === group) active = null
            val epochs = group.epochs.toList()
            epochs.forEach { it.stopAdmission() }
            group.members.values.forEach { it.closeAdmission(reason) }
            val members = group.members.values.toList()
            group.members.clear(); ownership.changedLocked()
            Triple(group.use, epochs, members)
        }
        captured.first?.let { roles.retire(it, reason) }
        captured.second.forEach { stopEpoch(group, it, reason) }
        captured.third.forEach { group.fanout.retireTarget(it, reason) }
        // Native cleanup/fences/joins proceed independently on fanout/decoder lanes.
        group.fanout.closeAsync(Executor { it.run() }) {
            group.closeObserved.countDown() // This callback follows the original GL lane's actual join.
            publishStatus()
        }
        publishStatus()
    }

    /** A captured callback can precede publishConfigured returning after PREPARE became visible. */
    private fun matchesUse(group: Group, use: PublishedCameraUse): Boolean {
        // Caller holds gate. Both claims are real local originals, never equal-valued wire DTOs.
        if (use.owner !== roles || use.original !== group.original) return false
        if (group.use == null) { group.use = use; ownership.changedLocked() }
        return group.use === use
    }

    private fun describe(group: Group, use: PublishedCameraUse, role: PendingCameraRole,
        stream: CameraStreamRef, description: CameraStreamDescription) {
        val pair = synchronized(gate) {
            if (closing || group.retired || !matchesUse(group, use) || stream.use != use.ref || group.endedRoles.containsKey(role)) return
            val previous = group.current
            if (previous != null && previous.original.role !== role) group.endedRoles[previous.original.role] = true
            val record = EpochRecord(PhoneDecoderEpoch(group.original, role, stream, description))
            group.epochs.add(record); group.current = record
            previous?.stopAdmission(); ownership.changedLocked()
            record to previous
        }
        pair.second?.let { stopEpoch(group, it, CameraEndReason.REPLACED) }
        if (!worker.post {
                val record = pair.first
                if (synchronized(record.ready) { record.closed }) return@post
                group.fanout.prepareDecoderSurface(record.original) { destination ->
                    val decoder = PhoneCameraDecoder(record.original, description, destination) { observation ->
                        val updates = synchronized(gate) {
                            record.observation = observation
                            if (observation.stage == "OUTPUT_RENDER_RETURNED") {
                                record.ingress = record.ingress.copy(outputObservations = observation.renderedOutputs,
                                    lastOutputPtsUs = observation.rawPtsUs, lastOutputObservationMonoNs = System.nanoTime())
                            }
                            val format = observation.actualFormat?.takeIf { it !== record.formatPublished }
                            if (format != null) record.formatPublished = format
                            val fence = observation.useStopped?.takeIf { !record.fencePublished }
                            if (fence != null) record.fencePublished = true
                            ownership.changedLocked()
                            format to fence
                        }
                        updates.first?.let { group.fanout.updateFormat(record.original, it) }
                        updates.second?.let { group.fanout.decoderStopped(record.original, it) }
                        observation.failure?.let { error ->
                            record.failed(error); record.stopAdmission(); recordFailure(error)
                            record.original.role.requestStop(CameraEndReason.ENCODER_FAILURE)
                        }
                        publishStatus()
                    }
                    val admitted = record.install(decoder)
                    if (admitted) decoder.start() else decoder.requestStop(record.original, CameraEndReason.ORIGINAL_RETIRED)
                    record.activated() // No reader can queue before start() has claimed the original lane.
                    if (synchronized(record.ready) { record.closed }) {
                        decoder.requestStop(record.original, CameraEndReason.ORIGINAL_RETIRED)
                        closeDecoder(group, record)
                    }
                }
            }) pair.first.failed(IllegalStateException("Original decoder preparation was not admitted"))
        publishStatus()
    }

    private fun access(group: Group, use: PublishedCameraUse, role: PendingCameraRole,
        stream: CameraStreamRef, value: CameraAccessUnit) {
        val receivedAt = System.nanoTime()
        val record = synchronized(gate) {
            val current = group.current
            if (closing || group.retired || !matchesUse(group, use) || current == null || current.original.role !== role ||
                current.original.stream != stream || value.formatGeneration != current.original.description.formatGeneration)
                null else current.also {
                    it.ingress = it.ingress.copy(receivedAccessUnits = it.ingress.receivedAccessUnits + 1,
                        waitingSequence = value.sequence, lastReceivedSequence = value.sequence,
                        lastReceivedPtsUs = value.ptsUs, lastReceiveMonoNs = receivedAt)
                    ownership.changedLocked()
                }
        } ?: return
        publishStatus() // Queue a dirty signal only, so a pending acceptance wait remains observable.
        try {
            val result = record.awaitDecoder()?.queue(record.original, value)
            val returnedAt = System.nanoTime()
            synchronized(gate) {
                var next = record.ingress.copy(lastSubmissionFact = result?.fact)
                if (result?.fact == CameraFact.OBSERVED_RETURN) {
                    val waitNs = returnedAt - receivedAt
                    next = next.copy(acceptedAccessUnits = next.acceptedAccessUnits + 1,
                        lastAcceptedSequence = value.sequence, lastAcceptedPtsUs = value.ptsUs,
                        lastAcceptanceReturnMonoNs = returnedAt, lastAcceptanceWaitNs = waitNs,
                        maxAcceptanceWaitNs = maxOf(next.maxAcceptanceWaitNs, waitNs),
                        totalAcceptanceWaitNs = next.totalAcceptanceWaitNs + waitNs)
                }
                record.ingress = next
                ownership.changedLocked()
            }
        }
        catch (error: InterruptedException) { Thread.currentThread().interrupt(); record.failed(error); stopEpoch(group, record, CameraEndReason.TRANSPORT_EOF) }
        catch (error: Throwable) { record.failed(error); recordFailure(error); stopEpoch(group, record, CameraEndReason.ENCODER_FAILURE) }
        finally {
            synchronized(gate) {
                record.ingress = record.ingress.copy(waitingSequence = null)
                ownership.changedLocked()
            }
            publishStatus()
        }
    }

    private fun endStream(group: Group, use: PublishedCameraUse, role: PendingCameraRole, end: CameraMediaEnd) {
        val stream = end.stream ?: return
        val records = synchronized(gate) {
            if (!matchesUse(group, use) || end.use != use.ref) return
            group.epochs.filter { it.original.role === role &&
                it.original.stream.sourceGeneration == stream.first && it.original.stream.encoderGeneration == stream.second }
                .also { originals ->
                    originals.forEach(EpochRecord::stopAdmission)
                    if (originals.any { it === group.current }) group.current = null
                    ownership.changedLocked()
                }
        }
        records.forEach { stopEpoch(group, it, end.reason) }
        publishStatus() // A stream END is not a logical role retirement or a Phone input close.
    }

    private fun endRole(group: Group, use: PublishedCameraUse, role: PendingCameraRole, reason: CameraEndReason) {
        val records = synchronized(gate) {
            if (!matchesUse(group, use)) return
            group.endedRoles[role] = true
            group.epochs.filter { it.original.role === role }.also {
                it.forEach(EpochRecord::stopAdmission)
                if (group.current?.original?.role === role) group.current = null
                ownership.changedLocked()
            }
        }
        records.forEach { stopEpoch(group, it, reason) }
        publishStatus() // Actual target membership remains, allowing a fresh ingress for the same use.
    }

    private fun stopEpoch(group: Group, record: EpochRecord, reason: CameraEndReason) {
        record.stopAdmission()
        group.fanout.closeDecoderAdmission(record.original) // Render admission is separate from codec's use fence.
        val decoder = synchronized(record.ready) { record.decoder }
        if (decoder != null) { decoder.requestStop(record.original, reason); closeDecoder(group, record) }
    }
    private fun closeDecoder(group: Group, record: EpochRecord) {
        val decoder = synchronized(record.ready) {
            if (record.joinRequested) null else record.decoder?.also { record.joinRequested = true }
        }
        decoder?.closeAsync(record.original, Executor { it.run() }) {
            // Snapshot already contains actual codec-release and actual join observations separately.
            record.closeObserved.countDown()
            publishStatus()
        }
    }

    fun requestClose(reason: CameraEndReason): PhoneMediaStopSubmission {
        val captured = synchronized(gate) {
            if (closing) return PhoneMediaStopSubmission(CameraFact.STALE_OR_DUPLICATE)
            closing = true
            groups.flatMap { it.epochs }.forEach { it.stopAdmission() }
            targets.values.forEach { it.closeAdmission(reason) }
            ownership.changedLocked()
            slot.original to groups.toList()
        }
        captured.first?.requestClose()
        captured.second.forEach { retireGroup(it, reason) }
        // FIFO sentinel covers queued/current publication and configured-input claims, without a timer/join on caller.
        if (!worker.post { ownerDrained.countDown() })
            recordFailure(IllegalStateException("Original owner drain remains unconfirmed"))
        worker.closeWhenIdle() // Late closing events remain cleanup-only above.
        publishStatus()
        return PhoneMediaStopSubmission(CameraFact.PENDING)
    }

    /** Wait only off-caller for the captured original media joins; native registration can remain pending. */
    fun closeAsync(executor: Executor, completion: (PhoneMediaCloseObservation) -> Unit) {
        requestClose(CameraEndReason.ORIGINAL_RETIRED)
        val original = Thread({
            try {
                ownerDrained.await() // Admission is closed; after this no new group/prepare can be enrolled.
                val originals = synchronized(gate) { groups.toList() }
                originals.forEach { it.closeObserved.await() }
                originals.flatMap { synchronized(gate) { it.epochs.toList() } }.forEach { record ->
                    if (synchronized(record.ready) { record.decoder != null }) record.closeObserved.await()
                }
                val observation = PhoneMediaCloseObservation(snapshot())
                val once = AtomicBoolean()
                try { executor.execute {
                    if (once.compareAndSet(false, true)) try { completion(observation) }
                    catch (error: Throwable) { recordObserverFailure(error) }
                } }
                catch (error: Throwable) { recordObserverFailure(error) }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt(); recordFailure(error)
            }
        }, "phone-camera-close-observation")
        try { original.start() } catch (error: Throwable) { recordFailure(error) }
    }

    /** Resource-complete retirement of this original, in addition to the old media-only close. */
    fun closeResourcesAsync(executor: Executor, completion: (PhoneMediaCloseObservation) -> Unit) {
        // closeAsync supplies its own off-caller coordinator and waits for captured codec/GL joins.
        closeAsync(Executor { it.run() }) {
            val original = synchronized(gate) { slot.original }
            val once = AtomicBoolean()
            val observation = try {
                val invokedWithoutOriginal = synchronized(gate) { slot.invocationStarted && original == null }
                if (invokedWithoutOriginal) {
                    PhoneMediaCloseObservation(snapshot(),
                        IllegalStateException("Original registration outcome remains unresolved"))
                } else {
                    // Condition wait releases the original registration monitor; no native call or polling.
                    val fresh = original?.awaitResourceRelease()
                    synchronized(gate) {
                        if (slot.original === original && fresh != null &&
                            (observedRegistration == null || fresh.revision >= observedRegistration!!.revision)) {
                            observedRegistration = fresh
                            ownership.changedLocked()
                        }
                    }
                    // A media sentinel is earlier than the actual owner join. Only this
                    // resource-complete, off-caller coordinator retires the persistent status lane.
                    worker.awaitJoined()
                    val media = snapshot()
                    val mediaClear = media.groups.all { group ->
                        group.retired && group.fanout.nativeResourcesClear && group.fanout.joined &&
                            group.decoders.all { decoder -> decoder.useStopped?.let { stopped ->
                                decoder.joined || stopped.noConfigureAttempted && !decoder.threadEntered
                            } == true }
                    }
                    if (!mediaClear) throw IllegalStateException("Original media resource retirement remains incomplete")
                    closeStatusLane()
                    // Preserve the exact fresh original/as-of revision rather than a delayed status cache.
                    PhoneMediaCloseObservation(snapshot().copy(registration = fresh))
                }
            } catch (error: Throwable) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                recordFailure(error)
                PhoneMediaCloseObservation(snapshot().copy(registration = original?.snapshot()), error)
            }
            try {
                executor.execute {
                    if (once.compareAndSet(false, true)) try { completion(observation) }
                    catch (error: Throwable) { recordObserverFailure(error) }
                }
            } catch (error: Throwable) { recordObserverFailure(error) }
        }
    }

    fun snapshot(): PhoneCameraMediaSnapshot {
        val fields = synchronized(gate) {
            PhoneCameraMediaSnapshot(originalSession, ownership.revision, started, closing, observedRegistration,
                targets.keys.map { it.snapshot() }, groups.map { group ->
                    PhoneMediaGroupSnapshot(group.original, group.use?.ref, group.members.keys.toList(), group.retired,
                        group.epochs.mapNotNull { it.observation }, listOfNotNull(group.lastFanout), group.fanout.snapshot(),
                        group.epochs.map { it.ingress })
                }, primaryFailure, observerFailures.toList(), worker.snapshot(), status.snapshot(), listOf(status.snapshot()),
                sampleMonoNs = System.nanoTime(), statusProgress = PhoneCameraStatusProgress(statusRequests,
                    statusSnapshots, statusPublications, lastSnapshotDurationNs, maxSnapshotDurationNs,
                    lastPublicationDurationNs, maxPublicationDurationNs, statusSealed))
        }
        return fields
    }
    private fun recordFailure(error: Throwable) {
        synchronized(gate) { if (primaryFailure == null) primaryFailure = error; ownership.changedLocked() }
        publishStatus()
    }
    private fun recordObserverFailure(error: Throwable) {
        synchronized(gate) { observerFailures.add(error); ownership.changedLocked() }
    }
    private fun publishStatus() {
        val enqueue = synchronized(gate) {
            if (statusSealed) return
            statusRequests++
            statusDirty = true
            if (statusDrainOwned) false else { statusDrainOwned = true; true }
        }
        if (enqueue && !status.post(::drainStatus)) {
            val sealed = synchronized(gate) { statusDrainOwned = false; statusSealed }
            // A publisher claimed just before final sealing can arrive after closeWhenIdle.
            // The final off-caller snapshot covers it; never restart a retired original lane.
            if (!sealed) recordObserverFailure(IllegalStateException("As-of status sink no longer admitted"))
        }
    }

    private fun drainStatus() {
        while (true) {
            val dirty = synchronized(gate) {
                if (statusDirty) { statusDirty = false; true }
                else { statusDrainOwned = false; false }
            }
            if (!dirty) return // The same original lane sleeps until another publication request.
            publishStatusSnapshot()
        }
    }

    /** All full snapshots and sink I/O run on the status lane or its final off-caller coordinator. */
    private fun publishStatusSnapshot() {
        try {
            val begin = System.nanoTime()
            val value = snapshot()
            val snapshotReturned = System.nanoTime()
            synchronized(gate) {
                statusSnapshots++
                lastSnapshotDurationNs = snapshotReturned - begin
                maxSnapshotDurationNs = maxOf(maxSnapshotDurationNs, lastSnapshotDurationNs)
            }
            val publicationBegin = System.nanoTime()
            try {
                observations(value)
                synchronized(gate) { statusPublications++ }
            } finally {
                synchronized(gate) {
                    lastPublicationDurationNs = System.nanoTime() - publicationBegin
                    maxPublicationDurationNs = maxOf(maxPublicationDurationNs, lastPublicationDurationNs)
                }
            }
        } catch (error: Throwable) { recordObserverFailure(error) }
    }

    private fun closeStatusLane() {
        val claimed = synchronized(gate) {
            if (statusCloseClaimed) false else {
                statusCloseClaimed = true
                statusSealed = true
                ownership.changedLocked()
                true
            }
        }
        if (!claimed) {
            statusCloseFinished.await()
            synchronized(gate) { statusCloseFailure }?.let { throw it }
            return
        }
        try {
            status.closeWhenIdle()
            status.awaitJoined()
            synchronized(gate) { statusDirty = false; statusDrainOwned = false }
            // This final publication can truthfully include the status worker's own joined fact.
            publishStatusSnapshot()
        } catch (error: Throwable) {
            synchronized(gate) { statusCloseFailure = error }
            throw error
        } finally { statusCloseFinished.countDown() }
    }
}
