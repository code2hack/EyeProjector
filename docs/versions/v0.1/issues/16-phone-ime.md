# EP-16 — Make the Phone IME usable on the projection display

**Canonical issue:** [#20](https://github.com/code2hack/EyeProjector/issues/20). **Blocked by:** [EP-06](06-projection-home.md), [EP-08](08-per-app-scale.md), [EP-11](11-long-press-drag.md).

**SPEC:** [Text entry](../../../../SPEC.md#text-entry).

Qualify and fix the real Phone system IME route on the virtual display, using the existing pointer/touch pipeline. Resolve placement, viewport and scale issues that prevent actual text editing.

**Primary writes:** host display/IME integration, owned editable-field fixtures and any affected geometry handling.

**Acceptance**

- Focusing a projected editable field opens the selected Phone IME on the projection display.
- Glasses interaction produces actual insertion, deletion, selection and submission.
- Required keys and selection handles remain reachable at supported app scales.
- App switching, Home, keyboard dismissal and reconnect preserve valid input targeting.
- Record the IME/app/device versions and precise incompatible cases.

**Verification:** fixture instrumentation and human text-entry workflows in the initial target apps where applicable. Keyboard pixels alone are insufficient. A concrete incompatibility is reported for scope/implementation disposition; this is a core requirement, unlike the optional camera experiment.
