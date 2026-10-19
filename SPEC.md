# EyeProjector product specification

EyeProjector projects supported Android applications from a Phone-hosted virtual display onto smart glasses, forwarding synchronized video and audio while accepting glasses-native interaction.

This document defines product requirements. Build instructions belong in [RUNBOOK.md](RUNBOOK.md), agent coordination in [AGENTS.md](AGENTS.md), and source history and observed results in [the initialization audit](docs/migration/initialization-audit.md). A requirement here is not a claim that the current implementation has passed it.

## Terms

| Term | Meaning |
| --- | --- |
| Phone | Android device running the projection host. |
| Glasses | Lightweight receiving display, audio and input device. |
| Target app | Android application running on the Phone's projection display. |
| Virtual display | Phone-owned Android display created for projected apps. |
| Projection session | Authenticated transport and active video, audio and control lifecycle. |
| Projection host | Phone-side session, capture, encoding and control implementation. |
| Receiver | Glasses-side video/audio decoder and interaction client. |
| Agent runtime | Development orchestration infrastructure, separate from the product. |

## User outcomes

1. As a user, I want a supported Phone app to run on the projection display, so that I can see and hear it on the Glasses.
2. As a user, I want to establish an authenticated connection, so that only my authorized peer receives media or sends controls.
3. As a user, I want a responsive local head pointer and touchpad actions, so that I can select, go Back and scroll in the target app.
4. As a user, I want to operate the Phone's system keyboard, so that I can enter and edit text in the target app.
5. As a user, I want explicit Stop, local Disconnect and a fresh reconnect, so that I can end or resume projection without stale input or abandoned resources.
6. As a user, I want clear failure and compatibility information, so that I can distinguish a connected transport from usable video, audio and app interaction.
7. As a user, I want projection to continue until I end it or a real failure occurs, so that an elapsed-time limit does not interrupt my session.

## Product behavior

### Execution and transport

The target app executes and renders on the Phone's virtual display. The Glasses receive the projected output and send native controls directly to the Phone. A development computer may provision and debug the devices; it is not a media or control relay in the product path.

The projection host must create and own the virtual display and its session resources. The current backend requires an explicitly authorized Android shell bootstrap. Installing or opening the ordinary host APK does not grant shell privileges. The bootstrap's privilege, exact code and device must be attributable; peer requests must not become arbitrary shell commands.

Peer establishment must authenticate both endpoints before creating capture, media or input resources. The transport must preserve confidentiality and integrity, validate protocol versions and message bounds, and reject an absent or untrusted peer identity. An authenticated peer must not gain a general command execution interface.

### Video and mandatory audio

The host must encode target-display video and the Receiver must decode it, using Android hardware assistance where available. The Receiver must preserve aspect ratio and map input to the actual current video geometry. A changing source must produce changing displayed content; a live socket or a repeated old frame is insufficient.

Audio forwarding is mandatory. Unsupported, absent, malformed or stalled audio must produce an explicit session failure rather than a successful silent video session. Audio and video must preserve source timing and play in synchronization through bounded buffers. Backpressure must not create unlimited queues or an ever-growing delay. Decoder and playback failures must be visible and release session resources.

Audio capture scope must be disclosed. Target-only isolation is unresolved; the current backend captures Phone output and can include other apps. Protected content and apps that disallow capture remain subject to Android policy. Microphone capture and capture-policy bypass are outside this product's current scope.

### Glasses-native interaction

The Receiver must draw a local head-pointer overlay independently of video delivery. It must stop or reset tracking when sensor data, focus or the session becomes invalid. The pointer must use current display orientation and fitted video geometry, with no remote tap for a target outside the visible app image.

The current interaction contract is:

| Action | Required behavior |
| --- | --- |
| Head movement | Move one local pointer with the configured bounded gain. |
| Short touchpad tap | Send one native touch at the visible pointer target. |
| Two quick touchpad taps | Send Android Back to the projected Phone app. |
| Three quick touchpad taps | Disconnect locally, without sending constituent taps or Back. |
| Touchpad forward/backward swipe | Send native wheel scrolling at the visible target. |
| Aim beyond the upper/lower video edge | Repeat bounded native scrolling until aim returns or the session becomes invalid. |
| Android Back while projecting | Send Phone Back. |
| Android Back while disconnected | Use the Receiver's ordinary local Activity navigation. |

Duplicate device reports, canceled contacts and incomplete gestures must not trigger extra actions. Pause, focus loss, geometry change and Disconnect must cancel pending input. Reconnect must not replay old input. Native app scroll behavior may differ between widgets.

Projection controls must remain minimal: connection/status, local pointer/scroll settings, Disconnect and host Stop. Phone-provided navigation can expose app selection where supported. A general app launcher and a Glasses-local keyboard are outside the current scope.

### Text entry

