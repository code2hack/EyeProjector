#!/usr/bin/env python3
"""Exact Activity callback/lifecycle/destinations and held owner; named Session/UI boundaries."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output-dir', type=Path, required=True)
    a = p.parse_args()
    suite = Path(__file__).resolve().parent
    root = suite.parents[3]
    actual = root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
    baseline = suite.parent / 'navigation'
    out = a.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=False)
    helper = root / 'directhost/src/test/hid/run.py'
    spec = importlib.util.spec_from_file_location('blocks', helper)
    blocks = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(blocks)
    activity = (actual / 'ReceiverActivity.kt').read_text()
    names = ['dispatchNavigation', 'completedNavigation', 'contextualHome', 'contextualBack', 'remoteBack',
             'sleepGlasses', 'showControlPanel', 'cancelInput', 'exitToLauncher', 'cancelOriginalOnExit',
             'finishProjection', 'cancelInputState', 'chooseAnchorRegistration', 'cancelAnchorRegistration',
             'updatePointerBounds', 'remoteTap', 'remoteScroll', 'anchorMessage', 'currentRegistrationIntent', 'applyReceiverDemand',
             'onResume', 'onWindowFocusChanged', 'onPause', 'onDestroy', 'currentNavigation', 'cancelInputRevision']
    extracted = {n: blocks.block(activity, rf'^    (?:private|internal|override) fun {n}\(') for n in names}
    extracted['NavigationContext'] = blocks.block(activity, r'^    private data class NavigationContext\(')
    extracted['navigationContext'] = re.search(r'^    private fun navigationContext\(\).*?anchorRegistration.offer\)', activity, re.M | re.S).group()
    predicates = '\n'.join(re.search(rf'^    private fun {n}\(.*$', activity, re.M).group()
                           for n in ['registrationActive', 'localInputVisible', 'projectedInput', 'functionContext', 'usable'])
    initializer = re.search(r'^        nativeSwipeInput = NativeSwipeNavigationInput\(this\) \{.*?^        }', activity, re.M | re.S).group()
    fixture = (baseline / 'ActivityBoundary.kt').read_text()
    fixture = fixture.replace('internal class ActivityNavigationWiring {', 'internal class ActivityNavigationWiring : SwipeLifecycleBoundary() {')
    fixture = fixture.replace('    var focus = true\n', '').replace('    private fun usable() = resumed && focus\n', '')
    fixture = fixture.replace('    val root = Node()', '    lateinit var root: Node')
    fixture = fixture.replace('    val longTouch = Node()', '    lateinit var longTouch: Node')
    fixture = fixture.replace('    val serviceBinder = Binder()', '    var serviceBinder: Binder? = Binder()')
    fixture = fixture.replace('    private fun publishHeadPointer() { Calls.events.add("publish") }',
                              '    val publishedSessions = mutableListOf<Session?>()\n    private fun publishHeadPointer() { publishedSessions.add(session); Calls.events.add("publish") }')
    fixture = fixture.replace('lateinit var heldOwner: Node', 'lateinit var heldOwner: HeldInputOwner').replace('lateinit var heldInput: Node', 'lateinit var heldInput: HeldPointerDispatcher')
    fixture = fixture.replace('heldOwner = Node(); heldInput = Node()', '''heldInput = HeldPointerDispatcher({ PointerPosition(200f, 100f, true) }, { { e -> onHeldEvent(e) } }); heldOwner = HeldInputOwner(heldInput)''')
    extra = '''
    lateinit var nativeSwipeInput: NativeSwipeNavigationInput
    val anchorHeldIndicator = Node()
    val pairingPanel = Node()
    val cameraPermissionHost = Any()
    val cameraPermissionRequests = mutableMapOf<Int, Any>()
    val serviceConnection = Any()
    var bound = false
    var isFinishing = false
    var onHeldEvent: (HeldPointerDispatcher.Event) -> Boolean = { true }
    fun down() = heldOwner.acceptNative(PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT,
        PadGestureRecognizer.Phase.DOWN, 100, 100, position = PointerPosition(200f, 100f, true)))
    private fun hasWindowFocus() = focus
    private fun attachCameraPermissionHost() = Unit
    private fun unbindService(connection: Any) = Unit
'''
    fixture = fixture.replace('    init {', extra + '\n    init {\n        root = Node(); longTouch = Node()', 1)
    fixture = fixture.replace('// ACTUAL_ANCHOR_INITIALIZER', blocks.block(activity, r'^        anchorRegistration = AnchorRegistrationController '))
    fixture = fixture.replace('// ACTUAL_COMPLETED_INITIALIZER', re.search(r'^        completedInput = CompletedReceiverInput\(.*?::completedNavigation\)', activity, re.M | re.S).group() + '\n' + initializer + '\n        nativeSwipeInput.start()')
    fixture = fixture.replace('// ACTUAL_CONTEXT_GETTER', re.search(r'^    internal val projectionContext:.*$', activity, re.M).group())
    fixture = fixture.replace('// ACTUAL_PREDICATES', predicates).replace('// ACTUAL_METHODS', '\n'.join(extracted.values()))
    fixture += '''
internal open class SwipeLifecycleBoundary : android.content.Context() {
    var focus = true
    open fun onResume() = Unit
    open fun onWindowFocusChanged(hasFocus: Boolean) { focus = hasFocus }
    open fun onPause() = Unit
    open fun onDestroy() = Unit
}
'''
    boundary = (baseline / 'Boundaries.kt').read_text()
    boundary = re.sub(r'internal class HeldPointerDispatcher \{.*?^}', '', boundary, flags=re.M | re.S)
    boundary = boundary.replace('    fun stop() {', '    fun start() = Unit\n    fun close() = Unit\n    fun stop() {')
    boundary = boundary.replace('    fun disconnect(scope:', '    fun detach(view: Any) = Unit\n    fun disconnect(scope:')
    boundary = boundary.replace('internal class Binder {', 'internal class Binder { fun detachCameraPermissionHost(host: Any) = Unit;')
    boundary = boundary.replace('    fun cancelPendingInput() {', '    var pendingCancellations = 0\n    fun cancelPendingInput() { pendingCancellations++;')
    boundary = boundary.replace('    var pendingCancellations = 0', '    val pendingWork = mutableListOf<String>()\n    var pendingCancellations = 0')
    boundary = boundary.replace('pendingCancellations++;', 'pendingCancellations++; pendingWork.clear();')
    boundary += '\ninternal typealias ReceiverSessionOwner = Owner\n'
    boundary = boundary.replace('    var revision = 0L', '    val inputRevision get() = revision\n    var revision = 0L')
    boundary = boundary.replace('    fun chooseAnchorRegistration(offer:', '    var onChoice: (AnchorChoice) -> Unit = {}\n    fun chooseAnchorRegistration(offer:')
    boundary = boundary.replace('        choices.add(choice);', '        onChoice(choice); choices.add(choice);')
    generated = out / 'ActivityNavigationWiring.kt'; generated.write_text(fixture)
    generated_boundary = out / 'Boundaries.kt'; generated_boundary.write_text(boundary)
    protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
    files = [generated, generated_boundary, suite / 'ActivityScenarios.kt', baseline / 'Scenarios.kt'] + sorted((suite / 'doubles').glob('*.kt'))
    files += [actual / (n + '.kt') for n in ['NativeSwipeNavigationInput', 'ContextualNavigation', 'CompletedReceiverInput',
              'SessionPresentation', 'AnchorRegistrationController', 'HeldInputOwner', 'HeldPointerDispatcher', 'PadGestureRecognizer']]
    files += [protocol / n for n in ['Wire.kt', 'PhoneInput.kt', 'Touch.kt', 'LayoutScale.kt', 'ReceiverUsability.kt',
                                    'ActivationControl.kt', 'CameraFeed.kt', 'FunctionIntent.kt', 'AnchorMessage.kt']]
    sha = lambda f: hashlib.sha256(Path(f).read_bytes()).hexdigest()
    cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
    deps = ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21', 'org.jetbrains.kotlin/kotlin-stdlib/2.2.21',
            'org.jetbrains.kotlin/kotlin-script-runtime/2.2.21', 'org.jetbrains.kotlin/kotlin-reflect/1.6.10',
            'org.jetbrains/annotations/13.0', 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0']
    jars = [j for d in deps for j in sorted((cache / d).rglob('*.jar'))]
    assert len(jars) == len(deps)
    cp = os.pathsep.join(map(str, jars))
    inputs = []
    for index, f in enumerate(files):
        copy = out / 'sources' / str(index) / f.name; copy.parent.mkdir(parents=True, exist_ok=True); copy.write_bytes(f.read_bytes())
        inputs.append({'path': str(f), 'snapshot': str(copy), 'sha256': sha(copy)})
    (out / 'ReceiverActivity-source.kt').write_text(activity)
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
    jar = out / 'activity-source.jar'
    commands = {'compile': [java, '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                            '-jvm-target', '17', '-classpath', cp, '-d', str(jar)] + [x['snapshot'] for x in inputs],
                'run': [java, '-Xlog:class+load=info:file=' + str(out / 'class-load.log'), '-cp', str(jar) + os.pathsep + cp,
                        'com.code2hack.eyeprojector.directrg.ActivityScenariosKt'],
                'regression': [java, '-cp', str(jar) + os.pathsep + cp, 'com.code2hack.eyeprojector.directrg.ScenariosKt']}
    record = {'scope': __doc__, 'sourceActivitySha256': sha(actual / 'ReceiverActivity.kt'), 'actualMethods': extracted,
              'nativeInitializer': initializer, 'actualPredicates': predicates, 'inputs': inputs, 'commands': commands,
              'dependencies': [{'path': str(j), 'sha256': sha(j)} for j in jars],
              'readOnlyHelpers': [{'path': str(f), 'sha256': sha(f)} for f in [helper, baseline / 'ActivityBoundary.kt', baseline / 'Boundaries.kt']],
              'runnerSha256': sha(__file__)}
    try:
        for phase, limit in [('compile', 60), ('run', 20), ('regression', 20)]:
            with (out / (phase + '.log')).open('w') as log:
                result = subprocess.run(commands[phase], stdout=log, stderr=subprocess.STDOUT, timeout=limit)
            record[phase + 'Exit'] = result.returncode
            print((out / (phase + '.log')).read_text(), end='')
            if result.returncode:
                return result.returncode
        lines = (out / 'class-load.log').read_text().splitlines()
        required = ['NativeSwipeNavigationInput', 'ActivityNavigationWiring', 'ActivityNavigationWiring$NavigationContext', 'HeldInputOwner', 'HeldPointerDispatcher']
        record['sourceClassLoads'] = {n: [x for x in lines if '] com.code2hack.eyeprojector.directrg.' + n + ' source:' in x] for n in required}
        record['sourceClassesVerified'] = all(len(v) == 1 and ' source: file:' + str(jar) in v[0] for v in record['sourceClassLoads'].values())
        return 0 if record['sourceClassesVerified'] else 1
    finally:
        (out / 'result.json').write_text(json.dumps(record, indent=2) + '\n')


if __name__ == '__main__':
    raise SystemExit(main())
