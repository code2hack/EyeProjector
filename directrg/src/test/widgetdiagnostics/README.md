# Widget diagnostic failure-path host adapter

This harness compiles the supplied **actual** `NativeInputWidgetChecks`,
`HeldInputWidgetChecks`, `LocalPointerDispatcher`, `HeldPointerDispatcher`,
`PadGestureRecognizer`, `Touch`, `Wire`, and (when present)
`WidgetCheckDiagnostics` source. It extracts the actual `local-input-replay`
operation body and terminal `catch (error: Exception)` body from
`ProjectionInstrumentation`. It does not duplicate those report-attachment or
catch implementations.

Run with JDK17 and the compiler artifacts provisioned by the existing Gradle
checks. Compiler dependency versions match the existing directrg adapter.
All output paths are caller supplied; external evidence paths are convenient:

```sh
python3 directrg/src/test/widgetdiagnostics/run.py --source-dir /exact/repository-root/snapshot \
  --output-dir /private/evidence/widget-diagnostic-host-run
```

The runner records source blocks, exact source hashes, optional actual Git
identity/status, pinned cached compiler hashes, commands, JDK version, logs,
bounded command results and artifact hashes. A Git archive without `.git`
records null Git identity; it never borrows the current workspace's HEAD.

The host doubles deliberately provide deterministic UI/layout/touch boundaries.
Their purpose is to drive the actual fixture code far enough to exercise its
failure evidence, **not** to establish Android click classification, timers,
event routing, real Activity behavior, firmware, or physical touch capability.
In particular, the fake long-click is immediate, fake double-tap classification
is deterministic, and post callbacks execute immediately unless a fault drops
one. The genuine fixture's three-second layout wait remains unchanged and is
actually exercised by the dropped-post scenario.

The host UI scheduler records an escaping callback exception and returns rather
than killing/hanging the host process. AOSP's real `SyncRunnable` instead misses
completion/notification when an exception escapes. Correct test diagnostic
wrapping captures it inside the callback and rethrows the identical exception
after the delegate returns. See AOSP Android12.1
[`runOnMainSync`/`SyncRunnable`](https://android.googlesource.com/platform/frameworks/base/+/android-12.1.0_r1/core/java/android/app/Instrumentation.java).
Those sources do not identify RK firmware.

Scenarios cover a successful fixture control flow, main-thread setup failure,
an actual layout latch timeout, completed-native/held-DOWN failure, geometry
before an assertion failure, unexpectedly repeated UI callbacks producing more
than eight observed downs, Activity-launch failure with a longer-than-six cause
chain, and same-object exception transfer. They assert retained child reports,
real last-entered/completed checkpoints, observed counters and geometry,
array/cause bounds, class-only error reporting, unchanged actual event counts
where applicable, and no replay after failure.

This tests the report object retained through the actual local branch and catch.
It does not run the complete instrumentation lifecycle or Android's final file
write. Process death, a blocked main thread, or a failure in that final write
remain outside this host qualification.

The same harness accepts a complete baseline repository snapshot using
`--source-dir`; the unchanged happy fixture control should pass while missing
failure diagnostics make the diagnostic cases fail. Use a separate output
directory for each execution so source and result evidence remain distinct.

No device command, APK installation, instrumentation rerun, or repository edit
is performed by this harness.
