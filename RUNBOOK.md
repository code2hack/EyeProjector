# EyeProjector engineering runbook

Use [SPEC.md](SPEC.md) for product requirements and [AGENTS.md](AGENTS.md) for assignments, shared-resource ownership and publication. This file describes the current implementation; [the initialization audit](docs/migration/initialization-audit.md) records checks actually executed.

## Toolchain and modules

Run the checked-in wrapper from the repository root. Configure a local JDK 17 and Android SDK through `JAVA_HOME` and `ANDROID_HOME`, or untracked `local.properties` with `sdk.dir`. SDK installation and credential/session provisioning are host setup, separate from the application build.

| Component | Current pin |
| --- | --- |
| Gradle wrapper | 8.11.1; distribution SHA-256 `f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6` |
| Android Gradle Plugin | 8.7.3 |
| Kotlin plugin | 2.2.21 |
| Java/Kotlin target | JVM 17; build with JDK 17 |
| Android SDK | compile/target 35, minimum 32 |
| Android build tools | 34.0.0, verified AGP default for all four modules; no project override |
| Unit tests | JUnit 4.13.2 |

| Module | Responsibility | Debug artifact / identity |
| --- | --- | --- |
| `directhost` | Phone Stop screen, owned fixtures and explicit shell bridge | `directhost/build/outputs/apk/debug/directhost-debug.apk`; `com.code2hack.eyeprojector.directhost` |
| `directrg` | Glasses AV playback, local pointer and native controls | `directrg/build/outputs/apk/debug/directrg-debug.apk`; `com.code2hack.eyeprojector.directrg` |
| `protocol` | Platform mutual TLS, bounded framing, scrcpy parsing and controls | Android library; no installed app |
| `hostprobe` | Independent editable-field/AV and second-tone fixtures | `hostprobe/build/outputs/apk/debug/hostprobe-debug.apk`; `com.code2hack.eyeprojector.hostprobe` |

Receiver instrumentation is `directrg/build/outputs/apk/androidTest/debug/directrg-debug-androidTest.apk`, package `com.code2hack.eyeprojector.directrg.test`, runner `com.code2hack.eyeprojector.directrg.ProjectionInstrumentation`. All first-party namespaces use `com.code2hack.eyeprojector.<module>`. APK metadata identifies this repository bootstrap; it is not a new release version.

## Build and automated checks

From the root, with the SDK platform and AGP-selected build tools installed:

```sh
java -version
./gradlew --version
timeout --foreground 20m ./gradlew --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  :directhost:assembleDebug :directrg:assembleDebug :hostprobe:assembleDebug \
  :directrg:assembleDebugAndroidTest \
  :protocol:testDebugUnitTest :directhost:testDebugUnitTest :directrg:testDebugUnitTest \
  :protocol:lintDebug :directhost:lintDebug :directrg:lintDebug :hostprobe:lintDebug
```

JUnit XML lives under each tested module's `build/test-results/testDebugUnitTest/`; lint reports are under `build/reports/lint-results-debug.*`. Receiver JVM tests use test-only Android doubles for codec/lifecycle fault cases. They do not decode real hardware video or prove physical input, visibility or audibility. Review lint warnings as well as the exit code.

## Backend and bootstrap contract

