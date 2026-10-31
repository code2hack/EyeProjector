# V03-10: Replace Function navigation with short/long Anchor intentions

Status: **Draft issue outline; not a canonical GitHub issue or implementation assignment.**

Blocked by: [V03-03](03-input-contracts.md)

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). Owner-defined behavior and marked completion/validation proposals retain their different status; this outline does not approve a proposal.

## Objective

Classify each original Function gesture into activation or registration without owning the Anchor output contact.

## Proposed scope

- Native, Phone HID and Glasses HID Function paths.
- Short press -> activation intent; long press -> registration intent; remove old Function Back/Home/triple-Lock dispatch.
- Original context/gesture identity, duplicate-summary handling and ordered repeated activation delivery.

## Proposed acceptance and evidence

- Production-used grammar emits intentions through the shared contract rather than invoking navigation.
- Exercise the proposed long-release/no-extra-activation behavior, pending plan approval.
- Two short activations retain order and are not coalesced into one intent or a Home action.

## Dispatch boundary

After the Owner authorizes the plan, the Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. This outline authorizes no source, device or public issue operation by itself.
