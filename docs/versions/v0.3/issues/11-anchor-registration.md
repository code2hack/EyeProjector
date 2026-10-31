# V03-11: Implement hover registration and the explicit Click/Hold chooser

Status: **Draft issue outline; not a canonical GitHub issue or implementation assignment.**

Blocked by: [V03-07](07-ui-target-reader.md), [V03-08](08-anchor-target-resolution.md), [V03-09](09-anchor-preferences.md), [V03-10](10-function-intent-grammar.md)

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). Owner-defined behavior and marked completion/validation proposals retain their different status; this outline does not approve a proposal.

## Objective

Turn a hovered actionable target into the app sole saved Anchor after explicit mode selection.

## Proposed scope

- Capture the hovered target through V03-07/08 and consume the registration intention.
- Always offer Click and Hold, irrespective of advertised node actions; save via V03-09.
- Proposed local highlight/chooser, Cancel, completion feedback and no click-through.

## Proposed acceptance and evidence

- Every completed registration records a deliberate Click/Hold choice and one target for that app.
- Test proposed cancellation/failed-registration retention and registration side-effect rules once the plan accepts them.
- No classifier substitutes an inferred mode, and the chooser does not create a held execution contact.

## Dispatch boundary

After the Owner authorizes the plan, the Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. This outline authorizes no source, device or public issue operation by itself.
