# Finite Phone companion preparation

Worker: Worker-EyeProjector-v0.3-112.

`operation=anchor-adb-phone-control` is an opt-in branch of the existing `ChosenAppsInstrumentation`. It returns its own DIAGNOSTIC_COMPLETE/DIAGNOSTIC_ERROR result before the existing default PASS path. Existing UI, notification, snapshot, scale, projected-home and fixture persistence operations keep their original dispatch and preference namespaces. No manifest, runner declaration, build file, permission or production source changes are made here. The branch never accesses UiAutomation, requests consent, repairs a helper or sends human prompts/audio.

This is test-only preparation. The original3bfb/SDK169 and Phone176/SDK175 preparation records remain historical. Helper correction257 targets unchanged Main9cd and requires a new matched AndroidTest build, review and runtime allocation; earlier APKs and closed237/254 observations do not qualify this changed helper. Preserve the original4aa test APK as the restoration target after later temporary installs. No device command in this document is authorized by source preparation.

## One app-process lifetime

The parent accepts a fresh `runId` and finite `seconds` (default180, range1..660). It retains its actual target app process, typed bootstrap configuration lease and staged HostActivity across the both-device batch. It does not restart instrumentation between commands.

Its first `ready` row names `files/probe-<run>-phone-inbox.json` and append-only `probe-<run>-phone-events.jsonl`. A host coordinator atomically replaces only that run's inbox with `{runId,commands:[{id,op,...}]}`. Commands are claimed once before execution; completed, unavailable and failed IDs are never replayed. Append a fresh command ID for a later observed state. Unknown/invalid input is diagnostic evidence, not permission or admission. The parent closes on its finite bound or `finish_parent`; neither path injects Stop/CANCEL, finishes the normal Host UI or writes an Anchor.

`phone_plan.py` prepares the initial host file without making any ADB/product call:

```sh
python3 tools/anchor-adb-control/phone_plan.py --run-id "$RUN_ID" \
  --trace-path /mission-owned/phone-input.jsonl --normal-start --output private/phone-inbox.json
```

With the explicit `--normal-start` option, order is observe, configure_trace, stage_host, anchor_backup, normal_start. Without that option no Start instruction is prepared. The actual automated coordinator observes status/source transitions before appending later capture, registration-accounting and restoration commands. This uses no Ready/Done or per-case human gate and does not require all cancellation-latch cases before first ordinary controls.

## Actual setup and control paths

| Operation | Actual path and scope |
| --- | --- |
| `observe` / `refresh_status` | Actual HostBootstrap status / ordinary queued refresh. Record setup/backend/source and connected production UiTargetAccess reader; connected alone is not target readiness. |
| `configure_trace` with absolute `path` | Actual target-app `HostBootstrap.get(context).configureInputTrace(PhoneInputTraceConfig(path,runId))`, saving its exact prior nullable value. It configures the next normal START only. |
| `stage_host` | Start the normal own HostActivity in this invocation, find its actual Start/Stop buttons by their resource labels, and scroll the existing own Stop rectangle into view. Do this before arming Hold. No private UI inventory is exported. |
| `normal_start` / `normal_stop` | Default `route=own_view` requires the original staged Activity's focus and its actual attached/shown/enabled button. Explicit `route=controller` calls current `PhoneSessionController.start()` / **`stopFromUser()`** without requiring window focus or button visibility, preserving the normal central user gate. Both routes retain the original staged Activity's non-destroyed/non-finishing lifetime. No arbitrary epoch or direct internal teardown `stop()` call. The command never opens/reopens a page implicitly. |
| `capture_owned_source` | Capture an actual new nonempty ownerEpoch/projectionToken observed after this parent's submitted normal Start. Existing source, missing source, successor source and queued Start are not relabeled as successful start/ownership ACK. |
| `projection_display` | Actual normal typed source-bound projectionDisplayState query. Callback request/generation/display/source and callback receipt clocks remain separate from command submission. |
| `finish_parent` | Stop only this test command loop, preserving normal Session ownership. |

