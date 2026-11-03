# EyeProjector product specification

EyeProjector runs supported Android apps on a Phone-owned workspace and projects them directly to smart glasses. The Glasses provide the display, head pointer, native controls, camera and Bluetooth audio endpoint. A development computer may install or provision the system; it is not a media or input relay.

**v0.2 is Owner-accepted.** On 2026-10-28 the Owner accepted the managed-display experiment and v0.2 and authorized publication, merge and cleanup. The [v0.2 record](docs/runs/v0.2.md) distinguishes accepted device observations, implementation checks and remaining limits. [v0.1](https://github.com/code2hack/EyeProjector/releases/tag/v0.1) remains historical; its earlier limitations are not descriptions of the current managed path.

**v0.3 is an archival development checkpoint.** The Owner authorized its consolidation into main and the v0.4 redesign on 2026-11-03 (Asia/Singapore). The [v0.3 checkpoint](docs/versions/v0.3/CHECKPOINT.md) records its source, actual observations and remaining limits; it is not a claim that every old acceptance case passed.

**v0.4 implementation is Owner-authorized.** Its current requirements replace control-identity/Click/Hold Anchors with per-app lists of absolute screen positions and Normal, Anchor and Anchor Settings modes. The [v0.4 plan](docs/versions/v0.4/README.md) defines the issue boundaries and verification. This is the required behavior, not a statement of completed implementation or device acceptance.

This document defines the current product contract. The [v0.2 plan](docs/versions/v0.2/README.md) records scope, [managed-display setup](docs/versions/v0.2/managed-display.md) describes the current bootstrap, [RUNBOOK.md](RUNBOOK.md) covers engineering procedures, and [AGENTS.md](AGENTS.md) governs agent coordination.

## Terms

| Term | Meaning |
| --- | --- |
| Phone | Android device hosting target apps, managed workspace, encoder and virtual camera. |
| Glasses | Receiving display with camera, audio, head tracking and native input. |
| Target app | An ordinary, unmodified Phone app opened in the projection workspace. |
| Managed source display | The app workspace owned by an Android VirtualDeviceManager device, with its own display, camera context and lock policy. |
| Capture mirror | A separate capture copy used by the legacy scrcpy video path. It is not the app workspace or positional-input target. |
| Projection session | Authenticated video/control transport, camera forwarding when requested, and their owned resources. |
| Virtual camera | Phone camera interface supplied with frames captured by the Glasses. |
| EyeProjector Home | The chosen-app icon grid rendered on the Phone's projection workspace. |
| Control panel | The Glasses-local connection and pointer/input settings UI. |
| Pin | Dedicate one selected physical Phone-connected HID device to EyeProjector by intercepting its input collections. |

## Architecture and Phone power

### Managed virtual-device execution

Running ordinary apps on a **managed source display** is the central v0.2 architectural discovery. With **Use Glasses camera** enabled, the authorized shell backend creates a legitimate VirtualDeviceManager workspace. Apps, virtual-camera selection, layout configuration and positional input belong to that source display.

The source renders directly into a MediaCodec AVC encoder. scrcpy runs with video and audio disabled and supplies the control connection targeting the managed source. The current managed path does not encode a separate scrcpy mirror. This removes the earlier dependency on a capture mirror that could turn off with the physical Phone and leave the Glasses displaying an old image.

The source uses Android's independent unlocked-display policy. The accepted Fold6 configuration continues projection and source-directed interaction while the physical Phone screens are off and its keyguard is locked. It also works with the Phone unlocked. This does not remove the Phone's PIN, unlock its physical displays or bypass the physical keyguard.

Physical panel power, keyguard, aggregate device wakefulness and the managed display's own power group are separate facts. An awake app workspace still requires processor and network work. Working projection with locked, blank physical panels does not establish full-device suspend, DeviceIdle deep Doze or universal OEM compatibility.

The current implementation selects this managed route when **Use Glasses camera** is On. Off retains the earlier Phone-camera/display path. Managed-path power and camera observations must not be attributed to that legacy path without evidence; making managed execution common to both camera choices is not part of the accepted implementation.

### Setup and privilege

The Phone uses Shizuku to run its projection backend with explicitly authorized Android shell privileges. The app guides the user through Shizuku installation, startup, authorization and recovery. An ordinary APK installation does not grant virtual-device, camera-registration or input-injection privileges.

Managed execution requires the actual platform APIs and a legitimate companion-device association owned by the authorized backend. Missing association, permission or platform capability must produce actionable setup status. The workspace's unlocked-display policy uses the required platform privilege; peer requests do not become arbitrary shell commands.

The accepted managed/virtual-camera configuration uses the exercised stock Android 16/QPR2 Phone. APK minimum/target SDK values alone do not establish that these advanced APIs work on another OS or vendor implementation. Reboot can require Shizuku recovery and native-helper bootstrap; automatic restoration of every privileged component is not claimed.

### Direct transport

Phone and Glasses communicate directly on a reachable LAN, including the Phone hotspot configuration. Video, input and camera traffic do not pass through a development computer. Audio uses the devices' native Bluetooth connection.

Mutual TLS authenticates the paired endpoints before capture or control. Protocol versions, channel layouts, dimensions and payload bounds are validated. Missing or untrusted identity is rejected. Private keys, credentials, peer configuration and live device addresses remain outside public source and release records.

The main connection retains normal OS TCP buffering. Each complete wire header and payload is submitted in one application write, preserving framing without introducing a batching delay. TLS may split that write internally. Ping round-trip samples are diagnostics: a delayed sample alone must not terminate a healthy projection.

## Connection and everyday controls

### Pairing

Unpaired Glasses show a four-digit code and a five-minute countdown. Expiry rotates the code and restarts the countdown. The Phone's **Pair with display device** action accepts that code over the LAN. Each displayed code permits at most five pairing attempts; rotation resets the limit without interrupting an existing projection.

Pairing remembers an EyeProjector peer for video, control and camera forwarding. Stop and Disconnect preserve it; Forget removes it. System Bluetooth pairing remains independent.

| Device | Controls |
| --- | --- |
| Phone, unpaired | Pair with display device. |
| Phone, paired | Start, Stop, Forget and actual setup/connection status. |
| Phone camera choice | Persisted Use Glasses camera switch. |
| Phone app selection | Chosen-app icons, with + first; tap to open, hold for the app menu. |
| Phone HID setup | Select device/profile, Pin, assign Tap / Scroll down / Scroll up / Back / Home / Lock / Mode, Unpin, Reset selected profile. |
| Glasses, paired | Connect or Disconnect, Forget and actual connection status. |
| Glasses local settings | Separate X/Y pointer sensitivity, scroll speed and head-motion scrolling. Bluetooth HID bindings are Phone-only. |

Host availability, remembered pairing, authenticated connection and usable projection are distinct states. The UI must not present a queued request as a completed connection or native operation.

### Launch, wake and recovery

Opening EyeProjector on paired Glasses requests automatic connection. Phone-connected HID reconnection or an input while projection is unavailable requests wake/reconnection. An enabled chosen-app notification can request wake/reconnection and open its app. Overlapping requests share the existing ownership path rather than creating duplicate sessions.

A HID press consumed for wake/reconnection is not replayed as an app action after readiness. Later presses operate the saved controls. Explicit Stop, Disconnect and app exit cancel the current activation and pending input; a subsequent allowed trigger can request a new activation.

Temporary Glasses Activity/Surface loss suspends or detaches local presentation/input while retaining the connection and Phone workspace. Sleep/wake and genuine transport/process failure are distinct: failure recovery releases the old owner before creating a replacement, and old input is not replayed into it.

### Chosen apps and notifications

Both launchers use the Phone's chosen-app list. EyeProjector Home is a taskbar-free icon grid; selecting an app opens it on the projection display. Apps retain their ordinary interfaces.

Long-pressing a chosen Phone icon opens a menu headed by its app name, containing **Glasses notifications**, editable **Scale** with a slider, and **Remove**. Opening the menu or changing notification preference alone does not launch the app. Remove edits only the chosen-app list.

The per-app notification toggle persists independently of connection and app restart. On enables EyeProjector's new-notification wake/open route; Off disables that route without changing the Phone's OS notification settings. Chosen apps initially default to On unless the user saved another choice. Duplicate updates and listener reconnection do not become new notifications.

The Phone widget retains the shared Start/Stop path and actionable setup recovery. Its legacy **Panel off** control is not required by the current managed workflow: users may lock or blank the physical Phone normally. A separate Panel off repair was dropped once managed projection through physical-screen sleep worked.

## Camera and audio

### Glasses camera in ordinary apps

**Use Glasses camera** is a persisted Phone control. On gives supported ordinary apps in the managed workspace a Glasses-fed virtual camera. Off selects the Phone-camera route. Applying a changed choice restarts projection through ordered retirement of the old camera/session owner; it does not mutate a registration already in use.

Opening the virtual camera starts the required Glasses capture. It must not silently start the physical Phone camera as the producer for the Glasses-camera choice. Other apps independently using the physical Phone are outside that camera ownership.

The live viewfinder follows the complete path:

**Glasses camera → Phone virtual camera → ordinary Phone app → managed-display video → Glasses.**

The app's viewfinder, shutter and other controls remain the actual projected UI, including controls drawn over its viewfinder. Photo/Add photo and supported continuous-video/call flows use the app's normal camera interfaces. No standalone Glasses shutter or replaced viewfinder image is required.

The Owner accepted the much faster returned preview and explicitly dropped the Glasses-local preview replacement. No precise end-to-end camera latency is claimed. Live previews were exercised in Douyin, Doubao, ChatGPT and WeChat; this does not establish every photo, video-call or app/OS combination.

Orientation and aspect must be correct without stretching or a per-app rotation workaround. Apps retaining an older camera/device context may need a fresh process after workspace recreation. The observed WeChat orientation issue became upright after a fresh app process; camera-context refresh remains a compatibility consideration.

Camera close, source replacement and interruption preserve original resource ownership until their actual retirement is observed. Pending/failed native cleanup is reported honestly rather than treated as successful release.

### Native Bluetooth output and automatic microphone

Projection does not forward audio. Media output and calls use the Phone/Glasses Bluetooth connection. Microphone capability and selected recording input are separate from successful A2DP playback.

While the backend is enabled, actual unsilenced microphone/voice recording activity drives automatic Glasses-microphone routing. The shell owner requests communication mode and the SCO communication endpoint matching Android's currently active Bluetooth HEADSET. It creates no dummy recorder or playback. Android and the target app retain routing priority; an unrelated available headset is not selected arbitrarily.

When recording ends, the backend disables or the owner closes, it releases only its own communication-device and mode requests. Idle projection does not retain communication mode. Recording through an unsupported profile or an app's own forced input must be reported as a compatibility limit.

The Owner confirmed automatic Glasses-microphone input for the exercised dictation and voice-message workflows. The earlier SCO-only comparison still used the Phone mic; the successful comparison also required communication mode. Broader recording/app/profile compatibility and numeric physical AV synchronization remain unmeasured.

## Input

### Pointer, native controls and Mode

The Phone hosts one authoritative per-app mode and Anchor list. The Glasses render that state and capture native input; Phone-connected HID controls use the same semantic operations. Mode is independent of the physical Rokid Function button.

The physical Function button's short press performs **Mode**. Its long press performs no action. Other Rokid bindings retain the v0.3 native behavior, including ordinary touchpad Tap/hold/drag, scrolling, contextual two-finger Back/Home and the existing native Lock behavior. Firmware brightness coexistence and shell-helper limitations remain documented. Long Function no longer opens a registration chooser; completed duplicate OEM reports must not advance Mode twice.

The mode cycle in an ordinary projected app is **Normal → Anchor → Anchor Settings → Normal**. If that app has no Anchors, skip Anchor: **Normal → Anchor Settings → Normal**. A learned HID Mode gesture advances the cycle exactly once, whether assigned to a short press, long press or multi-click.

Each app remembers its mode during the running EyeProjector instance. Switching to another app applies that app's current mode. EyeProjector Home always uses Normal and does not acquire its own Anchor mode. Restart initializes every app to Normal; only saved Anchor positions survive restart.

| Mode | Head pointer | Scroll up/down | Tap | Back |
| --- | --- | --- | --- | --- |
| Normal | Follows head motion using normal X/Y sensitivity | Ordinary page scrolling; held Scroll keys repeat | Existing ordinary Tap behavior | Ordinary contextual Back |
| Anchor | Fixed at selected saved Anchor, independent of head motion | Previous/next Anchor in creation order, with wrap; held Scroll keys repeat selection | Existing ordinary Tap at selected Anchor | Ordinary contextual Back |
| Anchor Settings | Follows head motion as in Normal | No action | Append current pointer position and save immediately, without app input | Remove newest Anchor and save immediately, without app Back; empty list does nothing |

In Anchor mode, entering selects the first Anchor by creation order. Scroll Down chooses the next and Scroll Up the previous. Head-motion page scrolling is disabled in Anchor mode regardless of its Normal-mode setting. In Normal, that saved head-motion scrolling setting retains its usual effect. Anchor Settings Scroll controls do nothing. Home and Lock retain their ordinary behavior; after navigation, apply the destination app's mode, and use Normal at EyeProjector Home.

### Coordinate Anchors and editing

An Anchor is only an **absolute screen position** on the projected app canvas. It does not identify an accessibility node or follow a control as the app layout changes. The same saved position is used through the existing projection/input coordinate mapping; no UI-tree matching, ancestor substitution, resource/label identity, clickable/action eligibility or automatic target relocation participates.

Each ordinary app has its own ordered list. Anchor Settings displays all saved positions, numbered by creation order. Tap appends to the end; Back removes from the end, giving last-in-first-out removal. Every successful add/remove is persisted immediately. A failed write must report failure rather than show an unsaved change as saved. Leaving Settings requires no additional save confirmation or chooser.

There are no Click/Hold Anchor types, held-Anchor contact toggle, registration long press or Click/Hold/Cancel menu. Tap uses its normal touch behavior at the current pointer. Ordinary user-held Tap/drag still owns its normal contact and terminal; removing the old Anchor Hold implementation must not break it.

There is no global keyboard-Anchor category. Keyboard visibility, current key labels and the IME package do not alter a coordinate Anchor. If a keyboard is shown at a saved point, an ordinary Tap affects whatever currently occupies that point; hidden keyboard controls are not invoked by identity and the keyboard is not opened automatically.

Old v0.3 descriptors do not contain the new coordinate lists. Do not guess positions by resolving old controls. New lists begin empty; historical preference data can remain inactive while source/evidence is archived. Preserve pairing, pin intent, permission choice and unrelated settings.

### Phone HID: pin, learn and operate

Bluetooth controllers connect to the Phone only. The pinned physical group and its input collections are dedicated to EyeProjector; unassigned inputs do not operate Android independently. Keep actual interception, saved pin intent, retry, explicit Unpin and profile reset, and wake/reconnection behavior distinct.

The assignment rows are **Tap, Scroll down, Scroll up, Back, Home, Lock and Mode**. Remove the Anchor row and combined Back/Lock/Home role. Back, Home and Lock are separate semantic actions rather than a built-in one/two/three-click navigation sequence.

| Assignment | Learning | Runtime |
| --- | --- | --- |
| Tap | Capture a single click only | Retain existing Tap/multi-tap/real hold/drag behavior |
| Scroll down / Scroll up | Capture a single click only | One direction action on press; holding repeats according to the active mode |
| Back / Home / Lock / Mode | Capture and distinguish short press, long press and multi-click | Match the learned gesture and perform its assigned action once |

Learning consumes its gesture without invoking the app or mode action. Learning still waits for the final quiet period to discover the complete short/long/multi-click gesture. Runtime uses the saved gesture instead: a short SINGLE acts immediately on release, without a final multi-click wait; MULTI N acts on the Nth short release, with 500 ms as the maximum gap between incomplete clicks. No short action fires while a saved multi-click is incomplete. A completed gesture retires, so the next press begins a new gesture: four rapid clicks of a double-click binding produce two actions. LONG retains its 800 ms threshold and acts on release. Tap and Scroll retain the behaviors in the table.

Each physical HID device has its own persistent binding profile, including its learned gestures. Configuring one device does not overwrite another. Only one pinned device is active at a time; simultaneous HID operation is outside the current design. The existing device selection and Pin flow selects the corresponding profile. Unpinning or switching preserves saved profiles. Reset clears only the selected profile. Reconnect and restart restore the saved pin/profile intent. Profiles use actual stable device identity rather than transient Android input IDs; migration must retain recorded identities rather than guess them from whichever device is currently pinned.

Repeats, pending gestures and release tails belong to the original device, binding and press. Stop, connection/device loss, profile reset and device replacement retire that original input before another device/profile takes over; stale release/timer/learning callbacks must not operate the replacement. A later distinct input is handled normally. No invented action, default physical assignment or new hardware input grab is introduced.

Existing compatible Tap/Scroll bindings and pin intent are retained. Where a legacy single-button Anchor assignment is migrated, its short gesture becomes Mode. A legacy combined navigation assignment can preserve its single-click Back as Back; Home/Lock need explicit assignments and must not silently inherit multiple actions from one binding. The persistence migration must be explicit and tested, without resetting unrelated settings. Migration from the v0.4 global map retains each recorded device's bindings in its own profile, including mixed-device records, and keeps legacy data recoverable. Unpinning must not erase those records.

The native shell reader remains a Glasses-local product component where needed for actual raw input and fixed sleep; no Glasses HID learning/storage/dispatch is reintroduced. ADB automation is a development test route and is not a production media/input relay.

### Per-app native layout scale

The icon long-press menu contains an editable numeric value and synchronized slider from **0.5× to 1.5×**. **1×** is the center and default. There is no extra Scale button below each icon and no pinch-panel navigation.

Dragging previews the requested value and commits on release. Numeric completion and discrete keyboard/accessibility adjustment commit an absolute value. The first committed edit selects/opens that app in the captured projection context; stale app/session work is not applied to a replacement.

Scale changes the app's **native projected layout**. Smaller values show smaller UI and more content across the Glasses; they do not add image margins or apply a crop/bitmap zoom. Requested profiles persist per app and restore on selection. Saved request and actual observed layout are distinct feedback. Invalid legacy values outside the supported range reset to 1×; an already settled identical restore avoids unnecessary reconfiguration.

The managed source captures the physical Phone density as its session baseline and changes only its own logical layout size, separately from fixed encoded-video geometry. No global Phone size/density, font scale or package-wide zoom setting is changed. Scaling must leave the physical Phone app view normal, survive the target app's process restart and return to the original projected baseline at 1×.

The Owner accepted the exercised WeChat 1× → 0.75× → Phone reopen/process restart → projected reopen → 1× sequence: **“Both views scale correctly now.”** This does not create independent app processes or guarantee arbitrary apps' global resource-cache behavior.

### Text entry and current-frame presentation

Focusing an editable field uses the real Phone IME on the projection display. Its visible keys must support normal insertion, deletion, selection and submission through the pointer/Tap route. The app layout must keep required keys reachable.

A completed decoded UI redraw must remain eligible for presentation when its target time has already passed. An age-only discard must not leave the final update of a static keyboard invisible. Late completed frames are presented promptly; future-frame pacing and ownership/Stop handling remain separate.

After that rendering correction, the Owner reported **“Typing is prompt and stays connected”** in the projected-keyboard follow-up. Earlier TCP/packet-write changes alone were insufficient. The result establishes the exercised keyboard behavior, not numeric optical latency or every app/IME operation.

## Lifecycle, compatibility and acceptance

Start, Stop, Connect, Disconnect and Forget share explicit ownership. Phone Stop, Glasses Disconnect, app exit and genuine failure release their owned sockets, capture/control children, virtual display, codecs, camera resources, input and sensor subscriptions. Async native retirement may remain pending; a submitted stop is not proof of release. Pairing and ordinary system Bluetooth state remain independent.

Opening the control panel from EyeProjector Home and returning with local Back preserve a live session. Sleep/temporary detachment preserves the connection/workspace contract; actual peer/network/process loss follows recovery. No overall elapsed-time cutoff ends a healthy session. Startup and genuinely blocked resource operations retain their applicable finite bounds.

The Owner accepted v0.2 and the experiment and authorized publication/merge/cleanup after the camera, microphone, scaling and keyboard follow-ups. Run records retain the earlier failures and the distinction between implementation checks, device observations and Owner acceptance. Acceptance does not retroactively convert an old rejected candidate or an unexecuted matrix into a pass.

| Accepted observation | Scope |
| --- | --- |
| Managed projection with physical Phone off/locked | Exercised stock Fold6 configuration and source-directed input; not full-system deep suspend. |
| Ordinary-app Glasses camera preview | Exercised Douyin, Doubao, ChatGPT and WeChat contexts; upright WeChat after fresh app process. |
| Faster full-round-trip preview | Owner accepted the improvement; exact end-to-end camera latency unmeasured. |
| Automatic Glasses mic | Exercised dictation/voice-message inputs; system routing and endpoint compatibility still apply. |
| Projection-only native scale | Owner-confirmed WeChat appearance, restart stability and absolute return to 1×. |
| Native hold/head-drag | Owner accepted the approximately three-second native hold limit; longer HID hold works. |
| Keyboard follow-up | Owner-confirmed prompt typing and connection stability after late-frame rendering correction. |

Secure surfaces/DRM, app capture or multi-display policies, task reuse, camera-context caching, vendor IMEs, audio routing and OEM power behavior can limit compatibility. The current app workspace is not a cloned Android user or independent Application instance. Report specific unsupported combinations rather than claiming universal app/device support.

Native-helper recovery after reboot/process loss, broader app/photo/video-call coverage, full DeviceIdle/deep-suspend behavior and precise optical/audio/camera latency remain distinct work. The accepted manual-bootstrap and native-hold limitations remain visible in release documentation.
