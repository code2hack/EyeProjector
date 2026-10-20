# Bootstrap adapter event regressions

Run after the ordinary Gradle host checks have provisioned the pinned Kotlin compiler, with JDK 17 configured:

```sh
python3 directhost/src/test/adapter/run.py --output-dir /path/to/private/test-output
```

This compiles the actual `HostBootstrap`, `HostForgetCompletion` and `BootstrapSessionFiles` production sources alongside test-only Android/Shizuku/Binder doubles. It drives delayed construction/Stop, unavailable setup restoration, accepted binds without callbacks, late abandoned callbacks, mismatched/matching installed revisions and Forget completion. No production source is rewritten or copied into a mock implementation. `--source-dir` accepts an exact source snapshot to demonstrate that regressions fail on the reviewed base.

The harness models API events and uses synthetic fixture bytes. It does not start a daemon, call Android Binder, validate Android filesystem permissions or prove physical teardown. The JVM lifecycle tests independently cover actual delayed worker cancellation; reserved-Phone evidence remains necessary. A result manifest records source and log hashes in the output directory.
