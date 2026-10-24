# V02-05 — Remove seconds-scale delay from native Rokid touch input

**Canonical issue:** [#66](https://github.com/code2hack/EyeProjector/issues/66). **Blocked by:** None.

**Objective**

Measure and correct the observed native touchpad latency while preserving ordinary taps, swipes, held input and the existing Function Back/Home mapping.

**Scope**

Actual native event/firmware timing, PadGestureRecognizer/NativePadInput/contact adapters and affected tests. Coordinate Activity hooks separately from the sleep owner.

**Source ownership**

PadGestureRecognizer.kt, NativePadInput.kt, native contact/long-touch adapters and focused Receiver test fixtures; ReceiverActivity source only through a separately coordinated narrow hook.

**Acceptance**

- Establish an exact-device/source baseline separating physical action, firmware event receipt, classification/replay, dispatch/injection and visible response.
- Eliminate avoidable application-added seconds-scale delay using a faithful fast event source; demonstrate changed behavior on real hardware.
- Retain tap/multi-tap count and timing, no preliminary click for long/swipe, held/contact cancellation, local/projected targeting and existing native-default handling.
- The existing1000ms constant alone is not assumed to be the whole cause; observed CONTACT pulses before long classification around815ms remain evidence to preserve.
- Report a demonstrated firmware/API limitation promptly rather than inventing a global input API or silently changing gesture semantics; no new guard/expiry/drop policy without Owner approval.

**Verification and coordination**

Use exact current source/artifact bindings, focused meaningful checks and an independent Reviewer. PRs target `version/v0.2`. Shared source regions, host build/SDK services and physical devices use Manager allocations. Initial source work is host-only until a concrete reserved device window is assigned. Preserve the v0.1 release and installed data/bindings. Existing authentication, resource cleanup and Owner-approved requirements stay in force; new safeguards/verification guards require the existing Owner decision procedure.
