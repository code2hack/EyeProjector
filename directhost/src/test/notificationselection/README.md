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

The saved63 pure helper/actor composition scenario constructs an already admitted
primitive loss obligation. It checks the exact imported helper under the real65
wrappers and actual actor. It does not sample/map native state, exercise native
descriptor transport or establish physical usability. The broader composed HID
suite retains its known HID27 counterexample separately. No suite here establishes
system notification delivery, foreground Android behavior, hardware wake, global
Home intent, native transport or full issue65 acceptance.
