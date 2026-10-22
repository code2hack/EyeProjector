# Starting projection from the Phone app

Research for the v0.1 SPEC discussion, 2026-10-20. The Owner requires launchers on both Phone and Glasses and selected **Shizuku** for Phone-app startup. The alternatives below retain the reasoning considered before that choice.

## Verified current behavior

The current [Phone Activity](../../directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/HostActivity.kt) implements status and Stop. [ShellMain](../../directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/ShellMain.kt) requires shell UID 2000 and starts the pinned scrcpy backend through `app_process`. The Android source baseline is `9ddf2fa3fe71b5b0edeac7086e76f8fa8f37c9dd`.

The pinned [scrcpy developer documentation](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/doc/develop.md#privileges) explains its shell-process capture privileges and hidden input APIs. Android assigns ordinary apps their own [sandboxed UID](https://source.android.com/docs/security/app-sandbox). Starting an ordinary service does not supply the shell identity this implementation requires.

## Candidate approaches

| Approach | Startup path and tradeoff |
| --- | --- |
| Shizuku integration | The Phone UI can bind a shell-identity UserService after the user authorizes EyeProjector. An adapter could run the existing bridge there. This adds an installed Shizuku dependency; its UserService is not an ordinary app process. [API](https://github.com/RikkaApps/Shizuku-API#userservice) |
| Embedded wireless-ADB client | EyeProjector could handle pairing and connect to the Phone's own ADB daemon, then start its bridge. This avoids a separate Shizuku app but adds pairing/client integration. This is an engineering proposal supported by [AOSP's wireless-ADB protocol](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/adb_wifi.md) and Shizuku's [on-device client/bootstrap](https://github.com/RikkaApps/Shizuku/blob/master/manager/src/main/java/moe/shizuku/manager/receiver/BootCompleteReceiver.kt). |
| Public MediaProjection backend | An ordinary app can request screen/app capture, with consent for each session and a foreground service. This would replace the current capture path; app placement and native control need separate designs. [Android capture API](https://developer.android.com/media/grow/media-projection), [display launch policy](https://source.android.com/docs/core/display/multi_display/activity-launch#security-restrictions) |

Shizuku documents computer-free wireless startup on Android 11+, normally retained pairing and startup again after reboot. Its current source also has a conditional boot path requiring Android 13+, an additional settings grant and prior ADB startup. These are setup/recovery considerations, not a guarantee of automatic EyeProjector recovery. [Setup guide](https://shizuku.rikka.app/guide/setup/#start-via-wireless-debugging), [boot implementation](https://github.com/RikkaApps/Shizuku/blob/master/manager/src/main/java/moe/shizuku/manager/receiver/BootCompleteReceiver.kt)

## Selected product direction

The Owner chose Shizuku over embedding wireless-ADB pairing. After documented on-device setup and authorization, the user starts and stops projection from the Phone app through Shizuku. The Phone and Glasses both provide app launchers, while projected apps execute on the Phone and media/control remain direct between the devices. These decisions are recorded in [SPEC.md](../../SPEC.md#phone-setup-and-startup).

The Phone [home-screen widget](../../SPEC.md#phone-home-screen-widget) also offers **Start / Stop** through that same startup/session path, alongside **Panel off**. Missing setup or authorization directs the user into the Phone app's relevant setup/status screen.

This is source/document research. No startup adapter, dependency or device setting was changed. Exact Phone compatibility, setup and reboot recovery remain unverified.
