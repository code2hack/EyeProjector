# Standalone Bluetooth microphone route trial

`BluetoothMicRoute` runs in a separate shell `app_process`; it does not install an APK,
open a recorder, or touch projection/camera ownership. Audio operations use public
Android 35 APIs. Reflection creates a real `com.android.shell` LoadedApk/ContextImpl
context and constructs one AudioManager through its public runtime `AudioManager(Context)`
constructor, which is hidden from SDK stubs. The constructor receives the local Shell
wrapper whose application context is itself, instead of the service registry's base
ContextImpl with a null Application. No global Application, attribution or private
AudioManager field is changed. Android checks
the real calling identity and permissions. Failures report stage and exception class,
followed by at most 12 root-cause stack frames containing only class, method, source
file and line number. Exception messages, arguments, locals and raw traces are omitted.

`list` reads the current mode, communication output, and available communication output
IDs/types. `select <SCO-output-id> <hold-ms>` requests a currently available SCO output
through one retained AudioManager/client Binder, reports acceptance separately from
current/changed communication devices, holds for the supplied positive duration, and
clears through that same client in `finally`. IDs are observations, not saved device IDs.
Type 7 is `TYPE_BLUETOOTH_SCO`. The microphone input port and A2DP media output are not
valid selections for this trial.

`select-communication <SCO-output-id> <hold-ms>` additionally requests
`MODE_IN_COMMUNICATION` through that same retained AudioManager before SCO selection.
The initial `state phase=before` exposes the actual mode before any change. Mode request
return, subsequent actual mode/communication snapshots, and public mode-change callbacks
are separate observations. Other mode owners retain the platform's priority; the tool
does not spoof a caller or set mode on behalf of another app. `list` and normal `select`
make no mode request.

All modes register a public `AudioRecordingCallback` on the retained framework Handler
and take one initial `getActiveRecordingConfigurations()` snapshot. Each snapshot or
framework recording-change callback reports the active count and, for each recording,
only its session ID, client audio source, actual `getAudioDevice()` input ID/type/isSource,
and `isClientSilenced`. Empty lists report zero active recordings; unavailable device
information reports `none`/`unknown`. There is no recording poll, additional timer,
UID/package lookup, reflection into recording metadata, or audio-content access. The
callback is unregistered in `finally` after route cleanup. `list` observes its initial
snapshot and then exits; both selection modes retain it during the hold.

The hold duration starts after acceptance/current-route reporting. Setup and Binder
calls have their own platform execution time; the duration is not an overall process
deadline. The tool adds no polling, retries, product cap or persistent setting.

Root supplies the SDK/tool paths and performs compilation, dexing and execution:

```bash
# AUDIO_ANDROID_JAR: SDK platforms/android-35/android.jar
# AUDIO_D8: SDK build-tools/<installed-version>/d8
# AUDIO_BUILD: Root-selected temporary build directory
mkdir -p "$AUDIO_BUILD/classes"
javac --release 8 -cp "$AUDIO_ANDROID_JAR" -d "$AUDIO_BUILD/classes" tools/managed-audio/src/com/code2hack/eyeprojector/managedaudio/BluetoothMicRoute.java
jar cf "$AUDIO_BUILD/classes.jar" -C "$AUDIO_BUILD/classes" .
"$AUDIO_D8" --lib "$AUDIO_ANDROID_JAR" --min-api 35 --output "$AUDIO_BUILD/bluetooth-mic-route.jar" "$AUDIO_BUILD/classes.jar"
adb -s "$PHONE_SERIAL" push "$AUDIO_BUILD/bluetooth-mic-route.jar" /data/local/tmp/eyeprojector-bluetooth-mic-route.jar
adb -s "$PHONE_SERIAL" shell CLASSPATH=/data/local/tmp/eyeprojector-bluetooth-mic-route.jar app_process / com.code2hack.eyeprojector.managedaudio.BluetoothMicRoute list
# Set SCO_OUTPUT_ID from that current list; Root chooses the trial's hold budget.
adb -s "$PHONE_SERIAL" shell CLASSPATH=/data/local/tmp/eyeprojector-bluetooth-mic-route.jar app_process / com.code2hack.eyeprojector.managedaudio.BluetoothMicRoute select "$SCO_OUTPUT_ID" "$HOLD_MS"
# Optional comparison: request own MODE_IN_COMMUNICATION before SCO.
adb -s "$PHONE_SERIAL" shell CLASSPATH=/data/local/tmp/eyeprojector-bluetooth-mic-route.jar app_process / com.code2hack.eyeprojector.managedaudio.BluetoothMicRoute select-communication "$SCO_OUTPUT_ID" "$HOLD_MS"
```

Output contains no Bluetooth addresses, product names or audio content. Exit codes are
0 for normal completion, 1 for an operation/cleanup failure, 2 for invalid arguments or
unavailable/non-SCO selection, and 3 for a rejected route request. `clear_return` means
the call returned; `after_clear` is a separate observation, not a promise that a specific
prior global route was restored.

Selection modes share one coordinated cleanup owner between `finally` and a shutdown
hook. It serializes our request calls with cleanup and attempts each applicable release
once: clear the communication-device request, then, only for `select-communication`,
call `setMode(MODE_NORMAL)` through the same AudioManager to relinquish our mode request.
It never reasserts a saved global mode/device. `mode_release_return` and the subsequent
actual mode snapshot are separate; another owner's active mode need not be normal.
`cleanup_complete` identifies `finally` or `shutdown_hook` and reports observed failures.

Early termination receives explicit cleanup only if the runtime invokes its shutdown
hook and the calls return. SIGKILL/crash or stuck framework/Binder calls can prevent it;
Binder-death cleanup remains the platform fallback, not a claimed explicit release.

This is shared communication routing, not per-app/PID recorder selection. Other shell
helpers share UID 2000 and may share framework arbitration; Root coordinates them. An
app's mode/recorder preferences and vendor policy may win, and live SCO can affect media
playback quality. Only the optional comparison makes the cooperative mode request;
there is no automatic escalation, recorder/dummy playback, persistent policy, or retry.
Root must observe the actual
target recorder's input while recording and confirm the microphone audibly; acceptance
or a communication-device callback alone does not establish Glasses-microphone capture.
Recording metadata is whatever the public framework returns to the actual caller;
permission-based anonymization, a null device, or missing observations are not bypassed.
Root correlates session changes with the target app's recording action; these logs do
not identify another app's UID/package or prove audible microphone capture.
