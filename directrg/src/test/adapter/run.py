#!/usr/bin/env python3
"""Run real continuous-input adapters with ReceiverActivity wiring and host API doubles."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def activity_lambda(source, prefix, label):
    """Extract a braced lambda from the current Activity, without substituting test wiring."""
    matches = list(re.finditer(prefix, source))
    if len(matches) != 1:
        raise ValueError(f'Expected one {label} in ReceiverActivity, found {len(matches)}')
    start = matches[0].end()
    while start < len(source) and source[start].isspace():
        start += 1
    if source[start:start + 1] != '{':
        raise ValueError(f'{label} must be an explicit lambda for this adapter test')
    depth = 0
    i = start
    while i < len(source):
        if source.startswith('//', i):
            end = source.find('\n', i + 2)
            i = len(source) if end < 0 else end + 1
            continue
        if source.startswith('/*', i):
            comment_depth = 1
            i += 2
            while comment_depth and i < len(source):
                if source.startswith('/*', i):
                    comment_depth += 1
                    i += 2
                elif source.startswith('*/', i):
                    comment_depth -= 1
                    i += 2
                else:
                    i += 1
            continue
        if source.startswith('"""', i):
            end = source.find('"""', i + 3)
            if end < 0:
                raise ValueError(f'Unterminated string in {label}')
            i = end + 3
            continue
        if source[i] in ('"', "'"):
            quote = source[i]
            i += 1
            while i < len(source):
                if source[i] == '\\':
                    i += 2
                elif source[i] == quote:
                    i += 1
                    break
                else:
                    i += 1
            continue
        if source[i] == '{':
            depth += 1
        elif source[i] == '}':
            depth -= 1
            if depth == 0:
                return source[start:i + 1]
        i += 1
    raise ValueError(f'Unterminated lambda for {label}')


