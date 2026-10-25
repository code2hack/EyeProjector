# Phone HID host composition scenarios

Run with JDK 17 and the pinned compiler dependencies already cached locally:

```sh
JAVA_HOME=/path/to/jdk17 python3 directhost/src/test/hid/run.py \
  --source-dir /path/to/repository \
  --output-dir /path/to/a/new/evidence-directory
```

One isolated compilation includes the complete actual `PhoneHidBindings`,
`PhoneHidController`, `PhoneHidRuntime`, `PhoneHidKeyService` and `PhoneHidPanel`
production sources, the feature-local activation ownership helpers, the actual store JUnit tests, and this fixture. The runner
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
seam doubles. The session facade records `capturePhoneHidRoute`, `submitPhoneHid` and
`cancelPhoneHid`, with explicit unavailable setup and scoped-value methods for
the current Runtime. Isolated routing cases select a named ready local port;
they do not select production native transport. Capture records the original
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

Released Function-route controls cover explicit reset/open/select/close,
connection loss, device removal and common cancellation after a valid physical
UP. They verify the original captured caller route is canceled once, including
UP during pending construction and cancellation reentry from the submit
callback. Actual service sequences verify successive Function DOWN/UP edges
add no intermediate cancellation, only the latest released route is retained,
and an older overlapping UP or old attachment removal cannot replace/cancel a
newer attachment's marker. This establishes caller-half cancel delivery to the
recording session seam only. The actual backend Function merger and its quiet
timer are absent, so no backend recognition cancellation or acceptance follows;
that requires the Manager's separate assembled composition checks.

Pass-through disposition controls queue an actual captured assignment, deliver
an unbound false DOWN before its persistence operation completes, then publish
the mapping between physical edges. Duplicate DOWN, held repeat and UP/CANCEL
must remain false for that original gesture; the next fresh actual bound press
alone may capture a route. Cold bound repeat/UP/CANCEL also remain false without
an observed handled DOWN, while admitted, captured and explicitly retired raw
releases remain consumed. Additional controller and actual service sequences
hold an unbound key across row selection, page open/close/reset, cancellation,
device changes/removal and a missing InputDevice. Their old raw edges cannot
start learning or output; a fresh real DOWN/UP can learn afterward. These cases
use the same queued persistence, service and caller seams described above;
they do not add backend or physical acceptance.

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


Issue63 Phone-local activation cases compile the actual generic
`PhoneActivationOwner`, `PhoneHidActivation` injection port and narrow controller/
service hooks. The production Runtime now selects its typed original native snapshot capture.
This isolated suite selects explicit recording/ready ports on that actual
controller; its facade supplies no native descriptor. The actual native Kotlin
region/lease classes compile on explicit JNI/SharedMemory/Parcel doubles to
close Runtime dependencies, but isolated cases do not establish their use.
Fixture targets stand in for `ProjectionContext`. The separate activation runner
with `--producer --transport` composes the actual Bootstrap, facade, Runtime,
KeyService, controller, native Kotlin reader/writer, Session and merger; its
Android/JNI/FD/Binder boundaries and evidence remain separately identified.
No activation persistence, retry scheduler, Phone backend owner, control schema,
Glasses receiver, physical wake or background Activity capability is supplied by
these seams.

The local owner cases exercise overlap, repeated externally driven recovery,
release-before-retry, cancellation before admission, canceled-old-attempt release,
observer reentry, one-shot exact-target readiness and presentation loss without
allocating another backend. HID cases exercise bound and unassigned wake presses,
readiness changes inside request callbacks, same-key old-wake/new-normal and
old-normal/new-wake ordering, constructing/released Function route cancellation,
Stop/page/device/service retirement, missing/changed device metadata, request
failure, sticky learning while disconnected and unchanged ready pass-through.
The actual service attachment cases reuse the existing external/nonvirtual/native
exclusions and do not treat metadata change as a new attachment.


Correction77 routes eligible attachment through the same one captured disposition
as fresh keys. Production USABLE attachment is an idempotent join without a
latest-owner request; NONE/UNKNOWN retain the captured scoped wake/setup path.
The existing scoped helper represents these HID observations as HID_PRESS while
the caller port retains HID_ATTACHMENT. Attachment creates no raw gesture or
target edge. Its actual Runtime/KeyService/Session/engine proof is in the
activation runner's `--producer --transport` suite; `--attachment-only` isolates
that subset and `--source-root` binds the preserved parent production snapshot.
The isolated HID port cases here retain separate caller/gesture/learning scope.
