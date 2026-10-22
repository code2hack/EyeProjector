# Actual common-launch harness

This suite compiles production
`HostActivity`, `AppScaleActivity`, `PhoneSessionController`, `SessionController`, `SessionAttemptStore`,
`ProjectedHomeActivity`, the feature coordinator/store/preferences, and their actual
context/display/scale wire types. `PhoneSessionController` is never replaced by a double.

For the combined widget/scale production controller, `PhoneWidgetUpdates.attach`
is an explicit host attachment boundary that records the real controller call.
It does not subscribe widget observers, render launcher views or deliver widget
actions. The controlled bootstrap also supplies the context-bound panel-off
facade used by the actual controller, returning null without claiming panel
admission or completion. Actual widget/controller/scale behavior belongs to the
separate widget composition suite; this closure retains every original common
and focused scale regression unchanged.

The controlled bootstrap records pending launch/display/read/request calls and delivers
their callbacks explicitly. Android Activity/view/preferences APIs and pairing/network
boundaries are inert doubles. Scenarios exercise both ordinary Phone launch overloads,
the new panel-target entry, actual projected Home callback wiring, package restoration,
same-session ordering, replacement, Home before the first token poll, Stop during launch,
foreground change, and close/reattach without Stop.

The additive Phone Activity scenarios reflectively invoke actual `launchChosenApp`
and `scaleChosenApp` after actual `onCreate`/`onStart`. Actual session restore plus
`SessionController.receiverRendered` establishes STREAMING before the Host observer
binds connected callbacks. They cover tap A to Scale B and Scale A to tap B with
A's stale null completion after B selection, plus Host Activity recreation retaining
the Phone owner and projection. Widgets, menu rendering and platform Activity
transitions remain inert boundaries. Delayed Handler ticks are retained but
never run automatically, preventing a synthetic event-loop recursion.

Scale Activity scenarios compile its actual lifecycle and production intent factory.
An inert `ScalePanelView` records render calls and invokes the callbacks supplied by
the real Activity. Scenarios cover new onStart before old onStop, close invoking
finish without Stop, stale/mismatched target intents, and a modeled new Phone owner
with no in-memory target receiving a retained old intent. The owner-loss scenario
does not terminate an OS process. No scenario claims actual View formatting, touch or
pinch recognition, platform Activity restoration, or physical-device behavior.

The exercised APIs are `PhoneSessionController.appScales`,
`launchAppForScale(packageName, expected, complete): AppScaleTarget`, and explicit
context overloads on the bootstrap scale/display facade. The feature backend is expected
to observe display foreground before scale reads/writes.

After the assigned host build slot is available, with RUNBOOK JDK 17 configured:

```sh
python3 run.py --repo /path/to/exact/worktree --output-dir /path/to/fresh/private/output
```

Use a fresh output directory for every attempt. The runner retains compile/scenario
logs, source/runner/tool-jar hashes, exact commands, output artifact hash and a result
manifest even when compilation, execution or a bounded invocation fails. It detects
source changes during the run so results cannot be accepted against a moving snapshot.

This is host model evidence. It does not establish actual Android Binder admission,
Activity/manifest/menu behavior, durable Android preference I/O, physical video/input,
or wearer observations. Keep the existing actual bootstrap/server suites and compiled
Phone instrumentation as complementary evidence.

P2 remediation regressions preserve attribution to Reviewer-EyeProjector-12's
independent EP12-R1/EP12-R2 findings. They run both failed command statuses with
matching display foreground, identity/foreground rejection, Close before onStop
with held automatic poll or foreground callbacks, dispatched-versus-unsent writes,
and recreated attachment ownership. The focused entrypoint continues after each
case failure and exits unsuccessfully if any case fails, so original RED evidence
can show both restoration statuses and exact Close intervals in one run.

```sh
python3 run.py --repo /path/to/exact/worktree --output-dir /path/to/fresh/private/output --p2-only
```

The default entrypoint also includes these P2 cases. Callback success remains
request-return/controlled observation evidence, with observed geometry retained
independently of the requested value.
