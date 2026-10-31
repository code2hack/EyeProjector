# V03-02: Identify native two-finger navigation reports

Status: **Draft issue outline; not a canonical GitHub issue or implementation assignment.**

Blocked by: Version plan authorization only; independent lane.

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). Owner-defined behavior and marked completion/validation proposals retain their different status; this outline does not approve a proposal.

## Objective

Observe how the actual Rokid firmware delivers or handles two-finger Back, Home and Lock.

## Proposed scope

- A minimal key/motion/broadcast metadata observation path, independent of the production navigation adapter.
- Report identity/order, finger counts if delivered, duplicate summaries and whether firmware acts before the app.
- Compare two-finger operations with ordinary one-finger pad input and existing Function reports.

## Proposed acceptance and evidence

- Retain actual device observations for the three Owner-specified operations.
- Do not infer two-finger Back from the existing pad key4 double-tap alias.
- Document any firmware-owned/unobservable behavior and exact capability limit; no invented broadcast or successful-interception claim.

## Dispatch boundary

After the Owner authorizes the plan, the Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. This outline authorizes no source, device or public issue operation by itself.
