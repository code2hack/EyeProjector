# EP-12 — Resolve function-button sequences into contextual navigation

**Canonical issue:** [#16](https://github.com/code2hack/EyeProjector/issues/16). **Blocked by:** [EP-06](06-projection-home.md), [EP-10](10-native-taps-scroll.md).

**SPEC:** [Glasses-native interaction](../../../../SPEC.md#glasses-native-interaction).

Consume the relevant Rokid function-button reports and classify each complete single/double/triple/long sequence once. Use the same semantic actions later exposed by the HID Function binding.

**Primary writes:** function-button receiver/classifier, local navigation routes and assigned `ReceiverActivity.kt` integration.

**Acceptance**

- In projection, single returns to the chosen-app home, double sends Back, and triple disconnects to the Glasses control panel.
- In local panels/settings, single returns to the main panel, double performs local Back, and triple exits to Rokid home.
- Long press performs no action; a double/triple sequence does not first execute constituent Home/Back actions.
- Reclaimed gestures suppress competing system behavior, including the triple-click pairing action.
- Exit/focus changes release interception and pending sequence state appropriately.

**Verification:** deterministic sequence/duplicate/timing tests, local navigation instrumentation and physical button/system-effect observation. The existing `Navigation.HOME` enum is reusable; actual routing and firmware broadcast ordering require verification. See [input mapping](../../../research/glasses-interaction-mapping.md).
