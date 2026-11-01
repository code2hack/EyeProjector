package android.os

/** Session telemetry identity boundary only; these values establish no Android privilege. */
object Process {
    @JvmStatic fun myUid() = 2000
    @JvmStatic fun myPid() = 42
    @JvmStatic fun myTid() = 43
}
