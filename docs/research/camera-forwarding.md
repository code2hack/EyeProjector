# Glasses camera for Phone apps

Owner decision, 2026-10-20: virtual camera is a late v0.1 experiment, scheduled among the final issues after core projection. Its deliverable is reproducible evidence and a feasibility conclusion, including limitations and any working prototype. A working implementation is optional for v0.1 completion. The [SPEC draft](../../SPEC.md#glasses-camera-through-a-phone-virtual-camera) records the intended experience, with stock Phone feasibility and target-app compatibility still open.

## Capture, attachment and camera replacement

The matching consumer [Rokid camera chapter](https://custom.rokid.com/prod/rokid_web/ff28c865a9634876be98cbc293588460/pc/us/5702e09fefa343d49b949260fd23c487.html?documentId=8005821179264084bccc479e6a024074) describes on-device CameraX/Camera2 capture. Capture on the Glasses and transfer to the Phone is a viable design; it is distinct from exposing that sensor to another app's camera APIs.

| Target-app flow | Integration candidate | Boundary |
| --- | --- | --- |
| Add photo / image picker | Capture on Glasses, transfer to Phone, publish the image in MediaStore and select it in the app's normal attachment UI | Selection depends on the app's picker; this is a photo attachment, not live camera substitution. |
| Delegated camera capture | A cooperating app explicitly invokes an EyeProjector capture Activity, which returns the Glasses image through the standard capture result/output URI | Modern implicit camera intents resolve to preinstalled system camera apps; a third-party handler alone does not cover arbitrary apps. |
| Embedded Camera2/CameraX or live camera | Feed Glasses frames into a Phone virtual camera that the target app can enumerate/select | Platform privileges/services, camera visibility and app selection need qualification on the actual Phone. |

Android documents image selection through the [Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker), shared images through [MediaStore](https://developer.android.com/training/data-storage/shared/media), and the [capture result/output-URI contract](https://developer.android.com/reference/android/provider/MediaStore#ACTION_IMAGE_CAPTURE). For callers targeting Android 11+, implicit image/video capture handlers are restricted to preinstalled system camera apps; explicit third-party targeting remains possible. [Android camera-intent behavior](https://developer.android.com/about/versions/11/behavior-changes-11#camera)

## A real virtual-camera path exists

Android 16's virtual-camera implementation accepts producer frames through a Surface and exposes them through standard Camera2/CameraX, NDK and legacy camera APIs. It supports YUV/JPEG with limited camera capabilities, including no flash/torch. This supplies a relevant platform mechanism, not proof of support on the stock Fold6. [Android 16 implementation description](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android16-release/services/camera/virtualcamera/README.md)

Two access/routing candidates deserve separate evaluation:

- **Virtual-device camera policy:** expose remote cameras to apps running in that virtual device. The current pinned backend creates an ordinary virtual display through DisplayManager, rather than a VirtualDeviceManager-owned device. A virtual-device camera therefore does not automatically replace cameras for apps on the current projection display. [Android 16 camera policy](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/companion/virtual/VirtualDeviceParams.java), [pinned display creation](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java#L211-L238)
- **Native default-device registration:** Android 16's VirtualCameraService checks `CREATE_VIRTUAL_DEVICE`, accepts a device ID, and its test-camera command registers against default device ID0. AOSP's Shell requests that permission. A shell/Shizuku producer is thus a plausible route to investigate; root has not been established as necessary. Default device ID0 is not physical camera ID `"0"`, and adding a camera does not establish that an app selects it instead of the Phone camera. [Native service and test command](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android16-release/services/camera/virtualcamera/VirtualCameraService.cc), [Shell permissions](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/packages/Shell/AndroidManifest.xml)

The virtual-camera configuration/creation interfaces are hidden System APIs in the inspected Android 16 release and are absent from the installed public API36 SDK JAR. AOSP permission declarations are not proof of stock Samsung access: service availability, permission/SELinux behavior, frame production, camera enumeration and target-app selection remain untested. [Configuration API source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/companion/virtual/camera/VirtualCameraConfig.java)

## Selected direction and feasibility work

Investigate the selected Glasses-camera → authenticated LAN → Phone-virtual-camera → target-app path on the actual Phone. The intended result is the app's usual camera preview and photo capture using the Glasses sensor, including when its Add photo flow offers a camera action. An existing-image picker remains an existing-image picker. Qualify service access, frame production, visibility on the projection display and actual selection/capture in the initial ChatGPT, Paseo, Comet and WeChat targets where they expose a camera flow. Record demonstrated limitations as valid experimental outcomes. The attachment and delegated-capture alternatives above explain different integration paths; they do not establish the selected virtual-camera experience.

Native Bluetooth audio remains the selected audio route. Camera frames need their own integration; Bluetooth headset pairing does not supply a Phone camera provider. A USB external-camera implementation also does not automatically accept a LAN camera stream. [AOSP external-camera integration](https://source.android.com/docs/core/camera/external-usb-cameras)

No device commands, camera capture, streaming prototype, installation, settings, runtime changes or implementation edits were performed. The research does not establish stock Fold6/Shizuku virtual-camera support or universal target-app compatibility.
