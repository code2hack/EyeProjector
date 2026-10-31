# V03-09: Persist one Anchor per app and add its settings entry

Status: **Draft issue outline; not a canonical GitHub issue or implementation assignment.**

Blocked by: [V03-08](08-anchor-target-resolution.md)

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). Owner-defined behavior and marked completion/validation proposals retain their different status; this outline does not approve a proposal.

## Objective

Save the selected target descriptor and explicit Click/Hold mode using package-level app identity.

## Proposed scope

- One package -> one Anchor descriptor/mode, alongside existing independent scale/notification preferences.
- Save/replace/reload and the proposed Anchor menu/Clear operation.
- Expose setting-change notifications through the shared contact-owner contract, not a second execution owner.

## Proposed acceptance and evidence

- Restart round-trips the one-per-app target and user-chosen mode without storing live nodes/contacts.
- Incomplete replacement and Clear/app-removal semantics follow the marked proposal once accepted for execution.
- Storage/menu work does not depend back on completed Hold execution; later contact integration consumes its typed change contract.

## Dispatch boundary

After the Owner authorizes the plan, the Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. This outline authorizes no source, device or public issue operation by itself.
