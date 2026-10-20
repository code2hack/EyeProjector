# EP-04 — Operate one projection session from both control panels

**Canonical issue:** [#8](https://github.com/code2hack/EyeProjector/issues/8). **Blocked by:** [EP-01](01-shizuku-startup.md), [EP-02](02-lan-pairing.md), [EP-03](03-bluetooth-video.md).

**SPEC:** [Session lifecycle](../../../../SPEC.md#session-lifecycle), [control panels](../../../../SPEC.md#pairing-and-control-panels).

Connect paired-peer availability to one owned host/session state and the two control panels. Phone Start must initiate projection on available paired Glasses; Glasses Connect attaches to the available Phone host. Provide the same commands/state to later app controls and the widget.

**Primary writes:** host session controller, peer start/connect route, host/Receiver control-panel integration and lifecycle tests.

**Acceptance**

- Phone Start reaches usable projection without a second Glasses button press.
- Glasses Connect/reconnect and Phone Stop have the specified observed effects.
- Startup, streaming, inactive and failed states agree across both devices; concurrent/repeated actions preserve one session owner.
- Opening the local control panel during projection retains the connection, decoder and Surface; it switches to local input and cancels pending projected gestures/head scroll. Local Back returns without reconnecting. Provide these view transitions for the function-button issue.
- Glasses Connect becomes Disconnect while connected. Panel Disconnect, app exit, pause, Surface loss and genuine failure release session resources and pending input. Fresh reconnect allocates fresh resources.
- Paired panel actions and setup guidance work; Glasses controls use black backgrounds/light content.

**Verification:** state-transition and competing-action tests, both-device lifecycle instrumentation, and observed backend/display/socket teardown. Keep session duration unlimited; individual startup/I/O operations retain their bounded behavior.
