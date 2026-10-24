# v0.2 implementation plan

Status: Owner-authorized implementation, 2026-10-25 (SGT). Integration branch: `version/v0.2`, based on the completed v0.1 main/tag `aff9ebd62bdb779af0eb02e5e61ea4842e9af371`. [SPEC.md](../../../SPEC.md) defines the approved behavior; current assignments and resources live in the private runtime. Canonical issue numbers are filled after publication.

## Scope and decisions

- Paired Glasses app launch automatically connects/retries. Phone HID reconnection or a wake press requests actual Glasses wake/reconnect/projection.
- Glasses sleep preserves the authenticated connection and Phone display/app state; wake restores fresh rendering/input.
- A HID press used for wake only wakes; its assigned action is not replayed.
- New enabled chosen-app notifications wake/connect and open the notifying app. Each Phone icon long-press menu has a persistent Glasses notifications toggle beside Remove and current-context Scale. Phone OS notifications are independent.
- Remove avoidable native touch latency with real firmware/event/target timing and correct gestures.
- Deliver the real Glasses-to-Phone virtual-camera foundation for preview/photo and continuous video/call flows in supported apps. Start with actual stock-Phone producer/consumer access; a platform blocker needs Owner disposition rather than being counted as camera success.

Initial notification defaults are On for chosen apps; explicit saved choices override that default. Enabled notifications use the existing active session where available. These are recorded implementation defaults following the requested notifying-app launch behavior, not new restrictions. Explicit Stop/Disconnect ends the current activation/retry; a new allowed trigger may request another activation.

## Small issues and dependencies

| Plan ID | Deliverable | Blocked by |
| --- | --- | --- |
| V02-01 | [Keep paired projection alive through Glasses sleep and wake](issues/01-sleep-continuity.md) | — |
| V02-02 | [Auto-connect on launch and wake projection from Phone HID](issues/02-automatic-activation.md) | V02-01 |
| V02-03 | [Add per-app Glasses notifications toggle to the Phone app menu](issues/03-notification-preferences.md) | — |
| V02-04 | [Wake Glasses and open the notifying app for enabled notifications](issues/04-notification-activation.md) | V02-02, V02-03 |
| V02-05 | [Remove seconds-scale delay from native Rokid touch input](issues/05-native-input-latency.md) | — |
| V02-06 | [Prove the stock Phone virtual-camera producer and consumer path](issues/06-virtual-camera-access.md) | — |
| V02-07 | [Feed the Glasses camera into Phone app photo and video flows](issues/07-glasses-camera-feed.md) | V02-06 |
| V02-08 | [Verify the assembled v0.2 launch, notification, input and camera flows](issues/08-version-acceptance.md) | V02-01, V02-02, V02-03, V02-04, V02-05, V02-06, V02-07 |

The first independent issues are V02-01,03,05,06. Dependent source work starts as its required interface is integrated; there is no whole-wave barrier. Shared Receiver Activity/lifetime and protocol regions are assigned to one source writer at a time. Native input can begin in its dedicated recognizer/adapter files while the lifetime Worker owns Activity hooks.

## Execution policy

The Manager carries forward the Owner's standing unlimited Worker/Reviewer concurrency preference and Codex / gpt-6.1-sol / ultra / full-access profiles. Resource-heavy shared builds and device windows are scheduled separately. Every issue has one assigned Worker/dedicated Paseo worktree and one independent Reviewer when dispatched; PRs target this version branch.

Written notifications cover material results and concrete Owner decisions. Preserve the existing Owner policy to resume Phone music for genuinely required Owner action, subject to current device custody. Ordinary source work does not require Owner prompts. Foreseeable human/device gates are actual service permissions/setup where Android requires them, physical HID/native input and camera/app observations, and any demonstrated platform limitation requiring a product decision. Discover current cross-project custody before operating shared devices; prior routes/measurements are historical hints.

No new retry cap, session expiry, inactivity reset, security safeguard or device-verification guard is introduced by this plan. Individual commands and tests remain bounded. Proposed additional guards use AGENTS.md's explicit Owner procedure and timeout handling.

## Completion boundary

Integration requires exact changed-head independent review and affected current verification; shared protocol changes cover both consumers. Final v0.2 acceptance requires the assembled goals in V02-08 and an Owner decision, with actual limitations recorded honestly. Feasibility-only camera evidence and host-only sleep/input models do not establish the working device feature. Preserve v0.1 history, data/bindings and app compatibility while improving the new goals.
