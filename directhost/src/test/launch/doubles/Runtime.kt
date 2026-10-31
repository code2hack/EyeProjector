package android.os
object Build { const val MODEL = "modeled-host" }
object Process { @JvmStatic fun myUid() = 2000; @JvmStatic fun myPid() = 42; @JvmStatic fun myTid() = 43 }

object SystemClock {
    @JvmStatic fun uptimeMillis() = System.nanoTime() / 1_000_000
    @JvmStatic fun elapsedRealtimeNanos() = System.nanoTime()
    @JvmStatic fun elapsedRealtime() = elapsedRealtimeNanos() / 1_000_000
}
