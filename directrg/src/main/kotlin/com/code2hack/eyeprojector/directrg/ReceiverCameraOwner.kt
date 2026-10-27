package com.code2hack.eyeprojector.directrg

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import com.code2hack.eyeprojector.directrg.localcamera.*
import com.code2hack.eyeprojector.directrg.localcamera.acquisition.*
import com.code2hack.eyeprojector.directrg.localcamera.forwarding.*
import com.code2hack.eyeprojector.protocol.*
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** One original projection attachment; numerical camera-use references never select this object. */
internal class ReceiverCameraProjection internal constructor(
    private val owner: ReceiverCameraOwner,
    internal val token: Any,
) {
    /** Sticky after actual/pending source work, including work already physically retired. */
    val hasPhysicalDuty get() = owner.hasPhysicalDuty(token)
    fun retire(reason: CameraEndReason) = owner.retire(token, reason)
    fun closeAsync(executor: Executor, completion: (ReceiverCameraCloseObservation) -> Unit) =
        owner.closeProjectionAsync(token, executor, completion)
}

/**
 * Connected service-owned use/resource owner. The service lane admits demand and permission;
 * actual renderer/Camera2 preparation and all joins run on owned background paths. Every native
 * partial result is installed into its original record before callbacks can retire it.
 */
internal class ReceiverCameraOwner(
    private val context: Context,
    private val deliver: ((() -> Unit) -> Unit),
    private val promoteForegroundCamera: () -> Unit,
    private val changed: () -> Unit = {},
) {
    companion object { private val permissionCodes = AtomicInteger(0x6800) }
    private class Completion(val executor: Executor, val callback: (ReceiverCameraCloseObservation) -> Unit)
    private class Host(val identity: Any, val request: (ReceiverCameraPermissionRequest) -> Unit)
    private class ProjectionRecord(val original: Any, val uses: ReceiverCameraRoleClient,
        val output: LocalPreviewOutput, val outputIdentity: Any) {
        lateinit var handle: ReceiverCameraProjection
        var retired = false
        var physicalDuty = false
        val members = IdentityHashMap<CapturedPhoneCameraUse, Member>()
        val sources = ArrayList<Source>()
        val selections = ArrayList<Selection>()
        val completions = ArrayList<Completion>()
        val failures = ArrayList<ReceiverCameraOwnerFailure>()
    }
    private class Member(val projection: ProjectionRecord, val original: CapturedPhoneCameraUse) {
        var retired = false
        var permissionDenied = false
        var admissionFailed = false
        var permissionInvocationFailed = false
        var source: Source? = null
        var attempt: Attempt? = null
        var selection: Selection? = null
        var pendingRecovery: Attempt? = null
    }
    private class Selection(val member: Member, val request: CameraStreamRequest) {
        val task = NativeTask("source-selection")
        var retired = false
        var installationAccounted = false
    }
    private class Attempt(val member: Member, val source: Source,
        val attachment: EncoderCameraMediaSource.Attachment) {
        var role: OwnedCameraRole? = null
        var connecting = true
        var retired = false
        var closeClaimed = false
        var closeObservation: RoleCloseObservation? = null
        var observation: RoleCloseObservation? = null
    }
    private class NativeTask(val name: String) {
        var entered = false
        var returned = false
        var joined = false
        var notStarted = false
        var joinFailed = false
    }
    private class Source(val projection: ProjectionRecord, val profile: ReceiverCameraProfile,
        val key: CameraSourceKey, val encoderGeneration: Long) {
        val identity = Any()
        val members = LinkedHashSet<Member>()
        val attempts = ArrayList<Attempt>()
        val tasks = ArrayList<NativeTask>()
        val failures = ArrayList<ReceiverCameraOwnerFailure>()
        var setupClaimed = false
        var setupReturned = false
        var retired = false
        var reason = CameraEndReason.UNKNOWN
        var hub: EncoderCameraMediaSource? = null
        var local: LocalCameraInput? = null
        var encoder: CameraSurfaceEncoder? = null
        var run: OwnedCameraRun? = null
        var producerStartEntered = false
        var producerStartClaimed = false
        var producerStartReturned = false
        var cameraOwnership: CameraOwnershipSnapshot? = null
        var outputsSafe: CameraOutputsSafe? = null
        var cameraJoined = false
        var encoderRelease: EncoderReleaseResult? = null
        var localRelease: LocalInputReleaseResult? = null
        var outputRelease: LocalOutputReleaseResult? = null
        var legacyLocalCloseReturned: Boolean? = null
        var cameraStopClaimed = false
        var hubRetireClaimed = false
        var encoderCloseClaimed = false
        var localCloseClaimed = false
        var contentionReported = false
    }

    private val gate = Any()
    private val projections = IdentityHashMap<Any, ProjectionRecord>()
    private val completions = ArrayList<Completion>()
    private val failures = ArrayList<ReceiverCameraOwnerFailure>()
    private var closed = false
    private var host: Host? = null
    private var permissionRequest: ReceiverCameraPermissionRequest? = null
    private var sourceGeneration = 0L
    private var encoderGeneration = 0L
    private val direct = Executor { it.run() }
    private val selector = ReceiverCameraSelector(context)

    fun attachProjection(original: ProjectionSession, output: LocalPreviewOutput,
        originalOutputIdentity: Any): ReceiverCameraProjection =
        attachCapturedProjection(original, original.cameraUses, output, originalOutputIdentity)

    /** Same engine for an actual captured role client; it does not fabricate a use or selector. */
    internal fun attachCapturedProjection(originalIdentity: Any, cameraUses: ReceiverCameraRoleClient,
        output: LocalPreviewOutput, originalOutputIdentity: Any): ReceiverCameraProjection {
        val selected = synchronized(gate) {
            projections[originalIdentity]?.let {
                check(it.uses === cameraUses && it.output === output && it.outputIdentity === originalOutputIdentity)
                return@synchronized it to false
            }
            check(!closed) { "Receiver camera owner closed" }
            val next = ProjectionRecord(originalIdentity, cameraUses, output, originalOutputIdentity)
            next.handle = ReceiverCameraProjection(this, next)
            projections[originalIdentity] = next // Before observer installation or callback-capable work.
            next to true
        }
        if (selected.second) cameraUses.setUseObserver { event -> post(selected.first) { use(selected.first, event) } }
        return selected.first.handle
    }

    fun retireProjection(original: ProjectionSession, reason: CameraEndReason): ReceiverCameraProjection? {
        val record = synchronized(gate) { projections[original] } ?: return null
        retire(record, reason)
        return record.handle
    }

    fun outputReleased(original: ProjectionSession, output: LocalPreviewOutput, result: LocalOutputReleaseResult) =
        outputReleasedCaptured(original, output, result)

    internal fun outputReleasedCaptured(originalIdentity: Any, output: LocalPreviewOutput,
        result: LocalOutputReleaseResult) {
        synchronized(gate) {
            val record = projections[originalIdentity] ?: return
            if (record.output !== output) return
            for (source in record.sources) {
                val local = source.local ?: continue
                val inputResult = result.inputs.firstOrNull { it.input === local } ?: continue
                source.outputRelease = result
                source.localRelease = inputResult
            }
        }
        post { evaluate() }
    }

    internal fun hasPhysicalDuty(token: Any): Boolean = synchronized(gate) { (token as ProjectionRecord).physicalDuty }

    internal fun retire(token: Any, reason: CameraEndReason) {
        val record = token as ProjectionRecord
        val members = synchronized(gate) {
            record.retired = true
            if (permissionRequest?.projectionIdentity === record) permissionRequest = null
            record.members.values.toList()
        }
        record.uses.setUseObserver(null)
        members.forEach { retireMember(it, reason, retireUse = true) }
        synchronized(gate) { record.sources.toList() }.forEach { retireSource(it, reason) }
        evaluate()
    }

    internal fun closeProjectionAsync(token: Any, executor: Executor,
        completion: (ReceiverCameraCloseObservation) -> Unit) {
        val record = token as ProjectionRecord
        synchronized(gate) { record.completions.add(Completion(executor, completion)) }
        retire(record, CameraEndReason.ORIGINAL_RETIRED)
    }

    fun attachPermissionHost(identity: Any, request: (ReceiverCameraPermissionRequest) -> Unit) {
        synchronized(gate) { if (!closed) {
            host = Host(identity, request)
            // A new observed Activity attachment may retry a known pre-request failure.
            // It never resolves an uncertain native camera operation or grants permission.
            projections.values.filter { !it.retired }.forEach { record ->
                record.members.values.filter { !it.retired && it.permissionInvocationFailed }.forEach {
                    it.permissionInvocationFailed = false; it.admissionFailed = false
                }
            }
        } }
        post { resumeDemand() }
    }

    fun detachPermissionHost(identity: Any) {
        synchronized(gate) {
            if (host?.identity === identity) {
                host = null
                if (permissionRequest?.hostIdentity === identity) permissionRequest = null
            }
        }
    }

    fun permissionResult(originalRequest: ReceiverCameraPermissionRequest, granted: Boolean) {
        val member = synchronized(gate) {
            val current = permissionRequest
            if (current !== originalRequest || host?.identity !== originalRequest.hostIdentity) null
            else {
                permissionRequest = null
                val record = originalRequest.projectionIdentity as? ProjectionRecord
                record?.members?.get(originalRequest.original)?.takeIf { !record.retired && !it.retired }
            }
        } ?: return
        if (!member.original.live) return
        val actual = granted && permissionGranted(member.projection)
        synchronized(gate) { member.permissionDenied = !actual }
        report(member, CameraStage.PERMISSION, if (actual) CameraFact.OBSERVED_RETURN else CameraFact.UNAVAILABLE)
        post(member.projection) { resumeDemand() }
    }

    fun permissionRequestCurrent(originalRequest: ReceiverCameraPermissionRequest): Boolean {
        val member = synchronized(gate) {
            val record = originalRequest.projectionIdentity as? ProjectionRecord
            if (closed || permissionRequest !== originalRequest || host?.identity !== originalRequest.hostIdentity ||
                record == null || record.retired) null
            else record.members[originalRequest.original]?.takeIf { !it.retired }
        } ?: return false
        if (!member.original.live) return false
        return synchronized(gate) { !closed && permissionRequest === originalRequest &&
            host?.identity === originalRequest.hostIdentity && !member.retired && !member.projection.retired }
    }

    fun permissionRequestFailed(originalRequest: ReceiverCameraPermissionRequest, error: Throwable) {
        if (!permissionRequestCurrent(originalRequest)) return
        val member = synchronized(gate) {
            if (permissionRequest !== originalRequest) null else {
                permissionRequest = null
                (originalRequest.projectionIdentity as ProjectionRecord).members[originalRequest.original]
                    ?.also { it.admissionFailed = true; it.permissionInvocationFailed = true }
            }
        } ?: return
        recordFailure(member.projection, "permission-request-invocation", error)
        report(member, CameraStage.PERMISSION, CameraFact.FAILED)
        post(member.projection) { resumeDemand() }
    }

    private fun permissionGranted(record: ProjectionRecord): Boolean = try {
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    } catch (error: Throwable) { recordFailure(record, "permission-observation", error); false }

    private fun use(record: ProjectionRecord, event: CameraUseEvent) {
        if (synchronized(gate) { closed || record.retired }) return
        if (event.fact == CameraFact.STALE_OR_DUPLICATE) return
        when (event.message) {
            is CameraUseMessage.Close -> event.original?.let {
                synchronized(gate) { record.members[it] }?.let { member -> retireMember(member, event.message.reason, true) }
            }
            is CameraUseMessage.Replace -> {
                event.original?.let { synchronized(gate) { record.members[it] } }
                    ?.let { retireMember(it, event.message.reason, true) }
                event.replacement?.takeIf { it.live }?.let { member(record, it)?.let(::admit) }
            }
            is CameraUseMessage.Prepare, is CameraUseMessage.Start ->
                event.original?.takeIf { it.live }?.let { member(record, it)?.let(::admit) }
            is CameraUseMessage.Update -> event.original?.takeIf { it.live }?.let { original ->
                val member = member(record, original) ?: return@let
                synchronized(gate) {
                    member.admissionFailed = false; member.retired = false
                    member.selection?.let { it.retired = true; it.installationAccounted = true }
                    member.selection = null
                }
                val current = synchronized(gate) { member.source }
                if (current != null && current.profile.compatibleWith(original.request)) {
                    // The native profile and admitted encoded stream are unchanged.
                    // Keep its captured role; target-size updates need no socket handover.
                    attachRole(member, current)
                } else {
                    retireMember(member, CameraEndReason.REPLACED, false)
                    admit(member)
                }
            }
        }
        evaluate()
    }

    private fun member(record: ProjectionRecord, original: CapturedPhoneCameraUse): Member? = synchronized(gate) {
        if (closed || record.retired) null else record.members.getOrPut(original) { Member(record, original) }
    }

    private fun resumeDemand() {
        val members = synchronized(gate) { projections.values.filter { !it.retired }.flatMap { it.members.values.toList() } }
        members.filter { !it.retired && it.original.live }.forEach(::admit)
        evaluate()
    }

    private fun admit(member: Member) {
        if (!member.original.live || synchronized(gate) {
                closed || member.retired || member.projection.retired || member.admissionFailed ||
                    member.source != null || member.selection != null || member.pendingRecovery != null
            }) return
        if (!permissionGranted(member.projection)) {
            val prompt = synchronized(gate) {
                val selectedHost = host
                if (member.permissionDenied || selectedHost == null || permissionRequest != null) null else {
                    val request = ReceiverCameraPermissionRequest(member.projection, member.original,
                        selectedHost.identity, permissionCodes.getAndIncrement())
                    permissionRequest = request
                    selectedHost to request
                }
            }
            report(member, CameraStage.PERMISSION, CameraFact.PENDING)
            if (prompt != null) try { prompt.first.request(prompt.second) }
                catch (error: Throwable) { permissionRequestFailed(prompt.second, error) }
            return
        }
        report(member, CameraStage.PERMISSION, CameraFact.OBSERVED_RETURN)
        if (!member.original.started) return // PREPARE never becomes physical source demand.
        val shared = synchronized(gate) { member.projection.sources.firstOrNull {
            !it.retired && it.profile.compatibleWith(member.original.request)
        } }
        if (shared != null) { joinSource(member, shared); return }
        val selection = synchronized(gate) {
            if (closed || member.retired || member.projection.retired || member.selection != null) null else {
                Selection(member, member.original.request).also {
                    member.selection = it; member.projection.selections.add(it)
                    member.projection.physicalDuty = true
                }
            }
        } ?: return
        launchSelection(selection)
    }

    private fun finishSelection(selection: Selection, profile: ReceiverCameraProfile?, error: Throwable?) {
        val member = selection.member
        val live = member.original.live && member.original.started
        val current = synchronized(gate) {
            selection.installationAccounted = true
            val accepted = !selection.retired && member.selection === selection && !closed &&
                !member.retired && !member.projection.retired && live &&
                member.original.request == selection.request
            if (member.selection === selection) member.selection = null
            if (accepted && error != null) member.admissionFailed = true
            accepted
        }
        if (error != null) recordFailure(member.projection, "source-selection", error)
        if (!current) { evaluate(); return }
        if (profile == null) {
            report(member, CameraStage.SOURCE_PREPARATION, CameraFact.UNAVAILABLE)
            evaluate(); return
        }
        val source = synchronized(gate) {
            if (closed || member.retired || member.projection.retired || !member.original.live ||
                !member.original.started || member.original.request != selection.request) null else {
                // Another selection can install a compatible native source while this
                // catalog read is pending. Phone dimensions remain an initial hint.
                val old = member.projection.sources.firstOrNull {
                    !it.retired && it.profile.compatibleWith(member.original.request)
                }
                old ?: Source(member.projection, profile,
                    CameraSourceKey(CameraUseKey(member.original.ref.ownerEpoch, member.original.ref.useGeneration), ++sourceGeneration),
                    ++encoderGeneration).also { member.projection.sources.add(it) }
            }
        } ?: return
        joinSource(member, source)
        startEligibleSources()
    }

    private fun launchSelection(selection: Selection) {
        val member = selection.member
        val task = selection.task
        val worker = try { Thread({
            synchronized(gate) { task.entered = true }
            var profile: ReceiverCameraProfile? = null
            var failure: Throwable? = null
            try {
                val admitted = synchronized(gate) { !closed && !selection.retired && !member.retired && !member.projection.retired }
                if (admitted && member.original.live && member.original.started) profile = selector.select(selection.request)
            } catch (error: Throwable) { failure = error }
            finally {
                synchronized(gate) { task.returned = true }
                val observed = profile; val problem = failure
                post(member.projection) { finishSelection(selection, observed, problem) }
            }
        }, "eyeprojector-camera-source-selection").apply { isDaemon = true } }
        catch (error: Throwable) {
            synchronized(gate) { task.notStarted = true; selection.installationAccounted = true; member.admissionFailed = true; if (member.selection === selection) member.selection = null }
            recordFailure(member.projection, "source-selection-create", error)
            evaluate(); return
        }
        try { worker.start() }
        catch (error: Throwable) {
            synchronized(gate) {
                task.notStarted = worker.state == Thread.State.NEW && !task.entered
                if (task.notStarted) { selection.installationAccounted = true; member.admissionFailed = true; if (member.selection === selection) member.selection = null }
            }
            recordFailure(member.projection, "source-selection-start", error)
        }
        if (synchronized(gate) { task.notStarted }) { evaluate(); return }
        try { Thread({
            try { worker.join(); synchronized(gate) { task.joined = !worker.isAlive } }
            catch (error: Throwable) { synchronized(gate) { task.joinFailed = true }; recordFailure(member.projection, "source-selection-join", error) }
            post { evaluate() }
        }, "eyeprojector-camera-selection-join").apply { isDaemon = true; start() } }
        catch (error: Throwable) { synchronized(gate) { task.joinFailed = true }; recordFailure(member.projection, "source-selection-join-start", error) }
    }

    private fun joinSource(member: Member, source: Source) {
        val join = synchronized(gate) {
            if (source.retired || member.retired || member.projection.retired) false else {
                source.members.add(member); member.source = source; true
            }
        }
        if (join) { attachRole(member, source); startEligibleSources() }
    }

    /** Source contention follows actual renderer/device ownership, without a use-count limit. */
    private fun startEligibleSources() {
        val sources = synchronized(gate) { projections.values.flatMap { it.sources.toList() } }
        for (source in sources) {
            migrateCompatibleDemand(source)
            val liveDemand = synchronized(gate) { source.members.toList() }.any {
                !it.retired && it.original.live && it.original.started
            }
            val claimed = synchronized(gate) {
                val conflicting = projections.values.any { record -> record.sources.any {
                    it !== source && it.setupClaimed && pendingLocked(it).isNotEmpty() &&
                        (it.profile.cameraId == source.profile.cameraId || it.projection.outputIdentity === source.projection.outputIdentity)
                } }
                if (closed || source.projection.retired || source.retired || source.setupClaimed ||
                    !liveDemand || source.members.isEmpty() || conflicting) false else {
                    source.setupClaimed = true; source.projection.physicalDuty = true; true
                }
            }
            if (!claimed) continue
            synchronized(gate) { source.contentionReported = false }
            try { promoteForegroundCamera() }
            catch (error: Throwable) {
                synchronized(gate) { source.setupReturned = true }
                sourceFailure(source, "foreground-camera", error, CameraEndReason.FOREGROUND_UNAVAILABLE)
                continue
            }
            synchronized(gate) { source.members.toList() }.forEach { report(it, CameraStage.FOREGROUND_CAMERA, CameraFact.OBSERVED_RETURN) }
            launch(source, "source-prepare") {
                try {
                    if (synchronized(gate) { source.retired }) return@launch
                    val input = source.projection.output.openLocalCamera(LocalCameraConfiguration(source.key,
                        source.profile.sampling, source.profile.localSize.width, source.profile.localSize.height),
                        object : LocalCameraObserver {
                            override fun failed(failure: LocalCameraFailure) {
                                sourceFailure(source, "local-" + failure.operation, failure.error, CameraEndReason.SOURCE_UNAVAILABLE)
                            }
                        })
                    synchronized(gate) { source.local = input }
                    if (input !is ProducerRetirementInput) {
                        sourceFailure(source, "local-retirement-capability", IllegalStateException("Actual Camera2 local input cannot bind its original producer"), CameraEndReason.SOURCE_UNAVAILABLE)
                        return@launch
                    }
                    if (synchronized(gate) { source.retired }) { driveRetirement(source); return@launch }
                    val hub = EncoderCameraMediaSource({ attachment, reason -> post(source.projection) {
                        val selected = synchronized(gate) { source.attempts.firstOrNull { it.attachment === attachment } }
                        if (selected != null) retireAttempt(selected, reason)
                    } }, { error -> sourceFailure(source, "encoder-media-source", error, CameraEndReason.ENCODER_FAILURE) })
                    val encoder = CameraSurfaceEncoder(SurfaceEncoderRequest(ForwardCameraStream(source.key, source.encoderGeneration),
                        source.profile.forwardOutput, source.profile.bitRate, source.profile.frameRate,
                        source.profile.iFrameIntervalSeconds), hub)
                    synchronized(gate) { source.encoder = encoder }
                    hub.bindEncoder(encoder)
                    synchronized(gate) { source.hub = hub }
                    post(source.projection) {
                        synchronized(gate) { source.members.toList() }.forEach { attachRole(it, source) }
                    }
                    if (synchronized(gate) { source.retired }) { driveRetirement(source); return@launch }
                    encoder.prepareAsync(direct) { prepared -> post(source.projection) {
                        if (!prepared.ready) sourceFailure(source, "encoder-prepare", null, CameraEndReason.ENCODER_FAILURE)
                        else startCamera(source)
                    } }
                } finally {
                    synchronized(gate) { source.setupReturned = true }
                    if (synchronized(gate) { source.retired }) driveRetirement(source)
                }
            }
        }
    }

    /** A real explicit native-profile change can hand over all compatible original members. */
    private fun migrateCompatibleDemand(destination: Source) {
        val oldSources = synchronized(gate) {
            if (destination.retired || destination.setupClaimed || destination.projection.retired || closed) emptyList()
            else destination.projection.sources.filter { it !== destination && !it.retired && it.setupClaimed }
        }
        for (old in oldSources) {
            val members = synchronized(gate) { old.members.toList() }.filter {
                !it.retired && it.original.live && it.original.started
            }
            if (members.isEmpty()) continue
            if (!members.all { destination.profile.compatibleWith(it.original.request) }) {
                val announceContention = synchronized(gate) {
                    if (destination.contentionReported) false else { destination.contentionReported = true; true }
                }
                if (announceContention) synchronized(gate) { destination.members.toList() }.forEach {
                    report(it, CameraStage.SOURCE_PREPARATION, CameraFact.UNAVAILABLE)
                }
                continue
            }
            val moved = synchronized(gate) {
                if (destination.retired || destination.setupClaimed || old.retired || destination.projection.retired) emptyList()
                else members.filter { it.source === old && !it.retired }.map { member ->
                    val attempt = member.attempt
                    member.attempt = null; attempt?.retired = true
                    old.members.remove(member); destination.members.add(member); member.source = destination
                    attempt
                }
            }
            // Original direct stop happens before old child/socket/encoder retirement.
            if (synchronized(gate) { old.members.isEmpty() }) retireSource(old, CameraEndReason.REPLACED)
            moved.filterNotNull().forEach { closeRole(it, CameraEndReason.REPLACED) }
        }
    }

    private fun startCamera(source: Source) {
        val input: LocalCameraInput
        val encoder: CameraSurfaceEncoder
        synchronized(gate) {
            if (source.retired || source.producerStartClaimed) return
            input = source.local ?: return
            encoder = source.encoder ?: return
            source.producerStartClaimed = true // Original pending worker exists before callbacks.
        }
        launch(source, "camera-start") {
            try {
                if (synchronized(gate) { source.retired }) {
                    synchronized(gate) { source.producerStartEntered = false }
                    return@launch
                }
                val live = synchronized(gate) { source.members.toList() }.any { it.original.live && it.original.started && !it.retired }
                if (!live) {
                    synchronized(gate) { source.producerStartEntered = false }
                    retireSource(source, CameraEndReason.ORIGINAL_RETIRED)
                    return@launch
                }
                val granted = try { context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED }
                catch (error: Throwable) {
                    sourceFailure(source, "permission-before-camera", error, CameraEndReason.SOURCE_UNAVAILABLE)
                    return@launch
                }
                if (!granted) {
                    synchronized(gate) { source.members.toList() }.forEach { report(it, CameraStage.PERMISSION, CameraFact.UNAVAILABLE) }
                    sourceFailure(source, "permission-observed-denied-before-camera", null, CameraEndReason.PERMISSION_DENIED)
                    return@launch
                }
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val events = object : CameraRunEvents {
                    override fun observed(original: OwnedCameraRun, observation: CameraRunObservation) {
                        captureCamera(source, original)
                        if (observation.stage == CameraRunStage.REPEATING_SUBMITTED) post(source.projection) {
                            synchronized(gate) { source.members.toList() }.forEach { report(it, CameraStage.SOURCE_START, CameraFact.OBSERVED_RETURN) }
                        }
                    }
                    override fun failed(original: OwnedCameraRun, failure: CameraRunFailure) {
                        captureCamera(source, original)
                        if (failure.operation != "capture-failed" && failure.operation != "capture-result")
                            sourceFailure(source, "camera-" + failure.operation, failure.error, CameraEndReason.SOURCE_UNAVAILABLE)
                        else sourceFailureRecord(source, "camera-" + failure.operation, failure.error)
                    }
                    override fun outputsSafe(original: OwnedCameraRun, fact: CameraOutputsSafe) {
                        captureCamera(source, original)
                        synchronized(gate) { if (source.run === original && fact.original === original.identity) source.outputsSafe = fact }
                        if (!synchronized(gate) { source.retired })
                            sourceFailure(source, "camera-ended", null, CameraEndReason.SOURCE_UNAVAILABLE)
                        post { evaluate() }
                    }
                }
                synchronized(gate) { source.producerStartEntered = true }
                val run = GlassesCameraProducer(manager).start(CameraUseDescription(source.key, source.profile.cameraId,
                    source.profile.localOutput, source.profile.forwardOutput, source.profile.sampling, source.profile.provenance),
                    CameraTargets(input, encoder), events)
                captureCamera(source, run)
            } finally {
                synchronized(gate) { source.producerStartReturned = true }
                if (synchronized(gate) { source.retired }) driveRetirement(source)
            }
        }
    }

    private fun captureCamera(source: Source, original: OwnedCameraRun) {
        val accepted = synchronized(gate) {
            if (source.run != null && source.run !== original) false else {
                source.run = original; source.cameraOwnership = original.snapshot
                source.cameraJoined = original.ownerThreadClosedObserved; true
            }
        }
        if (!accepted) { sourceFailure(source, "camera-original-mismatch", null, CameraEndReason.SOURCE_UNAVAILABLE); return }
        if (synchronized(gate) { source.retired }) driveRetirement(source)
        post { evaluate() }
    }

    private fun attachRole(member: Member, source: Source) {
        val hub = synchronized(gate) { source.hub } ?: return
        if (!member.original.live || synchronized(gate) { source.retired || member.retired || member.attempt != null }) return
        val request = member.original.request
        if (!source.profile.compatibleWith(request)) return // Its queued Update owns reconfiguration.
        val attachment = try { hub.attach(member.original,
            CameraStreamRef(member.original.ref, source.key.sourceGeneration, source.encoderGeneration), request) }
        catch (error: Throwable) { sourceFailure(source, "role-source-attach", error, CameraEndReason.ENCODER_FAILURE); return }
        val attempt = Attempt(member, source, attachment)
        val install = synchronized(gate) {
            source.attempts.add(attempt)
            if (source.retired || member.retired || member.attempt != null) { attempt.retired = true; false }
            else { member.attempt = attempt; true }
        }
        if (!install) {
            synchronized(gate) { attempt.connecting = false }
            attachment.requestRetire(CameraEndReason.ORIGINAL_RETIRED)
            return
        }
        try {
            val role = member.projection.uses.connect(member.original, attachment, object : CameraRoleEvents {
                override fun changed(role: OwnedCameraRole, value: RoleCloseObservation) {
                    synchronized(gate) { if (attempt.role == null) attempt.role = role; attempt.observation = value }
                    post(member.projection) {
                        if (value.retirementRequested)
                            retireAttempt(attempt, value.failure ?: CameraEndReason.ORIGINAL_RETIRED)
                        evaluate()
                    }
                }
                override fun remoteEnd(role: OwnedCameraRole, value: CameraMediaEnd) {
                    if (value.stream == null) post(member.projection) {
                        retireAttempt(attempt, value.reason)
                    }
                }
            })
            synchronized(gate) { attempt.role = role }
        } catch (error: Throwable) {
            recordFailure(member.projection, "role-connect", error)
            synchronized(gate) { attempt.retired = true; member.admissionFailed = true }
            retireMember(member, CameraEndReason.CONSUMER_UNAVAILABLE, true)
        } finally {
            synchronized(gate) { attempt.connecting = false }
            if (synchronized(gate) { attempt.retired || source.retired }) closeRole(attempt, source.reason)
            post(member.projection) { evaluate() } // A close callback may have arrived before connect returned.
        }
    }

    /** Transport retirement ends one captured attempt; the actual use ledger owns demand. */
    private fun retireAttempt(attempt: Attempt, reason: CameraEndReason) {
        val member = attempt.member
        val terminal = !member.original.live || !member.original.started || synchronized(gate) {
            closed || member.retired || member.projection.retired
        }
        if (terminal) {
            if (synchronized(gate) { member.attempt === attempt }) retireMember(member, reason, true)
            else closeRole(attempt, reason)
            return
        }
        val claim = synchronized(gate) {
            if (closed || member.retired || member.projection.retired || !member.original.live ||
                !member.original.started || member.attempt !== attempt || member.pendingRecovery != null) false
            else { member.pendingRecovery = attempt; true } // Before callback-capable retirement.
        }
        if (claim) retireMember(member, reason, false)
        else if (synchronized(gate) { member.pendingRecovery === attempt }) closeRole(attempt, reason)
    }

    private fun retireMember(member: Member, reason: CameraEndReason, retireUse: Boolean) {
        val selected = synchronized(gate) {
            if (permissionRequest?.projectionIdentity === member.projection &&
                permissionRequest?.original === member.original) permissionRequest = null
            if (retireUse) { member.retired = true; member.pendingRecovery = null }
            member.selection?.let { it.retired = true; it.installationAccounted = true }
            member.selection = null
            val source = member.source; member.source = null
            val attempt = member.attempt; member.attempt = null; attempt?.retired = true
            source?.members?.remove(member)
            Triple(source, attempt, source != null && source.members.isEmpty())
        }
        if (selected.third) selected.first?.let { retireSource(it, reason) }
        selected.second?.let { closeRole(it, reason) }
        evaluate()
    }

    private fun closeRole(attempt: Attempt, reason: CameraEndReason) {
        // Wake the captured handoff before any join. The callback names this exact child.
        try { attempt.attachment.requestRetire(reason) }
        catch (error: Throwable) { recordFailure(attempt.member.projection, "role-source-retire", error) }
        val role = synchronized(gate) {
            attempt.retired = true
            if (attempt.role == null || attempt.closeClaimed) null else {
                attempt.closeClaimed = true; attempt.role
            }
        } ?: return
        try { role.requestStop(reason); role.closeAsync(direct) { observation ->
            synchronized(gate) { attempt.observation = observation; attempt.closeObservation = observation }
            post { evaluate() }
        } } catch (error: Throwable) { recordFailure(attempt.member.projection, "role-close", error) }
    }

    private fun retireSource(source: Source, reason: CameraEndReason) {
        synchronized(gate) { if (!source.retired) { source.retired = true; source.reason = reason } }
        driveRetirement(source)
    }

    private fun driveRetirement(source: Source) {
        val run = synchronized(gate) {
            if (!source.retired || source.cameraStopClaimed || source.run == null) null
            else { source.cameraStopClaimed = true; source.run }
        }
        // Direct captured producer stop precedes any potentially blocked media/encoder handoff.
        if (run != null) try { run.stop("receiver-original-" + source.reason.name) }
            catch (error: Throwable) { sourceFailureRecord(source, "camera-stop", error) }
        val hub = synchronized(gate) {
            if (!source.retired || source.hubRetireClaimed || source.hub == null) null
            else { source.hubRetireClaimed = true; source.hub }
        }
        if (hub != null) try { hub.retireSource(source.reason) }
            catch (error: Throwable) { sourceFailureRecord(source, "media-source-retire", error) }
        synchronized(gate) { source.attempts.toList() }.forEach { closeRole(it, source.reason) }
        val encoder = synchronized(gate) {
            if (!source.retired || source.encoderCloseClaimed || source.encoder == null) null
            else { source.encoderCloseClaimed = true; source.encoder }
        }
        if (encoder != null) try { encoder.closeAsync(direct) { result ->
            synchronized(gate) { source.encoderRelease = result }
            post { evaluate() }
        } } catch (error: Throwable) { sourceFailureRecord(source, "encoder-close", error) }
        val local = synchronized(gate) {
            if (!source.retired || source.localCloseClaimed || source.local == null) null
            else if (source.local !is ProducerRetirementInput && source.producerStartEntered && source.outputsSafe == null) null
            else { source.localCloseClaimed = true; source.local }
        }
        if (local is ProducerRetirementInput) try { local.closeAsync(direct) { result ->
            synchronized(gate) { source.localRelease = result }
            post { evaluate() }
        } } catch (error: Throwable) { sourceFailureRecord(source, "local-close", error) }
        else if (local != null) {
            sourceFailureRecord(source, "local-retirement-capability", null)
            launch(source, "legacy-local-close") {
                try { local.close(); synchronized(gate) { source.legacyLocalCloseReturned = true } }
                catch (error: Throwable) { synchronized(gate) { source.legacyLocalCloseReturned = false }; throw error }
            }
        }
        post { evaluate() }
    }

    private fun sourceFailure(source: Source, operation: String, error: Throwable?, reason: CameraEndReason) {
        sourceFailureRecord(source, operation, error)
        val members = synchronized(gate) { source.members.toList() }
        members.forEach { report(it, CameraStage.SOURCE_PREPARATION, CameraFact.FAILED) }
        synchronized(gate) { members.forEach { it.admissionFailed = true; it.retired = true; it.source = null; it.attempt = null }; source.members.clear() }
        retireSource(source, reason)
    }

    private fun sourceFailureRecord(source: Source, operation: String, error: Throwable?) {
        synchronized(gate) { source.failures.add(ReceiverCameraOwnerFailure(operation, error)) }
    }

    private fun report(member: Member, stage: CameraStage, fact: CameraFact) {
        try { member.projection.uses.reportProgress(member.original, CameraProgress(member.original.ref, null,
            stage, fact, CameraClock.JAVA_SYSTEM_NANOTIME_NS, System.nanoTime())) }
        catch (error: Throwable) { recordFailure(member.projection, "progress-submission", error) }
    }

    private fun launch(source: Source, name: String, action: () -> Unit) {
        val task = NativeTask(name)
        synchronized(gate) { source.tasks.add(task) }
        val worker = try { Thread({
            synchronized(gate) { task.entered = true }
            try { action() }
            catch (error: Throwable) { sourceFailure(source, name, error, CameraEndReason.SOURCE_UNAVAILABLE) }
            finally { synchronized(gate) { task.returned = true }; post { evaluate() } }
        }, "eyeprojector-receiver-camera-" + name).apply { isDaemon = true } }
        catch (error: Throwable) {
            synchronized(gate) { task.notStarted = true; settleNotStartedLocked(source, name) }
            sourceFailure(source, name + "-create", error, CameraEndReason.LOCAL_RELEASE_FAILURE)
            return
        }
        try { worker.start() }
        catch (error: Throwable) {
            synchronized(gate) {
                task.notStarted = worker.state == Thread.State.NEW && !task.entered
                if (task.notStarted) settleNotStartedLocked(source, name)
            }
            sourceFailure(source, name + "-start", error, CameraEndReason.LOCAL_RELEASE_FAILURE)
        }
        if (synchronized(gate) { task.notStarted }) { post { evaluate() }; return }
        try { Thread({
            try { worker.join(); synchronized(gate) { task.joined = !worker.isAlive } }
            catch (error: Throwable) { synchronized(gate) { task.joinFailed = true }; sourceFailureRecord(source, name + "-join", error) }
            post { evaluate() }
        }, "eyeprojector-receiver-camera-join").apply { isDaemon = true; start() } }
        catch (error: Throwable) { synchronized(gate) { task.joinFailed = true }; sourceFailureRecord(source, name + "-join-start", error) }
    }

    private fun settleNotStartedLocked(source: Source, name: String) {
        if (name == "source-prepare") source.setupReturned = true
        if (name == "camera-start") { source.producerStartEntered = false; source.producerStartReturned = true }
    }

    private fun rolePending(attempt: Attempt): Boolean {
        if (attempt.connecting) return true
        val role = attempt.role ?: return false
        // A delayed changed snapshot cannot replace the actual joined close facts.
        val value = attempt.closeObservation ?: attempt.observation ?: return true
        return !attempt.closeClaimed || !value.retirementRequested ||
            value.writerStarted && !value.writerJoined || value.readerStarted && !value.readerJoined ||
            value.sourceRetireReturned != true || value.socketCloseResults.any { it != true } ||
            value.retirementWorkers.any { !it.returned || !it.joined || it.failed }
    }

    private fun pendingLocked(source: Source): Set<String> = buildSet {
        if (!source.retired && source.members.isNotEmpty()) add("active-demand")
        if (!source.retired && source.members.isNotEmpty() && !source.setupClaimed && source.contentionReported)
            add("source-profile-contention")
        if (source.setupClaimed && !source.setupReturned) add("source-setup")
        source.tasks.filter { !it.notStarted && !it.joined }.forEach { add(it.name + "-join") }
        if (source.producerStartEntered && source.run == null) add("camera-start-outcome")
        if (source.run != null) {
            if (source.outputsSafe == null) add("camera-output-fence")
            if (!source.cameraJoined) add("camera-owner-join")
        }
        source.encoder?.let {
            val result = source.encoderRelease
            if (result == null) add("encoder-release") else {
                val state = result.ownership
                if (state.codecAcquired && result.steps[EncoderOperation.CODEC_RELEASE]?.disposition != EncoderStepDisposition.RETURNED) add("encoder-codec-release")
                if (state.inputSurfaceAcquired && result.steps[EncoderOperation.INPUT_SURFACE_RELEASE]?.disposition != EncoderStepDisposition.RETURNED) add("encoder-surface-release")
                if (state.workerStarted && !state.workerThreadClosedObserved) add("encoder-owner-join")
            }
        }
        source.local?.let {
            val result = source.localRelease
            if (result == null) add(when (source.legacyLocalCloseReturned) {
                true -> "legacy-local-close-returned-release-unobserved"
                false -> "legacy-local-close-failed"
                null -> "local-release"
            }) else for (name in listOf("surfaceRelease", "surfaceTextureRelease", "programDelete", "textureDelete")) {
                val step = result.steps[name]
                val unacquired = step != null && step.disposition == LocalReleaseDisposition.NOT_ATTEMPTED && step.error == null
                val gl = name == "programDelete" || name == "textureDelete"
                val parent = source.outputRelease
                val contextDestroyed = parent != null && parent.steps["contextDestroy"]?.disposition == LocalReleaseDisposition.RETURNED &&
                    parent.workerJoin.disposition == LocalReleaseDisposition.RETURNED && parent.workerAlive == false
                val invalidated = gl && (contextDestroyed ||
                    step?.disposition == LocalReleaseDisposition.NOT_ATTEMPTED && result.glContext == LocalGlContextDisposition.CONFIRMED_CONTEXT_LOST)
                if (step?.disposition != LocalReleaseDisposition.RETURNED && !unacquired && !invalidated) add("local-" + name)
            }
        }
        source.attempts.filter(::rolePending).forEach { add("role-" + it.attachment.ref.toString()) }
    }

    private fun pendingSelectionsLocked(record: ProjectionRecord): Set<String> = buildSet {
        record.members.values.filter { it.pendingRecovery != null }.forEach {
            add("role-recovery-" + it.original.ref.toString())
        }
        record.selections.forEachIndexed { index, selection ->
            if (!selection.task.notStarted && !selection.task.joined) add("source-selection-$index-join")
            if (!selection.installationAccounted) add("source-selection-$index-delivery")
        }
    }

    fun hasResources(): Boolean = synchronized(gate) { projections.values.any { record ->
        pendingSelectionsLocked(record).isNotEmpty() || record.sources.any { pendingLocked(it).isNotEmpty() }
    } }

    private fun sourceObservationLocked(source: Source) = ReceiverCameraSourceCloseObservation(source.identity,
        source.profile, source.run?.identity, source.cameraOwnership, source.outputsSafe,
        source.run?.let { source.cameraJoined }, source.encoder?.identity, source.encoderRelease?.ownership ?: source.encoder?.snapshot,
        source.encoderRelease, source.local, source.localRelease,
        source.attempts.map { ReceiverCameraRoleCloseObservation(it.member.original, it.closeObservation ?: it.observation, rolePending(it)) },
        pendingLocked(source), source.failures, source.outputRelease)

    private fun observationLocked(record: ProjectionRecord?) = ReceiverCameraCloseObservation(record,
        (record?.let { pendingSelectionsLocked(it).isNotEmpty() } ?: projections.values.any { pendingSelectionsLocked(it).isNotEmpty() }) ||
            (record?.sources ?: projections.values.flatMap { it.sources }).any { pendingLocked(it).isNotEmpty() },
        (record?.sources ?: projections.values.flatMap { it.sources }).map(::sourceObservationLocked),
        failures + (record?.failures ?: projections.values.flatMap { it.failures }),
        record?.let(::pendingSelectionsLocked) ?: projections.values.flatMap { pendingSelectionsLocked(it) }.toSet())

    fun snapshot(): ReceiverCameraCloseObservation = synchronized(gate) { observationLocked(null) }

    fun closeAsync(executor: Executor, completion: (ReceiverCameraCloseObservation) -> Unit) {
        val records = synchronized(gate) {
            closed = true; host = null; permissionRequest = null
            completions.add(Completion(executor, completion)); projections.values.toList()
        }
        records.forEach { retire(it, CameraEndReason.PROJECTION_CLOSED) }
        evaluate()
    }

    private fun evaluate() {
        val ready = ArrayList<Pair<Completion, ReceiverCameraCloseObservation>>()
        val recovered = ArrayList<Member>()
        synchronized(gate) {
            for (record in projections.values) for (member in record.members.values) {
                val previous = member.pendingRecovery ?: continue
                if (previous.closeObservation != null && !rolePending(previous)) {
                    member.pendingRecovery = null // Consume this exact original completion once.
                    if (!closed && !record.retired && !member.retired) recovered.add(member)
                }
            }
            for (record in projections.values) if (record.retired && pendingSelectionsLocked(record).isEmpty() && record.sources.none { pendingLocked(it).isNotEmpty() }) {
                val observation = observationLocked(record)
                record.completions.forEach { ready.add(it to observation) }; record.completions.clear()
            }
            if (closed && projections.values.all { record -> pendingSelectionsLocked(record).isEmpty() && record.sources.none { pendingLocked(it).isNotEmpty() } }) {
                val observation = observationLocked(null)
                completions.forEach { ready.add(it to observation) }; completions.clear()
            }
        }
        recovered.forEach(::admit) // Re-observe live/start/permission/request; never reuse old native handles.
        for ((completion, observation) in ready) try { completion.executor.execute {
            try { completion.callback(observation) } catch (error: Throwable) { recordFailure(null, "completion-callback", error) }
        } } catch (error: Throwable) { recordFailure(null, "completion-dispatch", error) }
        startEligibleSources()
        try { changed() } catch (error: Throwable) { recordFailure(null, "changed-observer", error) }
    }

    private fun recordFailure(record: ProjectionRecord?, operation: String, error: Throwable) {
        synchronized(gate) { (record?.failures ?: failures).add(ReceiverCameraOwnerFailure(operation, error)) }
    }

    private fun post(record: ProjectionRecord? = null, action: () -> Unit) {
        try { deliver { try { action() } catch (error: Throwable) { recordFailure(record, "owner-delivery", error) } } }
        catch (error: Throwable) { recordFailure(record, "owner-delivery-dispatch", error) }
    }
}
