# V02-02 — Auto-connect on launch and wake projection from Phone HID

**Canonical issue:** Pending publication. **Blocked by:** V02-01.

**Objective**

Implement one activation path for paired Glasses app launch, Phone HID reconnection and wake-only HID presses, including genuine disconnected Glasses wake/reconnect.

**Scope**

Automatic paired launch/retry state, reachable authenticated Glasses wake/control admission, actual stock wake capability, and Phone HID attach/edge hooks through current Phone session/startup ownership.

**Source ownership**

Feature-local activation owner; current PhoneSessionController/HostBootstrap/Shizuku service narrow typed APIs; PhoneHidKeyService/Controller wake hooks; Receiver launch/service and authenticated control consumer; protocol only through an agreed matched-consumer allocation.

**Acceptance**

- Launching paired EyeProjector requests projection automatically; transient unavailability retries until success or cancellation, without an arbitrary retry count/session expiry.
- Phone external-HID reconnection or any delivered external HID press can wake/connect the paired Glasses and show projection without a development-host relay.
- A wake press owns its full DOWN/repeat/UP/cancel gesture and causes no assigned target action or replay after readiness; normal later input remains unchanged, including unassigned pass-through while already ready.
- Overlapping triggers share one current activation/session; old callbacks or releases cannot start/stop/retarget a newer session.
- Existing peer trust is reused for actual background wake/control admission; missing Android/Shizuku setup is displayed accurately. Verify real wake/reconnect separately from local Intent submission.

**Verification and coordination**

Use exact current source/artifact bindings, focused meaningful checks and an independent Reviewer. PRs target `version/v0.2`. Shared source regions, host build/SDK services and physical devices use Manager allocations. Initial source work is host-only until a concrete reserved device window is assigned. Preserve the v0.1 release and installed data/bindings. Existing authentication, resource cleanup and Owner-approved requirements stay in force; new safeguards/verification guards require the existing Owner decision procedure.