The backend is unchanged upstream [scrcpy 5.0.1](https://github.com/Genymobile/scrcpy/releases/tag/v5.0.1), source commit `a60891aea193d92e7e5c3942700eca63f9d19a5f`. Its server asset is 733,930 bytes, SHA-256 `764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536`. Run `sh tools/fetch-scrcpy.sh` to obtain and verify the ignored `third_party/scrcpy-server-v5.0.1`; an existing mismatched file is rejected. See [third-party notices](third_party/NOTICE.md) and the [scrcpy license](third_party/scrcpy-LICENSE.txt).

`directhost.ShellMain` requires Android shell UID 2000 and exactly two arguments: an existing private session directory and the pinned master server JAR. The explicitly authorized Phone shell loads the host APK as its dex container:

```sh
CLASSPATH="$HOST_APK" app_process / \
  com.code2hack.eyeprojector.directhost.ShellMain \
  "$PRIVATE_SESSION_DIRECTORY" "$PINNED_MASTER_JAR"
```

These variables are paths on the Phone, set in that Phone shell. Resolve `HOST_APK` from the installed EyeProjector host package, verify installed bytes, and run the bridge in the assigned bounded command/process supervision route. The bridge itself has no overall elapsed-time cutoff. An ordinary app launch does not start it with shell privileges, and reboot does not automatically restore the bootstrap.

Provision per-role session directories privately with `identity.p12`, `password`, `peer.crt` and `endpoint.properties`. The latter supplies `host` and `port`; the server certificate must match the actual endpoint SAN. The bridge binds one configured private interface and port 1024–65535. The Receiver reads its app-private `files/session/`; the host Stop screen also requires its own app-private `files/session/` identity/configuration. Do not publish keys, passwords, live addresses or session directories. Platform PKCS12 loading must be tested on the actual OS; do not weaken peer authentication to work around a container-format error.

Mutual TLS 1.2/1.3 precedes capture. Wire revision 2 requires H.264 video and Opus audio. Messages are bounded to 1 MiB and dimensions to 4096 per axis. The current stock command creates a 480×640/160-dpi virtual display, H.264 at 2 Mbit/s and up to 30 fps, output-source Opus at 128 kbit/s, native controls, local display IME, `power_on=false`, `keep_active=true`, and cleanup. Clipboard synchronization is disabled. `send_codec_meta=true` is retained but unrecognized upstream; the upstream stream-metadata default supplies the headers. Reverse Unix sockets remain device-local. Each session gets a verified new JAR copy because stock cleanup removes its own classpath file; the master remains available for reconnect.

Current safety limits include 8-second host startup, 2-second blocked writes and 5-second audio/clock liveness. The Receiver uses the audio playback clock for video scheduling, drops video over 100 ms late and fails beyond 500 ms lookahead. Audio codec input and track writes each have a 250 ms bound; track allocation accepts a platform minimum up to 200 ms and a live queued backlog above 300 ms fails. These are implementation bounds, not measured latency guarantees.

## Device identity, installation and recovery

Obtain the current Phone/Glasses reservation and debugging route before installation, bootstrap or device mutation. Keep live serials, ADB server settings and network endpoints in local configuration. If the assigned ADB server is remote, set its established `ADB_SERVER_SOCKET`; do not replace the shared server. With `PHONE` and `GLASSES` set to exact reserved serials:

```sh
adb devices -l
timeout 15s adb -s "$PHONE" get-state
timeout 15s adb -s "$PHONE" shell getprop ro.product.model
timeout 15s adb -s "$PHONE" shell getprop ro.build.version.sdk
timeout 15s adb -s "$GLASSES" get-state
timeout 15s adb -s "$GLASSES" shell getprop ro.product.model
timeout 15s adb -s "$GLASSES" shell getprop ro.build.version.sdk
```

Record serial, model, OS/API, Android user and shell UID against the reservation. For a lost route, recheck that exact transport and the assigned server before retrying. Reconnect only the assigned known TCP endpoint when the reservation permits it; USB recovery may require the device owner. Report unavailable access with the failed command and outcome. A transport reset affecting other agents requires shared-route coordination.

The new application IDs are independent installed identities. Installation creates new private state rather than upgrading the earlier experimental applications. Leave those installed packages and their state in place. Within an authorized device window, install the EyeProjector builds:

```sh
timeout 120s adb -s "$PHONE" install -r directhost/build/outputs/apk/debug/directhost-debug.apk
timeout 120s adb -s "$PHONE" install -r hostprobe/build/outputs/apk/debug/hostprobe-debug.apk
timeout 120s adb -s "$GLASSES" install -r directrg/build/outputs/apk/debug/directrg-debug.apk
timeout 120s adb -s "$GLASSES" install -r directrg/build/outputs/apk/androidTest/debug/directrg-debug-androidTest.apk
```

Query `pm path` and package metadata for these exact IDs and hash the actual installed base APK bytes as well as the build outputs. Provision fresh private identities/configuration explicitly; do not copy another application's private state as an upgrade step.

## Instrumentation and diagnostics

After private provisioning and authorized bootstrap, use two terminals for a controlled fixture run. The stock virtual display is created by the Receiver connection. In the first terminal, start the driver with fixture synchronization and a unique `RUN_ID` matching `[A-Za-z0-9_-]{1,48}`:

```sh
timeout --foreground 240s adb -s "$GLASSES" shell am instrument -w \
  -e runId "$RUN_ID" -e operation stream -e seconds 10 -e waitForGo true \
  com.code2hack.eyeprojector.directrg.test/com.code2hack.eyeprojector.directrg.ProjectionInstrumentation
```

In the second terminal, verify the current authenticated geometry in Receiver `files/status.json` and obtain `DISPLAY_ID` from the new stock session log. Launch the fixture on that exact display, then release the driver's setup wait:

```sh
timeout 15s adb -s "$PHONE" shell am start --display "$DISPLAY_ID" \
  -n com.code2hack.eyeprojector.directhost/.AvFixtureActivity
timeout 15s adb -s "$GLASSES" shell run-as com.code2hack.eyeprojector.directrg \
  touch "files/probe-$RUN_ID.go"
```

After the first command completes, retrieve its report:

```sh
adb -s "$GLASSES" exec-out run-as com.code2hack.eyeprojector.directrg \
  cat "files/probe-$RUN_ID.json" > "$RUN_ID.json"
```

Other owned activities are `.FixtureActivity` (moving/tap counter), `.InputFixtureActivity` (real field, main and nested scrolling), and `com.code2hack.eyeprojector.hostprobe/.ToneActivity` (separate 440 Hz source). Target launch is a separate authorized ADB operation; the media protocol has no arbitrary app/shell launch command.

The driver allows 1–660 seconds; bound the host command to the chosen duration plus setup/teardown. Supported cases include `stream`, `reconnect`, `pause`, `local-stop`, `host-stop`, `unauthenticated`, `tls-diagnostic`, `input-observation`, `audio-focus`, `audio-volume` and `surface-diagnostic`. Some cases change local volume/focus or need fixture setup; read `ProjectionInstrumentation.kt` before selecting arguments. `waitForGo=true` waits for controlled fixture setup for up to 180 seconds. Reconnect allocates a new display; launch the fixture on that new display before releasing Go. A `host-stop` case consumes the bridge; later connections require explicit bootstrap. Direct method invocation in `local-stop` does not verify the physical three-tap binding.

| Symptom / area | Evidence to inspect |
| --- | --- |
| No connection | Current ADB/device identity, provisioned endpoint/SAN, PKCS12 loading, bridge `bridge-status.json`, TLS refusal; distinguish transport from authenticated media. |
| Black/frozen video | Current session freshness, changing packet and rendered counts, actual Surface/geometry, stock session log, receiver decoder/error state. Inspect actual Glasses output separately. |
| Audio missing/stalled | Required-audio state, source PTS and decoded-buffer progress, route/focus/volume, timestamp validity, underruns and queued frames. `PLAYING_UNCONFIRMED_AUDIBILITY` is not an audibility pass. |
| AV offset | Source PTS and Receiver audio-clock samples; use calibrated physical flash/beep observations for optical/acoustic claims. Packet-to-callback and ping RTT have narrower scopes. |
| Input | Physical sensor/pad observations, fitted geometry, gesture counters and actual target-app effect; compare main and nested native widgets. Use real IME key effects for text entry. |
| Power state | Timestamped physical panel, Android wakefulness, keyguard and virtual-display observations; distinguish continuing capture from fresh startup while blanked/Awake. |
| Stop/failure | `last-session.json` reason/stage, child liveness, runtime display removal, owned listener/socket closure, receiver codecs/audio/sensors and canceled controls. |

Bridge files are in its private session directory: `bridge-status.json`, `last-session.json` and `scrcpy-<session-id>.log`. Receiver `files/status.json` and `files/probe-<runId>.json` contain scoped counters/state. Read only the assigned application's diagnostics. Preserve original failures and private logs; publish a redacted, hash-bound evidence capsule.

For every reproduction record source SHA, clean/diff state, build command and tool versions, artifact hashes, installed-byte hashes, device/config identities, command results and actual measurement interval. Verify teardown promptly and return reservations to the Manager. Archival follows the recorded run completion boundary.

The owned synthetic clip is `src/main/res/raw/owned_flash_beep.mp4` in both `directhost` and `hostprobe`. It contains 12 seconds of 480×640 H.264 video and 48 kHz AAC audio, with a 100 ms flash/1 kHz beep each second. `sh tools/generate-fixture.sh` regenerates both copies using FFmpeg/libx264 and replaces those source assets; compare their hashes and rebuild affected APKs afterward. Codec/font/toolchain differences may change the generated bytes. The checked-in copies have SHA-256 `fa5f9fc73da052322b929b1e10a1d77765becbdb735a23d157617e965b28076a`. Authored timing is a test reference, not measured playback latency.

## Agent runtime reference

Use the repository's [manager-runtime skill](.agents/skills/manager-runtime/SKILL.md) to inspect registry/watchdog compatibility and initialize monitoring only for a verified assigned Manager. `runtime.json` is private and untracked. Reuse the installed Paseo messaging and device skills identified by the Manager; their host-specific configuration and active reservations stay local.
