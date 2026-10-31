# V03-13: Extend the same owner with stationary Hold and complete retirement

Status: **Draft issue outline; not a canonical GitHub issue or implementation assignment.**

Blocked by: [V03-04](04-contextual-navigation.md), [V03-12](12-anchor-click-owner.md)

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). Owner-defined behavior and marked completion/validation proposals retain their different status; this outline does not approve a proposal.

## Objective

Keep one selected target contact down across physical Function release and release it on the next activation.

## Proposed scope

- Extend the V03-12 owner; no parallel or competing contact latch.
- First activation -> DOWN; next -> original UP without a fresh target match; head motion does not MOVE it.
- Include original-context/window/input handoff, navigation, resize/scale, settings changes and session close retirement in this first Hold candidate.

## Proposed acceptance and evidence

- Physical Function release leaves the logical contact held; next activation releases the same contact exactly once.
- UP of an existing held contact does not require a fresh resolver/storage lookup or a new node identity.
- Real production cancellation/close/context paths retire the latch in this candidate; V03-14 is not the first meaningful retirement test.
- No new hold timeout/cap or automatic replay is introduced; app-observed outcomes remain separate from input submission.

## Dispatch boundary

After the Owner authorizes the plan, the Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. This outline authorizes no source, device or public issue operation by itself.