The original176 source anchors were immutable SDK175 Phone production, corrected API172 (`68a7827b861ad953b5757e0048c9ebfbba017564337fc058d4fbde00a5a35f42`) and the SDK159 runner/build snapshot. Current helper qualification must use the assigned immutable Main9cd context. The normal HostActivity's Stop listener calls stopFromUser; the historical accepted f6d direct stop implementation is not current user-control behavior. Explicit controller dispatch is separately labeled `normal_controller_callback`: it exercises the central controller, without claiming a physical own-button UI flow or window visibility. SUBMITTED still means the original call returned, not admission, completed Start/Stop, retirement or target-app acknowledgment.

Corrected tracing passes typed observation configuration through existing normal START endpoint bytes to the real Shizuku service/Bridge/Session. No app Java property, adb export, Android setprop, daemon reflection, new Wire/AIDL/CLI or permission route is used. The target process restores the exact previous nullable config in `finally`; configuring/restoring it alone starts or stops nothing. The exclusive mission assumes no concurrent trace-config writer; an unexpected replacement is reported as inconclusive. Manager must establish the existing path parent and original Shell UID write access. This source does not guess that app readability/writability proves Shell access, or create/chmod a directory.

Setup/consent/service/source failures are explicit UNAVAILABLE/INCONCLUSIVE rows. Owner148's missing human-dependent consent remains deferred; no enabled setting is toggled and no production accessibility service is suppressed through UiAutomation.

## Controlled Anchor accounting

The only store package is `com.code2hack.eyeprojector.hostprobe`. The companion uses the actual app-process `AppAnchorPreferences.open(context)` and its AppAnchorCodec; it does not use the existing instrumentation_fixture_ preferences for this real registration state.

- `anchor_backup` saves original presence, exact canonical encoded value, mode, store instance/revision, run and observed source in a newly created private `probe-<run>-phone-anchor-prior.json`. Repeating it preserves the first backup. It writes no Anchor.
- `anchor_readback` reads the actual current controlled-package store value and context. No unrelated package is read or written.
- After actual Glasses aim/Long/own chooser/provider registration, `anchor_account_registration` accepts a retained `registrationWitness` and records the actual new controlled store value/context in `probe-<run>-phone-anchor-test.json`. A live original source and connected production reader are required. Attribution is host-supplied evidence of the real preceding registration; this command neither registers nor manufactures Hold. Independent later replacement is detected by exact context/value comparison.
- `verify_original_retirement` checks the exact captured original owner/token against actual managedProjection/retiredManagedProjections normal status, a terminal managed state, and inputTrace enabled+closing+terminated with originalObligations=0. Missing/disabled/stale/successor status is inconclusive. Backend Stop/click return or a missing Hold label is insufficient.
- `anchor_restore` requires that original retirement, no observed successor/live source or later submitted Start, and the exact accounted controlled-package current value/context. This ledger accounts for one captured original Session; unexpected replacement makes restoration inconclusive. Restoring the original value must advance revision; the old revision is never claimed restored.

The normal app's `bootstrap-status.json` is one supported readback source. When normal source publication no longer updates it after retirement, Manager's automated coordinator may retain the original normal status readback and forward it in the exact private `probe-<run>-phone-retirement.json`:

```json
{"runId":"RUN","rawStatus":"EXACT_ORIGINAL_NORMAL_STATUS_JSON","sha256":"SHA256_OF_RAW_UTF8","sourceWitness":"RETAINED_ORIGINAL_COMMAND_OR_ARTIFACT_ID"}
```

The companion checks the raw hash and literal original source/terminal fields. This is explicitly host-forwarded evidence, not a local Shell object/UID observation or fabricated cross-source contact join. Originals and operation outcomes must be retained independently by Manager. No cross-device clock subtraction is performed.

