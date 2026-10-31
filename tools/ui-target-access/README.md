# Managed-display UI access evidence (#111)

This is an isolated feasibility helper, separate from the production Anchor reader. Source and host
checks do **not** establish Android compilation, installation, UI access, touch behavior or app
compatibility. The Manager schedules the SDK and physical-device operations below. No device
execution result is included in this directory.

The actual caller is an installed, self-targeting `android.app.Instrumentation`:

- Package: `com.code2hack.eyeprojector.uitargetaccess`.
- Runner: `com.code2hack.eyeprojector.uitargetaccess.UiTargetAccessInstrumentation`.
- Temporary connection: `Instrumentation.getUiAutomation(FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)`.
- Only this caller's service flags gain `FLAG_RETRIEVE_INTERACTIVE_WINDOWS | FLAG_REPORT_VIEW_IDS`.
- `UiAutomation.getWindowsOnAllDisplays()` supplies display keys and window IDs. Only the explicitly
  supplied display's roots are read. Only the selected package's nodes are traversed; excluded
  package roots and null children are counted. There is no default-display fallback.

No accessibility service is installed/enabled, and directhost's HID service is unchanged. The helper
uses no Shizuku grant, shell permission adoption, hidden APIs, network permission, UI launch, input
injection, `performAction`, screenshots or hierarchy dump. It exports actionable nodes' static
resource/class identifiers, numeric action IDs, bounds, visibility/enabled flags and display/window
metadata. It does not read text, content descriptions, action labels, window titles or unique IDs.
Exception reports contain class names only. Framework accessibility transport may internally
retrieve node content; no conversation content is read or serialized by the helper.

An empty display inventory, absent display, null root, zero matching nodes, zero actionable nodes
or exception is an observation to investigate. `observationCompleted` means the API calls completed;
it is not a capability verdict. An advertised click/long-click/press-and-hold action does not choose
the user's Click/Hold mode or establish sustained touch behavior.

## Source and host checks

```sh
python3 -B tools/ui-target-access/run_host_checks.py
git diff --check
```

These compile/run the actual package-scoped traversal with host doubles (including exception
release and a deep tree), and check the actual comparison logic with explicitly synthetic inputs.
They do not compile the Android adapter or Kotlin fixture.

## Build in the Manager's assigned SDK slot

Use JDK 17 and the project's existing SDK/build tools. No downloads or Gradle project changes are
needed for the helper. `build.py` recreates only its own ignored `tools/ui-target-access/build`.

```sh
python3 tools/ui-target-access/build.py --sdk "$ANDROID_HOME" --build-tools 34.0.0 --platform 35
```

This outputs an **unsigned** APK. The Manager signs it with the assigned existing debug keystore
using the SDK's `apksigner` and its normal signing configuration; record the signed hash and signer.
For the standard Android debug keystore only, the concrete signing recipe is:

```sh
"$ANDROID_HOME/build-tools/34.0.0/apksigner" sign \
  --ks "$DEBUG_KEYSTORE" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --out tools/ui-target-access/build/ui-target-access.apk \
  tools/ui-target-access/build/ui-target-access-unsigned.apk
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --verbose --print-certs \
  tools/ui-target-access/build/ui-target-access.apk
```

`DEBUG_KEYSTORE` is supplied by the Manager; do not create/replace an existing key. Build the fixture
in the separately allocated Gradle slot:

```sh
timeout --foreground 10m ./gradlew --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g :hostprobe:assembleDebug :hostprobe:lintDebug
sha256sum tools/ui-target-access/build/ui-target-access.apk \
  hostprobe/build/outputs/apk/debug/hostprobe-debug.apk
```

## Reserved-device setup and one snapshot

Use the assigned ADB server/route and exact reserved `PHONE`. `DISPLAY_ID` comes from the current
managed backend/session evidence, **not** a guessed ID or the physical default display. Record the
source SHA, build/installed-byte hashes, helper signer, Android user, device model/API, actual display
identity/geometry and selected app/version/screen in the private run evidence. Record whether the
helper was already installed. Preserve installed applications, pairing and pending UI prompts.

Read the existing accessibility configuration before setup and after cleanup (do not write it):

```sh
timeout 15s adb -s "$PHONE" shell settings get secure enabled_accessibility_services
timeout 15s adb -s "$PHONE" shell settings get secure accessibility_enabled
timeout 15s adb -s "$PHONE" shell pm path com.code2hack.eyeprojector.uitargetaccess
timeout 120s adb -s "$PHONE" install -r tools/ui-target-access/build/ui-target-access.apk
timeout 120s adb -s "$PHONE" install -r hostprobe/build/outputs/apk/debug/hostprobe-debug.apk
```

The owned fixture has a separate task affinity and one activity instance, allowing explicit cleanup
without force-stopping hostprobe. It contains Click and a sustained-touch Hold control, distinct
resource IDs, passive DOWN/UP/CANCEL/click counters and independently observed View screen/window
rectangles. It generates no input. Launch only on the reserved managed display:

