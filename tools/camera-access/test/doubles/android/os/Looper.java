package android.os;
import java.util.ArrayDeque;
import java.util.Deque;
public final class Looper {
    private final Deque<Runnable> immediate = new ArrayDeque<>();
    private final Deque<Runnable> delayed = new ArrayDeque<>();
    public volatile boolean acceptsPosts = true;
    public volatile RuntimeException postFailure;
    synchronized boolean post(Runnable task, boolean later) {
        if (postFailure != null) throw postFailure;
        if (!acceptsPosts) return false;
        (later ? delayed : immediate).addLast(task);
        return true;
    }
    synchronized void remove(Runnable task) {
        immediate.removeIf(candidate -> candidate == task);
        delayed.removeIf(candidate -> candidate == task);
    }
    private void execute(Runnable task) {
        int uid = Binder.getCallingUid(), pid = Binder.getCallingPid();
        Binder.setCallingIdentity(Process.myUid(), Process.myPid());
        try { task.run(); }
        finally { Binder.setCallingIdentity(uid, pid); }
    }
    public void drain() {
        int count = 0;
        while (true) {
            Runnable task;
            synchronized (this) { task = immediate.pollFirst(); }
            if (task == null) return;
            if (++count > 1000) throw new AssertionError("Test immediate queue did not settle");
            execute(task);
        }
    }
    public boolean delayedOnce() {
        Runnable task;
        synchronized (this) { task = delayed.pollFirst(); }
        if (task == null) return false;
        execute(task);
        drain();
        return true;
    }
    public synchronized int immediateCount() { return immediate.size(); }
    public synchronized int delayedCount() { return delayed.size(); }
    public void postForTest(Runnable task) {
        if (!post(task, false)) throw new AssertionError("Test probe post rejected");
    }
}
