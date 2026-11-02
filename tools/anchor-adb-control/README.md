# Unattended Anchor control preparation

Worker: Worker-EyeProjector-v0.3-112.

This is test-only preparation for Owner148's ADB-control-and-trace testing while the Owner sleeps. It issues no human prompt, guide, audio or alarm. Preparing or running the host checks grants no SDK/build/device execution. Manager supplies a reviewed matched Phone/Glasses/fixture assembly and both device reservations later.

`operation=anchor-adb-control` is an opt-in dispatch in `ProjectionInstrumentation`. It composes the existing passive twelve-action observer with `AnchorAdbControlDriver` in **one invocation and target process**. The observer installs first; that same invocation starts the normal Receiver immediately and optionally clicks its own Connect button for initial acquisition. Already-observed streaming, a Session, or an existing connection request consumes that same one-shot acquisition, even without focus. Once observed or submitted, later original cleanup/disconnection cannot trigger another automatic Connect. `initial_connection_observed` records the observed path separately from `own_connect_submitted`. It never starts competing instrumentation. `autoConnect=false` leaves Connect to an explicit own-View command. The driver forces `gestureGuide=false` and `stopOnRequest=false`; the existing finite recorder bound controls closure. All other operations retain their default behavior.

The driver reads only its exact `files/probe-<runId>-adb-plan.json`. The automated host coordinator can write an initial plan containing just `initial-observe`, then replace the file atomically with appended uniquely identified commands after reading actual source/fixture observations. This is host automation, not a Ready/Done or per-step human flow. Completed, failed and timed-out command IDs are not replayed. Every wait is bounded by the authored test phase timeout and enclosing recorder window. Own phase completion is not a Phone disposition or app effect acknowledgment.

## Existing test commands

| `op` | Path exercised and evidence label |
| --- | --- |
| `observe` | Own original Receiver/source snapshot; `injectionKind=none` |
| `aim` | Stop acquisition on the exact owned PointerOverlay, feed synthetic `RotationSample`s through the actual HeadPointerModel, publish its real pointer callback; `synthetic_rotation_sample` |
| `surface_tap` | Mapped DOWN/UP through the own Activity's actual touch dispatch; `own_activity_dispatch_MotionEvent`, not physical touchscreen/native-pad input or head hover |
| `own_view_click` | The actual Connect/Disconnect Button or the shown chooser's Click/Hold/Cancel Button listener; `own_view_performClick` |
| `native_broadcast` | Four declared Function CLICK/LONG/DOWN/UP or two native swipe actions through the actual app receiver; `injected_native_broadcast`, not firmware or physical fingers |
| `native_receiver_report` | Only Function DOWN/UP/CLICK/LONG through the exact original registered `ReceiverActivity.functionInput` receiver's `onReceive`, on main; `direct_original_own_receiver_onReceive`, not an OS broadcast or physical firmware arrival |
| `callback` | Existing contextualBack/contextualHome/showControlPanel/showHidBindings callback; `direct_own_callback`, not a touchscreen user-flow proof |

The smallest registration sequence is synthetic aim at the actual fixture Hold control, explicit `native_receiver_report` Function LONG, actual chooser Hold, actual Phone store readback, then explicit Function CLICK through that same original receiver/grammar and real registration reader/provider/store/execution path. Missing or unregistered original input/receiver remains inconclusive. There are no direct preference writes or fabricated Hold output. The automated host must observe actual Phone fixture DOWN before appending a Short release or distinct non-short input, and observe its original UP/CANCEL before a fresh positive. The driver does not use a Glasses held-indicator label as authoritative Phone HELD evidence. Chooser disappearance can follow UNAVAILABLE/CANCELED and is not proof of saved Hold.

Closed283 retained an actual system denial of the app UID's protected Function LONG broadcast. The existing `native_broadcast` operation remains available with its original semantics; it does not automatically fall back. The new operation calls the actual original `NativeFunctionInput` receiver, including its registered check, report parsing, recognizer, source capture and retirement, rather than sending a protected Android intent or calling downstream activation directly. It constructs no replacement producer and grants no permission. `prepare_plan.py` still emits legacy broadcast operations: a later authorized coordinator must explicitly select the new Function operation, without treating an unchanged old plan as corrected. Swipe actions are not accepted by the new operation.

At the actual own-action boundary, `own_action_exception` copies the original type/message/stack/cause before the same throwable is rethrown. Diagnostic failure cannot replace that throwable; no failed action becomes successful. These are test observer facts, not firmware arrival, Phone admission or target-app acknowledgment.

Every command has `id`, `op`, optional `timeoutMs` (default10 seconds), and optional Boolean `before`/`after` predicates on recorded own state, e.g. streaming/focused/chooserShown/aimAtTarget. An optional `sourceSessionIdentity` fences the observed original process-local Session. These IDs are not cross-device protocol IDs. Source snapshots retain actual Session start, owner identity, input generation and allowlisted current usability identities where exposed. Missing fields are not fabricated. The initial driver targets its own original Receiver; arbitrary successor-Activity/source control, all local settings/HID/Lock paths and deterministic cancellation/feedback latches are deferred coverage, not prerequisites for the first ordinary/registration/hold tests.

