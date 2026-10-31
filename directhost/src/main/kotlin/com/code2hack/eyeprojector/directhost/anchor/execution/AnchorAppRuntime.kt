package com.code2hack.eyeprojector.directhost.anchor.execution

import android.content.Context
import android.os.SystemClock
import com.code2hack.eyeprojector.directhost.anchor.preferences.AppAnchorPreferences
import com.code2hack.eyeprojector.directhost.anchor.preferences.AnchorSettingContext
import com.code2hack.eyeprojector.directhost.anchor.target.ResolvedAnchorTarget
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Optional121 feature installation. Absence leaves the typed unavailable registration seam. */
internal fun interface AnchorRegistrationInstaller { fun install(runtime: AnchorAppRuntime) }

/** One app FIFO and app-private store, installed on the actual HostBootstrap binding. */
internal class AnchorAppRuntime(context: Context, registrationProviderName: String =
    "com.code2hack.eyeprojector.directhost.anchor.registration.AnchorRegistrationProvider") {
    enum class ProviderState { MISSING, INSTALLED, FAILED }
    var registrationProviderState = ProviderState.MISSING
        private set
    var registrationInstallationFailure: Throwable? = null
        private set
    val appContext: Context = context.applicationContext
    val store = AppAnchorPreferences.open(appContext)
    private val bindingGeneration = AtomicLong()
    private val lane = Executors.newSingleThreadExecutor { Thread(it, "eyeprojector-app-anchor").apply { isDaemon = true } }
    val coordinator = AnchorAppCoordinator(store, UiTargetAccess::reader,
        SystemClock::uptimeMillis) { lane.execute(it) }
    init { UiTargetAccess.observe { change ->
        if (change == null) coordinator.contextRetired()
        else coordinator.windowChanged(change.displayId, change.windowId, change.currentChanged)
    } }
    /** Installs handlers on the same FIFO;121 creates no second worker/store/reader owner. */
    fun installRegistration(begin: (CapturedAnchorRequest) -> Unit,
        message: (AnchorMessage, AnchorOriginalRoute) -> Unit) = onLane {
        coordinator.registration = begin; coordinator.registrationMessage = message
    }
    fun onLane(action: () -> Unit) { lane.execute(action) }
    fun read(original: AnchorOriginalRoute, selectedPackage: String, windowId: Int? = null): UiTargetSnapshot {
        val source = original.source()
        val expected = source?.context ?: com.code2hack.eyeprojector.directhost.ProjectionContext()
        return UiTargetSnapshotProvider(ManagedUiSourceProvider { query -> original.source()?.takeIf { it.context == query } },
            UiTargetAccess::reader, SystemClock::uptimeMillis).read(expected, selectedPackage, windowId)
    }

    init {
        when (val result = AnchorRegistrationLoader.install(registrationProviderName,
            AnchorRegistrationInstaller::class.java) { it.install(this) }) {
            AnchorProviderInstallation.Missing -> Unit
            AnchorProviderInstallation.Installed -> registrationProviderState = ProviderState.INSTALLED
            is AnchorProviderInstallation.Failed -> {
                registrationProviderState = ProviderState.FAILED
                registrationInstallationFailure = result.cause
                onLane { coordinator.registration = null; coordinator.registrationMessage = null }
                android.util.Log.e("AnchorAppRuntime", "Registration provider initialization failed", result.cause)
            }
        }
    }
    fun invalidateBinding() { bindingGeneration.incrementAndGet(); coordinator.contextRetired() }
    fun bind(): IAnchorAppBridge {
        invalidateBinding()
        val generation = bindingGeneration.get()
        return object : IAnchorAppBridge.Stub() {
            override fun onIntent(transactionId: Long, functionIntent: ByteArray, original: IAnchorSession) {
                val intent = FunctionIntent.from(functionIntent)
                coordinator.accept(Route(transactionId, intent, original) { bindingGeneration.get() == generation })
            }
            override fun onRegistration(message: ByteArray, original: IAnchorSession) {
                val value = AnchorMessage.from(message)
                coordinator.acceptRegistration(value, Route(value.transactionId, value.intent, original) { bindingGeneration.get() == generation })
            }
        }
    }

    private class Route(override val transactionId: Long, override val intent: FunctionIntent,
        private val original: IAnchorSession, private val current: () -> Boolean) : AnchorOriginalRoute {
        override val identity: Any get() = original.asBinder()
        override fun source(): ManagedUiSource? = if (current()) original.source()?.let(ManagedUiSource::from) else null
        override fun reconcile(setting: AnchorSettingContext) = current() && original.reconcile(AnchorExecutionCodec.setting(setting))
        override fun heldSetting(): AnchorSettingContext? =
            if (current()) original.heldSetting()?.let(AnchorExecutionCodec::setting) else null
        override fun releaseHeld(expected: AnchorSettingContext?, settingCurrent: Boolean): AnchorReleaseResult =
            if (!current()) AnchorReleaseResult.RETIRED else AnchorReleaseResult.entries[
                original.releaseHeld(expected?.let(AnchorExecutionCodec::setting) ?: byteArrayOf(), settingCurrent)]
        override fun click(setting: AnchorSettingContext, target: ResolvedAnchorTarget): AnchorExecutionStatus =
            if (!current()) AnchorExecutionStatus.RETIRED else AnchorExecutionStatus.entries[original.click(AnchorExecutionCodec.setting(setting), AnchorExecutionCodec.target(target))]
        override fun hold(setting: AnchorSettingContext, target: ResolvedAnchorTarget): AnchorExecutionStatus =
            if (!current()) AnchorExecutionStatus.RETIRED else AnchorExecutionStatus.entries[original.hold(AnchorExecutionCodec.setting(setting), AnchorExecutionCodec.target(target))]
        override fun retire() = original.retire()
        override fun send(message: AnchorMessage) = original.send(message.bytes())
        override fun feedback(status: AnchorExecutionStatus) = send(AnchorMessage.Feedback(transactionId, intent,
            AnchorFeedback.valueOf(status.name)))
    }
}
