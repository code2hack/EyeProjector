package com.code2hack.eyeprojector.directhost.camera

import android.companion.virtualcamera.ICaptureResultConsumer
import android.companion.virtualcamera.IVirtualCameraCallback
import android.companion.virtualcamera.IVirtualCameraService
import android.companion.virtualcamera.VirtualCameraMetadata
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Forces actual generated Proxy -> actual generated Stub -> actual product callback.
 * The platform bridge is synchronous/in-process; oneway flags are observations only.
 */
private class OwnerGeneratedBridge(private val descriptor: String, private val target: IBinder) : IBinder {
    data class Call(val code: Int, val flags: Int, val nullReply: Boolean, val cells: List<Parcel.Cell>)
    val calls = CopyOnWriteArrayList<Call>()
    override fun queryLocalInterface(name: String): IInterface? = null
    override fun getInterfaceDescriptor(): String = descriptor
    override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val cells = data.fixtureCells()
        check(cells.first().kind == "token" && cells.first().value == descriptor)
        calls.add(Call(code, flags, reply == null, cells))
        return target.transact(code, data, reply, flags)
    }
}

private class GeneratedProductEvents : PhoneCameraEvents {
    val observations = CopyOnWriteArrayList<PhoneCameraObservation>()
    val inputs = CopyOnWriteArrayList<PhoneCameraLease>()
    val hints = CopyOnWriteArrayList<Triple<PhoneCameraLease, Int, PhoneMetadataObservation>>()
    val retired = CopyOnWriteArrayList<PhoneCameraLease>()
    override fun onObservation(original: OwnedPhoneRegistration, observation: PhoneCameraObservation) {
        observations.add(observation)
    }
    override fun onInputConfigured(original: PhoneCameraLease) { inputs.add(original) }
    override fun onCaptureHint(original: PhoneCameraLease, frameId: Int, metadata: PhoneMetadataObservation) {
        hints.add(Triple(original, frameId, metadata))
    }
    override fun onStreamRetired(original: PhoneCameraLease) { retired.add(original) }
}

private fun generatedProduct(service: ModelCameraService, events: PhoneCameraEvents,
                             profile: PhoneCameraProfile = modelProfile()): Pair<OwnedPhoneRegistration, OwnerGeneratedBridge> {
    val bridge = OwnerGeneratedBridge("android.companion.virtualcamera.IVirtualCameraService", service.asBinder())
    val proxy = IVirtualCameraService.Stub.asInterface(bridge)
    check(proxy !== service && proxy.asBinder() === bridge)
    val original = Qpr2VirtualCameraOwner(ModelCameraPlatform(proxy)).register(profile, events)
    awaitCamera("generated service returned to actual owner") {
        original.snapshot().registerCall.state == PhoneNativeCallState.RETURNED
    }
    return original to bridge
}

private fun callbackProxy(service: ModelCameraService): Pair<IVirtualCameraCallback, OwnerGeneratedBridge> {
    val originalCallback = checkNotNull(service.callback)
    val bridge = OwnerGeneratedBridge("android.companion.virtualcamera.IVirtualCameraCallback", originalCallback.asBinder())
    val proxy = IVirtualCameraCallback.Stub.asInterface(bridge)
    check(proxy !== originalCallback && proxy.asBinder() === bridge)
    return proxy to bridge
}

private fun closeGeneratedProduct(original: OwnedPhoneRegistration) {
    closeModel(original)
    awaitCamera("actual original event and all input workers joined") {
        val state = original.snapshot()
        state.eventWorker.joined && state.inputs.all { it.worker.joined }
    }
}

private fun preserveGeneratedCleanup(primary: Throwable?, cleanup: () -> Unit) {
    try { cleanup() } catch (failure: Throwable) {
        if (primary == null) throw failure else primary.addSuppressed(failure)
    }
}

