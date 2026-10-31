# Projected Home verification

With JDK 17 configured, run ordinary affected Gradle checks first, then:

```sh
python3 directhost/src/test/adapter/run.py --output-dir /private/evidence/bootstrap
python3 directhost/src/test/launch/run.py --output-dir /private/evidence/session-launch
```

The bootstrap suite compiles the actual Phone adapter and checks launch/display replies, malformed and dead-service results, fresh binding identity and Stop during a blocked launch. The Session suite compiles actual `ShellMain`, launch adapters and release/input/scale components with external Android socket/JSON doubles. It drives the production Session's cancellation callback and teardown, helper-release failure/retry, pending launch versus fresh Session, and teardown while the old control lock is occupied. The JVM `ProjectedAppLaunchTest` also drives real host subprocess output drainage, timeout and Stop/reap. These are host results, not Android Binder, target-app placement, screen, optical or physical-input results.

The pinned scrcpy capture logs its new display before the first stream session metadata. `ShellMain` reads only its own child's current log, opens Home once and disables that virtual display's system decorations. Launches are fixed argument vectors through the existing Session owner. `cmd activity start-activity` is the direct operation behind `am start`, avoiding an extra shell-script process. Target launches retain native tasks and themes. The Home Activity has a separate task affinity from the Phone panel.

The foreground parser follows the per-display focused `Resumed:` summary in AOSP [Android 12L RootWindowContainer](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-12.1.0_r1/services/core/java/com/android/server/wm/RootWindowContainer.java) and [Android 15 RootWindowContainer](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/core/java/com/android/server/wm/RootWindowContainer.java). The fixed query is `dumpsys -t 2 activity activities`: Android 15's `activity -d` filter is absent from the Android 12L dump parser. Only the exact backend display section's focused summary is used. Task-local resumed records and global summaries are ignored. An unknown format or absent section yields no observed package. OEM/version qualification remains a device check.

Local Binder ID 7 exposes `launchApp(requestId, packageName, expectedOwnerEpoch, expectedProjectionToken)`; ID 8 queries `projectionDisplayState(expectedOwnerEpoch, expectedProjectionToken)`. Version 2 local replies append this context. The accepted backend epoch and actual Session UUID are distinct; that single Session UUID is separate from its child/display generation. `ProjectionDisplayState` separates those identities, requested package, command status/exit/component and freshly observed foreground package. `STARTED` means the platform command reported `Status: ok`; it does not imply the observed package matches or that physical video/input works. A timed-out command may already have submitted an Activity start. Stop closes queue admission and reaps only the owned helper before existing transport/stock-child release. Helper-release failures remain retryable and are reported after transport cleanup.

R1 correction binds user intent before any executor hop. Fixed Home intents carry the original owner/token, and retained icon callbacks do not switch to the latest Phone singleton. A cheap coherent status snapshot supplies Phone targets; a new owner/STARTING state clears the preceding token. The server captures the accepted immutable owner/Bridge association, atomically captures that Bridge's exact Session under admission, and invokes only the captured object outside the locks. The route registry is typed-operation metadata for the existing lifecycle, not a controller. Stop detaches admission before long teardown; late creation/retirement is conditional on Owner identity. An unavailable/mismatched target never launches or observes a replacement.

The ten Session/service cases compile actual `ShizukuHostService`, `OwnedHostLifecycle`, route metadata, `Bridge`, `Session` and launch/cleanup components. The original public Shizuku constructor retains Android provisioning; an internal constructor supplies temporary sessions and a real Bridge factory for host tests, with a modeled listener instead of provisioning/network. Tests cover late creation after Stop, old-owner and same-owner/new-Session requests, fresh positives, exact captured-target behavior and conditional retirement with repeated epoch text, retaining all four earlier cleanup cases. External Android APIs are doubled. These runs do not establish Android Binder/device execution.

In a Manager-granted device window, the existing host instrumentation runner accepts `operation=projected-home` and an explicit `projectionDisplayId` from that exact backend. It checks Activity placement, isolated fixture preference updates, saved order/availability and icon callback routing without editing the real selection. Assembled-device launch/Home/switch cycles, absence of a system taskbar, physical Phone isolation and native app rendering still need evidence on the supported devices and four target apps.

The Function intention composition uses a binary artifact emitted by the actual Glasses
ProjectionSession test. After running `FunctionIntentTransportTest` with
`EYEPROJECTOR_FUNCTION_PRODUCER_EVIDENCE` set to a private evidence directory, run:

```sh
EYEPROJECTOR_FUNCTION_PRODUCER_EVIDENCE=/absolute/evidence \
  python3 directhost/src/test/launch/run.py --function-intents-only --output-dir /absolute/new/consumer-evidence
```

It decodes those exact producer bytes through the actual Phone Session, observes typed
registration/activation hooks bound to that same Session, exercises actual Phone HID production,
and verifies queued work cannot inherit a replacement context. The hook has no target,
preference or output-contact implementation. This is a host transport composition, not a
physical LAN, firmware or target-app result. The runner also supports `--navigation-only`,
`--stop-race-only` and `--function-failure-only` to exercise retained navigation and retirement.
All modes use the recorded compiled class closure from the focused Gradle build.
