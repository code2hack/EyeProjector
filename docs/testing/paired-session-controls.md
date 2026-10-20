# Paired session controls

Worker: Worker-EyeProjector-8

The Phone app owns one app-scoped `PhoneSessionController`. Its paired control endpoint shares the Phone's existing ephemeral **pairing port**; media still uses the separately stored projection port. UDP discovery moves a remembered address only. Every status, Start, Stop and rendered-state exchange uses the existing exact paired certificate pins, PKIX validation and TLS hostname check.

Each Start has a new epoch. Repeated or competing actions retain one backend owner. Remote commands carry the observed epoch; Stop retires it, so a delayed Start or render report cannot affect a later session. The Receiver consumes an available epoch once. Explicit Disconnect and pause cancel pending Start commands, including a Start whose response is still in flight. A subsequent explicit Start creates fresh resources. There is no session lifetime limit or periodic reset.

The paired control protocol (separate from media framing) carries an admission identity before the first STATUS and returns typed attempt receipts. The Phone atomically retains cancellation intent before dispatching Stop; a recreated controller withholds the canceled owner and reconciles its release even if the Receiver has closed. CANCEL_PENDING confirms retained intent, while CANCELED requires actual release or confirmed absence. Retired identities cannot bind a newer owner. Storage failures are retryable, remain visible, and cannot dispatch an uncommitted backend action. An authoritative STOPPED observation remains truthful even if its retirement write fails.

Listener readiness is `AVAILABLE`, followed by `CONNECTING`; `STREAMING` requires the Receiver's actual render callback. The shell reports session startup and first encoded video separately. After Phone process restart, status stays unknown until the existing shell service is reconciled; an unknown response does not disconnect a live Receiver. The Phone panel and future widget entry points use the same controller.

The connected Glasses panel overlays the existing attached Surface. Entering it cancels projected input and edge ticks before changing context, and uses local pointer geometry. Back returns to the same session/decoder/Surface. Disconnect, pause, Surface loss and failure close projection resources. The shared pairing/control endpoint remains available for a later explicit Start. Issue 16 owns function-button classification; the Activity supplies contextual actions. Issue 15 separately adds continuous held-input cancellation at the preserved common hook and immediately before transport close.

Backend admission remains owned until cleanup completes, including concurrent close callers. Startup allocations are serialized with release. Queued/handshaking TLS sockets are also closed. `last-session.json` records per-phase close durations, last PING/PONG receipt/write timestamps and the last write channel, all on the Phone monotonic clock. These facts do not establish cross-device latency or explain the older reconnect failure by themselves.

## Host verification

Use the RUNBOOK JDK 17 / SDK 35 / two-worker / 2 GiB command for protocol, Phone and Receiver unit tests, both app APKs, Receiver instrumentation APK and the three affected lint tasks. Also run:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 timeout 4m \
  python3 directhost/src/test/adapter/run.py --output-dir "$EVIDENCE/adapter"
```

Focused tests exercise concurrent Start, listener versus rendered readiness, Stop during startup, stale epochs, failed release/retry, actual adapter epoch propagation, local TCP/mTLS admission, suppression of an old available epoch and connected-panel return history. Host tests use synthetic identities/API events and codec doubles. They do not prove Android Binder, hardware video, physical input or optical output.

## Reserved-device verification plan

No device operations are authorized by this document. The Manager must reserve both devices, the Phone projection listener and the owned shell session first. Before installation, preserve actual host/Receiver/test APK bytes and hashes, app-private state and metadata, foreground/lifecycle state, owned process/display/socket facts, and the existing Shizuku/legacy state. Keep failures and restore those exact captured artifacts and private metadata afterward. Do not alter Bluetooth/network/root services or unrelated applications.

Build immutable APKs from the recorded candidate; record source/tree hashes, APK hashes, JUnit and lint output before requesting the device window. Use bounded `adb -s "$PHONE"` and `adb -s "$GLASSES"` commands through the Manager's assigned server; never restart the shared server.

With both ordinary apps paired and the Phone setup available, launch the Receiver test below. Its `probe-issue8-panel-01.ready` marker announces that the Receiver is resumed. Invoke **Phone Start** through the actual Phone panel during the existing 15-second startup observation window. `autoStart=true` never invokes Glasses Connect:

```sh
timeout --foreground 90s adb -s "$GLASSES" shell am instrument -w \
  -e operation connected-panel -e autoStart true -e seconds 3 \
  -e runId issue8-panel-01 \
  com.code2hack.eyeprojector.directrg.test/com.code2hack.eyeprojector.directrg.ProjectionInstrumentation
```

Confirm the current instrumentation argument names against its exact candidate before execution. Retrieve its own private report and both bootstrap/session status records with bounded reads. The connected-panel checks call Activity actions synthetically, assert identical session/decoder/Surface references through panel entry/Back, and reject projected tap/scroll in local context. They do not classify a genuine Rokid function gesture.

Then exercise Glasses Connect, Phone Stop, Glasses Disconnect, pause, Surface loss, failure and a fresh reconnect. Record actual child/display/listener/socket release and fresh session identities, with no replayed input. Repeat readiness/status observation after Disconnect and show no automatic reconnection until a new explicit Start. Measure the app-scoped state on both devices; keep Phone and Receiver monotonic clocks separate. Physical function/pad/HID, optical and subjective observations remain deferred until the Owner returns. Full component and assembled acceptance remain separate from source review.
