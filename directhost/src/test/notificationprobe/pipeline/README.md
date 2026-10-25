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