The Phone's selected system IME is the first text-entry route. Focusing an ordinary editable field must request the real Phone IME on the projection display; the Glasses pointer and touchpad must operate its visible keys. Full usability requires observable text insertion, deletion, selection and submission, with reachable keys and a usable app layout. Keyboard pixels alone do not establish usable text input. Full Phone-IME usability remains unresolved.

### Session lifecycle

Connect must start a fresh authenticated session and report progress until usable video and audio are established. The Receiver must distinguish connecting, streaming, stopped and failed outcomes. The host must serialize ownership of active projection resources.

Phone Stop must end the host and its active projection. Glasses Disconnect must end that session locally; pause, Surface destruction and genuine transport/media failures must follow the same release path. Release includes sockets, capture children, virtual display, codecs, audio playback/focus, sensors and pending input owned by that session. A status message that a stop was sent is insufficient to verify teardown.

The host has no overall elapsed-time cutoff. Startup, blocked I/O, stalled audio and resource-release operations retain finite safety bounds. A long-running test interval is a measurement, not a product expiry time. Reconnect must allocate fresh media/control resources and a new synchronization clock after the previous session releases ownership.

### Phone power and Android compatibility

The following states are independent and must be recorded separately in any claim:

| State | What must be established |
| --- | --- |
| Physical panel power | Whether each physical Phone panel is actually displaying or blanked. Brightness and a black app image are not panel-power evidence. |
| Device wakefulness | Android Awake, Dozing or Asleep state and its transitions. |
| Keyguard | Whether the keyguard is present/locked and what app interaction Android permits. |
| Virtual display | Whether the session display exists, has the target app, and continues producing usable AV/input. |

Panel-off operation is unresolved. Qualification must test both continuing an existing session when physical panels are blanked and starting fresh capture while panels are already blanked and Android remains Awake. Secure-lock continuity is a separate compatibility result; EyeProjector must respect keyguard and Android security boundaries.

App placement, multi-display support, secure surfaces, DRM, capture opt-out, audio focus and OEM behavior limit compatibility. Support must be stated for tested app/device/OS combinations. The existence of a virtual display does not imply that every ordinary Android app works on it. Automatic bootstrap after reboot is unresolved.

## Acceptance expectations

Each acceptance record must bind the exact source commit, built and installed artifact hashes, device identities/OS versions, private configuration revision and actual test interval. Record failures and incomplete observations alongside passes. Host builds and synthetic tests support implementation checks; physical behavior needs device evidence on the same candidate.

| Area | Required evidence |
| --- | --- |
| Video | A known changing source remains visible on the Glasses, with actual rendered progress, correct aspect/orientation and no sustained decoder stall over a declared sustained run. Record encoder/decoder names and dimensions. |
| Audio and synchronization | Source and decoded audio progress, real Glasses audibility, correct volume/focus behavior and bounded backlog. Measure physical flash/beep offset against a 100 ms target with a stated method and uncertainty; software timestamps alone do not pass physical synchronization. |
| Native input | Physical head direction, one tap, Back, main/nested scrolling and three-tap Disconnect produce the intended app/local effects without duplicates. Record input settings and test cancellation/geometry changes. |
| Phone IME | Actual system keyboard on the virtual display supports physical insertion, deletion, selection and submission without hidden required keys. Record failures per IME/device/layout. |
| Lifetime | A declared sustained AV/input run completes without elapsed-time shutdown or unexplained stall; a separate automated regression crosses the former lifetime boundary. Explicit Stop and failure safeguards still operate. No finite run proves unlimited uptime. |
| Security | Absent/untrusted identity, wrong endpoint identity, incompatible protocol and malformed/out-of-bounds controls fail closed before unauthorized capture or input. Valid peers establish the intended direct endpoints. |
| Reconnection and release | Stop, Disconnect, pause, Surface loss and network/media failure release owned resources. Reconnect establishes fresh AV and input with no replay; verify child/display/listener cleanup as applicable. |
| Phone power | Record panel, wakefulness, keyguard and virtual-display states separately for baseline, panel-blank transition, fresh capture while blanked/Awake, wake/sleep transitions and keyguard cases. A failed or untested case remains unsupported. |
| Supported apps | Identify each tested target app/version and verify launch/placement, rendering, sound, native controls, text entry where applicable and exit. Publish limits rather than extrapolating universal coverage. |

## Current implementation and unresolved work

The four modules are `directhost` (Phone control and shell bridge), `directrg` (Receiver), `protocol` (authenticated framing and backend parsing), and `hostprobe` (controlled Android fixtures). Current parameters, backend pins and diagnostics are technical details in [RUNBOOK.md](RUNBOOK.md), not permanent product requirements.

Initialization does not establish fresh physical acceptance of the renamed applications. Full Phone-IME usability, panel-off continuation and fresh startup, sustained decoder stability, reboot bootstrap, target-only audio isolation and supported target-app coverage remain unresolved. Physical AV timing needs quantified acceptance. Historical observations retain their own source/artifact bindings in the [migration record](docs/migration/initialization-audit.md).
