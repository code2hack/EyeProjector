package android.os;
public final class SystemClock {
    private static long clock;
    public static long elapsedRealtimeNanos() { return ++clock; }
}
