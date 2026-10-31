# V03-05: Route observed native gestures to navigation

Status: **Draft issue outline; not a canonical GitHub issue or implementation assignment.**

Blocked by: [V03-02](02-native-gesture-evidence.md), [V03-03](03-input-contracts.md), [V03-04](04-contextual-navigation.md)

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). Owner-defined behavior and marked completion/validation proposals retain their different status; this outline does not approve a proposal.

## Objective

Connect only the verified native event shapes to Back, Home and Lock.

## Proposed scope

- Adapt the evidence from V03-02 into the actual native ingress.
- Deduplicate physical reports without reclassifying ordinary single-finger taps or Function input.
- Integrate app-visible and firmware-handled cases with their actual ordering/limits.

## Proposed acceptance and evidence

- Actual device gestures invoke the intended navigation once in each exercised context.
- Ordinary pad tap/hold/scroll and native Function remain distinct.
- A firmware capability gap is reported for Owner disposition rather than replaced by an invented gesture.

## Dispatch boundary

After the Owner authorizes the plan, the Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. This outline authorizes no source, device or public issue operation by itself.
