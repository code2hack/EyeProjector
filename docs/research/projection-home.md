# Custom projection home

v0.1 discussion, 2026-10-20. The Owner wants to select a subset of Phone apps and see their icons on the Glasses home page without a bottom taskbar. The requirement is recorded in [SPEC.md](../../SPEC.md#phone-and-glasses-launchers); implementation remains pending.

## Verified implementation facts

The current [host command](../../directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/ShellMain.kt) fixes the virtual display at 480×640/160 dpi and leaves upstream system-decoration defaults enabled. This is the current configuration, not a fixed desktop layout imposed by the transport.

The pinned scrcpy source supports custom display dimensions/density, starting an app or launcher on the new display, and disabling virtual-display system decorations with `--no-vd-system-decorations`. With decorations disabled, an app must supply content on the otherwise empty display. [Pinned virtual-display documentation](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/doc/virtual-display.md)

The corresponding standalone-server argument is `vd_system_decorations=false`; its default is true. [Pinned Options.java](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/Options.java#L505-L513)

## Proposed implementation direction

Launch an EyeProjector home Activity on the Phone-owned virtual display, drawing the selected-app grid, and open selected apps on that same display. The Phone UI maintains the selection. Route the product's Home action back to this Activity. Disable virtual-display system decorations through the existing backend; this affects the projection display's presentation. This is an implementation proposal supported by the upstream interfaces, not a tested device result.

Selection defines the apps exposed by EyeProjector's launchers. App-to-app links and intents follow Android behavior; a separate restriction on all such navigation has not been requested.

Android manages secondary-display navigation and launcher behavior through system-decoration policy. Exact taskbar behavior is device-specific work to verify on the supported Phone, together with Home navigation, app switching and the existing Phone IME. [AOSP system decorations](https://source.android.com/docs/core/display/multi_display/system-decorations)

No display setting, launcher, dependency or device was changed during this research.
