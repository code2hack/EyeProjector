# V03-05: Route observed native gestures to navigation

Status: **Published; implementation authorized.** Canonical issue: [#115](https://github.com/code2hack/EyeProjector/issues/115). Actual dispatch and review status live in that issue and the private runtime.

Blocked by: [V03-02](02-native-gesture-evidence.md), [V03-03](03-input-contracts.md), [V03-04](04-contextual-navigation.md)

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). The Owner authorized the published plan to start on2026-10-31; its documented completion behavior is the implementation direction. Unrelated new restrictions retain the AGENTS approval procedure.

## Objective

Connect only the verified native event shapes to Back, Home and Lock.

## Scope

- Adapt the evidence from V03-02 into the actual native ingress.
- Deduplicate physical reports without reclassifying ordinary single-finger taps or Function input.
- Integrate app-visible and firmware-handled cases with their actual ordering/limits.

## Acceptance and evidence

- Actual device gestures invoke the intended navigation once in each exercised context.
- Ordinary pad tap/hold/scroll and native Function remain distinct.
- A firmware capability gap is reported for Owner disposition rather than replaced by an invented gesture.

## Dispatch boundary

The Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. Source/device/public operations remain bound to that recorded assignment and its resources.
