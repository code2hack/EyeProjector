# EP-05 — Maintain the Phone's chosen-app list

**Canonical issue:** [#9](https://github.com/code2hack/EyeProjector/issues/9). **Blocked by:** None.

**SPEC:** [Phone and Glasses launchers](../../../../SPEC.md#phone-and-glasses-launchers).

Implement the Phone's persistent projection-app selection. Area 2 displays chosen-app icons with **+** first; adding selects an installed app, and holding an icon exposes Remove. Supply the saved selection to the projected home and app-launch integration.

**Primary writes:** app catalog/selection store, Phone app-list component, relevant resources and tests. Coordinate the `HostActivity.kt` integration window with EP-01/02.

**Acceptance**

- Add, display and remove chosen apps; the leading + remains first after edits/restart.
- Remove changes the projection list only and preserves the installed app and its data.
- Selection survives app restart and represents missing/uninstalled apps accurately.
- Record the actual identities of ChatGPT, Paseo, Comet and WeChat available on the test Phone.
- The store exposes one authoritative selection for Phone icons and the projected home.

**Verification:** persistence/catalog tests and Phone UI tests, including restart and missing-package cases. Actual launching/switching is EP-06; the Scale menu entry arrives with EP-08.
