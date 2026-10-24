package com.code2hack.eyeprojector.cameraaccess;

import android.companion.virtualcamera.Format;
import android.companion.virtualcamera.IVirtualCameraCallback;
import android.companion.virtualcamera.IVirtualCameraService;
import android.companion.virtualcamera.LensFacing;
import android.companion.virtualcamera.SensorOrientation;
import android.companion.virtualcamera.SupportedStreamConfiguration;
import android.companion.virtualcamera.VirtualCameraConfiguration;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.view.Surface;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/** Isolated shell/Shizuku-UID experiment. No projection or physical-camera operations. */
public final class CameraAccessMain {
    private static final String PERMISSION = "android.permission.CREATE_VIRTUAL_DEVICE";
    private final IBinder token = new Binder();
    private final IVirtualCameraService service;
    private final HandlerThread thread = new HandlerThread("owned-camera-producer");
    private final Handler renderer;
    private boolean cleanupRequired;
    private ProducerCallback activeCallback;

    static synchronized void event(String name, Object... fields) {
        JSONObject json = new JSONObject();
        try {
            json.put("event", name);
            json.put("elapsedNanos", android.os.SystemClock.elapsedRealtimeNanos());
            for (int i = 0; i < fields.length; i += 2) {
                json.put((String) fields[i], fields[i + 1] == null ? JSONObject.NULL : fields[i + 1]);
            }
        } catch (org.json.JSONException error) { throw new IllegalArgumentException(error); }
        System.out.println(json);
        System.out.flush();
    }

    private CameraAccessMain(IVirtualCameraService service) {
        this.service = service;
        thread.start();
        renderer = new Handler(thread.getLooper());
    }

    private final class ProducerCallback extends IVirtualCameraCallback.Stub {
        private volatile boolean active = true;
        // Each registration owns a separate callback/map; stream IDs can repeat on reopen.
        private final Map<Integer, SyntheticStream> streams = new HashMap<>();
        @Override public void onStreamConfigured(int id, Surface surface, int width, int height, int format) {
            boolean accepted = renderer.post(() -> {
                if (!active) { surface.release(); return; }
                SyntheticStream previous = streams.remove(id);
                if (previous != null) previous.close();
                try {
                    SyntheticStream stream = new SyntheticStream(id, surface, width, height, renderer);
                    streams.put(id, stream);
                    stream.start();
                    event("stream_configured", "streamId", id, "width", width, "height", height, "format", format);
                } catch (RuntimeException error) {
                    surface.release();
                    event("producer_error", "streamId", id, "error", error.toString());
                }
            });
            if (!accepted) surface.release();
        }
        @Override public void onProcessCaptureRequest(int id, int frameId) {
            renderer.post(() -> {
                SyntheticStream stream = streams.get(id);
                if (stream != null) stream.captureRequested(frameId);
            });
        }
        @Override public void onStreamClosed(int id) {
            renderer.post(() -> {
                SyntheticStream stream = streams.remove(id);
                if (stream != null) stream.close();
                event("stream_closed", "streamId", id);
            });
        }
        void release() {
            active = false;
            renderer.post(() -> {
                for (SyntheticStream stream : streams.values()) stream.close();
                streams.clear();
                event("producer_resources_released");
            });
        }
        void report() { renderer.post(() -> streams.values().forEach(SyntheticStream::report)); }
    }

    private void register() throws Exception {
        if (cleanupRequired) {
            event("registration_pending", "detail", "unregister the existing owned token first");
            return;
        }
        SupportedStreamConfiguration input = new SupportedStreamConfiguration();
        input.width = 640;
        input.height = 480;
        input.pixelFormat = Format.RGBA_8888;
        input.maxFps = 30;
        VirtualCameraConfiguration config = new VirtualCameraConfiguration();
        config.supportedStreamConfigs = new SupportedStreamConfiguration[]{input};
        activeCallback = new ProducerCallback();
        config.virtualCameraCallback = activeCallback;
        config.sensorOrientation = SensorOrientation.ORIENTATION_0;
        config.lensFacing = LensFacing.EXTERNAL;
        // A failed Binder response can be ambiguous. Retain this exact token for cleanup/retry.
        cleanupRequired = true;
        boolean registered;
        try {
            registered = service.registerCamera(token, config, 0);
        } catch (SecurityException denied) {
            // The pinned service rejects permission before touching its token map.
            cleanupRequired = false;
            activeCallback.release();
            activeCallback = null;
            throw denied;
        }
        cleanupRequired = registered;
        if (!registered) { activeCallback.release(); activeCallback = null; }
        event("register_result", "registered", registered, "androidDeviceId", 0,
                "input", "640x480 RGBA_8888 maxFps=30");
        event("camera_id", "cameraId", service.getCameraId(token));
    }

