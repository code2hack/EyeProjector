# HID Android adapter and bindings panel host scenarios

Run with JDK 17 and the pinned Kotlin compiler dependencies already cached locally:

```sh
JAVA_HOME=/path/to/jdk17 python3 directrg/src/test/hidframework/run.py \
  --source-dir /path/to/repository \
  --output-dir /path/to/a/new/evidence-directory
```

This suite compiles the complete actual `AndroidHidInput`, `HidBindingsPanel`,
`HidKeyInput`, `HidBindings`, `FunctionGestureRecognizer`, `PadGestureRecognizer`,
`HeadPointerModel`, `HeadOrientation`, `LocalInputSettings` and
`LocalInputSettingsPanel` source files. There is no extracted or
copied adapter, capture, persistence or UI implementation in the harness. Android
`InputDevice`, `KeyEvent`, `InputManager`, main `Looper`/`Handler`, `Context`,
resources and view/widget APIs are small observable host doubles. The scheduler
advances one monotonic test clock and accepts absolute deadlines. No native pad
normalizer is used for HID edges.

Scenarios exercise descriptor/vendor/product/key/scan identity and display name
capture, whole-gesture capture consumption, Android repeat/long/cancel flags,
unbound key fallthrough, listener start/stop/change/removal, persistence across a
fresh attachment ID, actual Function classification and scroll callbacks, the
four labels, saved assignment movement/readback, storage errors, black/light
styling, selected capture display, registered buttons and Back callback. Actual
listener change/removal also exercises canceled capture release ownership and a
fresh capture after removal and readdition with a reused Android attachment ID.
Missing-device events carry only the actual runtime ID/key/scan/downTime, never a
fabricated descriptor. Scenarios check consumption of previously captured edges,
held owner cancellation before a listener callback without target UP, exact
identity matching, unowned missing-device fallthrough, changed descriptor facts,
and fresh binding/dispatch after readdition.

The actual settings panel checks preserve independent axis values, scroll steps,
toggle state, persistent updates, bounds/disabled controls, failed-write behavior
and change callbacks. The HID entrance must be present in the registered local
button list, use the existing black/light styling, and call its supplied callback
without changing settings or sending the settings-change callback. View doubles
record color-state inputs and layout parameters; they do not simulate framework
state resolution or actual head-pointer hit testing.

The runner copies every production/test input into the attempt directory before
compilation, records original and snapshot paths with exact SHA-256 values, and
compiles those snapshots. It records JDK version, pinned compiler dependency
hashes, bounded commands, logs, jar and result hashes. Existing nonempty evidence
directories are left intact. Compilation is bounded to 60 seconds and scenarios
to 15 seconds. The runner invokes no Git, Gradle or network operation.

Host API doubles do not prove Android event delivery or framework behavior,
peripheral descriptor stability or uniqueness, actual HID key identity, Bluetooth
connection, physical press/release/repeat/reconnect, local head-pointer hit
testing, native target hold/drag/scroll, default suppression or navigation on a
device. The full `ReceiverActivity` and routing from its settings entrance are
outside this suite. Core physical acceptance for issues 15, 16 and 17 remains open.
