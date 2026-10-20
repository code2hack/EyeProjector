# Native pad taps and scroll — issue #14

Worker: Worker-EyeProjector-14. This change replaces touchpad double-tap Back and triple-tap Disconnect with ordinary touches. Function-button navigation belongs to its own issue.

## Reports and actions

`NOTIFICATION83` is a short marker, **not sustained contact/release**. The retained triple has pulses `(17048181,17048204)`, `(17048371,17048392)`, `(17048524,17048548)` ms and no tap summary. The two retained long holds also begin with approximately 22 ms markers. Their AI classifications arrive approximately 815 ms after marker DOWN; Activity key186 release was not delivered. See [the input mapping](../research/glasses-interaction-mapping.md) and [physical long-touch evidence](../provenance/rokid-long-touch-2026-10-20.md).

The recognizer keeps marker timings and the pointer position captured at DOWN. ENTER/BACK summaries never add touches once marker reports are present. A swipe replaces its own marker, leaving earlier taps intact; documented DOWN/UP companion keys are consumed. Legacy alternate key291/292/293 support is retained without claiming fresh physical measurement of those aliases.

Every ambiguous marker replays at a constant offset of **1000 ms after marker UP**, preserving the captured DOWN/UP timestamps for local Views and inter-tap intervals for remote submission. A delayed long classification removes its marker without inventing physical release. A classification can also arrive before marker UP. True `HELD_CONTACT` DOWN/MOVE/UP/CANCEL actions pass immediately and separately; notification pulses never enter that interface. Issue #15 must supply and implement the real held-contact source and output. This issue does not establish physical long press/drag delivery.

This delay is a **provisional tradeoff requiring disposition**, not accepted ergonomics or a universal firmware bound. The measured long classification rules out the inherited 300 ms pulse deadline. Positive ENTER/BACK classifications can arrive earlier, but they are aggregate reports, cannot identify every constituent time, and are absent from the captured triple. Dispatching markers immediately gives a preliminary tap for the measured hold; using only summaries loses multi-tap counts; selectively accelerating summaries changes the relative timing of adjacent unclassified markers. Faithful early contact/release would avoid this ambiguity, but is not available through the measured Activity callbacks. Device testing must quantify the delay and investigate additional supported channels. A later-than-1000 ms classification remains a known limitation of the provisional policy.

Summary-only fallback reconstructs one/two touches with explicitly **inferred** constituent timing. It reconciles a delayed pair with recent single reports over the same 1000 ms window, including singles already replayed. Closely spaced independent aggregate reports cannot always be distinguished from related summaries without marker identity. Alternate swipe-key families within the documented 500 ms companion interval are likewise treated as related reports, even if a newer marker is pending. These fallback assumptions require physical qualification; they are not claims that physical edges were observed. Current-firmware marker traces are the primary replay evidence.

## Dispatch and cancellation

Local actions traverse Android's touch/generic-motion View dispatch, including nested scrolling, instead of calling `performClick()` directly. Remote actions retain their pointer target and fitted geometry. `OrderedControlDispatcher` accepts a rapid mixed burst behind a blocked write, preserves order and inter-action spacing, and distinguishes accepted, written, canceled, failed and rejected work. Written means the TLS write returned; no Phone injection acknowledgment is implied. The existing session write watchdog remains responsible for blocked network writes.

Focus loss, unavailable pointer tracking, geometry changes and Disconnect cancel pending normalization and queued controls. Already-started writes complete or fail; cancellation cannot retract bytes already written. Head-driven scroll has its own queue category, so returning aim or disabling head scrolling removes queued head scroll without removing queued taps/native swipes.

Receiver input telemetry reports normalization sample count/mean/max delay. Session telemetry reports queue counts and submission-to-write mean/max delay. These separate the local normalization and control-write stages from physical end-to-end latency.

## Host checks

Use JDK17/SDK35 and the [portable runbook settings](../../RUNBOOK.md#build-and-automated-checks):

```sh
timeout --foreground 20m ./gradlew --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  :directrg:assembleDebug :directrg:assembleDebugAndroidTest \
  :directrg:testDebugUnitTest :directrg:lintDebug \
  :directhost:assembleDebug :directhost:testDebugUnitTest :directhost:lintDebug \
  :protocol:testDebugUnitTest :protocol:lintDebug
```

`PadGestureRecognizerTest` includes exact captured marker/long-touch times, one/two/four/six taps, duplicate summaries, swipe overlap, missing UP, classification before UP, markerless ordering, explicit held cancellation and callback-driven context changes. `OrderedControlDispatcherTest` covers mixed bursts, pacing, write failure and independent head-scroll cancellation. `ProjectionInputTest` exercises the actual `ProjectionSession` submission and Wire serialization with a controlled blocked/failing output stream; it is host evidence, not physical input injection.

## Device checks still required

All operations require the Manager's recorded Phone/Glasses reservation and route. Preserve installed experimental packages/private state.

- Install the exact built APKs and the Receiver instrumentation APK using the runbook. Record installed hashes and device identities.
- Run `operation=local-input-replay` with the existing instrumentation runner and a unique `runId`. This synthetic on-device check exercises an Android Button/GestureDetector and nested ListView/ScrollView through `LocalPointerDispatcher`. Its report explicitly says `physicalPadPass=false`.
- Project `directhost.InputFixtureActivity` on the authenticated current virtual display. Its native button records click count, native double-tap count and target DOWN/UP uptimes in `input-fixture.json`. Its nested list and outer scroll retain separate positions.
- With the Owner wearing the Glasses, capture actual native reports and input telemetry for single, double, triple and several quick taps; forward/backward swipes over the nested and outer widgets; a tap followed rapidly by a swipe; a long hold; focus/geometry/Disconnect/reconnect boundaries. Repeat local controls as applicable.
- Compare counts, native widget classifications, ordering and relative times. Quantify marker-to-submission and submission-to-write delay from their respective device clocks. Do not subtract unrelated Phone/Glasses uptimes to claim physical end-to-end latency. Record Owner observations separately.

Host checks, on-device synthetic replay and genuine pad evidence are separate results. No physical success is claimed by this document.
