# V03-04: Implement contextual Back, Home and Lock dispatch

Status: **Draft issue outline; not a canonical GitHub issue or implementation assignment.**

Blocked by: [V03-03](03-input-contracts.md)

Decision references: [v0.3 specification](../SPEC.md), [draft plan](../README.md). Owner-defined behavior and marked completion/validation proposals retain their different status; this outline does not approve a proposal.

## Objective

Implement action destinations independently of whether input originated from a native gesture or HID.

## Proposed scope

- Back uses current app/local context.
- Home follows app -> EyeProjector app grid -> Glasses control panel -> Rokid Home.
- Lock uses Glasses screen sleep/lock and retains the projection/workspace; integrate existing input retirement.

## Proposed acceptance and evidence

- Actual production dispatch follows all three Home destinations and does not use Function as navigation.
- Lock is distinguished from Stop/Disconnect/app exit.
- Home transition lifetime/settings-subpage proposals are settled before implementing them; original input retirement is included in the candidate.

## Dispatch boundary

After the Owner authorizes the plan, the Manager allocates exact files/interfaces, a version-branch base, one Worker, an independent Reviewer, a dedicated worktree and evidence/resources. Shared writers and device windows are coordinated. This outline authorizes no source, device or public issue operation by itself.
