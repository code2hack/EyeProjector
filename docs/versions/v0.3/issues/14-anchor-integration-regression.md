# V03-14: Integrate Anchor feedback and verify cross-feature transitions

Status: **Draft issue outline; not a canonical GitHub issue or implementation assignment.**

Blocked by: [V03-04](04-contextual-navigation.md), [V03-06](06-hid-bindings-migration.md), [V03-11](11-anchor-registration.md), [V03-12](12-anchor-click-owner.md), [V03-13](13-anchor-hold-toggle.md)

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). Owner-defined behavior and marked completion/validation proposals retain their different status; this outline does not approve a proposal.

## Objective

Verify the already owned Click/Hold and registration paths together with navigation, settings and ordinary input.

## Proposed scope

- Proposed held/unavailable/registration feedback consumes state from the single existing owner.
- Cross-feature regression through Tap/Scroll, app/Activity changes, scale, IME, Clear/replace, Home and Lock.
- No new contact state machine or deferred core cancellation implementation.

## Proposed acceptance and evidence

- The integrated UI reflects held/canceled/unavailable states without claiming an app action acknowledgment.
- All proposed transition semantics are accepted before being implemented or called a product gate.
- Exercise real production joins of the existing contracts; preserve pending/failed original cleanup rather than reporting synthetic success.

## Dispatch boundary

After the Owner authorizes the plan, the Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. This outline authorizes no source, device or public issue operation by itself.
