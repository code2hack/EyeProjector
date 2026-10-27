# Notification listener focused checks

This isolated suite checks the system-bound `PhoneNotificationListener`,
application-context metadata/status runtime, ordinary Settings helper and panel.
Its selection/facade/preferences constructor endpoints are explicitly inactive
boundaries. The production panel mount and selection/launch wiring are separate
from this suite; `notificationselection/run.py` compiles and drives their actual
non-UI composition. The accepted classifier and its 29 tests are unchanged.

With the Manager's isolated host allocation:

```sh
python3 directhost/src/test/notificationlistener/run.py --output-dir /absolute/new/run-directory
```

The runner compiles actual listener/runtime/helper/panel/classifier sources,
the existing metadata tests, new runtime tests and adapter scenarios against
explicit framework doubles. It uses already cached Kotlin/JUnit dependencies,
with no Gradle/SDK/network/device invocation. Compilation is bounded to 120
seconds and execution to 30 seconds. Each closed run retains argv/hash, source
snapshots/hashes, dependency/Java/artifact/log hashes and named outcomes.
Manifest and resource bytes are also bound; the service declaration is checked.

The new runtime tests are also normal Phone JUnit tests. Android-specific host
adapter scenarios and doubles are isolated from normal Android source sets.
The doubles model NLS objects, approval, Settings dispatch, queued main delivery
and widgets. The canonical ChosenApps read/observe API is a boundary double;
this suite does not test its Android SharedPreferences/JSON implementation.
Generated resource constants/text model the actual new resource XML. No copied
notification state algorithm substitutes for the production runtime/classifier.

Callbacks classify on actual receipt before a new app queue. Package is read
first; only chosen records require opaque key and platform postTime extraction.
Each result retains captured ingress plus local membership scope. The project
does not inspect notification contents, flags, images, styles or content intents
or log raw keys/tags/platform objects. Android still supplies and internally
handles notification payload objects; no contrary claim is made.

The current chosen list is read at receipt even if a queued preference observer
lags. An entirely unobserved remove/re-add cannot be reconstructed from a
current-list API. First-received native events across a same-instance reconnect
likewise have no platform epoch token. Local scope is captured app ownership,
not a claim of universal native provenance or semantic new-message detection.
Off is not an ingress filter: all chosen metadata stays consumed, with policy
and accepted app selection belonging to later integration.

Baseline request/service/ingress identities are captured before the background
query. Only minimal records for the request's chosen packages return to main.
Obsolete completion cannot seed replacement membership/ingress or clear a
replacement query. Membership additions during an in-flight query retain their
named external query trigger. Connect, new membership or explicit refresh may
query awaiting/unavailable baseline state; failure/status publication alone
does not create a retry loop, timer, cap or expiry.

Observable null/exception/scheduling failure becomes Unavailable. A returned
array, including empty, is an **observed public return**. Public Android wrappers
can mask internal unbound/RPC/parcel failures as empty arrays, so OBSERVED or
NotificationSnapshot.Available does not prove complete successful platform
query. Access approval, actual callback connection and baseline observation are
separate facts. No hidden/reflection API or notification-type filter is used.
See [Android NLS API](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
and [fixed Android15 public implementation](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-15.0.0_r1/core/java/android/service/notification/NotificationListenerService.java).

Disconnect/unbind/destroy retires only that original metadata ingress. Old
teardown cannot disconnect a replacement. UI close releases its observation;
it does not retire the app owner or listener. A future accepted selection G is
not represented/canceled by this runtime. Across a gap, baseline absence or
equal metadata does not prove matching removal/continuity. Restart establishes
a fresh baseline without reconstructing target work. Caller36 semantic,
baseline-relative, native-incarnation and same-time ambiguity limits remain.

Tests cover held query/receipt/membership races, remove/re-add, late instance
completion/teardown, unavailable versus returned data, no failure query loop,
reentrant event/status publication, registration callback-before-return cleanup,
inline snapshot completion, scheduling failure, access/status independence,
metadata-only extraction, Settings detail/flattened component/fallback/both-fail
and panel closure. Synthetic objects and key labels are not actual framework
notification flag, system binding, OEM lifecycle, installed UI or physical
notification/wake results. A separately allocated Android build and later
reserved device verification remain necessary.
