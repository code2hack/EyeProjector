# Live projected layout scale

Author: Worker: Worker-EyeProjector-11

Issue [#11](https://github.com/code2hack/EyeProjector/issues/11) adds the backend operation. Phone pinch UI, app switching and saved per-app values belong to #12. Device qualification is pending a Manager reservation; host checks and the mapping below do not establish physical visibility, keyboard usability or target-app compatibility.

## Mechanism and values

The unmodified, hash-pinned scrcpy 5.0.1 server runs `new_display=480x640/160` with `flex_display=true`. Control type 21 contains two unsigned 16-bit dimensions. Stock calls `VirtualDisplay.resize()` on the existing display and preserves its current density. EyeProjector changes the virtual display bounds rather than only scaling captured pixels. [Pinned resize implementation](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java), [control reader](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java).

At 160 dpi, pixels equal dp. The inference is that requesting 384×512 instead of 480×640 supplies fewer dp to Android's app layout, while fitting that display into the same Glasses area makes its controls appear larger. The fixture must confirm the actual layout change. [Android density units](https://developer.android.com/training/multiscreen/screendensities).

`LayoutScale.target(s)` requests `round(480/s) × round(640/s)`; landscape swaps the baseline axes. Some examples before codec alignment:

| Requested scale | Requested portrait bounds | Initial density |
| --- | --- | --- |
| 0.75× | 640×853 | 160 dpi |
| 1.0× | 480×640 | 160 dpi |
| 1.25× | 384×512 | 160 dpi |
| 1.5× | 320×427 | 160 dpi |
| 2.0× | 240×320 | 160 dpi |

These are proposed qualification points, not a verified supported range. No new percentage clamp is imposed. Positive finite numbers must map, after rounding, into the protocol's existing 1…4096 dimensions. Android, the selected encoder and stock size alignment further constrain what can run. Extreme or unsupported sizes may fail the session. A measured usable range must come from device evidence, including launcher and keyboard reachability.

`ScaleState.requested`, `target` and `requestId` describe the latest valid submitted request. `observed` contains the latest independent stock session geometry. A rejected reply describes that rejected request and retains the preceding observed geometry; it does not replace the backend's valid current state. `LayoutScale.appliedX/Y(observed)` divides the oriented baseline by the observed dimensions. `LayoutScale.applied(observed)` is the smaller ratio, matching uniform fitting into the baseline area; report both axes when rounding changes the aspect ratio. Never show `requested` as the applied number.

`clientResize` identifies the cause flag on the last stock session record, not a capability flag. `OBSERVED` means a new backend geometry sample, not acknowledgment of a particular request: stock metadata carries no request IDs. During rapid adjustments an intermediate observation may precede the latest target. `PENDING` retains the old numeric applied value. Stock's 300 ms debouncer coalesces updates; EyeProjector submits subsequent requests without waiting, because codec rounding can make a request a no-op with no geometry event. An unrelated display change during a pending request is reported as `INTERRUPTED`. There is no automatic retry or periodic encoder reset. [Stock debouncer](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/display/DisplayResizeDebouncer.java).

## Integration contract

Wire revision 4 adds `SCALE_REQUEST` (8-byte requested double, caller correlation in frame stamp) and `SCALE_STATE` (40-byte typed state). Both endpoints must be upgraded together. Glasses use `ProjectionSession.requestScale()` and receive `scaleState`/`onScale`; local submission success is not applied confirmation. Scale feedback itself does not replace the decoder. Stock geometry records continue through the existing decoder replacement and pointer-fit path. Stale geometry-tagged controls are ignored during a resize race, following stock coordinate mapping, instead of ending the session. [Stock position mapping](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/control/PositionMapper.java).

`Bridge.requestScale(requestId, value)` and `Bridge.scaleState()` are typed local hooks for the owning Shizuku controller. The Phone must use its existing local Binder authority. It cannot connect as the pinned Glasses peer using its own certificate. No Glasses keys are copied and no peer trust is broadened. The narrow local Binder adapter against issue5's exact candidate is supplied separately for Manager-coordinated composition; there is no Phone pinch UI in this issue.

Scaling changes the whole virtual display, including the projection launcher and the local Phone IME. `display_ime_policy=local` is retained. Android may recreate Activities for changed screen dimensions. The fixture permits ordinary recreation, saves text/tap counts and has a stable text-field ID; actual focus, selection, IME visibility and third-party state retention need observation. [Android configuration changes](https://developer.android.com/guide/topics/resources/runtime-changes).

## Reserved-device procedure

The Manager must assign the exact Phone/Glasses routes, current session ownership, provisioning and installation window. Preserve other installed apps/private state and the existing ADB server. This procedure deliberately has no audible source.

1. Build and hash both APKs and the receiver test APK with RUNBOOK JDK17/SDK35/max-workers2/Xmx2g. Verify installed bytes and record actual devices, Android user, encoder/decoder and configuration.
2. Establish one authenticated projection using the same upgraded endpoints. Identify that session's stock display ID and baseline geometry. Record its session/start identity and rendered progress.

   The focused driver establishes the connection and waits for fixture setup:

   ```sh
   timeout --foreground 480s adb -s "$GLASSES" shell am instrument -w \
     -e runId "$RUN_ID" -e operation scale -e waitForGo true \
     -e scales 1.25,1.5,0.75,1.0 -e waitForScaleInput true \
     com.code2hack.eyeprojector.directrg.test/com.code2hack.eyeprojector.directrg.ProjectionInstrumentation
   ```

   Use the existing driver's run-ID format. This is a test execution bound, not a projection lifetime.
3. Launch the owned fixture on that exact display:

   ```sh
   adb -s "$PHONE" shell am start --display "$DISPLAY_ID" \
     -n com.code2hack.eyeprojector.directhost/.ScaleFixtureActivity
   adb -s "$PHONE" shell run-as com.code2hack.eyeprojector.directhost cat files/scale-fixture.json
   adb -s "$GLASSES" shell run-as com.code2hack.eyeprojector.directrg touch "files/probe-$RUN_ID.go"
   ```

4. Record initial `widthDp`, `heightDp`, `densityDpi`, `columns`, target bounds, Activity instance/creation count and harmless text. Exercise successive 1.25×, 1.5×, 0.75× and 1.0× requests through the authenticated Glasses operation or the composed typed local Binder hook. Retain pending responses and every stock observation, including rounded results and failures. Confirm the same projection session/display persists.
5. At each settled observation, capture Phone fixture configuration and actual Glasses Surface output. Demonstrate changed dp bounds and column reflow; changed encoded dimensions alone are insufficient. Use the fixture's measured first-target center with the current stream geometry, then verify `taps` increases. Exercise a stale previous-geometry input and ensure the session remains usable.

   Each 1-based phase writes `files/probe-RUN-scale-N.json` on the Glasses. With `waitForScaleInput=true`, the driver waits up to 45 seconds for `files/probe-RUN-scale-N.input`, containing `x,y,width,height`. Supply numeric `X/Y` from the Phone fixture's measured target center and `W/H` from the current stream geometry, then inspect both phase telemetry and the Phone tap count:

   ```sh
   adb -s "$GLASSES" shell run-as com.code2hack.eyeprojector.directrg cat "files/probe-$RUN_ID-scale-$INDEX.json"
   adb -s "$GLASSES" shell "run-as com.code2hack.eyeprojector.directrg sh -c 'echo $X,$Y,$W,$H > files/probe-$RUN_ID-scale-$INDEX.input'"
   ```

   The driver sends the typed tap through the current receiver session; its transport count alone is not a target hit. It records unconfirmed/no-op scale observations and continues to the next adjustment. The debugger supplies test coordinates; production video/control still runs directly between devices.
6. Focus the real EditText, operate visible Phone IME keys through the projection, and record text insertion/deletion, focus/selection, keyboard visibility and reachable targets after adjustments. Return Home to the composed projection launcher and record reachability. If launcher #10/#12 is unavailable, retain that gate explicitly.
7. Exercise a codec-rounded near-no-op request followed immediately by a material adjustment and a return to baseline. Exercise rapid updates and an invalid number/dimension request; verify current applied feedback remains truthful and further scaling/input is usable.
8. End the projection normally. Verify child/display/socket/decoder release using the assigned diagnostics, preserve failures and artifacts with hashes, and return both reservations.

`scale-fixture.json` records real Android configuration, responsive columns, input targets, text/taps, IME state and Activity recreation. It makes no optical or human touchpad claim. Synthetic taps and screenshots cannot replace genuine ergonomic/optical observations; retain those Owner-only gates for later.
