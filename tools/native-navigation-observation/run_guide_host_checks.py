#!/usr/bin/env python3
"""Compile actual guide plan/adapter with cached Kotlin and narrow UI doubles; no SDK/device."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

from guide_host_doubles import CHECKS, DOUBLES


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    source = root / "directrg/src/androidTest/kotlin/com/code2hack/eyeprojector/directrg"
    inputs = [source / name for name in ("NativeGestureBatchGuidePlan.kt", "NativeGestureBatchGuide.kt",
                                        "NativeInputObservationQueue.kt")]
    for name, content in {**DOUBLES, "GuideChecks.kt": CHECKS}.items():
        path = output / name
        path.write_text(content)
        inputs.append(path)
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    dependencies = [("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"),
                    ("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21"),
                    ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"),
                    ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
                    ("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
                    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
                    ("org.jetbrains", "annotations", "13.0")]
    jars = []
    for group, artifact, version in dependencies:
        matches = list((cache / group / artifact / version).glob("*/" + artifact + "-" + version + ".jar"))
        if len(matches) != 1:
            parser.error("Missing/ambiguous cached dependency " + artifact + "; no downloads performed")
        jars.append(matches[0])
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    cp = os.pathsep.join(map(str, jars))
    artifact = output / "guide-host-checks.jar"
    commands = {
        "compile": [java, "-Xmx512m", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp,
                    "-d", str(artifact)] + list(map(str, inputs)),
        "checks": [java, "-Xmx128m", "-cp", str(artifact) + os.pathsep + cp,
                   "com.code2hack.eyeprojector.directrg.GuideChecksKt"],
    }
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    recorded = inputs + [source / "NativeInputObservation.kt", Path(__file__),
                         Path(__file__).with_name("guide_host_doubles.py")]
    result = {"scope": "Actual guide plan/UI adapter and primitive queue with host doubles only; no SDK/device",
              "inputs": [{"path": str(path), "sha256": digest(path)} for path in recorded],
              "dependencies": [{"path": str(path), "sha256": digest(path)} for path in jars],
              "commands": commands}
    for name, command in commands.items():
        with (output / (name + ".log")).open("w") as log:
            try:
                result[name + "Exit"] = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                                         timeout=45).returncode
            except subprocess.TimeoutExpired:
                result[name + "Exit"] = 124
                log.write("\nHOST_CHECK_TIMEOUT\n")
        (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print((output / (name + ".log")).read_text(), end="")
        if result[name + "Exit"]:
            raise SystemExit(result[name + "Exit"])


if __name__ == "__main__":
    main()
