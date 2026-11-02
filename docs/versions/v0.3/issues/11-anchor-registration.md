# V03-11: Implement hover registration and the explicit Click/Hold chooser

Status: **Published; implementation authorized.** Canonical issue: [#121](https://github.com/code2hack/EyeProjector/issues/121). Actual dispatch and review status live in that issue and the private runtime.

Blocked by: [V03-07](07-ui-target-reader.md), [V03-08](08-anchor-target-resolution.md), [V03-09](09-anchor-preferences.md), [V03-10](10-function-intent-grammar.md)

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). The Owner authorized the published plan to start on2026-10-31; its documented completion behavior is the implementation direction. Unrelated new restrictions retain the AGENTS approval procedure.

## Objective

Turn a hovered actionable target into the app sole saved Anchor after explicit mode selection.

## Scope

- Capture the hovered target through V03-07/08 and consume the registration intention.
- Always offer Click and Hold, irrespective of advertised node actions; save via V03-09.
- Proposed local highlight/chooser, Cancel, completion feedback and no click-through.

## Acceptance and evidence

- Every completed registration records a deliberate Click/Hold choice and one target for that app.
- Test proposed cancellation/failed-registration retention and registration side-effect rules under the authorized plan.
- No classifier substitutes an inferred mode, and the chooser does not create a held execution contact.

## Dispatch boundary

The Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. Source/device/public operations remain bound to that recorded assignment and its resources.
