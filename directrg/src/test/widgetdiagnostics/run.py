#!/usr/bin/env python3
"""Exercise actual widget fixtures and extracted actual local branch/catch on host API doubles."""
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
    matches = list(re.finditer(marker, source))
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one source block: {marker}; got {len(matches)}")
    start = source.index("{", matches[0].start())
    depth = 0
    quote = None
    i = start
    while i < len(source):
        if source.startswith("//", i):
            end = source.find("\n", i)
            i = len(source) if end < 0 else end
            continue
        if source.startswith("/*", i):
            end = source.find("*/", i + 2)
            if end < 0:
                raise ValueError("Unterminated Kotlin comment")
            i = end + 2
            continue
        char = source[i]
        if quote:
            if char == "\\":
                i += 2
                continue
            if char == quote:
                quote = None
        elif char in "\"'":
            quote = char
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return source[start + 1:i]
        i += 1
    raise ValueError("Unterminated source block")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    root = args.source_dir.resolve()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    suite = Path(__file__).resolve().parent
    fixtures = root / "directrg/src/androidTest/kotlin/com/code2hack/eyeprojector/directrg"
    actual = root / "directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg"
    instrumentation = fixtures / "ProjectionInstrumentation.kt"
    source = instrumentation.read_text()
    local = block(source, r'if\s*\(operation\s*==\s*"local-input-replay"\)\s*\{')
    caught = block(source, r'catch\s*\(error:\s*Exception\)\s*\{')
    generated = output / "ExtractedLocalReplayDriver.kt"
    generated.write_text('''package com.code2hack.eyeprojector.directrg
import android.app.Activity
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import org.json.JSONArray
import widgethost.Host

// Only the local operation body and terminal catch come from actual supplied instrumentation.
// Android launch/idle/UI scheduling and status I/O are host boundaries, not a real Activity.
internal class ExtractedLocalReplayDriver {
    val targetContext = Context()
    fun startActivitySync(intent: Intent): Activity { Host.beforeActivityStart(); return Activity() }
    fun waitForIdleSync() { Host.beforeIdle() }
    fun runOnMainSync(task: Runnable) = Host.main(task)
    fun status(): JSONObject = error("No projection status in this host local-only fixture")
    fun run(): JSONObject {
        val report = JSONObject().put("passed", false).put("driver", "on-RG Kotlin instrumentation")
        var activity: Activity? = null
        val operation = "local-input-replay"
        report.put("operation", operation)
        try {
''' + local + '''
            report.put("passed", true)
        } catch (error: Exception) {
''' + caught + '''
        }
        return report
    }
}
''')
    sources = [fixtures / "NativeInputWidgetChecks.kt", fixtures / "HeldInputWidgetChecks.kt"]
    helper = fixtures / "WidgetCheckDiagnostics.kt"
    if helper.exists():
        sources.append(helper)
    sources += [actual / name for name in ("LocalPointerDispatcher.kt", "HeldPointerDispatcher.kt", "PadGestureRecognizer.kt")]
    sources += [root / "protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol" / name for name in ("Touch.kt", "Wire.kt", "ActivationControl.kt", "ReceiverUsability.kt", "PhoneInput.kt")]
    sources += sorted((suite / "doubles").glob("*.kt")) + [suite / "Scenarios.kt", generated]
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
    dependencies = (
        ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"),
        ("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21"),
        ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"),
        ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        ("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
        ("org.jetbrains", "annotations", "13.0"),
    )
    jars = []
    for group, artifact, version in dependencies:
        matches = sorted((cache / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
        if len(matches) != 1:
            parser.error(f"Provision pinned Gradle compiler dependency {artifact}:{version}")
        jars.append(matches[0])
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else shutil.which("java")
    version = subprocess.run([java, "-version"], text=True, capture_output=True, timeout=5)
    (output / "java-version.log").write_text(version.stdout + version.stderr)
    manifest = {"sourceRoot": str(root), "sourceRootGitHead": None, "sourceRootGitStatus": None,
                "sourceBlocks": {"localInputReplay": local, "terminalCatch": caught},
                "sources": [{"path": str(path), "sha256": digest(path)} for path in [instrumentation] + sources],
                "compilerDependencies": [{"path": str(path), "sha256": digest(path)} for path in jars]}
    if (root / ".git").exists():
        manifest["sourceRootGitHead"] = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True, timeout=5).strip()
        manifest["sourceRootGitStatus"] = subprocess.check_output(["git", "-C", str(root), "status", "--short"], text=True, timeout=5).splitlines()
    manifest_path = output / "source-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    classpath = os.pathsep.join(map(str, jars))
    jar = output / "widget-diagnostics-host.jar"
    compile_cmd = [java, "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath, "-d", str(jar)] + list(map(str, sources))
    run_cmd = [java, "-cp", str(jar) + os.pathsep + classpath, "com.code2hack.eyeprojector.directrg.ScenariosKt"]
    result = {"compileExit": None, "scenariosExit": None, "sourceManifestSha256": digest(manifest_path),
              "commands": {"compile": compile_cmd, "scenarios": run_cmd},
              "scope": "Actual widget fixture source, dispatcher/recognizer source and extracted local branch/catch against host UI/API boundary doubles. No real Android widget/Activity/firmware/physical input evidence."}
    exit_code = 1
    try:
        with (output / "compile.log").open("w") as log:
            compiled = subprocess.run(compile_cmd, stdout=log, stderr=subprocess.STDOUT, timeout=45)
        result["compileExit"] = compiled.returncode
        if compiled.returncode:
            print((output / "compile.log").read_text(), end="")
            exit_code = compiled.returncode
        else:
            with (output / "scenarios.log").open("w") as log:
                run = subprocess.run(run_cmd, stdout=log, stderr=subprocess.STDOUT, timeout=20)
            result["scenariosExit"] = run.returncode
            print((output / "scenarios.log").read_text(), end="")
            exit_code = run.returncode
    except subprocess.TimeoutExpired as error:
        result["timeout"] = {"seconds": error.timeout, "command": error.cmd}
    finally:
        result["artifacts"] = [{"path": path.name, "sha256": digest(path)} for path in output.iterdir() if path.is_file() and path.name not in ("result.json", "result.sha256")]
        (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        (output / "result.sha256").write_text(digest(output / "result.json") + "  result.json\n")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
