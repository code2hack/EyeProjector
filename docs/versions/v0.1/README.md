# v0.1 implementation plan

Status: Owner-authorized implementation. The 20 canonical issues are published in the [v0.1 milestone](https://github.com/code2hack/EyeProjector/milestone/1). EP identifiers retain the plan mapping; GitHub issues own execution scope.

Basis: Owner decisions in [SPEC.md](../../../SPEC.md), committed as [`e972546c6ea32bba49a296beaf24c9c5020bfd85`](https://github.com/code2hack/EyeProjector/blob/e972546c6ea32bba49a296beaf24c9c5020bfd85/SPEC.md). Implementation branches and PRs target `version/v0.1`.

The breakdown contains 18 core implementation or focused verification issues, one assembled-version acceptance issue, and one late virtual-camera experiment. Each issue has one Worker, one independent Reviewer and an isolated worktree under [AGENTS.md](../../../AGENTS.md). Actual assignments belong in the version run record and private runtime.

## Issues and dependencies

| Plan ID / canonical issue | Deliverable | Blocked by |
| --- | --- | --- |
| [EP-01](issues/01-shizuku-startup.md) / [#5](https://github.com/code2hack/EyeProjector/issues/5) | Start and stop the Phone backend through Shizuku | — |
| [EP-02](issues/02-lan-pairing.md) / [#6](https://github.com/code2hack/EyeProjector/issues/6) | Pair and remember a peer using the rotating LAN code | 01, 05 |
| [EP-03](issues/03-bluetooth-video.md) / [#7](https://github.com/code2hack/EyeProjector/issues/7) | Video/control projection with all audio left to Bluetooth | — |
| [EP-04](issues/04-session-controls.md) / [#8](https://github.com/code2hack/EyeProjector/issues/8) | Phone Start and Glasses Connect operate one session lifecycle | 01, 02, 03 |
| [EP-05](issues/05-selected-apps.md) / [#9](https://github.com/code2hack/EyeProjector/issues/9) | Maintain the Phone's chosen-app list | — |
| [EP-06](issues/06-projection-home.md) / [#10](https://github.com/code2hack/EyeProjector/issues/10) | Launch/switch chosen apps and return to the projected home grid | 04, 05 |
| [EP-07](issues/07-live-scale-backend.md) / [#11](https://github.com/code2hack/EyeProjector/issues/11) | Apply live scale to the actual projected app layout | 03 |
| [EP-08](issues/08-per-app-scale.md) / [#12](https://github.com/code2hack/EyeProjector/issues/12) | Pinch and save scale through the Phone gesture panel | 06, 07 |
| [EP-09](issues/09-pointer-settings.md) / [#13](https://github.com/code2hack/EyeProjector/issues/13) | Independent X/Y gains and head-scroll toggle | — |
| [EP-10](issues/10-native-taps-scroll.md) / [#14](https://github.com/code2hack/EyeProjector/issues/14) | Ordinary tap counts and pointer-targeted pad scrolling | 03, 09 |
| [EP-11](issues/11-long-press-drag.md) / [#15](https://github.com/code2hack/EyeProjector/issues/15) | Continuous long press and head-driven dragging | 03, 10 |
| [EP-12](issues/12-function-button.md) / [#16](https://github.com/code2hack/EyeProjector/issues/16) | Function-button sequences and contextual navigation | 06, 10 |
| [EP-13](issues/13-hid-bindings.md) / [#17](https://github.com/code2hack/EyeProjector/issues/17) | Configure the four Glasses-connected HID bindings | 11, 12 |
| [EP-14](issues/14-panel-power.md) / [#18](https://github.com/code2hack/EyeProjector/issues/18) | Session-owned panel OFF, keep-active and restoration | 04 |
| [EP-15](issues/15-phone-widget.md) / [#19](https://github.com/code2hack/EyeProjector/issues/19) | Two-button Phone widget | 04, 14 |
| [EP-16](issues/16-phone-ime.md) / [#20](https://github.com/code2hack/EyeProjector/issues/20) | Usable Phone IME on the projection display | 06, 08, 11 |
| [EP-17](issues/17-video-stability.md) / [#21](https://github.com/code2hack/EyeProjector/issues/21) | Resolve decoder stalls and verify sustained/no-input projection | 03 |
| [EP-18](issues/18-bluetooth-qualification.md) / [#22](https://github.com/code2hack/EyeProjector/issues/22) | Measure Bluetooth playback, microphone routes and AV timing | 06, 10, 17 |
| [EP-19](issues/19-version-acceptance.md) / [#23](https://github.com/code2hack/EyeProjector/issues/23) | Verify the assembled version with the four target apps | 08, 09, 13, 15, 16, 17, 18 |
| [EP-20](issues/20-camera-experiment.md) / [#24](https://github.com/code2hack/EyeProjector/issues/24) | Late virtual-camera investigation and evidence | 19, by Owner-requested order |

During implementation, #6 acquired source-composition dependencies on #5/#9, and #14 on #7/#13. Their feature-local work was already underway; the composed candidates retain explicit prerequisite commits and wait for those issues' accepted corrections and integration. These working branches do not establish acceptance.

An issue's implementation blockers apply to new dispatch and integration, not just merge order. Work progresses when its required implementation interfaces land; there is no whole-wave barrier. The Owner's later autonomous-work direction below distinguishes implementation readiness from deferred human-only acceptance.

## Autonomous work while the Owner sleeps

The Owner authorized ADB controls, UI automation, logs, dumps and screenshots on both devices, asked agents to continue implementation, and directed genuinely Owner-only gates to be recorded for the next actual Owner message. Routine wake/music alarms and human prompts are suppressed during this period. The Manager retains exclusive device scheduling and installed-state preservation.

An exact independently reviewed source/host candidate can be accepted for **implementation integration** on `version/v0.1` with its outstanding evidence recorded explicitly. Dependent implementation can then proceed against that integrated interface. This scope does not close an issue's unresolved acceptance or claim a device pass. Open machine failures receive their own diagnosis and affected verification; deferred genuine pad/HID, optical, hearing and ergonomic judgments stay visible in the canonical issue and run record. Synthetic ADB evidence states what it actually exercised.

The Manager records integration readiness, full issue acceptance and assembled-version acceptance separately. Reviewers remain associated with open issues. Core product criteria and the final assembled-version gate in #23 are retained. New guard proposals still follow the Owner-approval procedure; unanswered proposals expire as denied, with any Owner question deferred until the Owner returns. Protected-document and final publication approvals remain their existing gates.

## Concurrency and write ownership

The initial independent lanes were **EP-01, EP-02, EP-03, EP-05, EP-09 and EP-10**. The Owner chose unlimited Worker and Reviewer concurrency. Every role uses Codex / `gpt-6.1-sol` / `ultra` / Full Access. Shared-file integration, available build resources and exclusive device windows are scheduled separately. The [version run record](../../runs/v0.1.md) records the remaining runtime decisions.

| Shared area | Coordination |
| --- | --- |
| `Wire.kt`, `Tls.kt` and protocol tests | EP-03 owns the video-only revision; EP-02 owns peer establishment. Later control additions use short assigned integration windows and coordinated operation IDs. Both endpoints are verified together. |
| `ShellMain.kt` and host session state | EP-01 adds the Shizuku adapter beside the bridge. EP-03 owns media removal. EP-04 owns session orchestration. Scale, launch, touch and panel operations integrate into the current merged host in assigned windows. |
| `HostActivity.kt`, host manifest/resources | Feature logic stays with its issue's component/store. Pairing, app list, scale and widget Workers coordinate the small Activity/manifest changes. |
| `ReceiverActivity.kt` | Settings, pad input, function input and pairing have separate components. One issue at a time owns shared dispatch/lifecycle integration edits. |
| `ProjectionSession.kt`, `SurfaceDecoder.kt` | EP-03 owns media readiness/timing changes; EP-17 follows with evidence-driven decoder corrections. Input Workers consume the current session interface. |
| Phone, Glasses, HID and shared ADB routes | The Manager records exclusive operation windows. Source work can proceed concurrently while physical checks queue for the available devices. |

Agree the minimum shared interfaces in the owning issue before dependent dispatch: peer identity versus current endpoint; backend/session state and Start/Stop; fixed launch/scale/panel operations; and touch DOWN/MOVE/UP/CANCEL plus scroll/navigation. Reuse existing code and extract only the components needed for these features.

A successful build is not a physical pass. Each Worker supplies its exact commit, artifact hashes, affected automated checks and any reserved-device evidence; the Reviewer assesses that same candidate. Rebase/integrate against the latest version branch and repeat affected checks after conflicting changes.

## Run preparation and decisions

- The Manager records the authorized version run, concurrency, human test windows and notification/alarm policy, then uses the existing [manager-runtime skill](../../../.agents/skills/manager-runtime/SKILL.md). Verify one actual scheduled watchdog wake and resulting Manager progress report during implementation.
- Refresh live device custody and coordinate access before physical work. Prior panel-power and long-touch results are evidence inputs; they do not reserve devices or establish EyeProjector acceptance. The [provenance index](../../provenance/README.md) retains the pending source and handoff boundaries.
- Record the actual installation/version of **ChatGPT, Paseo, Comet and WeChat**, the Phone IME and the HID peripheral used. Establish concrete representative workflows during test preparation.
- Defaults in the authorized issue set: a fresh projection opens the chosen-app home; a connection failure returns the Glasses to the control panel for explicit Connect. Pairing and saved app/input preferences persist.
- Existing product authentication and lifecycle requirements apply. Any proposed additional guard follows the Owner-decision procedure already defined in AGENTS.md.
- Investigations that expose a limitation in a core requirement report the concrete result for disposition. Only virtual-camera success is explicitly optional. EP-20 can close with reproducible evidence of a platform limitation.

## SPEC coverage

| SPEC area | Owning issues |
| --- | --- |
| Shizuku startup and recovery guidance | 01, 04, 15 |
| LAN pairing, remembered trust and Forget | 02, 04 |
| Two control panels and monochrome EyeProjector UI | 02, 04, 06, 09, 13 |
| Chosen apps, launch/switch, Home and taskbar-free display | 05, 06 |
| Video-only transport and native Bluetooth audio | 03, 17, 18 |
| Touchpad, pointer, function button and HID | 09–13 |
| Per-app scale and Phone IME | 07, 08, 16 |
| Panel OFF, inactivity prevention and Phone widget | 14, 15, 17, 19 |
| Lifetime, failure release and reconnection | 04, 11, 14, 17, 19 |
| Authenticated endpoints and protocol bounds | 02, 03, 11, 14, 19 |
| Target-app compatibility and physical acceptance | 18, 19 |
| Experimental virtual camera | 20 |

The two earlier withdrawn v0.1 tracker issues remain historical records. Canonical issues #5–#24 are the authorized implementation set.
