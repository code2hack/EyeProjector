package android.os;
/** Queue ownership double: no Android/JVM background thread is started. */
public final class HandlerThread extends Thread {
    private final Looper looper = new Looper();
    public HandlerThread(String name) { super(name); }
    @Override public synchronized void start() { }
    public Looper getLooper() { return looper; }
    public boolean quitSafely() { looper.acceptsPosts = false; return true; }
}
