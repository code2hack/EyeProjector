# V03-06: Expose navigation/Lock bindings and preserve saved HID configuration

Status: **Published; implementation authorized.** Canonical issue: [#116](https://github.com/code2hack/EyeProjector/issues/116). Actual dispatch and review status live in that issue and the private runtime.

Blocked by: [V03-03](03-input-contracts.md), [V03-04](04-contextual-navigation.md)

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). The Owner authorized the published plan to start on2026-10-31; its documented completion behavior is the implementation direction. Unrelated new restrictions retain the AGENTS approval procedure.

## Objective

Let the user learn the new bindings on both Phone and Glasses HID routes.

## Scope

- Binding panels and role persistence for the six roles, preserving Pin and existing Tap/Scroll configuration.
- Back/Home single/double press navigation and separate Lock.
- Existing Function maps to its new Function intent role; Back/Home and Lock start unassigned.

## Acceptance and evidence

- Learned Back/Home single/double and Lock route to V03-04 through both HID paths.
- Repeat/UP/CANCEL stay associated with the original physical press and do not multiply actions.
- Reopen/reconnect/migration preserve unrelated assignments and device ownership; no default physical button is inferred.

## Dispatch boundary

The Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. Source/device/public operations remain bound to that recorded assignment and its resources.
