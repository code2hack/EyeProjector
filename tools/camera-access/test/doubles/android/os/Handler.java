package android.os;
public final class Handler {
    public interface BeforePost { void accept(Handler handler, Runnable task); }
    // Test-only handoff hook; it runs BEFORE enqueue and outside the queue lock.
    public static volatile BeforePost beforePostForTest;
    private final Looper looper;
    public Handler(Looper looper) { this.looper = looper; }
    public boolean post(Runnable task) {
        BeforePost hook = beforePostForTest;
        if (hook != null) hook.accept(this, task);
        return looper.post(task, false);
    }
    public boolean postDelayed(Runnable task, long delayMillis) { return looper.post(task, true); }
    public void removeCallbacks(Runnable task) { looper.remove(task); }
    public Looper getLooper() { return looper; }
}
