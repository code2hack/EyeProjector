# v0.4.1 implementation plan

Manager: Manager-EyeProjector-v0.4.1

The Owner authorized v0.4.1 on 2026-11-03 to fix unnecessary HID gesture delays and retain separate profiles for multiple devices. Simultaneous operation is explicitly excluded. The published v0.4 checkpoint `c99df1390c87ca8109c2c6f903dcccc306c0d866` is the base. The permanent checkout stays on main; implementation and PRs use `version/v0.4.1`. The [SPEC](../../../SPEC.md) is authoritative; [this snapshot](SPEC.md) records the authorized start contract. The Owner's standing instruction permits the Manager to edit SPEC.

## Contract

Runtime uses a saved gesture: SINGLE acts on short release, MULTI N on its Nth short release, and the 500 ms timer expires only incomplete inter-click sequences. Completed gestures retire immediately. LONG remains at least 800 ms and acts on release. Learning keeps generic quiet-period discovery and consumes input. Tap hold/drag/multi-tap, Scroll repeat and native Rokid behavior remain unchanged.

Each actual physical device retains its own bindings and gestures. Only one pinned device supplies input. Existing selection/Pin flow activates its corresponding profile. Unpin and switching preserve all profiles; Reset clears only the selected one. Reconnect/restart restore saved pin/profile intent. Migration preserves recorded device identity, legacy bytes, pairing, pin and unrelated settings. Original input ownership retires before replacement; no stale old release/timer/learning tail may operate a new profile.

## Small issues and ownership

| Issue | Objective | Dependency and source ownership |
| --- | --- | --- |
| [#158](issues/158.md) | Execute known HID gestures without unnecessary final wait | Ready; protocol matcher and PhoneInputMerger timing plus focused tests |
| [#159](issues/159.md) | Independent persistent HID profiles, one active device | Ready; directhost HID codec/controller/pin/runtime/panel/resources/tests |
| [#160](issues/160.md) | Assembled verification, preservation and unattended E2E | Read-only preparation now; implementation/acceptance follows exact #158 and #159 candidates |

#158 and #159 run concurrently with disjoint writers. Any actual cross-boundary seam is reported and allocated by the Manager before edits; imports use exact committed candidates. #160 first reads frozen source and maps existing caller/fixture routes, then returns idle until its dependencies are available. Each implementation issue has one Worker and an independent Reviewer. PRs target the version branch; the Manager owns integration and version acceptance.

## Execution policy

Standing Owner concurrency is unlimited; dispatch only useful disjoint work. New Workers/Reviewers use gpt-6.1-sol / ultra / full-access / service tier default, fast mode off. Preserve the existing Manager profile. Verify actual native/Paseo/project/workspace/CWD/profile and a correlated startup receipt before relying on work. Runtime expectations remain distinct from observed lifecycle.

Workers run meaningful focused actual-source host checks. The Manager reserves SDK and devices, performs affected Android/JVM/Main packaging and operates both actual devices through the existing authorized ADB routes, preferring TCP 5555. Preserve installed applications, private state, pairing, pin, helpers and source evidence. No new hardware input grab, profile export/rename, simultaneous input arbitration, generic diagnostics framework or camera #103 work is included.

Use written material progress notifications. Routine implementation, review, reversible fixes, authorized testing and publication do not require another Owner prompt. Existing guard-request rules apply to genuinely proposed added restrictions; a timeout dismisses a proposal rather than stopping authorized work. The Owner's credential-retention decision remains in the private runtime.

## Verification and completion

Focused tests cover immediate SINGLE and Nth-MULTI delivery, incomplete expiry, rapid completed sequences, LONG release, learning isolation and original-input cancellation. Profile tests cover independent maps/gestures, switch/unpin/reset, migration including mixed-device records, reconnect/restart, I/O failures and old-callback isolation. Actual caller composition must preserve ordinary Tap/hold/Scroll/native Mode and ordered output.

After exact independent reviews, compose and verify affected modules and both consumers where shared code changes. Bind installed artifacts to source for actual route checks where available. Record the distinction between host fixtures, ADB-injected inputs and physical HID/radio timing. Do not invent two-physical-device acceptance if only one is connected. Preserve meaningful compatibility limits and prior failure history.

Integrate accepted issues, close their canonical issues, publish the assembled reviewed version to main and create its checkpoint, then archive Workers/workspaces and remove this version's watchdog registration. Keep source/evidence/Owner decisions. No additional Owner usability/publication gate is implied by test qualifications. Carried camera #103 and physical/head/optical/media limitations remain outside this patch.

## Accepted implementation and verification

All three issues are accepted and closed. PR161 integrates exact timing86433c9 at67a8a75; PR162 integrates corrected profiles0245982 at14a5e1a; PR163 integrates final verificationc0bcc66 at ecffcc3. The assembled code/test tree is byte-identical to the final Root build. [The run record](../../runs/v0.4.1.md) distinguishes 328 fresh Phone tests, retained unchanged protocol98/Glasses253 evidence, both Main/AndroidTest packages and actual Phone upgrade/profile persistence checks.

The initial Android fallback selection defect is resolved. Original temporary test APKs are restored/readback equal, owned fixture preferences removed, new Main artifacts retained and the standard matched Reader preserved. Actual profile tests use synthetic identities, not two physical devices. Glasses Wi-Fi remains disconnected despite a bounded reconnect attempt; its preferred TCP relay was offline before its update and USB served package/custody checks. No normal projected-input smoke, visible target UI or physical HID/radio/head/optical acceptance is claimed. Camera103 and other carried media/firmware limits are unchanged. No additional Owner gate is imposed on authorized publication.