```sh
timeout 15s adb -s "$PHONE" shell am start --display "$DISPLAY_ID" \
  -n com.code2hack.eyeprojector.hostprobe/.UiTargetFixtureActivity --es runId "$RUN_ID"
timeout --foreground 60s adb -s "$PHONE" shell am instrument -w \
  -e runId "$RUN_ID" -e displayId "$DISPLAY_ID" \
  -e targetPackage com.code2hack.eyeprojector.hostprobe \
  com.code2hack.eyeprojector.uitargetaccess/.UiTargetAccessInstrumentation
adb -s "$PHONE" exec-out run-as com.code2hack.eyeprojector.uitargetaccess \
  cat files/ui-target-access.json > "$EVIDENCE/fixture-unlocked-access.json"
adb -s "$PHONE" exec-out run-as com.code2hack.eyeprojector.hostprobe \
  cat files/ui-target-fixture.json > "$EVIDENCE/fixture-unlocked-view.json"
python3 tools/ui-target-access/compare_fixture.py \
  "$EVIDENCE/fixture-unlocked-access.json" "$EVIDENCE/fixture-unlocked-view.json" \
  > "$EVIDENCE/fixture-unlocked-comparison.json"
```

`RUN_ID` identifies this fixture run; use distinct host filenames for each observation. The runner
always writes the same app-private report file, so retrieve it after each completed invocation.
Check its run ID and observation timestamps against the invocation to distinguish an old file after
a failed start. Command exit alone is insufficient: retain `INSTRUMENTATION_CODE`, errors and the
JSON. A canceled or incomplete observation is not a capability result.

Comparison requires unique matching fixture IDs on matching displays/windows with equal nonempty
screen rectangles. It retains both sources' timestamps. The View file is updated on layout, input
edges and lifecycle callbacks; an unchanged file is an earlier layout observation, not a newly
sampled locked-state View observation. Equal rectangles show UI/View coordinate agreement only.
They do not identify encoded pixels, prove touch delivery or establish ergonomic usability.

## Physical-off/locked and selected-app observations

With the same live managed session, the Manager coordinates physical Phone panel-off/keyguard
state and records its actual observations separately. Repeat the same instrumentation call with
the actual current `DISPLAY_ID`, retrieve both files under `fixture-locked-*` names and compare.
Do not restart the fixture or wake/unlock the physical display merely to obtain a snapshot. Record
any old View timestamp, unavailable tree, changed display/window or platform exception explicitly.

After fixture cleanup below, the Manager/Owner selects an actual projected ChatGPT screen and
records that screen context without conversation contents. Repeat the instrumentation command with
`-e targetPackage com.openai.chatgpt` only if that is the **actually installed selected package**,
and save under distinct app/lock-state filenames. Do not invoke an app/node action to observe it.
Record real resource IDs/action IDs/bounds and package-node counts, or the exact empty/null/error
outcome. A ChatGPT metadata observation does not establish any Click or Hold activation result.

## Completion and cleanup

The runner restores its original temporary service flags in `finally` and calls
`Instrumentation.finish`. The [AOSP Android 16 QPR2 implementation](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/core/java/android/app/Instrumentation.java)
disconnects its UiAutomation before finishing instrumentation. Verify the `am instrument -w` command completed and the helper process
ended; the report's cleanup string is intent, not independent lifecycle evidence. If the bounded
command timed out, preserve that failure, inspect the helper process and stop **only**
`com.code2hack.eyeprojector.uitargetaccess` within the reservation before another caller is tried.
Never force-stop directhost or change accessibility settings for this probe.

Finish the fixture through its own single-task activity's cleanup intent:

```sh
timeout 15s adb -s "$PHONE" shell am start --display "$DISPLAY_ID" \
  -n com.code2hack.eyeprojector.hostprobe/.UiTargetFixtureActivity --ez finishFixture true
timeout 15s adb -s "$PHONE" shell pidof com.code2hack.eyeprojector.uitargetaccess
timeout 15s adb -s "$PHONE" shell settings get secure enabled_accessibility_services
timeout 15s adb -s "$PHONE" shell settings get secure accessibility_enabled
```

Verify the fixture task is gone. Retain the hostprobe installation and private data. Retrieve all
reports before removing the helper; if newly installed exclusively for this run, the exact removal
is `adb -s "$PHONE" uninstall com.code2hack.eyeprojector.uitargetaccess`. Preserve a preexisting
helper unless its removal is assigned. Do not uninstall hostprobe, clear app data, remove any paired
state or disable other services. Record actual cleanup outcomes and return the device reservation.

API basis: [UiAutomation and coexistence/all-displays flags](https://developer.android.com/reference/android/app/UiAutomation),
[Instrumentation lifecycle](https://developer.android.com/reference/android/app/Instrumentation),
[node metadata and screen bounds](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo).
