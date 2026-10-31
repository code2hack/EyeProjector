# Original Anchor Hold host checks

Run `python3 directhost/src/test/anchor-hold/run.py --output-dir <private-evidence-directory>`.
Use `--indicator-only` for the independent Glasses presentation tests.

The runner uses cached Kotlin/JUnit JARs and compiles snapshots of the current production sources.
It does not run Gradle, download dependencies, use the Android SDK, or operate devices.
Each run retains exact compiler inputs, source/dependency hashes, the compiled artifact and logs.

Owner tests compose the actual Anchor owner, ordinary input merger and touch dispatcher with an
external MotionTarget fixture. Coordinator tests use the actual store, reader and resolver. The
indicator owns presentation only. These checks do not establish physical Binder behavior, app
acknowledgment, accessibility grants, native firmware delivery or the assembled Activity UI.

Actual Session/Binder adapter retirement requires its separate production source composition
closure; low-level invalidation calls here do not prove the navigation adapter's delivery.
