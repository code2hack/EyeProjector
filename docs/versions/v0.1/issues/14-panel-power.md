# EP-14 — Own panel OFF and restoration for the active session

**Canonical issue:** [#18](https://github.com/code2hack/EyeProjector/issues/18). **Blocked by:** [EP-04](04-session-controls.md).

**SPEC:** [Phone power](../../../../SPEC.md#phone-power-and-android-compatibility).

Expose the fixed physical-panel OFF operation through the session controller for the widget. Reuse the stock backend display-power mechanism, serialize control writes, retain session-scoped inactivity prevention and restore display power when the session ends.

**Primary writes:** host panel operation/control writer, protocol request and session cleanup/status tests.

**Acceptance**

- An active session accepts Panel off and powers down physical displays while Android remains Awake and the virtual display remains usable.
- The result distinguishes command submission from observed success/failure.
- Stop, Disconnect and session failure restore physical display power and end keep-active behavior.
- Unlocked and securely locked Awake continuation cases work, including an interval with no user input.
- Record actual foldable-panel restoration, rather than assuming the prior individual panel states were reproduced.

**Verification:** fixed-command/serialized-write/lifecycle tests and reserved Phone/Glasses observations. Consult the [panel note](../../../research/phone-panel-power.md) and [pending provenance](../../../provenance/README.md); reconcile source acceptance and device custody before reuse. Fresh capture while already blanked and sleep/Doze recovery remain future work.
