# Anchor target host checks

Run `python3 directhost/src/test/anchor-target/run.py --output-dir /path/to/evidence`.
This uses the cached Kotlin compiler and JUnit, without Gradle, an Android SDK or a device. It
compiles the production descriptor, codec, resolver, reader/provider, snapshot and geometry models
plus the normal JUnit test source. Two existing doubles supply unrelated `CapturedProjection` return types only.
The output records commands, source/dependency hashes, JAR provenance and compiler/JUnit results.
The JUnit source is also part of the normal `directhost` unit-test source set.

Checks cover static identity capture and encoding, recreated node/window/display/session values,
current moved/scaled bounds, required current package/window observations, optional actual Activity,
missing/ambiguous identity and unavailable reads. No Click/Hold mode is inferred or stored here.
One joined scenario recreates modeled handles through the actual reader/provider before capture
and resolution, checks disposal and changes traversal ordinal, session, display, window and geometry.

Supported identity is a nonblank app resource ID, refined by class when observed and scoped to the
current app/window type plus the actual Activity when observed at capture. ID-less controls return
identity unavailable. Repeated full identities return ambiguity. Missing saved Activity remains
unknown: ID reuse on different screens cannot be distinguished. Even known Activity/ID/class values
cannot distinguish an app reusing them for different logical controls. No labels, text, tree paths,
ordinals or old coordinates are used as substitutes. These are metadata coverage limits.

A unique result contains the current source, window and observation, including current bounds.
It is a snapshot match, not permission to bypass existing input admission or a live app acknowledgment.
Host checks establish no managed-display accessibility grant, app compatibility, physical input,
Phone-off/locked behavior or universal target identity. Those remain assembled-version validation.
