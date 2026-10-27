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
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.view.Surface;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final ResourceInventory resources = new ResourceInventory();

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

    static void failure(String eventName, String stage, int id, RuntimeException error) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        event(eventName, "streamId", id, "stage", stage, "error", error.toString(),
                "exceptionClass", error.getClass().getName(), "stack", trace.toString());
    }

    private static String classOrigin(Class<?> type) {
        try {
            ClassLoader loader = type.getClassLoader();
            return "class=" + type.getName() + ";loader="
                    + (loader == null ? "BOOT" : loader.toString());
        } catch (RuntimeException error) {
            return "UNOBSERVED: " + error;
        }
    }

    /** Actor-local accounting, including leases acquired before a Handler post can complete. */
    private static final class ResourceInventory {
        private final Map<OwnedSurface, Boolean> owned = new IdentityHashMap<>();
        private final Set<Long> activeCallbacks = new HashSet<>();
        private final Set<Long> pendingSweeps = new HashSet<>();
        private long nextCallback;
        private long revision;
        private boolean releaseRequested;
        private boolean unconfirmed;
        synchronized long openCallback() {
            long id = ++nextCallback;
            activeCallbacks.add(id);
            changed("callback_open");
            return id;
        }
        synchronized OwnedSurface acquire(long callback, int id, Surface surface) {
            OwnedSurface lease = new OwnedSurface(id, surface, callback, this);
            owned.put(lease, false);
            changed("surface_acquired");
            return lease;
        }
        synchronized void attach(OwnedSurface lease, SyntheticStream stream) {
            lease.stream = stream;
            changed("stream_handoff");
        }
        synchronized void retire(long callback) {
            releaseRequested = true;
            activeCallbacks.remove(callback);
            pendingSweeps.add(callback);
            changed("callback_retired");
        }
        synchronized List<OwnedSurface> snapshot(long callback) {
            List<OwnedSurface> result = new ArrayList<>();
            for (OwnedSurface lease : owned.keySet())
                if (lease.callback == callback) result.add(lease);
            return result;
        }
        void dispose(OwnedSurface lease, String stage) {
            SyntheticStream stream;
            synchronized (this) { stream = lease.stream; }
            if (stream != null) {
                stream.close();
                complete(lease, stream.cleanupComplete());
            } else {
                complete(lease, lease.release(stage));
            }
        }
        synchronized void complete(OwnedSurface lease, boolean clean) {
            if (!owned.containsKey(lease)) return; // Already disposed by the sweep/retired path.
            if (clean) owned.remove(lease);
            else owned.put(lease, true);
            changed(clean ? "surface_disposed" : "surface_unconfirmed");
        }
        synchronized void sweepComplete(long callback) {
            pendingSweeps.remove(callback);
            changed("sweep_complete");
        }
        synchronized void unconfirmed(String reason) {
            unconfirmed = true;
            changed(reason);
        }
        private void changed(String reason) { revision++; report(reason); }
        synchronized void report(String reason) {
            int failed = unconfirmed ? 1 : 0;
            for (boolean value : owned.values()) if (value) failed++;
            int pending = 0;
            for (boolean value : owned.values()) if (!value) pending++;
            boolean clear = releaseRequested && activeCallbacks.isEmpty()
                    && pendingSweeps.isEmpty() && owned.isEmpty() && failed == 0;
            Object[] state = {"revision", revision, "inventoryId", System.identityHashCode(this),
                    "ownerPid", Process.myPid(), "activeCallbacks", activeCallbacks.size(),
                    "sweepPending", pendingSweeps.size(), "ownedCount", owned.size(),
                    "pendingCount", pending, "unconfirmedCount", failed, "knownClear", clear,
                    "scope", "KNOWN_AT_OBSERVATION", "reason", reason};
            event("producer_resources_state", state);
            if (clear) event("producer_resources_released", state);
            else if (releaseRequested) event("producer_resources_release_unconfirmed", state);
        }
    }

    /** One release owner per acquired callback lease; inventory enrollment precedes posting. */
    static final class OwnedSurface {
        private final int id;
        private final Surface surface;
        private final long callback;
        private final ResourceInventory inventory;
        private SyntheticStream stream; // Attached on renderer, read under inventory ownership.
        private boolean attempted;
        private boolean released;
        OwnedSurface(int id, Surface surface, long callback, ResourceInventory inventory) {
            this.id = id; this.surface = surface; this.callback = callback; this.inventory = inventory;
        }
        Surface surface() { return surface; }
        void finished(boolean clean) { inventory.complete(this, clean); }
        synchronized boolean release(String stage) {
            if (attempted) return released;
            attempted = true;
            if (surface == null) {
                event("surface_cleanup", "streamId", id, "stage", stage,
                        "surfacePresent", false, "outcome", "ABSENT_NOOP");
                released = true;
                return true;
            }
            try {
                surface.release();
                released = true;
                return true;
            } catch (RuntimeException error) {
                failure("producer_cleanup_error", stage, id, error);
                return false;
            }
        }
    }

    private final class ProducerCallback extends IVirtualCameraCallback.Stub {
        private volatile boolean active = true;
        private final long callbackId = resources.openCallback();
        // Each registration owns a separate callback/map; stream IDs can repeat on reopen.
        private final Map<Integer, SyntheticStream> streams = new HashMap<>();
        private void transaction(String phase, int code, Parcel data, int flags,
                Boolean result, Throwable primary) {
            try {
                event("callback_transaction", "phase", phase,
                        "attachedDescriptor", IVirtualCameraCallback.DESCRIPTOR,
                        "callbackId", callbackId, "ownerPid", Process.myPid(),
                        "code", code, "flags", flags,
                        "dataSize", data.dataSize(), "dataPosition", data.dataPosition(),
                        "dataAvail", data.dataAvail(), "result", result,
                        "primaryExceptionClass", primary == null ? null : primary.getClass().getName());
            } catch (Throwable diagnosticFailure) {
                // Observation must not replace the original Binder result or exception.
            }
        }
        @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            transaction("before", code, data, flags, null, null);
            try {
                boolean result = super.onTransact(code, data, reply, flags);
                transaction("after", code, data, flags, result, null);
                return result;
            } catch (RemoteException | RuntimeException | Error primary) {
                transaction("after", code, data, flags, null, primary);
                throw primary;
            }
        }
        private void entry(String operation, int id, Object... details) {
            Object[] fields = new Object[16 + details.length];
            Object[] base = {"operation", operation, "streamId", id,
                    "callbackId", callbackId,
                    "binderCallingUid", Binder.getCallingUid(), "binderCallingPid", Binder.getCallingPid(),
                    "sourceClassOrigin", classOrigin(CameraAccessMain.class),
                    "stubClassOrigin", classOrigin(IVirtualCameraCallback.Stub.class),
                    "surfaceClassOrigin", classOrigin(Surface.class)};
            System.arraycopy(base, 0, fields, 0, base.length);
            System.arraycopy(details, 0, fields, base.length, details.length);
            event("callback_entry", fields); // Capture calling identity before Handler handoff.
        }
        private void closeStream(SyntheticStream stream) {
            resources.dispose(stream.ownedSurface(), "stream_close");
        }
        @Override public void onStreamConfigured(int id, Surface surface, int width, int height, int format) {
            OwnedSurface owned = resources.acquire(callbackId, id, surface);
            Object valid = null;
            if (surface != null) {
                try { valid = surface.isValid(); }
                catch (RuntimeException error) { valid = "UNOBSERVED: " + error; }
            }
            entry("onStreamConfigured", id, "width", width, "height", height, "format", format,
                    "surfacePresent", surface != null, "surfaceValid", valid);
            boolean accepted;
            try {
                accepted = renderer.post(() -> {
                    if (!active) {
                        resources.dispose(owned, "retired_callback");
                        event("callback_retired", "streamId", id);
                        return;
                    }
                    SyntheticStream stream = null;
                    try {
                        SyntheticStream previous = streams.remove(id);
                        if (previous != null) closeStream(previous);
                        stream = new SyntheticStream(id, owned, width, height, renderer);
                        resources.attach(owned, stream);
                        stream.start();
                        streams.put(id, stream); // Publish only a successfully started stream.
                        event("stream_configured", "streamId", id, "width", width,
                                "height", height, "format", format);
                    } catch (RuntimeException error) {
                        failure("producer_error", "onStreamConfigured", id, error);
                        if (stream != null) {
                            if (streams.get(id) == stream) streams.remove(id);
                            closeStream(stream);
                        } else {
                            resources.dispose(owned, "configure_before_handoff");
                        }
                        event("stream_configuration_failed", "streamId", id);
                    }
                });
            } catch (RuntimeException error) {
                failure("producer_error", "callback_post", id, error);
                accepted = false;
            }
            if (!accepted) {
                resources.dispose(owned, "rejected_callback");
                event("callback_post_rejected", "streamId", id, "operation", "onStreamConfigured");
            }
        }
        @Override public void onProcessCaptureRequest(int id, int frameId) {
            entry("onProcessCaptureRequest", id, "frameId", frameId);
            if (!renderer.post(() -> {
                SyntheticStream stream = streams.get(id);
                if (stream != null) stream.captureRequested(frameId);
            })) event("callback_post_rejected", "streamId", id, "operation", "onProcessCaptureRequest");
        }
        @Override public void onStreamClosed(int id) {
            entry("onStreamClosed", id);
            if (!renderer.post(() -> {
                SyntheticStream stream = streams.remove(id);
                if (stream != null) closeStream(stream);
                event("stream_closed", "streamId", id);
            })) {
                resources.unconfirmed("onStreamClosed_post");
            }
        }
        void release() {
            active = false;
            resources.retire(callbackId);
            if (!renderer.post(() -> {
                // The inventory also contains constructed/pre-post/queued-not-mapped leases.
                for (OwnedSurface lease : resources.snapshot(callbackId))
                    resources.dispose(lease, "release_sweep");
                streams.clear();
                resources.sweepComplete(callbackId);
            })) resources.unconfirmed("release_post");
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
                        resources.report("status");
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
