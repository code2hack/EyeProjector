# Phone gesture panel and saved app scale

v0.1 discussion, 2026-10-20. The Owner specified connected app switching by tapping a Phone icon, a long-press menu with Remove and connected-only Scale, and a blank Phone gesture panel with a numeric scale readout. Pinching changes the actual projected app on the Glasses and the chosen scale persists per app. [SPEC.md](../../SPEC.md#per-app-scale) owns the current behavior.

## Verified backend interfaces

The pinned scrcpy backend accepts initial virtual-display dimensions and DPI through `new_display=WIDTHxHEIGHT/DPI`. Its flex-display mode supports subsequent width/height changes. [Pinned display documentation](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/doc/virtual-display.md)

The resize control message contains width and height only. The implementation preserves the current display DPI when resizing. It supplies no native package-keyed scale preference. [Control reader](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java#L167-L171), [resize implementation](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java#L303-L342)

Android's `VirtualDisplay.resize(width, height, densityDpi)` exposes all three parameters to the display owner. Changing density changes Android's logical layout sizing. [Android display API](https://developer.android.com/reference/android/hardware/display/VirtualDisplay#resize(int,int,int)), [density documentation](https://developer.android.com/training/multiscreen/screendensities)

## Integration work to qualify

An EyeProjector app profile could map the gesture panel's pinch to display settings and save them by target package, applying the saved profile when that app is selected. The panel's numeric readout reflects the applied scale. This is an engineering proposal. A live DPI-control path requires further integration; the pinned resize message alone does not provide it. App layout changes and input-coordinate mapping must be verified, including any impact on the Phone IME and launcher because settings affect the display as a whole.

Current EyeProjector starts `480x640/160`, and capture belongs to the receiver's projection session. The planned Phone gesture panel will send scaling controls for that active display. [Current host](../../directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/ShellMain.kt)

No scaling prototype, dependency, device setting or runtime was changed during this research. Live resizing stability and app/device compatibility remain unverified.
