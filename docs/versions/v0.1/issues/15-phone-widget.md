# EP-15 — Add the two-button Phone home-screen widget

**Canonical issue:** [#19](https://github.com/code2hack/EyeProjector/issues/19). **Blocked by:** [EP-04](04-session-controls.md), [EP-14](14-panel-power.md).

**SPEC:** [Phone home-screen widget](../../../../SPEC.md#phone-home-screen-widget).

Provide exactly **Start/Stop** and **Panel off**, using the same commands and observed session state as the Phone app.

**Primary writes:** widget provider/layout/resources, host manifest and session-state subscription.

**Acceptance**

- The widget can be added to the Phone home screen.
- Start appears while projection is inactive and starts the paired available Glasses; Stop appears while starting/projecting and ends the owned host/session.
- Panel off is available during projection and invokes EP-14's operation while projection continues.
- Changes from either app, Glasses Disconnect, failure and process restart produce truthful widget state.
- Missing pairing or Shizuku authorization opens the relevant Phone setup/status screen.

**Verification:** action/state tests, widget/app cross-control checks and actual home-screen use. Include Stop after panel blanking and restoration through Glasses Disconnect. Share the controller rather than introducing a second session owner.
