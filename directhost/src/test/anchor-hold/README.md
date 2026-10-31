# Original Anchor Hold host checks

Run `python3 directhost/src/test/anchor-hold/run.py --output-dir <private-evidence-directory>`.
Use `--indicator-only` for the independent Glasses presentation tests.
Pass `--dependency-manifest <Manager-frozen-manifest.json>` to use the fixed compiler/JUnit inputs.

The runner uses cached Kotlin/JUnit JARs and compiles snapshots of the current production sources.
It does not run Gradle, download dependencies, use the Android SDK, or operate devices.
Each run retains exact compiler inputs, source/dependency hashes, the compiled artifact and logs.

Owner tests compose the actual Anchor owner, ordinary input merger and touch dispatcher with an
external MotionTarget fixture. Coordinator tests use the actual store, reader and resolver. The
indicator owns presentation only. These checks do not establish physical Binder behavior, app
acknowledgment, accessibility grants, native firmware delivery or the assembled Activity UI.

Actual Session/Binder adapter retirement uses `run-session.py --dependency-manifest <manifest>
--output-dir <evidence>`. It compiles the original Phone Session and the reflected registration
provider, retaining the retirement/failure/settings checks and source/class-loading evidence.
Low-level invalidation calls in the unit suite alone do not prove navigation adapter delivery.

Add `--ui-pipeline` for the joined Function producer, Glasses Session, original Phone contact and
reflected provider, state transport, retained Glasses owner and current Activity indicator source.
The runner snapshots the full current Activity and compiles its complete label initializer,
callback, Function capture/dispatch, registration, geometry, cancellation and lifecycle methods
against named widgets/service/renderer boundaries. The real Activity is separately compiled by
the allocated matched SDK check. This does not establish Android view hit testing or app effects.

`--glasses --phone-artifact <phone-held-state.bin>` runs the focused original-state consumer checks.
All source-composition modes use only the Manager-frozen dependency manifest, record overlapping
classes and put the newly compiled actual-source JAR first. The frozen accepted dependency origin
and differences from historical cache inputs remain explicit in each result.
