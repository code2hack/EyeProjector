# Notification metadata checks

This isolated suite compiles the actual `NotificationEvents.kt`, existing pure
`AppNotificationStore.kt`, and standard JUnit tests with pinned, already cached
Kotlin 2.2.21/JUnit 4.13.2 jars and JDK 17. It does not invoke Gradle, provision an
SDK, download dependencies, use Android doubles, read device notifications, or
request activation/launch. Run only within an assigned host-check allocation:

```sh
python3 directhost/src/test/notifications/run.py --output-dir /absolute/new/run-directory
```

Each closed run retains exact compiler/test argv and its hash, source snapshots
and hashes, cached dependency hashes, Java executable hash, compiler/test logs,
named test outcomes, artifact hash, exit codes and timestamps. Compilation is
bounded to 120 seconds and test execution to 30 seconds. A failed run is retained
in its own directory. Standard Android JUnit discovery can also run the test
class later under a separately allocated build window.

The classifier's API has no side effects. Its caller serializes transitions and
supplies chosen membership; current On/Off routing stays with the eventual
activation owner. Off observations still enter metadata history. `connected`
creates a new ingress epoch, and `disconnected` retires metadata ingress only;
neither method models cancellation of an accepted app-scoped selection.

Call `beginBaseline(epoch)` before querying the active snapshot, and complete
that request with `NotificationSnapshot.Available(records)` or `Unavailable`.
An empty successful snapshot differs from a failed/unknown snapshot. Only
memberships still owned by that request are seeded. New/re-added memberships
need their own baseline without resetting unchanged memberships. Posts received
before readiness are consumed without a catch-up arrival. Snapshot completion
merges observed versions. It supersedes a pre-request callback's current version
when no post for that key occurred during the query; otherwise the callback
version is retained. Matching current removal during collection prevents any
known version of that retired lifecycle from being restored by the snapshot.
Snapshot absence is not synthesized into a received removal callback. No
baseline result is a posted-callback result.

Within an active record lifecycle, equality of a previously observed postTime
is duplicate/replay evidence. A distinct unseen postTime is an updated/reposted
record even when wall-clock time decreases. A replay of an older seen version
cannot replace the current observed version. Matching current removal retires
that lifecycle; known noncurrent and unknown-version removals do not erase its
current record. Removal followed by a post starts a new *observed* lifecycle.
Only chosen metadata is retained, until removal, membership removal, or ingress
retirement. There is no cap, expiry, output-job queue or persisted record cache.

All semantic message novelty remains **UNKNOWN**. This suite deliberately shows
limits: identical same-millisecond posts cannot be separated; a late identical
removal after same-time remove/repost has ABA ambiguity; an unseen older queued
version can resemble a fresh repost after clock reversal; and a real arrival
inside the snapshot window can already be absorbed into the no-catch-up
baseline. There is no atomic snapshot/stream cursor in the permitted metadata.

Named scenarios exercise these limits, ingress replacement, partial membership
snapshots, delayed/repeated versions, stale/unmatched removal, failed-baseline
recovery, Off then On, and provenance. Synthetic opaque keys labeled ongoing,
silent or grouped establish only that this metadata model has no type filter;
they do not establish Android flag handling, service delivery or physical wake.
No test result proves listener permission/setup, shared activation H, exact
launch commitment, actual foreground app or Phone/Glasses behavior.
