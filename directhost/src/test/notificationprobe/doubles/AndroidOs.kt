package android.os

class Bundle {
    private val values = linkedMapOf<String, String>()
    fun getString(name: String, defaultValue: String? = null): String? = values[name] ?: defaultValue
    fun putString(name: String, value: String?) { if (value == null) values.remove(name) else values[name] = value }
    fun snapshot(): Map<String, String> = values.toMap()
}

object Build { object VERSION { var SDK_INT = 35 } }

class Looper private constructor() {
    companion object {
        private val main = Looper()
        private val isMain = ThreadLocal.withInitial { false }
        fun getMainLooper(): Looper = main
        fun myLooper(): Looper? = if (isMain.get()) main else null
        fun <T> onMain(work: () -> T): T {
            val previous = isMain.get()
            isMain.set(true)
            try { return work() } finally { isMain.set(previous) }
        }
        fun requireMain() { check(myLooper() === main) { "Host boundary expected main dispatch" } }
    }
}

object SystemClock {
    var stepMillis = 0L
    private var simulated = 0L
    fun reset() { stepMillis = 0L; simulated = 0L }
    fun advance(milliseconds: Long) { check(stepMillis > 0); simulated += milliseconds }
    fun elapsedRealtime(): Long = if (stepMillis == 0L) System.nanoTime() / 1_000_000 else simulated.also { simulated += stepMillis }
}
