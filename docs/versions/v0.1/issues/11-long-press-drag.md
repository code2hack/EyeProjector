# EP-11 — Deliver continuous long press and head-driven dragging

**Canonical issue:** [#15](https://github.com/code2hack/EyeProjector/issues/15). **Blocked by:** [EP-03](03-bluetooth-video.md), [EP-10](10-native-taps-scroll.md).

**SPEC:** [Glasses-native interaction](../../../../SPEC.md#glasses-native-interaction).

Carry actual touch DOWN/MOVE/UP/CANCEL through the Receiver, protocol and Phone injection path, and dispatch the equivalent local touches. Holding still lets the target recognize a long press; holding while moving the head drags. Integrate the Rokid long-touch interception needed to keep the action in EyeProjector.

**Primary writes:** touch messages/encoding in `Wire.kt`, session control delivery, serialized host control writes, held-touch dispatch and tests.

**Acceptance**

- Genuine pad contact/release supplies a continuous gesture, including head-driven MOVE events and release at the final position.
- Long touch performs the app action without invoking Rokid AI; ordinary taps retain EP-10 behavior.
- Sliders and text-selection handles can be dragged in owned fixtures and local controls.
- Focus loss, geometry change, Stop/Disconnect and input-device loss cancel held input without replay on reconnect.
- Closely spaced events preserve order; the current zero-queue executor does not silently discard required touch transitions.

**Verification:** wire encoding/state-transition tests and physical hold/release/drag observations. The [earlier trial](../../../provenance/rokid-long-touch-2026-10-20.md) proves bounded AI suppression only; faithful hold duration/release still needs evidence on EyeProjector.
