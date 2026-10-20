package android.os
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(looper: Looper) { fun post(callback: () -> Unit): Boolean { callback(); return true } }
interface IBinder {
 val isBinderAlive: Boolean
 fun linkToDeath(recipient: () -> Unit, flags: Int)
}
