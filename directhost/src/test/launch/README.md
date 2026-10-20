# Projected Home verification

With JDK 17 configured, run ordinary affected Gradle checks first, then:

```sh
python3 directhost/src/test/adapter/run.py --output-dir /private/evidence/bootstrap
python3 directhost/src/test/launch/run.py --output-dir /private/evidence/session-launch
```

The bootstrap suite compiles the actual Phone adapter and checks launch/display replies, malformed and dead-service results, fresh binding identity and Stop during a blocked launch. The Session suite compiles actual `ShellMain`, launch adapters and release/input/scale components with external Android socket/JSON doubles. It drives the production Session's cancellation callback and teardown, helper-release failure/retry, pending launch versus fresh Session, and teardown while the old control lock is occupied. The JVM `ProjectedAppLaunchTest` also drives real host subprocess output drainage, timeout and Stop/reap. These are host results, not Android Binder, target-app placement, screen, optical or physical-input results.

The pinned scrcpy capture logs its new display before the first stream session metadata. `ShellMain` reads only its own child's current log, opens Home once and disables that virtual display's system decorations. Launches are fixed argument vectors through the existing Session owner. `cmd activity start-activity` is the direct operation behind `am start`, avoiding an extra shell-script process. Target launches retain native tasks and themes. The Home Activity has a separate task affinity from the Phone panel.

The foreground parser follows the per-display focused `Resumed:` summary in AOSP [Android 12L RootWindowContainer](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-12.1.0_r1/services/core/java/com/android/server/wm/RootWindowContainer.java) and [Android 15 RootWindowContainer](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/core/java/com/android/server/wm/RootWindowContainer.java). The fixed query is `dumpsys -t 2 activity activities`: Android 15's `activity -d` filter is absent from the Android 12L dump parser. Only the exact backend display section's focused summary is used. Task-local resumed records and global summaries are ignored. An unknown format or absent section yields no observed package. OEM/version qualification remains a device check.

Local Binder IDs 7 and 8 expose `launchApp(requestId, packageName)` and `projectionDisplayState()`. `ProjectionDisplayState` separates the actual backend display and UUID/child generation, requested package, command status/exit/component and freshly observed foreground package. `STARTED` means the platform command reported `Status: ok`; it does not imply the observed package matches or that physical video/input works. A timed-out command may already have submitted an Activity start. Stop closes queue admission and reaps only the owned helper before existing transport/stock-child release. Helper-release failures remain retryable and are reported after transport cleanup.

In a Manager-granted device window, the existing host instrumentation runner accepts `operation=projected-home` and an explicit `projectionDisplayId` from that exact backend. It checks Activity placement, isolated fixture preference updates, saved order/availability and icon callback routing without editing the real selection. Assembled-device launch/Home/switch cycles, absence of a system taskbar, physical Phone isolation and native app rendering still need evidence on the supported devices and four target apps.
