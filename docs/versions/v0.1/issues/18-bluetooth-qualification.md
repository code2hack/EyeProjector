# EP-18 — Qualify native Bluetooth audio, microphone routes and AV timing

**Canonical issue:** [#22](https://github.com/code2hack/EyeProjector/issues/22). **Blocked by:** [EP-06](06-projection-home.md), [EP-10](10-native-taps-scroll.md), [EP-17](17-video-stability.md).

**SPEC:** [Video and Bluetooth audio](../../../../SPEC.md#video-and-bluetooth-audio).

Measure the actual native Bluetooth behavior with scrcpy audio disabled. Exercise representative available playback, calling and recording/ASR workflows among ChatGPT, Paseo, Comet and WeChat, using Glasses interaction to initiate them.

**Primary writes:** focused diagnostic/fixture improvements and version-bound compatibility evidence; any required video-timing adjustment is coordinated with EP-17.

**Acceptance**

- Record audible media output, volume and call-route transitions on the Glasses.
- Establish the actual microphone for each tested recording/ASR workflow; label Phone-microphone or unavailable routes accurately.
- Bluetooth loss or microphone unavailability leaves projection video/control usable.
- Stop, Disconnect and Forget preserve ordinary system Bluetooth management.
- Measure physical video/audio offset against the SPEC's 100 ms target with stated method and uncertainty; preserve failures for correction/disposition.

**Verification:** real device/app routes and physical flash/beep measurements. Software timestamps and old forwarded-audio results are supporting diagnostics only. See [Bluetooth research](../../../research/bluetooth-audio.md).
