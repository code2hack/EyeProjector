package android.os;
/** Host monotonic clock shape; not an Android/camera timestamp association. */
public final class SystemClock {
    public static long elapsedRealtimeNanos() { return System.nanoTime(); }
    public static long elapsedRealtime() { return elapsedRealtimeNanos() / 1000000L; }
    private SystemClock() { }
}
