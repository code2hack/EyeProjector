# Anchor execution host checks

Run from the repository root, using only the existing cached Kotlin/JUnit compiler dependencies:

```sh
python3 directhost/src/test/anchor-execution/run.py --output-dir /absolute/private/evidence/directory
```

The runner compiles the actual execution owner/coordinator/Binder-value codec together with the actual
preference store, target resolver, accessibility reader, source geometry and protocol values. External
UI handles are test fixtures. It writes compiler/JUnit output, commands, source hashes and dependency
hashes. It downloads nothing and does not acquire an Android SDK, Gradle or device resource.

These checks establish host behavior only. Actual Android Binder transport, enabled accessibility
consent, managed-display visibility, framework injection and application effects require the separate
assembled build/device verification. CLICK_SUBMITTED means both output edges were accepted by the
original route; it never claims that an application completed an action.
