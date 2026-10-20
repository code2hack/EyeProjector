# EP-20 — Investigate a Glasses-fed Phone virtual camera

**Status:** Draft, late experiment. **Blocked by:** [EP-19](19-version-acceptance.md), by Owner-requested scheduling order.

**SPEC:** [Experimental camera evaluation](../../../../SPEC.md#experimental-camera-evaluation).

Investigate Glasses camera → authenticated LAN → Phone virtual camera → target app on the actual devices. The deliverable is reproducible evidence and a feasibility conclusion, with a working prototype only where the platform supports it. Successful camera forwarding is optional for v0.1 completion.

**Primary writes:** isolated experiment/fixture code and evidence; production integration follows the demonstrated result and independent review.

**Acceptance**

- Establish stock Phone service/privilege access and whether apps on the projection display can enumerate/select the supplied camera.
- Where feasible, prove fresh Glasses imagery in a target preview and captured photo through an actual camera/Add photo flow.
- Record app/version-specific results, close/reopen behavior and resource release, plus concurrent projection/input/native Bluetooth behavior.
- Preserve unsupported, failed and unavailable cases with exact source, artifact and device evidence.
- Finish with a clear supported path or documented limitation and next-step recommendation.

**Verification:** begin from the [camera research](../../../research/camera-forwarding.md). A photo-file transfer alone does not prove virtual-camera substitution. If an experiment changes shipped code, repeat its affected core checks. A documented platform limitation is a valid closeout.
