# Original pipeline observation checks

This allocated isolated suite compiles the actual notification owner, policy, carrier,
observation model, runtime, canonical adapters, facade, Bootstrap and scale coordinator.
It reuses existing producer Android/Shizuku/Binder/JSON/TLS/native boundary fixtures.
Adversarial delivery/settlement cases control that boundary while admission and complete
Ready facts come from the actual facade. Two real app queues are additionally held in one
composition, then released to the modeled Binder with exact original carrier identity.
These are JVM source checks, not Android binding, native transport, notification delivery,
physical wake or device foreground proof. Existing suites are compiled for fixture closure
only and their scenario/JUnit main methods are not executed or counted here.

Run only with the granted cached host allocation:

```sh
python3 directhost/src/test/notificationprobe/pipeline/run.py --output-dir /absolute/new/run
```

The complete cached check is bounded below 90 seconds (compiler 55, scenarios 20, Java version 5).
It retains exact argv, source/dependency/runner/Java snapshots or hashes, logs, closed process
results and artifact hashes. Failed runs remain in their original directories. Source changes
while running invalidate unchanged-source attribution. Observer callbacks can be inline at the
explicit delivery boundary; this does not claim Android Handler.post executes inline.

No-subscriber checks account for the existing policy callback post separately and reject any
extra observer scheduling. The second-queue hold uses a delegating executor acceptance observer;
it preserves the actual executor/queue and typed claim, rather than assuming a JDK wrapper type.

The passive observation219 checks compare disabled/enabled submission, original selection/handle,
settlement and retirement results. They account for every added post using the existing pipeline
delivery count; tracing adds no separate observer queue. Existing delivery cases also assert
immutable counter snapshots, a caught producer callback `Error`, a throwing scheduler versus a
Unit-returning boundary that never executes work, and inactive captured-recipient suppression.
Producer counters do not stand in for helper callback entry or report append. A successful return
from the Unit post adapter does not establish Android Handler enqueue acceptance or delivery.

The companion actual-source `notificationselection` suite owns the prepare-read exception and
short-circuit package/revision/access/approval/chosen/On rejection matrix, null facade, old/missing
ticket, reentry, and ingress-retirement cases. This pipeline suite does not copy that matrix or
count those scenarios as executed. These host checks establish neither actual198's cause nor
Android notification, physical activation, foreground or full issue acceptance.
