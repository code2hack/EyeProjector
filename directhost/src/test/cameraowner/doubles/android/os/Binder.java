package android.os;
/** Platform shape only. The harness's RemoteBridge forces generated asInterface onto Proxy. */
public class Binder implements IBinder {
    // Host observations only; this is not Binder attribution or target-app identity.
    public static int callingUid = 1000;
    public static int callingPid = 0;
    public static int getCallingUid() { return callingUid; }
    public static int getCallingPid() { return callingPid; }
    private IInterface owner;
    private String descriptor;
    public void attachInterface(IInterface value, String name) { owner = value; descriptor = name; }
    @Override public IInterface queryLocalInterface(String name) {
        return descriptor != null && descriptor.equals(name) ? owner : null;
    }
    @Override public String getInterfaceDescriptor() { return descriptor; }
    @Override public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        data.setDataPosition(0);
        boolean handled = onTransact(code, data, reply, flags);
        if (reply != null) reply.setDataPosition(0);
        return handled;
    }
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        return false;
    }
    public IBinder asBinder() { return this; }
}
