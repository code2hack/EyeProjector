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

## Runtime registry

The Manager generates the host-specific root `runtime.json` from this chapter. It is private and Git-ignored. This is the canonical field specification; the runtime skill implements it. Initial creation uses schema 2; reconcile existing identities, assignments, decisions and history before converting a schema-1 registry, retaining a private original snapshot.

| Root field | Type and meaning |
| --- | --- |
| `schemaVersion` | Integer `2` |
| `revision`, `updatedAt` | Positive integer incremented on change; ISO-8601 update timestamp |
| `project` | `{name, repository, root, paseoId}`: product name, Git remote, absolute physical permanent checkout and verified Paseo project ID |
| `settings` | Host/run settings and Owner decision references; `guardApprovalTimeoutSeconds` is a positive number, default `600`. Record the Owner's Worker concurrency and notification/alarm choices here |
| `currentVersion` | `null` outside a version, otherwise the version object below |
| `agents` | Current registered participants using the participant fields below |
| `missions` | Ongoing issue missions; completed mission evidence moves to the version record |
| `reservations` | Current exclusive resource reservations |
| `guardRequests` | Guard proposals and retained decisions, including timeout denials |
| `credentials` | Credential references and cleanup decisions; values are in external private files |
| `history` | Durable record references for completed versions, issues, participants and decisions |

Retain unrelated extension fields during reconciliation. A fresh registry has empty mission/reservation/request/credential/history arrays and the verified assigned Manager. The [runtime helper](.agents/skills/manager-runtime/SKILL.md) supplies `inspect` and `register`; initialize with explicit settings and current-version JSON files. Re-running registration preserves other participants and records. An explicitly supplied current-version input updates that field.

| Participant field | Meaning |
| --- | --- |
| `role`, `name`, `scope` | `Manager`, `Worker` or `Reviewer`; unique role/name; assigned responsibility |
| `host`, `runtime`, `id` | Actual hostname, native provider and native session identity; tuple is unique |
| `paseo`, `workspace`, `cwd` | Verified unique Paseo agent ID, workspace ID and absolute assigned checkout |
| `model`, `effort`, `mode` | Verified effective configuration; retain configured preferences separately in settings |
| `assignment` | Durable absolute mission/version assignment reference |
| `status` | Expected `running` (work is assigned), `idle` (no current work) or `waiting` (recorded dependency/decision) |

A registered runtime has one Manager. Observe actual Paseo status, archive and permission state separately when reporting; expectations change after the Manager reconciles assignments and results. Native identity, public role/name and transport ID serve different purposes.

| Version field | Meaning |
| --- | --- |
| `id`, `branch` | Owner-agreed version identifier and dedicated integration branch, for example `version/<id>` |
| `plan`, `record` | Absolute durable authorized-plan and version-run record paths |
| `status` | `planned`, `implementing` or `completed` |

| Mission field | Meaning |
| --- | --- |
| `id`, `issue` | Stable mission identity and canonical issue URL |
| `worker`, `reviewer` | Worker Paseo ID and independent Reviewer Paseo ID; `reviewer` is `null` before assignment |
| `branch`, `worktree` | Dedicated issue branch based on the version branch and absolute issue checkout |
| `status` | `implementing`, `reviewing` or `waiting` |
| `activity`, `updatedAt` | Short description of what is happening now and its timestamp |

Active participant references resolve to the expected roles. At issue completion retain source/verdict/evidence in the version record, remove the ongoing mission, retire its Reviewer and retain its idle Worker/worktree until version completion. Retire the Reviewer agent rather than archiving a workspace still owned by the Worker.

| Reservation field | Meaning |
| --- | --- |
| `id`, `resource` | Unique reservation and exclusive physical device/service identity |
| `mission`, `holder` | Mission ID and holding participant's Paseo ID |
| `operations`, `acquiredAt` | Authorized operation strings and acquisition timestamp |

The Manager writes reservations before conflicting operations and removes them after resource return. Record every device in a cross-device test. Live serials, endpoints and recovery bindings belong here or referenced private configuration.

| Guard-request field | Meaning |
| --- | --- |
| `id`, optional `inReplyTo` | Stable proposal request and correlation to a previous proposal |
| `mission` | Mission ID, or `null` for version-wide work |
| `proposal`, `reason` | Specific proposed restriction, its effects and why it is requested |
| `requestedAt`, `deadline` | Request time and request time plus `settings.guardApprovalTimeoutSeconds` |
| `status`, `resolution` | `pending`, `approved` or `denied`; resolution `null`, `owner` or `timeout` |
| `decisionAt`, `dismissedAt` | Decision timestamp and dismissal timestamp; `null` while pending |
| `askOnNextWatchdog` | Boolean flag to ask the Owner about a recorded timeout denial at the next wake |
| Optional `ownerDecision` | Authenticated Owner decision reference |

