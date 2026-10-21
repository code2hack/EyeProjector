#!/usr/bin/env python3
"""Production Function adapter plus exact Activity blocks on host API boundaries."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    root, out = args.source_dir.resolve(), args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=True)
    suite = root / 'directrg/src/test/functionadapter'
    helper = root / 'directrg/src/test/widgetdiagnostics/run.py'
    spec = importlib.util.spec_from_file_location('source_blocks', helper)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    actual = root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
    activity = actual / 'ReceiverActivity.kt'
    source = activity.read_text()
    init = re.search(r'        val functionNavigation = FunctionNavigation\(.*?\n        \)\n', source, re.S)
    if not init:
        parser.error('Missing unique Function initialization in actual Activity')
    names = ['remoteBack', 'showControlPanel', 'projectionHome', 'contextualBack', 'exitToLauncher',
             'cancelInput', 'disconnect', 'onResume', 'onWindowFocusChanged', 'onPause', 'onDestroy']
    blocks = {name: module.block(source, rf'\bfun {name}\(') for name in names}
    signatures = {}
    for name in names:
        m = re.search(rf'^\s*((?:(?:private|internal|override) )?fun {name}\([^\n]*?\))\s*\{{', source, re.M)
        if not m:
            parser.error(f'Missing exact method signature: {name}')
        signatures[name] = m[1]
    projection = re.search(r'internal val projectionContext: Boolean get\(\) = ([^\n]+)', source)[1]
    generated = out / 'ActivityFunctionWiring.kt'
    generated.write_text('''package com.code2hack.eyeprojector.directrg
import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import java.io.File

// Methods and initialization below are extracted verbatim from the supplied Activity.
// Boundary types do not model Android navigation, decoder operation or framework dispatch.
internal class ActivityFunctionWiring : Activity() {
    lateinit var functionInput: NativeFunctionInput
    lateinit var gestures: Boundary
    lateinit var heldInput: Boundary
    lateinit var padContact: Boundary
    lateinit var screen: Boundary
    lateinit var localInput: Boundary
    val pointer = Boundary()
    lateinit var longTouch: Boundary
    val pairingPanel = Boundary()
    lateinit var root: Boundary
    val edgeMotion = Boundary()
    val window = Boundary()
    val connectionView = Boundary()
    val connectButton = Boundary()
    val presentation = SessionPresentation()
    val attachment = Attachment()
    var session: Session? = null
    var peerSession: Peer? = null
    var sessionClient: PairedSessionClient? = null
    var resumed = true
    var streaming = false
    var currentGeometry: Any? = null
    var connectRequested = false
    var clientGeneration = 0L
    var edgeScheduled = false
    val confirmTap = Runnable {}
    val edgeTick = Runnable {}
    var resizes = 0
    var observed = 0
    init {
        root = Boundary(); longTouch = Boundary()
        gestures = Boundary(); heldInput = Boundary(); padContact = Boundary()
        screen = Boundary(); localInput = Boundary()
''' + init[0] + '''
    }
    private fun usable() = resumed && hasWindowFocus()
    private fun projectedInput() = streaming && presentation.projectedInput
    private fun resize() { cancelInput(); resizes++ }
    private fun observePeer(peer: Peer) { observed++ }
    private fun showStatus(text: String) {}
    private fun getString(id: Int) = "resource-$id"
    fun invalidate() = cancelInput()
    internal val projectionContext: Boolean get() = ''' + projection + '\n' +
        '\n'.join(signatures[n] + ' {' + blocks[n] + '}\n' for n in names) + '\n}\n')
    production = [actual / n for n in ('FunctionGestureRecognizer.kt', 'NativeFunctionInput.kt', 'SessionPresentation.kt')]
    sources = production + sorted((suite / 'doubles').glob('*.kt')) + [suite / 'Scenarios.kt', generated]
    cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
    deps = [('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'),
            ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'),
            ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'),
            ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
            ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
            ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
            ('org.jetbrains', 'annotations', '13.0')]
    jars = []
    for group, artifact, version in deps:
        found = list((cache / group / artifact / version).glob(f'*/{artifact}-{version}.jar'))
        if len(found) != 1:
            parser.error(f'Missing pinned compiler dependency {artifact}:{version}')
        jars.extend(found)
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java')
    cp = os.pathsep.join(map(str, jars))
    artifact = out / 'function-adapter.jar'
    compile_cmd = [java, '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                   '-jvm-target', '17', '-classpath', cp, '-d', str(artifact)] + list(map(str, sources))
    run_cmd = [java, '-cp', str(artifact) + os.pathsep + cp, 'com.code2hack.eyeprojector.directrg.ScenariosKt']
    manifest = {'sourceRoot': str(root), 'gitHead': subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip(),
                'gitStatus': subprocess.check_output(['git', '-C', str(root), 'status', '--short'], text=True),
                'sources': [{'path': str(p), 'sha256': digest(p)} for p in sources + [activity, helper, suite / 'run.py']],
                'activityBlocks': blocks, 'initialization': init[0], 'compiler': [{'path': str(p), 'sha256': digest(p)} for p in jars]}
    (out / 'source-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    result = {'scope': 'Actual Function adapter/recognizer/router and extracted Activity methods on host boundaries; no Android/firmware/device destination proof',
              'compileExit': None, 'scenariosExit': None, 'commands': [compile_cmd, run_cmd]}
    try:
        for key, cmd, limit, logfile in [('compileExit', compile_cmd, 60, 'compile.log'), ('scenariosExit', run_cmd, 15, 'scenarios.log')]:
            with (out / logfile).open('w') as log:
                proc = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, timeout=limit)
            result[key] = proc.returncode
            print((out / logfile).read_text())
            if proc.returncode:
                break
    except subprocess.TimeoutExpired as error:
        result['timeoutSeconds'] = error.timeout
    result['artifacts'] = [{'path': p.name, 'sha256': digest(p)} for p in sorted(out.iterdir()) if p.is_file() and p.name != 'result.json']
    (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    return 0 if result['scenariosExit'] == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
