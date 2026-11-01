#!/usr/bin/env python3
"""Cached actual-source phase/aim/append-only writer checks; no Gradle, SDK or device."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

CHECKS = r'''package com.code2hack.eyeprojector.directrg
import com.code2hack.eyeprojector.hostprobe.UiTargetEdgeTrace
import java.nio.file.Files

fun main() {
    val events=mutableListOf<Triple<String,String,String?>>()
    val phases=AnchorAdbPhasePlan { a,b,c -> events.add(Triple(a,b,c)) }
    val steps=listOf(AnchorAdbPhasePlan.Step("ordinary","observe",1000,mapOf("streaming" to true),mapOf("witness" to true)),
        AnchorAdbPhasePlan.Step("unavailable","observe",1000,mapOf("access" to true),emptyMap()))
    var submissions=0
    phases.tick(steps,0,mapOf("streaming" to false)) { submissions++ }
    check(submissions==0)
    phases.tick(steps,10,mapOf("streaming" to true)) { submissions++ }
    phases.tick(steps,20,mapOf("streaming" to true)) { submissions++ }
    check(submissions==1 && phases.completed==0)
    phases.tick(steps,30,mapOf("witness" to true)) { submissions++ }
    check(phases.completed==1)
    phases.tick(steps,40,emptyMap()) { submissions++ }
    phases.tick(steps,1040,emptyMap()) { submissions++ }
    check(phases.inconclusive==1 && submissions==1)
    phases.tick(steps,2000,mapOf("streaming" to true,"witness" to true,"access" to true)) { submissions++ }
    check(submissions==1) // No replay of old completed/timed-out commands.
    phases.close(); phases.tick(steps,3000,emptyMap()) { submissions++ };check(submissions==1)
    val failed=AnchorAdbPhasePlan { _,_,_ -> }
    failed.tick(listOf(AnchorAdbPhasePlan.Step("throws","observe",1000,emptyMap(),emptyMap())),0,emptyMap()) { error("failed ingress") }
    check(failed.inconclusive==1 && failed.completed==0)

    for (rotation in 0..3) {
        val bounds=PointerBounds(0f,0f,1000f,700f)
        val model=HeadPointerModel();model.resize(bounds,rotation)
        val aim=AnchorAdbSyntheticAim(model,bounds,rotation)
        var now=1000000000L
        for ((x,y) in listOf(700f to 200f,300f to 500f,500f to 350f)) {
            var point=aim.tick(x,y,now)
            repeat(400) { now+=20000000L;point=aim.tick(x,y,now) }
            check(point.available && kotlin.math.abs(point.x-x)<=2 && kotlin.math.abs(point.y-y)<=2) {
                "Synthetic real-model aim failed rotation=$rotation target=$x,$y got=$point"
            }
            now+=20000000L
        }
        model.stop();check(!model.position.available)
    }

    val dir=Files.createTempDirectory("fixture-edges-host-").toFile()
    try {
        val file=java.io.File(dir,"edges.jsonl");file.writeText("retained-prefix\n")
        val writer=UiTargetEdgeTrace(file,"run","instance") { it.toString() }
        writer.append("motion","case","down",mapOf("actionMasked" to 0,"downTimeMs" to 7L,"pointerIds" to listOf(0)))
        writer.append("motion","case","up",mapOf("actionMasked" to 1,"downTimeMs" to 7L,"pointerIds" to listOf(0)))
        writer.append("motion","case","cancel",mapOf("actionMasked" to 3,"downTimeMs" to 8L,"pointerIds" to listOf(0)))
        check(writer.sequence==3L && writer.failures==0L)
        writer.close();val before=file.readText();writer.append("late","case","late",emptyMap())
        check(file.readText()==before && before.startsWith("retained-prefix\n") && file.readLines().size==4)
        check(before.contains("sequence=1") && before.contains("sequence=3") && before.contains("runId=run"))
        val errors=UiTargetEdgeTrace(java.io.File(dir,"encode-error.jsonl"),"run","instance") { error("encoder failure") }
        errors.append("motion","case","down",emptyMap());check(errors.failures==1L && errors.failureType!=null);errors.close()
    } finally { dir.deleteRecursively() }
    println("PASS: actual phase predicates/once/no replay/inconclusive closure; synthetic real-model aim all4 rotations; append-only fixture row identity/sequence/closure/failure. Host only, no Android/runtime/physical proof.")
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    out = args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=False)
    rg = root / "directrg/src/androidTest/kotlin/com/code2hack/eyeprojector/directrg"
    main_source = root / "directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg"
    inputs = [rg / "AnchorAdbPhasePlan.kt", rg / "AnchorAdbSyntheticAim.kt",
              main_source / "HeadPointerModel.kt", main_source / "HeadOrientation.kt",
              root / "hostprobe/src/main/kotlin/com/code2hack/eyeprojector/hostprobe/UiTargetEdgeTrace.kt"]
    checks = out / "Checks.kt"
    checks.write_text(CHECKS)
    inputs.append(checks)
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    coords = [("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"),
              ("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21"),
              ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"),
              ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
              ("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
              ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
              ("org.jetbrains", "annotations", "13.0")]
    jars = []
    for group, artifact, version in coords:
        matches = list((cache / group / artifact / version).glob("*/" + artifact + "-" + version + ".jar"))
        if len(matches) != 1:
            parser.error("Missing/ambiguous existing compiler input " + artifact + "; no download")
        jars.append(matches[0])
    cp = os.pathsep.join(map(str, jars))
    java = "/usr/lib/jvm/java-17-openjdk-amd64/bin/java"
    jar = out / "checks.jar"
    commands = {"compile": [java, "-Xmx512m", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                            "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp,
                            "-d", str(jar)] + list(map(str, inputs)),
                "checks": [java, "-Xmx128m", "-cp", str(jar) + os.pathsep + cp,
                           "com.code2hack.eyeprojector.directrg.ChecksKt"]}
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    result = {"scope": "Actual pure driver phase/aim and fixture writer; Android driver integration is not compiled/executed here",
              "inputs": [{"path": str(p), "sha256": sha(p)} for p in inputs + [Path(__file__)]],
              "dependencies": [{"path": str(p), "sha256": sha(p)} for p in jars], "commands": commands}
    for name, command in commands.items():
        with (out / (name + ".log")).open("w") as log:
            try:
                result[name + "Exit"] = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=45).returncode
            except subprocess.TimeoutExpired:
                result[name + "Exit"] = 124
        (out / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print((out / (name + ".log")).read_text(), end="")
        if result[name + "Exit"]:
            raise SystemExit(result[name + "Exit"])


if __name__ == "__main__":
    main()
