# v0.4.2 — shared-key HID gestures

Manager: Manager-EyeProjector-v0.4.2

The Owner authorized [#165](https://github.com/code2hack/EyeProjector/issues/165) and explicitly required Tap/Scroll keys to remain exclusive. Back/Home/Lock/Mode now share one physical key through different short/long/multi gestures. Exact-gesture reassignment moves only that collision; one binding per action row, per-device profiles and one active device remain.

Runtime waits only for unresolved click alternatives on that key. SINGLE alone or with LONG is immediate on short release. SINGLE+double and double+triple wait the existing 500 ms window only where needed. Expiry dispatches only an assigned exact count. LONG remains at least 800 ms and acts on release. Learning retains quiet-period discovery. Dedicated Tap hold/drag/multitap and Scroll 400/100 ms repeat behavior remain.

One Worker implemented the coupled store/controller/original route/typed local codec/recognizer/merger and tests; one independent Reviewer assessed every exact candidate. Both used default speed (FAST OFF). Manager owns SPEC, issue, integration, SDK and Phone-only verification. No new guard, simultaneous-device support, unrelated network/media work or Owner publication gate was added.

Issue165 is accepted for source, focused host, Android build/JVM, actual isolated Phone shared-profile persistence across app-process replacement, and named APK/private-state custody. Full checks and limits are in [the run record](../../runs/v0.4.2.md). Physical HID/radio timing remains unmeasured.

Shared-key maps require this version. The older APK rejects them on load while preserving bytes; an explicit assignment/reset in that older APK can overwrite them. Single-binding local edges retain their 39-byte encoding. Shared candidate data is Phone Binder-local only; LAN Wire remains 15 and native Rokid inputs are unchanged.

The Manager publishes the accepted assembled patch to main and tags v0.4.2, preserving source/evidence, retiring the issue participants/workspace, and removing only its watchdog registration. Existing explicit credential-retention decisions remain in force.
