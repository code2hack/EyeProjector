# Bluetooth qualification packet — v0.1 / issue #22

Author: Worker: Worker-EyeProjector-22. Source basis: `b6675e0de27cb25cca05643fd619212ce4b4e3fd` on `version/v0.1`. This packet prepares later qualification; it contains no device execution or physical result. Implementations #10, #14 and #21 are integrated at that basis, while their physical/full acceptance remains open.

Use [matrix.csv](matrix.csv) for case coverage, [run-template.json](run-template.json) for each later run, and [measurement-template.json](measurement-template.json) with the [measurement method](method.md) for physical AV accounting. Keep filled runs, raw recordings, identifiers and application-private evidence in the assigned private evidence location. These public files contain only sanitized templates.

## Evidence and outcome rules

- All later actual facts start as JSON `null` (CSV literal `null`); every case starts `NOT_EXECUTED`. Fill facts only from the observed run. The published source basis is not the later installed/artifact identity.
- Record workflow availability separately: `available` means the identified feature was observed; `unsupported` means evidence establishes that the app/device combination lacks it; `unavailable` means an observed temporary/account/permission/service condition prevents use; `unknown` means it has not been established. Missing inventory and historical installation are not evidence of support or current login.
- Execution status is separate: `NOT_EXECUTED`, `EXECUTED`, `FAILED` or `DEFERRED`. Preserve operation failures, unavailability and ambiguous observations; do not turn them into a skipped pass. Record a proposed action separately from its actual execution and result.
- Record the action origin for every step: genuine Glasses touchpad/function/HID, Phone system UI, injected input, or host command. Injected input does not establish genuine Glasses interaction. A command returning successfully does not establish target-app behavior.
- Output route, input route and route API inventory are separate observations. A list of Bluetooth devices, enabled Audio/Calls toggles, a speaker route, or a recorder button does not establish the target app's active microphone. Record microphone identity as `unknown` when it cannot be corroborated.
- Host checks, synthetic calculations and MediaPlayer/decoder callbacks cannot pass hearing, microphone, optical timing, genuine input, route-loss resilience or physical synchronization. Measurement validity, relation to the 100 ms target, Reviewer verdict, Manager implementation acceptance and full issue acceptance are separate records.

## Reuse the owned fixtures

`directhost/.../AvFixtureActivity.kt` loops the owned 12 s flash/1 kHz clip with ordinary Android MediaPlayer, at player volume `.25f`. Its latest-only `files/av-fixture.json` records display/playback and source-clock diagnostics every second; it is not an optical/audio measurement. Stopping the Activity stops playback and sampling. `hostprobe/.../FixtureActivity.kt` already provides ordinary Play/Stop controls for the same clip. `hostprobe/.../ToneActivity.kt` provides a separate quiet 440 Hz media/focus comparison with no microphone; its optional `focus=true` changes focus behavior and must be identified in any later use.

Both `directhost/src/main/res/raw/owned_flash_beep.mp4` and `hostprobe/src/main/res/raw/owned_flash_beep.mp4` have SHA256 `fa5f9fc73da052322b929b1e10a1d77765becbdb735a23d157617e965b28076a` at the stated basis. `tools/generate-fixture.sh` authors a nominally simultaneous 100 ms full-frame flash and 1 kHz pulse each second: 30 fps, 480×640 H.264, 48 kHz mono AAC, 12 s. Reuse the existing assets; the generator replaces both copies. Nominal source simultaneity and equal container start times are not measured zero physical offset.

The active source passes `audio=false` in `ShellMain.kt`; protocol v4 retires forwarded-audio channels; `ProjectionSession.kt` reports native Bluetooth and physical AV unmeasured. Receiver callback metrics are packet-read-to-callback receipt on the Receiver monotonic clock. Source media timestamps, Receiver timestamps and an external recorder clock are distinct domains without a measured mapping. This packet changes none of those paths and introduces no microphone transport or video correction.

## Later run preparation and baseline

The current assignment has no device, ADB, installation, playback, microphone, Bluetooth, volume, call, message, credential or reservation operation. The stages below are proposed work for a separately assigned Manager-reserved Phone + Glasses + audio-route window, using that window's existing grants. No Owner prompt or alarm is requested by this packet.

Before that work, fill the actual assignment/request, reservation, participants, exact candidate/source and artifact hashes, target device identities, current Android/firmware versions, current user/profile and installed packages. Record Phone and Glasses pairing, route/volume/focus state and existing projection/private-app state before changing it. Preserve current logins, conversations, drafts, recordings, chosen apps, EyeProjector pairing, saved settings and installed applications according to the later assignment. If an artifact is unavailable, record it as unavailable rather than inventing a hash.

