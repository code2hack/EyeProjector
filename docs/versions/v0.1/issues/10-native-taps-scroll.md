# EP-10 — Preserve ordinary tap counts and native pad scrolling

**Canonical issue:** [#14](https://github.com/code2hack/EyeProjector/issues/14). **Blocked by:** None.

**SPEC:** [Glasses-native interaction](../../../../SPEC.md#glasses-native-interaction).

Replace the current double-tap Back/triple-tap Disconnect interpretation with ordinary Android touches. Normalize contact and delayed firmware summaries so one physical action has one semantic result, in projected apps and the local controls.

**Primary writes:** `PadGestureRecognizer.kt`, native event adapter/local pointer dispatch, focused input tests and assigned Receiver Activity/session input-submission regions. Coordinate the latter with EP-03's media changes.

**Acceptance**

- One, two and several quick taps produce the matching touch count with usable timing for target widgets.
- Forward/backward swipes scroll down/up at the pointer, including nested widgets.
- Contact markers and delayed duplicate summaries produce neither duplicate taps nor a preliminary tap for a swipe/long gesture.
- Existing focus, geometry and disconnect boundaries cancel pending gestures.
- Rapid tap/scroll sequences survive the actual control-submission path in order, with delivery failures reported accurately.
- The action interface can pass held-contact state to EP-11 without silently replacing it with an atomic tap.

**Verification:** timestamped event replay and instrumented local/remote widgets, followed by genuine pad input. Inspect the [input evidence](../../../research/glasses-interaction-mapping.md) before deciding which firmware reports represent physical contacts.
