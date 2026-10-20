# EP-03 — Project video/control while audio uses native Bluetooth

**Canonical issue:** [#7](https://github.com/code2hack/EyeProjector/issues/7). **Blocked by:** None.

**SPEC:** [Video and Bluetooth audio](../../../../SPEC.md#video-and-bluetooth-audio).

Make one coordinated host/protocol/Receiver change that disables scrcpy audio and removes its mandatory socket, Opus readiness and playback-clock dependency. Establish video timing from the video stream and a fresh session timing origin. This issue lands a working path across both endpoints.

**Primary writes:** `ShellMain.kt`, `Wire.kt`, `ProjectionSession.kt`, `SurfaceDecoder.kt`, audio-only code/tests and relevant diagnostics.

**Acceptance**

- Backend startup uses audio disabled and accepts the correct remaining stock sockets/headers.
- Projection establishes changing video and controls without an audio stream or Bluetooth connection.
- Video timing, bounded buffering, geometry changes and reconnect function without an audio playback clock.
- Both current endpoints agree on the new contract. Incompatible peers, absent/untrusted identities and malformed/out-of-bounds frames retain the specified rejection behavior.
- Stop/failure closes the actual owned video/control resources; status no longer reports required Opus forwarding.

**Verification:** both-endpoint protocol/lifecycle tests, all affected builds/lint and an owned video/control fixture on the device pair. Bluetooth route loss leaves projection usable. Actual microphone routing and physical AV offset are measured in EP-18. See [audio implications](../../../research/bluetooth-audio.md).
