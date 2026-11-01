# Native swipe receiver host checks

```sh
python3 directrg/src/test/native-swipe-navigation/run.py --output-dir /absolute/new/evidence
```

Compiles the complete current `NativeSwipeNavigationInput` and navigation enum with cached
Kotlin dependencies. Android registration and broadcast delivery are explicit test boundaries.
The runner retains exact source snapshots, dependency hashes, commands, failures and JVM class
loading provenance. No Gradle, SDK, download or device operation is performed.

The two exact swipe actions delegate synchronously. Their current firmware sender is unordered,
with no gesture ID or payload, so the adapter neither aborts nor changes a broadcast result.
Repeated callbacks are receipts, not physical gesture counts. Brightness suppression, physical
direction, current-user app delivery and app effects require separate device observations.

Tests cover API32/33/35 registration, the two destinations, unknown/ordinary/Function/Lock
actions, current sender flags, start/stop/restart, registration failure cleanup, stale callbacks
and synchronous lifecycle changes during dispatch. They do not establish Activity destination,
Phone transport or physical behavior on their own.

```sh
python3 directrg/src/test/native-swipe-navigation/run-activity.py --output-dir /absolute/new/activity-evidence
python3 directrg/src/test/native-swipe-navigation/run-function.py --source-dir . --output-dir /absolute/new/function-evidence
python3 directrg/src/test/native-swipe-navigation/run-composition.py --dependency-manifest /absolute/frozen/manifest.json --output-dir /absolute/new/composition-evidence
```

The Activity suite extracts the exact current initializer, foreground lifecycle, destinations,
context checks and cancellation methods. It compiles the actual held input owner and dispatcher;
Session, Android and widgets are named boundaries. It also runs the existing navigation scenarios
against those current method bodies. CANCEL callbacks exercise replacement, focus, geometry,
panel, owner and nested revision changes. Expected cancellation advances its own revision and
dismisses its own chooser; it cannot navigate, cancel pending input or publish into a successor.

The Function wrapper supplies current source predicates and Anchor DTOs missing from the older
read-only fixture. It compiles the complete current native/HID Function grammar and exact Activity
Function initializers/capture/dispatch. Swipe actions remain outside that grammar.

The composition suite compiles current Phone Session/Binder/Anchor/store/reader and Glasses
Session/owner code before immutable dependencies from the supplied hash-checked manifest.
It retains bytecode overlaps, actual class origins and ABI inspection. Its reused accepted122
dependencies retain the manifest's historical qualification (23 changed and 6 extra host classes
relative to historical Session05); they do not stand in for changed behavior. Android registration,
widgets, TLS, rendering, accessibility observations and target input are boundaries. The ordinary
key mapping and pad recognizer feed the actual Activity remote-scroll method through an explicit
test callback. Phone/Glasses held Anchor cleanup, chooser Back/Home, lifecycle and a second actual
Session are composed; locally queued navigation is still not a target-app acknowledgment.
