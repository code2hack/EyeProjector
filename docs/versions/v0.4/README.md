# v0.4 implementation plan

Manager: Manager-EyeProjector-v0.4

The Owner authorized implementation of the clarified coordinate-Anchor design, direct SPEC editing, v0.3 archival merge and agent retirement, then v0.4 execution on 2026-11-03 (Asia/Singapore). Base: the tagged v0.3 checkpoint on main. Current [SPEC](../../../SPEC.md) is authoritative; [this version snapshot](SPEC.md) records the agreed start contract.

## Outcome

Replace the old semantic Anchor pipeline with per-app absolute-position lists and Normal / Anchor / Anchor Settings modes. Keep normal pointer/Tap/navigation, camera/audio, connection and source-input mapping functional. New agents use Codex gpt-6.1-sol / ultra / full-access with service tier **default** (fast mode off). The Manager retains actual existing configuration; no retrospective model/profile changes are inferred.

## Small issues and dependencies

| Plan ID | Objective | Dependencies |
| --- | --- | --- |
| [V04-01 / #147](https://github.com/code2hack/EyeProjector/issues/147) | Coordinate model, immediate per-app list persistence, transient mode state and shared state contracts | None |
| [V04-02 / #148](https://github.com/code2hack/EyeProjector/issues/148) | Seven Phone HID roles, gesture learning/matching and continuous Scroll controls | None for owned HID source; coordinate routing joins V04-01/V04-03 |
| [V04-03 / #149](https://github.com/code2hack/EyeProjector/issues/149) | Phone/Glasses mode wiring, native Function Mode, numbered Settings overlay, pointer routing and removal of old Anchor code | V04-01 and V04-02 exact contracts |
| [V04-04 / #150](https://github.com/code2hack/EyeProjector/issues/150) | Assemble, independently assess cleanup, and run both-device ADB E2E plus carried ordinary-input/stability regression | V04-01 through V04-03 |
| [V04-05 / #154](https://github.com/code2hack/EyeProjector/issues/154) | Retire the captured original direct session on explicit Disconnect, preserving successor isolation | Frozen V04-03 routing contract; final integration follows #149 |

V04-01 and V04-02 run concurrently with disjoint write ownership. They publish exact typed contracts promptly; V04-03 may inspect/plan its caller removal while those contracts settle, but imports only committed prerequisites. One independent Reviewer assesses each exact candidate. No mutable peer worktree import or shared SDK/device concurrency is allowed. Each PR targets version/v0.4.

## Implementation and cleanup

1. V04-01 adds a small coordinate model, codec/store and deterministic mode reducer under a new position-specific package. Persist only package-keyed ordered positions. Keep per-app mode/selection in memory and default Normal at process start. Cover empty-list skipping, first selection, wrap, LIFO and persistence failure/reload. It owns shared coordinate/mode DTOs, not HID role fields.
2. V04-02 replaces the old Anchor/combined-navigation binding vocabulary, stores gesture identity for Back/Home/Lock/Mode and retains single-click learning for Tap/Scroll. Preserve real Tap hold/drag. Repeat Scroll as one owned physical press, with explicit terminal/loss handling and no tail replay. Migrate compatible saved assignments as described in SPEC. It owns Phone HID classes, PhoneInput HID-role DTOs and necessary typed merger handoff; minimal shared callers are allocated before edits.
3. V04-03 connects one Phone mode owner to native and Phone-HID operations, sends current app/mode/positions to the Glasses and routes local and remote pointer actions consistently. Anchor mode pins the pointer; Settings renders numbered positions and intercepts Tap/Back; switching apps restores transient state and Home forces Normal. Replace native Function registration with single Mode and no-op long; deduplicate actual raw/OEM delivery. Delete obsolete chooser/registration/descriptor/resolver/held-Anchor Binder and wire routes, tree-reading capability used solely by the old feature, old visual indicators, obsolete tests and setup UI. Keep service permission/interception and generic input/connection ownership intact. No permanent alias, no-op compatibility tombstone or dead duplicate owner remains after the final join; matched wire versioning applies if payloads change.
4. V04-04 verifies no old active path remains, both peers compile/test/package, and installed artifacts match the assembled source. Run ordinary and new-mode E2E on both actual devices. Preserve old v0.3 source/evidence and private state; there is no need to finish abandoned UI identity work.

## Verification and resource plan

Use meaningful focused reducer/store/codec/gesture and actual caller-composition tests, followed by affected Android module checks and both Main assemblies. Do not equate host doubles or injected ADB events with physical buttons, optical head motion, native firmware timing or radio behavior.

Manager-owned E2E covers Normal Tap/hold/drag/navigation; creation at several distinct positions and LIFO deletion down to empty; first selection, next/previous/wrap and held repeat; Settings Scroll no-op; no target app input during Settings Tap/Back; per-app switch and EyeProjector Home; restart persistence with Normal default; all seven HID roles and learned short/long/multi gestures; native Function single/long raw route; ordinary mode head-scroll and Anchor suppression; keyboard shown/hidden independence; connection/Stop and input-tail recovery; carried camera/audio/session stability where reproducible. Use a controlled app/fixture for visible effects, plus Doubao/ChatGPT/WeChat/Paseo where useful without private-content capture.

The Owner is asleep and authorized the Manager to operate, trace and debug both devices via ADB without attendant gestures. Preferred path is TCP/IP port 5555. Phone is on Morty5G; Rokid is on the Phone hotspot. Resolve and record actual current addresses/serials before operations; preserve existing ADB server and product connection/helpers. Routine prompts and music alarms are suspended during sleep. Setup/install and automated E2E remain distinct from physical human acceptance.

## Completion boundary

Integrate independently assessed exact candidates, then verify the assembled version branch and real-device effects. Report unsupported physical/camera/audio/firmware cases honestly. A software pass or scripted input submission does not establish universal app compatibility. Release/Owner usability acceptance stays distinct from the Owner's current implementation and unattended-testing authorization. Retain carried issue #103 and native input limitations in the verification record if they survive the redesign.

## Device-test follow-up

The first assembled device trials exposed a supported direct-session path without an activation ID. V04-05 runs as a small parallel lifecycle correction while V04-03 Mode readiness is verified. Its local and peer cleanup must target the captured original Session; trigger acknowledgement or a stale STREAMING status file alone is not resource-release evidence. The product contract remains unchanged.

## Integration status

#147, #148 and #149 are accepted. #154's reviewed original-session correction is accepted and merged through PR155 at `2c2f2fb`; its superseded failures remain in the run record. PR156 integrates the reviewed #150 fixture/test source at `376fb17`. That production/test tree matches the tested `883a1adb` composition except for run documentation. The latest affected build passes 253 fresh Glasses tests and Main/AndroidTest assembly; Phone315/protocol93 results are retained from unchanged inputs.

The [run record](../../runs/v0.4.md) distinguishes actual mode, app/Home/restart, keyboard, native adapter, Stop, static-session and reconnect cases from physical input and media limitations. The camera smoke still rejects the registered virtual ID in the ordinary consumer context; #103 stays open. Final assembled evidence review and cleanup are recorded under #150. Integration into the version branch does not publish v0.4 to main or establish Owner usability acceptance.
