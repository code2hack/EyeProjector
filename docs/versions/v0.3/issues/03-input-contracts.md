# V03-03: Define shared input roles and original-operation delivery contracts

Status: **Draft issue outline; not a canonical GitHub issue or implementation assignment.**

Blocked by: Version plan authorization only; independent lane.

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). Owner-defined behavior and marked completion/validation proposals retain their different status; this outline does not approve a proposal.

## Objective

Add the small matched-consumer contracts required for separate navigation and Anchor intent routing.

## Proposed scope

- Phone and Glasses HID roles: Tap, Scroll down, Scroll up, Back/Home, Lock and Function.
- Typed short/long Function intents with original source/context and ordered delivery; no second contact latch.
- A narrow original-contact execution/retirement contract reused by Click and Hold; target descriptors are defined in V03-08.

## Proposed acceptance and evidence

- Both endpoints encode/decode the same roles and intentions; assess wire/persistence compatibility explicitly.
- Existing physical Function assignments retain the Function role; new navigation/Lock roles are not guessed.
- Two activation intents cannot collapse through a latest-only timer/work slot, and successor context cannot replace captured original identity.

## Dispatch boundary

After the Owner authorizes the plan, the Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. This outline authorizes no source, device or public issue operation by itself.
