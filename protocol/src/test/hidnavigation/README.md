# Back/Home and binding migration host checks

From the repository root, using the existing cached Kotlin 2.2.21 compiler and JDK 17:

```sh
python3 protocol/src/test/hidnavigation/run.py --output-dir /absolute/new/evidence/directory
```

This runs the actual shared Back/Home recognizer, both production binding stores and
their canonical JUnit suites, plus the complete Phone binding panel. Inputs are copied
and hashed before compilation; dependencies, commands, output and results are retained.
It does not invoke Gradle, an Android SDK, a network download or a device.

The pure grammar checks single/double arbitration, original context, repeat/release,
cancellation, overlapping keys, stale timers and synchronous dispatch retirement.
The actual Phone controller and captured route feed this actual grammar as a modeled
Session destination. Controller checks retire pending Back after UP and double sequences
across page, device and availability changes, including reentrant route construction,
binding replacement and late original-route cancellation. The actual activation port
compiles with opaque wake-authority fixture types; no activation owner runs here.
Store checks cover legacy four-role records, explicit new-role learning, saved Function
and unrelated assignments, metadata, reopen and persistence failures. The Phone panel
test uses explicit Android widget and runtime/interception doubles. It checks the six
rows and selection, availability feedback, and that opening/selecting/closing the panel
does not mutate bindings or the modeled pin. It does not execute the pin service or
raw Phone HID capture. No actual PhoneInputMerger or transport runs in this suite.
Actual Phone/Glasses adapter integration and physical behavior
require their separate evidence.
