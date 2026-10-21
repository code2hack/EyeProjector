package android.os
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(looper: Looper) {
 fun post(callback: () -> Unit): Boolean { if (defer) pending.add(callback) else callback(); return true }
 companion object {
  @Volatile var defer = false
  private val pending = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
  fun flush() { while (true) (pending.poll() ?: return).invoke() }
 }
}
interface IBinder {
 val isBinderAlive: Boolean
 fun linkToDeath(recipient: () -> Unit, flags: Int)
}

class Bundle
