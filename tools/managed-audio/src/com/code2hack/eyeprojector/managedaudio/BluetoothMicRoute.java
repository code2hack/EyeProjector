package com.code2hack.eyeprojector.managedaudio;

import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Standalone shell trial. It owns one transient route request and never opens a recorder. */
public final class BluetoothMicRoute {
    private static final String SHELL_PACKAGE = "com.android.shell";

    private BluetoothMicRoute() {}

    public static void main(String[] args) {
        final boolean select;
        final boolean communicationMode;
        final int outputId;
        final long durationMs;
        try {
            if (args.length == 1 && "list".equals(args[0])) {
                select = false;
                communicationMode = false;
                outputId = 0;
                durationMs = 0;
            } else if (args.length == 3 && ("select".equals(args[0])
                    || "select-communication".equals(args[0]))) {
                select = true;
                communicationMode = "select-communication".equals(args[0]);
                outputId = Integer.parseInt(args[1]);
                durationMs = Long.parseLong(args[2]);
                if (outputId <= 0 || durationMs <= 0) throw new IllegalArgumentException();
            } else {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            System.err.println("Usage: BluetoothMicRoute list | select <SCO-output-id> <hold-ms>"
                    + " | select-communication <SCO-output-id> <hold-ms>");
            System.exit(2);
            return;
        }

        // app_process supplies no Application/main Looper. Keep the framework main Looper
        // running, and create the real Shell context on a second retained framework Looper.
        Looper.prepareMainLooper();
        final HandlerThread frameworkThread = new HandlerThread("bluetooth-mic-framework");
        frameworkThread.start();
        final Handler framework = new Handler(frameworkThread.getLooper());
        new Thread(() -> {
            int result = run(select, communicationMode, outputId, durationMs, framework);
            frameworkThread.quitSafely();
            try {
                frameworkThread.join();
            } catch (InterruptedException error) {
                failure("framework_join", error);
                Thread.currentThread().interrupt();
                result = 1;
            }
            event("finished", "exit_code=" + result);
            // Also releases this process's Binder lifetime; no other process is stopped.
            System.exit(result);
        }, "bluetooth-mic-trial").start();
        Looper.loop();
    }

    private static int run(boolean select, boolean communicationMode, int outputId,
            long durationMs, Handler framework) {
        AudioManager audio = null;
        AudioManager.OnCommunicationDeviceChangedListener listener = null;
        AudioManager.OnModeChangedListener modeListener = null;
        AudioManager.AudioRecordingCallback recordingCallback = null;
        OwnAudioRequests requests = null;
        Thread shutdownHook = null;
        boolean interrupted = false;
        int result = 0;
        String stage = "shell_context";
        try {
            FutureTask<AudioManager> setup = new FutureTask<>(() -> {
                Context shell = freshShellContext();
                event("identity", "uid=" + Process.myUid() + " pid=" + Process.myPid()
                        + " package=" + shell.getPackageName()
                        + " application_uid=" + shell.getApplicationInfo().uid
                        + " attribution_uid=" + shell.getAttributionSource().getUid()
                        + " attribution_package=" + shell.getAttributionSource().getPackageName());
                // The service registry constructs its manager with the base ContextImpl,
                // whose Application can be null in app_process. Bind this one manager to
                // our real Shell wrapper so setMode has its required application context.
                // The constructor is public at runtime but hidden from SDK stubs.
                AudioManager manager = AudioManager.class.getConstructor(Context.class)
                        .newInstance(shell);
                event("audio_manager", "binding=FRESH_SHELL_CONTEXT_CONSTRUCTOR");
                return manager;
            });
            framework.post(setup);
            audio = setup.get();
            stage = "recording_callback";
            recordingCallback = new AudioManager.AudioRecordingCallback() {
                @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                    try {
                        recordings("changed", configs);
                    } catch (Throwable error) {
                        failure("recording_callback", error);
                    }
                }
            };
            audio.registerAudioRecordingCallback(recordingCallback, framework);
            stage = "initial_recordings";
            recordings("initial", audio.getActiveRecordingConfigurations());
            stage = "list";
            List<AudioDeviceInfo> outputs = audio.getAvailableCommunicationDevices();
            event("available_communication_outputs", "count=" + outputs.size());
            for (AudioDeviceInfo device : outputs) {
                event("available_communication_output", deviceFields(device));
            }
            snapshot("before", audio);
            if (select) {
                AudioDeviceInfo selected = null;
                for (AudioDeviceInfo device : outputs) {
                    if (device.getId() == outputId) selected = device;
                }
                if (selected == null) {
                    event("selection_unavailable", "requested_output_id=" + outputId);
                    result = 2;
                } else if (!selected.isSink()
                        || selected.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    event("selection_requires_sco_output", deviceFields(selected));
                    result = 2;
                } else {
                    stage = "route_listener";
                    listener = device -> event("communication_changed", deviceFields(device));
                    audio.addOnCommunicationDeviceChangedListener(
                            command -> framework.post(command), listener);
                    requests = new OwnAudioRequests(audio);
                    final OwnAudioRequests retainedRequests = requests;
                    shutdownHook = new Thread(() -> retainedRequests.release("shutdown_hook"),
                            "bluetooth-mic-release");
                    stage = "register_shutdown_hook";
                    Runtime.getRuntime().addShutdownHook(shutdownHook);
                    if (communicationMode) {
                        stage = "mode_listener";
                        modeListener = mode -> event("mode_changed", "mode=" + mode);
                        audio.addOnModeChangedListener(command -> framework.post(command), modeListener);
                        stage = "set_communication_mode";
                        requests.requestCommunicationMode();
                        stage = "after_mode_request";
                        snapshot(stage, audio);
                    }
                    stage = "set_communication_device";
                    boolean accepted = requests.requestRoute(selected);
                    event("request_return", "accepted=" + accepted
                            + " requested_output_id=" + outputId);
                    stage = "after_request";
                    snapshot(stage, audio);
                    if (accepted) {
                        event("hold_begin", "duration_ms=" + durationMs);
                        stage = "hold";
                        Thread.sleep(durationMs);
                        stage = "hold_end";
                        snapshot(stage, audio);
                    } else {
                        result = 3;
                    }
                }
            }
        } catch (Throwable error) {
            failure(stage, error);
            interrupted = rootCause(error) instanceof InterruptedException;
            result = 1;
        } finally {
            if (audio != null && listener != null) {
                try {
                    audio.removeOnCommunicationDeviceChangedListener(listener);
                } catch (Throwable error) {
                    failure("remove_route_listener", error);
                    result = 1;
                }
            }
            if (requests != null && !requests.release("finally")) result = 1;
            if (shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException shuttingDown) {
                    // A concurrently running hook uses the same coordinated release owner.
                } catch (Throwable error) {
                    failure("remove_shutdown_hook", error);
                    result = 1;
                }
            }
            if (audio != null && modeListener != null) {
                try {
                    audio.removeOnModeChangedListener(modeListener);
                } catch (Throwable error) {
                    failure("remove_mode_listener", error);
                    result = 1;
                }
            }
            if (audio != null && recordingCallback != null) {
                try {
                    audio.unregisterAudioRecordingCallback(recordingCallback);
                } catch (Throwable error) {
                    failure("unregister_recording_callback", error);
                    result = 1;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        return result;
    }

    /** Serializes our writes with normal/shutdown cleanup; never changes another client's identity. */
    private static final class OwnAudioRequests {
        private final AudioManager audio;
        private boolean routeAttempted;
        private boolean modeAttempted;
        private boolean released;
        private boolean cleanupSucceeded = true;

        OwnAudioRequests(AudioManager audio) { this.audio = audio; }

        synchronized void requestCommunicationMode() throws InterruptedException {
            if (released) throw new InterruptedException();
            // Mark before Binder entry so an ambiguous throwing call still receives cleanup.
            modeAttempted = true;
            audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
            event("mode_request_return", "requested_mode=" + AudioManager.MODE_IN_COMMUNICATION
                    + " returned=true");
        }

        synchronized boolean requestRoute(AudioDeviceInfo selected) throws InterruptedException {
            if (released) throw new InterruptedException();
            routeAttempted = true;
            return audio.setCommunicationDevice(selected);
        }

        synchronized boolean release(String origin) {
            if (released) return cleanupSucceeded;
            released = true;
            // The same AudioManager carries the original client Binder. Each release is
            // attempted once, including on partial failure; no saved global state is restored.
            if (routeAttempted) {
                try {
                    audio.clearCommunicationDevice();
                    event("clear_return", "returned=true origin=" + origin);
                } catch (Throwable error) {
                    failure("clear_communication_device", error);
                    cleanupSucceeded = false;
                }
                observeAfterRelease("after_clear");
            }
            if (modeAttempted) {
                try {
                    // MODE_NORMAL relinquishes this caller's mode request to framework arbitration.
                    audio.setMode(AudioManager.MODE_NORMAL);
                    event("mode_release_return", "requested_mode=" + AudioManager.MODE_NORMAL
                            + " returned=true origin=" + origin);
                } catch (Throwable error) {
                    failure("release_communication_mode", error);
                    cleanupSucceeded = false;
                }
                observeAfterRelease("after_mode_release");
            }
            event("cleanup_complete", "origin=" + origin + " route_attempted=" + routeAttempted
                    + " mode_attempted=" + modeAttempted + " succeeded=" + cleanupSucceeded);
            return cleanupSucceeded;
        }

        private void observeAfterRelease(String phase) {
            try {
                snapshot(phase, audio);
            } catch (Throwable error) {
                failure(phase, error);
                cleanupSucceeded = false;
            }
        }
    }

    private static void snapshot(String phase, AudioManager audio) {
        event("state", "phase=" + phase + " mode=" + audio.getMode()
                + " communication_" + deviceFields(audio.getCommunicationDevice()));
    }

    private static String deviceFields(AudioDeviceInfo device) {
        return device == null ? "id=none type=none" : "id=" + device.getId()
                + " type=" + device.getType() + " sink=" + device.isSink();
    }

    private static void recordings(String phase, List<AudioRecordingConfiguration> configs) {
        event("active_recordings", "phase=" + phase + " count=" + configs.size());
        for (AudioRecordingConfiguration config : configs) {
            AudioDeviceInfo device = config.getAudioDevice();
            String input = device == null ? "input_id=none input_type=none input_is_source=unknown"
                    : "input_id=" + device.getId() + " input_type=" + device.getType()
                            + " input_is_source=" + device.isSource();
            event("active_recording", "phase=" + phase
                    + " session_id=" + config.getClientAudioSessionId()
                    + " client_audio_source=" + config.getClientAudioSource()
                    + " " + input + " client_silenced=" + config.isClientSilenced());
        }
    }

    private static synchronized void event(String name, String fields) {
        // No AudioDeviceInfo.toString(), address, product name, exception message or audio data.
        System.out.println("event=" + name + " elapsed_ms=" + SystemClock.elapsedRealtime()
                + " " + fields);
        System.out.flush();
    }

    private static void failure(String stage, Throwable error) {
        Throwable cause = rootCause(error);
        event("failure", "stage=" + stage + " exception=" + cause.getClass().getName());
        StackTraceElement[] frames = cause.getStackTrace();
        // Diagnostic output bound only: never print exception messages, arguments or locals.
        for (int index = 0; index < frames.length && index < 12; index++) {
            StackTraceElement frame = frames[index];
            event("failure_frame", "stage=" + stage + " index=" + index
                    + " class=" + frame.getClassName() + " method=" + frame.getMethodName()
                    + " file=" + (frame.getFileName() == null ? "unknown" : frame.getFileName())
                    + " line=" + frame.getLineNumber());
        }
    }

    private static Throwable rootCause(Throwable error) {
        while ((error instanceof InvocationTargetException || error instanceof ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }

    /** Identity delegates unchanged to the framework-created real Shell app context. */
    private static final class ShellApplicationContext extends ContextWrapper {
        ShellApplicationContext(Context base) { super(base); }
        @Override public Context getApplicationContext() { return this; }
    }

    private static Context freshShellContext() throws Exception {
        Class<?> activityThreadType = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadType.getMethod("currentActivityThread").invoke(null);
        if (activityThread == null) {
            activityThread = activityThreadType.getMethod("systemMain").invoke(null);
        }
        Context system = (Context) activityThreadType.getMethod("getSystemContext").invoke(activityThread);
        Context shellPackage = system.createPackageContext(SHELL_PACKAGE, 0);
        Class<?> contextImplType = Class.forName("android.app.ContextImpl");
        Class<?> loadedApkType = Class.forName("android.app.LoadedApk");
        Field packageInfo = contextImplType.getDeclaredField("mPackageInfo");
        packageInfo.setAccessible(true);
        Object loadedShell = packageInfo.get(shellPackage);
        Method createAppContext = contextImplType.getDeclaredMethod(
                "createAppContext", activityThreadType, loadedApkType);
        createAppContext.setAccessible(true);
        Context shell = (Context) createAppContext.invoke(null, activityThread, loadedShell);
        // The fresh AudioManager constructor receives this wrapper directly. Context identity
        // and attribution still delegate to the real framework-created Shell ContextImpl.
        return new ShellApplicationContext(shell);
    }
}