For each possible installation, record package, exact APK SHA256, version/code/signature compatibility, baseline installed artifact and the concrete return method. An APK replacement and any return installation are separate writes. A compatible `install -r` does not itself demonstrate preservation. A later run must document what can actually be restored; do not assume data backup, downgrade, account access, uninstall or clearing app data is available. Prefer the already-installed qualifying artifact where the assigned run permits it. The source-only packet neither installs anything nor adds permissions.

Inventory ChatGPT (`com.openai.chatgpt`), Paseo (`sh.paseo`), Comet (`ai.perplexity.comet`) and WeChat (`com.tencent.mm`) anew. Identify each concrete playback, recording, ASR and call workflow, feature entry point, permission and availability condition. The four workflow rows per app are discovery/qualification slots, not claims that each app offers each feature. Login, permission changes, account actions and actual calls/messages require coverage by the later assignment; no such operation is authorized here. Use owned/non-private material where a feature is available under that assignment.

The read/write table describes the proposed effects, not completed actions:

| Stage | Intended reads | Intended writes / effects | Evidence and return |
| --- | --- | --- | --- |
| Baseline | Candidate/APK hashes; device/build/user/package identities; existing session, route, pairing, volume and permission state | Private evidence files only | Keep original dumps privately, with capture time/hash and exit status. |
| Artifact preparation | Baseline installed package and artifact compatibility | Only an exact assigned installation, if needed | Record installation output, installed identity, preservation checks and return artifact/method. |
| Projection and fixture | Current session/display and fixture status | Ordinary session start; owned clip playback; native inputs | Record actual display, launch origin, physical capture and any failed launch/decoder outcome. |
| App workflows | Observed feature, permission, active output/input corroboration | Assigned playback/record/ASR action; system volume/route action; calls only if specifically covered later | Keep per-app results; distinguish no feature from unavailable route and failure. Preserve private content. |
| Loss / microphone unavailable | Video changes, genuine control response, session and actual audio/input state before/during/after | Ordinary system Bluetooth disconnect/reconnect or an assigned unavailable-input setup | Record exact transition and real video/control observations; a live socket is insufficient. Restore baseline route/volume. |
| Stop / Disconnect / Forget | Session resources, EyeProjector pairing, system pairing and route before/after | Invoke each product action in separate recorded cases; Forget changes EyeProjector peer identity | Verify actual release and independently managed Bluetooth. Record any normal target-app playback effect caused by display closure. Restore EyeProjector state only by the later assigned route. |
| Return | Compare installed, private-app, route, volume, permissions, pairing and session states against baseline | Stop owned playback/capture; perform only assigned restoration writes | Record each difference, restoration result and unresolved state; release resources through Manager coordination. |

## Proposed command packet

The following are **non-executable documentation examples**. Angle-bracket placeholders must be resolved in the later run record; they are not commands to paste now. Every later attempt records exact expanded command, authorization, start/end, exit status, raw stdout/stderr and hashes. Commands alone do not supply optical/hearing/input evidence. Dumps may contain identifiers/private state and belong in private evidence, not this directory.

```text
# Host artifact reads; select only artifacts from the exact assigned source/build.
git rev-parse HEAD
sha256sum <PHONE_APK> <GLASSES_APK> <OPTIONAL_HOSTPROBE_APK>

# Phone and Glasses baseline reads; run separately for each reserved serial.
adb -s <DEVICE_SERIAL> shell getprop ro.product.model
adb -s <DEVICE_SERIAL> shell getprop ro.build.fingerprint
adb -s <DEVICE_SERIAL> shell getprop ro.build.version.release
adb -s <DEVICE_SERIAL> shell getprop ro.build.version.sdk
adb -s <DEVICE_SERIAL> shell am get-current-user
adb -s <DEVICE_SERIAL> shell dumpsys display
adb -s <DEVICE_SERIAL> shell dumpsys audio
adb -s <DEVICE_SERIAL> shell dumpsys media.audio_policy
adb -s <DEVICE_SERIAL> shell dumpsys bluetooth_manager

# Current installed identity for each matrix app and each EyeProjector/fixture app.
adb -s <PHONE_SERIAL> shell dumpsys package <PACKAGE_NAME>
adb -s <PHONE_SERIAL> shell pm path --user <USER_ID> <PACKAGE_NAME>
adb -s <GLASSES_SERIAL> shell dumpsys package com.code2hack.eyeprojector.directrg

# Optional future writes, only when the later assignment covers exact installation.
adb -s <PHONE_SERIAL> install -r <EXACT_PHONE_APK>
adb -s <GLASSES_SERIAL> install -r <EXACT_GLASSES_APK>
adb -s <PHONE_SERIAL> install -r <EXACT_HOSTPROBE_APK_IF_REQUIRED>

# Launch the existing looping owned fixture on the observed projection display.
# This launch is host-command origin; it does not establish genuine Glasses launch.
adb -s <PHONE_SERIAL> shell am start --user <USER_ID> --display <PROJECTION_DISPLAY_ID> -n com.code2hack.eyeprojector.directhost/.AvFixtureActivity

# Private software status reads only if the installed debuggable build permits run-as.
adb -s <PHONE_SERIAL> exec-out run-as com.code2hack.eyeprojector.directhost cat files/av-fixture.json
adb -s <PHONE_SERIAL> exec-out run-as com.code2hack.eyeprojector.hostprobe cat files/tone-status.json

# Offline analysis of a filled physical/synthetic measurement document.
python3 tools/bluetooth-qualification/analyze.py <PRIVATE_MEASUREMENT_JSON>
# Explicitly synthetic demonstration; never a physical result.
python3 tools/bluetooth-qualification/analyze.py tools/bluetooth-qualification/examples/synthetic-offsets.json
```