Before adding a guard, send the explanation and deadline to the Owner. Continue independently authorized work while awaiting that decision. On an unanswered deadline, record `status: "denied"`, `resolution: "timeout"`, the decision/dismissal times and `askOnNextWatchdog: true`; dismiss the proposal and continue without its addition. The next watchdog wake prompts the Manager to ask again using a new correlated request, preserving the prior denial. After recording the new request and accepted send, clear the old `askOnNextWatchdog` flag; its denial and timestamps remain intact. Terminal records can retain references to completed missions. Explicit Owner approvals/denials use `resolution: "owner"` and their decision reference.

| Credential-reference field | Meaning |
| --- | --- |
| `id`, `kind`, `host` | Reference identity, credential purpose and storage host |
| `path` | Absolute private file outside the checkout |
| `ownerRequest`, `createdAt`, `versionId` | Explicit retention request, creation time and relevant version |
| `cleanupStatus` | `pending-owner-decision`, `retained` or `destroyed` |

For Owner-requested retention, use the [credential capture procedure](.agents/skills/manager-runtime/SKILL.md) in a unique window of the existing `sudo` tmux session. The Owner enters matching values twice directly in that TTY with echo disabled. The capture helper writes a mode-0600 file in a dedicated private directory and returns its path. Record only the reference. At version end ask whether to destroy it, then record the decision and result. In the Owner-controlled tmux window, with the paths and request ID resolved beforehand:

```sh
python3 "$SKILL_DIR/scripts/save-credential.py" \
  --file "$CREDENTIAL_FILE" --repo-root "$PROJECT_ROOT" --owner-request "$OWNER_REQUEST_ID"
```

Coordinate registry writers, check the previous revision, write and validate a same-directory temporary file, and atomically replace the destination with the incremented revision. The runtime helper's cooperative lock and conflict checks implement this procedure. A registry update records an assignment or decision; actual dispatch and observation use Paseo.

## Manager watchdog

The existing shared `manager-watchdog` checks registered Manager Paseo IDs. Each tick skips busy Managers and wakes available idle Managers. The wake tells the Manager to inspect local ongoing missions, observe Workers/Reviewers, briefly report what each Worker is doing and progress, process expired guard proposals, and ask about recorded timeout denials. Runtime and mission reads occur in the Manager's own host context.

Portable maintained source/tests are in `tools/manager-watchdog/`; the installed executable, config, user units and SDK path are host configuration. Inspect the installed version before changing it. Config accepts an empty target list:

```json
{
  "intervalSeconds": 1200,
  "managers": [
    {"paseoId": "<verified-manager-id>", "paseoHost": "ssh://<user>@<manager-host>"}
  ]
}
```

`paseoHost` is optional for a Manager on the watchdog's local daemon. Other existing entry metadata is retained. The shared interval remains independent of any project's runtime. Register the actual assigned Manager at version start, using explicit host configuration variables, including `WATCHDOG_CLIENT` for the installed SDK on the watchdog host:

```sh
"$WATCHDOG_EXECUTABLE" --config "$WATCHDOG_CONFIG" \
  --register "$MANAGER_PASEO_ID" --paseo-host "$MANAGER_DAEMON"
"$WATCHDOG_EXECUTABLE" --config "$WATCHDOG_CONFIG" --check --client "$WATCHDOG_CLIENT"
```

After all planned issues and version verification complete, unregister it:

```sh
"$WATCHDOG_EXECUTABLE" --config "$WATCHDOG_CONFIG" --unregister "$MANAGER_PASEO_ID"
```

Run these supported operations on the installed watchdog host, through SSH when appropriate. The watchdog needs a working Paseo route to the Manager; it does not need access to that Manager's runtime file. Preserve other targets and the shared interval. Reconcile user units after a target-count change using `--write-units`, `systemctl --user daemon-reload` and a timer restart; the service command carries the installed SDK module through `--client` or `PASEO_CLIENT`. A oneshot service may be inactive between ticks.

`--check` queries Managers without sending wakes. A live tick uses stable message IDs and retained wake records; reconcile an uncertain send before replay. Transport acceptance is separate from Manager receipt and progress reporting. Fixtures run with:

```sh
node --test tools/manager-watchdog/main.test.mjs
node --test .agents/skills/manager-runtime/tests/manager-runtime.test.mjs
python3 -m unittest discover -s .agents/skills/manager-runtime/tests -p 'test_*.py'
```
