# Active-session panel-power regressions

The additive local API is `PhoneSessionController.requestPanelOff(callback)` / `panelState(callback)`, forwarded through `HostBootstrap` and Binder transactions 5/6 to the existing owned Bridge/Session. A `null` callback result means transport/decoding was unavailable. `PanelSubmission.UNAVAILABLE` means the attached backend had no active streamed session for that request. Neither starts a session. The widget is a separate issue.

`SUBMITTED` means the complete fixed stock message was written locally. OFF and restoration submission are separate fields; `PanelObservation.UNVERIFIED` remains explicit because stock supplies no display-power acknowledgment. The host status and final session receipt contain `panelPower`; they cannot prove panel darkness, wakefulness, keyguard state, usable projection or exact foldable-panel restoration.

With the JDK 17 and Kotlin dependencies provisioned by the ordinary Gradle checks:

```sh
python3 directhost/src/test/adapter/run.py --panel --output-dir /private/evidence/panel-adapter
python3 directhost/src/test/adapter/run.py --output-dir /private/evidence/bootstrap-adapter
```

The panel harness compiles actual production `ShellMain`, `SessionPanelPower`, `PhoneTouchDispatcher`, `HeldInputRelease`, `OwnedRelease` and `LiveScale`. Reflection only arranges the private Session's Android socket/process edges; it executes the real Session request and close methods, and the real Bridge routing/close. Its nine scenarios cover Host Stop, Disconnect, failure, never-blanked cleanup, partial OFF failure, cancel/restore exceptions, blocked restore writes, retained Bridge evidence and the existing 250 ms cancellation budget. When cancellation remains blocked, actual transport/child cleanup still finishes, the final receipt says restoration PENDING and physical UNVERIFIED, and a later restoration attempt fails on that old closed socket without touching a new Session. No cleanup budget is extended.

The bootstrap harness executes the real callback adapter, including absent attachment, submitted/unverified replies, malformed/dead-service replies and fresh attachment. The ordinary JVM tests also exercise exact stock bytes, partial OFF failure, repeated OFF, status while a write is pending, whole-input serialization and pinned `keep_active=true` / `power_on=false` / `cleanup=true` startup options. Keep-active remains solely the stock session child's responsibility.

These are host checks with modeled Android edges. They do not exercise Binder, SurfaceControl, physical devices, an Awake interval or actual stock child teardown. Those acceptance observations require a Manager-granted Phone/Glasses window and separately recorded human judgments. The prior donor endurance failure and unproved per-panel restoration remain unchanged; no pending donor source was imported.
