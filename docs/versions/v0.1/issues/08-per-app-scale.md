# EP-08 — Pinch and save scale from the Phone app menu

**Canonical issue:** [#12](https://github.com/code2hack/EyeProjector/issues/12). **Blocked by:** [EP-06](06-projection-home.md), [EP-07](07-live-scale-backend.md).

**SPEC:** [Per-app scale](../../../../SPEC.md#per-app-scale).

Add connected-only Scale to the chosen-app long-press menu. Open that app on the Glasses and show the blank Phone gesture panel with an applied numeric scale. Persist scale by app and restore it when that app becomes the selected projection target.

**Primary writes:** Phone scale panel, app scale preferences and app-switch integration.

**Acceptance**

- Scale appears while connected, and a pinch changes the actual app layout seen on Glasses.
- The panel has a numeric value and no Phone preview; its value tracks the applied result.
- Different apps retain independent scale choices across switching and restart/relaunch.
- Closing the panel returns to Phone controls while projection continues.
- Input targeting remains correct after scale changes; unavailable sessions yield actionable status.

**Verification:** gesture/value/store tests, Phone UI tests and physical per-app switching/scale restoration. EP-16 verifies full keyboard usability at the supported scales.
