# v0.4 Phone HID component checks

Run `python3 directhost/src/test/hid-v04/run.py --output-dir /absolute/new/evidence-directory`.
The runner uses the cached Kotlin 2.2.21 compiler and JUnit 4.13.2 with JDK 17, without
Gradle, SDK, downloads or device operations. It snapshots and hashes every compiled input,
records compiler dependency hashes and commands, and writes `result.json` plus compile/run logs.

Actual production components compiled together are the Phone HID binding store, controller,
opaque original route, complete gesture recognizer, merger, touch dispatcher and relevant shared
protocol codecs, plus the actual app-process coordinate owner and router. Only Context and
Binder transport are doubled. Android framework, native pin acquisition, Shell execution and
physical devices remain outside the host runner's scope.
Preserved ordinary Tap/contact/reentry/concurrent-emission tests run with the actual dispatcher.
Results establish source/component behavior, not physical HID, radio, Android timing or app readiness.

Binding records use `eyeprojector.hid-bindings.2:` and store the exact gesture kind/count.
The explicit version 1 migration preserves Tap/Scroll, maps legacy Anchor to single Mode and
combined Navigation to single Back, leaves Home/Lock unassigned, and touches only the bindings
record. Pin intent and unrelated preferences are separate. One physical button still has one
exclusive role; learning another role on it moves the assignment in one write, as before.

Short and multi-click resolve after 500 ms from the final UP, preventing prefix dispatch.
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
