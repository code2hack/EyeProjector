# V03-07: Build the production display-scoped UI target reader and geometry mapping

Status: **Published; implementation authorized.** Canonical issue: [#117](https://github.com/code2hack/EyeProjector/issues/117). Actual dispatch and review status live in that issue and the private runtime.

Blocked by: [V03-01](01-managed-ui-access.md)

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). The Owner authorized the published plan to start on2026-10-31; its documented completion behavior is the implementation direction. Unrelated new restrictions retain the AGENTS approval procedure.

## Objective

Provide live actionable-target snapshots and coordinate transforms for the correct projection window.

## Scope

- Consume the proven authorized UI-access route from V03-01.
- Produce a narrow target snapshot model with display/window/session and observed node properties/bounds.
- Map source logical bounds through layout, encoded video and Receiver pointer spaces.

## Acceptance and evidence

- Production code reads the current managed source and does not substitute a default-display tree.
- Bounds agree through exercised scale/density, rotation, insets and IME cases.
- No persistent record stores a live node or assumes raw pixel bounds are durable target identity.

## Dispatch boundary

The Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. Source/device/public operations remain bound to that recorded assignment and its resources.
