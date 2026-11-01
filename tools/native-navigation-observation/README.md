# Native navigation observation — issue 112

Worker: Worker-EyeProjector-v0.3-112.

This isolated observation path retains the earlier tap/hold matrix and adds the Owner-directed two-finger swipe Back/Home comparison. The Owner's physical distinction between one-finger and two-finger swipes is accepted; the remaining question is current app routing/delivery. Guide timing and event labels are not physical classifiers. At the assigned base, `nativePadKey` maps pad key code 4 and 291 to `DOUBLE`; that is not evidence of two-finger Back. `NativePadContact` cancels on pointer transitions. `NativePadReader` forwards only `KEY_PROG1`/0094 contact edges and retains its separate power owner. None of those production paths is modified here.

The existing test-only `NativeInputObservation` records original key/motion metadata before production handling, with device inventory, lifecycle and dispatch stages. The argument `navigationMetadata=true` additionally installs `NativeNavigationObservation`, which records passive receipt of twelve exact broadcast actions and samples `PowerManager.isInteractive` in the existing approximately one-second sampling loop. The option remains off by default. It reads no broadcast extras, Intent data, text, UI content or private configuration. It never aborts a broadcast, changes its result, injects input, opens/grabs an input device, requests permissions or invokes a firmware action.

The original seven actions come from `NativeFunctionInput` and `NativePadLongTouch`:

```text
com.android.action.ACTION_SPRITE_BUTTON_DOWN
com.android.action.ACTION_SPRITE_BUTTON_UP
com.android.action.ACTION_SPRITE_BUTTON_CLICK
com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK
com.android.action.ACTION_BOLON_PAIRING
com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS
com.android.action.ACTION_AI_START
```

Five additions come from the current1.28.016 `PhoneWindowManager` Settings, two-finger single/double tap and two-finger swipe Back/Forward senders. The last two extend the previous ten-channel observer:

```text
com.android.action.ACTION_SETTINGS_KEY
com.android.action.ACTION_TWO_FINGER_SINGLE_TAP
com.android.action.ACTION_TWO_FINGER_DOUBLE_TAP
com.android.action.ACTION_TWO_FINGER_SWIPE_BACK
com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD
```

Current-build provenance is the retained1.28.016 `/system/framework/services.jar`, SHA-256 `ec3ae29ad1c39c69a2b80d29b7c98979ec6b5aec70ca947db3195957a8341603`, and its `classes2.dex`, SHA-256 `71738098b098c89b2718410734fac4635d2fd6780d50e48e4d3ce9be01f6b49b`. The selected policy/handler outputs establish these five senders statically. Current `framework.jar` SHA-256 `4fed239eef7b25902a9a81c3fc5ce92d6ec51b11b961668137ca18336b4e0118` supplies the matching KeyEvent constants. Normal policy decompilation left `interceptKeyBeforeQueueing` unsupported; retained same-class fallback instructions and the handler/accessor chain support the key/message links. This is current-build static evidence, not runtime delivery, physical direction, brightness ordering or suppression proof. Historical1.27 policy is not used for current applicability. Earlier seven-channel captures did not assess the first three additions; ten-channel captures45/50 did not assess either swipe action.

Broadcast priority matches the existing receivers. Start observation before normal foreground resume when possible. For ordered channels, an earlier production receiver may abort before observation; registration order does not establish guaranteed delivery. The five added senders use unordered `sendBroadcastAsUser(intent, UserHandle.CURRENT)`, flags `0x50000000` (plus `8` under their debug condition), and no explicit receiver-permission argument or event/finger-count payload in those sender bodies. This does not prove ordinary-app delivery or a way to suppress a default action. Unordered receipt supplies no ordered-broadcast abort/result interception seam.

Missing receipt remains inconclusive, and unknown actions cannot be discovered by this receiver. A present broadcast records its action label, flags, ordered/sticky state and result code at receipt (`null` when unordered); it is not a sender timestamp, physical finger identity, Home/sleep acknowledgment or proof of interception. Upstream gesture rules and current downstream consumers remain unresolved; the action names alone do not assign firmware ownership.

