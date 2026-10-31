# v0.3 draft implementation plan

Status: **Draft plan for Owner review, published alongside the v0.3 specification on 2026-10-31. Implementation has not started.** The [version specification](SPEC.md) records the Owner-defined behavior and explicitly marks the remaining completion/validation proposals. Publishing this plan does not approve those proposals or dispatch implementation.

Planning base: main `2085600efce27344f9849bf33b07a41f2da005b6`. The proposed integration branch is `version/v0.3`, to be created when execution is authorized. The IDs below are draft plan IDs with individual scope/acceptance outlines, not opened GitHub issues or assigned Workers.

## Goals

- Separate native/HID Back, Home and Lock from Function.
- Home follows projected app → EyeProjector app grid → Glasses control panel → Rokid Home.
- Register one persistent Anchor per app by hovering and long-pressing Function, always explicitly choosing Click or Hold.
- Activate without further aiming: Click once, or DOWN on one Function activation and original-contact UP on the next.

## Small issues and dependencies

Every row links to a small proposed issue containing its objective, scope, acceptance/evidence and explicit blockers. All lanes also require authorization of the implementation plan.

| Plan ID | Deliverable | Blocked by |
| --- | --- | --- |
| [V03-01](issues/01-managed-ui-access.md) | Prove UI access on the managed projection display | — |
| [V03-02](issues/02-native-gesture-evidence.md) | Identify native two-finger navigation reports | — |
| [V03-03](issues/03-input-contracts.md) | Define shared input roles and original-operation delivery contracts | — |
| [V03-04](issues/04-contextual-navigation.md) | Implement contextual Back, Home and Lock dispatch | V03-03 |
| [V03-05](issues/05-native-navigation-adapter.md) | Route observed native gestures to navigation | V03-02, V03-03, V03-04 |
| [V03-06](issues/06-hid-bindings-migration.md) | Expose navigation/Lock bindings and preserve saved HID configuration | V03-03, V03-04 |
| [V03-07](issues/07-ui-target-reader.md) | Build the production display-scoped UI target reader and geometry mapping | V03-01 |
| [V03-08](issues/08-anchor-target-resolution.md) | Define persistent target descriptors and resolve them in the current screen | V03-07 |
| [V03-09](issues/09-anchor-preferences.md) | Persist one Anchor per app and add its settings entry | V03-08 |
| [V03-10](issues/10-function-intent-grammar.md) | Replace Function navigation with short/long Anchor intentions | V03-03 |
| [V03-11](issues/11-anchor-registration.md) | Implement hover registration and the explicit Click/Hold chooser | V03-07, V03-08, V03-09, V03-10 |
| [V03-12](issues/12-anchor-click-owner.md) | Execute Click Anchors through one original-contact owner | V03-07, V03-08, V03-09, V03-10 |
| [V03-13](issues/13-anchor-hold-toggle.md) | Extend the same owner with stationary Hold and complete retirement | V03-04, V03-12 |
| [V03-14](issues/14-anchor-integration-regression.md) | Integrate Anchor feedback and verify cross-feature transitions | V03-04, V03-06, V03-11, V03-12, V03-13 |
| [V03-15](issues/15-assembled-device-verification.md) | Verify the assembled native/HID and real-app paths | V03-05, V03-06, V03-11, V03-12, V03-13, V03-14 |
| [V03-16](issues/16-owner-usability-acceptance.md) | Collect Owner usability decision and complete v0.3 | V03-15 |

## Parallel work and delivery contracts

The initial independent lanes are V03-01, V03-02 and V03-03. Their evidence/contracts unblock dependent work individually; there is no whole-wave barrier. The UI-access and native-gesture feasibility issues own their minimal evidence paths and do not depend on the later production reader/adapter.

V03-08 defines the persistent descriptor and resolves an in-memory descriptor. V03-09 stores it; the resolver does not depend on that preference store. V03-10 emits ordered short/long intentions rather than owning another contact latch. Registration V03-11 and Click V03-12 can run concurrently after their typed dependencies are accepted, with disjoint source ownership.

V03-12 establishes one execution/contact owner. V03-13 extends that same owner with Hold and includes complete original-contact retirement in its first candidate. V03-14 verifies cross-feature integration and feedback; it is not a place to postpone basic Hold cancellation. Existing FunctionWork latest-pending coalescing cannot collapse the two activations of a hold toggle.

## Source ownership and review

Before each dispatch, the Manager refreshes current source/decisions and assigns the exact base, issue branch/worktree, Worker, independent Reviewer, acceptance and evidence. Branches/PRs target the eventual version branch. Shared protocol roles, binding persistence, PhoneInputMerger/FunctionWork, Session invalidation, ReceiverActivity and manifests have one allocated writer at a time. New helper files alone do not make conflicting ownership patches independent.

Each implementation candidate includes the affected production-path checks and its own retirement behavior. Assembled verification covers the joins, rather than deferring meaningful cancellation tests until the final issue. Changed heads receive renewed review. Shared protocol/persistence changes assess Phone and Glasses consumers and old saved settings.

## Decisions and authorization before execution

The Owner has specified the controls, one Anchor per app, always-explicit Click/Hold registration and contextual Home destinations. Marked specification proposals still need disposition as part of plan approval: Home transition lifetimes/settings subpages, chooser/cancel behavior, missing/ambiguous-target feedback, held-contact cancellation details, Clear Anchor and app-removal preference retention. Proposed app/walking/regression test selection is also visible rather than silently made mandatory.

Carry forward standing Owner concurrency/profile and material written-notification preferences where applicable. Reconcile the current alarm/human-step policy rather than silently renewing a historical sleep override. Foreseeable human steps are real Android UI-access consent/setup where required, physical native/HID gestures and the proposed usability trial. Source work and actual device verification remain separate.

After execution authorization, record the version plan, issue dependencies, identities and resource reservations in the private runtime; create the version branch/canonical issues, register the verified Manager with the existing manager-watchdog and verify its route/timer using the manager-runtime procedure. Publishing requirements is not version implementation or release acceptance.

## Evidence and completion

Read-only UI-access evidence must identify the actual managed display/window and actionable control metadata. Native observations must identify actual firmware reports/handling. Neither source flags nor host doubles prove those device capabilities. A reproducible limitation completes its investigation objective but does not satisfy an unimplemented product requirement without Owner disposition.

The proposed assembled verification records exact source/artifact/device identities, selected app/controls, actual results and original cleanup limits. ChatGPT is an initial UI/Click candidate; select a genuinely hold-capable control for Hold. The proposed walking/head-motion trial answers the usability goal and does not invent a numeric jitter/latency threshold. Framework input acceptance is not a target-app acknowledgment.

The accepted v0.2 baseline remains the regression reference. No full CXR integration, phone-call coexistence project, camera/audio redesign, new hold timeout or retry cap is introduced. Any additional guard follows AGENTS.md; this draft does not approve one by omission. Final publication and cleanup follow the Owner assembled-version decision.
