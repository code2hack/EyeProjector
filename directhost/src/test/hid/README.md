# Phone HID host composition scenarios

Run with JDK 17 and the pinned compiler dependencies already cached locally:

```sh
JAVA_HOME=/path/to/jdk17 python3 directhost/src/test/hid/run.py \
  --source-dir /path/to/repository \
  --output-dir /path/to/a/new/evidence-directory
```

One isolated compilation includes the complete actual `PhoneHidBindings`,
`PhoneHidController`, `PhoneHidRuntime`, `PhoneHidKeyService` and `PhoneHidPanel`
production sources, the actual store JUnit tests, and this fixture. The runner
does not copy their routing into test adapters. It snapshots the actual
HostActivity and extracts its four HID helpers plus complete `onResume`,
`onStop`, `onDestroy` and `onBackPressed` method bodies using a unique marker and
balanced-brace parser. Each block has a separate hash. The actual onCreate
`initializePhoneHid(this)` callsite is also uniquely required and hashed.

Those unchanged helpers and lifecycle methods compile in a generated Activity
with an explicit container and inert preceding bootstrap, observer and pairing
fields. The whole preceding HostActivity onCreate/product screen is outside
this fixture; its HID helper initialization is invoked by the generated boundary.
The complete service, runtime and panel are actual production code. One main
class runs the named scenarios, then another process runs the actual store
JUnit class from the same compiled jar; there is no second compilation.

Android Context/preferences, Activity/widgets, InputDevice/KeyEvent/InputManager,
Accessibility settings/service callbacks and the main clock/Handler are explicit
host doubles. Preferences are in memory and app-scoped service/settings lookups
are controlled. The service callback entrypoints are public in the host base
class to allow direct delivery to the actual overrides; this does not establish
Android filtering or lifecycle delivery. The actual runtime persistence executor
runs its actual write closure; completion is queued to an explicit host main
queue and drained with a bounded wait. The actual executor is shut down through
test reflection at suite exit to avoid retaining a worker JVM thread.

The protocol action/phase/edge and opaque route types are exact authorized444
seam doubles. The session facade has the exact `capturePhoneHidRoute`,
`submitPhoneHid` and `cancelPhoneHid` signatures. Capture records the original
DOWN once; submit and cancellation retain the returned opaque identity. No
shared protocol implementation, session/controller backend, Binder transport,
gesture injection or projection target selection is compiled by this suite.

Named scenarios cover capture ownership before matching-UP feedback; nested
newer completed capture and page change; normal pending-terminal feedback and
route construction; constructing-owner missing/changed/removal retirement;
newer completed shared TAP admission; independent Function ownership; deferred
failed persistence; reset ordering before an old cancellation callback completes
a newer assignment; actual Phone sticky row replacement, status counts/layout
and asynchronous save/readback; exact service eligibility, changed/missing/
retired release, held repeats and fresh reused attachment; unavailable-target
no-replay; cached settings observations; reset failure; and actual Host
open/Back/stop/resume/destruction helpers. Pure controller cases use queued
persistence and explicit captured route callbacks; composition cases use the
actual runtime/service/panel. These scopes are reported separately from the
actual store JUnit results and should not be summed as independent coverage.

Every named scenario runs even if another fails. The runner refuses a nonempty
evidence directory, snapshots each source/fixture before compilation, records
source/compiler/JDK hashes and exact commands, and retains failed attempts.
Compilation is bounded to 90 seconds, scenarios to 30 seconds and JUnit to
15 seconds. It invokes no Gradle, Git or network operation and uses no mutable
Gradle output classpath. Existing Phone scale/widget and Glasses HID suites
remain separate.

Host results cannot establish Android public API compatibility, accessibility
enablement or key filtering, actual input delivery, descriptor stability,
Bluetooth behavior, physical pointer/scroll/Function behavior, device output,
default suppression or assembled acceptance. A real SDK compilation is a
separate check. Device qualification remains open.
