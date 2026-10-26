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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Targeted213 checks: full producer bodies, real controlled JVM lanes, Android type doubles only. */
public final class InventoryScenarios {
    private static final long TEST_WAIT_MILLIS = 5000;
    private static final List<Thread> THREADS = new ArrayList<>();
    private static int passed;
    private interface Action { void run() throws Exception; }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void scenario(String name, Action action) throws Exception {
        Handler.beforePostForTest = null;
        action.run();
        for (Thread thread : THREADS) check(!thread.isAlive(), "Unjoined fixture worker: " + thread.getName());
        passed++;
        System.out.println("PASS " + name + "; all controlled workers joined");
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static Object invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true);
        try { return method.invoke(target); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            if (error.getCause() instanceof Error) throw (Error) error.getCause();
            throw new AssertionError(error.getCause());
        }
    }
    private static List<Map<String, Object>> events(String name) {
        List<Map<String, Object>> found = new ArrayList<>();
        for (Map<String, Object> row : JSONObject.events()) if (name.equals(row.get("event"))) found.add(row);
        return found;
    }
    private static long number(Map<String, Object> row, String name) {
        check(row.get(name) instanceof Number, "Missing numeric inventory field: " + name);
        return ((Number) row.get(name)).longValue();
    }
    private static boolean legacyAnyPositive() { return !events("producer_resources_released").isEmpty(); }
    private static boolean hasError(String event, String stage, String marker) {
        for (Map<String, Object> row : events(event))
            if (stage.equals(row.get("stage")) && String.valueOf(row.get("error")).contains(marker)) return true;
        return false;
    }
    private static int errorIndex(String event, String marker) {
        List<Map<String, Object>> rows = JSONObject.events();
        for (int i = 0; i < rows.size(); i++)
            if (event.equals(rows.get(i).get("event"))
                    && String.valueOf(rows.get(i).get("error")).contains(marker)) return i;
        return -1;
    }

    /** Test-only producer-event selector, not an implementation/change of the private173 caller. */
    private static Map<String, Object> latestState() {
        List<Map<String, Object>> states = events("producer_resources_state");
        check(!states.isEmpty(), "No actor resource-state events");
        Map<String, Object> latest = null;
        Object inventory = states.get(0).get("inventoryId"), owner = states.get(0).get("ownerPid");
        long previous = -1;
        for (Map<String, Object> state : states) {
            check(inventory != null && inventory.equals(state.get("inventoryId"))
                    && owner != null && owner.equals(state.get("ownerPid")), "Actor inventory identity changed");
            long revision = number(state, "revision");
            check(revision >= previous, "Resource event revisions moved backwards"); previous = revision;
            check("KNOWN_AT_OBSERVATION".equals(state.get("scope")), "Quiescence/future scope invented");
            for (String key : new String[]{"activeCallbacks", "sweepPending", "ownedCount", "pendingCount", "unconfirmedCount"})
                check(number(state, key) >= 0, "Negative resource accounting");
            if (Boolean.TRUE.equals(state.get("knownClear")))
                for (String key : new String[]{"activeCallbacks", "sweepPending", "ownedCount", "pendingCount", "unconfirmedCount"})
                    check(number(state, key) == 0, "Known-clear contradicts known duty: " + key);
            if (latest == null || revision >= number(latest, "revision")) latest = state;
        }
        return latest;
    }
    private static boolean latestKnownClear() { return Boolean.TRUE.equals(latestState().get("knownClear")); }
    private static void expectPending(long priorRevision) {
        Map<String, Object> latest = latestState();
        check(number(latest, "revision") > priorRevision && !latestKnownClear(), "Older clear remained latest after acquisition");
        check(number(latest, "ownedCount") >= 1 && number(latest, "pendingCount") >= 1,
                "Pre-post/queued-not-mapped Surface missing from inventory");
    }
    private static void expectFailed() {
        check(!latestKnownClear() && number(latestState(), "unconfirmedCount") >= 1,
                "Known failed cleanup was reported clear");
    }

    private static final class Service extends IVirtualCameraService.Stub {
        final IBinder unrelated = new Binder();
        final Map<IBinder, String> cameras = new IdentityHashMap<>();
        final List<IBinder> producerCalls = new ArrayList<>();
        VirtualCameraConfiguration config;
        Service() { cameras.put(unrelated, "UNRELATED_DOUBLE_CAMERA"); }
        @Override public boolean registerCamera(IBinder token, VirtualCameraConfiguration value, int deviceId) {
            check(deviceId == 0, "Producer route changed"); producerCalls.add(token); config = value;
            cameras.put(token, "SAME_DOUBLE_TOKEN_CAMERA"); return true;
        }
        @Override public String getCameraId(IBinder token) { producerCalls.add(token); return cameras.getOrDefault(token, ""); }
        @Override public void unregisterCamera(IBinder token) { producerCalls.add(token); cameras.remove(token); }
    }
    private static final class Rig {
        final Service service = new Service();
        final CameraAccessMain main;
        final IVirtualCameraCallback callback;
        final Looper queue;
        final IBinder token;
        Rig() throws Exception {
            JSONObject.reset(); EGL14.reset(); Handler.beforePostForTest = null;
            Constructor<CameraAccessMain> constructor = CameraAccessMain.class.getDeclaredConstructor(IVirtualCameraService.class);
            constructor.setAccessible(true); main = constructor.newInstance(service);
            queue = ((Handler) field(main, "renderer")).getLooper();
            token = (IBinder) field(main, "token"); invoke(main, "register");
            callback = service.config.virtualCameraCallback;
        }
        void configure(Surface surface) { callback.onStreamConfigured(19, surface, 640, 480, 1); }
        void unregister() throws Exception {
            check(Boolean.TRUE.equals(invoke(main, "unregister")), "Original token unregister did not return true");
            queue.drain();
        }
        void sameToken() {
            check(service.producerCalls.stream().allMatch(candidate -> candidate == token), "New/unrelated Binder token used");
            check(service.cameras.containsKey(service.unrelated), "Unrelated service token changed");
            check(!service.cameras.containsKey(token), "Owned service token not removed");
        }
    }

    private static Thread worker(String name, Action action, AtomicReference<Throwable> failure,
            CountDownLatch done) {
        Thread thread = new Thread(() -> {
            try { action.run(); }
            catch (Throwable error) { failure.set(error); }
            finally { done.countDown(); }
        }, "inventory213-" + name);
        THREADS.add(thread); return thread;
    }
    private static void await(CountDownLatch latch, String description) throws Exception {
        check(latch.await(TEST_WAIT_MILLIS, TimeUnit.MILLISECONDS), description);
    }
    private static void join(Thread thread) throws Exception {
        if (thread.getState() == Thread.State.NEW) return;
        thread.join(TEST_WAIT_MILLIS);
        if (thread.isAlive()) {
            // Only a fixture-owned worker is interrupted; never an Android actor.
            thread.interrupt();
            thread.join(TEST_WAIT_MILLIS);
        }
        check(!thread.isAlive(), "Controlled fixture worker not joined: " + thread.getName());
    }
    private static void finishWorkers(Throwable primary, CountDownLatch release, Thread... workers) throws Exception {
        release.countDown();
        Throwable failure = null;
        try {
            for (Thread worker : workers) {
                try { join(worker); }
                catch (Exception | Error error) {
                    if (failure == null) failure = error; else failure.addSuppressed(error);
                }
            }
        } finally { Handler.beforePostForTest = null; }
        if (failure == null) return;
        if (primary != null) { primary.addSuppressed(failure); return; }
        if (failure instanceof Exception) throw (Exception) failure;
        throw (Error) failure;
    }

    /** The controller never blocks in unregister: it can always release both worker lanes in finally. */
    private static void beforePostSweep(boolean current, boolean failRelease) throws Exception {
        Rig rig = new Rig(); Surface surface = new Surface();
        if (failRelease) surface.releaseFailure = new IllegalStateException("PRE_POST_RELEASE_FAILURE213");
        CountDownLatch reachedPost = new CountDownLatch(1), releasePost = new CountDownLatch(1);
        CountDownLatch callbackDone = new CountDownLatch(1), commandDone = new CountDownLatch(1);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>(), commandFailure = new AtomicReference<>();
        Thread callback = worker("callback-pre-post", () -> rig.configure(surface), callbackFailure, callbackDone);
        Thread commands = worker("command-sweep", rig::unregister, commandFailure, commandDone);
        AtomicBoolean pause = new AtomicBoolean(true);
        Handler.beforePostForTest = (handler, task) -> {
            if (Thread.currentThread() == callback && handler.getLooper() == rig.queue && pause.getAndSet(false)) {
                reachedPost.countDown();
                try { releasePost.await(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            }
        };
        Throwable primary = null;
        try {
            callback.start(); await(reachedPost, "Callback did not reach pre-enqueue handoff");
            check(surface.releases == 0 && rig.queue.immediateCount() == 0, "Hook did not precede enqueue/release");
            if (current) expectPending(-1);
            commands.start(); await(commandDone, "Unregister/sweep blocked on the paused callback lane");
            check(commandFailure.get() == null, "Command/sweep failed: " + commandFailure.get());
            if (current) {
                check(surface.releases == 1, "Sweep ignored already-owned pre-enqueue Surface");
                if (failRelease) expectFailed();
                else check(latestKnownClear(), "Successful sweep did not clear currently known ownership");
            } else {
                check(surface.releases == 0 && legacyAnyPositive(), "Parent premature positive not reproduced");
            }
        } catch (Exception | Error error) { primary = error; throw error; }
        finally { finishWorkers(primary, releasePost, callback, commands); }
        check(callbackFailure.get() == null && callbackDone.getCount() == 0, "Callback lane failed");
        rig.queue.drain();
        check(surface.releases == 1, "Original Surface release was missing or repeated");
        check(events("stream_configured").isEmpty(), "Retired callback started a stream");
        if (current) {
            if (failRelease) expectFailed(); else check(latestKnownClear(), "Successful retired completion lost clear state");
        } else if (failRelease) {
            check(!events("producer_cleanup_error").isEmpty() && legacyAnyPositive()
                    && events("producer_resources_release_unconfirmed").isEmpty(), "Parent late failure/old-positive mismatch not reproduced");
        }
        rig.sameToken();
    }

    private static void lateAfterSweep(boolean current, boolean reject, boolean failRelease) throws Exception {
        Rig rig = new Rig(); rig.unregister();
        check(legacyAnyPositive(), "Initial empty sweep did not emit historical positive");
        long priorRevision = current ? number(latestState(), "revision") : -1;
        if (current) check(latestKnownClear(), "Initial known-state sweep not clear");
        Surface surface = new Surface();
        if (failRelease) surface.releaseFailure = new IllegalStateException("LATE_RELEASE_FAILURE213");
        rig.queue.acceptsPosts = !reject;
        CountDownLatch reachedPost = new CountDownLatch(1), releasePost = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread callback = worker("late-callback", () -> rig.configure(surface), failure, done);
        AtomicBoolean pause = new AtomicBoolean(true);
        Handler.beforePostForTest = (handler, task) -> {
            if (Thread.currentThread() == callback && handler.getLooper() == rig.queue && pause.getAndSet(false)) {
                reachedPost.countDown();
                try { releasePost.await(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            }
        };
        Throwable primary = null;
        try {
            callback.start(); await(reachedPost, "Late callback did not reach pre-enqueue handoff");
            check(surface.releases == 0 && legacyAnyPositive(), "Late acquisition not observed before disposal");
            if (current) {
                expectPending(priorRevision);
                check(!latestKnownClear() && legacyAnyPositive(),
                        "Producer event selector consumed old positive instead of newer pending state");
            }
        } catch (Exception | Error error) { primary = error; throw error; }
        finally {
            try { finishWorkers(primary, releasePost, callback); }
            finally { rig.queue.acceptsPosts = true; }
        }
        check(done.getCount() == 0 && failure.get() == null, "Late callback lane failed: " + failure.get());
        rig.queue.drain();
        check(surface.releases == 1 && events("stream_configured").isEmpty(), "Late disposal ownership wrong");
        if (current) {
            check(number(latestState(), "revision") > priorRevision, "Late transition has no newer revision");
            if (failRelease) {
                expectFailed();
                check(legacyAnyPositive() && !latestKnownClear(), "New cleanup error left older positive consumable");
            } else check(latestKnownClear(), "Successful late disposal not reflected in current known state");
        } else {
            check(failRelease && !events("producer_cleanup_error").isEmpty()
                    && legacyAnyPositive() && events("producer_resources_release_unconfirmed").isEmpty(),
                    "Parent serial late-failure false-positive not reproduced");
        }
        rig.sameToken();
    }

    private static void currentConfiguredSuccess() throws Exception {
        Rig rig = new Rig(); Surface surface = new Surface();
        rig.configure(surface);
        expectPending(-1);
        check(events("stream_configured").isEmpty() && rig.queue.immediateCount() == 1,
                "Configured control did not cross the actual queue seam");
        rig.queue.drain();
        check(events("stream_configured").size() == 1 && surface.releases == 0,
                "Successful configured stream lost its live Surface");
        rig.callback.onProcessCaptureRequest(19, 61); invoke(rig.callback, "report"); rig.queue.drain();
        Map<String, Object> progress = events("producer_progress").get(events("producer_progress").size() - 1);
        check(number(progress, "captureRequests") == 1 && number(progress, "lastRequest") == 61,
                "Actual configured stream did not receive capture request");
        check(rig.queue.delayedCount() == 1 && rig.queue.delayedOnce(), "Controlled next frame missing");
        rig.callback.onStreamClosed(19); rig.queue.drain();
        check(surface.releases == 1 && rig.queue.delayedCount() == 0,
                "Original successful stream close did not cancel/release once");
        check(!latestKnownClear(), "Active registration was reported globally clear before retirement");
        rig.unregister();
        check(surface.releases == 1 && latestKnownClear(), "Normal final sweep did not preserve one release/known clear");
        rig.sameToken();
    }

    private static void currentFirstAndCleanupFaults() throws Exception {
        Rig rig = new Rig(); Surface surface = new Surface();
        surface.releaseFailure = new IllegalStateException("SURFACE_CLEANUP_SECONDARY213");
        EGL14.faults.put("eglInitialize", new IllegalArgumentException("FIRST_INIT_PRIMARY213"));
        EGL14.faults.put("eglTerminate", new IllegalStateException("EGL_CLEANUP_SECONDARY213"));
        rig.configure(surface); expectPending(-1); rig.queue.drain();
        check(hasError("producer_error", "start.initialize", "FIRST_INIT_PRIMARY213"), "First initialization error overwritten");
        check(hasError("producer_cleanup_error", "close.terminate", "EGL_CLEANUP_SECONDARY213")
                && hasError("producer_cleanup_error", "stream_close", "SURFACE_CLEANUP_SECONDARY213"),
                "Independent cleanup fault provenance missing");
        int primary = errorIndex("producer_error", "FIRST_INIT_PRIMARY213");
        check(primary >= 0 && primary < errorIndex("producer_cleanup_error", "EGL_CLEANUP_SECONDARY213")
                && primary < errorIndex("producer_cleanup_error", "SURFACE_CLEANUP_SECONDARY213"),
                "Cleanup diagnostics preceded original initialization error");
        check(surface.releases == 1 && events("stream_configured").isEmpty()
                && events("stream_configuration_failed").size() == 1, "Failed initialization lifecycle wrong");
        expectFailed();
        boolean[] later = {false}; rig.queue.postForTest(() -> later[0] = true); rig.queue.drain();
        check(later[0], "Contained failure prevented later queue work");
        rig.unregister(); expectFailed();
        check(surface.releases == 1 && !legacyAnyPositive(), "Sweep retried failed original or invented release");
        rig.sameToken();
    }

    private static void currentQueuedNotMappedFailure() throws Exception {
        Rig rig = new Rig(); Surface surface = new Surface();
        surface.releaseFailure = new IllegalStateException("QUEUED_RELEASE_FAILURE213");
        rig.configure(surface);
        check(rig.queue.immediateCount() == 1 && events("stream_configured").isEmpty(), "Callback not queued before map insertion");
        expectPending(-1);
        rig.unregister();
        check(surface.releases == 1 && events("stream_configured").isEmpty(), "Queued retirement started/released wrong stream");
        expectFailed();
        check(!legacyAnyPositive(), "Queued-not-mapped cleanup fault escaped inventory");
        rig.sameToken();
    }

    private static void currentWholeStreamCleanup() throws Exception {
        Rig rig = new Rig(); Surface surface = new Surface(); rig.configure(surface); rig.queue.drain();
        check(events("stream_configured").size() == 1, "Targeted configured-stream control not established");
        EGL14.faults.put("eglDestroySurface", new IllegalStateException("WHOLE_EGL_CLEANUP_FAILURE213"));
        rig.unregister();
        check(surface.releases == 1, "Native Surface disposal missing");
        expectFailed();
        check(!legacyAnyPositive(), "Successful Surface.release concealed failed whole-stream EGL cleanup");
        rig.sameToken();
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1, "Expected parent or current mode");
        if (args[0].equals("parent")) {
            scenario("parent201 pre-post Surface missed by successful sweep", () -> beforePostSweep(false, false));
            scenario("parent201 late failed release leaves premature aggregate positive", () -> beforePostSweep(false, true));
            scenario("parent201 after-first-sweep late retired failure leaves old positive", () -> lateAfterSweep(false, false, true));
        } else if (args[0].equals("current")) {
            scenario("pre-post Surface is owned and swept before enqueue, success", () -> beforePostSweep(true, false));
            scenario("pre-post Surface failure remains known-unconfirmed", () -> beforePostSweep(true, true));
            scenario("late retired callback publishes pending then successful release", () -> lateAfterSweep(true, false, false));
            scenario("late retired failure supersedes older positive", () -> lateAfterSweep(true, false, true));
            scenario("late rejected post publishes pending then successful release", () -> lateAfterSweep(true, true, false));
            scenario("late rejected release failure supersedes older positive", () -> lateAfterSweep(true, true, true));
            scenario("normal configured stream processes then closes original resources", InventoryScenarios::currentConfiguredSuccess);
            scenario("first initialization fault precedes independent cleanup failures", InventoryScenarios::currentFirstAndCleanupFaults);
            scenario("queued-not-mapped cleanup failure remains owned", InventoryScenarios::currentQueuedNotMappedFailure);
            scenario("whole-stream cleanup failure remains after Surface disposal", InventoryScenarios::currentWholeStreamCleanup);
        } else throw new AssertionError("Unknown fixture mode");
        for (Thread thread : THREADS) check(!thread.isAlive(), "Remaining worker at fixture exit");
        System.out.println("RESULT " + args[0] + " " + passed + " targeted inventory scenarios; "
                + THREADS.size() + " real controlled JVM workers joined; TYPE DOUBLES, no Android/wire/quiescence proof");
    }
}
