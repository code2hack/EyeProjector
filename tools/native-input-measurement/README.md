# Native input observation for issue 66

Worker: Worker-EyeProjector-v0.2-66.

`operation=native-input-observation` records one finite, named case of actual events delivered to the Receiver process. It injects no input, does not launch/finish an Activity, does not press Connect/Stop, and does not launch or reposition a Phone app. It does not reuse the old `input-observation` branch's synthetic-tap, new-session or changing-video assumptions. A static or no-input case can produce a complete recorder result without establishing working physical input.

The runner must be used in a later Manager-reserved device window with the exact source/APK/device bindings and genuine Owner actions. Starting Android instrumentation can restart the target process; Root must account for that before the case and prepare/observe normal Receiver UI and session state. This helper makes no Activity or activation-control calls. In particular, it does not finish a Receiver Activity, whose normal destructor can disconnect its activation. Instrumentation startup/finish preserving a live session remains unmeasured; record those boundaries separately. Preserve the Owner's current Phone placement; no target-display launch is part of this tool.

## Per-case procedure

Use a new `runId` accepted by the existing instrumentation runner (`[A-Za-z0-9_-]{1,48}`). Prepare one case at a time: no-input baseline, single/double/triple taps, several quick taps, forward/backward swipe, tap-then-swipe, stationary hold/release, held head movement, local control, and focus/context cancellation. The case name describes the requested Owner action; it is not a classifier or proof of what occurred.

With Root's later exact Glasses route assigned, an example finite 90-second invocation is:

```sh
timeout --foreground 130s adb -s "$GLASSES" shell am instrument -w \
  -e runId native_single_001 -e operation native-input-observation \
  -e case single-tap -e seconds 90 \
  com.code2hack.eyeprojector.directrg.test/com.code2hack.eyeprojector.directrg.ProjectionInstrumentation
```

This command is a procedure, not authorization or evidence that it has run. `seconds` uses the existing runner's 1–660 second test budget. The route does not read `tapAtSeconds`, `tapX/Y`, `autoStart` or `waitForGo`, and never calls `sendPointerSync` or a native dispatch method. After observer installation the runner writes `files/probe-native_single_001-native-ready.json`; the finite measurement clock is already running. Root and the Owner arrange readiness/actions within that window through normal UI. No new product gate or timeout is introduced.

The finite suggested case names/actions/durations are saved in [cases.json](cases.json). They are observation plans, not gesture-recognition rules. The app-private outputs are:

- `probe-<runId>.json`: recorder summary, actual window, ownership/cleanup and observer cost/failures.
- `probe-<runId>-native-events.jsonl`: original metadata, stages, lifecycle, inventories and samples.
- `probe-<runId>-native-ready.json`: case/start/duration marker.

Root retrieves only these owned diagnostic files under its reservation. No device operation is performed by the host analyzer. The summary's `passed` means recorder completion only; `physicalInputPass` and `physicalHoldReleasePass` remain false. Requested/actual window, installation, failure/loss counts, per-row export accounting and each cleanup outcome are recorded separately. Each owned cleanup is attempted independently, including attempted registration that throws after side effects; the original failure remains primary and cleanup failures are retained. A detached sink alone is not a complete recording. Failed/partial recordings retain their files and error class. An absent stream or absent events is not silently changed into a feature pass.

## Recorded boundaries and overhead

The optional `ReceiverActivity.NativeInputObserver` defaults to null. Before filtering, the key, touch-motion and generic-motion entries pass the original event with adjacent Android uptime/elapsed readings. With no observer, no event metadata, clock, device lookup, queue, JSON or I/O is evaluated. With a recorder, it copies device ID, source, key/scan/action/flags/repeat, original down/event time and motion pointer count. It never retains a recyclable `KeyEvent`/`MotionEvent`, text, key characters, network payloads or credentials.

Device names come from a test-thread InputManager inventory keyed by original device ID; the inventory and its read brackets are retained. Refresh callbacks run on a background HandlerThread. An unknown identity stays null; no per-event Binder lookup or guessed physical device is used. Names are inventory observations, not a claim that each event queried the device at its receipt instant.

Only primitive metadata is copied/enqueued inside input/stage callbacks. File writes, JSON serialization and status reads run on the instrumentation thread. Existing `inputSnapshot()` is sampled on the main thread outside input callbacks, only when a Receiver resume has actually been observed since listener installation. The tool does not use reflection to find an older Activity. Copy/enqueue monotonic duration totals/max apply only to raw event and stage callbacks; inventory/sampling work is excluded. This is observable overhead, not a promised physical latency budget.

