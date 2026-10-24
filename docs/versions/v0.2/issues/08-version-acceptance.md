# V02-08 — Verify the assembled v0.2 launch, notification, input and camera flows

**Canonical issue:** Pending publication. **Blocked by:** V02-01, V02-02, V02-03, V02-04, V02-05, V02-06, V02-07.

**Objective**

Verify the integrated v0.2 candidate on the actual Phone/Glasses/HID combination and publish source/artifact-bound results for the requested goals.

**Scope**

Version compatibility/run report and minimal focused reproduction fixtures; substantive defects return to their owning issue.

**Source ownership**

docs/runs/v0.2.md compatibility evidence and focused version-owned fixtures; Manager publication and device scheduling.

**Acceptance**

- Exercise app-launch auto connection, transient loss/retry, actual Glasses sleep continuity and wake, HID reattachment and whole wake-only presses.
- Exercise per-app menu preference persistence, enabled/disabled real notifications and notifying-app launch with concurrent/active-session cases.
- Compare native physical latency before/after while retaining the agreed gesture and Function mappings.
- Verify real Glasses preview/photo and continuous-video/call flows where the chosen apps expose them; include close/reopen and cleanup.
- Bind exact current source, build/test/lint, signed built/installed mains, device/OS identities, observation method and measured intervals; distinguish host, synthetic, physical and Owner evidence.
- No silent retreat to optional v0.1 camera feasibility. Any demonstrated unsupported core requirement is returned to the Owner for an explicit scope decision before version completion.

**Verification and coordination**

Use exact current source/artifact bindings, focused meaningful checks and an independent Reviewer. PRs target `version/v0.2`. Shared source regions, host build/SDK services and physical devices use Manager allocations. Initial source work is host-only until a concrete reserved device window is assigned. Preserve the v0.1 release and installed data/bindings. Existing authentication, resource cleanup and Owner-approved requirements stay in force; new safeguards/verification guards require the existing Owner decision procedure.
