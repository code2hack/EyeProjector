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
    private val preferences = app.getSharedPreferences("phone_hid_bindings", Context.MODE_PRIVATE)
    val controller = PhoneHidController(
        PhoneHidBindings({ preferences.getString("bindings", null) },
            { preferences.edit().putString("bindings", it).commit() }),
        object : PhoneHidRoutes {
            override fun capture(down: PhoneHidEdge): PhoneHidRoute? = sessions.capturePhoneHidRoute(down)
            override fun submit(route: PhoneHidRoute, edge: PhoneHidEdge) = sessions.submitPhoneHid(route, edge)
            override fun cancel(route: PhoneHidRoute, eventTimeMs: Long) = sessions.cancelPhoneHid(route, eventTimeMs)
        },
        { operation, complete -> writer.execute { try { operation() } finally { main.post { complete() } } } },
        SystemClock::uptimeMillis,
        ::publish,
    )

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
