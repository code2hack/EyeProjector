# EP-19 — Verify the assembled v0.1 workflows with the four target apps

**Canonical issue:** [#23](https://github.com/code2hack/EyeProjector/issues/23). **Blocked by:** [EP-08](08-per-app-scale.md), [EP-09](09-pointer-settings.md), [EP-13](13-hid-bindings.md), [EP-15](15-phone-widget.md), [EP-16](16-phone-ime.md), [EP-17](17-video-stability.md), [EP-18](18-bluetooth-qualification.md).

**SPEC:** [Acceptance expectations](../../../../SPEC.md#acceptance-expectations).

Verify the assembled version branch on the actual Phone/Glasses/HID combination. Bind the evidence to one integrated candidate and exercise complete user workflows, including failures and reconnect. This issue closes verification gaps between independently accepted features.

**Primary writes:** version evidence/compatibility report and focused reproduction fixtures. Assign substantive feature defects back to their owning issue or a precise follow-up.

**Acceptance**

- ChatGPT, Paseo, Comet and WeChat have explicit installation/version and workflow results: launch/switch/Home, native input, text entry, scale and relevant media behavior.
- Shared Wi-Fi/hotspot pairing, remembered reconnect and Forget work through the specified panels.
- Both widget actions, Glasses exits, HID bindings and saved settings work together on the integrated build.
- Declared sustained/no-input runs cover required unlocked/locked Awake cases before/after panel OFF; record actual restoration and failure cleanup.
- EyeProjector black/light UI and each app's native dark presentation are physically readable, with remaining limitations documented.
- The relevant [RUNBOOK build/test/lint checks](../../../../RUNBOOK.md#build-and-automated-checks) pass on this candidate; exact source/APK/test bindings and independent review accompany the report.

**Verification:** execute the assembled workflow matrix in reserved device windows, retain raw evidence privately and publish a concise source/artifact-bound report. Reuse unchanged issue evidence where it applies to this candidate and rerun affected behavior after integration changes.

Virtual-camera implementation success is outside core acceptance. A finite successful run establishes its measured interval, not unlimited uptime.
