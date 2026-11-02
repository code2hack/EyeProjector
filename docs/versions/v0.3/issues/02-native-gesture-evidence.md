# V03-02: Identify native two-finger navigation reports

Status: **Published; implementation authorized.** Canonical issue: [#112](https://github.com/code2hack/EyeProjector/issues/112). Actual dispatch and review status live in that issue and the private runtime.

Blocked by: Version plan authorization only; independent lane.

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). The Owner authorized the published plan to start on2026-10-31; its documented completion behavior is the implementation direction. Unrelated new restrictions retain the AGENTS approval procedure.

## Objective

Observe how the actual Rokid firmware delivers or handles two-finger Back, Home and Lock.

## Scope

- A minimal key/motion/broadcast metadata observation path, independent of the production navigation adapter.
- Report identity/order, finger counts if delivered, duplicate summaries and whether firmware acts before the app.
- Compare two-finger operations with ordinary one-finger pad input and existing Function reports.

## Acceptance and evidence

- Retain actual device observations for the three Owner-specified operations.
- Do not infer two-finger Back from the existing pad key4 double-tap alias.
- Document any firmware-owned/unobservable behavior and exact capability limit; no invented broadcast or successful-interception claim.

## Dispatch boundary

The Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. Source/device/public operations remain bound to that recorded assignment and its resources.