## Actual geometry and setup availability

An `aim` or `surface_tap` command supplies `target`: actual `geometryWidth`, `geometryHeight`, `frameX`, `frameY`, observed `phoneDisplayId` and a private `sourceWitness` identifier. The driver compares the current encoded geometry and maps through the actual current Receiver root/Fit. It never assumes Phone display0, never treats an ADB tap as hover, and does not infer a frame mapping from the fixture rectangles alone.

`prepare_plan.py` consumes three **already obtained** observations and makes no ADB call:

```sh
python3 tools/anchor-adb-control/prepare_plan.py --run-id "$RUN_ID" \
  --receiver-observation private/receiver.json --fixture-counter private/fixture.json \
  --phone-observation private/phone-forwarded-source.json --output private/plan.json
```

The receiver input is the `source` object of a live `adb_control/observed` row. Fixture input is the existing own counter/geometry file. The Phone input is an explicit **host-forwarded test metadata contract**, not a claimed production API or invented executable command: actual managed `displayId`, mapping `width`/`height`, confirmed `identityFrameCoordinates`, `sourceWitness`, observed production `registrationReady`, and controlled-package `fixtureAnchorStateRestorable`. The initial helper supports only a witnessed identity frame mapping; other mappings are explicitly unavailable until mapped from real production data. It preserves the fixture's original singleTask run ID and never resets another run's counters.

Initial registration preparation ends at the actual chooser choice. The host can regenerate the same plan with later `fixtureAnchorReadback={packageName,mode,sourceWitness}` from the supported store snapshot; only the controlled hostprobe package with observed `mode=HOLD` adds the uniquely named CLICK. Already completed command IDs are not replayed. These are host-forwarded observations, not a Phone API or a substitute for real registration.

Missing accessibility/provider readiness or prior fixture Anchor restoration evidence omits registration commands and records an unavailable scope; ordinary remote-effect preparation continues when its mapping is available. Root's old-package preflight160 found no enabled EyeProjector accessibility entry. That is not a grant to toggle settings or proof about a future matched package. Recheck actual enabled **and connected** service/provider state later. The driver records forwarded unavailable scopes and local unavailable-session closure without waking the Owner, repairing grants or inventing Anchor state.

## Independent controlled-app edge history

The controlled `UiTargetFixtureActivity` is based only on accepted immutable f6d source. It is absent from this old2703 baseline; its **already accepted f6d manifest singleTask declaration and resource IDs are final matched-integration dependencies**. This preparation does not edit a manifest/resources or claim standalone SDK compatibility. Counters, existing latest `ui-target-fixture.json` and default behavior remain separate and unchanged when tracing is off.

At future mission-owned creation, `edgeTrace=true` adds a unique private `ui-target-<run>-<instance>-edges.jsonl`. It is append-only and never truncates an existing file. Rows preserve run/instance/case/phase/sequence, actual own app MotionEvent action/mask/index/downTime/eventTime/pointer IDs/count/source/device/flags/coordinates, receipt uptime/elapsed, PID/UID/display and actual target callback. Activity-dispatch and child Click/Hold motion rows are deliberately separate. Hold counters change only on the real DOWN/UP/CANCEL callback; no long-press or physical classification is invented. Trace write failures are exposed separately in the latest snapshot. `onNewIntent` retains its original run/counters/trace and updates phase attribution only for that same run.

The automated host retains every edge filename from the first and subsequent same-run fixture counters, including Activity recreation, then collects those original files plus the latest counter independently. A later instance's edge file does not cover an earlier one. `summarize.py` normalizes only allowlisted driver/fixture metadata and hashes originals:

```sh
python3 tools/anchor-adb-control/summarize.py --glasses-events private/native-events.jsonl \
  --fixture-edges private/fixture-edges.jsonl --phone-trace private/phone-input.jsonl \
  --output private/adb-evidence.json
```

No stream is cross-clock sorted; no cross-device latency, target-app protocol ACK, physical count or original Phone contact join is fabricated. Use the existing navigation parser separately for the original native key/motion/stage rows. App edges prove their own View callbacks, not every upstream gate/handler negative.

## Phone authority handoff and first useful cases

The authoritative Anchor gate is in the separate loaded Phone Shell Session. App instrumentation cannot reflect into it. API163 contract SHA256 `8cff103852314e92a1d07231f19ef1cb932f2f0752edff49c6886fa5b48f3914` is historical: its property/environment-only setup was not passed by normal bootstrap. Corrected API172 and CAS181 are implemented by the [finite Phone companion](PHONE_COMPANION.md); current changed helpers require the assigned immutable Main9cd matched build/review/runtime allocation. No Phone production method, command, manifest or permission is invented here.

