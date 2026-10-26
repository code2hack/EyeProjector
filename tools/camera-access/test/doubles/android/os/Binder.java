package android.os;
public class Binder implements IBinder {
    @FunctionalInterface public interface TransactDelegate {
        boolean invoke(Binder receiver, int code, Parcel data, Parcel reply, int flags) throws RemoteException;
    }
    private static TransactDelegate delegate;
    public static void setTransactDelegate(TransactDelegate value) { delegate = value; }
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        return delegate != null && delegate.invoke(this, code, data, reply, flags);
    }
    private static final ThreadLocal<int[]> CALLER = ThreadLocal.withInitial(() -> new int[]{2000, 32001});
    public static int getCallingUid() { return CALLER.get()[0]; }
    public static int getCallingPid() { return CALLER.get()[1]; }
    public static void setCallingIdentity(int uid, int pid) { CALLER.set(new int[]{uid, pid}); }
    @Override public String getInterfaceDescriptor() { return "TYPE_DOUBLE_ONLY"; }
}
