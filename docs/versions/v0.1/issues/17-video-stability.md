# EP-17 — Resolve decoder stalls and sustain video-only projection

**Canonical issue:** [#21](https://github.com/code2hack/EyeProjector/issues/21). **Blocked by:** [EP-03](03-bluetooth-video.md).

**SPEC:** [Video](../../../../SPEC.md#video-and-bluetooth-audio), [session lifetime](../../../../SPEC.md#session-lifecycle).

Investigate the inherited decoder input-stall failure on the video-only candidate, correct its demonstrated cause and verify both changing content and quiet reading. Preserve the distinction between legitimate static content, backpressure and an actual stalled decoder.

**Primary writes:** `SurfaceDecoder.kt`, Receiver timing/lifecycle code, focused fault tests and evidence.

**Acceptance**

- Reproduce or bound the historical failure with diagnostics that identify input/output progress; any code correction is tied to evidence.
- Legitimate decoder scheduling/backpressure does not prematurely terminate a healthy stream.
- A declared sustained run covering the previously failed ten-minute interval completes with changing rendered content and usable controls.
- A separate no-user-input/static-content interval preserves the session without an inactivity cutoff.
- Genuine codec/Surface failure still produces a visible outcome, releases resources and permits a fresh reconnect.

**Verification:** codec/lifecycle regression tests and physical runs with exact artifacts, decoder identity and timing. Retain failed attempts. Investigate the 50 ms dequeue behavior; merely increasing a timeout is not evidence of a resolved root cause. See [retained failure](../../../provenance/pending-panel-power-results.md).
