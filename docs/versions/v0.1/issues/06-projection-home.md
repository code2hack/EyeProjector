# EP-06 — Launch chosen apps and return to the projected home

**Canonical issue:** [#10](https://github.com/code2hack/EyeProjector/issues/10). **Blocked by:** [EP-04](04-session-controls.md), [EP-05](05-selected-apps.md).

**SPEC:** [Phone and Glasses launchers](../../../../SPEC.md#phone-and-glasses-launchers), [display style](../../../../SPEC.md#monochrome-display-style).

Provide a Phone-hosted home Activity on the session's virtual display, showing the chosen-app grid. Connect both that grid and connected Phone icon taps to a fixed app-launch operation on the current display. Make projection Home return to this grid.

**Primary writes:** projected-home Activity, display-scoped launch/Home handling, Phone icon activation and manifest/resources.

**Acceptance**

- Fresh projection opens the chosen-app home; icons reflect the saved Phone selection.
- Selecting an app from either device launches/switches it on the projection display.
- Home returns to the grid, with no bottom taskbar; the Phone's physical launcher remains separately controlled.
- The grid has black/light styling, and target apps retain their native UI/dark-theme behavior.
- Repeated launch/Home/switch cycles preserve usable video/input and correct display placement.

**Verification:** launch-command and selection tests, display placement instrumentation, and physical cycles with the four target apps. Reuse the existing HOME operation; verify its actual routing. See [home research](../../../research/projection-home.md).
