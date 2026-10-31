# Production managed-display metadata reader host checks

```sh
python3 -B directhost/src/test/uiaccess/run.py --output-dir /absolute/private/evidence/host
```

Uses the existing cached Kotlin compiler, without Gradle, SDK, downloads or devices. Compiles the
actual reader, Android adapter, models, connection/provider and geometry together with actual
protocol `Fit`, `ProjectionContext` and `PhoneHidKeyService` callbacks. Framework handles, existing
HID controller/runtime and unused `CapturedProjection` return types are doubles. Evidence records
source/dependency hashes, compiler command, exit codes and logs.

Scenarios exercise exact display/window selection, no physical-display root read, node/window
release after success and a stale-node failure, excluded-package subtrees, a deep tree, absent
display/window/root/access, enabled/visible/positive-area filtering, absent/repeated IDs, source
replacement during reading and exact service connection retirement.
Actual service callbacks retain key filtering alongside retrieval/view-ID flags and retire access
on unbind/destroy without retiring a replacement service's connection.

Independent geometry landmarks cover logical pixels versus 160-dpi layout, density, scaling,
encoded dimensions, centered aspect padding and integer rounding, all four rotations, disabled
scaling, source screen bounds that already include insets/IME offsets, and actual Receiver `Fit`.
Accepted #111's View bounds are a logical-coordinate reference, not encoded-frame validation.

In an assigned SDK/Gradle slot, compile directhost and run the actual mapping-owner/injector checks:

```sh
./gradlew --offline --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g \
  :directhost:testDebugUnitTest --tests com.code2hack.eyeprojector.directhost.ManagedUiMappingTest
python3 -B directhost/src/test/uiaccess/run-context.py --output-dir /absolute/private/evidence/context
```

The context runner uses current SDK-compiled dependency classes and existing Android/Shizuku/Binder
doubles to compile the actual `HostBootstrap` facade. It exercises original proxy/context/generation
capture, replacements before/after Binder query and while queued, explicit unavailable context,
app-mediated access-required feedback, and selected-package versus observed-foreground separation.
The JVM tests exercise the actual managed mapping owner and injector at independent landmarks,
existing transition admission, nullable unique ID, context encoding and default-unavailable capture
contract. The production Session overrides that contract for its original valid managed mapping.

After the focused SDK compile, exercise the actual Session/Bridge join and delegated #116 authority:

```sh
python3 -B directhost/src/test/launch/run.py --ui-targets-only --output-dir /absolute/private/evidence/session
python3 -B directhost/src/test/launch/run.py --nav-context-only --output-dir /absolute/private/evidence/nav-context
```

Session cases use the actual Session and managed mapping owner with synthetic observations. They
cover current-package metadata, unavailable Activity/unique ID, original capture across replacement,
retirement during a foreground query, transitions/geometry changes and the actual reader/provider
composition. Metadata observers acquire Session/input locks on an independent lane to establish
those locks are released; tree traversal checks its caller also owns none of those locks.

The delegated #116 `acceptsContext` helper permits a missing pointer position for Back/Home and Lock
while retaining the original region/word/stamp, owner/incarnation/input revision/mode and pointer
context checks. The existing spatial predicate keeps its availability requirement. Its direct host
checks cover both local/projected modes and mismatched/stale/retired contexts; the complete stamped
navigation route also depends on the separately owned #116 merger integration.

These host checks establish source behavior against modeled platform responses. Android compilation,
actual user accessibility enablement, managed-display production access, screen/encoder landmarks,
IME/rotation/scale on devices and physical-Phone-off/locked behavior require separate scheduled
validation. They establish no target matching, persistent Anchor identity or input delivery.
