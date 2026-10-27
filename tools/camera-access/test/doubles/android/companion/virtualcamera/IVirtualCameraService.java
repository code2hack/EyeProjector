package android.companion.virtualcamera;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
/** Pinned method/type shape only; it supplies no real service or wire decoder. */
public interface IVirtualCameraService extends IInterface {
    boolean registerCamera(IBinder token, VirtualCameraConfiguration configuration, int deviceId);
    void unregisterCamera(IBinder token);
    String getCameraId(IBinder token);
    abstract class Stub extends Binder implements IVirtualCameraService {
        public static IVirtualCameraService asInterface(IBinder binder) { return (IVirtualCameraService) binder; }
        @Override public IBinder asBinder() { return this; }
    }
}
