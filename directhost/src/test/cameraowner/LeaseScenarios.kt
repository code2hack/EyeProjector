package com.code2hack.eyeprojector.directhost.camera

import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun configuredModel(
    service: ModelCameraService, events: ModelCameraEvents, id: Int = 1, surface: Surface = Surface(),
): PhoneCameraLease {
    val size = events.leases.size
    service.callback!!.onStreamConfigured(id, surface, 640, 480, 1)
    awaitCamera("original input delivery") { events.leases.size == size + 1 }
    return events.leases.last()
}

fun runLeaseScenarios(): Int {
    var cases = 0
    cameraCase("surface-borrow-only-after-original-binding") {
        val service = ModelCameraService(); val events = ModelCameraEvents(); val original = liveModel(service, events)
        val surface = Surface(); val input = configuredModel(service, events, surface = surface)
        val producer = PhoneInputProducerIdentity(); val wrong = PhoneInputProducerIdentity()
        check(input.surfaceFor(producer) == null)
        check(input.bindProducer(producer) {})
        check(input.surfaceFor(producer) === surface && input.surfaceFor(wrong) == null)
        service.callback!!.onStreamClosed(1)
        check(input.surfaceFor(producer) == null && surface.releaseCount.get() == 0)
        check(input.producerStopped(producer, PhoneInputUseStopped(input, producer)))
        closeModel(original)
    }; cases++
    cameraCase("wrong-original-fence-cannot-release-wrapper") {
        val service = ModelCameraService(); val events = ModelCameraEvents(); val original = liveModel(service, events)
        val a = configuredModel(service, events, 1); val b = configuredModel(service, events, 2)
        val p = PhoneInputProducerIdentity(); val other = PhoneInputProducerIdentity()
        check(a.bindProducer(p) {})
        a.requestClose()
        check(!a.producerStopped(other, PhoneInputUseStopped(a, other)))
        check(!a.producerStopped(p, PhoneInputUseStopped(b, p)))
        check(!a.snapshot().surfaceReleased)
        check(a.producerStopped(p, PhoneInputUseStopped(a, p)))
        closeModel(original)
    }; cases++
    cameraCase("requestclose-nonblocking-stop-port-and-inline-fence") {
        val service = ModelCameraService(); val events = ModelCameraEvents(); val original = liveModel(service, events)
        val input = configuredModel(service, events); val p = PhoneInputProducerIdentity()
        val stopEntered = CountDownLatch(1); val stopReturn = CountDownLatch(1)
        check(input.bindProducer(p) {
            stopEntered.countDown(); stopReturn.await()
            check(input.producerStopped(p, PhoneInputUseStopped(input, p)))
        })
        input.requestClose()
        check(stopEntered.await(5, TimeUnit.SECONDS))
        check(!input.snapshot().surfaceReleased)
        stopReturn.countDown(); closeModel(original)
    }; cases++
    cameraCase("throwing-stop-retains-binding-until-exact-fence") {
        val service = ModelCameraService(); val events = ModelCameraEvents(); val original = liveModel(service, events)
        val surface = Surface(); val input = configuredModel(service, events, surface = surface)
        val producer = PhoneInputProducerIdentity(); val entered = CountDownLatch(1)
        check(input.bindProducer(producer) { entered.countDown(); error("model stop failure") })
        input.requestClose(); check(entered.await(5, TimeUnit.SECONDS))
        check(!input.snapshot().producerUseStopped && surface.releaseCount.get() == 0)
        check(input.producerStopped(producer, PhoneInputUseStopped(input, producer)))
        closeModel(original)
    }; cases++
    cameraCase("early-matching-fence-does-not-invent-native-close") {
        val service = ModelCameraService(); val events = ModelCameraEvents(); val original = liveModel(service, events)
        val surface = Surface(); val input = configuredModel(service, events, surface = surface)
        val producer = PhoneInputProducerIdentity(); check(input.bindProducer(producer) {})
        check(input.producerStopped(producer, PhoneInputUseStopped(input, producer)))
        check(surface.releaseCount.get() == 0 && !input.snapshot().closeRequested)
        service.callback!!.onStreamClosed(1); closeModel(original)
    }; cases++
    cameraCase("same-id-after-actual-close-and-terminal-retirement-reopens") {
        val service = ModelCameraService(); val events = ModelCameraEvents(); val original = liveModel(service, events)
        val a = configuredModel(service, events, 4); service.callback!!.onStreamClosed(4)
        awaitCamera("first original resource retirement") { a.snapshot().resourcesClear }
        val b = configuredModel(service, events, 4)
        check(a !== b && !original.snapshot().closeRequested)
        service.callback!!.onStreamClosed(4) // No parent epoch in this raw callback.
        check(!b.snapshot().closeRequested && original.snapshot().associationIndeterminate)
        original.requestClose(); closeModel(original)
    }; cases++
    cameraCase("overlapping-id-never-adopts-successor") {
        val service = ModelCameraService(); val events = ModelCameraEvents(); val original = liveModel(service, events)
        val a = configuredModel(service, events, 3); val second = Surface()
        service.callback!!.onStreamConfigured(3, second, 1, 1, 1)
        awaitCamera("original registration overlap retirement") { original.snapshot().closeRequested }
        check(original.snapshot().inputs.size == 2 && original.snapshot().associationIndeterminate)
        check(a.snapshot().closeRequested)
        closeModel(original); check(second.releaseCount.get() == 1)
    }; cases++
    cameraCase("same-wrapper-alias-released-once-after-original-fence") {
        val service = ModelCameraService(); val events = ModelCameraEvents(); val original = liveModel(service, events)
        val surface = Surface(); val a = configuredModel(service, events, 5, surface)
        val p = PhoneInputProducerIdentity(); check(a.bindProducer(p) {})
        service.callback!!.onStreamConfigured(6, surface, 1, 1, 1)
        check(original.snapshot().inputs.size == 2 && surface.releaseCount.get() == 0)
        check(a.producerStopped(p, PhoneInputUseStopped(a, p)))
        awaitCamera("single physical wrapper release") { surface.releaseCount.get() == 1 }
        closeModel(original); check(surface.releaseCount.get() == 1)
    }; cases++
    cameraCase("stale-registration-callback-keeps-original-registration") {
        val aService = ModelCameraService(); val bService = ModelCameraService()
        val aEvents = ModelCameraEvents(); val bEvents = ModelCameraEvents()
        val a = liveModel(aService, aEvents); val b = liveModel(bService, bEvents)
        closeModel(a)
        aService.callback!!.onStreamConfigured(10, Surface(), 1, 1, 1)
        check(a.snapshot().inputs.size == 1 && b.snapshot().inputs.isEmpty())
        closeModel(b)
    }; cases++
    cameraCase("absent-entry-surface-is-observed-not-a-null-release") {
        val service = ModelCameraService(); val original = liveModel(service)
        service.callback!!.onStreamConfigured(20, null, 0, 0, 0)
        check(original.snapshot().inputs.single().surfaceAbsent)
        closeModel(original)
        check(!original.snapshot().inputs.single().surfaceReleased)
    }; cases++
    cameraCase("first-stop-fault-preserved-separately-from-release-fault") {
        val service = ModelCameraService(); val events = ModelCameraEvents(); val original = liveModel(service, events)
        val surface = Surface(); val input = configuredModel(service, events, surface = surface)
        val p = PhoneInputProducerIdentity()
        val first = IllegalArgumentException("original stop fault")
        val release = IllegalStateException("physical wrapper release unconfirmed")
        surface.releaseFault = release
        check(input.bindProducer(p) {
            check(input.producerStopped(p, PhoneInputUseStopped(input, p)))
            throw first
        })
        original.requestClose()
        awaitCamera("native absent and input worker actually joined") {
            original.snapshot().nativeWorker.joined && input.snapshot().worker.joined
        }
        val state = input.snapshot()
        check(state.stopFailure === first && state.releaseFailure === release && state.worker.primaryFailure === first)
        check(!state.resourcesClear && !state.surfaceReleased && surface.releaseCount.get() == 1)
    }; cases++
    cameraCase("same-wrapper-after-release-remains-indeterminate-no-second-release") {
        val service = ModelCameraService(); val events = ModelCameraEvents(); val original = liveModel(service, events)
        val surface = Surface(); val first = configuredModel(service, events, 21, surface)
        service.callback!!.onStreamClosed(21)
        awaitCamera("physical first release and join") { first.snapshot().resourcesClear && first.snapshot().worker.joined }
        service.callback!!.onStreamConfigured(22, surface, 1, 1, 1)
        awaitCamera("same original registration native close") { original.snapshot().nativeWorker.joined }
        val state = original.snapshot()
        check(state.inputs.last().aliasAfterRelease && !state.knownResourcesClearAtRevision)
        check(surface.releaseCount.get() == 1)
    }; cases++
    cameraCase("callback-entry-during-release-invalidates-resource-clear") {
        val service = ModelCameraService(); val events = ModelCameraEvents(); val original = liveModel(service, events)
        val surface = Surface(); val first = configuredModel(service, events, 30, surface)
        val entered = CountDownLatch(1); val allowReturn = CountDownLatch(1)
        surface.releaseHook = Runnable { entered.countDown(); allowReturn.await() }
        service.callback!!.onStreamClosed(30)
        check(entered.await(5, TimeUnit.SECONDS))
        val revision = original.snapshot().revision
        service.callback!!.onStreamConfigured(31, surface, 4, 4, 1)
        val arrival = original.snapshot()
        check(arrival.revision > revision && arrival.inputs.size == 2)
        check(arrival.inputs.last().releaseClaimedAtArrival && !arrival.knownResourcesClearAtRevision)
        allowReturn.countDown()
        awaitCamera("in-flight original release worker joined") { first.snapshot().worker.joined && original.snapshot().nativeWorker.joined }
        check(surface.releaseCount.get() == 1 && !original.snapshot().knownResourcesClearAtRevision)
    }; cases++
    cameraCase("queued-configured-tasks-retain-original-across-reopen") {
        val service = ModelCameraService(); val entered = CountDownLatch(1); val allowEvents = CountDownLatch(1)
        val delivered = java.util.concurrent.CopyOnWriteArrayList<PhoneCameraLease>()
        val events = object : PhoneCameraEvents {
            override fun onObservation(original: OwnedPhoneRegistration, observation: PhoneCameraObservation) {
                if (observation.method == "onStreamConfigured" && delivered.isEmpty()) {
                    entered.countDown(); allowEvents.await()
                }
            }
            override fun onInputConfigured(original: PhoneCameraLease) { delivered.add(original) }
        }
        val original = liveModel(service, events)
        service.callback!!.onStreamConfigured(40, Surface(), 2, 2, 1)
        check(entered.await(5, TimeUnit.SECONDS))
        val first = original.snapshot().inputs.single().input
        service.callback!!.onStreamClosed(40)
        awaitCamera("old actual input retired before reopen") { first.snapshot().resourcesClear }
        service.callback!!.onStreamConfigured(40, Surface(), 2, 2, 1)
        val second = original.snapshot().inputs.last().input
        allowEvents.countDown()
        awaitCamera("both captured originals delivered") { delivered.size == 2 }
        check(delivered[0] === first && delivered[1] === second && first !== second)
        check(!first.bindProducer(PhoneInputProducerIdentity()) {})
        val producer = PhoneInputProducerIdentity()
        check(second.bindProducer(producer) { second.producerStopped(producer, PhoneInputUseStopped(second, producer)) })
        closeModel(original)
    }; cases++
    return cases
}
