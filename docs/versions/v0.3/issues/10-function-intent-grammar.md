# V03-10: Replace Function navigation with short/long Anchor intentions

Status: **Published; implementation authorized.** Canonical issue: [#120](https://github.com/code2hack/EyeProjector/issues/120). Actual dispatch and review status live in that issue and the private runtime.

Blocked by: [V03-03](03-input-contracts.md)

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). The Owner authorized the published plan to start on2026-10-31; its documented completion behavior is the implementation direction. Unrelated new restrictions retain the AGENTS approval procedure.

## Objective

Classify each original Function gesture into activation or registration without owning the Anchor output contact.

## Scope

- Native, Phone HID and Glasses HID Function paths.
- Short press -> activation intent; long press -> registration intent; remove old Function Back/Home/triple-Lock dispatch.
- Original context/gesture identity, duplicate-summary handling and ordered repeated activation delivery.

## Acceptance and evidence

- Production-used grammar emits intentions through the shared contract rather than invoking navigation.
- Exercise the proposed long-release/no-extra-activation behavior, under the authorized plan.
- Two short activations retain order and are not coalesced into one intent or a Home action.

## Dispatch boundary

The Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. Source/device/public operations remain bound to that recorded assignment and its resources.
