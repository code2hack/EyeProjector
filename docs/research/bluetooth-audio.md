# Native Bluetooth audio

Owner decision, 2026-10-20: **v0.1 uses native Phone↔Glasses Bluetooth for all audio, with scrcpy audio forwarding disabled.** [SPEC.md](../../SPEC.md#video-and-bluetooth-audio) owns the contract. This note records implementation implications and microphone compatibility boundaries.

## Supported conclusions

| Use case | Conclusion |
| --- | --- |
| Phone media playback on Glasses | Feasible through the selected Bluetooth media route. |
| Phone/chat calls using Glasses speakers and microphone | Feasible for supported calling apps with the Glasses selected as their call endpoint. |
| Arbitrary Phone app Record/ASR button | Microphone choice depends on the target app/service and Android audio policy. Enabling Calls and Audio alone does not establish Glasses-microphone capture. |

The consumer Rokid [FAQ](https://global.rokid.com/pages/faq) documents music and Bluetooth calls, including microphone/speaker use for chat calls. It does not provide an exact profile/codec matrix or a firmware `1.26.011` compatibility guarantee. A Bluetooth version number alone does not establish LE Audio support.

## Output and microphone routes are distinct

In the classic Bluetooth model, the media route uses A2DP and bidirectional voice uses HFP/headset audio. Android documents these as separate profiles. The Phone's media/calls toggles enable the relevant capabilities; active route selection remains a separate fact. [Android profiles](https://developer.android.com/develop/connectivity/bluetooth/profiles)

Communication apps can select a communication device; Android pairs that output with its matching input. Ordinary recorders have their own preferred-device routing, and speech recognition may delegate capture to another service. A Glasses-originated touch starts the recorder in the Phone app; it does not determine the recorder's microphone. [Communication routing](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)), [recorder routing](https://developer.android.com/reference/android/media/AudioRecord#getRoutedDevice()), [recognition service input](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE)

Classic Bluetooth microphone use can lower playback quality relative to the media-only route. LE Audio offers different simultaneous-input/output capabilities, but support and actual negotiation on this device pair are not established. [Android LE Audio overview](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview), [LE Audio recording](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-recording)

## EyeProjector implementation impact

The pinned backend supports disabling audio with `--no-audio` / server `audio=false`. The current Phone bridge requires an audio socket and Opus stream; the Receiver requires that stream and uses its playback clock for video scheduling. A Bluetooth-only design therefore changes host startup, protocol/audio requirements and Receiver video timing, rather than just one server flag. [Pinned audio options](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/doc/audio.md), [host](../../directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/ShellMain.kt), [protocol](../../protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/Wire.kt), [receiver](../../directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ProjectionSession.kt)

Bluetooth audio and projected video use separate delivery paths. Their resulting AV synchronization requires measurement; the existing scrcpy audio-clock results cannot establish Bluetooth AV timing. A successful route to Glasses speakers also does not establish microphone routing in another target app.

For an actual microphone test, identify the Phone recorder/service's routed input while recording and corroborate it with distinguishable speech near the Glasses and Phone microphones. Validate the required target apps individually, along with media playback and call-route transitions. These are proposed physical checks, not results already obtained.

Bluetooth audio profiles do not expose the Glasses camera as a Phone system camera. The experimental [virtual-camera design](camera-forwarding.md) sends Glasses camera frames over the LAN to a Phone camera source; its platform support and app compatibility remain untested.

No live Bluetooth/profile, microphone or AV timing test was performed for this research. This decision updates the product draft; device settings, implementation source and runtime were unchanged.
