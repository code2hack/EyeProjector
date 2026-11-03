# v0.4.2 — shared-key HID gestures

Manager: Manager-EyeProjector-v0.4.2

The Owner authorized implementation of [#165](https://github.com/code2hack/EyeProjector/issues/165), then explicitly required Tap/Scroll keys to be exclusive. Back/Home/Lock/Mode may share one key through distinct short/long/multi gestures. Preserve one binding per action row, per-device profiles, one active device and original input ownership.

One Worker owns the coupled codec/controller/recognizer/merger and meaningful tests; one independent Reviewer evaluates exact source/evidence. Both use fast mode off. The Manager owns SPEC/plan/issues, integration and SDK/devices. Preserve the existing Manager profile. No mutable peer imports, extra product guards or unrelated network/media work.

Timing follows the configured alternatives per key: immediate short when no longer click count competes, including short+long; short+double waits only that key's existing500ms window; double+triple similarly defers double. LONG remains800ms on release. Learning consumes input. Tap/Scroll exclusivity applies across actions and preserves their original contact/repeat behavior.

Acceptance is issue165 plus the explicit Owner raw-role decision in SPEC. Test real learning/store/controller/original-route/merger composition, migration, timing/cancellation and dedicated Tap/Scroll regressions; run affected Android/JVM/Main checks and small Phone fixture checks where available. Distinguish fixtures from physical/radio timing. Do not spend this patch on the previously unavailable Glasses network or camera103.

Integrate the independently reviewed candidate, verify the assembled patch, publish to main/checkpoint, retire participants/workspace and remove the exact Manager watchdog registration. No additional Owner publication gate is imposed.