Current normal setup uses target-app `HostBootstrap.configureInputTrace(PhoneInputTraceConfig(path,runTag))`, preserving its exact prior nullable config. Existing normal START passes the typed observation values through the real Shizuku/Bridge path to a new Shell Session; no app property/environment substitute or Android setprop is used. The mission-owned parent must already exist and be writable by the actual Shell UID. Configuration alone starts/stops nothing, cannot add tracing to an existing Session, and is restored in the Phone parent's `finally`. The original source captures primitive receipt/admission/contact facts and a separate daemon appends them; scheduler/output remain production-owned. Existing `managedProjectionStatus.inputTrace` reports enabled/appended/failure and retirement metadata, not permission or target ACK. The companion's explicit `route=controller` uses actual central `start()` / `stopFromUser()` without window focus while preserving the original staged Activity lifetime; default own-View focus/shown/enabled behavior remains unchanged. Launch Phone with `am instrument --no-restart -w` in its already-running bound process and compare actual pre/in/post PID and service binding; never silently retry default restarting instrumentation. See the companion launch recipe and scoped283 readiness evidence.

`summarize.py --phone-trace` retains the declared frame request/gesture/contact/source IDs, source display/video geometry/target, original HELD/CANCELING/prepare/finish/failure/disposition facts, `jvmMonoNs`/`wallMs` and both observer/append sequence. Its API163 schema provenance remains historical; compatible parsing does not prove the old setup route executable. It preserves file order even when observer sequence or clocks differ. Only literal `capturedOriginal` identity tuples are grouped within that same Phone Session; CONTROL_* `atControlObservation` rows remain separate. It invents no Phone-to-fixture contact match or cross-device clock ordering. Missing/disabled/unmatched trace is inconclusive.

The companion opens the actual app-process `AppAnchorPreferences` store, backs up only the controlled fixture's complete nullable value/context before real registration, then reads back the actual registered value. Historical `replace`/`clear` restoration guidance is superseded by CAS181 `compareAndSet(expected,replacement)` after verified original contact/Session retirement and exact test-owned context/value accounting. Mismatch makes no write or refresh-and-retry; applied-then-superseded readback remains inconclusive. Revisions advance; the original revision is not restored. An unchanged absent Anchor needs no write. No unrelated package is written, and no initial Hold is manufactured with preferences. See the Phone companion for the concrete typed API and retirement limits.

Initial practical execution is ordinary remote click, actual explicit Hold registration and app DOWN, Short -> original app UP only, rearm, a non-short input -> app CANCEL only, then fresh distinct ordinary positive. Actual original Phone authority/trace and app edges are separate witnesses. Blocked-finish/CANCELING bursts, before-feedback/late-feedback races and every possible producer/lifecycle case may follow later; they do not block initial ADB testing.

Future invocation (proposal only, after matched build/review/reservations):

```sh
adb -s "$GLASSES" shell am instrument -w -e operation anchor-adb-control \
  -e navigationMetadata true -e seconds 180 -e runId "$RUN_ID" \
  com.code2hack.eyeprojector.directrg.test/com.code2hack.eyeprojector.directrg.ProjectionInstrumentation
```

The one parent installs observation, starts normal own UI and drives the plan without a model/tool wait between start and setup. Root's host coordinator obtains actual Phone managed display and fixture observations, writes only the run-owned plan atomically, and advances later commands from original observed edges/Phone trace. No physical prompt or competing instrumentation. Unavailable human-dependent setup is explicit deferred coverage. No source here grants permission, changes brightness, bootstraps a helper/relay, launches arbitrary private apps or makes a second sleep action.

## Preparation verification and cleanup

```sh
PYTHONDONTWRITEBYTECODE=1 python3 tools/anchor-adb-control/run_host_checks.py --output-dir /private/new-host-case
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/anchor-adb-control -p 'test_*.py' -v
```

These checks use cached Kotlin/JDK and actual phase, real aim model, and append-only writer code; they are not Android/driver SDK compilation or device execution. Helper correction257 changes only the two AndroidTest drivers and documentation. Its affected recipe is `:directhost:compileDebugAndroidTestKotlin :directhost:assembleDebugAndroidTest :directrg:compileDebugAndroidTestKotlin :directrg:assembleDebugAndroidTest` against immutable Main9cd and the new test overlay. The unchanged fixture and old qualified artifacts remain separate dependencies. No SDK task is run under the source-only assignment.

Driver cleanup closes only its phase cursor and restores acquisition on its exact still-current own PointerOverlay under the existing lifecycle; it schedules no independent handler/thread and leaves normal Receiver/session lifetime to its owner. Existing observer removes its exact sink/listeners. Root retains all original reports/native JSONL/ready/plan/fixture edge/counter/Phone trace files, closes only the owned fixture and supported trace hook, and restores only the controlled fixture's prior Anchor record through its supported path. All previous837b/other APK evidence and original4aa test restoration duties remain. Source-host results do not accept native112, Anchor115, physical/optical behavior or the assembled version.
