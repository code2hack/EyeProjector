# HID connection topology

v0.1 discussion, 2026-10-20. The Owner selected **HID → Glasses** for v0.1, with four bindings that reuse touchpad Tap, Scroll down, Scroll up and Function button. [SPEC.md](../../SPEC.md#hid-interaction) owns the behavior. The comparison below explains the input paths considered; the selected Glasses route keeps binding and head-pointer handling together.

## Input path and visible response

For ordinary input to Phone-hosted apps, direct Phone attachment removes the Glasses receive/dispatch and LAN-forwarding stages:

- Direct: HID → Phone input handling → target app.
- Forwarded: HID → Glasses input handling → LAN → Phone injection → target app.

The expected advantage of the direct route is an inference from the architecture, not a measured millisecond result. Both routes still need Phone rendering, encoding, transport and Glasses decoding for the app's visible response. A locally drawn Glasses pointer can respond independently of that video path. [Product architecture](../../SPEC.md#execution-and-transport), [local pointer and controls](../../directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ReceiverActivity.kt), [current forwarding](../../directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ProjectionSession.kt)

## Routing and purpose

| Intended use | Topology consideration | Qualification |
| --- | --- | --- |
| Native input delivered directly to projected apps | Phone attachment removes the Glasses forwarding stage | Verify input reaches the virtual display, including required Phone power/keyguard states. |
| Selected v0.1 button bindings | Glasses attachment combines the button with the local head pointer and operates the local panel | Forward the resulting projected action when needed; verify the four bindings and their gesture timing. |
| Independent mouse pointer | Phone routing and a Glasses-local pointer have different response paths | Physical mouse display routing and the relationship between mouse and head pointer require separate design and verification. |

Android dispatches display-specific input to that display's focused window; input without a display target follows the focused display. Bluetooth pairing alone does not establish the recipient app/display. [AOSP per-display focus](https://source.android.com/docs/core/display/multi_display/displays#per-display-focus)

The pinned backend explicitly sets the display on injected events. That capability does not automatically bind a physical Bluetooth mouse or keyboard to the virtual display. The AOSP port-association documentation describes a touch-device mechanism, not a general Bluetooth accessory-to-display preference. [Pinned injection code](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/device/Device.java), [AOSP input routing](https://source.android.com/docs/core/display/multi_display/input-routing)

For a head-pointer clicker, Glasses attachment provides the button and pointer position in the same process. Direct Phone attachment would need a way to obtain/synchronize that local position before it could reproduce the same pointer-based action. This is an implementation consequence of the current pointer location, not a claim that direct attachment is impossible.

No HID was paired, no latency comparison was run, and no source or device configuration was changed. Actual accessory/host behavior, virtual-display routing and panel-off/locked operation need physical verification before support is claimed.
