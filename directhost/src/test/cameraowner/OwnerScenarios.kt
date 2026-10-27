package com.code2hack.eyeprojector.directhost.camera

import android.companion.virtualcamera.IVirtualCameraCallback
import android.companion.virtualcamera.IVirtualCameraService
import android.companion.virtualcamera.VirtualCameraConfiguration
import android.os.IBinder
import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal fun cameraCase(name: String, action: () -> Unit) {
    action()
    println("PASS $name")
}

/** Harness observation deadline only. It is not a product owner/cleanup expiry. */
internal fun awaitCamera(label: String, test: () -> Boolean) {
    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!test()) {
        check(System.nanoTime() < end) { "Pending host control: $label" }
        Thread.sleep(2)
    }
}

internal fun modelProfile() = PhoneCameraProfile(
    listOf(RequestedPhoneStream(640, 480, 1, 30)), 2, 0, 0,
)

internal open class ModelCameraService : IVirtualCameraService.Stub() {
    @Volatile var callback: IVirtualCameraCallback? = null
    @Volatile var token: IBinder? = null
    @Volatile var requested: VirtualCameraConfiguration? = null
    @Volatile var id = "model-camera"
    val registers = AtomicInteger()
    val unregisters = AtomicInteger()
    var registerAction: (VirtualCameraConfiguration) -> Boolean = { true }
    var unregisterAction: () -> Unit = {}
    override fun registerCamera(token: IBinder, config: VirtualCameraConfiguration, deviceId: Int): Boolean {
        this.token = token; callback = config.virtualCameraCallback; requested = config
        registers.incrementAndGet()
        return registerAction(config)
    }
    override fun unregisterCamera(token: IBinder) {
        check(token === this.token)
        unregisters.incrementAndGet()
        unregisterAction()
        id = ""
    }
    override fun getCameraId(token: IBinder): String {
        check(token === this.token)
        return id
    }
}

internal class ModelCameraPlatform(val target: IVirtualCameraService?) : Qpr2CameraPlatform {
    val lookups = AtomicInteger()
    val callers = AtomicInteger()
    override fun caller(): PhoneCameraCaller {
        callers.incrementAndGet()
        return PhoneCameraCaller(2000, 1234, 36, 0)
    }
    override fun lookup(): PhoneCameraServiceLookup {
        lookups.incrementAndGet()
        return PhoneCameraServiceLookup(target, target?.asBinder()?.interfaceDescriptor, false, true)
    }
}

internal class ModelCameraEvents : PhoneCameraEvents {
    val leases = CopyOnWriteArrayList<PhoneCameraLease>()
    val observations = CopyOnWriteArrayList<PhoneCameraObservation>()
    override fun onInputConfigured(original: PhoneCameraLease) { leases.add(original) }
    override fun onObservation(original: OwnedPhoneRegistration, observation: PhoneCameraObservation) {
        observations.add(observation)
    }
}

internal fun liveModel(service: ModelCameraService = ModelCameraService(), events: PhoneCameraEvents = ModelCameraEvents()): OwnedPhoneRegistration {
    val registration = Qpr2VirtualCameraOwner(ModelCameraPlatform(service)).register(modelProfile(), events)
    awaitCamera("register returned") { registration.snapshot().registerCall.state == PhoneNativeCallState.RETURNED }
    return registration
}

internal fun closeModel(original: OwnedPhoneRegistration) {
    original.requestClose()
    awaitCamera("original unregister/empty ID/known resources/native join") {
        val s = original.snapshot()
        s.tokenAbsenceObserved && s.knownResourcesClearAtRevision && s.nativeWorker.joined
    }
}

