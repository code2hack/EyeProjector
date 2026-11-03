# v0.4 Phone HID component checks

Run `python3 directhost/src/test/hid-v04/run.py --output-dir /absolute/new/evidence-directory`.
The runner uses the cached Kotlin 2.2.21 compiler and JUnit 4.13.2 with JDK 17, without
Gradle, SDK, downloads or device operations. It snapshots and hashes every compiled input,
records compiler dependency hashes and commands, and writes `result.json` plus compile/run logs.

Actual production components compiled together are the Phone HID binding store, controller,
opaque original route, original Binder route, complete gesture recognizer, merger, touch dispatcher and relevant shared
protocol codecs, plus the actual app-process coordinate owner and router. Only Context and
Binder transport are doubled. Android framework, native pin acquisition, Shell execution and
physical devices remain outside the host runner's scope.
Preserved ordinary Tap/contact/reentry/concurrent-emission tests run with the actual dispatcher.
Results establish source/component behavior, not physical HID, radio, Android timing or app readiness.

`PhoneHidProfileCompositionTest` joins the real profiles store and profile selection to the
controller, original route and merger. Two recorded synthetic stable identities reuse the same
attachment/key/scan numbers and retain independent SINGLE/MULTI maps. Assertions check delivery
inside the matching UP call, original pending/held-input cancellation before replacement, stale
timers/releases, and queued learning/reset writes staying with their original profile. Time,
persistence, identity mapping and output ports are explicit host fixtures. The separate
`hid-profiles` runner retains the actual pin/runtime/UI tests from issue 159; it is not duplicated here.

The existing Phone `ChosenAppsInstrumentation` also accepts `operation=hid-profiles` with
`phase=all` (default), `write`, or `read`. It uses actual Android SharedPreferences and the real
`PhoneHidPinRuntime.profileForDevice` mapper with synthetic native descriptors. It checks mixed
device v0.4 migration, independent same-role gestures, reset of B alone, reopen without remigration,
and byte-identical legacy/unrelated/pin/pairing sentinels. Every preference name is prefixed
`instrumentation_hid_profiles_`; no live HID/pin/pairing preferences or pin services are used.
Root can run `write`, restart the app process, then run `read`, retaining the returned `fixturePid`
and its own process-restart evidence. `all` alone proves reopening in one process. These operations
do not activate fake devices, inject HID, prove physical reconnect or measure radio/physical timing.
The check leaves its isolated fixtures for the read phase; Root owns their eventual cleanup.

Binding records use `eyeprojector.hid-bindings.2:` and store the exact gesture kind/count.
The explicit version 1 migration preserves Tap/Scroll, maps legacy Anchor to single Mode and
combined Navigation to single Back, leaves Home/Lock unassigned, and touches only the bindings
record. Pin intent and unrelated preferences are separate. One physical button has one
exclusive raw Tap/Scroll role, or distinct discrete Back/Home/Lock/Mode gestures. An exact
gesture collision moves only that gesture; learning raw Tap/Scroll moves every assignment off
its key. Learning discrete on a raw-owned key replaces that raw role in the same write.

Runtime recognition captures all saved candidates and the original context at the first DOWN.
Each physical key has its own recognizer. SINGLE completes on short UP, including SINGLE+LONG,
unless a larger assigned click count competes. MULTI N completes on the Nth short UP unless a
larger assigned count remains possible. Competing prefixes wait for that key's 500 ms interval,
then emit only an exact bound count. Four rapid clicks of a double-only binding perform two actions. Learning still discovers
the complete generic click count after 500 ms from the final UP and consumes input without effects.
Long means at least 800 ms held and resolves on its UP. A held press is not terminated by an
elapsed-time cutoff. A long press after earlier clicks is not a valid single/long/multi gesture.
Tap/Scroll learn only SINGLE but retain their runtime raw contact/press behavior. Scroll emits on
DOWN, repeats after 400 ms and then every 100 ms, and never emits on UP. Delayed delivery schedules
from the actual delivery time, avoiding catch-up bursts. These are implementation choices, not
measured hardware timing.

Issue 149 joins the actual PhoneModeOwner and CoordinateInputRouter to the merger.
Mode and mode-aware Tap/Back/Scroll decisions emit in committed order after releasing the
control lock. The Session's mode/input FIFO also owns native wire input and deadlines.
The regression blocks Mode delivery while later operations commit, then checks Settings
consumes the complete Tap contact and all persistence/publication/output occurs outside
that lock. Context and Binder transport are test boundaries; this is no Android/device pass.

Issue 165 checks join real learning, store/reload, controller, PhoneHidOriginal, typed local codec
and merger for shared gestures, original capture, prefix timing and cleanup. The local edge codec
keeps the 39-byte single-binding representation and extends shared maps with action/gesture
candidates. LAN/Wire and native Rokid input are unchanged. The Android isolated profile fixture
retains a shared short/long/double map on A through its write/read phases, while preserving legacy,
pin and unrelated preference bytes and resetting B alone. The profiles runner checks actual UI
rows for coexistence and conflict movement.
