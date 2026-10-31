# V03-08: Define persistent target descriptors and resolve them in the current screen

Status: **Draft issue outline; not a canonical GitHub issue or implementation assignment.**

Blocked by: [V03-07](07-ui-target-reader.md)

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). Owner-defined behavior and marked completion/validation proposals retain their different status; this outline does not approve a proposal.

## Objective

Reidentify a saved control from current target snapshots without depending on a preference implementation.

## Proposed scope

- Define the serializable per-app target descriptor and explicit resolution result contract.
- Resolve an in-memory descriptor against the app current Activity/window; capture current bounds.
- Represent missing/ambiguous/unavailable results without selecting a different control.

## Proposed acceptance and evidence

- An in-memory descriptor resolves after exercised node recreation and target movement.
- Descriptor resolution has no dependency on V03-09 preferences, avoiding a storage/resolver cycle.
- The chosen feedback/ambiguity policy remains subject to the specification marked proposals; no automatic Click/Hold classification is added.

## Dispatch boundary

After the Owner authorizes the plan, the Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. This outline authorizes no source, device or public issue operation by itself.
