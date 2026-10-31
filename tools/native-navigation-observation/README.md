# Native navigation observation — issue 112

Worker: Worker-EyeProjector-v0.3-112.

This is an isolated observation path for the Owner's requested two-finger single tap (Back), double tap (Lock) and long press (Home). Those names describe requested physical operations, not established event classifiers. At the assigned base, `nativePadKey` maps pad key code 4 and 291 to `DOUBLE`; that is not evidence of two-finger Back. `NativePadContact` cancels on pointer transitions. `NativePadReader` forwards only `KEY_PROG1`/0094 contact edges and retains its separate power owner. None of those production paths is modified here.

The existing test-only `NativeInputObservation` records original key/motion metadata before production handling, with device inventory, lifecycle and dispatch stages. The argument `navigationMetadata=true` additionally installs `NativeNavigationObservation`, which records passive receipt of the seven broadcast actions already named by `NativeFunctionInput` and `NativePadLongTouch`, and samples `PowerManager.isInteractive` in the existing approximately one-second sampling loop. It reads no broadcast extras, Intent data, text, UI content or private configuration. It never aborts a broadcast, changes its result, injects input, opens/grabs an input device, requests permissions or invokes a firmware action.

Broadcast priority matches the existing receivers. Start observation before normal foreground resume when possible. A production receiver at the same priority may abort first; registration order does not establish guaranteed delivery. An absent broadcast is inconclusive, and unknown broadcast actions cannot be discovered by this receiver. A present broadcast records its receipt flags, ordered/sticky state and result code at receipt; it is not a broadcast-send timestamp or firmware-action acknowledgment.

## Manager's device procedure

These are proposed operations for a later explicit reservation. No device, Android build, installed artifact or physical result is established by source/host checks. The Manager owns the physical window, Owner prompts, build slot and exact device/artifact bindings.

1. Allocate a Receiver/AndroidTest build slot and build the exact frozen candidate. Preserve existing installed applications, app data, pairing and the working native pad/power helper. Record source/artifact/installed-byte identities separately. Starting or finishing instrumentation can restart the target process and disturb a live session: prepare the window accordingly and record that boundary. This observer does not launch/finish an Activity or reconnect a session.
2. Under the reservation, retain actual model, OS/API, firmware build and current device route. Read `getevent -lp` to identify the actual pad input node(s) and their key/ABS/MSC capabilities. Do not assume `/dev/input/event1`; compare the Android `device_inventory` rows by actual name/source. Keep serials/endpoints and full local inventory private.
3. Use a separate run ID/capture per operation. Include a no-input baseline, ordinary one-finger single tap, double tap and long press, Function short and long press, then two-finger single tap, double tap and long press. Ask for one physical operation per capture and record what the Owner actually performed. Restore the desired foreground state through normal UI between cases if firmware leaves the app or sleeps. Do not silently reinterpret an operation that could not be performed.
4. Start the existing instrumentation runner with the extra opt-in argument. Its ready marker confirms installation; it does not confirm an Activity is foreground or a gesture is recognized. Bring the normal Receiver UI to the required state after installation, then announce the exact live case to the Owner. In parallel, capture `getevent -lt` from the identified pad node(s) with read-only command/process supervision. This is a second non-grabbing reader; leave the existing helper running. If the shell lacks access, retain that limitation instead of changing permission/firmware.
5. Record the visible destination and sleep/wake effect with the Owner's observation. Retain lifecycle, power samples, app reports, kernel reports, capture boundaries and closed command/report outcomes. For a firmware-before-app claim, supply an actual ordering witness; polling intervals, Android callbacks and the Owner's untimed observation alone cannot establish precise firmware onset. Preserve absent/unobservable app delivery and unknown ownership as limits.
6. Retrieve only the owned trace/report/ready files. Verify observer/listener cleanup in the report and completion of the exact owned raw reader process. Return the reservation to the Manager without stopping or replacing the production pad/power helper.

Example invocation, after the Manager resolves the exact Glasses route and chooses a finite case window:

```sh
timeout --foreground 130s adb -s "$GLASSES" shell am instrument -w \
  -e runId nav_two_single_001 -e operation native-input-observation \
  -e navigationMetadata true -e case two-finger-single -e seconds 90 \
  com.code2hack.eyeprojector.directrg.test/com.code2hack.eyeprojector.directrg.ProjectionInstrumentation
```

The existing 1–660 second measurement allowance is unchanged. Use the selected duration plus setup/teardown for the host command bound. In the same reserved interval, the separately supervised raw command is `adb -s "$GLASSES" shell getevent -lt "$PAD_NODE"`, with `PAD_NODE` taken from the actual inventory. Repeat for another actual pad node only if identified and reserved. No device command is executed by the Python tool.

The outputs remain `files/probe-<runId>.json`, `files/probe-<runId>-native-events.jsonl` and `files/probe-<runId>-native-ready.json`. The summary adds `navigationMetadataEnabled`, the exact `broadcastActions`, `navigationObserverReleased`, and cleanup outcome `navigationUnregister`. `captureComplete` includes removal of this observer when enabled. As before, recorder completion is separate from physical acceptance.

## Host processing and verification

Run on already supplied/retrieved files:

```sh
python3 tools/native-navigation-observation/observe.py \
  --app-jsonl /private/case/native-events.jsonl \
  --getevent /private/case/getevent.txt --case two-finger-single \
  --node /dev/input/eventN --output /private/case/navigation-metadata.json
```

`--node` supplies the actual node only when getevent omitted it. The raw normalization preserves kernel timestamp strings and original EV type/code/value, including MSC scan and MT slot/tracking records. It does not infer a complete finger count from keys or a partial MT trace. Android motion rows preserve the actual delivered pointer count and IDs. Key rows preserve device/source/key/scan/action/repeat and original/receipt times. Broadcast, lifecycle, interactive and production-stage metadata remain separate rows. The parser exports only the documented event fields, counts malformed/unmatched/omitted rows, hashes its inputs, and never exports raw unparsed lines or extra payload fields.

Report occurrence tables retain every original sequence number, including repeated identities. They are possible summaries/companion reports for investigation, not deduplication rules or counts of physical gestures. Streams retain their own order and clock domains; no kernel-to-app or cross-device latency is computed. The original closed instrumentation report and raw command outcome remain required to assess capture completeness. The parser never supplies a physical pass or assigns firmware ownership.

For each physical case, retain a private evidence record with: requested/actual physical action; source/APK/firmware/device identities; captured source/key/scan/action/pointer counts in order; repeated report identities; app lifecycle/power changes; visible effect and observation origin; production-stage activity; any actual ordering witness; firmware/app ownership conclusion and uncertainty. A no-app-report result with visible firmware action is a capability limit to investigate, not proof of successful interception.

```sh
python3 -m unittest discover -s tools/native-navigation-observation -p 'test_*.py' -v
python3 tools/native-navigation-observation/run_host_checks.py --output-dir /private/new-navigation-host-checks
```

The focused check compiles actual test-only observation source against narrow host doubles using the already-cached Kotlin compiler and JDK. It uses no Gradle, Android SDK or device. These checks verify metadata preservation, passive broadcast behavior, exact receiver cleanup and failure accounting. They do not establish Android delivery order, SDK compilation, physical gestures, firmware interception or visible UI behavior.
