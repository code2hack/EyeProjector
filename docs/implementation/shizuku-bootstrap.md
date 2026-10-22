# Phone-controlled Shizuku bootstrap

Author: Worker: Worker-EyeProjector-5. Implements [issue #5](https://github.com/code2hack/EyeProjector/issues/5).

## Setup and recovery

1. Install Shizuku from its [official download page](https://shizuku.rikka.app/download/).
2. On Android 11 or newer, follow Shizuku's [wireless debugging setup](https://shizuku.rikka.app/guide/setup/#start-via-wireless-debugging) on the Phone, including its local pairing and Start steps. This backend needs Shizuku running with Android shell UID 2000, the same identity required by `ShellMain`.
3. Open EyeProjector host and tap **Authorize EyeProjector**. Allow its Shizuku request. A prior persistent denial is recovered through Shizuku's Authorized applications panel.
4. For this issue's pre-pairing slice, provision the existing private peer fixture into EyeProjector host's own `files/session/`: `identity.p12`, `password`, `peer.crt`, and `endpoint.properties`. Follow the RUNBOOK private-identity and endpoint requirements. No experimental application's private state is copied or modified. Provisioning is a development step; subsequent pairing work supplies this identity material.
5. Tap **Start**. “Host is ready” means the configured listener actually bound. Usable projection requires the Glasses' authenticated connection and separate video/input observations. Tap **Stop host** to release the bridge and its owned shell service.

After reboot, Shizuku may need its wireless debugging Start procedure again. Return to EyeProjector, restore authorization if necessary, and tap Start for a fresh session. There is no boot receiver or automatic reboot recovery claim. Missing installation, unavailable/dead Shizuku, denied authorization, unsupported Shizuku, wrong execution UID, missing private fixture and backend failure have separate recovery messages or setup status.

These are the implemented/manual recovery instructions. Actual Phone compatibility, steps observed on the assigned device and reboot recovery must be recorded in the reserved-device evidence; source inspection and JVM tests do not demonstrate them.

## Ownership and integration

`HostBootstrap.get(context)` is app scoped. `start()`, `stop()`, `stopBeforeForget(complete)`, `observe(callback)`, `removeObserver(callback)` and `refresh()` are the integration hooks for the Phone panel and later widget. Observers run on the main thread; remove Activity observers on stop. Shizuku setup and backend state are separate fields. The app does not silently start projection when reopening. A matching installed revision reattaches to the fixed service tag. A mismatched peek revision is discarded before any service call; version-aware binding replaces the older loaded code. An accepted bind without a callback is detached locally after the existing eight-second attachment interval and retried through the same tag. Abandoned callbacks cannot affect the current attachment. This attachment recovery neither resets an active projection nor adds a host lifetime limit.

`ShizukuHostService` is a Shizuku UserService with a fixed Binder API. It calls the existing `Bridge` beside `ShellMain`, with no media-loop or protocol edits. One lifecycle owns bridge creation, the readiness callback, cancellation during construction, closure and completion. Repeated Start coalesces. Start during teardown leaves teardown in progress; a fresh Start is available after verified completion. A timed-out close remains unverified and retains the requested Stop through later worker completion, copied-session revocation and exact service death. Status checks continue that same Stop without requiring another user click. Start remains available after completed teardown.

The original fixture remains app owned. When `paired-peer.properties` exists, export it preferentially and omit `peer.crt`; otherwise retain the legacy four-file fixture. Copy `endpoint.properties` verbatim: its Phone bind address differs from the remote Glasses address stored in paired-peer.properties. Pairing supplies port 27183. The UserService creates a unique mode-0700 session under a fixed shell-owned root in `/data/local/tmp` named for the package and installed Android app UID, copies the selected fixed files as mode 0600, and copies the packaged master server there. It does not accept shell commands, executable names or caller-chosen paths. Stop closes the bridge, waits for its worker, and ends that exact UserService process. Stop removes the owned shell root and verifies copy revocation before destroying the service; a replacement service can revoke the preceding service's copies from that same fixed root. It never sweeps other roots. Bridge/stock session cleanup remains the existing implementation.

The build requires `sh tools/fetch-scrcpy.sh`, packages the unmodified scrcpy 5.0.1 server only after its existing SHA-256 check, and the service verifies the same hash before constructing the bridge. A changed installed APK selects a new UserService revision rather than retaining old loaded code. Private `files/bootstrap-status.json` snapshots report actual service PID/UID, installed APK hash/path, server hash and owned session directory for device attribution. Keep paths and fixture identities out of public evidence.

`PairingPanel.beforeForget` uses `beforeForget = { complete -> HostBootstrap.get(activity).stopBeforeForget(complete) }`. The adapter coalesces repeated Forget, prevents a queued Start from overtaking the requested teardown/deletion sequence, and retains pending completion while Shizuku is unavailable or teardown/revocation is unverified. It invokes `complete` on the main thread only after the shell copy has been revoked and service death observed; the panel then deletes app trust. The callback remains pending until it returns. #6 handles late completion after a panel closes.

The Shizuku dependency is pinned to API/provider 13.1.5. Its provider permission and reserved UserService destroy transaction follow the [upstream API contract](https://github.com/RikkaApps/Shizuku-API). The Context constructor requires Shizuku API 13 or later.

## Verification boundary

`OwnedHostLifecycleTest` covers coalesced startup, delayed readiness, Stop during construction, restart after cancellation, startup failure, retained ownership after close failure, backend-initiated shutdown and repeated Stop. The additional `BootstrapSessionFilesTest`, `HostForgetCompletionTest` and `OwnedShellSessionsTest` exercise paired trust without legacy peer.crt, preferred paired selection, verbatim local endpoints, legacy compatibility, missing setup, UI callback ordering/coalescing, and replacement-service copy cleanup. Affected host build, JVM tests and lint must pass on the candidate. They do not prove Shizuku installation, Binder/process behavior on the Phone, shell filesystem access, physical startup/Stop or reboot recovery.

The reserved-Phone checklist is: installed APK hash and actual device identity; Shizuku absent/unavailable and authorization-denied routes; user authorization; Phone UI Start without computer bootstrap; actual shell service PID/UID and listening endpoint; repeated Start/Stop during startup; Activity recreation/reopen; fresh restart; Stop with owned service/listener/stock child teardown; Shizuku death and restoration. Reboot is a separate coordinated operation. Preserve installed experimental apps and private state throughout.

The actual-adapter event regressions also run with JDK 17 after the Gradle checks provision the pinned Kotlin compiler:

```sh
python3 directhost/src/test/adapter/run.py --output-dir /path/to/private/test-output
```

The six scenarios compile unchanged production adapter sources with controlled API doubles. The reviewed 57a4745 source fails the three delayed-Stop/lost-callback/stale-revision regressions; the corrected candidate passes them and the matching-revision, unavailable-setup and Forget-ordering cases. `--source-dir` selects an exact source snapshot for a before/after reproduction. These event models complement the JVM lifecycle tests and retain the same physical verification boundary.
