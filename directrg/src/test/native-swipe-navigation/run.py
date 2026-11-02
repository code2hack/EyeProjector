#!/usr/bin/env python3
"""Compile the complete swipe receiver against named Android boundaries using cached Kotlin."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    suite = Path(__file__).resolve().parent
    root = suite.parents[3]
    actual = root / 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
    out = args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=False)
    cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
    dependencies = [('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'),
                    ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'),
                    ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'),
                    ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                    ('org.jetbrains', 'annotations', '13.0'),
                    ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0')]
    jars = []
    for group, artifact, version in dependencies:
        found = list((cache / group / artifact / version).glob(f'*/{artifact}-{version}.jar'))
        if len(found) != 1:
            parser.error(f'Expected one cached {artifact}:{version}, found {len(found)}')
        jars.extend(found)
    cp = os.pathsep.join(map(str, jars))
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
    files = [actual / 'NativeSwipeNavigationInput.kt', actual / 'ContextualNavigation.kt', suite / 'ReceiverScenarios.kt']
    files += sorted((suite / 'doubles').glob('*.kt'))
    inputs = []
    for index, source in enumerate(files):
        copy = out / 'sources' / str(index) / source.name
        copy.parent.mkdir(parents=True, exist_ok=True)
        copy.write_bytes(source.read_bytes())
        inputs.append({'path': str(source), 'snapshot': str(copy), 'sha256': sha(copy)})
    jar = out / 'receiver-source.jar'
    commands = {
        'compile': [java, '-Xmx512m', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib',
                    '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-d', str(jar)] + [x['snapshot'] for x in inputs],
        'run': [java, '-Xlog:class+load=info:file=' + str(out / 'class-load.log'), '-cp', str(jar) + os.pathsep + cp,
                'com.code2hack.eyeprojector.directrg.ReceiverScenariosKt']}
    record = {'scope': 'Complete actual receiver and enum source; explicit Android registration/delivery boundaries; '
                       'no device/user/physical gesture or brightness suppression proof',
              'base': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
              'inputs': inputs, 'dependencies': [{'path': str(x), 'sha256': sha(x)} for x in jars],
              'runner': {'path': str(Path(__file__).resolve()), 'sha256': sha(__file__)}, 'commands': commands}
    status = 1
    try:
        for phase, limit in [('compile', 60), ('run', 20)]:
            with (out / (phase + '.log')).open('w') as log:
                result = subprocess.run(commands[phase], stdout=log, stderr=subprocess.STDOUT, timeout=limit)
            record[phase + 'Exit'] = result.returncode
            print((out / (phase + '.log')).read_text(), end='')
            if result.returncode:
                return result.returncode
        required = ['NativeSwipeNavigationInput', 'NativeSwipeNavigationInput$Registration', 'ContextualNavigationAction']
        lines = (out / 'class-load.log').read_text().splitlines()
        record['classLoads'] = {name: [line for line in lines if '] com.code2hack.eyeprojector.directrg.' + name + ' source:' in line]
                                for name in required}
        record['sourceClassesVerified'] = all(len(rows) == 1 and ' source: file:' + str(jar) in rows[0]
                                               for rows in record['classLoads'].values())
        record['jarSha256'] = sha(jar)
        status = 0 if record['sourceClassesVerified'] else 1
        return status
    except subprocess.TimeoutExpired as error:
        record['timeoutSeconds'] = error.timeout
        raise
    finally:
        (out / 'result.json').write_text(json.dumps(record, indent=2) + '\n')


if __name__ == '__main__':
    raise SystemExit(main())
