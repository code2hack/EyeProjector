#!/usr/bin/env python3
"""Actual HID joins, Activity destinations and ProjectionSession navigation writer on host boundaries."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output-dir', type=Path, required=True)
a = p.parse_args()
root = Path(__file__).resolve().parents[4]
out = a.output_dir.resolve()
out.mkdir(parents=True, exist_ok=False)
suite = Path(__file__).resolve().parent
glass = root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
host = root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
helper = root / 'directrg/src/test/hidadapter/run-held-local.py'
spec = importlib.util.spec_from_file_location('extract', helper)
extract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(extract)
block_spec = importlib.util.spec_from_file_location('blocks', root / 'directhost/src/test/hid/run.py')
blocks = importlib.util.module_from_spec(block_spec)
block_spec.loader.exec_module(blocks)
activity = (glass / 'ReceiverActivity.kt').read_text()
names = ['dispatchNavigation', 'completedNavigation', 'contextualHome', 'contextualBack', 'remoteBack',
         'sleepGlasses', 'showControlPanel', 'cancelInput', 'exitToLauncher', 'cancelOriginalOnExit', 'finishProjection']
methods = {name: blocks.block(activity, rf'^    (?:private|internal) fun {name}\(') for name in names}
getter = next(line for line in activity.splitlines() if line.startswith('    internal val projectionContext:'))
context = next(line for line in activity.splitlines() if line.startswith('    private fun functionContext('))
completed = extract.initializer(activity, 'completedInput', 'CompletedReceiverInput')
hid = extract.initializer(activity, 'hidInput', 'HidKeyInput')
template = (suite.parent / 'navigation/ActivityBoundary.kt').read_text()
template = template.replace('lateinit var hidInput: Node', 'lateinit var hidInput: HidKeyInput')
template = template.replace('hidPanel = Node(); hidInput = Node();', 'hidPanel = Node();')
template = template.replace('    val pointer = Node()', '    val hidBindings = HidBindings({ null }, { true })\n    val pointer = Node()')
template = template.replace('// ACTUAL_COMPLETED_INITIALIZER', completed + '\n' + hid)
template = template.replace('// ACTUAL_CONTEXT_GETTER', getter + '\n' + context)
template = template.replace('// ACTUAL_METHODS', '\n'.join(methods.values()))
template = template.replace('    private fun usable()', '''    private fun hoveredHidAction(trace: HidDecisionTrace.Event?): HidAction? = null
    private fun captureFunction(expected: Any): Any? = null
    private fun dispatchFunction(source: FunctionSource, gesture: FunctionGestureRecognizer.Gesture) { error("Unexpected Function") }
    private fun schedulePadConfirmation() = Unit
    private fun updateEdgeScroll() = Unit
    private fun dispatchPadAction(action: PadGestureRecognizer.Action) { error("Unexpected spatial input") }
    private fun usable()''')
activity_fixture = out / 'generated/ActivityNavigationWiring.kt'
activity_fixture.parent.mkdir(parents=True)
activity_fixture.write_text(template)
boundaries = (suite.parent / 'navigation/Boundaries.kt').read_text()
boundaries = re.sub(r'^internal data class PointerPosition.*\n', '', boundaries, flags=re.M)
start = boundaries.index('internal class HeldPointerDispatcher {')
end = boundaries.index('internal object View', start)
boundaries = boundaries[:start] + boundaries[end:]
boundaries = boundaries.replace('fun uptimeMillis() = 100L', 'fun uptimeMillis() = android.os.SystemClock.uptimeMillis()')
boundaries = boundaries.replace('    fun clear() = Unit', '''    fun clear() = Unit
    var positionReads = 0
    fun inputPosition(): PointerPosition { positionReads++; return PointerPosition(-500f, 10000f, false) }
    fun accept(owner: Any, event: PadGestureRecognizer.Event) = Unit
    fun cancel(owner: Any, at: Long) = cancel()
    fun refresh() = Unit''')
boundaries = boundaries.replace('    fun cancelPendingInput() { Calls.events.add("session-cancel") }',
    '    val transport = SessionNavigationTransport()\n    fun cancelPendingInput() { transport.cancel(); Calls.events.add("session-cancel") }')
boundaries = boundaries.replace('    fun navigate(action: Navigation) { navigations.add(action); Calls.events.add("navigate") }',
    '    fun navigate(action: Navigation) { navigations.add(action); Calls.events.add("navigate"); check(transport.navigate(action)) }')
boundary_fixture = out / 'generated/Boundaries.kt'
boundary_fixture.write_text(boundaries)
session = (glass / 'ProjectionSession.kt').read_text()
transport_methods = {name: blocks.block(session, rf'^    (?:private )?fun {name}\(') for name in ['navigate', 'input']}
transport = '''package com.code2hack.eyeprojector.directrg
import com.code2hack.eyeprojector.protocol.*
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
internal class SessionNavigationTransport : AutoCloseable {
    val running = AtomicBoolean(true)
    var geometry: Geometry? = Geometry(480, 640)
    var readyForTouch = true
    var inputRejected = 0L
    var touches = 0L
    var scrolls = 0L
    var navigations = 0L
    val frames = LinkedBlockingQueue<Frame>()
    val errors = LinkedBlockingQueue<Exception>()
    val controls = OrderedControlDispatcher { errors.add(it) }
    private fun send(frame: Frame) {
        val bytes = ByteArrayOutputStream()
        Wire.writeFrame(DataOutputStream(bytes), frame)
        frames.add(Wire.readFrame(DataInputStream(bytes.toByteArray().inputStream())))
    }
    fun cancel() = controls.cancelPending()
    fun awaitNavigation(): Navigation {
        val frame = checkNotNull(frames.poll(2, TimeUnit.SECONDS)) { "No navigation frame; errors=$errors" }
        check(frame.channel == Wire.NAVIGATE)
        return Navigate.from(frame.payload).action
    }
    override fun close() { running.set(false); controls.close() }
// ACTUAL_TRANSPORT_METHODS
}
'''.replace('// ACTUAL_TRANSPORT_METHODS', '\n'.join(transport_methods.values()))
transport_fixture = out / 'generated/SessionNavigationTransport.kt'
transport_fixture.write_text(transport)
files = [activity_fixture, boundary_fixture, transport_fixture, suite / 'Scenarios.kt']
files += [glass / (n + '.kt') for n in ['HidKeyInput', 'AndroidHidInput', 'HidBindings', 'HidDecisionTrace',
    'FunctionGestureRecognizer', 'ContextualNavigation', 'CompletedReceiverInput', 'SessionPresentation',
    'HeadPointerModel', 'HeadOrientation', 'PadGestureRecognizer', 'HeldPointerDispatcher', 'OrderedControlDispatcher']]
files += [protocol / (n + '.kt') for n in ['Wire', 'PhoneInput', 'Touch', 'ReceiverUsability', 'ActivationControl',
    'CameraFeed', 'FunctionIntent', 'BackHomeGestureRecognizer']]
files += [host / n for n in ['hid/PhoneHidController.kt', 'hid/PhoneHidBindings.kt', 'input/PhoneHidRoute.kt',
    'input/PhoneInputMerger.kt', 'activation/PhoneHidActivation.kt', 'activation/PhoneHidDisposition.kt']]
files += [root / 'directhost/src/test/hidnavigation/ActivationBoundary.kt']
files += [f for f in sorted((suite.parent / 'hidframework/doubles').glob('*.kt')) if f.name not in ['Handler.kt', 'SystemClock.kt']]
files += [suite.parent / 'functionadapter/doubles/Clock.kt']
cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
deps = ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21', 'org.jetbrains.kotlin/kotlin-stdlib/2.2.21',
        'org.jetbrains.kotlin/kotlin-script-runtime/2.2.21', 'org.jetbrains.kotlin/kotlin-reflect/1.6.10',
        'org.jetbrains/annotations/13.0', 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0']
jars = [f for d in deps for f in sorted((cache / d).glob('*/*.jar'))]
sha = lambda f: hashlib.sha256(f.read_bytes()).hexdigest()
inputs = []
for index, f in enumerate(files):
    snapshot = out / 'source' / str(index) / f.name
    snapshot.parent.mkdir(parents=True, exist_ok=True)
    snapshot.write_bytes(f.read_bytes())
    inputs.append({'path': str(f), 'snapshot': str(snapshot), 'sha256': sha(snapshot)})
cp = os.pathsep.join(map(str, jars))
java = str(Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-17-openjdk-amd64')) / 'bin/java')
jar = out / 'hid-destinations.jar'
commands = {'compile': [java, '-Xmx1g', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
    '-jvm-target', '17', '-classpath', cp, '-d', str(jar)] + [f['snapshot'] for f in inputs],
    'run': [java, '-cp', str(jar) + os.pathsep + cp, 'com.code2hack.eyeprojector.directrg.ScenariosKt']}
record = {'scope': 'Actual HID constructor/callback, both production HID adapters and Phone merger/controller/route, Activity navigation/completed destinations and ProjectionSession navigation/input writer bodies with actual ordered dispatcher and wire codecs. Android/UI/session lifetime, sleep, Function capture and Phone stamped admission are named boundaries; no ShellMain, network, framework or physical claim.',
    'runnerSha256': sha(Path(__file__)), 'activitySourceSha256': sha(glass / 'ReceiverActivity.kt'),
    'activityMethods': methods, 'contextGetter': getter, 'functionContext': context, 'hidInitializer': hid, 'completedInitializer': completed,
    'transportSourceSha256': sha(glass / 'ProjectionSession.kt'), 'transportMethods': transport_methods,
    'inputs': inputs, 'dependencies': [{'path': str(f), 'sha256': sha(f)} for f in jars], 'commands': commands}
record['generationInputs'] = []
for index, f in enumerate([glass / 'ReceiverActivity.kt', glass / 'ProjectionSession.kt', helper,
        root / 'directhost/src/test/hid/run.py', suite.parent / 'navigation/ActivityBoundary.kt',
        suite.parent / 'navigation/Boundaries.kt', Path(__file__), suite / 'README.md']):
    snapshot = out / 'generation-inputs' / str(index) / f.name
    snapshot.parent.mkdir(parents=True, exist_ok=True)
    snapshot.write_bytes(f.read_bytes())
    record['generationInputs'].append({'path': str(f), 'snapshot': str(snapshot), 'sha256': sha(snapshot)})
try:
    for phase, timeout in [('compile', 90), ('run', 25)]:
        with (out / (phase + '.log')).open('w') as log:
            r = subprocess.run(commands[phase], stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
        record[phase + 'Exit'] = r.returncode
        print((out / (phase + '.log')).read_text(), end='')
        if r.returncode: break
finally:
    (out / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
raise SystemExit(0 if record.get('compileExit') == record.get('runExit') == 0 else 1)
