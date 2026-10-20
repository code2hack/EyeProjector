# Glasses interaction mapping

Owner design, 2026-10-20; [SPEC.md](../../SPEC.md#glasses-native-interaction) owns product behavior. This is an input translation reference, not an implementation or a claim that every listed channel is verified on the device.

## Rokid input to Android behavior

Rokid input names below come from its consumer [bare-metal input chapter](https://custom.rokid.com/prod/rokid_web/ff28c865a9634876be98cbc293588460/pc/us/5702e09fefa343d49b949260fd23c487.html?documentId=ca8fedf26d534e1fabb8a34d1fa24e98), version1.2. Broadcast names in the table have prefix `com.android.action.`. Android output uses [MotionEvent](https://developer.android.com/reference/android/view/MotionEvent) for touch/scroll and [KeyEvent](https://developer.android.com/reference/android/view/KeyEvent) for navigation.

| Physical operation | Documented Rokid input | EyeProjector translation |
| --- | --- | --- |
| One-finger tap | `KEYCODE_ENTER` (66) | Touch `ACTION_DOWN` → `ACTION_UP` at the head pointer. |
| One-finger double tap | `KEYCODE_BACK` (4) | Two normal touch DOWN/UP pairs at the pointer, preserving timing for Android's target-app recognizer. |
| Multiple quick one-finger taps | No generic multi-tap event documented | Normalize per-contact reports and firmware summaries into the actual number of ordinary touch pairs. |
| One-finger long touch | `ACTION_AI_START`; also documented `KEYCODE_PROG_BLUE` (186) | Suppress the AI default and produce an ordinary Android long press with touch DOWN, sustained press and UP. |
| Hold touchpad contact and move the head | Sustained contact/release plus head-pose updates; firmware event preservation needs verification | Maintain touch DOWN, send MOVE at the head pointer and UP on release for ordinary dragging. Holding still retains long-press behavior. |
| One-finger forward swipe | `KEYCODE_DPAD_RIGHT` (22) → `KEYCODE_DPAD_DOWN` (20), within500ms | `ACTION_SCROLL` at the pointer, vertical axis negative: scroll down. |
| One-finger backward swipe | `KEYCODE_DPAD_LEFT` (21) → `KEYCODE_DPAD_UP` (19), within500ms | `ACTION_SCROLL` at the pointer, vertical axis positive: scroll up. |
| Function single click | `ACTION_SPRITE_BUTTON_CLICK`; DOWN/UP also documented | Home on the Phone virtual display, restoring its chosen-app home. Android Home is `KEYCODE_HOME` (3). |
| Function double click | `ACTION_SPRITE_BUTTON_DOUBLE_CLICK` | Back on the Phone virtual display, `KEYCODE_BACK` (4). |
| Function triple click | `ACTION_BOLON_PAIRING` | While projecting: show the local control panel and keep projection connected. In that panel: exit EyeProjector to the Rokid home and release its session. |
| Function long press | `ACTION_SPRITE_BUTTON_LONG_PRESS` | Consume the gesture/default; no app action. |

Tap, drag and scroll output operates the Phone virtual display during projection and the local UI in the Glasses control panel. The function single/double navigation rows above describe projection. In the local panel/settings, single returns to the main control panel and double performs local Android Back. Triple-click owns the two explicitly specified local transitions. In the connected control panel, Connect becomes Disconnect; local Back returns to the live projection.

Android does not define separate touchscreen key codes for double tap or long press: the target app interprets the touch stream. Home is handled by the framework. Display routing must make projection Home restore the chosen-app grid on the Phone's virtual display. The Glasses' physical home is the explicit control-panel exit destination. [MotionEvent streams](https://developer.android.com/reference/android/view/MotionEvent), [Home key](https://developer.android.com/reference/android/view/KeyEvent#KEYCODE_HOME), [pinned backend controller](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/control/Controller.java)

## HID equivalents

The Owner selected HID attachment to the Glasses for v0.1. A **HID key bindings** entry in the local control panel opens four bindings: **Tap**, **Scroll down**, **Scroll up** and **Function button**. [Canonical requirements](../../SPEC.md#hid-interaction)

Tap and Function button each capture one physical button. Its actual press/release sequence feeds the corresponding gesture family: Tap includes ordinary single/double/multiple taps, long press and held-Tap/head-motion dragging; Function button includes projection Home/Back, local main-panel/Back navigation, the connected panel-return and local app-exit actions and long-press no-op. Scroll bindings invoke their corresponding pointer-targeted action. Normalize held-key repeats and clear pending input on focus loss or peripheral disconnect.

Capture identifies and saves the physical button; runtime timing determines the gesture. Persist locally and restore the binding for the same peripheral after reconnection. This is the required behavior; stable device identification and event preservation still need implementation and device verification.

## Translation details to implement and verify

- **Pad reports:** the existing receiver recognizes per-contact NOTIFICATION83 reports and delayed ENTER/BACK summaries. Today's long-touch trial also produced a short83 contact pulse before its long-touch broadcast. A contact pulse is not a faithful physical hold/release stream. Combine reports so a tap is emitted once and a long press or swipe has no preliminary click. [Current adapter](../../directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ReceiverActivity.kt), [recognizer](../../directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/PadGestureRecognizer.kt), [physical report](../provenance/rokid-long-touch-2026-10-20.md)
- **Long touch and dragging:** the physical trial proved AI broadcast cancellation. It did not deliver held key186 DOWN/UP to the Activity or send a long press to a target app. Faithful touch duration/release, pointer-following MOVE events while held, target effects and timing remain integration work. [Physical report](../provenance/rokid-long-touch-2026-10-20.md)
- **Function clicks:** resolve one physical sequence into one final single/double/triple action. CLICK, UP and multi-click broadcasts are reports of related input, not independent actions to dispatch immediately. Observe their ordering and arbitration interval on the firmware; include late reports after state transitions. [Documented channels](https://custom.rokid.com/prod/rokid_web/ff28c865a9634876be98cbc293588460/pc/us/5702e09fefa343d49b949260fd23c487.html?documentId=ca8fedf26d534e1fabb8a34d1fa24e98)
- **Triple-click default:** Rokid documents Bluetooth pairing as the default. Cancellation of this action on the actual firmware remains unverified. The required result is the requested local transition with pairing state preserved. [Official controls](https://global.rokid.com/blogs/academy-glasses/2-3-button-touch-controls)
- **Home and Back:** verify routing and actual result on the projection display, including the curated launcher. Preserve local control-panel navigation as a separate context. [Projection home design](projection-home.md)

The imported baseline used touchpad double-tap Back and triple-tap Disconnect. The v0.1 issue set replaces those bindings with ordinary touches; implementation and physical qualification are tracked separately in the version plan.