`run-as` failure is retained as a diagnostic limitation; it does not justify changing permissions. The ordinary product/system UI supplies Start/Stop/Disconnect/Forget, volume, route and pairing operations under the later assignment. Record exact controls and action origin rather than inventing app-private intents or force-routing audio. The packet intentionally supplies no call/message command, microphone service, reset, unpair, app-data clear or credential operation.

## Physical AV and workflow procedure

Use external, calibrated observation of the Glasses' projected flash and emitted beep on one known capture timebase. Record capture apparatus, optical location, microphone location/distance, timebase, sample/frame rate, exposure, detection method, calibration, uncertainty and raw file hashes. Positive offset means **audio arrival minus visual arrival**. Do not subtract a nominally zero source offset. If a measured source/baseline comparison is useful diagnostically, retain the actual physical output result separately.

Planned sampling is three independent 12 s captures of the existing clip, retaining every expected pulse and every missed/ambiguous event, including startup/loop behavior observed in those intervals. This is a sampling plan, not a session lifetime or acceptance-duration restriction. Pair events by explicit clip cycle/pulse identity; a missing periodic event must not silently shift pairing by a second. Record repeated-run variation and unresolved events. A different actual capture count/duration is reported honestly, with the reason and its evidential limit.

The analyzer reports a measurement's relation to the existing 100 ms target and preserves validity limits. It does not grant physical acceptance. Capture uncertainty overlapping the target boundary remains inconclusive. Noisy, low-resolution, missing or software-only data retains its limitation. Use [method.md](method.md) for the actual accounting fields and equations.

For each available target-app recording/ASR workflow, corroborate which physical microphone contributes while that workflow is active. Use distinguishable owned speech or non-private test material near the separated Glasses and Phone microphones, retaining the actual acoustic method and uncertainty. App-provided active-input diagnostics may support the result; device API inventory alone cannot. Where attribution is ambiguous or evidence is unavailable, keep actual microphone unknown. Playback and calls have separate output/input evidence and may use different native profiles.

For Bluetooth loss and microphone-unavailable cases, observe changing projected content and an actual target-widget response to genuine Glasses input before, during and after the transition. Record whether the audio/input feature fails independently, the actual system route, recovery and any session failure. An injected diagnostic step remains labelled injected. For each Stop/Disconnect/Forget case, compare actual projection-resource release, EyeProjector peer state, system Bluetooth pairing and available routing separately. Normal app playback effects from closing its display must be recorded without attributing them automatically to Bluetooth management.

## Retained limits and review boundary

Issue #21's earlier static-content/header-timeout and first-scale failures remain part of the qualification context. Its integrated implementation and a changing-content fixture cannot erase those failures or establish their cause; retain the exact prior evidence references supplied by Manager in the filled run. Current sustained/physical video acceptance remains unproved until observed.

Historical documentation drift exists at this source basis: `docs/research/bluetooth-audio.md` implementation-impact text and `SPEC.md` bootstrap snapshot still describe forwarded audio/audio-clock scheduling, while active source and the current audio contract are Bluetooth-only. This is reported for Manager disposition, not permission to alter protected documents or restore forwarding.

Preserve an evidence manifest with exact source/base/head, artifacts, raw commands/captures, calibration files, expanded case results, failures and analysis tool/input/output hashes. A distinct Reviewer assesses the exact source-host candidate and later evidence. Manager implementation integration, physical/full issue acceptance, dependency acceptance and assembled-version acceptance remain separate. This packet cannot close #22 or self-accept a result.
