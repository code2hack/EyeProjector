# Registration host checks

Run from the issue worktree, with the assigned private evidence directory:

```sh
python3 directhost/src/test/anchor-registration/run.py --output-dir /path/to/evidence
```

The runner uses the cached Kotlin compiler and JUnit only, without Gradle, an Android SDK,
downloads or devices. It compiles actual registration, target resolver, snapshot/geometry,
preference store and Glasses chooser-controller source. Narrow doubles supply unrelated
`CapturedProjection` return types. Input sources, dependency hashes, compiler/test commands
and output artifact hash are retained with closed logs.

Checks cover original hover/fit mapping through the source display geometry, rotations/density,
actual reader/provider handle closure, unique identity, explicit Click/Hold choice, atomic-save
failure, Cancel after an external preference change, context loss, intentional chooser input
transition, original Session/incarnation/fit binding and late/duplicate UI messages.

These are host behavior checks. The Android overlay, shared Binder/transport integration,
production accessibility grant and physical target/gesture behavior require their allocated
build and device verification windows.

To include focused registration-provider cases, add `--provider-api-checks`. This uses the
actual shared message DTO/codec and a named runtime API double, while retaining actual
provider/reader/resolver/store behavior. Its result records `modeledProviderApi: true`.
The [production composition](../../../../directrg/src/test/registration-pipeline/README.md)
separately exercises the real shared runtime, FIFO, reflected installer and both Sessions.
