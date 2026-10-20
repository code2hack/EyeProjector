# Chosen-app verification

Issue [#9](https://github.com/code2hack/EyeProjector/issues/9), Worker: Worker-EyeProjector-9.

`ChosenApps.open(context)` supplies the ordered selection for both the Phone panel and the future projected home. `read()` exposes saved package identities, while `entries(catalog)` adds current labels and availability. `ChosenApps.observe(context, callback)` reports edits from another consumer; close the subscription with the consumer's lifecycle. `ChosenAppsPanel.onAppSelected` is the integration callback for issue #10. This issue does not start activities or projection.

The store starts empty. Package identities come from the Phone's MAIN/LAUNCHER catalog, with one choice per application package. A saved app displays **Unavailable** when the package lookup cannot observe it or it has no enabled launcher. Android's [package visibility filtering](https://developer.android.com/training/package-visibility) means a failed lookup cannot prove uninstallation. Its saved package, label and position remain intact. Restoring visibility, reinstalling or enabling the app makes the same choice available again. **Remove** only edits EyeProjector's list.

Host checks, using JDK 17 and SDK 35:

```sh
timeout --foreground 20m ./gradlew --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  :directhost:assembleDebug :directhost:assembleDebugAndroidTest \
  :directhost:testDebugUnitTest :directhost:lintDebug
```

JVM tests cover ordering, duplicate package identities, shared consumers, removal, absent-or-invisible lookup results, visible disabled apps, restored visibility/reinstall/re-enable, and picker filtering/sorting. Catalog doubles simulate these cases; they do not exercise Android package visibility, preference serialization or a real Phone UI.

After the Manager assigns the Phone and debugging route, install the host and test APKs with `adb install -r`. Preserve existing experimental packages and their data. The platform instrumentation runner needs no third-party test framework:

```sh
timeout --foreground 90s adb -s "$PHONE" shell am instrument -w \
  -e operation ui \
  com.code2hack.eyeprojector.directhost.test/com.code2hack.eyeprojector.directhost.ChosenAppsInstrumentation
timeout --foreground 90s adb -s "$PHONE" shell am instrument -w \
  -e operation persist-write \
  com.code2hack.eyeprojector.directhost.test/com.code2hack.eyeprojector.directhost.ChosenAppsInstrumentation
timeout 15s adb -s "$PHONE" shell am force-stop com.code2hack.eyeprojector.directhost
timeout --foreground 90s adb -s "$PHONE" shell am instrument -w \
  -e operation persist-read \
  com.code2hack.eyeprojector.directhost.test/com.code2hack.eyeprojector.directhost.ChosenAppsInstrumentation
```

Each run must report `result=PASS` and instrumentation code `-1`. The UI driver checks real package enumeration, add through the picker, leading + and ordered icons after consumer recreation, simulated absent/invisible/disabled/restored states, and long-press Remove. It also writes through another store consumer and asserts automatic panel redraw before any explicit refresh or recreation. The panel's store and observer share the fixture namespace while retaining the Activity theme/window. Compilation does not establish that these checks ran; retain individual results from an assigned Phone window. They do not reproduce a real package update removing its last launcher filter, true uninstall, or platform disable/re-enable; those physical transitions need separate evidence with an authorized test package. Its catalog fixtures and preferences are isolated in a test-only private preference file; it does not uninstall/disable real apps or change the host's saved choices. The two persistence operations check Android preference serialization across a stopped/restarted target process. They also use the isolated fixture preference file. Only stop the host within the assigned device window, with its current session state accounted for.

In that same window, record actual installed identities and versions for ChatGPT, Paseo, Comet and WeChat from package-manager output and visible application labels. A familiar package name or an empty selection is not evidence that an app is installed. Do not preselect those apps. Device evidence must record model, Android version/user, actual package names/versions, exact APK hashes and individual test results. Successful host checks do not establish Phone/device acceptance.
