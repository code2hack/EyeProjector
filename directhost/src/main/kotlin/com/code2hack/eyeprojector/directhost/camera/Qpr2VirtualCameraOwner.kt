package com.code2hack.eyeprojector.directhost.camera

import android.companion.virtualcamera.ICaptureResultConsumer
import android.companion.virtualcamera.IVirtualCameraCallback
import android.companion.virtualcamera.IVirtualCameraService
import android.companion.virtualcamera.SupportedStreamConfiguration
import android.companion.virtualcamera.VirtualCameraConfiguration
import android.companion.virtualcamera.VirtualCameraMetadata
import android.os.Binder
import android.view.Surface
import java.util.IdentityHashMap

/** Requested values, not a capability claim or a product-selected camera default. */
data class RequestedPhoneStream(val width: Int, val height: Int, val pixelFormat: Int, val maxFps: Int)

class PhoneCameraProfile(
    streams: List<RequestedPhoneStream>,
    val lensFacing: Int,
    val sensorOrientation: Int,
    val androidDeviceId: Int,
) {
    val streams: List<RequestedPhoneStream> = java.util.Collections.unmodifiableList(streams.map { it.copy() })
}

enum class PhoneNativeCallState { NOT_STARTED, IN_FLIGHT, RETURNED, THREW }

data class PhoneNativeCall<T>(
    val state: PhoneNativeCallState = PhoneNativeCallState.NOT_STARTED,
    val value: T? = null,
    val failure: Throwable? = null,
)

data class PhoneMetadataObservation(val present: Boolean, val byteCount: Int?)

data class PhoneCameraObservation(
    val method: String,
    val elapsedNanos: Long?,
    val immediateBinderUid: Int?,
    val immediateBinderPid: Int?,
    val streamId: Int? = null,
    val frameId: Int? = null,
    val metadata: PhoneMetadataObservation? = null,
    val captureConsumerPresent: Boolean? = null,
    val association: String? = null,
    val failure: Throwable? = null,
    // The callback API supplies no target-app identity, session token or camera-frame clock join.
    val targetApplicationUid: Int? = null,
)

interface PhoneCameraEvents {
    fun onRegistrationState(original: OwnedPhoneRegistration) {}
    /** Captured as-of native facts; delivery does not imply a later worker join or callback quiescence. */
    fun onRegistrationState(original: OwnedPhoneRegistration, state: PhoneCameraRegistrationSnapshot) {
        onRegistrationState(original)
    }
    fun onObservation(original: OwnedPhoneRegistration, observation: PhoneCameraObservation) {}
    fun onInputConfigured(original: PhoneCameraLease) {}
    fun onCaptureHint(original: PhoneCameraLease, frameId: Int, metadata: PhoneMetadataObservation) {}
    fun onStreamRetired(original: PhoneCameraLease) {}
}

data class PhoneCameraRegistrationSnapshot(
    val original: OwnedPhoneRegistration,
    val revision: Long,
    val caller: PhoneCameraCaller?,
    val serviceLookup: PhoneCameraServiceLookup?,
    val setupUnresolved: Boolean,
    val registerCall: PhoneNativeCall<Boolean>,
    val cameraIdCalls: List<PhoneNativeCall<String>>,
    val unregisterCall: PhoneNativeCall<Unit>,
    val postUnregisterId: PhoneNativeCall<String>,
    val closeRequested: Boolean,
    val tokenAbsenceObserved: Boolean,
    val inputs: List<PhoneInputSnapshot>,
    val knownResourcesClearAtRevision: Boolean,
    val associationIndeterminate: Boolean,
    val firstPrimaryFailure: Throwable?,
    val observerFailures: List<Throwable>,
    val nativeWorker: PhoneWorkerSnapshot,
    val eventWorker: PhoneWorkerSnapshot,
    val callbackQuiescence: String = "UNOBSERVED",
    val fullH: String = "UNKNOWN",
)

/** A callable local backend, separate from the diagnostic actor and any authenticated Session. */
class Qpr2VirtualCameraOwner(
    private val platform: Qpr2CameraPlatform = AndroidQpr2CameraPlatform(),
    private val startThread: (Thread) -> Unit = { it.start() },
) {
    private val lock = Any()
    private val originals = ArrayList<OwnedPhoneRegistration>()

    // Neither construction nor registration starts a physical camera, renderer or synthetic source.
    fun register(profile: PhoneCameraProfile, events: PhoneCameraEvents): OwnedPhoneRegistration {
        val original = OwnedPhoneRegistration(profile, events, platform, startThread)
        synchronized(lock) { originals.add(original) }
        original.start()
        return original
    }

    fun registrations(): List<OwnedPhoneRegistration> = synchronized(lock) { originals.toList() }
}

