# Notification selection composition

Run with JDK 17 and the pinned Kotlin/JUnit dependencies already cached locally:

```sh
python3 directhost/src/test/notificationselection/run.py --output-dir /absolute/new/run-directory
```

The entire cached check is bounded to 115 seconds, including input capture;
compilation receives at most 85 seconds, scenario execution at most 25 seconds,
and the JDK version query at most 5 seconds within that same overall budget.
Each compiler/JVM invocation has a new owned process group, with timeout cleanup
and terminal closure recorded. Every run uses a new directory and retains exact
source/runner snapshots and modes, dependency/JDK hashes, argv, artifact, logs
and exit codes. Source or runner changes invalidate current-input attribution.
Failed and superseded attempts remain
separate evidence; their overlapping pass lines are never summed.

This compiles the actual facade, accepted activation engine/producer, Bootstrap,
coordinator, metadata runtime/classifier, selection owner, canonical chosen-app
and notification preference adapters together. It also runs the existing
metadata/runtime/chosen-store/coordinator JUnit cases. Android, Shizuku, Binder,
JSON and TLS are explicit host boundaries. The synthetic flat JSON fixture is
not Android parsing or disk-durability evidence.

The combined facade also compiles the native snapshot Kotlin region, word and
input-lease sources with the same explicit Parcel/SharedMemory/JNI boundaries
used by the HID transport host suite. This source closure does not load JNI,
transfer a real descriptor or establish native/device behavior.

Three combined cases hold the actual display queue, publish NONE through the
production Kotlin region and take one sample through the actual attachment
caller. They check rejection of the older notification Ready, the original
G/D/permit across fresh readiness, Off/On and Stop. The first path launches once
after a fresh same-D report; the retired paths produce no launch. These cases
use the JNI/SharedMemory boundaries above and cover the second queue only.

The normal route reaches the actual typed final claim and fixed original Binder
call through both actual queues. Held display and old scale work exercise those
queues separately. Canonical held reads/writes, deferred observers, write
failures, policy/listener reentry, first-receipt Stop, explicit choice and
Off/remove/readd exercise original receipt and permit ownership. Committed
Binder/decode/feedback failures retain commitment and cannot resubmit G.

`ControlledPort` replaces only observation delivery/registration and submission
settlement for adversarial return/close/throw cases. Admission and complete Ready
facts still come from the actual facade/producer. Its callbacks model inline,
late and held delivery, not Android scheduling. The actual producer loses and
restores authority to supply fresh same-D Ready facts. A fresh fact can rearm a
settled uncommitted dispatch fault with the same G/D/permit; it is not proof that
an unrelated failed executor was repaired. Registration/close without established
detachment and submit without actual settlement remain explicitly unresolved.

Cardinality is scoped to each original G: one attempted stream, at most one
unresolved arm and one unsettled submission. Retired G retains its exact stream,
arm and submission obligations until their own return, confirmed close and
actual settlement allow release. An old return/close/callback cannot operate on
the new G or overwrite its outcome. A no-handle exception or failed close is not
confirmation of cleanup, including after a late callback. The retired set never
blocks a fresh G. Deliberately failing/unknown ports cannot prove global physical
registration nonoverlap across different G; the ordinary concrete facade posts
observations and has no intentional throwing registration/close boundary. No
global block, cleanup queue, retry timer, expiry or cap is supplied by these tests.

The ten correction65 cases also exercise original local removal order before
its selection effect. One actual runtime case reenters after removal1 has been
classified but before its effect, admitting same-record G2; G2 survives old1 and
ordinary later matching removal3 retires it. A separate chosen-read reproduction
reenters before classification: its old removal consumes the classifier record,
so the later removal remains REMOVAL_UNMATCHED while G2 survives. The classifier
is unchanged. These explicit modeled boundaries do not establish Android read
reentry reachability, semantic message novelty or native same-key/time identity.
An escaped old submit cannot repopulate the outcome cleared by a newer G; later
old settlement remains scoped to its own original work.
Use `--correction65-only` for the isolated ten-case run; the default also renews
the full composition and existing JUnit cases. Parent/current comparisons retain
all input hashes and qualify their actual source/fixture differences separately.

The saved63 pure helper/actor composition scenario constructs an already admitted
primitive loss obligation. It checks the exact imported helper under the real65
wrappers and actual actor. It does not sample/map native state, exercise native
descriptor transport or establish physical usability. The broader composed HID
suite retains its known HID27 counterexample separately. No suite here establishes
system notification delivery, foreground Android behavior, hardware wake, global
Home intent, native transport or full issue65 acceptance.

The observation219 cases exercise the actual policy/facade/owner/runtime trace
with observation enabled and disabled. Seven direct facade cases obtain snapshots
through actual receipt/prepare calls and canonical reads/mutations, then verify the
first evaluated rejection and every later NOT_EVALUATED predicate. The direct
unchosen case is an adversarial admission boundary; ordinary runtime extraction
does not admit an already unchosen package. No Snapshot/revision/private policy
field is fabricated to make an install succeed. A separate fake-null port case
does not invoke installation and must leave its reason unknown. A deliberately
lossy return after actual admission retains its observed installed G while leaving
the returned handle unknown; it must not fabricate DEMAND publication.

Other cases cover original G/D/DEMAND correlation, caught eligibility exceptions,
exact primary non-Exception propagation with observation disabled/enabled,
missing tickets, filtered/old receipts, pre-read Stop, explicit-choice read reentry,
ingress replacement, scheduler/callback Throwable, inline callbacks outside the
actual gate/fastLock, and closed/late-original recipients. A post adapter returning
Unit while retaining no callback is deliberately modeled; returned scheduling
never establishes enqueue acceptance or delivery. Disabled cases assert null
diagnostic storage and unchanged functional outcomes, not a heap-allocation
profiler result. Immutable snapshots and equal-but-distinct ingress copies retain
the original object correlation boundary. These tests do not identify actual198's
cause, demonstrate Android scheduling/reentry, or establish device behavior.

Use `--observation219-only` for those affected cases. The default renews them plus
the existing composition/JUnit cases; the focused and full counts overlap and
must not be summed. The existing `--correction65-only` selector remains available.