/** New product integration controls; historical host410's standalone20 are not replayed/summed. */
fun runGeneratedContractScenarios(): Int {
    var cases = 0
    cameraCase("generated-service-preserves-actual-product-requested-profile-six-fields") {
        val requested = arrayListOf(RequestedPhoneStream(704, 400, 1, 24), RequestedPhoneStream(320, 240, 35, 15))
        val profile = PhoneCameraProfile(requested, 2, 90, 7)
        requested.clear() // The actual product profile must have captured its own immutable values.
        val service = ModelCameraService()
        val (original, bridge) = generatedProduct(service, GeneratedProductEvents(), profile)
        var primary: Throwable? = null
        try {
            val actual = checkNotNull(service.requested)
            check(actual.supportedStreamConfigs.size == 2)
            check(actual.supportedStreamConfigs[0].width == 704 && actual.supportedStreamConfigs[0].height == 400)
            check(actual.supportedStreamConfigs[0].pixelFormat == 1 && actual.supportedStreamConfigs[0].maxFps == 24)
            check(actual.supportedStreamConfigs[1].width == 320 && actual.supportedStreamConfigs[1].pixelFormat == 35)
            check(actual.sensorOrientation == 90 && actual.lensFacing == 2)
            check(!actual.perFrameCameraMetadataEnabled && actual.cameraCharacteristics == null)
            check(actual.virtualCameraCallback != null && original.snapshot().registerCall.value == true)
            val register = bridge.calls.single { it.code == 1 }
            check(register.flags == 0 && !register.nullReply)
            check(register.cells.last().kind == "int" && register.cells.last().value == 7)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally { preserveGeneratedCleanup(primary) { closeGeneratedProduct(original) } }
        check(bridge.calls.any { it.code == 2 && it.flags == 0 && !it.nullReply })
        check(bridge.calls.any { it.code == 3 && it.flags == 0 && !it.nullReply })
    }; cases++
    cameraCase("generated-callbacks-feed-original-product-lease-and-opaque-observations") {
        val service = ModelCameraService(); val events = GeneratedProductEvents()
        val (original, _) = generatedProduct(service, events)
        val (callback, bridge) = callbackProxy(service)
        var boundInput: PhoneCameraLease? = null
        var boundProducer: PhoneInputProducerIdentity? = null
        var primary: Throwable? = null
        val consumerCalls = AtomicInteger()
        val consumer = object : ICaptureResultConsumer.Stub() {
            override fun acceptCaptureResult(timestamp: Long, data: VirtualCameraMetadata?) { consumerCalls.incrementAndGet() }
        }
        try {
            callback.onOpenCamera()
            callback.onConfigureSession(VirtualCameraMetadata().also { it.metadata = byteArrayOf(4, 5, 6) }, consumer)
            awaitCamera("session-only product observations delivered") {
                events.observations.count { it.method == "onOpenCamera" || it.method == "onConfigureSession" } == 2
            }
            check(original.snapshot().inputs.isEmpty()) // No session-level synthetic stream0.
            val configure = events.observations.single { it.method == "onConfigureSession" }
            check(configure.streamId == null && configure.metadata == PhoneMetadataObservation(true, 3))
            check(configure.captureConsumerPresent == true && configure.targetApplicationUid == null)
            check(consumerCalls.get() == 0)
            val supplied = Surface(0x53555246)
            callback.onStreamConfigured(71, supplied, 703, 401, 1)
            awaitCamera("generated configured callback delivered original lease") { events.inputs.size == 1 }
            val input = events.inputs.single(); val producer = PhoneInputProducerIdentity()
            boundInput = input; boundProducer = producer
            check(input.registration === original)
            check(input.bindProducer(producer) {})
            val decoded = checkNotNull(input.surfaceFor(producer))
            check(decoded !== supplied && decoded.fixtureId == supplied.fixtureId) // Artificial Parcel CREATOR wrapper.
            callback.onProcessCaptureRequest(71, 991, VirtualCameraMetadata().also { it.metadata = byteArrayOf(8, 9) })
            awaitCamera("generated capture hint keeps original lease") { events.hints.size == 1 }
            check(events.hints.single() == Triple(input, 991, PhoneMetadataObservation(true, 2)))
            callback.onStreamClosed(71)
            awaitCamera("generated close retires captured original lease") { events.retired.size == 1 }
            check(events.retired.single() === input && input.surfaceFor(producer) == null)
            check(decoded.releaseCount.get() == 0)
            check(input.producerStopped(producer, PhoneInputUseStopped(input, producer)))
            awaitCamera("same generated-input wrapper released after original fence") { decoded.releaseCount.get() == 1 }
            check(bridge.calls.map { it.code } == listOf(1, 2, 3, 4, 5))
            check(bridge.calls.all { it.flags == 1 && it.nullReply })
            check(events.observations.all { it.targetApplicationUid == null })
            check(consumerCalls.get() == 0)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally { preserveGeneratedCleanup(primary) {
            // A failed assertion must not strand the known model writer binding.
            val input = boundInput
            val producer = boundProducer
            if (input != null && producer != null)
                input.producerStopped(producer, PhoneInputUseStopped(input, producer))
            closeGeneratedProduct(original)
        } }
    }; cases++
    cameraCase("generated-late-configured-callback-remains-original-after-native-close") {
        val service = ModelCameraService(); val events = GeneratedProductEvents()
        val (original, _) = generatedProduct(service, events)
        val (callback, bridge) = callbackProxy(service)
        closeGeneratedProduct(original)
        val before = original.snapshot()
        callback.onStreamConfigured(81, Surface(0x4c415445), 11, 13, 1)
        awaitCamera("late actual generated wrapper retirement") {
            val now = original.snapshot()
            now.inputs.size == 1 && now.knownResourcesClearAtRevision && now.inputs.all { it.worker.joined }
        }
        val after = original.snapshot()
        check(after.original === original && after.revision > before.revision)
        check(after.tokenAbsenceObserved && after.callbackQuiescence == "UNOBSERVED" && after.fullH == "UNKNOWN")
        check(events.inputs.isEmpty()) // It was never admitted as a successor producer target.
        check(bridge.calls.single().code == 3 && bridge.calls.single().flags == 1)
    }; cases++
    return cases
}
