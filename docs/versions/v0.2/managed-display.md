# v0.2 managed-display setup and operation

The [SPEC](../../../SPEC.md) describes the Owner-accepted product behavior; [the run record](../../runs/v0.2.md) records its scope. This guide describes the current managed implementation. The older low-level capture/audio examples in RUNBOOK describe the original bootstrap rather than this direct managed encoder.

## Phone setup

Install the Phone and Glasses APKs, retain ordinary system Bluetooth pairing, start Shizuku on the Phone and authorize EyeProjector. The accepted managed/virtual-camera setup was exercised on stock Fold6 Android 16/QPR2; not every device exposing the APK minimum SDK supplies those framework capabilities.

The privileged backend needs a legitimate companion-device association accepted by VirtualDeviceManager. That association belongs to the actual shell backend. The current APK does not automatically provision every platform prerequisite. Keep device-specific association, identity, endpoint and recovery data private; do not copy another peer's credentials or fake a framework identity.

Pair EyeProjector through the Glasses code. Enable **Use Glasses camera** for the managed workspace and ordinary-app virtual-camera route. Off retains the legacy Phone-camera/display route. Changing the choice restarts projection and retires its previous resources.

The managed source has independent lock state, receives positional input directly and renders into its AVC encoder. scrcpy supplies control only (`video=false`, `audio=false`). Normal physical Phone locking/screen blanking is supported in the exercised managed configuration. No Panel off operation is required.

## Glasses-local native helper

The native sustained touchpad contact and requested Function sleep action currently need one shell-owned helper running on the Glasses. It is local to the Glasses, not a computer relay. Reboot or helper loss requires bootstrap; raw-pad EOF ends that contact connection, while the retained power owner can remain alive across Activity exit/reopen.

Open the Glasses EyeProjector receiver first. Identify the actual Rokid pad node using the device's input inventory and physical-event observation. Supply its path as `PAD_DEVICE`; a historical event number must not be assumed on another device. Then, in the authorized **Glasses shell**, start the installed class:

```sh
pad_apk=$(pm path com.code2hack.eyeprojector.directrg)
pad_apk=${pad_apk#package:}
CLASSPATH="$pad_apk" nohup /system/bin/app_process /system/bin \
  com.code2hack.eyeprojector.directrg.NativePadReader "$PAD_DEVICE" \
  >/data/local/tmp/eyeprojector-native-reader.log 2>&1 </dev/null &
pad_reader_pid=$!
printf '%s\n' "$pad_reader_pid" >/data/local/tmp/eyeprojector-native-reader.pid
```

Run only one owned helper. For an upgrade/restart, retire that exact original process and its owned reader before starting the replacement; retain PID/birth identity rather than killing every `app_process` or `getevent`. The helper has no arbitrary remote command interface: its local power endpoint accepts the fixed sleep request. It does not grab the native Glasses pad or alter firmware.

The accepted native held-key stream reports release at about three seconds even during a longer finger hold. Use HID Tap for longer holds/drags. Do not describe that input limit as camera/network latency.

## Daily use

- Open a chosen app from EyeProjector Home or the Phone app grid.
- Long-press a Phone icon for notifications, numeric/slider Scale and Remove. Scale is absolute, 0.5–1.5, default 1; it changes projected native layout without changing the physical Phone view.
- For a Phone controller, select it in HID key bindings, Pin it, then learn the four controls. Projection Stop keeps the pin. Unpin/reset releases it and clears the Phone assignments.
- Function single keeps Back. Double steps app → EyeProjector Home → control panel → Rokid Home. Triple requests Glasses sleep. Long remains a no-op.
- Supported app camera previews use the full Glasses → Phone app → Glasses return path. Reopen an app retaining an obsolete camera context after workspace recreation.
- Native Bluetooth carries audio. Actual recording demand automatically requests the active headset's SCO route in communication mode and releases the backend's own requests afterward.

Stop/Disconnect and app exit differ from display sleep. Keep pairing and installed state during ordinary recovery. Exact camera/optical/audio latency and universal app/IME/video-call compatibility are not inferred from the accepted manual flows.