/** Owns ONE new token and callback object; exceptions never substitute for native absence evidence. */
class OwnedPhoneRegistration internal constructor(
    val requestedProfile: PhoneCameraProfile,
    private val events: PhoneCameraEvents,
    private val platform: Qpr2CameraPlatform,
    startThread: (Thread) -> Unit,
) {
    private val ownership = PhoneCameraOwnership()
    private val token = platform.newToken()
    private val surfaces = IdentityHashMap<Surface, PhoneSurfaceOwner>()
    private val inputs = ArrayList<PhoneCameraLease>()
    private class InputEpoch(val lease: PhoneCameraLease, var actualClose: Boolean = false)
    private val epochs = HashMap<Int, MutableList<InputEpoch>>()
    private var caller: PhoneCameraCaller? = null
    private var lookup: PhoneCameraServiceLookup? = null
    private var service: IVirtualCameraService? = null
    private var setupUnresolved = true
    private var registerCall = PhoneNativeCall<Boolean>()
    private val idCalls = ArrayList<PhoneNativeCall<String>>()
    private var unregisterCall = PhoneNativeCall<Unit>()
    private var postUnregisterId = PhoneNativeCall<String>()
    private var closeRequested = false
    private var cleanupQueued = false
    private var associationIndeterminate = false
    private var primaryFailure: Throwable? = null
    private val observerFailures = ArrayList<Throwable>()
    private val nativeWorker = PhoneCameraWorker("phone-qpr2-native", ownership, ::primary, startThread)
    private val eventWorker = PhoneCameraWorker("phone-qpr2-events", ownership, ::observerFailure, startThread)

    // This original Stub is allocated and retained before any actual native invocation.
    private val callback = object : IVirtualCameraCallback.Stub() {
        override fun onOpenCamera() {
            observe(entry("onOpenCamera")) // Preparation signal only, not a fictitious stream0.
        }

        override fun onConfigureSession(parameters: VirtualCameraMetadata?, consumer: ICaptureResultConsumer?) {
            observe(entry("onConfigureSession").copy(
                metadata = metadata(parameters), captureConsumerPresent = consumer != null,
            )) // Opaque scalar observation only; never retain/invoke the consumer or metadata wrapper.
        }

        override fun onStreamConfigured(id: Int, surface: Surface?, width: Int, height: Int, format: Int) {
            val observation = entry("onStreamConfigured", id)
            val original: PhoneCameraLease
            val ambiguous: Boolean
            val retired: Boolean
            synchronized(ownership.lock) {
                original = PhoneCameraLease(this@OwnedPhoneRegistration, this, id, width, height,
                    format, surface, ownership, surfaces, observation.elapsedNanos ?: -1L)
                inputs.add(original) // Every wrapper enrolled before dispatch or any rejection decision.
                val history = epochs.getOrPut(id) { ArrayList() }
                // A legitimately closed and retired old original does not impose a reopen/use cap.
                ambiguous = history.any { !it.actualClose || !it.lease.snapshot().resourcesClear } ||
                    original.snapshot().aliasAmbiguous
                history.add(InputEpoch(original))
                if (ambiguous) associationIndeterminate = true
                retired = closeRequested
                ownership.changedLocked()
            }
            if (retired || ambiguous || surface == null) original.requestClose()
            if (ambiguous) requestClose() // Whole ORIGINAL registration, never a guessed numeric successor.
            observe(observation.copy(association = when {
                ambiguous -> "OVERLAPPING_OR_WRAPPER_ALIAS_ASSOCIATION_INDETERMINATE"
                retired -> "LATE_ORIGINAL_AFTER_RETIREMENT"
                surface == null -> "ABSENT_ACTUAL_SURFACE"
                else -> "FRESH_ORIGINAL_CONFIGURED_INPUT"
            }))
            if (!retired && !ambiguous && surface != null) dispatch {
                // This task retains original, even if a close/reopen happens before delivery.
                events.onInputConfigured(original)
            }
        }

        override fun onProcessCaptureRequest(id: Int, frameId: Int, settings: VirtualCameraMetadata?) {
            val observation = entry("onProcessCaptureRequest", id).copy(frameId = frameId, metadata = metadata(settings))
            val original = synchronized(ownership.lock) {
                val history = epochs[id]
                // The API has no parent epoch. A repeated ID cannot authenticate a late capture hint.
                if (history?.size == 1 && !history[0].actualClose) history[0].lease else null
            }
            observe(observation.copy(association = if (original == null) "PARENT_UNOBSERVED_OR_REUSED_ID" else "CAPTURED_ORIGINAL"))
            if (original != null) dispatch { events.onCaptureHint(original, frameId, observation.metadata!!) }
        }

        override fun onStreamClosed(id: Int) {
            val observation = entry("onStreamClosed", id)
            val original = synchronized(ownership.lock) {
                val history = epochs[id]
                if (history?.size == 1) {
                    history[0].actualClose = true
                    ownership.changedLocked()
                    history[0].lease
                } else {
                    // Accepting a reopened input does not invent a parent for a later ID-only close.
                    associationIndeterminate = true
                    ownership.changedLocked()
                    null
                }
            }
            observe(observation.copy(association = if (original == null) "PARENT_UNOBSERVED_OR_REUSED_ID" else "CAPTURED_ORIGINAL"))
            if (original != null) {
                original.requestClose() // Immediate admission retirement; producer effects remain asynchronous.
                dispatch { events.onStreamRetired(original) }
            }
        }
    }

    internal fun start() {
        nativeWorker.post {
            try {
                val actualCaller = platform.caller()
                synchronized(ownership.lock) { caller = actualCaller; ownership.changedLocked() }
                registrationStateChanged()
                val actualLookup = platform.lookup()
                synchronized(ownership.lock) {
                    lookup = actualLookup
                    service = actualLookup.service
                    setupUnresolved = actualLookup.service == null
                    ownership.changedLocked()
                }
                registrationStateChanged()
                val target = actualLookup.service ?: return@post
                val config = actualConfiguration()
                val invoke = synchronized(ownership.lock) {
                    if (closeRequested) false else {
                        registerCall = PhoneNativeCall(PhoneNativeCallState.IN_FLIGHT)
                        ownership.changedLocked()
                        true
                    }
                }
                if (invoke) {
                    registrationStateChanged()
                    try {
                        val accepted = target.registerCamera(token, config, requestedProfile.androidDeviceId)
                        synchronized(ownership.lock) {
                            registerCall = PhoneNativeCall(PhoneNativeCallState.RETURNED, accepted)
                            ownership.changedLocked()
                        }
                        registrationStateChanged()
                        if (!accepted) requestClose()
                    } catch (error: Throwable) {
                        synchronized(ownership.lock) {
                            registerCall = PhoneNativeCall(PhoneNativeCallState.THREW, failure = error)
                            ownership.changedLocked()
                        }
                        primary(error) // SecurityException is not a token-absence proof either.
                        registrationStateChanged()
                        requestClose()
                    }
                    readId(target, false)
                }
            } catch (error: Throwable) {
                synchronized(ownership.lock) { setupUnresolved = true; ownership.changedLocked() }
                primary(error)
                registrationStateChanged()
            }
        }
    }

    /** New observation only. It cannot clear an unresolved register/unregister using an old empty ID. */
    fun queryCameraId() {
        nativeWorker.post { synchronized(ownership.lock) { service }?.let { readId(it, false) } }
    }

    /** Returns without native calls, stop callbacks, waiting for a fence, Surface.release or join. */
    fun requestClose() {
        val originals: List<PhoneCameraLease>
        val enqueue: Boolean
        synchronized(ownership.lock) {
            closeRequested = true
            originals = inputs.toList()
            enqueue = !cleanupQueued
            cleanupQueued = true
            ownership.changedLocked()
        }
        originals.forEach { it.requestClose() } // Independent of a blocked register/getId lane.
        if (enqueue) nativeWorker.post { closeNative() }
    }

    private fun closeNative() {
        val pair = synchronized(ownership.lock) { service to registerCall.state }
        val target = pair.first
        if (target != null && pair.second != PhoneNativeCallState.NOT_STARTED) {
            synchronized(ownership.lock) {
                unregisterCall = PhoneNativeCall(PhoneNativeCallState.IN_FLIGHT)
                ownership.changedLocked()
            }
            registrationStateChanged()
            try {
                target.unregisterCamera(token)
                synchronized(ownership.lock) {
                    unregisterCall = PhoneNativeCall(PhoneNativeCallState.RETURNED, Unit)
                    ownership.changedLocked()
                }
                registrationStateChanged()
            } catch (error: Throwable) {
                synchronized(ownership.lock) {
                    unregisterCall = PhoneNativeCall(PhoneNativeCallState.THREW, failure = error)
                    ownership.changedLocked()
                }
                primary(error)
                registrationStateChanged()
            }
            readId(target, true) // Independent, fresh post-unregister result, including a thrown unregister.
        }
        val nativeResolved = synchronized(ownership.lock) {
            registerCall.state == PhoneNativeCallState.NOT_STARTED || tokenAbsentLocked()
        }
        if (nativeResolved) {
            nativeWorker.closeWhenIdle()
            eventWorker.closeWhenIdle()
        } // An indeterminate native outcome retains this original lane/record without automatic retry.
    }

    private fun readId(target: IVirtualCameraService, afterUnregister: Boolean) {
        val index = synchronized(ownership.lock) {
            val pending = PhoneNativeCall<String>(PhoneNativeCallState.IN_FLIGHT)
            if (afterUnregister) postUnregisterId = pending
            idCalls.add(pending)
            ownership.changedLocked()
            idCalls.lastIndex
        }
        registrationStateChanged()
        val result: PhoneNativeCall<String> = try {
            PhoneNativeCall<String>(PhoneNativeCallState.RETURNED, target.getCameraId(token))
        } catch (error: Throwable) {
            primary(error)
            PhoneNativeCall<String>(PhoneNativeCallState.THREW, failure = error)
        }
        synchronized(ownership.lock) {
            idCalls[index] = result
            if (afterUnregister) postUnregisterId = result
            ownership.changedLocked()
        }
        registrationStateChanged()
    }

    private fun tokenAbsentLocked(): Boolean =
        unregisterCall.state == PhoneNativeCallState.RETURNED &&
            postUnregisterId.state == PhoneNativeCallState.RETURNED &&
            postUnregisterId.value != null && postUnregisterId.value!!.isEmpty()

    fun snapshot(): PhoneCameraRegistrationSnapshot = synchronized(ownership.lock) {
        val resources = inputs.map { it.snapshot() }
        PhoneCameraRegistrationSnapshot(this, ownership.revision, caller, lookup, setupUnresolved,
            registerCall, idCalls.toList(), unregisterCall, postUnregisterId, closeRequested,
            tokenAbsentLocked(), resources, resources.all { it.resourcesClear }, associationIndeterminate,
            primaryFailure, observerFailures.toList(), nativeWorker.snapshot(), eventWorker.snapshot())
    }

    /** Dedicated media-owner close thread only. Success is a fresh as-of revision, not callback quiescence. */
    internal fun awaitResourceRelease(): PhoneCameraRegistrationSnapshot {
        requestClose()
        return synchronized(ownership.lock) {
            var state = snapshot()
            while (!(((state.registerCall.state == PhoneNativeCallState.NOT_STARTED && state.nativeWorker.joined) ||
                    state.tokenAbsenceObserved) && state.knownResourcesClearAtRevision &&
                    state.nativeWorker.joined && state.eventWorker.joined && state.inputs.all { it.worker.joined })) {
                ownership.lock.wait() // Releases the monitor; ownership changes notify this exact original.
                state = snapshot()
            }
            state
        }
    }

    private fun actualConfiguration() = VirtualCameraConfiguration().also { config ->
        config.supportedStreamConfigs = requestedProfile.streams.map { requested ->
            SupportedStreamConfiguration().also {
                it.width = requested.width; it.height = requested.height
                it.pixelFormat = requested.pixelFormat; it.maxFps = requested.maxFps
            }
        }.toTypedArray()
        config.virtualCameraCallback = callback
        config.sensorOrientation = requestedProfile.sensorOrientation
        config.lensFacing = requestedProfile.lensFacing
        config.perFrameCameraMetadataEnabled = false
        config.cameraCharacteristics = null
    }

    private fun metadata(value: VirtualCameraMetadata?) = PhoneMetadataObservation(value != null, value?.metadata?.size)

    private fun entry(method: String, streamId: Int? = null): PhoneCameraObservation {
        var stamp: Long? = null
        var uid: Int? = null
        var pid: Int? = null
        var failure: Throwable? = null
        try { stamp = platform.elapsedRealtimeNanos() } catch (error: Throwable) { failure = error }
        try { uid = Binder.getCallingUid(); pid = Binder.getCallingPid() }
        catch (error: Throwable) { if (failure == null) failure = error }
        return PhoneCameraObservation(method, stamp, uid, pid, streamId, failure = failure)
    }

    private fun observe(observation: PhoneCameraObservation) = dispatch { events.onObservation(this, observation) }

    private fun registrationStateChanged() {
        val original = this
        val state = snapshot() // Acquire only to copy; never invoke the observer while holding ownership.lock.
        dispatch { events.onRegistrationState(original, state) }
    }

    private fun dispatch(action: () -> Unit) {
        if (!eventWorker.post {
                try { action() } catch (error: Throwable) { observerFailure(error) }
            }) observerFailure(IllegalStateException("Original event dispatch is not confirmed"))
    }

    private fun primary(error: Throwable) = synchronized(ownership.lock) {
        if (primaryFailure == null) primaryFailure = error
        ownership.changedLocked()
    }

    private fun observerFailure(error: Throwable) = synchronized(ownership.lock) {
        observerFailures.add(error)
        ownership.changedLocked()
    }
}
