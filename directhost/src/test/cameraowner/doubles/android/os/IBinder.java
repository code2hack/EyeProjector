package android.os;
public interface IBinder {
    int FIRST_CALL_TRANSACTION = 1;
    int LAST_CALL_TRANSACTION = 0x00ffffff;
    int INTERFACE_TRANSACTION = 0x5f4e5446;
    int FLAG_ONEWAY = 1;
    IInterface queryLocalInterface(String descriptor);
    String getInterfaceDescriptor() throws RemoteException;
    boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException;
}
