# EP-13 — Configure and persist four Glasses-connected HID bindings

**Canonical issue:** [#17](https://github.com/code2hack/EyeProjector/issues/17). **Blocked by:** [EP-11](11-long-press-drag.md), [EP-12](12-function-button.md).

**SPEC:** [HID interaction](../../../../SPEC.md#hid-interaction).

Add the Glasses control panel's HID key bindings entrance and four assignments: Tap, Scroll down, Scroll up and Function button. Map the selected peripheral's subsequent events into the same gesture/action paths as built-in controls.

**Primary writes:** HID identity/binding store, capture/settings screen and input adapter.

**Acceptance**

- Selecting an entry and pressing/releasing a button records and displays the assignment; that capture gesture is consumed.
- A button has one assignment; reassignment moves it. Bindings survive app restart and reconnection of the same peripheral.
- Tap supports single/multiple taps, long press and head-driven drag. Function preserves the contextual navigation sequence.
- Held-key repeat remains one press; disconnect/focus loss cancels pending/held actions.
- Scroll buttons act at the current pointer, and the screen uses black/light styling.

**Verification:** binding collision/persistence and synthetic event tests, UI capture checks, then the Owner-selected HID's real press/release/repeat/reconnect behavior. Record its actual device identity. See [HID topology](../../../research/hid-connection.md).

The Function binding inherits the updated connected navigation: triple opens the control panel while the session stays live; local Back returns to projection; the panel's Connect button becomes Disconnect. A triple in the local panel exits EyeProjector and releases the session.
