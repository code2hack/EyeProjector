# V03-01: Prove UI access on the managed projection display

Status: **Published; implementation authorized.** Canonical issue: [#111](https://github.com/code2hack/EyeProjector/issues/111). Actual dispatch and review status live in that issue and the private runtime.

Blocked by: Version plan authorization only; independent lane.

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). The Owner authorized the published plan to start on2026-10-31; its documented completion behavior is the implementation direction. Unrelated new restrictions retain the AGENTS approval procedure.

## Objective

Establish an authorized way to observe actionable UI nodes on the actual managed source before committing to a production reader.

## Scope

- A minimal evidence harness owned by this issue, independent of the later production reader.
- Display/window identity, actionable-node bounds and coordinate agreement on an owned fixture and selected app screen.
- Actual setup/permission and physical-Phone-off/locked behavior where supported; metadata only, no conversation dumps.

## Acceptance and evidence

- Evidence identifies the real projection display and window rather than only the physical default display.
- Show actual actionable-node and coordinate observations, or an exact reproducible platform limitation.
- The harness has its own cleanup; completing this feasibility issue does not claim that Anchor is implemented.

## Dispatch boundary

The Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. Source/device/public operations remain bound to that recorded assignment and its resources.
