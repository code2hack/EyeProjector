# Notification selection composition

Run with JDK 17 and the pinned Kotlin/JUnit dependencies already cached locally:

```sh
python3 directhost/src/test/notificationselection/run.py --output-dir /absolute/new/run-directory
```

Compilation is bounded to 90 seconds and execution to 60 seconds. Every run
uses a new directory and retains exact compiler inputs, dependency hashes,
commands, artifact, logs and exit codes. Failed and superseded attempts remain
separate evidence; their overlapping pass lines are never summed.

This compiles the actual facade, accepted activation engine/producer, Bootstrap,
coordinator, metadata runtime/classifier, selection owner, canonical chosen-app
and notification preference adapters together. It also runs the existing
metadata/runtime/chosen-store/coordinator JUnit cases. Android, Shizuku, Binder,
JSON and TLS are explicit host boundaries. The synthetic flat JSON fixture is
not Android parsing or disk-durability evidence.

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
