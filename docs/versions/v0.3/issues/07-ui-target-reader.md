# V03-07: Build the production display-scoped UI target reader and geometry mapping

Status: **Draft issue outline; not a canonical GitHub issue or implementation assignment.**

Blocked by: [V03-01](01-managed-ui-access.md)

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). Owner-defined behavior and marked completion/validation proposals retain their different status; this outline does not approve a proposal.

## Objective

Provide live actionable-target snapshots and coordinate transforms for the correct projection window.

## Proposed scope

- Consume the proven authorized UI-access route from V03-01.
- Produce a narrow target snapshot model with display/window/session and observed node properties/bounds.
- Map source logical bounds through layout, encoded video and Receiver pointer spaces.

## Proposed acceptance and evidence

- Production code reads the current managed source and does not substitute a default-display tree.
- Bounds agree through exercised scale/density, rotation, insets and IME cases.
- No persistent record stores a live node or assumes raw pixel bounds are durable target identity.

## Dispatch boundary

After the Owner authorizes the plan, the Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. This outline authorizes no source, device or public issue operation by itself.
