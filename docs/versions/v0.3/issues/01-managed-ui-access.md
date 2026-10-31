# V03-01: Prove UI access on the managed projection display

Status: **Draft issue outline; not a canonical GitHub issue or implementation assignment.**

Blocked by: Version plan authorization only; independent lane.

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). Owner-defined behavior and marked completion/validation proposals retain their different status; this outline does not approve a proposal.

## Objective

Establish an authorized way to observe actionable UI nodes on the actual managed source before committing to a production reader.

## Proposed scope

- A minimal evidence harness owned by this issue, independent of the later production reader.
- Display/window identity, actionable-node bounds and coordinate agreement on an owned fixture and selected app screen.
- Actual setup/permission and physical-Phone-off/locked behavior where supported; metadata only, no conversation dumps.

## Proposed acceptance and evidence

- Evidence identifies the real projection display and window rather than only the physical default display.
- Show actual actionable-node and coordinate observations, or an exact reproducible platform limitation.
- The harness has its own cleanup; completing this feasibility issue does not claim that Anchor is implemented.

## Dispatch boundary

After the Owner authorizes the plan, the Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. This outline authorizes no source, device or public issue operation by itself.
