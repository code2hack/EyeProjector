# Stock Phone camera-access experiment (#67)

This isolated experiment prepares a real native virtual-camera producer and an ordinary
Camera2 consumer. It does not establish support on Samsung, replace a physical camera,
implement Glasses capture/transport, or prove target-app selection. All device operations
belong to the Manager's concrete reservation. No device identity, display ID, endpoint,
installed path or firmware result is assumed here.

## Exact Android 16 contract

The seven unmodified Apache-2.0 AIDL files are pinned and hashed in
[aosp-pin.json](aosp-pin.json). They come from `frameworks/av` commit
[`4b2111885ff934799e70eddae2527a9848c3be29`](https://android.googlesource.com/platform/frameworks/av/+/4b2111885ff934799e70eddae2527a9848c3be29/services/camera/virtualcamera/aidl/android/companion/virtualcamera/).
The interface is **unstable**, not a public SDK API; Samsung may omit or change it.

| Item | Pinned AOSP behavior |
| --- | --- |
| ServiceManager name | `virtual_camera` |
| Binder descriptor | `android.companion.virtualcamera.IVirtualCameraService` |
| Methods, in AIDL order | `boolean registerCamera(IBinder token, VirtualCameraConfiguration config, int deviceId)`; `void unregisterCamera(IBinder token)`; `String getCameraId(IBinder token)` |
| Permission on all three calls | `android.permission.CREATE_VIRTUAL_DEVICE` |
| Configuration | input streams, callback, sensor orientation, lens facing |
| Input selected here | 640×480, RGBA_8888 (=1), max 30 fps, orientation 0, EXTERNAL (=2) |
| Callback, one-way | `onStreamConfigured(id, Surface, width, height, format)`; `onProcessCaptureRequest(id, frameId)`; `onStreamClosed(id)` |
| Route selected here | Android device ID `0`, the default host device; distinct from physical camera ID `"0"` |
| Camera identifier | Read the actual string through `getCameraId(token)`; AOSP generates `v<deviceId>_<counter>` |

[Service implementation](https://android.googlesource.com/platform/frameworks/av/+/4b2111885ff934799e70eddae2527a9848c3be29/services/camera/virtualcamera/VirtualCameraService.cc),
[service registration](https://android.googlesource.com/platform/frameworks/av/+/4b2111885ff934799e70eddae2527a9848c3be29/services/camera/virtualcamera/main.cc),
[lazy-service configuration](https://android.googlesource.com/platform/frameworks/av/+/4b2111885ff934799e70eddae2527a9848c3be29/services/camera/virtualcamera/virtual_camera.hal.rc).

AOSP's Shell requests that permission, declared `internal|role`, in pinned
`frameworks/base` commit `99b01a65cc4c104933788b3143285ab6bae65827`:
[Shell manifest](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/packages/Shell/AndroidManifest.xml),
[permission declaration](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/core/res/AndroidManifest.xml).
This is a candidate for an actual shell/Shizuku UID caller, not evidence of Samsung's
grant or a requirement for root. An ordinary app APK does not acquire shell privileges.

The service is lazy. A null `checkService` is only a cache observation. The caller also
uses `getService`, which asks the platform service manager to start a lazy service.
A null result then uses `waitForService` and reports `service_lookup_waiting`. Root may
bound this pre-registration wait (or the read-only `inspect` mode) and correlate service
declarations/init/logs before declaring a platform-wide absence. An inspect timeout is
a lookup outcome, not a permission or camera-path verdict.

Native direct registration retains a token-to-camera map without token-death cleanup.
The framework [VirtualCameraController](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/companion/java/com/android/server/companion/virtual/camera/VirtualCameraController.java)
adds lifecycle handling for its own path. This experiment therefore retains its own fresh
Binder token and explicitly unregisters it. The global `cmd virtual_camera enable_test_camera`
and `disable_test_camera` commands share a singleton test token and are not used.

## Producer error and Surface ownership evidence

Actual camera188 used historical JAR `c654e3c8` and failed before any changing
imagery. Its renderer catch dereferenced a null Surface before emitting the preceding
error; the first exception and null origin were not recorded. That result remains
failed historical evidence. Matching service registration/descriptor and consumer
configuration do not prove callback-wire compatibility or rendered frames.

The producer now records owned callback arguments, Surface presence/diagnostic
validity, calling UID/PID and class-loader descriptions before Handler handoff.
Unavailable metadata remains unresolved; validity is not an admission/permission gate.
Class-loader descriptions are observations, not a verified platform sender/schema
or loaded-code digest. No Binder token, parcel contents or image pixels are dumped.

A callback's Surface has one explicit release owner, shared with its stream after
handoff. Failed initialization is reported with its FIRST exception, operation stage
and full stack before cleanup. Cleanup faults are reported separately; absent-Surface
cleanup owns no object and is an explicit no-op. Failed streams are not published as
configured. Repeated closure cannot release the same Surface twice. Partial EGL
cleanup attempts all acquired resources and reports its outcome without replacing
the initialization error or killing the renderer on an expected cleanup exception.
Unconfirmed cleanup emits `producer_resources_release_unconfirmed` rather than
a fabricated `producer_resources_released` completion.

The actor enrolls each callback Surface in an inventory before diagnostics or a
Handler post. Unregister sweeps every lease for that callback, including a lease
whose callback has not finished posting and one not yet mapped as a stream.
Every inventory change emits `producer_resources_state` with a monotonic revision,
actor PID/inventory identity, active registration callbacks, pending release sweeps,
owned/pending/unconfirmed counts and `knownClear`. Stream completion includes the
whole acquired EGL cleanup result, not just the Surface release result. Failed
cleanup remains recorded; repeated release does not erase it or attempt release
again. A status command also reports the retained inventory after token removal.

`knownClear` describes only resources known at that observation. A later callback
first publishes a newer pending state, and late failed cleanup publishes a newer
unconfirmed state. Neither a clear state nor the legacy release marker establishes
that future Binder callbacks cannot arrive. A caller must interpret the latest
applicable inventory state, preserving pending duties when it supersedes an older
positive marker. The frozen private173 caller's any-marker selection cannot do
this; its separately allocated correction is required before operational use of
this producer candidate. No inactivity period or callback-quiescence claim is used.

These are error/ownership repairs, with the same AIDL pin, service route, stream-ID
reuse and ONE live actor/Binder-token register/status/unregister/quit policy. They
do not identify Samsung's null origin, create a missing input Surface, recover the
dead camera188 token or establish real EGL/preview/JPEG/physical success. The orphan
`v0_1001` remains a separate Manager duty; no new registration/reset is implied.

Cached producer failure checks use the complete production Java files against
explicit Surface/EGL/Handler/AIDL-shape doubles, not an Android runtime or wire
implementation. Source/host results and later allocated standalone JAR builds must
retain their exact parent/current/source/artifact association.

## Host build (allocated shared build window)

No dependencies are downloaded. The builder verifies every pinned AIDL digest, generates
Java with installed SDK AIDL, compiles Java 17, then creates a dex JAR with D8. It only
replaces this tool's ignored `build/` directory. Use the project's JDK 17 and SDK:

```sh
python3 tools/camera-access/build.py --verify-pin
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  PATH=/usr/lib/jvm/java-17-openjdk-amd64/bin:$PATH \
  python3 tools/camera-access/build.py --sdk "$ANDROID_HOME"
timeout --foreground 10m ./gradlew --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g :hostprobe:assembleDebug :hostprobe:lintDebug
```

Outputs: `tools/camera-access/build/camera-access.jar` and
`hostprobe/build/outputs/apk/debug/hostprobe-debug.apk`. Freeze source SHA, diff state,
build commands/results and artifact SHA-256 before device execution.

## Ordinary consumer output mode and closure contract

The ordinary `com.code2hack.eyeprojector.hostprobe/.CameraAccessActivity` consumer
accepts `--es outputMode preview-only` for a real preview-only opening. That path
checks only supported SurfaceTexture sizes, allocates one preview Surface, and
configures a one-output session. It performs no required JPEG-size lookup, no JPEG
ImageReader allocation and no combined-output configuration. Optional JPEG metadata
in the independent enumeration log does not gate that opening. Actual camera APIs,
context, permission and display routing are unchanged.

The compatibility default remains `preview-jpeg`; an explicit
`--es outputMode preview-jpeg` selects the same combined preview/JPEG mode. It checks
both supported size sets and configures both outputs. Mode and exact camera ID are
captured immutably for each opening. The JPEG button is unavailable for preview-only;
a JPEG command on such an opening records `jpeg_not_configured`, without reopening
or switching mode. Use the existing in-app JPEG button on a live combined opening.
Root still owns ordinary permission/foreground setup. If Android pauses the fixture
for its permission prompt, pause cancels the pending open; after permission setup,
issue an explicit Open again rather than counting the grant as camera success.
A fresh Activity only handles `operation=open` automatically; `capture`, `close`,
`enumerate` and `finish` commands otherwise use the same existing top instance's
`onNewIntent`. Intent delivery may pause/close the current camera, so shell capture
or enumeration during preview is not a reliable live-photo control path. No new
automatic capture or background consumer interface is provided.

Full existing JSON metadata remains append-only in `camera-access-events.jsonl`
and the `EyeCameraConsumer` log. The visible status uses a compact two-line summary
rather than all enumeration JSON, preserving space for the weighted preview.
`surface_available`/`surface_size_changed` log actual dimensions, and preview samples
include the actual view dimensions, output mode, camera ID and opening generation.
A source layout correction alone is not evidence of a usable real Surface.

Each opening permanently owns its original CameraDevice, session, Surface and
optional ImageReader. Close invalidates preview/capture admission immediately and
records `consumer_close_requested`. Close calls do not imply callbacks. A late
`onOpened` after cancellation closes that original camera without configuring it;
a late session callback belongs to that original opening. A replacement open and
explicit finish wait behind actual original closure without blocking either thread.
The session and camera may report closure in either order. After those outcomes,
owned outputs are released and `consumer_close_completed` is emitted; only then
can a successor start. An explicitly finished Activity is finished after completion;
a system `consumer_destroyed` event is separately recorded and the callback worker
is retained until original ownership resolves, then `consumer_worker_shutdown` logs.
Destroyed TextureView textures are retained until that original ownership releases
its outputs; they are never handed to a successor.

`onConfigureFailed` is an actual terminal path for that failed session, which Android
considers closed; the fixture records `session_configure_failed` without inventing
`session_closed` or waiting for an undocumented later callback. Camera errors and
disconnection still require the delivered original camera's `onClosed`. See the
[session callback contract](https://developer.android.com/reference/android/hardware/camera2/CameraCaptureSession.StateCallback#onConfigureFailed(android.hardware.camera2.CameraCaptureSession))
and [camera callback contract](https://developer.android.com/reference/android/hardware/camera2/CameraDevice.StateCallback).
A synchronous setup failure before invoking an open can release only local outputs,
with no camera closure claimed. Exceptions from invoked open/configure calls are
recorded as `open_call_unresolved`/`configuration_call_unresolved`: queued original
callbacks may still arrive, so pending ownership is preserved. A close/release
exception is similarly unconfirmed. Missing callbacks leave reopen/finish/worker
shutdown pending; no elapsed cap, forced completion or fabricated cleanup is added.

Focused cached host checks compile the exact production close-owner declaration,
not a copy, with delayed/reentrant fake callbacks:

```sh
timeout --foreground 120s python3 hostprobe/src/test/cameraaccess/run.py \
  --output-dir "$NEW_HOST_CHECK_EVIDENCE_DIRECTORY"
```

These checks do not instantiate Android, open Camera2, allocate a real Surface or
prove layout/imagery. Root separately allocates the finite offline hostprobe build,
then ordinary installed APK/CAMERA/foreground/current-display setup and real device
checks. The accepted producer JAR/AIDL and closed camera90 evidence are unchanged;
that service/token/empty-map release result did not exercise a configured stream
or EGL imagery.

## Root-owned execution template (unresolved fields)

Resolve actual Phone serial/model/OS/API/user, shell UID, permission grant/denial,
current projection display and installed APK hashes in the reservation evidence first.
Use TCP5555 as directed; this experiment has no Glasses operation. Keep the existing
projection running. The commands below are templates, not executed evidence.

1. Install only the owned hostprobe update with `install -r` and transfer the isolated
   JAR to an assigned temporary location. Record installed/build byte hashes. Do not
   clear packages, change bindings or terminate the host/projection.
2. Launch the consumer on the observed existing scrcpy display and enumerate **before**
   registering. Its identity is `com.code2hack.eyeprojector.hostprobe/.CameraAccessActivity`.
   CAMERA permission uses the ordinary Android prompt or a separately authorized test grant.
3. Launch the producer through the reserved actual shell/Shizuku execution route, retaining
   stdin and stdout in a supervised interactive terminal:

   ```sh
   # Run in the reserved Phone shell; PHONE_CAMERA_JAR is an observed path on that Phone.
   CLASSPATH="$PHONE_CAMERA_JAR" app_process / \
     com.code2hack.eyeprojector.cameraaccess.CameraAccessMain
   ```

   An optional `inspect` argument exits after service lookup without registration; bound
   that invocation on Root's host if needed. Before receiving `register`, the program only reports caller/permission/service fields
   and waits for commands. `permissionResult=0` means granted; `-1` means denied;
   `UNRESOLVED` preserves the reflection failure rather than asserting a grant.
   Service calls preserve actual Binder exceptions and returned values.
4. Type `register`. Read the actual `camera_id.cameraId`; do not invent an ID.
   Record the additional enumeration separately from opening that exact ID. From Root's
   host, with observed `PHONE`, `DISPLAY_ID` and `CAMERA_ID`:

   ```sh
   timeout 15s adb -s "$PHONE" shell am start --display "$DISPLAY_ID" \
     -n com.code2hack.eyeprojector.hostprobe/.CameraAccessActivity \
     --es cameraId "$CAMERA_ID" --es outputMode preview-only --es operation open
   ```

   This selects the explicit preview-only experiment. Controls are Enumerate / Open /
   JPEG / Close, with JPEG unavailable in this mode. Mode omitted means the compatible
   combined preview/JPEG mode. Use read-only diagnostics during live preview. After
   close, same-instance `open` queues behind actual original closure; `finish` waits
   before ending the Activity. Do not cold-launch a new cleanup instance or use a
   shell `capture`/`enumerate` intent as an uninterrupted live-camera action. `open`
   takes the exact explicit ID; no default/first physical camera is substituted.
5. Verify increasing `producer_progress.postedFrames`, Camera2 `capture_result` frame
   numbers/timestamps, and `preview_presented` callbacks with changing pixel sample hashes.
   EGL generates a changing background, moving white bar and 16-bit binary frame marker
   along the source's bottom edge (OpenGL origin). Pixel values/geometry on the display
   may rotate or scale; inspect the actual preview as well.
6. In a separately selected combined `preview-jpeg` opening, use the existing in-app
   JPEG button for two photos separated by source progress, retrieve the exact reported filenames,
   verify valid dimensions, byte SHA-256 and decoded sample changes, and inspect the
   source marker in the images. Different encoded hashes alone do not prove changing
   imagery. Pixel samples establish changes at the consumer, not physical Glasses rendering.
7. Close; retain `consumer_close_requested`, real original `session_closed` /
   `camera_closed` (or actual failed-configuration terminal outcome), and
   `consumer_close_completed`. Reopen the same ID only after that completion,
   verify fresh preview/capture, then close again. Type `status` in the producer to inspect
   stream request/frame counts. Type `unregister` while that same token-owning process
   is alive, wait for `tokenAbsent=true` and `producer_resources_released`, then verify
   consumer enumeration no longer includes that camera. Type `quit` to release the renderer
   thread. A failed unregister retains the token and permits retrying `unregister`; an EOF
   with failed cleanup leaves the process alive and emits `eof_cleanup_failed`.
8. Confirm before/after physical camera identifiers and ordinary physical-camera open/capture,
   and verify existing projection/app/input still operate. Close only the consumer and owned
   producer resources. Do not stop the global camera service or use the singleton disable command.

Do not kill the producer or let an outer timeout kill it while registered: native token
lifetime alone does not establish removal. Keep the supervised terminal available through
explicit unregister. Service/Binder failure can prevent confirmed cleanup; preserve the
live token/process, exact failure and evidence for Root's coordinated disposition. Abrupt
process death or an unusable control TTY is a remaining experimental recovery limitation.

The consumer appends JSONL to app-private `files/camera-access-events.jsonl`, and saves
`files/camera-access-<instance>-<generation>-<count>.jpg`. Retrieve using the reserved `run-as` route;
these are owned experiment files. Every Activity creation logs UID/PID, OS fingerprint,
display and Android device context; commands log exact camera IDs and generations.
The fixture requests close on pause/destroy and retains original callback ownership
until its actual terminal outcomes before reporting completed release of its own
Surface, optional ImageReader, capture session and CameraDevice. Producer resources use one serial thread.

## Evidence and completion boundary

Keep actual identity fields unresolved until observed. Record exact command output for
service availability, permission/UID, ABI/configuration result, real camera ID,
enumeration, selected consumer context, preview/JPEG change, close/reopen, unregister,
physical-camera continuity and projection continuity. Report host build, real device,
physical observation and inference separately.

Adding a camera does not make an arbitrary target app select it. A virtual-device camera
policy would affect that virtual device's apps; the current scrcpy display is an ordinary
DisplayManager display. This experiment chooses default device ID0 and must demonstrate
actual Camera2 visibility/selection there. A permission, service, ABI, graphics or routing
failure is an exact blocker for Owner disposition, not completion of #67 or the working
v0.2 virtual-camera goal.
