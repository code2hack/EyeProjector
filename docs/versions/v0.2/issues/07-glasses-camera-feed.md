# V02-07 — Feed the Glasses camera into Phone app photo and video flows

**Canonical issue:** Pending publication. **Blocked by:** V02-06.

**Objective**

Replace the proven synthetic camera producer with actual Glasses imagery carried over the authenticated direct connection and consumed by supported projected Phone apps.

**Scope**

Glasses camera acquisition/permission/lifecycle, matched typed frame/config/end transport, Phone producer surface/configuration and end-to-end preview/photo/continuous-video integration.

**Source ownership**

Feature-local Glasses capture and Phone producer, matched protocol frame/config/lifecycle additions and narrowly allocated Session consumers. Shared API/ownership contract agreed before integration.

**Acceptance**

- A supported target app shows current actual Glasses imagery in its normal camera preview and photo/Add photo camera result.
- The shared producer supports continuous video and available video-call flows; record actual app/version selection and unsupported cases explicitly.
- Preserve orientation, negotiated dimensions/formats, useful pacing and current-consumer ownership without a development-host relay or fake photo-file substitution.
- Camera close/reopen, projection stop/reconnect, permission failure and consumer replacement release old capture/forwarding resources and show fresh frames on recovery.
- Projection, native/Phone HID input and ordinary Bluetooth audio continue under the actual tested combinations; both protocol consumers get affected verification.

**Verification and coordination**

Use exact current source/artifact bindings, focused meaningful checks and an independent Reviewer. PRs target `version/v0.2`. Shared source regions, host build/SDK services and physical devices use Manager allocations. Initial source work is host-only until a concrete reserved device window is assigned. Preserve the v0.1 release and installed data/bindings. Existing authentication, resource cleanup and Owner-approved requirements stay in force; new safeguards/verification guards require the existing Owner decision procedure.
