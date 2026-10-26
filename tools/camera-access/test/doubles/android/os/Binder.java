package android.os;
public class Binder implements IBinder {
    private static final ThreadLocal<int[]> CALLER = ThreadLocal.withInitial(() -> new int[]{2000, 32001});
    public static int getCallingUid() { return CALLER.get()[0]; }
    public static int getCallingPid() { return CALLER.get()[1]; }
    public static void setCallingIdentity(int uid, int pid) { CALLER.set(new int[]{uid, pid}); }
    @Override public String getInterfaceDescriptor() { return "TYPE_DOUBLE_ONLY"; }
}