    private boolean unregister() {
        if (!cleanupRequired) return true;
        try {
            service.unregisterCamera(token);
            String remaining = service.getCameraId(token);
            if (remaining != null && !remaining.isEmpty()) {
                event("cleanup_unconfirmed", "cameraId", remaining);
                return false;
            }
            cleanupRequired = false;
            if (activeCallback != null) activeCallback.release();
            activeCallback = null;
            event("unregister_result", "tokenAbsent", true);
            return true;
        } catch (Exception error) {
            event("cleanup_unconfirmed", "error", error.toString(), "tokenRetained", true);
            return false;
        }
    }

    private void run() throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        event("ready", "commands", "register | status | unregister | quit");
        for (String line; (line = input.readLine()) != null;) {
            try {
                switch (line.trim()) {
                    case "register": register(); break;
                    case "status":
                        event("status", "cleanupRequired", cleanupRequired, "cameraId", service.getCameraId(token));
                        if (activeCallback != null) activeCallback.report();
                        break;
                    case "unregister": unregister(); break;
                    case "quit": if (unregister()) return; break;
                    default: event("unknown_command", "command", line); break;
                }
            } catch (Exception error) {
                event("operation_failed", "operation", line, "error", error.toString(), "tokenRetained", cleanupRequired);
            }
        }
        if (!unregister()) {
            // Keep the token/process available for diagnosis; abrupt termination cannot prove cleanup.
            event("eof_cleanup_failed", "detail", "owned token retained in live process; inspect service before termination");
            thread.join();
        }
    }

    private static Object permissionObservation() {
        try {
            Object pm = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
            return Class.forName("android.content.pm.IPackageManager")
                    .getMethod("checkUidPermission", String.class, int.class).invoke(pm, PERMISSION, Process.myUid());
        } catch (Exception error) {
            return "UNRESOLVED: " + error;
        }
    }

    public static void main(String[] args) throws Exception {
        event("caller", "uid", Process.myUid(), "pid", Process.myPid(), "sdk", Build.VERSION.SDK_INT,
                "fingerprint", Build.FINGERPRINT, "permission", PERMISSION,
                "permissionResult", permissionObservation(), "androidDeviceId", 0);
        Class<?> manager = Class.forName("android.os.ServiceManager");
        IBinder cached = (IBinder) manager.getMethod("checkService", String.class).invoke(null, "virtual_camera");
        event("service_check", "cached", cached != null);
        // getService requests lazy startup; checkService(null) alone is not an unavailable verdict.
        IBinder binder = (IBinder) manager.getMethod("getService", String.class).invoke(null, "virtual_camera");
        if (binder == null) {
            event("service_lookup_waiting", "detail", "getService may have initiated lazy startup; absence unresolved");
            // No registration exists during this wait. Root may bound/terminate the preflight.
            binder = (IBinder) manager.getMethod("waitForService", String.class).invoke(null, "virtual_camera");
        }
        if (binder == null) {
            event("service_lookup_unresolved", "detail", "waitForService returned null");
            return;
        }
        event("service_found", "descriptor", binder.getInterfaceDescriptor());
        if (args.length == 1 && args[0].equals("inspect")) return;
        CameraAccessMain experiment = new CameraAccessMain(IVirtualCameraService.Stub.asInterface(binder));
        try { experiment.run(); }
        finally {
            if (experiment.unregister()) {
                experiment.thread.quitSafely();
                experiment.thread.join();
                event("exit", "cleanupConfirmed", true);
            } else {
                event("exit_pending", "cleanupConfirmed", false);
                experiment.thread.join();
            }
        }
    }
}
