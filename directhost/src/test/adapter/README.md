# Bootstrap adapter event regressions

Run after the ordinary Gradle host checks have provisioned the pinned Kotlin compiler, with JDK 17 configured:

```sh
python3 directhost/src/test/adapter/run.py --output-dir /path/to/private/test-output
```

This compiles the actual `HostBootstrap`, `HostForgetCompletion`, `BootstrapSessionFiles`, `SessionController` and `SessionAttemptStore` production sources alongside test-only Android/Shizuku/Binder doubles. It drives delayed construction/Stop, unavailable setup restoration, accepted binds without callbacks, late abandoned callbacks, mismatched/matching installed revisions and Forget completion. No production source is rewritten or copied into a mock implementation. `--source-dir` accepts an exact source snapshot to demonstrate that regressions fail on the reviewed base.

It also compiles the actual scale wire types and covers local scale replies before attachment, requested versus observed feedback, malformed/dead-service replies, Stop and fresh-session scale state. These add three scenarios to the six startup/recovery scenarios. Three paired-session cases cover epoch retention, cancellation during pending Start and correlated setup rejection. A thirteenth case drives terminal atomic-store failure through the actual main observer and verifies controlled retry. The harness compiles the matching layout/touch wire types explicitly. A snapshot used with `--source-dir` must expose the APIs exercised by that run.

Three projected-launch cases bring the suite to sixteen scenarios. They cover missing attachment, actual typed launch/display replies and fresh binding identity, malformed/dead-service replies, and Stop while a synchronous launch is blocked. Launch/query waits use a separate operation executor so they cannot starve the existing lifecycle executor. These cases compile the actual local `ProjectionDisplayState` codec as well.

Six R1 cases bring the suite to twenty-two. They exercise the actual controller's normal end/restart on the same live Binder, same-owner media replacement, capture before the first lifecycle hop, replacement after a client check, and the actual Home Activity's original intent/event/completion binding. They also cover Home's actual context arriving before the Phone status poll. Activity/Intent/view/controller boundaries are test doubles; Home Activity, HostBootstrap, SessionController and context/reply codecs are production sources. Server ownership is separately exercised through the actual Shizuku service/Bridge/Session harness.

The harness models API events and uses synthetic fixture bytes. It does not start a daemon, call Android Binder, validate Android filesystem permissions or prove physical teardown. The JVM lifecycle tests independently cover actual delayed worker cancellation; reserved-Phone evidence remains necessary. A result manifest records source and log hashes in the output directory.
