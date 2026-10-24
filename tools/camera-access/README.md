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
     --es cameraId "$CAMERA_ID" --es operation open
   ```

   Controls are Enumerate / Open / JPEG / Close. Subsequent `am start` commands to this
   same top Activity on this same display can use `--es operation enumerate`, `capture`,
   `close`, `open` or `finish`. `singleTop` retains the current consumer when it is on top.
   `open` takes the exact explicit ID; no default/first physical camera is substituted.
5. Verify increasing `producer_progress.postedFrames`, Camera2 `capture_result` frame
   numbers/timestamps, and `preview_presented` callbacks with changing pixel sample hashes.
   EGL generates a changing background, moving white bar and 16-bit binary frame marker
   along the source's bottom edge (OpenGL origin). Pixel values/geometry on the display
   may rotate or scale; inspect the actual preview as well.
6. Take two JPEGs separated by source progress, retrieve the exact reported filenames,
   verify valid dimensions, byte SHA-256 and decoded sample changes, and inspect the
   source marker in the images. Different encoded hashes alone do not prove changing
   imagery. Pixel samples establish changes at the consumer, not physical Glasses rendering.
7. Close; wait for real `session_closed` / `camera_closed` callbacks. Reopen the same ID,
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
The fixture closes camera resources on pause/destroy and releases only its own Surface,
ImageReader, capture session and CameraDevice. Producer resources use one serial thread.

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
