# Persistent HID profiles: focused host checks

Run with a new evidence directory and a local `org.json:json:20240303` jar:

```
python3 directhost/src/test/hid-profiles/run.py --output-dir /absolute/new/evidence --json-jar /absolute/path/json-20240303.jar
```

The runner reads the cached Kotlin compiler/JUnit dependencies, snapshots and hashes
all compiled inputs, and records commands, dependency hashes, exits and logs in
`result.json`. It makes no Gradle, SDK, device or download calls. The explicit JSON
jar supplies the host implementation of Android's JSON API. JDK 17 is selected by
`JAVA_HOME`, or the host's existing `/usr/lib/jvm/java-17-openjdk-amd64`.

Production sources compiled together include the bindings codec, profiles store,
controller, pin runtime, foreground notification service, panel, protocol value types
and learning recognizer.
The 14 existing bindings regressions also run. New regressions cover independent
maps and gestures, multiple native collections, mixed native/Android identities,
v1/v2 migration, exact legacy bytes, reload without remigration, failed/throwing
writes, original-profile queued save/reset, old learning callbacks, held/released
routes, cancel reentry, actual pin/unpin/switch/reset, B learning preserving A,
saved pin/profile restart/reconnect, stale Binder input, separate UI actions, notification Unpin and
pairing/unrelated preference preservation.

Android preferences/services, Handler/main-queue delivery, Binder/Shizuku transport
and target routes are explicit doubles. Pin runtime uses its actual executor;
tests await its bounded queue and shut it down. The panel's Runtime container is a
composition boundary: production `PhoneHidRuntime` and session snapshot preparation
are covered by the Manager's affected Android build and integration checks.
These tests do not prove OS preference durability, permission/service lifecycle,
native grabs, physical reconnect identity stability or radio/input timing.

The runtime stores the new `eyeprojector.hid-profiles.1:` record under
`phone_hid_bindings/profiles`. Existing `phone_hid_bindings/bindings` bytes remain
unchanged, including on reset/unpin. Each legacy entry supplies its own identity:
native `[physicalKey, collectionKey]` descriptors group by physical key while
keeping exact collection descriptors; old Android descriptors retain their exact
descriptor/vendor/product identities separately. There is no inferred translation
from an Android descriptor to a native physical key or to the current pin. Native
identity with missing/unreadable physical-key encoding reports migration failure
and retains the source bytes.


For the real Android fallback caller composition, add `--callers` with another new
evidence directory. This compiles the complete production `PhoneHidRuntime` and
`PhoneHidKeyService` with the actual profile/controller/pin/panel/notification
sources. It runs seven caller regressions plus the same 14 bindings checks. The
session facade, snapshot availability/target routes and Android framework remain
explicit doubles; Runtime and KeyService are neither extracted nor substituted.
It verifies actual migrated-key dispatch, mixed-profile explicit UI selection,
persisted selection/restart, transient attachment IDs, disconnect/identity loss,
native pin/release preemption and original fallback restoration, stale terminals,
selection read/write failure, selected-profile reset, canceled learning callbacks and
original-profile queued writes. Executors are bounded and shut down after tests.

One Android fallback intent is restored from `phone_hid_bindings/android_profile`,
or from the sole actual saved Android identity when no choice exists. Multiple
saved identities require the explicit legacy-only selector. Key callbacks never
select a profile. Native loading, pin intent and release preempt the fallback;
completed Unpin restores the same fallback intent. The selector labels this as
Android key fallback without native interception. Legacy binding bytes remain
untouched and exact Android identities are never translated into native keys.
