package com.code2hack.eyeprojector.cameraaccess;

import android.companion.virtualcamera.IVirtualCameraCallback;
import android.companion.virtualcamera.IVirtualCameraService;
import android.companion.virtualcamera.VirtualCameraConfiguration;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.opengl.EGL14;
import android.view.Surface;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Full production classes run unchanged. Android/graphics/wire behavior is NOT implemented. */
public final class ProducerScenarios {
    private static int passed;
    private interface Scenario { void run() throws Exception; }
    private static void scenario(String name, Scenario scenario) throws Exception {
        scenario.run(); passed++; System.out.println("PASS " + name);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static Object invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true);
        try { return method.invoke(target); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
    }
    private static List<Map<String, Object>> events(String name) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> event : JSONObject.events())
            if (name.equals(event.get("event"))) result.add(event);
        return result;
    }
    private static Map<String, Object> last(String name) {
        List<Map<String, Object>> rows = events(name);
        check(!rows.isEmpty(), "Missing event: " + name); return rows.get(rows.size() - 1);
    }
    private static long number(Map<String, Object> row, String name) {
        return ((Number) row.get(name)).longValue();
    }
    private static boolean text(Map<String, Object> row, String field, String value) {
        return String.valueOf(row.get(field)).contains(value);
    }
    private static boolean has(String event, String stage, String error) {
        for (Map<String, Object> row : events(event))
            if (stage.equals(row.get("stage")) && text(row, "error", error)) return true;
        return false;
    }
    private static int indexOf(String event, String marker) {
        List<Map<String, Object>> all = JSONObject.events();
        for (int i = 0; i < all.size(); i++)
            if (event.equals(all.get(i).get("event")) && text(all.get(i), "error", marker)) return i;
        return -1;
    }

    private static final class FakeService extends IVirtualCameraService.Stub {
        final IBinder unrelatedToken = new Binder();
        final Map<IBinder, String> cameras = new IdentityHashMap<>();
        final List<IBinder> registerTokens = new ArrayList<>();
        final List<IBinder> lookupTokens = new ArrayList<>();
        final List<IBinder> unregisterTokens = new ArrayList<>();
        VirtualCameraConfiguration configuration;
        RuntimeException unregisterOnce;
        FakeService() { cameras.put(unrelatedToken, "UNRELATED_TYPE_DOUBLE_CAMERA"); }
        @Override public boolean registerCamera(IBinder token, VirtualCameraConfiguration value, int deviceId) {
            registerTokens.add(token); configuration = value;
            check(deviceId == 0, "Production route changed");
            check(value.supportedStreamConfigs.length == 1, "Input count changed");
            check(value.supportedStreamConfigs[0].width == 640 && value.supportedStreamConfigs[0].height == 480
                    && value.supportedStreamConfigs[0].pixelFormat == 1
                    && value.supportedStreamConfigs[0].maxFps == 30, "Input contract changed");
            cameras.put(token, "TYPE_DOUBLE_OWNED_CAMERA"); return true;
        }
        @Override public String getCameraId(IBinder token) {
            lookupTokens.add(token); return cameras.getOrDefault(token, "");
        }
        @Override public void unregisterCamera(IBinder token) {
            unregisterTokens.add(token);
            if (unregisterOnce != null) {
                RuntimeException error = unregisterOnce; unregisterOnce = null; throw error;
            }
            cameras.remove(token);
        }
    }

    private static final class Rig {
        final FakeService service;
        final CameraAccessMain main;
        final IVirtualCameraCallback callback;
        final Looper queue;
        final IBinder token;
        Rig() throws Exception {
            JSONObject.reset(); EGL14.reset(); Binder.setCallingIdentity(1000, 41001);
            service = new FakeService();
            Constructor<CameraAccessMain> constructor = CameraAccessMain.class.getDeclaredConstructor(IVirtualCameraService.class);
            constructor.setAccessible(true); main = constructor.newInstance(service);
            queue = ((Handler) field(main, "renderer")).getLooper();
            token = (IBinder) field(main, "token");
            invoke(main, "register"); callback = service.configuration.virtualCameraCallback;
        }
        void configure(Surface surface) { callback.onStreamConfigured(7, surface, 640, 480, 1); }
        void closeStream() { callback.onStreamClosed(7); queue.drain(); }
        void unregister() throws Exception { check(Boolean.TRUE.equals(invoke(main, "unregister")), "Service cleanup did not return true"); queue.drain(); }
        void control(String commands) throws Exception {
            InputStream prior = System.in;
            try {
                System.setIn(new ByteArrayInputStream(commands.getBytes(StandardCharsets.UTF_8)));
                invoke(main, "run");
            } finally { System.setIn(prior); }
            queue.drain();
        }
        void sameToken() {
            check(service.registerTokens.size() == 1 && service.registerTokens.get(0) == token, "Fresh/replacement token used");
            check(service.lookupTokens.stream().allMatch(candidate -> candidate == token), "Lookup used another token");
            check(service.unregisterTokens.stream().allMatch(candidate -> candidate == token), "Cleanup used another token");
            check(service.cameras.containsKey(service.unrelatedToken), "Unrelated camera changed");
        }
        void finishControl() throws Exception {
            control("status\nunregister\nstatus\nquit\n"); sameToken();
            List<Map<String, Object>> statuses = events("status");
            check(statuses.size() == 2 && Boolean.TRUE.equals(statuses.get(0).get("cleanupRequired"))
                    && Boolean.FALSE.equals(statuses.get(1).get("cleanupRequired")), "Actual status/run path changed");
            check(!service.cameras.containsKey(token), "Owned token still in fake service");
        }
    }

    private static void parentCases() throws Exception {
        scenario("parent exact null callback masks first window exception and escapes Handler task", () -> {
            Rig r = new Rig(); RuntimeException primary = new IllegalArgumentException("FIRST_WINDOW_PRIMARY201");
            EGL14.faults.put("eglCreateWindowSurface", primary); r.configure(null);
            RuntimeException escaped = null;
            try { r.queue.drain(); } catch (RuntimeException error) { escaped = error; }
            check(escaped instanceof NullPointerException && escaped != primary, "Parent counterexample not reproduced");
            check(events("producer_error").isEmpty(), "Parent unexpectedly emitted original error");
            check(events("stream_configured").isEmpty(), "Parent advertised failed stream");
            check(r.service.cameras.containsKey(r.token), "Model lost original registered token");
            // Catching an escaped task here is a test observation, not Android process survival.
        });
        scenario("parent normal protocol uses original token and preserves unrelated token", () -> {
            Rig r = new Rig(); r.finishControl();
        });
    }

    private static void currentCases() throws Exception {
        scenario("null callback preserves natural first failure and actor command path", () -> {
            Rig r = new Rig(); r.configure(null); r.queue.drain();
            check(has("producer_error", "start.window", "DOUBLE_NULL_NATIVE_WINDOW"), "Natural null primary not retained");
            check(text(last("producer_error"), "stack", "IllegalArgumentException"), "Primary stack missing");
            check(events("stream_configuration_failed").size() == 1 && events("stream_configured").isEmpty(), "Null stream success fabricated");
            check(EGL14.calls.getOrDefault("eglCreateWindowSurface", 0) == 1, "Null was intercepted by a new input gate");
            check("ABSENT_NOOP".equals(last("surface_cleanup").get("outcome")), "Missing surface cleanup not explicit");
            r.finishControl();
        });
        scenario("same injected null counterexample retains first exception before cleanup", () -> {
            Rig r = new Rig(); EGL14.faults.put("eglCreateWindowSurface", new IllegalArgumentException("FIRST_WINDOW_PRIMARY201"));
            r.configure(null); r.queue.drain();
            check(has("producer_error", "start.window", "FIRST_WINDOW_PRIMARY201"), "Injected first error lost");
            check(events("stream_configured").isEmpty(), "Failed callback published"); r.finishControl();
        });
        scenario("nonnull invalid surface fails naturally then releases exactly once", () -> {
            Rig r = new Rig(); Surface surface = new Surface(); surface.valid = false;
            r.configure(surface); r.queue.drain();
            check(has("producer_error", "start.window", "DOUBLE_INVALID_NATIVE_WINDOW"), "Invalid window fault not retained");
            check(surface.releases == 1, "Invalid Surface release duplicated"); r.finishControl();
        });
        String[] methods = {"eglGetDisplay", "eglInitialize", "eglChooseConfig", "eglCreateContext", "eglCreateWindowSurface"};
        String[] stages = {"getDisplay", "initialize", "config", "context", "window"};
        for (int i = 0; i < methods.length; i++) {
            final String operation = methods[i], stage = stages[i];
            scenario("first start fault at " + operation + " is retained with one release", () -> {
                Rig r = new Rig(); Surface surface = new Surface();
                EGL14.faults.put(operation, new IllegalStateException("FIRST_" + operation));
                r.configure(surface); r.queue.drain();
                check(has("producer_error", "start." + stage, "FIRST_" + operation), "First stage fault lost");
                check(surface.releases == 1 && events("stream_configured").isEmpty(), "Partial start ownership wrong");
                r.finishControl();
            });
        }
        scenario("boolean initialization failure uses actual production require path", () -> {
            Rig r = new Rig(); Surface surface = new Surface(); EGL14.falseResults.add("eglInitialize");
            r.configure(surface); r.queue.drain();
            check(has("producer_error", "start.initialize", "initialize EGL error"), "False-return failure not retained");
            check(surface.releases == 1, "False-return release wrong"); r.finishControl();
        });
        scenario("first initialization error survives EGL and Surface cleanup exceptions", () -> {
            Rig r = new Rig(); Surface surface = new Surface();
            surface.releaseFailure = new IllegalStateException("SURFACE_CLEANUP_SECONDARY201");
            EGL14.faults.put("eglInitialize", new IllegalArgumentException("FIRST_INIT_PRIMARY201"));
            EGL14.faults.put("eglTerminate", new IllegalStateException("EGL_CLEANUP_SECONDARY201"));
            r.configure(surface); r.queue.drain();
            check(has("producer_error", "start.initialize", "FIRST_INIT_PRIMARY201"), "Primary overwritten");
            check(indexOf("producer_error", "FIRST_INIT_PRIMARY201") < indexOf("producer_cleanup_error", "EGL_CLEANUP_SECONDARY201"), "Cleanup preceded primary diagnostic");
            check(has("producer_cleanup_error", "close.terminate", "EGL_CLEANUP_SECONDARY201")
                    && has("producer_cleanup_error", "stream_close", "SURFACE_CLEANUP_SECONDARY201"), "Independent cleanup outcomes missing");
            check(surface.releases == 1, "Throwing release retried/doubled");
            boolean[] later = {false}; r.queue.postForTest(() -> later[0] = true); r.queue.drain();
            check(later[0], "Renderer queue did not survive contained failure");
            r.finishControl();
            check(events("producer_resources_released").isEmpty()
                    && !events("producer_resources_release_unconfirmed").isEmpty(), "Failed release reported complete");
        });
        scenario("callback provenance precedes queued worker and success/process/close uses originals", () -> {
            Rig r = new Rig(); Surface surface = new Surface(); Binder.setCallingIdentity(1000, 41077);
            r.configure(surface);
            Map<String, Object> entry = last("callback_entry");
            check(number(entry, "binderCallingUid") == 1000 && number(entry, "binderCallingPid") == 41077,
                    "Calling identity observed after Handler handoff");
            check(number(entry, "width") == 640 && number(entry, "height") == 480 && number(entry, "format") == 1
                    && Boolean.TRUE.equals(entry.get("surfacePresent")), "Callback arguments lost");
            check(entry.get("sourceClassOrigin") != null && entry.get("stubClassOrigin") != null
                    && entry.get("surfaceClassOrigin") != null, "Class origin metadata missing");
            check(r.queue.immediateCount() == 1 && events("stream_configured").isEmpty(), "Handler ran inline");
            r.queue.drain(); check(events("stream_configured").size() == 1, "Valid callback failed");
            r.callback.onProcessCaptureRequest(7, 42); invoke(r.callback, "report"); r.queue.drain();
            check(number(last("producer_progress"), "captureRequests") == 1
                    && number(last("producer_progress"), "lastRequest") == 42, "Capture request not routed");
            check(r.queue.delayedCount() == 1 && r.queue.delayedOnce(), "Controlled next frame not available");
            r.closeStream(); r.closeStream();
            check(surface.releases == 1 && r.queue.delayedCount() == 0, "Close did not cancel exact original tick/release once");
            r.finishControl();
        });
        scenario("same stream ID replacement closes each original Surface once", () -> {
            Rig r = new Rig(); Surface first = new Surface(), next = new Surface();
            r.configure(first); r.queue.drain(); r.configure(next); r.queue.drain();
            check(first.releases == 1 && next.releases == 0 && events("stream_configured").size() == 2, "Replacement ownership wrong");
            r.closeStream(); r.finishControl(); check(first.releases == 1 && next.releases == 1, "Replacement double release");
        });
        scenario("replacement cleanup failure does not kill successor or fabricate final release", () -> {
            Rig r = new Rig(); Surface first = new Surface(), next = new Surface();
            r.configure(first); r.queue.drain(); first.releaseFailure = new IllegalStateException("OLD_RELEASE_UNCONFIRMED201");
            r.configure(next); r.queue.drain();
            check(first.releases == 1 && events("stream_configured").size() == 2, "Old failure killed successor");
            r.closeStream(); r.finishControl();
            check(next.releases == 1 && events("producer_resources_released").isEmpty()
                    && !events("producer_resources_release_unconfirmed").isEmpty(), "Replacement cleanup credit wrong");
        });
        scenario("first draw failure and cleanup failure retain independent error provenance", () -> {
            Rig r = new Rig(); Surface surface = new Surface();
            surface.releaseFailure = new IllegalStateException("DRAW_SURFACE_CLEANUP201");
            EGL14.faults.put("eglSwapBuffers", new IllegalArgumentException("FIRST_DRAW_PRIMARY201"));
            EGL14.faults.put("eglDestroySurface", new IllegalStateException("DRAW_EGL_CLEANUP201"));
            r.configure(surface); r.queue.drain();
            check(has("producer_error", "draw", "FIRST_DRAW_PRIMARY201")
                    && has("producer_cleanup_error", "close.window", "DRAW_EGL_CLEANUP201"), "Draw/cleanup provenance lost");
            check(surface.releases == 1 && r.queue.delayedCount() == 0, "Draw failure ownership wrong");
            r.closeStream(); r.finishControl(); check(events("producer_resources_released").isEmpty(), "Draw cleanup falsely complete");
        });
        for (boolean missing : new boolean[]{false, true}) {
            scenario("retired queued callback releases own " + (missing ? "absent" : "nonnull") + " Surface", () -> {
                Rig r = new Rig(); Surface surface = missing ? null : new Surface();
                r.configure(surface); r.unregister();
                check(events("stream_configured").isEmpty() && !events("callback_retired").isEmpty(), "Retired callback started stream");
                if (surface != null) check(surface.releases == 1, "Retired Surface not released once");
                r.sameToken();
            });
            scenario("rejected callback post releases own " + (missing ? "absent" : "nonnull") + " Surface", () -> {
                Rig r = new Rig(); Surface surface = missing ? null : new Surface(); r.queue.acceptsPosts = false;
                r.configure(surface);
                check(events("stream_configured").isEmpty() && !events("callback_post_rejected").isEmpty(), "Rejected callback started stream");
                if (surface != null) check(surface.releases == 1, "Rejected Surface not released once");
                r.queue.acceptsPosts = true; r.finishControl();
            });
        }
        scenario("throwing post preserves its error and releases input without killing control", () -> {
            Rig r = new Rig(); Surface surface = new Surface();
            r.queue.postFailure = new IllegalArgumentException("FIRST_POST_PRIMARY201"); r.configure(surface);
            check(has("producer_error", "callback_post", "FIRST_POST_PRIMARY201") && surface.releases == 1, "Post exception lost");
            r.queue.postFailure = null; r.finishControl();
        });
        scenario("rejected post plus throwing Surface release remains explicitly unconfirmed", () -> {
            Rig r = new Rig(); Surface surface = new Surface();
            surface.releaseFailure = new IllegalStateException("REJECTED_RELEASE_SECONDARY201");
            r.queue.acceptsPosts = false; r.configure(surface);
            check(surface.releases == 1
                    && has("producer_cleanup_error", "rejected_callback", "REJECTED_RELEASE_SECONDARY201"), "Rejected cleanup error lost");
            r.queue.acceptsPosts = true; r.finishControl();
            check(events("producer_resources_released").isEmpty()
                    && !events("producer_resources_release_unconfirmed").isEmpty(), "Rejected cleanup falsely complete");
        });
        scenario("validity observation error is metadata rather than a new admission gate", () -> {
            Rig r = new Rig(); Surface surface = new Surface();
            surface.validityFailure = new IllegalStateException("VALIDITY_UNOBSERVED201");
            r.configure(surface);
            check(text(last("callback_entry"), "surfaceValid", "UNOBSERVED"), "Validity diagnostic lost");
            r.queue.drain();
            check(EGL14.calls.getOrDefault("eglCreateWindowSurface", 0) == 1
                    && has("producer_error", "start.window", "VALIDITY_UNOBSERVED201"), "Diagnostic changed admission policy");
            check(surface.releases == 1, "Validity-error Surface release wrong"); r.finishControl();
        });
        scenario("rejected close callback cannot report successful resource release", () -> {
            Rig r = new Rig(); Surface surface = new Surface(); r.configure(surface); r.queue.drain();
            r.queue.acceptsPosts = false; r.callback.onStreamClosed(7); r.queue.acceptsPosts = true;
            r.finishControl();
            check(surface.releases == 1 && events("producer_resources_released").isEmpty()
                    && !events("producer_resources_release_unconfirmed").isEmpty(), "Lost close callback credited as success");
        });
        scenario("actual command loop retains same token through negative cleanup and separate quit", () -> {
            Rig r = new Rig(); invoke(r.main, "register");
            check(events("registration_pending").size() == 1 && r.service.registerTokens.size() == 1, "Registration policy changed");
            r.service.unregisterOnce = new IllegalStateException("SERVICE_CLEANUP_NEGATIVE201");
            r.control("status\nunregister\nstatus\nunregister\nstatus\nquit\n");
            r.sameToken();
            check(r.service.unregisterTokens.size() == 2 && events("cleanup_unconfirmed").size() == 1, "Negative cleanup path changed");
            List<Map<String, Object>> statuses = events("status");
            check(statuses.size() == 3 && Boolean.TRUE.equals(statuses.get(1).get("cleanupRequired"))
                    && Boolean.FALSE.equals(statuses.get(2).get("cleanupRequired")), "Original token not retained through failure");
        });
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1, "Expected parent or current mode");
        if (args[0].equals("parent")) parentCases();
        else if (args[0].equals("current")) currentCases();
        else throw new AssertionError("Unknown mode");
        System.out.println("RESULT " + args[0] + " " + passed
                + " scenarios; full production Java, TYPE DOUBLES ONLY; no Android/wire/EGL/imagery execution");
    }
}
