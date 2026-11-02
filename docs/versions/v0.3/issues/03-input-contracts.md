# V03-03: Define shared input roles and original-operation delivery contracts

Status: **Published; implementation authorized.** Canonical issue: [#113](https://github.com/code2hack/EyeProjector/issues/113). Actual dispatch and review status live in that issue and the private runtime.

Blocked by: Version plan authorization only; independent lane.

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). The Owner authorized the published plan to start on2026-10-31; its documented completion behavior is the implementation direction. Unrelated new restrictions retain the AGENTS approval procedure.

## Objective

Add the small matched-consumer contracts required for separate navigation and Anchor intent routing.

## Scope

- Phone and Glasses HID roles: Tap, Scroll down, Scroll up, Back/Home, Lock and Function.
- Typed short/long Function intents with original source/context and ordered delivery; no second contact latch.
- A narrow original-contact execution/retirement contract reused by Click and Hold; target descriptors are defined in V03-08.

## Acceptance and evidence

- Both endpoints encode/decode the same roles and intentions; assess wire/persistence compatibility explicitly.
- Existing physical Function assignments retain the Function role; new navigation/Lock roles are not guessed.
- Two activation intents cannot collapse through a latest-only timer/work slot, and successor context cannot replace captured original identity.

## Dispatch boundary

The Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. Source/device/public operations remain bound to that recorded assignment and its resources.
