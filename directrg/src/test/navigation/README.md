# Contextual navigation verification

Run `python3 directrg/src/test/navigation/run.py --output-dir /private/evidence/navigation`
with JDK 17 and the cached Kotlin 2.2.21 compiler dependencies.

The runner compiles the complete current `ReceiverActivity` navigation, cancellation
and original-activation exit methods, its completed-input initialization, and the
actual `CompletedReceiverInput` consumer and presentation model. It records the
extracted source and compiled inputs. UI widgets, Android calls, the retained session
API and sleep submission are explicit host boundaries.

Scenarios cover projected Home, the Phone's grid-to-panel receipt, contextual Back,
settings-to-main-panel Home, original-activation exit with and without a bound owner,
Lock cancellation/retention, stale completed actions, registration OPEN before its exact
chooser revision is published, local Phone-completed Tap routing, Cancel/Back before app
navigation, Click/Hold waiting for matching completion and old-Session feedback. These checks establish
dispatch and request ordering; they do not establish native gesture delivery,
physical screen sleep, secure keyguard or optical/input usability.

The runner compiles the actual shared message source. The retained Session/owner boundaries
model the OPEN/choice transport; these focused Activity-method checks do not prove Binder or
wire delivery. The separate registration production-composition harness exercises both real
Sessions and the actual owner/runtime with named external Android boundaries.

After the focused Gradle host/protocol checks, run
`python3 directhost/src/test/launch/run.py --navigation-only --output-dir /private/evidence/session-navigation`
for the actual authenticated Session consumer and source-display launcher. This
compiles current Session/launch/input sources and uses the focused Gradle output for
their other host dependencies. Android socket, clock, logging and JSON are doubled.

Navigation and Anchor require matched Phone and Glasses versions from `protocol/Wire.kt`;
the existing handshake rejects incompatible endpoints. `Navigation.HOME` retains its prior unconditional-grid meaning.
The dedicated route uses `Navigation.CONTEXTUAL_HOME`, with `ReceiverInputKind.HOME`
and `LOCK` for completed Phone-origin actions. Source gesture classification and
legacy Function removal belong to their subsequent issues.
