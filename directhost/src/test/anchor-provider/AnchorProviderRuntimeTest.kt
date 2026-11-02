package com.code2hack.eyeprojector.directhost.anchor.execution

import android.content.Context
import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.anchor.preferences.*
import com.code2hack.eyeprojector.directhost.anchor.target.*
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** External Context/service handles are doubles; runtime/reflection/FIFO/store/reader/owner are real. */
class AnchorProviderRuntimeTest {
    @Test fun missingSuccessAndPartialInstallUseActualRuntimeAndFollowingFifoClick() {
        val directory = Files.createTempDirectory("eyeprojector-provider-r1-").toFile()
        try {
            val missing = AnchorAppRuntime(Context(directory), "com.code2hack.eyeprojector.AbsentRuntimeProviderForTest")
            assertEquals(AnchorAppRuntime.ProviderState.MISSING, missing.registrationProviderState)
            assertNull(missing.registrationInstallationFailure)
            val installed = AnchorAppRuntime(Context(directory), RuntimeSuccessProvider::class.java.name)
            installed.drain()
            assertEquals(AnchorAppRuntime.ProviderState.INSTALLED, installed.registrationProviderState)
            assertNotNull(installed.coordinator.registration); assertNotNull(installed.coordinator.registrationMessage)

            val failed = AnchorAppRuntime(Context(directory), RuntimePartialFailureProvider::class.java.name)
            failed.drain()
            assertEquals(AnchorAppRuntime.ProviderState.FAILED, failed.registrationProviderState)
            assertSame(RuntimeProviderFailures.partial, failed.registrationInstallationFailure)
            assertNull(failed.coordinator.registration); assertNull(failed.coordinator.registrationMessage)

            val size = Geometry(480, 640)
            val source = ManagedUiSource(ProjectionContext("owner", "original"), "display", 47, "managed", "app", null,
                UiSourceGeometry(size, size, 160, 0, false, size))
            val window = UiWindowMetadata(47, 7, 1, 0, true, true, UiBounds(0, 0, 480, 640))
            val target = UiTargetObservation(0, 7, "app", "app:id/action", "Button", UiBounds(300, 400, 340, 440),
                listOf(16), true, false, true, true)
            UiTargetAccess.currentReader = UiTargetReader(object : UiAccess {
                override val canRead = true
                override fun windowsOnAllDisplays() = mapOf(47 to listOf(object : UiAccessWindow {
                    override val metadata = window
                    override fun root() = object : UiAccessNode {
                        override val packageName = "app"; override val windowId = 7; override val childCount = 0
                        override fun child(index: Int): UiAccessNode? = null
                        override fun observation(ordinal: Int) = target
                        override fun close() = Unit
                    }
                    override fun close() = Unit
                }))
            }, { 0 })
            failed.store.replace(AppAnchor(AnchorTargetDescriptor("app", null, 1, "app:id/action", "Button"), AnchorMode.CLICK))
            val touches = mutableListOf<Touch>()
            val owner = AnchorContactOwner(Any(), { touches.add(it); true }, {})
            val intent = FunctionIntent(FunctionIntentKind.ACTIVATE, FunctionSource.GLASSES_NATIVE, 1, 1,
                FunctionIntentContext(ReceiverUsability("owner", ReceiverIncarnation("glasses", 1), 1, 1, ReceiverUsabilityMode.PROJECTED),
                    HeadPointerState(1, 1, true, true, 10f, 20f, Fit(0, 0, 480, 640), size)))
            val ticket = owner.capture(intent)!!
            val feedback = mutableListOf<AnchorExecutionStatus>()
            failed.coordinator.accept(object : AnchorOriginalRoute {
                override val intent = intent
                override fun source() = source
                override fun reconcile(setting: AnchorSettingContext) = owner.reconcile(setting)
                override fun click(setting: AnchorSettingContext, target: ResolvedAnchorTarget) = owner.click(ticket, setting, target, source) { true }
                override fun retire() = owner.retire()
                override fun feedback(status: AnchorExecutionStatus) { feedback.add(status) }
            })
            failed.drain()
            assertEquals(listOf(AnchorExecutionStatus.CLICK_SUBMITTED), feedback)
            assertEquals(listOf(Touch.Phase.DOWN, Touch.Phase.UP), touches.map { it.phase })
            assertEquals(touches[0].contactId, touches[1].contactId)
            assertTrue(touches.all { it.target == Tap(320, 420, size) })
            assertEquals(0, RuntimeProviderFailures.failedHandlerCalls)
        } finally { UiTargetAccess.currentReader = null; directory.deleteRecursively() }
    }
    private fun AnchorAppRuntime.drain() {
        val done = CountDownLatch(1); onLane { done.countDown() }; assertTrue(done.await(3, TimeUnit.SECONDS))
    }
}
internal object RuntimeProviderFailures {
    val partial = ClassNotFoundException("actual provider fails after queueing handlers")
    var failedHandlerCalls = 0
}
internal class RuntimeSuccessProvider : AnchorRegistrationInstaller {
    override fun install(runtime: AnchorAppRuntime) { runtime.installRegistration({}, { _, _ -> }) }
}
internal class RuntimePartialFailureProvider : AnchorRegistrationInstaller {
    override fun install(runtime: AnchorAppRuntime) {
        runtime.installRegistration({ RuntimeProviderFailures.failedHandlerCalls++ }, { _, _ -> RuntimeProviderFailures.failedHandlerCalls++ })
        throw RuntimeProviderFailures.partial
    }
}
