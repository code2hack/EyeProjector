# V02-01 — Keep paired projection alive through Glasses sleep and wake

**Canonical issue:** [#62](https://github.com/code2hack/EyeProjector/issues/62). **Blocked by:** None.

**Objective**

Retain the authenticated Phone/Glasses connection, Phone virtual display and app state through actual Glasses sleep and wake; reattach fresh rendering without manual reconnect.

**Scope**

One Receiver session/render lifetime owner; ReceiverActivity, PairedSessionClient, ProjectionSession and narrow service/manifest lifecycle wiring. Preserve actual Stop/failure release and native/Phone HID context cancellation.

**Source ownership**

ReceiverActivity.kt lifetime/render hooks; PairedSessionClient.kt; ProjectionSession.kt and feature-local lifetime/service helpers; Receiver manifest/resources and affected tests.

**Acceptance**

- Glasses sleep/pause/Surface detachment no longer submits an unintended STOP/CANCEL or destroys the Phone virtual display.
- Actual rendering can detach and reattach; received video while detached has an explicit supported lifecycle rather than a fake valid Surface/decoder.
- Wake resumes fresh current video and subsequent usable input in the same retained app/display; pairing and explicit Stop/Disconnect/exit still release correctly.
- Meaningful source tests cover detach/reattach, old callbacks, Stop/failure and repeated transitions; reserved-device evidence distinguishes sleep continuity from reconnect.

**Verification and coordination**

Use exact current source/artifact bindings, focused meaningful checks and an independent Reviewer. PRs target `version/v0.2`. Shared source regions, host build/SDK services and physical devices use Manager allocations. Initial source work is host-only until a concrete reserved device window is assigned. Preserve the v0.1 release and installed data/bindings. Existing authentication, resource cleanup and Owner-approved requirements stay in force; new safeguards/verification guards require the existing Owner decision procedure.
