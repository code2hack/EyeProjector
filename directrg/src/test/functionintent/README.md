# Function intention host checks

Run from the repository worktree after the pinned Kotlin/JUnit dependencies are available:

```sh
python3 directrg/src/test/functionintent/run.py --output-dir /absolute/new/evidence/directory
```

This runner invokes cached Kotlin/JUnit directly; it does not invoke Gradle, provision an SDK,
connect to devices or install apps. Each attempt keeps immutable compiler-input copies, hashes,
compiler logs and JUnit results. It compiles the complete actual recognizer, native Function
adapter, HID Function adapter, Phone HID controller/route/merger and FIFO Function worker.
The native receiver's Android API surface is doubled. The unused activation ProjectionContext
is a small type boundary; the exercised controller, route and grammar are actual source.

Checks cover each completed short, rapid pairs/triples, long registration and consumed release,
original context/hover, source IDs and delivery order, duplicate summaries, stale callbacks,
context retirement, reentry/backpressure, and unrelated HID contact/capture behavior.

Native CLICK/DOUBLE/TRIPLE have no report ID. Their existing 500 ms quiet arbitration remains
a model of duplicate delivery; the existing 800 ms threshold distinguishes long from short.
Summary counts fill missing original shorts rather than adding to observed edge presses.
Reports that arrive beyond the identity-free retirement boundary cannot be proven duplicates.
These inputs do not establish firmware ordering, actual interception or physical usability.

Each source retains an allocator across its adapter's recreation: native and Glasses HID have
separate process-local allocators, and Phone HID retains its own allocator across Sessions.
These counters only allocate opaque gesture IDs and ordered intention sequences. The actual
operation identity includes FunctionSource, the captured Receiver incarnation and owner/input
context, and the local CapturedFunctionIntent retains the original Session/route. Counters do
not create a global duplicate set, suppression policy, expiry or input/contact owner.
