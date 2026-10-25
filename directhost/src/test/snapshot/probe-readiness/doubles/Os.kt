package android.os

import java.util.concurrent.ConcurrentHashMap

interface IBinder
class Bundle {
    private val values = ConcurrentHashMap<String, Any>()
    fun getString(key: String, default: String): String = values[key] as? String ?: default
    fun putString(key: String, value: String) { values[key] = value }
    fun putInt(key: String, value: Int) { values[key] = value }
    fun putBoolean(key: String, value: Boolean) { values[key] = value }
    operator fun get(key: String): Any? = values[key]
    fun snapshot(): Map<String, Any> = values.toMap()
}
object Process {
    fun myPid() = 100
    fun myUid() = 10479
    fun is64Bit() = true
}
/** Controllable Android monotonic-clock boundary; no production clock is replaced. */
object SystemClock {
    @Volatile var advanceNanos = 0L
    fun elapsedRealtimeNanos() = System.nanoTime() + advanceNanos
}