fun runOwnerScenarios(): Int {
    var cases = 0
    cameraCase("owner-construction-no-service-or-native-work") {
        val platform = ModelCameraPlatform(ModelCameraService())
        val owner = Qpr2VirtualCameraOwner(platform)
        check(owner.registrations().isEmpty() && platform.lookups.get() == 0 && platform.callers.get() == 0)
    }; cases++
    cameraCase("fresh-original-tokens-no-same-uid-adoption") {
        val firstService = ModelCameraService(); val secondService = ModelCameraService()
        val first = liveModel(firstService); val second = liveModel(secondService)
        check(first !== second && firstService.token !== secondService.token)
        closeModel(first); closeModel(second)
    }; cases++
    cameraCase("callback-surface-owned-before-register-return") {
        val entered = CountDownLatch(1); val finishRegister = CountDownLatch(1)
        val surface = Surface(); val service = ModelCameraService()
        service.registerAction = { config ->
            config.virtualCameraCallback.onStreamConfigured(9, surface, 640, 480, 1)
            entered.countDown(); finishRegister.await(); true
        }
        val original = Qpr2VirtualCameraOwner(ModelCameraPlatform(service)).register(modelProfile(), ModelCameraEvents())
        check(entered.await(5, TimeUnit.SECONDS))
        check(original.snapshot().registerCall.state == PhoneNativeCallState.IN_FLIGHT)
        check(original.snapshot().inputs.size == 1 && !original.snapshot().knownResourcesClearAtRevision)
        original.requestClose()
        awaitCamera("retirement independent of blocked native register") { surface.releaseCount.get() == 1 }
        finishRegister.countDown(); closeModel(original)
    }; cases++
    cameraCase("false-register-preserves-original-callback-cleanup") {
        val surface = Surface(); val service = ModelCameraService()
        service.registerAction = { config -> config.virtualCameraCallback.onStreamConfigured(1, surface, 2, 3, 1); false }
        val original = Qpr2VirtualCameraOwner(ModelCameraPlatform(service)).register(modelProfile(), ModelCameraEvents())
        awaitCamera("false native register actually returned") { original.snapshot().registerCall.state == PhoneNativeCallState.RETURNED }
        closeModel(original)
        check(original.snapshot().registerCall.value == false && surface.releaseCount.get() == 1 && service.unregisters.get() == 1)
    }; cases++
    cameraCase("security-throw-is-not-native-absence-proof") {
        val failure = SecurityException("model ambiguous native call")
        val entered = CountDownLatch(1); val allowUnregister = CountDownLatch(1)
        val service = ModelCameraService()
        service.registerAction = { throw failure }
        service.unregisterAction = { entered.countDown(); allowUnregister.await() }
        val original = Qpr2VirtualCameraOwner(ModelCameraPlatform(service)).register(modelProfile(), ModelCameraEvents())
        check(entered.await(5, TimeUnit.SECONDS))
        val pending = original.snapshot()
        check(pending.registerCall.failure === failure && pending.firstPrimaryFailure === failure)
        check(!pending.tokenAbsenceObserved && !pending.nativeWorker.joined)
        allowUnregister.countDown(); closeModel(original)
    }; cases++
    cameraCase("null-lazy-lookup-unresolved-not-universal-unsupported") {
        val platform = ModelCameraPlatform(null)
        val original = Qpr2VirtualCameraOwner(platform).register(modelProfile(), ModelCameraEvents())
        awaitCamera("null actual lookup returned") { original.snapshot().serviceLookup != null }
        check(original.snapshot().setupUnresolved && original.snapshot().registerCall.state == PhoneNativeCallState.NOT_STARTED)
        check(platform.lookups.get() == 1)
        original.requestClose()
        awaitCamera("never-registered native lane joined") { original.snapshot().nativeWorker.joined }
        check(!original.snapshot().tokenAbsenceObserved) // No unregister was fabricated.
    }; cases++
    cameraCase("old-empty-status-does-not-complete-unregister-inflight") {
        val service = ModelCameraService(); val original = liveModel(service)
        service.id = ""; original.queryCameraId()
        awaitCamera("earlier empty ID") { original.snapshot().cameraIdCalls.any { it.value == "" } }
        val entered = CountDownLatch(1); val allow = CountDownLatch(1)
        service.unregisterAction = { entered.countDown(); allow.await() }
        original.requestClose(); check(entered.await(5, TimeUnit.SECONDS))
        check(!original.snapshot().tokenAbsenceObserved)
        allow.countDown(); closeModel(original)
    }; cases++
    cameraCase("observer-failure-does-not-mask-native-primary") {
        val nativeFailure = IllegalArgumentException("first native")
        val service = ModelCameraService()
        service.registerAction = { config -> config.virtualCameraCallback.onOpenCamera(); throw nativeFailure }
        val events = object : PhoneCameraEvents {
            override fun onObservation(original: OwnedPhoneRegistration, observation: PhoneCameraObservation) { error("observer") }
        }
        val original = Qpr2VirtualCameraOwner(ModelCameraPlatform(service)).register(modelProfile(), events)
        awaitCamera("native primary actually observed") { original.snapshot().registerCall.state == PhoneNativeCallState.THREW }
        closeModel(original)
        check(original.snapshot().firstPrimaryFailure === nativeFailure)
        awaitCamera("separate observer failure") { original.snapshot().observerFailures.isNotEmpty() }
    }; cases++
    cameraCase("inline-dispatch-cannot-invoke-native-or-fake-join") {
        val platform = ModelCameraPlatform(ModelCameraService())
        val original = Qpr2VirtualCameraOwner(platform) { it.run() }.register(modelProfile(), ModelCameraEvents())
        val state = original.snapshot()
        check(platform.lookups.get() == 0 && state.registerCall.state == PhoneNativeCallState.NOT_STARTED)
        check(!state.nativeWorker.joined && state.nativeWorker.startFailure != null)
    }; cases++
    cameraCase("rejected-dispatch-keeps-original-and-queued-work") {
        val failure = IllegalStateException("model thread admission failure")
        val owner = Qpr2VirtualCameraOwner(ModelCameraPlatform(ModelCameraService())) { throw failure }
        val original = owner.register(modelProfile(), ModelCameraEvents())
        check(owner.registrations().single() === original)
        val state = original.snapshot()
        check(!state.nativeWorker.joined && state.nativeWorker.queued > 0 && state.nativeWorker.startFailure === failure)
    }; cases++
    cameraCase("no-op-dispatch-is-not-work-or-a-join") {
        val platform = ModelCameraPlatform(ModelCameraService())
        val original = Qpr2VirtualCameraOwner(platform) {}.register(modelProfile(), ModelCameraEvents())
        check(platform.lookups.get() == 0 && original.snapshot().nativeWorker.startFailure != null)
        check(!original.snapshot().nativeWorker.threadEntered && !original.snapshot().nativeWorker.joined)
    }; cases++
    cameraCase("throw-after-real-start-does-not-erase-native-invocation") {
        val platform = ModelCameraPlatform(ModelCameraService())
        val failure = IllegalStateException("start port threw after starting original")
        val original = Qpr2VirtualCameraOwner(platform) { it.start(); throw failure }
            .register(modelProfile(), ModelCameraEvents())
        awaitCamera("native invocation despite thrown start return") { original.snapshot().registerCall.state == PhoneNativeCallState.RETURNED }
        check(platform.lookups.get() == 1 && original.snapshot().nativeWorker.startFailure === failure)
        closeModel(original)
    }; cases++
    cameraCase("late-original-callback-invalidates-knownclear-revision") {
        val service = ModelCameraService(); val original = liveModel(service); closeModel(original)
        val before = original.snapshot(); val surface = Surface()
        service.callback!!.onStreamConfigured(12, surface, 4, 5, 1)
        check(original.snapshot().revision > before.revision)
        awaitCamera("late wrapper actually released once") { surface.releaseCount.get() == 1 }
        check(original.snapshot().callbackQuiescence == "UNOBSERVED" && original.snapshot().fullH == "UNKNOWN")
    }; cases++
    cameraCase("thrown-unregister-plus-fresh-empty-id-still-pending-once") {
        val service = ModelCameraService(); val failure = IllegalStateException("native outcome indeterminate")
        // HOST MODEL ONLY: permit this deliberately unresolved model lane to survive main's return
        // as a daemon. Product default threads are not daemon and no product join is asserted here.
        val original = Qpr2VirtualCameraOwner(ModelCameraPlatform(service)) {
            it.isDaemon = true; it.start()
        }.register(modelProfile(), ModelCameraEvents())
        awaitCamera("actual model register returned") { original.snapshot().registerCall.state == PhoneNativeCallState.RETURNED }
        service.unregisterAction = { service.id = ""; throw failure }
        original.requestClose()
        awaitCamera("thrown unregister and independent fresh empty ID") {
            val s = original.snapshot()
            s.unregisterCall.state == PhoneNativeCallState.THREW && s.postUnregisterId.state == PhoneNativeCallState.RETURNED
        }
        original.requestClose()
        val pending = original.snapshot()
        check(pending.postUnregisterId.value == "" && !pending.tokenAbsenceObserved)
        check(pending.firstPrimaryFailure === failure && !pending.nativeWorker.joined && !pending.nativeWorker.loopReturned)
        check(service.unregisters.get() == 1)
        println("OBSERVED hostDaemonModelDuty=PENDING_ORIGINAL_UNREGISTER actualProductJoinProof=false")
    }; cases++
    return cases
}

fun main() {
    val owner = runOwnerScenarios()
    val lease = runLeaseScenarios()
    val generated = runGeneratedContractScenarios()
    println("RESULT ownerCases=$owner leaseCases=$lease generatedCases=$generated actualAndroidProof=false nativeABIProof=false callbackQuiescence=UNOBSERVED")
}
