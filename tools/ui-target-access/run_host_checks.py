#!/usr/bin/env python3
"""Check the actual traversal/comparison code using host doubles; no SDK/Gradle/device access."""
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent
with tempfile.TemporaryDirectory(prefix='eyeprojector-ui-target-host-') as tmp:
    subprocess.run(['javac', '--release', '17', '-d', tmp,
                    str(ROOT / 'src/com/code2hack/eyeprojector/uitargetaccess/PackageNodeWalk.java'),
                    str(ROOT / 'test/PackageNodeWalkScenarios.java')], check=True)
    subprocess.run(['java', '-cp', tmp,
                    'com.code2hack.eyeprojector.uitargetaccess.PackageNodeWalkScenarios'], check=True)
subprocess.run([sys.executable, '-B', str(ROOT / 'test/test_compare_fixture.py')], check=True)
print('PASS: host checks only; Android compilation, setup and managed-display access remain untested')
