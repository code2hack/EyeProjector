# V03-08: Define persistent target descriptors and resolve them in the current screen

Status: **Published; implementation authorized.** Canonical issue: [#118](https://github.com/code2hack/EyeProjector/issues/118). Actual dispatch and review status live in that issue and the private runtime.

Blocked by: [V03-07](07-ui-target-reader.md)

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). The Owner authorized the published plan to start on2026-10-31; its documented completion behavior is the implementation direction. Unrelated new restrictions retain the AGENTS approval procedure.

## Objective

Reidentify a saved control from current target snapshots without depending on a preference implementation.

## Scope

- Define the serializable per-app target descriptor and explicit resolution result contract.
- Resolve an in-memory descriptor against the app current Activity/window; capture current bounds.
- Represent missing/ambiguous/unavailable results without selecting a different control.

## Acceptance and evidence

- An in-memory descriptor resolves after exercised node recreation and target movement.
- Descriptor resolution has no dependency on V03-09 preferences, avoiding a storage/resolver cycle.
- The chosen feedback/ambiguity policy remains as specified by the authorized plan; no automatic Click/Hold classification is added.

## Dispatch boundary

The Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. Source/device/public operations remain bound to that recorded assignment and its resources.