## Opt-in on-Glasses batch guide

The separate `gestureGuide=true` argument adds an automatically advancing test instruction label to the existing Receiver decor drawing overlay. It is off by default. It adds no Activity, normal layout child, system overlay window, permission, input listener or production change. The label is large white text on black, nonfocusable, nonclickable and nonselectable; the underlying projection dimensions and input routing are retained. A guide-enabled report explicitly records whether its own label was drawn.

After the observation sink is active and a resumed, focused, interactive Receiver has drawn the label, the guide shows a 10-second preparation countdown followed by six automatic 20-second instruction slots:

| Prompt ID | Instruction |
| --- | --- |
| `one_single` | One finger: tap once |
| `one_double` | One finger: double tap |
| `one_hold` | One finger: hold about 2 seconds, then release |
| `two_single` | Two fingers: tap once |
| `two_hold` | Two fingers: hold about 2 seconds, then release |
| `two_double` | Two fingers: double tap last; screen may turn off |

Each slot says to do it once, then wait. No tap, Back, Done or chat acknowledgment advances the guide. Only the guide's instruction clock pauses when its own label is not shown, the Receiver loses focus/resume or sampled interactivity, or the exact observation sink is no longer active. It resumes the remaining instruction time on a visible Receiver, including after Activity recreation. A late update never skips prompts that have not been shown. The existing recorder measurement window continues unchanged; the guide never wakes/relaunches the app or extends/restarts a capture. The expected last screen-off can leave the last instruction paused or incomplete, which is reported honestly.

All UI changes use the recorder's main-thread/lifecycle path and existing roughly 50 ms loop. Interactivity is read on its existing roughly one-second instrumentation sampling loop, with the sample clock retained. Visibility requires an actual own draw, an eligible host decor, and the attached label's visible flags and bounds intersecting that host. It does not require the overlay child to have ordinary ancestor `isShown` or global-visible-rectangle semantics. The first actual own draw per prompt on each surface records `draw_attempt`, including primitive own/host eligibility facts; `labelHierarchyShown` is diagnostic only. These additional facts remain in the original JSONL; the existing normalized parser retains its established guide fields.

Only the owned label and host eligibility, draw/visibility and bounds are inspected. `guide` rows export primitive prompt IDs, countdown/step start/end, visibility, pause/resume, errors and cleanup with the normal receipt clocks. Authored label text and external UI text/trees/screenshots are not exported. These rows describe instructions and Android draw callbacks, not physical gesture boundaries, user completion, finger observations, optical visibility or input acknowledgment. Host regression checks model an overlay whose ordinary hierarchy visibility queries are false despite actual draw dispatch; device rendering still requires a separately authorized run.

For a later Manager-reserved guided run, supply both `-e navigationMetadata true -e gestureGuide true`, one run ID and a finite window long enough for the 130 visible instruction seconds plus setup/pauses. Root asks readiness before startup; the on-Glasses guide supplies the six instructions without per-step chat gates. Exact installation/runtime and a human-visible result still need their separately reviewed and reserved window. On completion, failure or lifecycle loss, only the exact owned label/callback is removed; guide cleanup/failure status is included in the recorder report. No guide-owned handler or delayed callback is scheduled.

For the targeted repeat batch, also supply `-e gestureGuideCase two-single-repeat`. This opt-in mode immediately draws one static instruction: **TWO FINGERS: ONE TAP / Repeat 10–15 times / About 3 seconds apart / Then stop.** It has no preparation countdown, recording deadline or other gesture slots. The same actual-own-draw/host eligibility and noninteractive overlay cleanup apply; hiding or pausing the Receiver removes the label, and a resumed eligible host shows the same instruction. Original `guide` rows identify `guideMode=manual_repeat` and `promptId=two_single_repeat`; step/countdown fields are null, and instruction-plan completion stays false. No attempt count, per-tap recognition or physical completion is inferred. Omitting the case argument preserves the original automatic six-step guide.

