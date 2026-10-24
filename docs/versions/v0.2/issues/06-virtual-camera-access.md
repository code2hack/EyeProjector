# V02-06 — Prove the stock Phone virtual-camera producer and consumer path

**Canonical issue:** Pending publication. **Blocked by:** None.

**Objective**

Establish a supported actual stock-Fold6/Shizuku camera-service path with a changing synthetic producer and a real Camera2 consumer on the existing projection display.

**Scope**

Isolated producer/service-access experiment and owned hostprobe consumer; exact Android/AOSP API binding, actual shell privilege, registration/enumeration and cleanup. No Glasses capture or camera transport yet.

**Source ownership**

New isolated camera-access experiment/feature-local Phone helper and owned hostprobe camera consumer/manifest/resources/tests; no shared projection lifecycle or protocol edits initially.

**Acceptance**

- Identify actual service availability, caller permissions/UID, configuration API and camera/device identifiers without assuming Samsung matches AOSP or root is necessary.
- A changing synthetic producer is visible through real camera APIs, with preview and JPEG capture on the current scrcpy-owned Phone display.
- Separate additional-camera enumeration from actual consumer selection and virtual-device routing from the existing ordinary virtual display.
- Close/reopen and unregister release only the owned test camera/consumer resources; existing physical cameras and projection remain usable.
- An unsupported result is an exact blocker for Owner disposition, not completion of the working virtual-camera feature. Root owns reserved-device execution after concrete source/caller preparation.

**Verification and coordination**

Use exact current source/artifact bindings, focused meaningful checks and an independent Reviewer. PRs target `version/v0.2`. Shared source regions, host build/SDK services and physical devices use Manager allocations. Initial source work is host-only until a concrete reserved device window is assigned. Preserve the v0.1 release and installed data/bindings. Existing authentication, resource cleanup and Owner-approved requirements stay in force; new safeguards/verification guards require the existing Owner decision procedure.
