#!/usr/bin/env python3
"""Compile actual Phone HID sources and exact Host lifecycle blocks on explicit host seams."""
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


def block(source, marker):
    matches = list(re.finditer(marker, source, re.MULTILINE))
    if len(matches) != 1:
        raise ValueError(f'Expected exactly one source marker {marker!r}, got {len(matches)}')
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
                raise ValueError('Unterminated source comment')
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
    raise ValueError('Unterminated source block')


def host_fixture(source):
    blocks = {}
    for name in ('initializePhoneHid', 'showPhoneHid', 'resumePhoneHid', 'stopPhoneHid'):
        blocks[name] = block(source, rf'^    private fun {name}\(')
    for name in ('onResume', 'onStop', 'onDestroy', 'onBackPressed'):
        blocks[name] = block(source, rf'^    override fun {name}\(')
    callsites = list(re.finditer(r'^                initializePhoneHid\(this\)$', source, re.MULTILINE))
    if len(callsites) != 1:
        raise ValueError('Expected one actual onCreate HID initialization callsite')
    blocks['onCreateCallsite'] = callsites[0].group()
    boundary = '''package com.code2hack.eyeprojector.directhost
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.code2hack.eyeprojector.directhost.hid.PhoneHidPanel
import com.code2hack.eyeprojector.directhost.hid.PhoneHidRuntime

// Only preceding Phone UI/bootstrap/observer behavior is inert. HID helpers and
// lifecycle method bodies below are exact production HostActivity source.
internal class InertBootstrap {
    fun refresh() = Unit
    fun removeObserver(observer: () -> Unit) = Unit
}
internal class InertPairing { fun close() = Unit }
internal class ExtractedHostHid : Activity() {
    private lateinit var phoneHid: PhoneHidPanel
    private val phoneControls = LinearLayout(this)
    private lateinit var bootstrap: InertBootstrap
    private val pairingPanel = InertPairing()
    private val main = Handler(Looper.getMainLooper())
    private val sessionTick = Runnable {}
    private val observer: () -> Unit = {}
    val container = LinearLayout(this)
    init { bootstrap = InertBootstrap(); container.addView(phoneControls); initializePhoneHid(container) }
    fun panelForTest() = phoneHid
    fun controlsForTest() = phoneControls
    fun openForTest(show: Boolean) = showPhoneHid(show)
'''
    generated = boundary + '\n'.join(value for key, value in blocks.items() if key != 'onCreateCallsite') + '\n}\n'
    metadata = {name: {'source': 'HostActivity.kt', 'sha256': hashlib.sha256(value.encode()).hexdigest(),
                       'bytes': len(value.encode())} for name, value in blocks.items()}
    return generated, metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    root, out = args.source_dir.resolve(), args.output_dir.resolve()
    if out.exists() and any(out.iterdir()):
        parser.error('Preserve the existing attempt; select a new empty --output-dir')
    out.mkdir(parents=True, exist_ok=True)
    suite = root / 'directhost/src/test/hid'
    production = root / 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
    actual = [production / 'hid' / name for name in (
        'PhoneHidBindings.kt', 'PhoneHidController.kt', 'PhoneHidRuntime.kt', 'PhoneHidKeyService.kt', 'PhoneHidPanel.kt')]
    actual += sorted((production / 'activation').glob('*.kt'))
    fixture = sorted((suite / 'doubles').glob('*.kt')) + [suite / 'Scenarios.kt',
        root / 'directhost/src/test/activation/ActivationScenarios.kt',
        root / 'directhost/src/test/kotlin/com/code2hack/eyeprojector/directhost/hid/PhoneHidBindingsTest.kt']
    extraction = [production / 'HostActivity.kt']
    metadata = [suite / 'run.py', suite / 'README.md', root / 'directhost/src/main/AndroidManifest.xml',
                root / 'directhost/src/main/res/xml/phone_hid_accessibility.xml',
                root / 'directhost/src/main/res/values/strings.xml']
    paths = actual + fixture + extraction + metadata
    for path in paths:
        if not path.is_file():
            parser.error(f'Missing actual source/fixture input: {path}')
    entries = []
    for path in paths:
        relative = path.relative_to(root)
        snapshot = out / 'source-snapshot' / relative
        snapshot.parent.mkdir(parents=True, exist_ok=True)
        data = path.read_bytes()
        snapshot.write_bytes(data)
        entries.append({'path': str(relative), 'originalPath': str(path), 'snapshotPath': str(snapshot.relative_to(out)),
                        'kind': 'production' if path in actual + extraction else 'fixture-or-metadata',
                        'compiled': path in actual + fixture, 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)})
    host = out / 'source-snapshot' / extraction[0].relative_to(root)
    generated, blocks = host_fixture(host.read_text())
    generated_path = out / 'source-snapshot/generated/ExtractedHostHid.kt'
    generated_path.parent.mkdir(parents=True, exist_ok=True)
    generated_path.write_text(generated)
    entries.append({'path': 'generated/ExtractedHostHid.kt', 'originalPath': None,
                    'snapshotPath': str(generated_path.relative_to(out)), 'compiled': True,
                    'kind': 'extracted-production-with-boundaries', 'sha256': digest(generated_path),
                    'bytes': generated_path.stat().st_size})
    cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
    dependencies = (
        ('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'),
        ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'),
        ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'),
        ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
        ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
        ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
        ('org.jetbrains', 'annotations', '13.0'), ('junit', 'junit', '4.13.2'),
        ('org.hamcrest', 'hamcrest-core', '1.3'))
    jars = []
    for group, artifact, version in dependencies:
        found = sorted((cache / group / artifact / version).glob(f'*/{artifact}-{version}.jar'))
        if len(found) != 1:
            parser.error(f'Missing/ambiguous cached compiler dependency {artifact}:{version}; no download attempted')
        jars.append(found[0])
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
    if not java:
        parser.error('Select required JDK 17 using JAVA_HOME or PATH')
    version = subprocess.run([java, '-version'], capture_output=True, text=True, timeout=5)
    (out / 'java-version.log').write_text(version.stdout + version.stderr)
    if version.returncode or not re.search(r'version "17(?:\.|\")', version.stdout + version.stderr):
        parser.error('This isolated source suite requires JDK 17')
    cp = os.pathsep.join(map(str, jars)); jar = out / 'phone-hid-host.jar'
    snapshots = [out / entry['snapshotPath'] for entry in entries if entry['compiled']]
    compile_command = [java, '-Xmx2g', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib',
        '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-d', str(jar)] + list(map(str, snapshots))
    scenario_command = [java, '-Xmx512m', '-cp', str(jar) + os.pathsep + cp,
        'com.code2hack.eyeprojector.directhost.hid.ScenariosKt']
    junit_command = [java, '-Xmx512m', '-cp', str(jar) + os.pathsep + cp, 'org.junit.runner.JUnitCore',
        'com.code2hack.eyeprojector.directhost.hid.PhoneHidBindingsTest']
    manifest = {'sourceRoot': str(root), 'sources': entries, 'hostExtraction': blocks,
        'compilerDependencies': [{'path': str(path), 'sha256': digest(path)} for path in jars],
        'java': str(Path(java).resolve()), 'javaVersionLogSha256': digest(out / 'java-version.log'),
        'boundaries': {'android': 'In-memory Context/preferences, Accessibility callbacks/settings, View/widget and queued-main Handler doubles',
            'host': 'Exact HID helpers/lifecycle bodies; old bootstrap/pairing UI and initialization container inert',
            'protocol': 'Exact authorized444 enums/edge seam double; no shared protocol production compiled',
            'session': 'Exact capture/submit/cancel signatures with opaque route identity; no backend implementation compiled',
            'activation': 'Actual generic local owner and HID injection port; immutable fixture targets, no shared Phone facade, persistence, control protocol, backend or Glasses wiring'}}
    manifest_path = out / 'source-manifest.json'; manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
    result = {'scope': 'Complete actual Phone HID store/controller/runtime/panel/Accessibility service and exact Host HID/lifecycle blocks; isolated Android and authorized444 session/protocol seams only, no framework/device/backend or physical acceptance',
        'sourceManifestSha256': digest(manifest_path), 'compileExit': None, 'scenariosExit': None, 'junitExit': None,
        'commands': {'compile': compile_command, 'scenarios': scenario_command, 'junit': junit_command},
        'timeoutsSeconds': {'compile': 90, 'scenarios': 30, 'junit': 15}}
    try:
        for key, command, timeout, logfile in (
            ('compileExit', compile_command, 90, 'compile.log'),
            ('scenariosExit', scenario_command, 30, 'scenarios.log'), ('junitExit', junit_command, 15, 'junit.log')):
            with (out / logfile).open('w') as log:
                completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
            result[key] = completed.returncode
            print((out / logfile).read_text(), end='')
            if key == 'compileExit' and completed.returncode:
                break
    except subprocess.TimeoutExpired as error:
        result['timeout'] = {'command': error.cmd, 'seconds': error.timeout}
    finally:
        result['artifacts'] = [{'path': str(path.relative_to(out)), 'sha256': digest(path)} for path in sorted(out.rglob('*'))
                               if path.is_file() and path.name not in ('result.json', 'result.sha256')]
        result_path = out / 'result.json'; result_path.write_text(json.dumps(result, indent=2) + '\n')
        (out / 'result.sha256').write_text(digest(result_path) + '  result.json\n')
    return 0 if result['compileExit'] == result['scenariosExit'] == result['junitExit'] == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
