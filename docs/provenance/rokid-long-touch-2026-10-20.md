# One-finger long-touch interception — physical trial

2026-10-20, Owner-authorized v0.1 investigation. **PASS for the bounded foreground AI-suppression trial on the recorded Glasses firmware.** This supplies the result missing from the [historical investigation](rokid-input-evidence.md#one-finger-long-touch-observed-failure-followed-by-an-unfinished-retest).

## Device and artifact

- Glasses serial `1906092617103125`, Android 12/API32; fingerprint `Rokid/glasses/glasses:12/SKQ1.240613.001/1.26.011-20260929-150201:user/release-keys`.
- Pad `ROKID,PSOC-TP-R`, `/dev/input/event1`; direct u4090 USB. Phone untouched.
- Test app `com.code2hack.glasseo/.MainActivity`, diagnostics `input_capture=true`, `consume_input=true`, cold launch PID24160/UID10090. The package was absent before this installation.
- Existing APK: SHA-256 `42474ec98d7c0ee70234abc0288c9c9b8e0048482099142753c34f5f45a5fbad`, 10,552,159 bytes. Installed base APK bytes matched that hash.
- Artifact location: clean historical issue-15 worktree at `96a4f6697ee8afc4dd8b516fd46551d3218819b4`. `BroadcastInterception.kt` and `GlasseoApplication.kt` match refreshed main `5f9d23512359aaaef7d5e1ba203ea7322cae433b`. No fresh rebuild or complete reproducible APK-to-source claim is made.

The reused interceptor dynamically registers `ACTION_AI_START` at priority `Int.MAX_VALUE`, checks ordered delivery and absence of `FLAG_RECEIVER_NO_ABORT`, sets canceled result and aborts the broadcast. [Exact source](https://github.com/code2hack/Glasseo/blob/5f9d23512359aaaef7d5e1ba203ea7322cae433b/app/src/main/java/com/code2hack/glasseo/BroadcastInterception.kt#L55)

## Actual procedure and results

After the Owner confirmed readiness, bounded raw-input, app/system-log and focus capture ran from **09:03:16.139465 to 09:04:12.188912 UTC**, 56.049 seconds. The requested action was one two-second one-finger hold. The recorder actually observed two complete physical holds; neither was synthesized.

| Observation | First hold | Second hold |
| --- | --- | --- |
| Raw KEY_PROG1 DOWN, device seconds | 150894.076552 | 150900.636351 |
| Raw KEY_PROG1 UP, device seconds | 150897.183649 | 150903.747472 |
| Raw held interval | 3107.097 ms | 3111.121 ms |
| App broadcast receipt, elapsed device seconds | 150894.381625607 | 150900.939592688 |
| Delivery | Ordered, flags `0x50000010` | Ordered, flags `0x50000010` |
| App cancellation log | `abortAttempted=true`, resultCode0 | `abortAttempted=true`, resultCode0 |
| Android broadcast history | `resultAbort=true`; delivered to Glasseo; AssistServer remained Pending | Same |

Both receipts had `wizardStep=null`: this was foreground interception outside the qualification wizard. All 45 focus samples retained the same Glasseo MainActivity/PID, Awake state and brightness17. The Activity received the initial contact83 pairs, with no held KEYCODE_PROG_BLUE186 callback. No AI dialog, Glasseo pause/force-stop, or AssistServer AI handling was observed in the retained trace.

The authenticated Owner observation was **neither AI appeared nor wake sound played**. Ordinary touch-feedback playback occurred, so this is not a claim of complete silence. Before/after audio snapshots and logs showed no new AI capture activity; continuous external acoustic/microphone measurement was not performed. AssistServer/CXR traffic in the window concerned OTA/battery updates, with reported transmitted audio bytes0.

Framework `launchRokidAI: true` lines still occurred: policy attempted the launch by sending the broadcast. Android's `resultAbort=true` history, downstream receiver remaining Pending, stable focus and Owner observation distinguish that attempt from an actual assistant launch.

## Teardown and coordination

The current donor Manager received coordination request `EYEPROJECTOR-V01-LONG-TOUCH-20261020-COORD-01` and returned correlated receipt `MGR-EYEPROJECTOR-RG-LONG-TOUCH-RECEIPT-341`, confirming no conflicting device operation. No source/issue ownership transferred.

All owned recorders closed. The test package was force-stopped, its active interceptor removed, and the original Rokid launcher restored and verified focused on the same firmware. USB access subsequently disappeared; removal of the newly installed test package failed with device-not-found. It remains installed and stopped. No shared ADB/network reset or OTA action was issued. Actual return and route loss were reported to the donor Manager as `EYEPROJECTOR-V01-LONG-TOUCH-20261020-RETURN-01`.

## Evidence and scope

Private evidence root: `/home/code2hack/.local/state/eyeprojector/experiments/long-touch-20261020T085940Z`. The following hashes identify retained local files; private logs are not copied into the public tree.

| File relative to evidence root | SHA-256 |
| --- | --- |
| `capture-01/raw-event1.txt` | `dc5f508dba116cd2540f00f994910679009422a983d0b0969c43732c198fd087` |
| `capture-01/logcat.txt` | `753041a5a1933722601b04c52e9372235ecaea81673609d55f7df0ba100fd0e1` |
| `capture-01/focus.txt` | `6055178b2fdbd3928c0be9cfb5986bc0b456b11095c157ba16970c6c8e19239d` |
| `capture-01/after.txt` | `baa11f2d87f984ce5bf4d8d085d17745511002bcc964d0791af6597690b63b24` |
| `owner-observation.json` | `aedf1a147fdf7fdec413c8b29fe1cd2e0ab68e4adf5357ae01479b1df05183e1` |
| `result.json` | `33fb96cf7d24ca56434c8603eecffa2ee8dea4ba4d2d232cdfa3192d2f8f4f1c` |

A separate read-only inspection reproduced both physical durations and checked Android's abort history, all 45 focus samples, audio qualifications and the original 11 capture hashes.

This establishes current-firmware suppression with the tested historical APK. EyeProjector integration, a custom app action, continuous hold/release delivery, background/restart behavior, stock long-touch behavior after removal, other gestures and sustained reliability remain untested. No version implementation, issues, release or live Manager/watchdog activation was started.
