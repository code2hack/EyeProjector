# Unattended Anchor control preparation

Worker: Worker-EyeProjector-v0.3-112.

This is test-only preparation for Owner148's ADB-control-and-trace testing while the Owner sleeps. It issues no human prompt, guide, audio or alarm. Preparing or running the host checks grants no SDK/build/device execution. Manager supplies a reviewed matched Phone/Glasses/fixture assembly and both device reservations later.

`operation=anchor-adb-control` is an opt-in dispatch in `ProjectionInstrumentation`. It composes the existing passive twelve-action observer with `AnchorAdbControlDriver` in **one invocation and target process**. The observer installs first; that same invocation starts the normal Receiver immediately and optionally clicks its own Connect button when no Session/request already exists. It never starts competing instrumentation. `autoConnect=false` leaves Connect to an explicit own-View command. The driver forces `gestureGuide=false` and `stopOnRequest=false`; the existing finite recorder bound controls closure. All other operations retain their default behavior.

The driver reads only its exact `files/probe-<runId>-adb-plan.json`. The automated host coordinator can write an initial plan containing just `initial-observe`, then replace the file atomically with appended uniquely identified commands after reading actual source/fixture observations. This is host automation, not a Ready/Done or per-step human flow. Completed, failed and timed-out command IDs are not replayed. Every wait is bounded by the authored test phase timeout and enclosing recorder window. Own phase completion is not a Phone disposition or app effect acknowledgment.

## Existing test commands

| `op` | Path exercised and evidence label |
| --- | --- |
| `observe` | Own original Receiver/source snapshot; `injectionKind=none` |
| `aim` | Stop acquisition on the exact owned PointerOverlay, feed synthetic `RotationSample`s through the actual HeadPointerModel, publish its real pointer callback; `synthetic_rotation_sample` |
| `surface_tap` | Mapped DOWN/UP through the own Activity's actual touch dispatch; `own_activity_dispatch_MotionEvent`, not physical touchscreen/native-pad input or head hover |
| `own_view_click` | The actual Connect/Disconnect Button or the shown chooser's Click/Hold/Cancel Button listener; `own_view_performClick` |
| `native_broadcast` | Four declared Function CLICK/LONG/DOWN/UP or two native swipe actions through the actual app receiver; `injected_native_broadcast`, not firmware or physical fingers |
| `callback` | Existing contextualBack/contextualHome/showControlPanel/showHidBindings callback; `direct_own_callback`, not a touchscreen user-flow proof |

The smallest registration sequence is synthetic aim at the actual fixture Hold control, injected Function LONG, actual chooser Hold, actual Phone store readback, then injected Function CLICK through the real registration reader/provider/store/execution path. There are no direct preference writes or fabricated Hold output. The automated host must observe actual Phone fixture DOWN before appending a Short release or distinct non-short input, and observe its original UP/CANCEL before a fresh positive. The driver does not use a Glasses held-indicator label as authoritative Phone HELD evidence. Chooser disappearance can follow UNAVAILABLE/CANCELED and is not proof of saved Hold.

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

The automated host collects the named edge file plus the latest counter independently. `summarize.py` normalizes only allowlisted driver/fixture metadata and hashes originals:

```sh
python3 tools/anchor-adb-control/summarize.py --glasses-events private/native-events.jsonl \
  --fixture-edges private/fixture-edges.jsonl --phone-trace private/phone-input.jsonl \
  --output private/adb-evidence.json
```

No stream is cross-clock sorted; no cross-device latency, target-app protocol ACK, physical count or original Phone contact join is fabricated. Use the existing navigation parser separately for the original native key/motion/stage rows. App edges prove their own View callbacks, not every upstream gate/handler negative.

## Phone authority handoff and first useful cases

Declared frozen115 snapshots place the authoritative Anchor gate in the separate loaded Phone Shell Session. App instrumentation cannot reflect into it. Writer115's frozen API163 contract SHA256 `8cff103852314e92a1d07231f19ef1cb932f2f0752edff49c6886fa5b48f3914` and eight source snapshots were verified locally. Source/build/device applicability still requires the final matched assembly. No Phone production method, command, manifest or permission is invented here.

At **new actual Shell Session creation**, property `eyeprojector.inputTrace` or environment `EYEPROJECTOR_INPUT_TRACE` supplies an absolute mission-owned JSONL path writable by the original Shell UID; its parent must already exist. Optional tag is `eyeprojector.inputTraceRun` or `EYEPROJECTOR_INPUT_TRACE_RUN`. It is off by default and cannot be enabled dynamically on an existing Session by separate app instrumentation. Manager coordinates the actual source-bound process launch; this preparation does not restart or replace the working helper. The original source captures primitive receipt/admission/contact facts and a separate daemon appends them; scheduler/output remain production-owned. Existing `managedProjectionStatus.inputTrace` reports enabled/appended/appendFailure, not permission or target ACK.

`summarize.py --phone-trace` retains exact API163 frame request/gesture/contact/source IDs, source display/video geometry/target, original HELD/CANCELING/prepare/finish/failure/disposition facts, `jvmMonoNs`/`wallMs` and both observer/append sequence. It preserves file order even when observer sequence or clocks differ. Only literal `capturedOriginal` identity tuples are grouped within that same Phone Session; CONTROL_* `atControlObservation` rows remain separate. It invents no Phone-to-fixture contact match or cross-device clock ordering. Missing/disabled/unmatched trace is inconclusive.

The supported app-process restoration seam is `AppAnchorPreferences.open(targetContext)`: save only `snapshot(controlledFixturePackage).anchor`, encode with `AppAnchorCodec.encode` when present, and retain that mission-owned backup before real registration. Read back the same package through the store after registration. After original contact/Session retirement, restore the saved value using `store.replace` or `store.clear` if originally absent, then verify exact value. Revisions advance; the original revision is not restored. No unrelated package is written, and no initial Hold is manufactured with preferences. This is a frozen API handoff for the later Phone coordinator, not a new executable app hook in this Glasses-only instrumentation.

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

These checks use cached Kotlin/JDK and actual phase, real aim model, and append-only writer code; they are not Android/driver SDK compilation or device execution. The Android integration dispatch/automation hook requires a later final-matched compile/review of Phone/Glasses/fixture artifacts. Affected recipe: `:directrg:compileDebugAndroidTestKotlin :directrg:assembleDebugAndroidTest :hostprobe:compileDebugKotlin :hostprobe:assembleDebug`, plus whatever final Main/Phone-trace tasks Manager allocates. No task is run under source-only157.

Driver cleanup closes only its phase cursor and restores acquisition on its exact still-current own PointerOverlay under the existing lifecycle; it schedules no independent handler/thread and leaves normal Receiver/session lifetime to its owner. Existing observer removes its exact sink/listeners. Root retains all original reports/native JSONL/ready/plan/fixture edge/counter/Phone trace files, closes only the owned fixture and supported trace hook, and restores only the controlled fixture's prior Anchor record through its supported path. All previous837b/other APK evidence and original4aa test restoration duties remain. Source-host results do not accept native112, Anchor115, physical/optical behavior or the assembled version.
