# V03-09: Persist one Anchor per app and add its settings entry

Status: **Published; implementation authorized.** Canonical issue: [#119](https://github.com/code2hack/EyeProjector/issues/119). Actual dispatch and review status live in that issue and the private runtime.

Blocked by: [V03-08](08-anchor-target-resolution.md)

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). The Owner authorized the published plan to start on2026-10-31; its documented completion behavior is the implementation direction. Unrelated new restrictions retain the AGENTS approval procedure.

## Objective

Save the selected target descriptor and explicit Click/Hold mode using package-level app identity.

## Scope

- One package -> one Anchor descriptor/mode, alongside existing independent scale/notification preferences.
- Save/replace/reload and the proposed Anchor menu/Clear operation.
- Expose setting-change notifications through the shared contact-owner contract, not a second execution owner.

## Acceptance and evidence

- Restart round-trips the one-per-app target and user-chosen mode without storing live nodes/contacts.
- Incomplete replacement and Clear/app-removal semantics follow the marked proposal under the authorized plan.
- Storage/menu work does not depend back on completed Hold execution; later contact integration consumes its typed change contract.

## Dispatch boundary

The Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. Source/device/public operations remain bound to that recorded assignment and its resources.
