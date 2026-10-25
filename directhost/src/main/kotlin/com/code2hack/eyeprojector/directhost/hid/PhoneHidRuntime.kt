package com.code2hack.eyeprojector.directhost.hid

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.code2hack.eyeprojector.directhost.PhoneSessionController
import com.code2hack.eyeprojector.directhost.activation.*
import com.code2hack.eyeprojector.directhost.activation.snapshot.*
import com.code2hack.eyeprojector.directhost.input.PhoneHidRoute
import com.code2hack.eyeprojector.protocol.PhoneHidEdge
import java.io.Closeable
import java.util.concurrent.Executors

/** App-scoped ownership outlives the Phone panel. Only the Accessibility service feeds key edges. */
internal class PhoneHidRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private val observers = linkedSetOf<() -> Unit>()
    private var serviceEnabled = false
    private val sessions = PhoneSessionController.get(app)
    private val snapshot = PhoneHidSnapshotLease()
    private var settingUp = false
    private var reconcileQueued = false
    private var replaceSnapshot = false
    // An admitted setup obligation survives the raw DOWN/UP frame. It grants no target action.
    private var pendingSetup: HidInputAuthority? = null
    private val preferences = app.getSharedPreferences("phone_hid_bindings", Context.MODE_PRIVATE)
    val controller = PhoneHidController(
        PhoneHidBindings({ preferences.getString("bindings", null) },
            { preferences.edit().putString("bindings", it).commit() }),
        object : PhoneHidRoutes {
            override fun capture(down: PhoneHidEdge): PhoneHidRoute? = null
            override fun capture(down: PhoneHidEdge, admission: PhoneHidCapture): PhoneHidRoute? {
                val original = admission.wake?.authority ?: return null
                if (!sessions.isHidAuthorityCurrent(original)) return null
                return admission.route?.invoke(down)
            }
            override fun submit(route: PhoneHidRoute, edge: PhoneHidEdge) = sessions.submitPhoneHid(route, edge)
            override fun cancel(route: PhoneHidRoute, eventTimeMs: Long) = sessions.cancelPhoneHid(route, eventTimeMs)
        },
        { operation, complete -> writer.execute { try { operation() } finally { main.post { complete() } } } },
        SystemClock::uptimeMillis,
        ::publish,
        object : PhoneHidActivation {
            override fun needsWake() = true // Production uses the typed capture below, never this compatibility port.
            override fun capture(): PhoneHidCapture {
                val captured = snapshot.capture(sessions.captureHidAuthority())
                if (captured.disposition == PhoneHidDisposition.UNKNOWN) {
                    if (captured.wake?.setupReason == HidSetupReason.RETIRED) replaceSnapshot = true
                    scheduleSnapshot()
                }
                return captured
            }
            override fun request(cause: ActivationCause) { sessions.requestActivation(cause) }
            override fun request(cause: ActivationCause, capture: PhoneHidCapture) {
                val wake = capture.wake ?: return
                val h = sessions.requestHidActivation(wake) ?: return
                if (capture.disposition == PhoneHidDisposition.UNKNOWN) {
                    pendingSetup = wake.authority.copy(handle = h)
                    scheduleSnapshot()
                }
            }
        },
    )

    init {
        sessions.onHidInputInvalidated { preserveLearning ->
            if (preserveLearning) controller.presentationLost() else controller.cancelInput()
        }
        sessions.observeState { scheduleSnapshot() }
    }

    private fun scheduleSnapshot() {
        if (reconcileQueued) return
        reconcileQueued = true
        main.post { reconcileQueued = false; reconcileSnapshot() }
    }

    /** Runs between ingress callbacks on main; setup and JNI loading never occupy key ingress. */
    private fun reconcileSnapshot() {
        pendingSetup?.let { if (!sessions.isHidAuthorityCurrent(it)) pendingSetup = null }
        // Service attachment owns key ingress, not an already accepted H-scoped wake obligation.
        if (!controller.connected && pendingSetup == null) {
            snapshot.clear()?.let(sessions::retireHidRegion)
            return
        }
        val original = sessions.captureHidAuthority()
        if (replaceSnapshot || !snapshot.matches(original)) {
            replaceSnapshot = false
            snapshot.clear()?.let(sessions::retireHidRegion)
            if (settingUp || !original.context.available) return
            val source = sessions.bootstrap.capturePhoneHidSnapshot(original.context) ?: return
            settingUp = true
            writer.execute {
                var prepared: NativeSnapshotReadLane.Prepared? = null
                try { prepared = source.prepare() }
                catch (_: LinkageError) { /* Loader availability remains UNKNOWN. */ }
                catch (_: Exception) { /* Original demand remains pending for later setup observation. */ }
                val result = prepared
                main.post {
                    settingUp = false
                    if (result == null) return@post
                    if ((!controller.connected && pendingSetup == null) || !source.current() ||
                        !sessions.isHidAuthorityCurrent(original)) { result.close(); return@post }
                    val installed = sessions.installHidRegion(original, result.regionId)
                    if (installed == null) { result.close(); return@post }
                    snapshot.install(installed, source, result)
                    resolvePendingSetup()
                }
            }
        } else resolvePendingSetup()
    }

    private fun resolvePendingSetup() {
        val pending = pendingSetup ?: return
        if (!sessions.isHidAuthorityCurrent(pending)) { pendingSetup = null; return }
        val resolved = snapshot.capture(sessions.captureHidAuthority())
        if (resolved.disposition == PhoneHidDisposition.UNKNOWN) {
            if (resolved.wake?.setupReason == HidSetupReason.RETIRED) {
                replaceSnapshot = true
                scheduleSnapshot()
            }
            return
        }
        // Validation above binds this new setup observation to the accepted original H, never H2.
        pendingSetup = null
        if (resolved.disposition == PhoneHidDisposition.NONE) resolved.wake?.let { sessions.requestHidActivation(it, pending) }
        // USABLE fulfills setup only. The original raw press is never replayed as a target action.
    }

    fun observe(observer: () -> Unit): Closeable {
        observers.add(observer)
        return Closeable { observers.remove(observer) }
    }

    fun enabled(): Boolean = serviceEnabled

    private fun readEnabled(): Boolean {
        val expected = ComponentName(app, PhoneHidKeyService::class.java)
        val manager = app.getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val service = it.resolveInfo.serviceInfo
            ComponentName(service.packageName, service.name) == expected
        }
    }

    fun openSettings(): Boolean = try {
        app.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(app, PhoneHidKeyService::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) { false }

    fun refresh() { serviceEnabled = readEnabled(); publish() }
    private fun publish() {
        scheduleSnapshot()
        observers.toList().forEach { try { it() } catch (_: Exception) { /* UI observers do not own input. */ } }
    }

    companion object {
        @SuppressLint("StaticFieldLeak") // The singleton retains only applicationContext, never an Activity.
        @Volatile private var instance: PhoneHidRuntime? = null
        fun get(context: Context): PhoneHidRuntime = instance ?: synchronized(this) {
            instance ?: PhoneHidRuntime(context.applicationContext).also { instance = it }
        }
    }
}
