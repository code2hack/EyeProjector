# EyeProjector product specification

EyeProjector projects supported Android applications from a Phone-hosted virtual display onto smart glasses through direct video streaming and glasses-native interaction. Audio uses the Phone and Glasses' ordinary Bluetooth connection.

This document defines product requirements. Build instructions belong in [RUNBOOK.md](RUNBOOK.md), agent coordination in [AGENTS.md](AGENTS.md), and source history and observed results in [the initialization audit](docs/migration/initialization-audit.md). A requirement here is not a claim that the current implementation has passed it.

The current development target is **v0.2**, building on the Owner-accepted [v0.1 release](https://github.com/code2hack/EyeProjector/releases/tag/v0.1). The [v0.2 plan](docs/versions/v0.2/README.md) owns implementation scope and dependencies. Historical v0.1 evidence remains in its [run record](docs/runs/v0.1.md).

## Terms

| Term | Meaning |
| --- | --- |
| Phone | Android device running the projection host. |
| Glasses | Lightweight receiving display, audio and input device. |
| Target app | Android application running on the Phone's projection display. |
| Virtual display | Phone-owned Android display created for projected apps. |
| Projection session | Authenticated video/control transport, camera forwarding when used, and their active lifecycle; Bluetooth audio has its own system-managed lifecycle. |
| Virtual camera | Phone-side Android camera source supplied with frames captured by the Glasses. |
| Projection host | Phone-side session, capture, encoding and control implementation. |
| Receiver | Glasses-side video decoder and interaction client. |
| Agent runtime | Development orchestration infrastructure, separate from the product. |

## User outcomes

1. As a user, I want launchers on both the Phone and Glasses to choose, open and switch supported Phone apps on the projection display, so that I can use them from either device.
2. As a user, I want to establish an authenticated connection, so that only my authorized peer receives projected video or sends controls.
3. As a user, I want a responsive local head pointer, ordinary touchpad taps and long presses, scrolling and function-button navigation, so that I can operate projected apps naturally.
4. As a user, I want to operate the Phone's system keyboard, so that I can enter and edit text in the target app.
5. As a user, I want Start and Stop in the Phone app, local Disconnect on the Glasses and a fresh reconnect, so that I can start, end or resume projection without stale input or abandoned resources.
6. As a user, I want clear failure and compatibility information, so that I can distinguish a connected transport from usable video, audio and app interaction.
7. As a user, I want projection to continue until I end it or a real failure occurs, so that an elapsed-time limit does not interrupt my session.
8. As a user, I want an active projection to continue with the Phone's physical panels off while Android remains Awake, so that I can use the Glasses without keeping the Phone's screens lit.
9. As a user, I want supported Phone apps to use the Glasses camera through their normal camera interfaces, with live preview, photo/Add photo capture and continuous video where the app supports it.
10. As a user, I want launching EyeProjector or reconnecting/pressing my Phone-connected HID to bring projection back automatically, including waking the Glasses.
11. As a user, I want enabled chosen-app notifications to wake the Glasses and open the notifying app, with a separate per-app Glasses-notification toggle.
12. As a user, I want Glasses sleep to preserve my connection and projected app state, and native touch operations to respond promptly.

## Product behavior

### Execution and transport

The target app executes and renders on the Phone's virtual display. The Glasses receive projected video and send native controls directly to the Phone. Audio follows native Phone↔Glasses Bluetooth routing. A development computer may provision and debug the devices; it is not a media or control relay in the product path.

The Phone and Glasses communicate on the same LAN, either through the same Wi-Fi network or with the Glasses connected to the Phone's hotspot.

The projection host must create and own the virtual display and its session resources. The current backend requires an explicitly authorized Android shell bootstrap. Installing or opening the ordinary host APK does not grant shell privileges. The bootstrap's privilege, exact code and device must be attributable; peer requests must not become arbitrary shell commands.

Remote video, control and forwarded camera frames require mutually authenticated endpoints. The transport must preserve confidentiality and integrity, validate protocol versions and message bounds, and reject an absent or untrusted peer identity. An authenticated peer must not gain a general command execution interface.

### Phone setup and startup

For v0.1, the Phone host uses Shizuku to start its shell-privileged projection backend. The Phone app must guide the user through installing and starting Shizuku and authorizing EyeProjector. After that setup, users must be able to start and stop the projection host from the Phone app, with ordinary session startup requiring no computer command line.

The Phone app must show the actual host and Shizuku availability and explain the next action when setup or recovery is needed. After a reboot or loss of Shizuku availability or authorization, users must have an actionable route back to a fresh projection session. Automatic recovery after reboot remains unresolved. The [startup research note](docs/research/phone-app-startup.md) records the selected approach and its known limits.

### Pairing and control panels

While unpaired, the Glasses display a four-digit pairing code and its remaining validity, starting at five minutes. At expiry, the old code expires and a new code appears with a fresh five-minute countdown. The Phone's **Pair with display device** action opens a code-entry prompt and pairs with the Glasses over the LAN. A displayed code permits at most five pairing attempts; further attempts wait for the next code. Rotation resets the limit, and existing projection is unaffected.

Successful code pairing establishes a remembered EyeProjector peer identity for video, control and camera forwarding. Subsequent connections use that pairing without code re-entry. Stop and Disconnect retain pairing. Forget removes that peer identity and returns the device's EyeProjector panel to its unpaired state. System Bluetooth pairing is managed independently through the devices' ordinary settings.

| Glasses control-panel area | Contents |
| --- | --- |
| Area 1, unpaired | Four-digit pairing code and five-minute expiry countdown, rotating on expiry. |
| Area 1, paired | **Connect** while disconnected, **Disconnect** while connected, and **Forget**. |
| Area 2 | Separate X-axis and Y-axis head-pointer sensitivity controls, the existing scroll-speed control, a toggle for head-motion scrolling and a **HID key bindings** button. Retain the existing stepped numeric settings design. |

| Phone control-panel area | Contents |
| --- | --- |
| Area 1, unpaired | **Pair with display device**, opening the pairing-code prompt. |
| Area 1, paired | **Start**, **Stop** and **Forget**. Start immediately begins projection on the available paired Glasses. |
| Area 2 | Chosen-app icons with **+** always in the first position for adding an app. While connected, tapping an icon switches the Glasses to that app. Holding an icon opens its app menu. |

The Glasses' Connect action connects or reconnects to the paired Phone's available host. Pairing, host availability, connection and usable streaming are distinct states shown through the controls. Unavailable peers or missing setup produce actionable status.

### v0.2 quick launch, wake and automatic recovery

After ordinary pairing and Phone setup, the following events request usable projection without a development computer or repeated Connect/Start taps:

| Trigger | Required result |
| --- | --- |
| Open EyeProjector on the paired Glasses | Connect automatically to the paired Phone. Retry transient unavailability while the request remains active; show actual connecting/retrying/ready state. |
| Reconnect an external HID peripheral to the Phone | Wake the paired Glasses as needed, connect/resume projection and show its projected screen. |
| Press a single external HID button while wake/reconnection is needed | Wake/connect/resume only. Consume that physical press through repeat/release/cancel; do not replay its assigned action after projection becomes ready. Later ordinary presses operate their existing bindings. |
| Receive a new notification from a chosen app with Glasses notifications enabled | Wake/connect/resume as needed and open the notifying app on the projection display. If already usable, open that app through the existing session. |

The same activation path handles overlapping launch/HID/notification requests without duplicate sessions, retargeting old operations to a new session or losing the selected app. Existing pairing and Shizuku setup status remain actionable. Retry is scheduling/recovery, not a new total-session expiry or a finite retry-count policy. Explicit Stop/Disconnect/app exit cancels the current session/request and pending input; a subsequent allowed trigger can request a new activation. Forget still removes remembered trust.

Glasses display sleep or temporary loss of its Activity/Surface retains the authenticated connection and Phone-side virtual display/app state. Rendering and local sensor/input ownership may suspend while the Glasses are asleep; wake/resume reattaches rendering and shows fresh current content without a manual reconnect. Actual peer/network/process failure uses automatic recovery with correct release of the old session. A screen-off state must not be reported as a successful explicit Disconnect. Phone and Glasses power states remain distinct.

### Per-app Glasses notifications

The Phone chosen-app icon long-press menu provides **Remove**, **Scale** in its existing connected context, and a **Glasses notifications: On / Off** toggle. Notification preference can be edited while disconnected and persists independently per app across app restart and device reconnect. It controls EyeProjector's Glasses notification/activation route; the Phone's ordinary app/system notifications retain their existing settings and delivery.

On enables the notification-triggered wake/reconnection/open-app behavior above; Off disables that EyeProjector route for that app. Initial v0.2 default is On for chosen apps, preserving the requested chosen-app quick-launch behavior. This is an implementation default, separate from an explicit saved user choice. Newly arriving notifications are distinguished from duplicate updates or listener reconnection replay of an existing notification.

### Phone home-screen widget

For v0.1, EyeProjector provides a Phone home-screen widget with two buttons:

| Button | Behavior |
| --- | --- |
| **Start / Stop** | Shows **Start** when projection is inactive and begins projection on the paired Glasses using the Phone app's startup path. Shows **Stop** while starting or projecting and ends the host/session when tapped. |
| **Panel off** | Available during active projection. Powers down the Phone's physical panels while Android remains Awake and projection continues. |

The widget reflects actual host/session state, including changes made from the Phone app, Glasses Disconnect and session failures. If pairing or Shizuku setup/authorization needs user action, open the Phone app at the relevant setup/status screen. The widget uses the same session ownership and resource lifecycle as the Phone controls.

### Video and Bluetooth audio

The host must encode target-display video and the Receiver must decode it, using Android hardware assistance where available. The Receiver must preserve aspect ratio and map input to the actual current video geometry. A changing source must produce changing displayed content; a live socket or a repeated old frame is insufficient. Video buffering remains bounded, and decoder failures must be visible and release session resources.

For v0.1, all audio uses native Bluetooth routing between the Phone and Glasses, with scrcpy audio forwarding disabled. Media playback, call audio, microphone selection and volume follow ordinary system Bluetooth settings and the target app's audio behavior. The projection transport carries projected video, control and camera frames when used, while video scheduling operates independently of a forwarded-audio playback clock.

Supported calling and recording apps can use the Glasses microphone through their normal Bluetooth headset routes. Enabling the Phone's Calls and Audio settings makes those capabilities available; the actual input route remains app/service-dependent. Compatibility records identify which microphone each tested recording or ASR feature actually uses. Native audio routing also governs other Phone apps, notifications and calls.

Video/control readiness and Bluetooth audio availability are separate states. Projection operates independently of Bluetooth connection or microphone-route availability. Session Stop, Disconnect and EyeProjector Forget release EyeProjector resources while leaving system Bluetooth pairing and routing under normal system management.

Bluetooth audio and projected video have separate delivery paths. Physical AV synchronization and route-transition behavior require measurement on supported device/app combinations. The [Bluetooth audio note](docs/research/bluetooth-audio.md) records the selected approach, implementation changes and compatibility limits.

### Monochrome display style

For v0.1, EyeProjector's Glasses control panel and projected home grid use pure-black backgrounds with light text, icons, controls and pointer, suited to the Glasses' monochrome green display. Status remains distinguishable through labels and shapes. Black pixels leave the real-world view visible through the waveguide.

Projected apps use their native dark themes where available. Their content, including photos, video and camera previews, follows the app's rendering. Compatibility records identify readability and any remaining gray or bright surfaces per app. Automatic background conversion and image inversion remain future work. The [display-style research note](docs/research/display-style.md) records the decision and platform limits.

### Glasses camera through a Phone virtual camera

For v0.2, the feature goal is a working Glasses-camera → authenticated LAN → Phone virtual-camera → target-app path. The same camera foundation serves live preview, still-photo/JPEG capture and continuous video; supported video-call flows also retain ordinary Bluetooth audio. The v0.1 camera experiment was unexecuted and does not establish this capability.

Opening the camera in a supported projected Phone app uses the Glasses sensor. The app's ordinary viewfinder shows current Glasses imagery and its capture delivers that image through the app's normal photo/Add photo flow. Continuous video and available calling workflows use current Glasses frames with correct orientation and usable pacing. Selecting an existing image remains the app's ordinary image-picker flow.

Camera frames travel through the authenticated direct Phone↔Glasses path, without a development-host relay. Actual camera use controls acquisition; closing the camera, replacing its owner, ending projection or losing the peer releases the old capture and forwarding resources. Reopen and reconnect produce fresh frames belonging to the current consumer. Camera work must preserve projection, input and ordinary Bluetooth routing.

An early isolated stock-Fold6/Shizuku feasibility issue establishes actual service/privilege access, a changing producer and camera visibility on the existing projection display. Android/AOSP mechanisms are candidates until verified on the actual Phone. Root requirement, OS modification or a different privilege/display mechanism is not assumed. A platform blocker is reported for an Owner decision; a feasibility-only result does not by itself satisfy the working v0.2 camera goal. The [camera research note](docs/research/camera-forwarding.md) retains the baseline mechanism and compatibility questions.

### Glasses-native interaction

The Receiver must draw a local head-pointer overlay independently of video delivery, available for both the projection and the local control panel. Horizontal and vertical pointer directions must remain correct regardless of startup posture, including upright, sideways or upside-down startup followed by normal wear. Focus changes, relaunch and supported display rotations must preserve the correct directions. It must stop or reset tracking when sensor data or focus becomes invalid. Projection input uses current display orientation and fitted video geometry, with no remote tap for a target outside the visible app image; the local control panel uses its own UI geometry.

During projection, touch and scroll actions target the Phone's virtual display at the head pointer. In the control panel, they operate the local EyeProjector UI. The v0.1 interaction contract is:

| Action | Required behavior |
| --- | --- |
| Head movement | Move one local pointer with separately configured bounded X-axis and Y-axis gains. |
| Touchpad single, double or multiple quick taps | Send the corresponding number of ordinary Android touches at the visible pointer target, preserving their sequence and relative timing for the target app's gesture handling. |
| One-finger touchpad long press | Produce an ordinary Android long press at the pointer target, with the target app deciding its effect. |
| Hold touchpad contact or HID Tap and move the head | Maintain touch DOWN, send MOVE events following the pointer, then UP on release, enabling ordinary Android dragging. Holding still retains ordinary long-press behavior. |
| One-finger touchpad forward swipe | Scroll down at the pointer target. |
| One-finger touchpad backward swipe | Scroll up at the pointer target. |
| Function-button single click while projecting | Send Android Back to the projected Phone app. |
| Function-button double click while projecting | Perform Home on the Phone's virtual display, returning to the chosen-app home grid. |
| Function-button single click in the local control panel or settings | Perform local Android Back navigation; return to the live projection when it is the preceding view. |
| Function-button double click in the local control panel or settings | Return to the main Glasses control panel. |
| Function-button triple click while projecting | Show the Glasses control panel while keeping the projection session connected. |
| Function-button triple click in the local control panel or settings | Exit EyeProjector to the Rokid home and release the active session and local input resources. |
| Function-button long press | Consume the gesture and perform no action. |
| Aim beyond the upper/lower video edge | When head-motion scrolling is enabled, repeat bounded native scrolling until aim returns or the session becomes invalid. |
| Android Back navigation in the control panel | Return through local navigation; when entered from a live projection, return to that projection without reconnecting. |

Each physical gesture produces its assigned behavior once. Normalize duplicate firmware reports, resolve each function-button click sequence to its final single/double/triple classification, and consume competing Rokid defaults for reclaimed gestures. Long presses and swipes produce their own action without an additional contact-marker tap. Pause, focus loss, geometry change and Disconnect cancel pending or held input; reconnect starts with fresh input state. Native app multi-tap, long-press and scroll behavior follows the target widget.

The [input mapping](docs/research/glasses-interaction-mapping.md) distinguishes official Rokid input events from Android touch, scroll and navigation output. Long-touch AI suppression has a bounded physical result, while full long-press injection and function-button double/triple classification and default-action suppression still need implementation and verification.

Pointer X/Y sensitivity, scroll speed and the head-motion scrolling toggle persist locally on the Glasses. The existing control panel remains accessible during a connected session through the function-button triple click. Turning head-motion scrolling off cancels active head-driven scrolling while preserving pointer movement and touchpad scrolling.

For v0.2, ordinary native taps and swipes must avoid the observed application-added, seconds-scale classification/replay delay while retaining once-only tap counts, long-press/drag and swipe behavior. Measure physical action, received firmware/input events, classification, transport/injection and visible target response separately. Select a faithful fast event path; changing the existing classification constant alone does not prove correct physical behavior. Delayed or ambiguous firmware reports and their remaining limits must be demonstrated rather than disguised by a host-only timing test.

### HID interaction

The primary HID peripheral connects to the Phone. The Phone captures/binds its keys, receives the actual Glasses head-pointer/context state and constructs complete input events for the current projected display. Native Rokid controls keep their native route and are never assigned as external HID keys. Existing Glasses-side assignments remain preserved; there is no automatic migration or deletion.

The Phone's **HID key bindings** page provides four configurable bindings:

| Binding | Equivalent built-in control |
| --- | --- |
| Tap | Touchpad tap: single, double and multiple quick presses become ordinary Android taps; holding still produces a long press, and holding while moving the head drags at the pointer. |
| Scroll down | Forward touchpad swipe: scroll down at the pointer. |
| Scroll up | Backward touchpad swipe: scroll up at the pointer. |
| Function button | Physical function button: single Back, double Home (main panel locally), context-dependent triple-click panel return or app exit, and long-press no-op. |

Tap a Phone binding row to select sticky replacement capture, then press the external HID button and show its saved assignment. The received-key line counts distinct repeated presses as KEY x2/x3. Each assigned button maps to one control; assigning it elsewhere moves the assignment. One **Reset all HID bindings** restores all four to Unassigned. Capture records assignments without executing them. Save Phone bindings locally and restore them when the same peripheral reconnects or EyeProjector restarts. Wake-trigger presses follow the separate wake-only behavior above.

Tap and Function button each bind one physical button; press/release timing determines its short, long or multiple-press behavior. Repeated key-down reports during a held press represent that same press. HID disconnection and focus loss cancel pending or held input, and reconnection starts with fresh input state.

### Phone and Glasses launchers

Both the Phone and Glasses must provide an app launcher for choosing and opening supported Phone apps on the projection display. Users must be able to return to the launcher and switch the projected target app from either device. The target app continues to execute on the Phone.

The Phone app must let the user choose which installed supported apps appear in the projection launchers. Both launchers use that selection. The Glasses home page presents an icon grid of the chosen apps, with a taskbar-free layout. Selecting an icon opens that app on the projection display; returning Home restores the selected-app grid. Target apps retain their own interfaces.

On the Phone, tapping a chosen app while connected switches the Glasses' current screen to that app. Holding the icon opens a menu with **Remove**, **Glasses notifications: On / Off**, and, while connected, **Scale**. Remove edits only the projection list and its launcher icons. The Scale action opens that app on the Glasses and the adjustment panel described below.

The projection controls remain available separately from the app grid: connection/status, local pointer/scroll settings, Disconnect and host Stop. A Glasses-local keyboard remains outside the current scope.

The initial v0.1 target apps are **ChatGPT, Paseo, Comet and WeChat**. Compatibility records identify the actual installed identities/versions and exercised workflows for each.

### Per-app scale

Scale opens a blank gesture panel on the Phone with the current numeric scale, for example **1.0×**. A two-finger pinch changes the selected app's actual projected size/layout live on the Glasses, and the panel updates its scale number. Closing the panel returns to the Phone controls while projection continues.

The Phone stores the scale per app and reapplies it when that app is selected again. scrcpy supplies display-level size/density controls; the saved per-app profile and gesture are EyeProjector features. The live scaling mechanism and its interaction with app layout and the Phone IME still require implementation and verification, as recorded in the [scaling research note](docs/research/per-app-scale.md).

### Text entry

The Phone's selected system IME is the first text-entry route. Focusing an ordinary editable field must request the real Phone IME on the projection display; the Glasses pointer and touchpad must operate its visible keys. Full usability requires observable text insertion, deletion, selection and submission, with reachable keys and a usable app layout. Keyboard pixels alone do not establish usable text input. Full Phone-IME usability remains unresolved.

### Session lifecycle

Phone Start immediately begins projection on the available paired Glasses, reporting progress until usable video and control are established. Glasses Connect establishes a fresh authenticated receiver connection to the paired Phone's available host. The Receiver must distinguish connecting, streaming, stopped and failed outcomes. Bluetooth audio follows its independent system connection and routing. The host must serialize ownership of active projection resources.

Phone Stop must end the host and its active projection. The Glasses control panel's Disconnect button ends the active session. A function-button triple click during projection instead opens that panel while keeping the session, decoder and display Surface alive. The panel uses local pointer/input and cancels pending projected input and head-scroll motion; local Back returns to the live projection. Connect becomes Disconnect while connected. EyeProjector exit ends the session. In v0.2, Glasses sleep, temporary Activity pause and Surface destruction suspend or detach rendering/input as needed while retaining the connection and Phone app state. Genuine unrecoverable video/control failures release their owned session and enter the applicable recovery path. Release includes that session's sockets, capture children, virtual display, video codecs, Glasses camera capture and virtual-camera resources, projection sensor subscriptions and pending input. Local control-panel input has its own lifecycle and releases when EyeProjector exits. A status message that a stop was sent is insufficient to verify teardown. EyeProjector pairing remains available for subsequent use; system Bluetooth retains its ordinary lifecycle. Closing a target app's display can affect that app's own playback through normal Android behavior.

The host has no overall elapsed-time cutoff. Startup, blocked I/O and resource-release operations retain finite safety bounds. A long-running test interval is a measurement, not a product expiry time. After a real session failure, reconnect allocates fresh display/video/control resources and video timing after the old owner releases them. A Glasses sleep/wake rendering transition preserves the existing Phone virtual display and app state rather than creating an unnecessary replacement session.

### Phone power and Android compatibility

EyeProjector aims to keep projection usable across as many supported combinations of physical panel power, device wakefulness and keyguard state as possible. Coverage must be established for each tested combination.

The following states are independent and must be recorded separately in any claim:

| State | What must be established |
| --- | --- |
| Physical panel power | Whether each physical Phone panel is actually displaying or blanked. Brightness and a black app image are not panel-power evidence. |
| Device wakefulness | Android Awake, Dozing or Asleep state and its transitions. |
| Keyguard | Whether the keyguard is present/locked and what app interaction Android permits. |
| Virtual display | Whether the session display exists, has the target app, and continues producing usable AV/input. |

For v0.1, preserve the demonstrated Awake operating cases on supported device combinations, with usable video, audio and Glasses-native input:

| Required v0.1 power scenario | Behavior |
| --- | --- |
| Android Awake, active physical panel on | Projection operates with the Phone unlocked or securely locked. |
| Existing projection, physical panels turned off while Android remains Awake | Projection continues with the Phone unlocked or securely locked. |

During an active projection session, the host must prevent ordinary inactivity sleep even while the user is reading without touching controls or moving the head. This responsibility lasts until Stop, Disconnect or session failure releases the session. Remembered pairing alone follows ordinary Phone power policy.

The normal sequence starts projection with the active panel on, then turns the physical panels off while Android remains Awake. Starting or reconnecting capture with panels already off, projection through ordinary sleep/global Dozing or Asleep, DeviceIdle light/deep Doze and automatic recovery from those states remain future work. EyeProjector respects keyguard and Android security boundaries.

Physical-panel OFF uses a software display-power operation while projection keeps Android Awake and the virtual display active. The Phone home-screen widget's **Panel off** button is the explicit user-facing trigger. When the session has blanked the physical panels, Stop, Disconnect and failure teardown restore Phone display power. The [panel-power note](docs/research/phone-panel-power.md) explains the mechanism and historical evidence.

App placement, multi-display support, secure surfaces, DRM, capture opt-out, audio focus and OEM behavior limit compatibility. Support must be stated for tested app/device/OS combinations. The existence of a virtual display does not imply that every ordinary Android app works on it. Automatic bootstrap after reboot is unresolved.

## Acceptance expectations

Each acceptance record must bind the exact source commit, built and installed artifact hashes, device identities/OS versions, private configuration revision and actual test interval. Record failures and incomplete observations alongside passes. Host builds and synthetic tests support implementation checks; physical behavior needs device evidence on the same candidate.

| Area | Required evidence |
| --- | --- |
| Pairing and LAN | Pair by the displayed code on shared Wi-Fi and through the Phone hotspot. Verify the five-minute countdown/rotation, expired-code outcome, remembered pairing, subsequent authenticated connections and Forget returning the EyeProjector panel to unpaired state while system Bluetooth pairing remains intact. |
| Control panels | Verify the specified two areas on each device, state-dependent actions, Phone Start beginning Glasses projection and the Phone app grid's leading **+**. |
| Phone widget | Add the widget to the Phone home screen and verify its two buttons. Start begins projection, Stop ends it, and the label reflects starting/projecting/inactive state and changes from either device or session failure. Verify setup recovery opens the Phone app. During projection, Panel off blanks the physical panels while video/input continue and Android stays Awake; teardown restores display power. |
| Phone startup | With Shizuku installed, running and authorized, start and stop the host from the Phone app without a computer command line. Verify the setup/recovery guidance when Shizuku is unavailable or authorization is missing, and record actual reboot recovery behavior. |
| Launchers | Choose a subset of supported installed apps on the Phone and verify both projection launchers reflect it. On the Glasses, verify the home page shows the selected-app icon grid with no bottom taskbar. Verify connected Phone taps switch apps, holding opens the menu, Remove edits only the projection list and Scale appears while connected. Open apps from either device, return Home and switch targets; verify placement and usable AV/input after each launch and switch. |
| Video | A known changing source remains visible on the Glasses, with actual rendered progress, correct aspect/orientation and no sustained decoder stall over a declared sustained run. Record encoder/decoder names and dimensions. |
| Display style | Verify pure-black backgrounds and readable light content in EyeProjector's Glasses UI and projected home. For each supported target app, verify its native dark theme where available and record readable controls, remaining gray or bright surfaces, and photos/video/camera-preview readability. |
| Bluetooth audio and synchronization | With scrcpy audio disabled, verify actual Phone media playback through the Glasses, ordinary volume/call-route transitions and the actual microphone used by each supported recording/ASR app. Verify video/control continues across Bluetooth loss or microphone unavailability and that projection teardown leaves system Bluetooth management intact. Measure physical flash/beep offset against a 100 ms target with a stated method and uncertainty; software timestamps alone do not pass physical synchronization. |
| Native input | Verify head pointing, ordinary single/double/multiple touchpad taps, long press and held-contact/head-motion dragging, plus forward/down and backward/up scrolling at the pointer in main/nested widgets and local controls. Verify function-button single Back and double Home/main-panel navigation and the connected control-panel return and local app-exit actions; each click sequence produces only its final action, long press produces no action, and reclaimed gestures suppress competing Rokid behavior including AI and Bluetooth pairing. Verify session/local-input release, independent X/Y gains, persisted settings and cancellation/geometry changes, including held drags. Turning head-motion scrolling off stops that scrolling while touchpad scroll and pointer movement remain usable. |
| HID bindings | Connect a supported peripheral to the Phone, open **HID key bindings** and configure the four controls. Verify displayed assignments, reassignment, persistence across app restart/peripheral reconnect, ordinary single/double/multiple taps, long press and held-Tap/head-motion dragging, pointer-targeted scrolling, and the Function button's projection/local navigation and the connected panel-return and local app-exit actions. Binding capture records the assignment; subsequent presses operate it. Verify held-key repeat handling and cancellation on disconnect/focus loss. |
| Per-app scale | From a connected app's menu, open the blank Phone gesture panel and verify its numeric scale. Pinch to change that app's layout live on the Glasses, with matching numeric updates. Verify persistence per app, restoration on switching/relaunch, continued projection after closing the panel, native input mapping and IME behavior. |
| Phone IME | Actual system keyboard on the virtual display supports physical insertion, deletion, selection and submission without hidden required keys. Record failures per IME/device/layout. |
| Lifetime | A declared sustained AV/input run completes without elapsed-time shutdown or unexplained stall; a separate automated regression crosses the former lifetime boundary. Explicit Stop and failure safeguards still operate. No finite run proves unlimited uptime. |
| Security | Absent/untrusted identity, wrong endpoint identity, incompatible protocol and malformed/out-of-bounds controls fail closed before unauthorized capture or input. Valid peers establish the intended direct endpoints. |
| Reconnection and release | Triple-click panel entry and local Back preserve the connection/Surface while switching input context. Stop, panel Disconnect, app exit and actual session failure release owned resources. Glasses sleep/pause/Surface detachment preserves the connection and Phone app state and resumes fresh rendering on wake. Real failure recovery establishes fresh owned resources with no input replay; verify child/display/listener cleanup and independent Bluetooth lifecycle. |
| Phone power | Verify the two required v0.1 Awake scenarios above with the Phone unlocked and securely locked, including a declared interval without user input. Demonstrate usable video, audio and native input before and after turning physical panels off during an existing session. Verify session teardown ends inactivity prevention. Record panel, wakefulness, keyguard and virtual-display states, actual test interval and any failures. |
| Supported apps | Qualify ChatGPT, Paseo, Comet and WeChat. Identify each tested installation/version and verify launch/placement, rendering, native controls and exit, plus sound and text entry where applicable. Publish limits rather than extrapolating universal coverage. |

### v0.2 acceptance additions

| Area | Required evidence |
| --- | --- |
| Glasses launch/retry | Launch while paired and reachable, temporarily unavailable, then reachable. Show automatic usable projection, actual retry state and cancellation without repeated manual Connect/Start. |
| Phone activation | Actual HID reconnection and an external button wake/connect the Glasses; the wake press causes no assigned target action. Verify ordinary subsequent presses, overlap, old-session cancellation and resumed app state. |
| Sleep continuity | Actual Glasses sleep/blanking and wake preserve authenticated connection and the same Phone virtual display/app state. Show fresh resumed content/input and independent cleanup on real Stop/failure. Merely reconnecting after sleep is a different result. |
| App notifications | Each menu toggle persists and affects only that app's EyeProjector Glasses route. A real new enabled-app notification wakes/connects and opens that app; disabled-app notification leaves that route inactive while normal Phone notification delivery is retained. Verify duplicate/update/reconnection behavior and an already-active session. |
| Native latency | Physical-device timing compares the v0.1 baseline and new path, separates firmware delay from application/transport/render delay, and retains taps/multi-taps, swipe, long/held input, local/projected routing and duplicate/default handling evidence. |
| Virtual camera | Actual Phone consumer sees a fresh changing test producer on the owned display, followed by real Glasses preview/photo and continuous-video flows in supported target apps. Verify orientation, app selection, close/reopen, interruption/recovery and concurrent projection/input/Bluetooth. Report exact unsupported app/platform cases. |

## Current implementation and unresolved work

The four modules are `directhost` (Phone control and shell bridge), `directrg` (Receiver), `protocol` (authenticated framing and backend parsing), and `hostprobe` (controlled Android fixtures). Toolchain/backend pins and diagnostics remain in [RUNBOOK.md](RUNBOOK.md).

The Owner accepted and released v0.1. It contains Shizuku startup, pairing/session controls, app launchers, scale/settings, native controls, widget/panel operations and Phone HID/head-pointer fusion. Function single Back/double Home is installed on both devices. The [v0.1 run record](docs/runs/v0.1.md) preserves exact source/artifact bindings, independent source/host reviews, measured Phone learning/five-tap behavior, Owner manual results, failures and all unmeasured native/held/default-suppression, locked-Awake, target-app/IME/scale and optical/audio/AV limits. Completion did not turn those limits into new passes.

At the v0.2 baseline, the Glasses Receiver still disconnects on Activity pause/Surface destruction, startup requires explicit activation, the native pad path contains a one-second classification delay, and the per-app Glasses-notification route and virtual-camera implementation do not yet exist. v0.2 addresses those goals under its [authorized plan](docs/versions/v0.2/README.md). Actual stock camera access, notification/background wake behavior, native timing and app compatibility remain to be established on the current devices.