The original API172 store lacked atomic conditional restore; that handoff gap remains historical. Writer115's new conditional181 contract SHA256 `5444b14ffdbb30f2209dfec666ba429485047871729f8c1e28a7ff93e916127b` supplies `AppAnchorStore.compareAndSet(expected: AppAnchorSetting,replacement: AppAnchor?): AnchorSettingContext?`. The companion's narrow adapter reconstructs the exact recorded test context/value and atomically restores the original backed-up nullable Anchor through that API, only inside the verified-retirement ledger callback. Null means no mutation and no retry with a refreshed expectation. It verifies the returned committed context against actual readback; a reentrant newer write is **INCONCLUSIVE_APPLIED_THEN_SUPERSEDED**, never overwritten again. Restored values truthfully have an advanced revision. No initial registration/Hold is fabricated through preferences. This API is after SDK175: matched Android compilation must include its declared frozen source rather than claim old SDK175 classes expose it. First independent controls need no new hardware latch or permission architecture.

## Evidence, checks and later build

Original companion rows retain run/sequence, command intent/outcome, actual local source/status/control/Anchor contexts and Android uptime/elapsed receipt clocks. A command's SUBMITTED or own click return is not Phone admission, observer retirement, controlled-app receipt or overall PASS. The original Phone Shell trace and independent controlled-app MotionEvent edges remain separate witnesses.

```sh
python3 tools/anchor-adb-control/summarize_phone.py --events private/phone-events.jsonl \
  --output private/phone-companion-summary.json
PYTHONDONTWRITEBYTECODE=1 python3 tools/anchor-adb-control/run_phone_host_checks.py --output-dir /private/new-phone-case
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/anchor-adb-control -p 'test_phone_*.py' -v
```

The summary preserves original stream order/clocks and omits encoded Anchor payloads; original private backups remain retained. Host checks compile the actual pure command/config-lease/accounting/retirement code and frozen typed config with explicit swap boundaries, plus the actual narrow restoration adapter against conditional181's actual store/codec/descriptor source with a named in-memory storage boundary. They do not compile the Android driver or prove normal START/device behavior.

Proposed later affected tasks: `:directhost:compileDebugAndroidTestKotlin :directhost:assembleDebugAndroidTest` in Manager's immutable matched current context, including conditional181's store seam. SDK/Gradle/package/deployment is not authorized by preparing this source. Future reserved execution uses the existing runner with `operation=anchor-adb-phone-control`, `runId` and `seconds`; it requires no test manifest change. Final Main/Phone/Glasses matching and runtime review remain Manager-owned.

Retain all inbox/outbox/config/Anchor/retirement originals and exact command outcomes. The parent restores only its prior next-START config; it never silently cleans up live contacts, overrides independently changed Anchor state or turns a timeout into retirement. Manager owns any pending explicit normal Stop, qualified controlled Anchor restoration, own UI closure, exact probe removal and original4aa APK restoration.

## Legacy scale fixture compatibility (184)

The isolated SDK183 run compiled Phone production Kotlin/Java/AIDL but failed AndroidTest compilation: two old AppScaleChecks Unit callbacks no longer satisfied the actual `(ChosenApp) -> AppScaleTarget` API. No test APK resulted. That failure and the a551 candidate/evidence remain retained.

The follow-up test correction supplies an isolated actual AppScaleCoordinator and a labeled synchronous UI backend; selecting scale returns the coordinator's actual target. It exercises the current notification Switch and inline EditText/slider rather than the retired action-list menu. Assertions retain disconnected/unavailable disabled controls, notification persistence without app selection/resize, Remove, actual inline scale commit and saved request, no launch/resize on reopening, disconnect dismissal, pending observed-value feedback, pinch enabled/disabled behavior, close and preference persistence. Anchor menu storage is an isolated in-memory actual store; no real Anchor/Hold/Session is fabricated. Fake backend observations never claim a stock resize, production notification launch or continuing video.

Widget operations use the own component's pinned dialog field and actual View/listener methods, without a UiAutomation connection. A focused cached host check compiles the exact extracted backend/selection fixture against the frozen actual scale coordinator/store/value declarations. It proves the typed callback and fixture coordinator behavior only; Android dialog/switch/editor/pinch execution remains unperformed. The same two AndroidTest tasks and renewed exact-head review remain Manager-allocated follow-ups, with no production/manifest/build/permission change.
