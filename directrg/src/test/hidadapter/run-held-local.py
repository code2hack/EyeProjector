#!/usr/bin/env python3
"""Actual held/local dispatcher source against explicit host touch-target boundaries."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def balanced_end(source, start, opening, closing):
    depth = 0
    quote = None
    index = start
    while index < len(source):
        if not quote and source.startswith('//', index):
            end = source.find('\n', index)
            index = len(source) if end < 0 else end
            continue
        if not quote and source.startswith('/*', index):
            end = source.find('*/', index + 2)
            if end < 0:
                raise ValueError('Unterminated comment')
            index = end + 2
            continue
        char = source[index]
        if quote:
            if char == '\\':
                index += 2
                continue
            if char == quote:
                quote = None
        elif char in '\"\'':
            quote = char
        elif char == opening:
            depth += 1
        elif char == closing:
            depth -= 1
            if depth == 0:
                return index + 1
        index += 1
    raise ValueError(f'Unterminated {opening}')


def unique_start(source, marker):
    matches = list(re.finditer(marker, source))
    if len(matches) != 1:
        raise ValueError(f'Expected exactly one {marker}: got {len(matches)}')
    return matches[0].start()


def initializer(source, variable, type_name, trailing=False):
    start = unique_start(source, rf'\b{variable}\s*=\s*{type_name}\(')
    opening = source.index('(', start)
    end = balanced_end(source, opening, '(', ')')
    if trailing:
        opening = source.index('{', end)
        end = balanced_end(source, opening, '{', '}')
    return source[start:end]


def method(source, name):
    start = unique_start(source, rf'private fun {name}\(')
    opening = source.index('{', start)
    return source[start:balanced_end(source, opening, '{', '}')]


def extract_driver(source, output):
    blocks = {'heldInitializer': initializer(source, 'heldInput', 'HeldPointerDispatcher', trailing=True),
              'hidInitializer': initializer(source, 'hidInput', 'HidKeyInput')}
    for name in ('dispatchPadAction', 'cancelInput', 'schedulePadConfirmation', 'remoteTap', 'remoteScroll'):
        blocks[name] = method(source, name)
    generated = output / 'ExtractedHidIngress.kt'
    generated.write_text('''package com.code2hack.eyeprojector.directrg
import android.os.SystemClock
import android.util.Log
import android.view.View
import com.code2hack.eyeprojector.protocol.Touch
import com.code2hack.eyeprojector.protocol.Fit
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.Tap
import com.code2hack.eyeprojector.protocol.Scroll

// Activity ownership/lifecycle/UI and session transport are explicit host boundaries.
// The constructors and methods below are extracted verbatim from --source-activity.
internal class IngressPointer {
    var position = PointerPosition(10f, 20f, true)
    var onRefresh: () -> Unit = {}
    fun inputPosition(): PointerPosition { val callback = onRefresh; onRefresh = {}; callback(); return position }
}
internal class IngressBoundary {
    fun cancel() = Unit
    fun clear() = Unit
    fun stop() = Unit
    fun cancelTouch() = Unit
    fun refresh() = Unit
}
internal class IngressSession {
    val touches = mutableListOf<Touch>()
    val taps = mutableListOf<Tap>()
    val scrolls = mutableListOf<Scroll>()
    var onTouch: (Touch) -> Unit = {}
    var pendingCanceled = 0
    fun touch(touch: Touch, at: Long): Boolean { touches.add(touch); onTouch(touch); return true }
    fun tap(tap: Tap, at: Long): Boolean { taps.add(tap); return true }
    fun scroll(scroll: Scroll, at: Long, headMotion: Boolean): Boolean { scrolls.add(scroll); return true }
    fun cancelPendingInput() { pendingCanceled++ }
}
internal class ExtractedHidIngress(val root: TargetRoot) {
    val bindings = HidBindings({ null }, { true })
    val pointer = IngressPointer()
    val connectionView = View()
    lateinit var gestures: PadGestureRecognizer
    lateinit var localInput: LocalPointerDispatcher
    lateinit var heldInput: HeldPointerDispatcher
    lateinit var heldOwner: HeldInputOwner
    lateinit var hidInput: HidKeyInput
    private lateinit var functionInput: IngressBoundary
    private lateinit var padContact: IngressBoundary
    private lateinit var screen: IngressBoundary
    private lateinit var hidPanel: IngressBoundary
    var session: IngressSession? = null
    var currentGeometry: Geometry? = Geometry(100, 100)
    private val edgeMotion = IngressBoundary()
    private val confirmTap = Runnable {}
    private val edgeTick = Runnable {}
    private var edgeScheduled = false
    private var inputGeneration = 0L
    private var nativeActionsSubmitted = 0L
    private var nativeActionsRejected = 0L
    private var longClassifications = 0L
    private var tapDelaySamples = 0L
    private var tapDelayTotalMs = 0L
    private var tapDelayMaxMs = 0L
    var available = true
    val projectionContext: Boolean get() = session != null && connectionView.visibility != View.VISIBLE
    private fun usable() = available
    private fun projectedInput() = session != null && projectionContext
    private fun contextualBack() { cancelInput() }
    private fun updateEdgeScroll() = Unit
    fun cancelAll() = cancelInput()
    fun dispatch(action: PadGestureRecognizer.Action) = dispatchPadAction(action)
    init {
        functionInput = IngressBoundary(); padContact = IngressBoundary(); screen = IngressBoundary(); hidPanel = IngressBoundary()
        localInput = LocalPointerDispatcher(root)
        gestures = PadGestureRecognizer(500, 800, ::dispatchPadAction)
        val hidBindings = bindings
        val functionNavigation = FunctionNavigation({}, {}, {}, {})
''' + blocks['heldInitializer'] + '''
        heldOwner = HeldInputOwner(heldInput)
''' + blocks['hidInitializer'] + '''
    }
''' + '\n'.join(blocks[name] for name in ('dispatchPadAction', 'cancelInput', 'schedulePadConfirmation', 'remoteTap', 'remoteScroll')) + '\n}\n')
    return generated, blocks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--source-activity', type=Path, help='Extract exact proposed or integrated Activity HID/held constructors and methods')
    args = parser.parse_args()
    root = args.source_dir.resolve()
    out = args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=True)
    suite = Path(__file__).resolve().parent
    actual = root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
    protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
    sources = [actual / name for name in ('HeldInputOwner.kt', 'HeldPointerDispatcher.kt', 'LocalPointerDispatcher.kt', 'PadGestureRecognizer.kt', 'HeadPointerModel.kt', 'HeadOrientation.kt')]
    sources += [protocol / name for name in ('Touch.kt', 'Wire.kt')]
    sources += sorted((suite / 'helddoubles').glob('*.kt')) + [suite / 'HeldLocalScenarios.kt']
    unit = root / 'directrg/src/test/kotlin/com/code2hack/eyeprojector/directrg'
    sources += [unit / name for name in ('HeldInputOwnerTest.kt', 'HeldPointerDispatcherTest.kt')]
    blocks = None
    activity_bytes = None
    if args.source_activity:
        activity_bytes = args.source_activity.resolve().read_bytes()
        generated, blocks = extract_driver(activity_bytes.decode(), out)
        sources += [actual / name for name in ('HidBindings.kt', 'HidKeyInput.kt', 'FunctionGestureRecognizer.kt')]
        sources += [generated, suite / 'HidIngressScenarios.kt']
    cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
    dependencies = [('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'),
                    ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'),
                    ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'),
                    ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                    ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
                    ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
                    ('org.jetbrains', 'annotations', '13.0'),
                    ('junit', 'junit', '4.13.2'), ('org.hamcrest', 'hamcrest-core', '1.3')]
    jars = []
    for group, artifact, version in dependencies:
        found = list((cache / group / artifact / version).glob(f'*/{artifact}-{version}.jar'))
        if len(found) != 1:
            parser.error(f'Missing pinned compiler dependency {artifact}:{version}')
        jars.extend(found)
    java = str(Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-17-openjdk-amd64')) / 'bin/java')
    snapshot_sources = []
    manifest_sources = []
    inputs = sources + [Path(__file__).resolve()] + ([args.source_activity.resolve()] if args.source_activity else [])
    for path in inputs:
        try:
            relative = path.relative_to(root)
        except ValueError:
            relative = Path('external') / path.name
        snapshot = out / 'inputs' / relative
        snapshot.parent.mkdir(parents=True, exist_ok=True)
        if args.source_activity and path == args.source_activity.resolve():
            snapshot.write_bytes(activity_bytes)
        else:
            shutil.copyfile(path, snapshot)
        manifest_sources.append({'path': str(path), 'snapshot': str(snapshot), 'sha256': digest(snapshot)})
        if path in sources:
            snapshot_sources.append(snapshot)
    classpath = os.pathsep.join(map(str, jars))
    jar = out / 'held-local.jar'
    commands = [[java, '-Xmx512m', '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                 '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath, '-d', str(jar)] + list(map(str, snapshot_sources)),
                [java, '-Xmx128m', '-cp', str(jar) + os.pathsep + classpath, 'com.code2hack.eyeprojector.directrg.HeldLocalScenariosKt'],
                [java, '-Xmx128m', '-cp', str(jar) + os.pathsep + classpath, 'org.junit.runner.JUnitCore', 'com.code2hack.eyeprojector.directrg.HeldInputOwnerTest', 'com.code2hack.eyeprojector.directrg.HeldPointerDispatcherTest']]
    if args.source_activity:
        commands.append([java, '-Xmx128m', '-cp', str(jar) + os.pathsep + classpath, 'com.code2hack.eyeprojector.directrg.HidIngressScenarios'])
    manifest = {'scope': 'Actual held/owner/local/recognizer/model/protocol source on host Android API and ViewGroup target-attachment doubles; no device or Android runtime proof',
                'sources': manifest_sources, 'activityBlocks': blocks,
                'compiler': [{'path': str(p), 'sha256': digest(p)} for p in jars]}
    (out / 'source-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    result = {'compileExit': None, 'scenariosExit': None, 'junitExit': None, 'commands': commands}
    operations = [('compileExit', commands[0], 60, 'compile.log'), ('scenariosExit', commands[1], 15, 'scenarios.log'), ('junitExit', commands[2], 15, 'junit.log')]
    if args.source_activity:
        result['ingressExit'] = None
        operations.append(('ingressExit', commands[3], 15, 'ingress.log'))
    try:
        for key, command, limit, logname in operations:
            with (out / logname).open('w') as log:
                proc = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=limit)
            result[key] = proc.returncode
            print((out / logname).read_text(), end='')
            if proc.returncode:
                break
    except subprocess.TimeoutExpired as error:
        result['timeoutSeconds'] = error.timeout
    result['artifacts'] = [{'path': p.name, 'sha256': digest(p)} for p in sorted(out.iterdir()) if p.is_file() and p.name != 'result.json']
    (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    return 0 if all(result[key] == 0 for key, _, _, _ in operations) else 1


if __name__ == '__main__':
    raise SystemExit(main())
