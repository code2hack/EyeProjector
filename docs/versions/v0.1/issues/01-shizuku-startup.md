# EP-01 — Start and stop the Phone backend through Shizuku

**Canonical issue:** [#5](https://github.com/code2hack/EyeProjector/issues/5). **Blocked by:** None.

**SPEC:** [Phone setup and startup](../../../../SPEC.md#phone-setup-and-startup).

Replace the computer-command startup requirement with a Phone-controlled Shizuku path. Guide installation/start/authorization, then launch the existing pinned backend with its required shell identity and observe its actual readiness. Keep this slice usable with the existing private peer fixture before LAN pairing lands.

**Primary writes:** new host bootstrap adapter, `HostActivity.kt`, host manifest/build configuration and bootstrap tests. Coordinate media-loop edits with EP-03.

**Acceptance**

- An authorized user starts and stops the existing bridge from the Phone app after Shizuku setup, without a computer command line.
- Missing authorization, unavailable/dead Shizuku and backend startup failure show the actual state and an actionable recovery route.
- Repeated Start and Stop during startup produce one owned backend or a verified stopped outcome.
- The pinned server asset and session-file ownership remain attributable; stopping releases the owned process.
- Record reboot recovery behavior and the manual setup steps actually needed.

**Verification:** adapter lifecycle tests, affected host build/lint, then reserved-Phone startup/teardown evidence. Use the existing [startup research](../../../research/phone-app-startup.md); automatic reboot recovery remains outside the demonstrated claim.
