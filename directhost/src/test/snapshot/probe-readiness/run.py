#!/usr/bin/env python3
"""Bounded cached JVM check of actual NativeSnapshotChecks; explicit SDK/Android/native doubles."""
import argparse, hashlib, json, os, subprocess, time
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output-dir", type=Path, required=True)
parser.add_argument("--probe-source", type=Path)
parser.add_argument("--parent", action="store_true")
parser.add_argument("--cleanup", action="store_true", help="C121-01 original-attempt cleanup controls")
args = parser.parse_args()
root = Path(__file__).resolve().parents[5]
fixture = Path(__file__).resolve().parent
out = args.output_dir.resolve()
out.mkdir(parents=True, exist_ok=False)
probe = args.probe_source.resolve() if args.probe_source else root / "directhost/src/androidTest/kotlin/com/code2hack/eyeprojector/directhost/activation/snapshot/NativeSnapshotChecks.kt"
compiled_files = [probe, root / "directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/activation/snapshot/SnapshotWord.kt", fixture / "ProbeReadinessChecks.kt"] + sorted((fixture / "doubles").glob("*.kt"))
provenance_files = [Path(__file__).resolve(), fixture / "README.md"]
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
coords = [("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"), ("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21"), ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"), ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"), ("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"), ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"), ("org.jetbrains", "annotations", "13.0")]
jars = []
for group, name, version in coords:
    matches = list((cache / group / name / version).glob("*/" + name + "-" + version + ".jar"))
    assert len(matches) == 1, (group, name, version)
    jars += matches
inputs, compiled = [], []
for index, source in enumerate(compiled_files + provenance_files):
    snapshot = out / "inputs" / str(index) / source.name
    snapshot.parent.mkdir(parents=True, exist_ok=True)
    snapshot.write_bytes(source.read_bytes())
    inputs.append({"path": str(source), "snapshot": str(snapshot), "sha256": sha(snapshot), "bytes": snapshot.stat().st_size, "compiled": index < len(compiled_files)})
    if index < len(compiled_files):
        compiled.append(str(snapshot))
jdk = Path("/usr/lib/jvm/java-17-openjdk-amd64/bin")
cp = os.pathsep.join(map(str, jars))
jar = out / "probe-readiness.jar"
commands = {
    "compile": [str(jdk / "java"), "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp, "-d", str(jar)] + compiled,
    "run": [str(jdk / "java"), "-cp", str(jar) + os.pathsep + cp, "com.code2hack.eyeprojector.directhost.activation.snapshot.ProbeReadinessChecks"] + (["cleanup-parent" if args.parent else "cleanup"] if args.cleanup else ["parent"] if args.parent else []),
}
result = {
    "scope": "Actual NativeSnapshotChecks and SnapshotWord compiled/executed with explicit SDK, Android main/service/clock, mapped-memory/JNI/FD/process boundary doubles. No real SDK/provider/Binder/SharedMemory/native/Android/device/permission proof; neither parent control establishes actual118 cause.",
    "parent": args.parent, "cleanup": args.cleanup, "inputs": inputs, "dependencies": [{"path": str(path), "sha256": sha(path)} for path in jars],
    "commands": commands, "compileExit": None, "runExit": None, "scenarioPassLines": [],
}
started = time.monotonic()
try:
    for phase in ["compile", "run"]:
        remaining = 115 - (time.monotonic() - started)
        assert remaining > 0
        with (out / (phase + ".log")).open("w") as log:
            completed = subprocess.run(commands[phase], stdout=log, stderr=subprocess.STDOUT, timeout=min(90, remaining))
        result[phase + "Exit"] = completed.returncode
        result[phase + "LogSha256"] = sha(out / (phase + ".log"))
        print((out / (phase + ".log")).read_text(), end="")
        if completed.returncode:
            break
    if result["runExit"] == 0:
        result["scenarioPassLines"] = [line for line in (out / "run.log").read_text().splitlines() if line.startswith("PASS ")]
        assert len(result["scenarioPassLines"]) == (2 if args.parent else 31 if args.cleanup else 16)
except BaseException as failure:
    result["runnerFailure"] = repr(failure)
    raise
finally:
    result["elapsedSeconds"] = time.monotonic() - started
    result["inputSnapshotsStillMatch"] = all(sha(Path(row["snapshot"])) == row["sha256"] for row in inputs)
    (out / "result.json").write_text(json.dumps(result, indent=2) + "\n")
raise SystemExit(0 if result["compileExit"] == result["runExit"] == 0 and result["inputSnapshotsStillMatch"] else 1)