def generated_wiring(activity):
    held = activity_lambda(activity, r'\bheldInput\s*=\s*HeldPointerDispatcher\s*\(\s*', 'held position supplier')
    native = activity_lambda(activity, r'\bpadContact\s*=\s*NativePadContact\s*\(\s*this\s*,\s*', 'pad admission supplier')
    observer = activity_lambda(activity, r'\bonPositionChanged\s*=\s*', 'pointer observer')
    kotlin = f'''package com.code2hack.eyeprojector.directrg

import android.os.SystemClock

// Generated from the supplied production ReceiverActivity source; no good/bad test selector.
internal class ActivityInputWiring(val pointer: PointerOverlay) {{
    lateinit var heldInput: HeldPointerDispatcher
    val heldInputInitialized get() = ::heldInput.isInitialized
    var cancel: () -> Unit = {{}}
    var edgeUpdates = 0
        private set
    fun heldPosition(): () -> PointerPosition = {held}
    fun admissionPosition(): () -> PointerPosition = {native}
    fun installObserver() {{
        pointer.apply {{ onPositionChanged = {observer} }}
    }}
    private fun cancelInput() = cancel()
    private fun updateEdgeScroll() {{ edgeUpdates++ }}
}}
'''
    return kotlin, {'heldPosition': held, 'admissionPosition': native, 'onPositionChanged': observer}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True, help='External directory for generated wiring and evidence')
    parser.add_argument('--source-dir', type=Path, help='Exact repository-root snapshot, including directrg and protocol sources')
    args = parser.parse_args()
    suite = Path(__file__).resolve().parent
    root = args.source_dir.resolve() if args.source_dir else suite.parents[3]
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)

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
        matches = sorted((cache / group / artifact / version).glob(f'*/{artifact}-{version}.jar'))
        if len(matches) != 1:
            parser.error(f'Provision the affected Gradle checks first: missing/ambiguous cached {artifact}:{version}')
        jars.append(matches[0])
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
    if not java:
        parser.error('Configure JDK 17 using JAVA_HOME or PATH')
    version = subprocess.run([java, '-version'], text=True, capture_output=True, timeout=5)
    version_text = version.stdout + version.stderr
    (output / 'java-version.log').write_text(version_text)

    activity = root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ReceiverActivity.kt'
    actual = root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
    production = [actual / name for name in (
        'PointerOverlay.kt', 'HeadPointerModel.kt', 'HeadOrientation.kt',
        'HeldPointerDispatcher.kt', 'PadGestureRecognizer.kt', 'NativePadContact.kt',
    )]
    protocol = root / 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
    production += [protocol / name for name in ('Wire.kt', 'Touch.kt')]
    for path in [activity] + production:
        if not path.is_file():
            parser.error(f'--source-dir must be a complete repository-root snapshot: missing {path}')
    try:
        wiring, lambdas = generated_wiring(activity.read_text())
    except ValueError as error:
        parser.error(str(error))
    generated = output / 'ActivityInputWiring.kt'
    generated.write_text(wiring)

    suite_sources = sorted((suite / 'doubles').glob('*.kt')) + [suite / 'Scenarios.kt']
    sources = production + suite_sources + [generated]
    manifest = {
        'sourceRoot': str(root),
        'sourceRootGitHead': None,
        'sourceRootGitStatus': None,
        'production': [{'path': str(path.relative_to(root)), 'sha256': sha256(path)} for path in [activity] + production],
        'suite': [{'path': str(path.relative_to(suite)), 'sha256': sha256(path)}
                  for path in suite_sources + [suite / 'run.py', suite / 'README.md']],
        'extractedLambdas': lambdas,
        'generatedWiringSha256': sha256(generated),
        'compilerDependencies': [{'path': str(path), 'sha256': sha256(path)} for path in jars],
    }
    if (root / '.git').exists():
        manifest['sourceRootGitHead'] = subprocess.check_output(
            ['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True, timeout=5).strip()
        manifest['sourceRootGitStatus'] = subprocess.check_output(
            ['git', '-C', str(root), 'status', '--short'], text=True, timeout=5).splitlines()
    manifest_path = output / 'source-manifest.json'
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')

    classpath = os.pathsep.join(map(str, jars))
    jar = output / 'continuous-input-adapter-regressions.jar'
    compile_command = [java, '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                       '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath,
                       '-d', str(jar)] + list(map(str, sources))
    run_command = [java, '-cp', str(jar) + os.pathsep + classpath,
                   'com.code2hack.eyeprojector.directrg.ScenariosKt']
    result = {
        'compileExit': None, 'scenariosExit': None,
        'sourceManifestSha256': sha256(manifest_path),
        'commands': {'compile': compile_command, 'scenarios': run_command},
        'scope': 'Actual overlay/model/contact/held adapters and extracted Activity lambdas against host API doubles; '
                 'no full Activity, Android event dispatch, firmware, hardware or physical hold/release/drag proof',
    }
    exit_code = 1
    try:
        with (output / 'compile.log').open('w') as log:
            compiled = subprocess.run(compile_command, stdout=log, stderr=subprocess.STDOUT, timeout=45)
        result['compileExit'] = compiled.returncode
        if compiled.returncode:
            print((output / 'compile.log').read_text(), end='')
            exit_code = compiled.returncode
        else:
            with (output / 'scenarios.log').open('w') as log:
                run = subprocess.run(run_command, stdout=log, stderr=subprocess.STDOUT, timeout=15)
            result['scenariosExit'] = run.returncode
            print((output / 'scenarios.log').read_text(), end='')
            exit_code = run.returncode
    except subprocess.TimeoutExpired as error:
        result['timeout'] = {'command': error.cmd, 'seconds': error.timeout}
        print(f'Adapter regression command exceeded its bounded {error.timeout}s test budget', file=sys.stderr)
    finally:
        result['artifacts'] = [{'path': path.name, 'sha256': sha256(path)}
                               for path in (output / name for name in (
                                   'java-version.log', 'compile.log', 'scenarios.log',
                                   'ActivityInputWiring.kt', 'continuous-input-adapter-regressions.jar',
                               )) if path.exists()]
        result_path = output / 'result.json'
        result_path.write_text(json.dumps(result, indent=2) + '\n')
        (output / 'result.sha256').write_text(sha256(result_path) + '  result.json\n')
    return exit_code


if __name__ == '__main__':
    sys.exit(main())
