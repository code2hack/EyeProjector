package rikka.shizuku

import android.content.*
import com.code2hack.eyeprojector.directhost.FakeService

/** Modeled API events only: no Shizuku daemon or Android privileges in this harness. */
object Shizuku {
    fun interface OnBinderReceivedListener { fun onBinderReceived() }
    fun interface OnBinderDeadListener { fun onBinderDead() }
    fun interface OnRequestPermissionResultListener { fun onRequestPermissionResult(code: Int, result: Int) }
    class UserServiceArgs(val component: ComponentName) {
        var revision = 1
        fun tag(s: String) = this
        fun processNameSuffix(s: String) = this
        fun daemon(b: Boolean) = this
        fun version(i: Int) = apply { revision = i }
    }
    var binds = 0
    var detaches = 0
    var peekVersion = -1
    var peekService: FakeService? = null
    var available = true
    var connection: ServiceConnection? = null
    var args: UserServiceArgs? = null
    val boundConnections = mutableListOf<ServiceConnection>()
    fun addBinderReceivedListenerSticky(l: OnBinderReceivedListener) {}
    fun addBinderDeadListener(l: OnBinderDeadListener) {}
    fun addRequestPermissionResultListener(l: OnRequestPermissionResultListener) {}
    fun pingBinder() = available
    fun getVersion() = 13
    fun checkSelfPermission() = 0
    fun getUid() = 2000
    fun requestPermission(i: Int) {}
    fun bindUserService(a: UserServiceArgs, c: ServiceConnection) {
        binds++; args = a; connection = c; boundConnections.add(c)
    }
    fun peekUserService(a: UserServiceArgs, c: ServiceConnection): Int {
        args = a; connection = c
        // Queue a binder before returning, matching the upstream peek ordering.
        peekService?.let { c.onServiceConnected(a.component, it) }
        return peekVersion
    }
    fun unbindUserService(a: UserServiceArgs, c: ServiceConnection, remove: Boolean) {
        check(!remove) { "Attachment recovery must not destroy a running service" }
        detaches++
    }
    fun reset() {
        binds = 0; detaches = 0; peekVersion = -1; peekService = null
        available = true; connection = null; args = null; boundConnections.clear()
    }
}
