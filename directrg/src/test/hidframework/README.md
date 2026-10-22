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

The runner also snapshots the complete actual `ReceiverActivity.kt` and
`NativePadInput.kt`, then extracts exactly their `dispatchKeyEvent` and
`nativePadKey` functions with a unique-marker and balanced-brace parser. The
generated source compiles those unchanged function bodies against the actual
Android HID adapter, HID input and native pad recognizer. Only the surrounding
Activity superclass, pointer, usability, scheduling and diagnostic sinks are
explicit host boundaries. The manifest records both complete source snapshots,
the extracted function hashes and the generated source bytes. Native dispatch is
never reconstructed from a copied routing table.

Scenarios exercise descriptor/vendor/product/key/scan identity and display name
capture, whole-gesture capture consumption, Android repeat/long/cancel flags,
unbound key fallthrough, listener start/stop/change/removal, persistence across a
fresh attachment ID, actual Function classification and scroll callbacks, the
four labels, saved assignment movement/readback, storage errors, black/light
styling, active capture display, registered controls and Back callback. Assignment
rows have no click listener or sticky selection: the actual external fresh DOWN
captures the action under the root-local pointer. Every framework learning case
sets observable row geometry and pointer coordinates, then sends actual DOWN/UP
through the complete Android adapter. Moving the pointer before UP cannot change
the captured action. Repeated complete presses can immediately replace a row or
move an already assigned physical key to another row. A newer actual DOWN on
another key or attachment replaces incomplete capture; the old late UP remains
consumed and cannot save or disown the newer capture. Actual
listener change/removal also exercises canceled capture release ownership and a
fresh capture after removal and readdition with a reused Android attachment ID.
Missing-device events carry only the actual runtime ID/key/scan/downTime, never a
fabricated descriptor. Scenarios check consumption of previously captured edges,
held owner cancellation before a listener callback without target UP, exact
identity matching, unowned missing-device fallthrough, changed descriptor facts,
and fresh binding/dispatch after readdition.

Pre-listener changed-facts regressions capture an otherwise unbound Back DOWN
with the actual attachment 41/key 4/scan 158/downTime 1000, then pass matching
events through the complete actual Android adapter before any device listener
callback. Descriptor, vendor and product changes are tested independently for
UP, repeated/long DOWN, canceled UP and unsupported Android phases. Each must
consume and retire that captured gesture without persisting either identity or
emitting contact, scroll or Function output. A later selected capture and fresh
actual DOWN with changed facts must save those facts and dispatch normally.
Separate key, scan, downTime and attachment mismatches, plus a fresh DOWN from
the changed identity, preserve unrelated-event fallthrough and the original
capture until its actual matching release. Existing listener-first and missing-
device scenarios remain in the same suite. A cancellation publication callback
also starts a newer selected capture and fresh DOWN 1100 with the original
device/key/scan facts, replacing the old DOWN 1000 retirement record. The old
changed-descriptor UP must stay consumed, while the newer capture survives and
saves only its own DOWN facts on its actual UP with downTime 1100 without
emitting output. The same reentrant sequence also runs with a missing-device old
UP carrying only the actual attachment 41/key/scan/downTime; its already matched
release must stay consumed after the callback replaces the old retirement record.
Every named scenario runs even when another fails; the process
fails after reporting all named failures, so matched
parent RED evidence retains both new failures and existing control outcomes.

The adapter ownership and native-exclusion controls remain alongside the updated
panel scenarios. Native-exclusion correction scenarios use
the Owner-authorized recorded inventory: internal `ROKID,PSOC-TP-R` and
`qpnp_pon`, a virtual keyboard, and the external Nintendo Switch Right Joy-Con.
The saved native key 83/scan 204 Function assignment and the three saved Joy-Con
R1/A/X assignments are replayed as metadata/store inputs. These cases verify
external/nonvirtual admission, explicit exclusion of the observed native device
names even with a misleading external flag, capture and saved-binding bypass,
startup/listener repair that removes only the invalid device identity, retained
Joy-Con output, truthful failed repair and preservation of an unrelated active
external hold or capture. Changed eligibility on an already owned external edge
must cancel and consume that exact owner; a fresh excluded edge falls through.
No descriptor pattern or device connection is inferred by these host cases.

The single Reset all HID bindings control is located through the actual panel's
registered button label. This hover fixture requires the actual `actionAt` and
capture-target APIs; it is not a compatible preceding-production replay. Structural
checks require four full-width assignment controls, one Reset and Back, in that
order, without per-action Clear controls. Every interactive descendant remains
registered for the existing local-pointer route, uses normal-case button text
and retains the existing black/light style. These assertions observe widget
configuration; they do not render Android text or perform pointer hit testing.
External-HID learning instructions remain required.

The actual panel's `actionAt` uses `getGlobalVisibleRect` and its root-local to
global-root offset in one coordinate domain. Geometry fixtures include nonzero
root offsets and clipping, hidden and disabled rows/panels, hidden roots,
unavailable/outside points and the nonassignment Reset/Back areas. No screen
origin is assumed. These doubles supply visible rectangles and offsets; they do
not calculate Android layout or prove physical head-pointer accuracy. The
Activity's pointer publication/reentrancy checks are covered separately by the
existing actual Receiver/local-pointer harness.

Reset scenarios populate all four assignments, check exactly one attempted
empty-map persistence write, reload all four as Unassigned and then reassign
each control. Repeated Reset still performs one write. Null, valid empty and
malformed storage each exercise successful reset and failed writes: successful
reset produces the same valid existing-format empty record and clears any read
error, while failure retains old bytes and reports the save error. A failed
four-binding reset preserves all effective and reloaded assignments until retry.
Successful and failed reset both retire actual HID capture, held contact and
pending Function work. A captured old release stays consumed, a held old release
does not send target UP, and directly invoked stale Function timer callbacks
cannot dispatch after reset. These paths use the actual panel click listener,
store and HID cancellation implementation, with observable host target callbacks.

The actual Receiver extraction checks native key 83 with its legacy saved
Function assignment still present, both with an active HID capture and after a
failed startup repair. The excluded adapter edge must reach the unchanged native
`CONTACT` mapping and actual marker classifier, retaining its original
timestamps and classification deadline; no HID Function or learned assignment
may result. Another control presents the same numeric key and scan codes on an
eligible external device and verifies capture plus actual HID Function output.
The native mapper's existing key and source mappings are also retained controls.

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
device. The full `ReceiverActivity`, its target dispatch/lifecycle and routing
from its settings entrance remain outside this suite; only the documented key
ingress functions are extracted. Core physical acceptance for issues 15, 16 and
17 remains open.