For the four-swipe comparison, use `-e gestureGuide true -e gestureGuideCase swipe-navigation` with `navigationMetadata=true`. It retains the 10-second preparation and then four automatic 20-second slots, each saying to perform once and wait:

| Prompt ID | Instruction |
| --- | --- |
| `one_swipe_left` | ONE FINGER: SWIPE LEFT |
| `one_swipe_right` | ONE FINGER: SWIPE RIGHT |
| `two_swipe_left` | TWO FINGERS: SWIPE LEFT |
| `two_swipe_right` | TWO FINGERS: SWIPE RIGHT |

Original rows identify `guideMode=automatic_four_swipes` and `stepCount=4`. Each prompt requires its own draw under the same active-sink/foreground/interactive host conditions. Pauses retain remaining instruction time; late ticks never skip unseen prompts. The existing full-six and static-repeat cases, finite recorder bound and optional Owner-stop behavior are unchanged. No per-step chat/tap acknowledgment advances the guide. Guide rows do not attest physical attempts, direction or once-only routing. Builtin brightness coexistence is Owner-accepted; this passive observer makes no suppression attempt or extra sleep call.

Optional `-e stopOnRequest true` creates exactly `files/probe-<runId>-native-stop.request`, initially empty, and publishes that filename in the ready marker and report. Only after authenticated Owner Done, the reserved operator writes a nonempty constant such as `OWNER_DONE` to that exact run-owned file. The recorder checks file length on its existing roughly 50 ms instrumentation loop; it reads no payload or external UI text and injects no input. It records `capture_boundary` state `owner-stop-request`, actual receipt/duration and `measurementStopReason`, then runs normal cleanup/export. An early stop keeps full-window `windowCompleted` and `captureComplete` false; separate `ownerStoppedCaptureComplete` requires successful sink/listener/UI cleanup, export, matching row counts and no failures. It does not attest any gesture. With this option omitted, no stop file is created or polled and existing bounded-window completion is unchanged. Preserve this fourth exact probe file with the original reports for separately allocated cleanup; do not touch any other run's request.

## Manager's device procedure

These are proposed operations for a later explicit reservation. No device, Android build, installed artifact or physical result is established by source/host checks. The Manager owns the physical window, Owner prompts, build slot and exact device/artifact bindings.

1. Allocate a Receiver/AndroidTest build slot and build the exact frozen candidate. Preserve existing installed applications, app data, pairing and the working native pad/power helper. Record source/artifact/installed-byte identities separately. Starting or finishing instrumentation can restart the target process and disturb a live session: prepare the window accordingly and record that boundary. This observer does not launch/finish an Activity or reconnect a session.
2. Under the reservation, retain actual model, OS/API, firmware build and current device route. Read `getevent -lp` to identify the actual pad input node(s) and their key/ABS/MSC capabilities. Do not assume `/dev/input/event1`; compare the Android `device_inventory` rows by actual name/source. Keep serials/endpoints and full local inventory private.
3. Without the optional guide, use the separately allocated operation/case procedure: include a no-input baseline, ordinary one-finger input, Function references and the requested two-finger operations, retaining what the Owner actually performed. With the guide enabled, use the explicitly allocated default-six, four-swipe or static-repeat case described above, with no per-step acknowledgment gate. Any foreground restoration or further run needs the applicable Manager grant; do not silently reinterpret an operation that could not be performed.
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
python3 tools/native-navigation-observation/run_guide_host_checks.py --output-dir /private/new-guide-host-checks
```

The focused checks compile actual test-only observation/guide source against narrow host doubles using the already-cached Kotlin compiler and JDK. They use no Gradle, Android SDK or device. They verify metadata preservation, passive broadcasts, automatic plan ordering, own-draw/visibility/sink gating, pause/resume, noninteractive overlay/root geometry, exact cleanup, late callbacks and failure accounting. They do not establish Android delivery order, SDK compilation, physical gestures, firmware interception or visible UI behavior. The parser's guide timing summary retains the same `UNKNOWN` ownership and `UNESTABLISHED` physical classification.
