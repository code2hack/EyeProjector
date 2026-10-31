# V03-06: Expose navigation/Lock bindings and preserve saved HID configuration

Status: **Draft issue outline; not a canonical GitHub issue or implementation assignment.**

Blocked by: [V03-03](03-input-contracts.md), [V03-04](04-contextual-navigation.md)

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). Owner-defined behavior and marked completion/validation proposals retain their different status; this outline does not approve a proposal.

## Objective

Let the user learn the new bindings on both Phone and Glasses HID routes.

## Proposed scope

- Binding panels and role persistence for the six roles, preserving Pin and existing Tap/Scroll configuration.
- Back/Home single/double press navigation and separate Lock.
- Existing Function maps to its new Function intent role; Back/Home and Lock start unassigned.

## Proposed acceptance and evidence

- Learned Back/Home single/double and Lock route to V03-04 through both HID paths.
- Repeat/UP/CANCEL stay associated with the original physical press and do not multiply actions.
- Reopen/reconnect/migration preserve unrelated assignments and device ownership; no default physical button is inferred.

## Dispatch boundary

After the Owner authorizes the plan, the Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. This outline authorizes no source, device or public issue operation by itself.
