package com.code2hack.eyeprojector.cameraaccess;

import android.companion.virtualcamera.IVirtualCameraCallback;
import android.companion.virtualcamera.IVirtualCameraService;
import android.companion.virtualcamera.VirtualCameraConfiguration;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** Full actual producers, scalar Parcel substitutes and one controlled superclass delegation. */
public final class TransactionScenarios {
    private static int passed;
    private interface Scenario { void run() throws Exception; }
    private static void scenario(String name, Scenario body) throws Exception {
        body.run(); passed++; System.out.println("PASS " + name);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private static Object invoke(Object target, String name) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name); m.setAccessible(true);
        try { return m.invoke(target); }
        catch (InvocationTargetException wrapped) {
            Throwable primary = wrapped.getCause();
            if (primary instanceof Exception) throw (Exception) primary;
            if (primary instanceof Error) throw (Error) primary;
            throw new AssertionError(primary);
        }
    }
    private static List<Map<String, Object>> transactions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : JSONObject.events())
            if ("callback_transaction".equals(row.get("event"))) result.add(row);
        return result;
    }
    private static long number(Map<String, Object> row, String name) {
        return ((Number) row.get(name)).longValue();
    }

    private static final class Service extends IVirtualCameraService.Stub {
        final IBinder unrelated = new Binder();
        final Map<IBinder, String> cameras = new IdentityHashMap<>();
        final List<IBinder> tokens = new ArrayList<>();
        VirtualCameraConfiguration configuration;
        Service() { cameras.put(unrelated, "UNRELATED_FIXTURE_CAMERA"); }
        @Override public boolean registerCamera(IBinder token, VirtualCameraConfiguration config, int deviceId) {
            tokens.add(token); configuration = config;
            check(deviceId == 0 && config.supportedStreamConfigs.length == 1, "Registration route changed");
            check(config.supportedStreamConfigs[0].width == 640
                    && config.supportedStreamConfigs[0].height == 480
                    && config.supportedStreamConfigs[0].pixelFormat == 1
                    && config.supportedStreamConfigs[0].maxFps == 30, "Input schema changed");
            cameras.put(token, "OWNED_FIXTURE_CAMERA"); return true;
        }
        @Override public String getCameraId(IBinder token) { tokens.add(token); return cameras.getOrDefault(token, ""); }
        @Override public void unregisterCamera(IBinder token) { tokens.add(token); cameras.remove(token); }
    }
    private static final class Rig {
        final Service service;
        final CameraAccessMain main;
        final IVirtualCameraCallback.Stub callback;
        final IBinder token;
        final Looper queue;
        Rig() throws Exception {
            JSONObject.reset(); Binder.setTransactDelegate(null); Binder.setCallingIdentity(1000, 0);
            service = new Service();
            Constructor<CameraAccessMain> c = CameraAccessMain.class.getDeclaredConstructor(IVirtualCameraService.class);
            c.setAccessible(true); main = c.newInstance(service);
            queue = ((Handler) field(main, "renderer")).getLooper();
            token = (IBinder) field(main, "token");
            invoke(main, "register"); callback = (IVirtualCameraCallback.Stub) service.configuration.virtualCameraCallback;
        }
        boolean call(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            return callback.onTransact(code, data, reply, flags);
        }
        void finish() throws Exception {
            InputStream prior = System.in;
            try {
                System.setIn(new ByteArrayInputStream("status\nunregister\nstatus\nquit\n".getBytes(StandardCharsets.UTF_8)));
                invoke(main, "run"); queue.drain();
            } finally { System.setIn(prior); Binder.setTransactDelegate(null); }
            check(!service.cameras.containsKey(token) && service.cameras.containsKey(service.unrelated), "Token isolation changed");
            check(service.tokens.stream().allMatch(t -> t == token), "Replacement token used");
        }
    }
    private static final class Delegate implements Binder.TransactDelegate {
        final Rig rig;
        final int code;
        final Parcel data;
        final Parcel reply;
        final int flags;
        int calls;
        boolean result;
        Throwable primary;
        Runnable operation;
        Delegate(Rig rig, int code, Parcel data, Parcel reply, int flags) {
            this.rig = rig; this.code = code; this.data = data; this.reply = reply; this.flags = flags;
        }
        @Override public boolean invoke(Binder receiver, int gotCode, Parcel gotData, Parcel gotReply, int gotFlags)
                throws RemoteException {
            calls++;
            check(receiver == rig.callback && gotCode == code && gotData == data
                    && gotReply == reply && gotFlags == flags, "Superclass arguments/receiver changed");
            if (data != null) {
                check(data.fixtureSize == 44 && data.fixturePosition == 12, "Diagnostic changed input before delegate");
                data.fixtureSize = 52; data.fixturePosition = 28;
            }
            if (reply != null) {
                check(reply.fixtureSize == 20 && reply.fixturePosition == 4
                        && "ORIGINAL_REPLY".equals(reply.fixtureReplyState), "Diagnostic touched reply before delegate");
                reply.fixtureReplyState = "DELEGATE_REPLY"; reply.fixturePosition = 8;
            }
            if (operation != null) operation.run();
            if (primary instanceof RemoteException) throw (RemoteException) primary;
            if (primary instanceof RuntimeException) throw (RuntimeException) primary;
            if (primary instanceof Error) throw (Error) primary;
            return result;
        }
        void verify() {
            check(calls == 1, "Superclass delegation count=" + calls);
            if (data != null) check(data.fixtureSize == 52 && data.fixturePosition == 28
                    && data.forbiddenOperations == 0, "Input state or payload operation changed");
            if (reply != null) check(reply.fixtureSize == 20 && reply.fixturePosition == 8
                    && "DELEGATE_REPLY".equals(reply.fixtureReplyState)
                    && reply.forbiddenOperations == 0 && reply.scalarCalls.isEmpty(), "Reply was observed or changed");
        }
    }
    private static Parcel data() { return new Parcel(44, 12); }
    private static Parcel reply() {
        Parcel value = new Parcel(20, 4); value.fixtureReplyState = "ORIGINAL_REPLY"; return value;
    }
    private static Throwable callFailure(Rig r, Delegate d) {
        try { r.call(d.code, d.data, d.reply, d.flags); return null; }
        catch (Throwable primary) { return primary; }
    }
    private static void phase(Map<String, Object> row, Rig r, Delegate d, String phase, int size, int position) throws Exception {
        check(phase.equals(row.get("phase")), "Phase mismatch");
        check(IVirtualCameraCallback.DESCRIPTOR.equals(row.get("attachedDescriptor")), "Own descriptor missing");
        check(number(row, "callbackId") == ((Number) field(r.callback, "callbackId")).longValue(), "Callback identity wrong");
        check(number(row, "ownerPid") == 32001 && number(row, "elapsedNanos") > 0, "Own PID/timestamp missing");
        check(number(row, "code") == d.code && number(row, "flags") == d.flags, "Code or flags changed");
        check(number(row, "dataSize") == size && number(row, "dataPosition") == position
                && number(row, "dataAvail") == size - position, "Scalar snapshot mismatch");
    }

    private static void parentCases() throws Exception {
        scenario("parent213 own callback provides no transaction-node scalar evidence", () -> {
            Rig r = new Rig(); Delegate d = new Delegate(r, 4, data(), reply(), 1); d.result = false;
            Binder.setTransactDelegate(d);
            check(!r.call(d.code, d.data, d.reply, d.flags), "Parent superclass result changed"); d.verify();
            check(transactions().isEmpty(), "Exact parent unexpectedly has transaction diagnostics"); r.finish();
        });
    }
    private static void currentCases() throws Exception {
        scenario("true delegate returns actual result with untouched scalars and reply", () -> {
            Rig r = new Rig(); Delegate d = new Delegate(r, 1, data(), reply(), 0x41); d.result = true;
            Binder.setTransactDelegate(d);
            check(r.call(d.code, d.data, d.reply, d.flags), "True return replaced"); d.verify();
            List<Map<String, Object>> rows = transactions(); check(rows.size() == 2, "Before/after count wrong");
            phase(rows.get(0), r, d, "before", 44, 12); phase(rows.get(1), r, d, "after", 52, 28);
            check(rows.get(0).get("result") == null && rows.get(0).get("primaryExceptionClass") == null
                    && Boolean.TRUE.equals(rows.get(1).get("result"))
                    && rows.get(1).get("primaryExceptionClass") == null, "Return evidence fabricated");
            for (String method : new String[]{"dataSize", "dataPosition", "dataAvail"})
                check(d.data.scalarCalls.get(method) == 2, "Unexpected scalar observation count"); r.finish();
        });
        scenario("unknown code4 preserves false and unchanged code data reply flags", () -> {
            Rig r = new Rig(); Delegate d = new Delegate(r, 4, data(), reply(), 1); d.result = false;
            Binder.setTransactDelegate(d);
            check(!r.call(d.code, d.data, d.reply, d.flags), "Unknown false converted to true"); d.verify();
            check(transactions().size() == 2 && Boolean.FALSE.equals(transactions().get(1).get("result")), "False not observed");
            r.finish();
        });
        Throwable[] primaries = {new IllegalStateException("PRIMARY_RUNTIME337"),
                new RemoteException("PRIMARY_REMOTE337"), new AssertionError("PRIMARY_ERROR337")};
        for (Throwable primary : primaries) {
            scenario("same-instance " + primary.getClass().getSimpleName() + " survives after observation", () -> {
                Rig r = new Rig(); Delegate d = new Delegate(r, 2, data(), reply(), 1); d.primary = primary;
                Binder.setTransactDelegate(d); check(callFailure(r, d) == primary, "Primary instance replaced"); d.verify();
                List<Map<String, Object>> rows = transactions(); check(rows.size() == 2, "Exception phase missing");
                phase(rows.get(1), r, d, "after", 52, 28);
                check(rows.get(1).get("result") == null
                        && primary.getClass().getName().equals(rows.get(1).get("primaryExceptionClass")), "Exception evidence wrong");
                r.finish();
            });
        }
        for (String method : new String[]{"dataSize", "dataPosition", "dataAvail"}) {
            for (int call : new int[]{1, 2}) {
                scenario(method + " diagnostic fault in " + (call == 1 ? "before" : "after") + " cannot alter delegation", () -> {
                    Rig r = new Rig(); Parcel input = data(); input.failScalar(method, call, new AssertionError("SCALAR_ONLY337"));
                    Delegate d = new Delegate(r, 4, input, reply(), 1); d.result = false;
                    Binder.setTransactDelegate(d); check(!r.call(d.code, d.data, d.reply, d.flags), "Diagnostic changed false"); d.verify();
                    check(transactions().size() == 1, "Only failing phase should be absent"); r.finish();
                });
            }
        }
        scenario("scalar failure cannot mask checked primary or access reply", () -> {
            Rig r = new Rig(); Parcel input = data(); input.failScalar("dataSize", 2, new IllegalStateException("AFTER_SCALAR337"));
            Delegate d = new Delegate(r, 2, input, reply(), 1); d.primary = new RemoteException("REMOTE_SURVIVES_SCALAR337");
            Binder.setTransactDelegate(d); check(callFailure(r, d) == d.primary, "Checked primary replaced by scalar failure"); d.verify(); r.finish();
        });
        for (Throwable diagnostic : new Throwable[]{new JSONException("JSON337"), new IllegalStateException("JSON_RUNTIME337"), new AssertionError("JSON_ERROR337")}) {
            scenario("JSON diagnostic " + diagnostic.getClass().getSimpleName() + " cannot change primary", () -> {
                Rig r = new Rig(); Delegate d = new Delegate(r, 4, data(), reply(), 1); d.primary = new RemoteException("REMOTE_SURVIVES_JSON337");
                JSONObject.failEvent("callback_transaction", diagnostic); Binder.setTransactDelegate(d);
                check(callFailure(r, d) == d.primary, "JSON diagnostic masked primary"); d.verify();
                check(transactions().isEmpty(), "Failed JSON should not invent successful event");
                JSONObject.failEvent(null, null); r.finish();
            });
        }
        for (boolean throwsPrimary : new boolean[]{false, true}) {
            scenario("output failure preserves " + (throwsPrimary ? "primary exception" : "true return"), () -> {
                Rig r = new Rig(); Delegate d = new Delegate(r, 3, data(), reply(), 1); d.result = true;
                if (throwsPrimary) d.primary = new IllegalStateException("PRIMARY_SURVIVES_OUTPUT337");
                PrintStream original = System.out;
                PrintStream broken = new PrintStream(new ByteArrayOutputStream()) {
                    @Override public void println(Object value) { throw new AssertionError("DIAGNOSTIC_OUTPUT337"); }
                };
                Binder.setTransactDelegate(d);
                try {
                    System.setOut(broken);
                    if (throwsPrimary) check(callFailure(r, d) == d.primary, "Output masked primary");
                    else check(r.call(d.code, d.data, d.reply, d.flags), "Output changed return");
                } finally { System.setOut(original); broken.close(); }
                d.verify(); r.finish();
            });
        }
        scenario("null reply and unchanged null input are delegated without an admission gate", () -> {
            Rig r = new Rig(); Delegate d = new Delegate(r, 4, null, null, 1); d.result = true;
            Binder.setTransactDelegate(d); check(r.call(d.code, null, null, d.flags), "Diagnostic null input became a gate");
            d.verify(); check(transactions().isEmpty(), "Unavailable scalar metadata fabricated"); r.finish();
        });
        scenario("actual callback enrollment and original token cleanup survive transaction observations", () -> {
            Rig r = new Rig(); Surface surface = new Surface();
            Delegate configure = new Delegate(r, 1, data(), reply(), 1); configure.result = true;
            configure.operation = () -> r.callback.onStreamConfigured(7, surface, 640, 480, 1);
            Binder.setTransactDelegate(configure); check(r.call(1, configure.data, configure.reply, 1), "Configure delegation changed");
            configure.verify(); r.queue.drain();
            Delegate close = new Delegate(r, 3, data(), reply(), 1); close.result = true;
            close.operation = () -> r.callback.onStreamClosed(7);
            Binder.setTransactDelegate(close); check(r.call(3, close.data, close.reply, 1), "Close delegation changed");
            close.verify(); r.queue.drain(); check(surface.releases == 1, "Surface owner changed");
            r.finish();
            check(transactions().size() == 4, "Normal observations missing");
            Map<String, Object> latest = null;
            for (Map<String, Object> row : JSONObject.events())
                if ("producer_resources_state".equals(row.get("event"))) latest = row;
            check(latest != null && Boolean.TRUE.equals(latest.get("knownClear")), "Original known-state cleanup failed");
        });
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("parent or current required");
        if ("parent".equals(args[0])) parentCases();
        else if ("current".equals(args[0])) currentCases();
        else throw new IllegalArgumentException(args[0]);
        Binder.setTransactDelegate(null);
        System.out.println("RESULT mode=" + args[0] + " passed=" + passed + " targetThreadsStarted=0 wireProof=false");
    }
}
