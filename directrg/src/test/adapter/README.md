# Continuous-input adapter event regressions

Run after the ordinary Gradle host checks provision Kotlin 2.2.21, with JDK 17 configured:

```sh
python3 directrg/src/test/adapter/run.py --output-dir /path/to/private/adapter-output
```

The runner uses the Gradle dependency cache from `GRADLE_USER_HOME`, or `~/.gradle` by default. It compiles the actual `PointerOverlay`, `HeadPointerModel`, `HeadOrientation`, `NativePadContact`, `PadGestureRecognizer`, `HeldPointerDispatcher`, and touch protocol sources with small test doubles for Android scheduling, raw sensor acquisition, input device events, and rendering. Compilation has a 45 second test budget and scenario execution has a 15 second budget.

The held pointer supplier, native contact admission supplier, and `onPositionChanged` lambda are extracted from the supplied production `ReceiverActivity` and compiled into `ActivityInputWiring.kt` in the external output directory. There is no good/bad wiring selector or replacement implementation of those lambdas. The observer is wrapped only in a test failure bound: recursive observation fails at depth eight before a JVM stack overflow. No observer is skipped, and no product guard is introduced.

Six scenarios cover held head publication and frame movement, admission-time final coordinates on synthetic pad MOVE/UP without a display frame, stationary release, delayed expiry and cancellation without replay, registered input-device removal, and cancellation of the captured route before a context switch followed by a fresh contact on the new route. The final UP coordinate must reflect admission-time model advancement beyond the prior MOVE coordinate while smoothing is still settling.

To demonstrate a source regression against an exact repository-root snapshot, including its matching `directrg` and `protocol` production sources:

```sh
python3 directrg/src/test/adapter/run.py \
  --source-dir /path/to/exact/repository-snapshot \
  --output-dir /path/to/private/baseline-output
```

The reviewed `ce19e77` wiring fails with a nonzero exit when a held contact reaches head publication or synthetic pad UP admission. The corrected observer supplier passes the same scenarios. Snapshot provenance is recorded only when `.git` exists at that snapshot root; an archive is identified by its exact source hashes without borrowing another worktree's HEAD.

`source-manifest.json` records production, suite, generated wiring, and compiler hashes. `result.json` records command exits and source/log/artifact hashes; `result.sha256` identifies that result. The examples place generated outputs and evidence in an explicit private directory outside the source snapshot. The runner uses `JAVA_HOME/bin/java` when configured, otherwise `java` from `PATH`, and logs that JVM's version.

This is a host dataflow test with Android API doubles and modeled local/projected destination sinks. It does not execute the complete `ReceiverActivity`, Android event dispatch, real sensors, transport, firmware, or physical hold/release/drag behavior. Device and assembled-version evidence remain separate.
