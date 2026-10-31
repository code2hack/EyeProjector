# Anchor cross-feature integration

Run from this issue worktree:

```sh
python3 directhost/src/test/anchor-integration/run.py \
  --dependency-manifest /path/to/manager-frozen-dependencies/manifest.json \
  --output-dir /path/to/private-evidence
```

The runner uses only the immutable, hash-checked compiler and accepted dependency JARs in the
Manager manifest. It compiles current production sources with the resulting JAR first on the
runtime classpath. It performs no Gradle, SDK, device, network or installation operation.

The 33 scenarios compose the real Function recognizer and complete Activity input/registration/
feedback/navigation/lifecycle method bodies, Glasses ProjectionSession and ReceiverSessionOwner,
wire DTOs, Phone Session and Binder adapters, the single AnchorContactOwner, ordinary input
merger/dispatcher, reflected registration provider, app FIFO/store, production reader and resolver.
The existing accepted launch and Hold scenario files supply fixture helpers; their other `main`
functions are compiled but are not executed or counted. No alternative contact algorithm runs.

| Scenarios | Observable contract |
| --- | --- |
| Explicit Click/Hold/Cancel | OPEN precedes its exact chooser fact; only an explicit choice saves; Cancel preserves the complete setting; saved anchors execute away from the hover point; duplicate completed results stay silent. |
| Hold with Tap/Scroll | Ordinary input cancels the original contact before its own output; old held feedback cannot relight the indicator. |
| IME and app relayout | Other-package IME windows do not become app targets; unrelated IME lifetime metadata preserves an app contact; current app relayout cancels it and later activation resolves current bounds. |
| Modal/ambiguous and Activity-window replacement | Chooser completion uses a fresh production read; lost, ambiguous or replaced windows do not save the old offer; explicit window retirement cancels the original contact/registration. |
| Home, settings, Back and Lock | Actual Activity/Session/ProjectedAppLaunch routes join app → projected grid → control panel, HID settings → panel and panel → launcher; chooser Back is local; Lock invokes the named local shell power boundary. |
| Legacy and managed scale | Real scale request paths cancel the original Hold and publish PENDING rather than claiming an observed layout. |
| Clear/replacement with delayed callbacks | A committed setting alone is not retirement acknowledgment; a subsequent activation retires the original through Binder without falling through to a fresh contact. |
| Rejected/failed and blocked cleanup | Cancellation retains the original contact identity; local indicator hiding never implies successful UP or an application acknowledgment; blocked cancellation completes on its original turn. |
| Binder replacement and Stop | Old contacts retire; a successor starts fresh; old held feedback cannot mutate the replacement. |
| Current and stale failure feedback | Current no-anchor, pre-offer registration failure and modal-target loss still render; output-submission statuses remain silent. Retired panel/settings/layout/pause/incarnation/input/owner/mode/geometry contexts cannot show stale failure notices. Head motion adds no new feedback restriction. |

Each run retains source snapshots and hashes, generated complete Activity bodies, manifest and
artifact hashes, compile/test commands and logs, per-scenario bidirectional wire frames and
observations, overlapping older classes, JVM class-loading evidence for 15 critical current-source
classes, and `javap` ABI output when available. Failures return a nonzero exit; scenarios continue
independently so one failing assertion does not hide the rest of the matrix.

Android accessibility-service connection/events, public UiAccess handles, MotionTarget delivery,
Binder handles, TLS, video presentation and widgets are explicit host boundaries. Full Android
ReceiverActivity compilation, view hit testing, real accessibility grants, actual event delivery,
physical contacts/power and application effects are not established. The source Phone Session
leaves `foregroundActivity` unset; the Activity scenario therefore exercises an observed window
replacement, without claiming that a changed component string alone is detected. Window metadata
is injected at the Android-service boundary, not observed on a device.

The Phone contact owner has not changed in this issue. The production fix only filters
AnchorMessage.Feedback in the retained Glasses owner using the existing Function-context checks
and original owner/incarnation/input revision/mode. Registration Offer/OPEN/Result retain their
own transition contract. Cancellation is local retirement, framework submission is not an app
action acknowledgment, and these checks do not implement or prove the pending native two-finger
adapter. The separate accepted baseline suite is not added to the 33-scenario count.
