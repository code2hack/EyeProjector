# EP-09 — Persist X/Y pointer sensitivity and head-scroll control

**Canonical issue:** [#13](https://github.com/code2hack/EyeProjector/issues/13). **Blocked by:** None.

**SPEC:** [Glasses-native interaction](../../../../SPEC.md#glasses-native-interaction).

Split the current scalar pointer gain into independent X/Y settings. Retain the stepped numeric design and scroll-speed control, and add the head-motion scrolling toggle in Glasses control-panel area 2.

**Primary writes:** `LocalInputSettings.kt`, `HeadPointerModel.kt`, `PointerOverlay.kt`, local settings component and tests.

**Acceptance**

- Changing X changes horizontal sensitivity independently of Y, and vice versa.
- Existing scalar preferences migrate to both axes; gains, scroll speed and toggle persist.
- Turning head scrolling off ends an active head-driven scroll promptly while pointer movement and pad scrolling remain usable.
- Settings remain operable through the local pointer and use black/light styling.
- Invalid/stale sensor data and focus changes retain the existing cancellation behavior.

**Verification:** axis, persistence/migration and scroll-toggle tests plus an on-Glasses settings check.
