# V03-13: Extend the same owner with stationary Hold and complete retirement

Status: **Published; implementation authorized.** Canonical issue: [#123](https://github.com/code2hack/EyeProjector/issues/123). Actual dispatch and review status live in that issue and the private runtime.

Blocked by: [V03-04](04-contextual-navigation.md), [V03-12](12-anchor-click-owner.md)

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). The Owner authorized the published plan to start on2026-10-31; its documented completion behavior is the implementation direction. Unrelated new restrictions retain the AGENTS approval procedure.

## Objective

Keep one selected target contact down across physical Function release and release it on the next activation.

## Scope

- Extend the V03-12 owner; no parallel or competing contact latch.
- First activation -> DOWN; next -> original UP without a fresh target match; head motion does not MOVE it.
- Include original-context/window/input handoff, navigation, resize/scale, settings changes and session close retirement in this first Hold candidate.

## Acceptance and evidence

- Physical Function release leaves the logical contact held; next activation releases the same contact exactly once.
- UP of an existing held contact does not require a fresh resolver/storage lookup or a new node identity.
- Real production cancellation/close/context paths retire the latch in this candidate; V03-14 is not the first meaningful retirement test.
- No new hold timeout/cap or automatic replay is introduced; app-observed outcomes remain separate from input submission.

## Dispatch boundary

The Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. Source/device/public operations remain bound to that recorded assignment and its resources.
