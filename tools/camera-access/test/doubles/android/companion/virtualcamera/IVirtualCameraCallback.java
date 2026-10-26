package android.companion.virtualcamera;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.view.Surface;
/** Method/type shape only. No Parcel, Binder transaction, oneway or Android ABI implementation. */
public interface IVirtualCameraCallback extends IInterface {
    void onStreamConfigured(int streamId, Surface surface, int width, int height, int pixelFormat);
    void onProcessCaptureRequest(int streamId, int frameId);
    void onStreamClosed(int streamId);
    abstract class Stub extends Binder implements IVirtualCameraCallback {
        @Override public IBinder asBinder() { return this; }
        @Override public String getInterfaceDescriptor() { return "android.companion.virtualcamera.IVirtualCameraCallback"; }
    }
}
