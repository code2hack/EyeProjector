# V03-04: Implement contextual Back, Home and Lock dispatch

Status: **Published; implementation authorized.** Canonical issue: [#114](https://github.com/code2hack/EyeProjector/issues/114). Actual dispatch and review status live in that issue and the private runtime.

Blocked by: [V03-03](03-input-contracts.md)

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). The Owner authorized the published plan to start on2026-10-31; its documented completion behavior is the implementation direction. Unrelated new restrictions retain the AGENTS approval procedure.

## Objective

Implement action destinations independently of whether input originated from a native gesture or HID.

## Scope

- Back uses current app/local context.
- Home follows app -> EyeProjector app grid -> Glasses control panel -> Rokid Home.
- Lock uses Glasses screen sleep/lock and retains the projection/workspace; integrate existing input retirement.

## Acceptance and evidence

- Actual production dispatch follows all three Home destinations and does not use Function as navigation.
- Lock is distinguished from Stop/Disconnect/app exit.
- Home transition lifetime/settings-subpage proposals follow the authorized plan; original input retirement is included in the candidate.

## Dispatch boundary

The Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. Source/device/public operations remain bound to that recorded assignment and its resources.
