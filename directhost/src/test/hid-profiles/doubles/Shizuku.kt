package rikka.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder

object Shizuku {
    var available = true
    lateinit var binder: IBinder
    val connections = mutableListOf<ServiceConnection>()
    fun interface OnBinderReceivedListener { fun onBinderReceived() }
    fun interface OnBinderDeadListener { fun onBinderDead() }
    fun interface OnRequestPermissionResultListener { fun onRequestPermissionResult(request: Int, result: Int) }
    class UserServiceArgs(val component: ComponentName) {
        fun tag(value: String) = this
        fun processNameSuffix(value: String) = this
        fun daemon(value: Boolean) = this
        fun version(value: Int) = this
    }
    fun addBinderReceivedListenerSticky(listener: OnBinderReceivedListener) = Unit
    fun addBinderDeadListener(listener: OnBinderDeadListener) = Unit
    fun addRequestPermissionResultListener(listener: OnRequestPermissionResultListener) = Unit
    fun pingBinder() = available
    fun checkSelfPermission() = 0
    fun bindUserService(args: UserServiceArgs, connection: ServiceConnection) {
        connections.add(connection)
        connection.onServiceConnected(args.component, binder)
    }
    fun unbindUserService(args: UserServiceArgs, connection: ServiceConnection, remove: Boolean) {
        connections.remove(connection)
    }
}
