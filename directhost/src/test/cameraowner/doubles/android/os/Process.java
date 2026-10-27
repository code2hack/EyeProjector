package android.os;
/** Explicit configurable host observations, never actual Android identity. */
public final class Process {
    public static int uid = 2000;
    public static int pid = 4242;
    public static int myUid() { return uid; }
    public static int myPid() { return pid; }
    private Process() { }
}