Install and detach run on the main thread. A recorder cannot replace an existing observer; cleanup removes only its exact sink, unregisters its lifecycle/device listeners, closes/drains the queue, and reports ownership loss or recorder failures. Observation exceptions cannot change original event consumption/default suppression. There is no capacity cap, input drop/expiry policy, product retry or changed gesture behavior.

## Stage interpretation

`key`, `touch_motion` and `generic_motion` rows contain original Android event timestamps and actual local receipt clocks. A source flag or key pulse alone does not prove a physical finger or sustained contact.

`stage=ai_callback` records the existing `NativePadLongTouch` callback's `aiCallbackUptimeMs`, after its unchanged ordered-broadcast interception, plus observer receipt clocks. It does not claim the earlier kernel or broadcast-send time. A marker preceding that callback does not fabricate held DOWN/UP.

`stage=deadline` records the existing computed recognizer due once. `dispatch_enter`/`dispatch_result` use test-only action identities and existing action/down/up timestamps. `actionTimeMs` is recognizer/replay time, not necessarily raw event time. Entry-to-outcome timing ends at the existing result branch; it is not full method-return time (held Contact updates edge scrolling afterward). A submitted outcome means the existing local/queued operation accepted it; it does not acknowledge Phone injection or widget effect. A `cancel` stage and generation/context metadata preserve interpretation across transitions.

`sample.controls` contains only whitelisted existing status fields, a session start identity and status-read brackets. These approximately one-second aggregate counters expose accepted/written/canceled/failed/pending work and write-delay summaries. They are not per-event write timestamps. Existing status may precede this Activity; a continuing authenticated session is retained and recorded. Session changes/counter resets must not be subtracted as one continuous sample.

Root separately captures whitelisted before/after Phone InputFixture metadata: `displayId`, `active`, `taps`, `nativeDoubleTaps`, `tapDownTimesMs`, `tapUpTimesMs`, `mainScrollY`, `nestedFirst` and `nestedTop`, with host retrieval brackets. Do not export the fixture's editable text/selection fields. The Phone arrays contain Phone MotionEvent times, not Glasses times or target receipt timestamps. Preserve the fixture instance, actual display and case identity. The existing fixture does not supply a full long/drag event trace.

## Host analysis and checks

On already-retrieved, supplied files:

```sh
python3 tools/native-input-measurement/analyze.py \
  --trace /private/case/native-events.jsonl --device-id 3 \
  --capture-report /private/case/probe-report.json \
  --phone-before /private/case/phone-before-metadata.json \
  --phone-after /private/case/phone-after-metadata.json \
  --output /private/case/analysis.json
```

Choose `device-id` from the reserved run's inventory rather than the example. Without it the report remains a multi-device observation. Supply the closed probe report as well as its trace: installation, completed actual window, no observation/export failure or loss, exact cleanup and successful runner/export facts must agree with row counts/identities. Without that evidence capture completeness stays unestablished. The analyzer reports delivered key intervals, receipt lag, exact marker/action associations when unambiguous, canceled/missing associations, due/dispatch/outcome timing, aggregate control deltas and separate Phone counts/intervals. Missing/invalid session/display identity is unknown, and reset/changed intervals receive no continuous deltas. Cross-device physical latency remains null. A common physical recording of finger onset/release and visible target response, with its frame resolution, is required for that measurement; never subtract unrelated device uptimes. Matching counts/ordinals within a case is not an injection acknowledgment.

```sh
python3 -m unittest discover -s tools/native-input-measurement -p 'test_*.py' -v
python3 tools/native-input-measurement/run_focused.py \
  --output-dir /private/new-native-observation-focused-output
```

The focused runner reads the already-cached Kotlin2.2.21 compiler and uses JDK17, with no downloads, Gradle or Android SDK call. It compiles the actual test-only queue and source-extracted optional observer/ownership blocks using small host doubles. Checks cover original snapshot/clock retention, callback failure containment, disabled-hook metadata avoidance, exact install/detach ownership, late-close/cancellation behavior, writer failure and excluded background overhead. The generated code, input/dependency hashes and closed logs identify what ran. These checks do not execute the complete Activity or Android dispatch. A separately allocated Receiver/AndroidTest APK build and independent exact-candidate review are still required before a device run.
