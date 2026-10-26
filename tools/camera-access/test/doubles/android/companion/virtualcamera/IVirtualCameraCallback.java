package android.companion.virtualcamera;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;
/** Type shape and a controlled superclass-delegate seam; no wire/oneway/Android ABI implementation. */
public interface IVirtualCameraCallback extends IInterface {
    String DESCRIPTOR = "android.companion.virtualcamera.IVirtualCameraCallback";
    void onStreamConfigured(int streamId, Surface surface, int width, int height, int pixelFormat);
    void onProcessCaptureRequest(int streamId, int frameId);
    void onStreamClosed(int streamId);
    abstract class Stub extends Binder implements IVirtualCameraCallback {
        @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            return super.onTransact(code, data, reply, flags);
        }
        @Override public IBinder asBinder() { return this; }
        @Override public String getInterfaceDescriptor() { return "android.companion.virtualcamera.IVirtualCameraCallback"; }
    }
}
