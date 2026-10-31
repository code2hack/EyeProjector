#!/usr/bin/env python3
"""Compile complete production HID Android adapter/panel sources on explicit host API doubles."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def kotlin_block(source, marker):
    """Select one exact production function; strings/comments cannot alter brace depth."""
    matches = list(re.finditer(marker, source, re.MULTILINE))
    if len(matches) != 1:
        raise ValueError(f'Expected one production extraction marker {marker!r}, got {len(matches)}')
    start = matches[0].start()
    index = source.index('{', matches[0].end())
    depth, quote = 0, None
    while index < len(source):
        if not quote and source.startswith('//', index):
            end = source.find('\n', index)
            index = len(source) if end < 0 else end
            continue
        if not quote and source.startswith('/*', index):
            end = source.find('*/', index + 2)
            if end < 0:
                raise ValueError('Unterminated production comment')
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
        elif char == '{':
            depth += 1
        elif char == '}':
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
        index += 1
    raise ValueError('Unterminated production function')


def receiver_key_fixture(receiver_source, native_source):
    dispatch = kotlin_block(receiver_source, r'^    override fun dispatchKeyEvent\(')
    observe = kotlin_block(receiver_source, r'^    private inline fun observeNativeInput\(')
    hovered = kotlin_block(receiver_source, r'^    private fun hoveredHidAction\(')
    received_initializer = kotlin_block(receiver_source, r'^        hidDevices = AndroidHidInput\(this, hidInput\)')
    received_match = re.fullmatch(r'        hidDevices = AndroidHidInput\(this, hidInput\) \{ event ->(?P<body>[\s\S]*)\}', received_initializer)
    if not received_match:
        raise ValueError('Expected the exact production received callback signature')
    received_body = received_match.group('body')
    received_method = '    fun receivedForTest(event: HidKeyInput.Event) {' + received_body + '}\n'
    normalize = kotlin_block(native_source, r'^internal fun nativePadKey\(')
    boundary = '''package com.code2hack.eyeprojector.directrg
import android.os.SystemClock
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.View

// Only the Activity superclass, pointer and surrounding lifecycle/scheduling are doubled.
// Key dispatch, hover, received callback body and native mapper are actual source extractions.
internal open class ReceiverKeyBoundary {
    val fallthrough = mutableListOf<KeyEvent>()
    open fun dispatchKeyEvent(event: KeyEvent): Boolean { fallthrough.add(event); return false }
}
internal class ReceiverKeyPointer {
    var position = PointerPosition(100f, 200f, true)
    var reads = 0
    fun inputPosition(): PointerPosition { reads++; return position }
}
internal class ExtractedReceiverKeyIngress(private val input: HidKeyInput, adapter: AndroidHidInput) : ReceiverKeyBoundary() {
    // Only the optional key-observer interface is doubled; its invocation/helper are extracted.
    interface NativeInputObserver {
        fun key(activityId: Int, event: KeyEvent, receiptUptimeMs: Long, receiptElapsedNs: Long)
    }
    var nativeInputObserver: NativeInputObserver? = null
    private lateinit var hidDevices: AndroidHidInput
    val nativeActions = mutableListOf<PadGestureRecognizer.Action>()
    val gestures = PadGestureRecognizer(500, 800) { nativeActions.add(it) }
    val pointer = ReceiverKeyPointer()
    val scheduled = mutableListOf<Long?>()
    var padUps = 0L
    var streaming = true
    var available = true
    var cancellations = 0
    var inputGeneration = 0L
    var session: Any? = Any()
    var projectionContext = false
    lateinit var hidPanel: HidBindingsPanel
    lateinit var root: View
    val connectionView = View(Context())
    init { hidDevices = adapter }
    fun hoveredForTest(trace: HidDecisionTrace.Event?): HidAction? = hoveredHidAction(trace)
    private fun usable() = available
    private fun schedulePadConfirmation() { scheduled.add(gestures.deadline) }
    private fun cancelInput() { cancellations++; input.cancel(); gestures.cancel(SystemClock.uptimeMillis()) }
'''
    return boundary + observe + '\n' + received_method + dispatch + '\n' + hovered + '\n}\n\n' + normalize + '\n', {
        'dispatchKeyEvent': {'source': 'ReceiverActivity.kt', 'sha256': hashlib.sha256(dispatch.encode()).hexdigest()},
        'observeNativeInput': {'source': 'ReceiverActivity.kt', 'sha256': hashlib.sha256(observe.encode()).hexdigest()},
        'hoveredHidAction': {'source': 'ReceiverActivity.kt', 'sha256': hashlib.sha256(hovered.encode()).hexdigest()},
        'receivedInitializer': {'source': 'ReceiverActivity.kt', 'sha256': hashlib.sha256(received_initializer.encode()).hexdigest(),
                                'bodySha256': hashlib.sha256(received_body.encode()).hexdigest()},
        'nativePadKey': {'source': 'NativePadInput.kt', 'sha256': hashlib.sha256(normalize.encode()).hexdigest()},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    root, out = args.source_dir.resolve(), args.output_dir.resolve()
    if out.exists() and any(out.iterdir()):
        parser.error('Retain the existing evidence; select a new empty --output-dir for this attempt')
    out.mkdir(parents=True, exist_ok=True)
    suite = root / 'directrg/src/test/hidframework'
    production = root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
    production_paths = [production / name for name in (
        'AndroidHidInput.kt', 'HidBindingsPanel.kt', 'HidKeyInput.kt', 'HidBindings.kt', 'HidDecisionTrace.kt',
        'FunctionGestureRecognizer.kt', 'PadGestureRecognizer.kt', 'HeadPointerModel.kt', 'HeadOrientation.kt',
        'LocalInputSettings.kt', 'LocalInputSettingsPanel.kt',
    )]
    protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
    production_paths += [protocol / n for n in ('Wire.kt', 'Touch.kt', 'PhoneInput.kt', 'ReceiverUsability.kt',
        'ActivationControl.kt', 'CameraFeed.kt', 'FunctionIntent.kt')]
    suite_paths = sorted((suite / 'doubles').glob('*.kt')) + [suite / 'Scenarios.kt']
    extraction_paths = [production / 'ReceiverActivity.kt', production / 'NativePadInput.kt']
    metadata_paths = [suite / 'run.py', suite / 'README.md']
    all_paths = production_paths + suite_paths + metadata_paths + extraction_paths
    for path in all_paths:
        if not path.is_file():
            parser.error(f'Missing complete source input: {path}')

    # Compiler inputs are immutable per-attempt copies; source edits made elsewhere after
    # this point cannot change which adapter/panel implementation this attempt executes.
    snapshot_entries = []
    for path in all_paths:
        relative = path.relative_to(root)
        snapshot = out / 'source-snapshot' / relative
        snapshot.parent.mkdir(parents=True, exist_ok=True)
        data = path.read_bytes()
        snapshot.write_bytes(data)
        snapshot_entries.append({
            'path': str(relative), 'originalPath': str(path),
            'snapshotPath': str(snapshot.relative_to(out)),
            'kind': 'production' if path in production_paths + extraction_paths else 'suite',
            'compiled': path not in metadata_paths + extraction_paths,
            'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data),
        })

    receiver_snapshot = out / 'source-snapshot' / extraction_paths[0].relative_to(root)
    native_snapshot = out / 'source-snapshot' / extraction_paths[1].relative_to(root)
    extracted, extraction = receiver_key_fixture(receiver_snapshot.read_text(), native_snapshot.read_text())
    generated = out / 'source-snapshot/generated/ExtractedReceiverKeyIngress.kt'
    generated.parent.mkdir(parents=True, exist_ok=True)
    generated.write_text(extracted)
    snapshot_entries.append({
        'path': 'generated/ExtractedReceiverKeyIngress.kt', 'originalPath': None,
        'snapshotPath': str(generated.relative_to(out)), 'kind': 'extracted-production-with-boundaries',
        'compiled': True, 'sha256': digest(generated), 'bytes': generated.stat().st_size,
    })

    cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
    dependencies = (
        ('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'),
        ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'),
        ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'),
        ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
        ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
        ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
        ('org.jetbrains', 'annotations', '13.0'),
    )
    jars = []
    for group, artifact, version in dependencies:
        found = sorted((cache / group / artifact / version).glob(f'*/{artifact}-{version}.jar'))
        if len(found) != 1:
            parser.error(f'Missing/ambiguous cached compiler dependency {artifact}:{version}; no download attempted')
        jars.append(found[0])

    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
    if not java:
        parser.error('Select JDK 17 using JAVA_HOME or PATH')
    version = subprocess.run([java, '-version'], capture_output=True, text=True, timeout=5)
    version_text = version.stdout + version.stderr
    (out / 'java-version.log').write_text(version_text)
    if version.returncode != 0 or not re.search(r'version "17(?:\.|\")', version_text):
        parser.error('This affected host suite requires JDK 17; see java-version.log')

    classpath = os.pathsep.join(map(str, jars))
    jar = out / 'hid-framework-host.jar'
    snapshots = [out / entry['snapshotPath'] for entry in snapshot_entries if entry['compiled']]
    compile_command = [java, '-Xmx2g', '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                       '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath,
                       '-d', str(jar)] + list(map(str, snapshots))
    run_command = [java, '-Xmx512m', '-cp', str(jar) + os.pathsep + classpath,
                   'com.code2hack.eyeprojector.directrg.ScenariosKt']
    manifest = {
        'sourceRoot': str(root),
        'sourceIdentity': 'Complete production/test bytes retained below; no Git command invoked',
        'sources': snapshot_entries,
        'compilerDependencies': [{'path': str(path), 'sha256': digest(path)} for path in jars],
        'java': str(Path(java).resolve()),
        'javaVersionLogSha256': digest(out / 'java-version.log'),
        'productionExtraction': extraction,
        'apiDoubles': 'Android only; actual production PointerPosition, pad event types and Function classifier compiled',
    }
    manifest_path = out / 'source-manifest.json'
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
    result = {
        'scope': 'Complete actual Android HID adapter/bindings panel, local settings model/panel including HID entrance, '
                 'and binding/key/Function sources plus exact Receiver dispatchKeyEvent/nativePadKey source on explicit host Android API doubles; no full Activity or actual framework/device delivery, '
                 'HID identity stability, physical input, native target, navigation or suppression proof',
        'compileExit': None, 'scenariosExit': None,
        'sourceManifestSha256': digest(manifest_path),
        'commands': {'compile': compile_command, 'scenarios': run_command},
        'timeoutsSeconds': {'compile': 60, 'scenarios': 15},
    }
    try:
        for key, command, timeout, logfile in (
            ('compileExit', compile_command, 60, 'compile.log'),
            ('scenariosExit', run_command, 15, 'scenarios.log'),
        ):
            with (out / logfile).open('w') as log:
                completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
            result[key] = completed.returncode
            print((out / logfile).read_text(), end='')
            if completed.returncode:
                break
    except subprocess.TimeoutExpired as error:
        result['timeout'] = {'command': error.cmd, 'seconds': error.timeout}
        print(f'HID framework host command exceeded its bounded {error.timeout}s budget', file=sys.stderr)
    finally:
        result['artifacts'] = [
            {'path': str(path.relative_to(out)), 'sha256': digest(path)}
            for path in sorted(out.rglob('*')) if path.is_file() and path.name not in ('result.json', 'result.sha256')
        ]
        result_path = out / 'result.json'
        result_path.write_text(json.dumps(result, indent=2) + '\n')
        (out / 'result.sha256').write_text(digest(result_path) + '  result.json\n')
    return 0 if result['scenariosExit'] == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
