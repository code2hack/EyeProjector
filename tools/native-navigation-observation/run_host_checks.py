#!/usr/bin/env python3
"""Compile actual passive observer/queue with host doubles; no Gradle, SDK or device."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


DOUBLES = {
    "Annotation.kt": '''package android.annotation
annotation class SuppressLint(vararg val value: String)
''',
    "Android.kt": '''package android.content
import android.os.PowerManager
open class Context {
    var receiver: BroadcastReceiver? = null
    var filter: IntentFilter? = null
    var flags: Int? = null
    var failRegistration = false
    var failRemoval = false
    var removals = 0
    val power = PowerManager()
    fun registerReceiver(r: BroadcastReceiver, f: IntentFilter): Intent? = register(r, f, null)
    fun registerReceiver(r: BroadcastReceiver, f: IntentFilter, flags: Int): Intent? = register(r, f, flags)
    private fun register(r: BroadcastReceiver, f: IntentFilter, v: Int?): Intent? {
        check(receiver == null); receiver = r; filter = f; flags = v
        if (failRegistration) error("register after side effect")
        return null
    }
    fun unregisterReceiver(r: BroadcastReceiver) {
        if (failRemoval) error("unregister failure")
        check(receiver === r); receiver = null; removals++
    }
    @Suppress("UNCHECKED_CAST") fun <T> getSystemService(type: Class<T>): T {
        check(type == PowerManager::class.java); return power as T
    }
    companion object { const val RECEIVER_EXPORTED = 2 }
}
abstract class BroadcastReceiver {
    var isOrderedBroadcast = true
    var isInitialStickyBroadcast = false
    var originalResult = 37
    var resultCode: Int
        get() { check(isOrderedBroadcast); return originalResult }
        set(value) { error("observer changed result to " + value) }
    fun abortBroadcast(): Nothing = error("observer aborted broadcast")
    abstract fun onReceive(context: Context, intent: Intent)
}
class Intent(var action: String? = null, var flags: Int = 0) {
    val extras: Nothing get() = error("observer read payload")
    val data: Nothing get() = error("observer read data")
}
class IntentFilter {
    val actions = mutableListOf<String>()
    var priority = 0
    fun addAction(action: String) { actions.add(action) }
}
''',
    "Os.kt": '''package android.os
object Build { object VERSION { var SDK_INT = 32 } }
object SystemClock {
    fun uptimeMillis() = 1234L
    fun elapsedRealtimeNanos() = 9000000L
}
class PowerManager {
    var fail = false
    var interactive = true
    val isInteractive: Boolean get() { if (fail) error("sample failure"); return interactive }
}
''',
    "Checks.kt": '''package com.code2hack.eyeprojector.directrg
import android.content.Context
import android.content.Intent
import android.os.Build

fun main() {
    for (api in listOf(32, 33, 35)) {
        Build.VERSION.SDK_INT = api
        val context = Context()
        val queue = NativeInputObservationQueue()
        val observer = NativeNavigationObservation(context, queue)
        observer.start()
        check(context.filter!!.actions == NativeNavigationObservation.ACTIONS)
        check(context.filter!!.priority == Int.MAX_VALUE)
        check(context.flags == if (api >= 33) Context.RECEIVER_EXPORTED else null)
        val receiver = context.receiver!!
        val intent = Intent(NativeNavigationObservation.ACTIONS.first(), 0x40000000)
        receiver.onReceive(context, intent)
        intent.action = "mutated after receipt"; intent.flags = 0
        receiver.isOrderedBroadcast = false
        receiver.isInitialStickyBroadcast = true
        receiver.onReceive(context, Intent(NativeNavigationObservation.ACTIONS.last()))
        context.power.interactive = false
        observer.sample()
        observer.stop()
        receiver.onReceive(context, intent) // Late delivery is excluded after removing this observer.
        observer.stop() // Does not unregister a successor receiver.
        check(context.removals == 1 && context.receiver == null)
        val rows = mutableListOf<NativeInputObservationQueue.Row>()
        queue.close(); queue.drain { rows.add(it) }
        check(rows.map { it.seq } == listOf(1L, 2L, 3L))
        check(rows[0].fields["actionName"] == NativeNavigationObservation.ACTIONS.first())
        check(rows[0].fields["intentFlags"] == 0x40000000)
        check(rows[0].fields["resultCodeAtReceipt"] == 37)
        check(rows[0].fields["observerAborted"] == false && rows[0].fields["observerChangedResult"] == false)
        check(rows[1].fields["resultCodeAtReceipt"] == null && rows[1].fields["initialSticky"] == true)
        check(rows[2].fields["interactive"] == false)
        check(rows.all { it.uptimeMs == 1234L && it.elapsedNs == 9000000L })
        check(queue.failureCount == 0L)
    }
    val context = Context().apply { failRegistration = true }
    val queue = NativeInputObservationQueue()
    val observer = NativeNavigationObservation(context, queue)
    check(runCatching { observer.start() }.isFailure)
    val state = NativeInputObservationRunState(1000)
    context.failRemoval = true
    state.attempt("navigationUnregister") { observer.stop() }
    check(state.primary != null && !state.captureComplete)
    context.failRemoval = false
    observer.stop()
    check(context.removals == 1 && context.receiver == null)
    context.power.fail = true
    observer.sample()
    check(queue.failureCount == 1L)
    println("PASS: actual observer/queue; API32/33/35 registration, passive snapshots, exact cleanup, partial failure accounting. Host doubles only.")
}
''',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    directory = root / "directrg/src/androidTest/kotlin/com/code2hack/eyeprojector/directrg"
    observer = directory / "NativeNavigationObservation.kt"
    helper = directory / "NativeInputObservation.kt"
    existing = root / "directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg"
    # Production-source seven UNION the three policy10-established PhoneWindowManager senders.
    # Matching services.jar SHA-256: 6bdadf1db66ed93d1d065a4669218766fc6082563fd5ab77ca50d64a4612b1ff.
    action_pattern = r'"(com\.android\.action\.[A-Z_]+)"'
    expected = set(re.findall(action_pattern, (existing / "NativeFunctionInput.kt").read_text()
                              + (existing / "NativePadInput.kt").read_text()))
    policy_actions = {
        "com.android.action.ACTION_SETTINGS_KEY",
        "com.android.action.ACTION_TWO_FINGER_SINGLE_TAP",
        "com.android.action.ACTION_TWO_FINGER_DOUBLE_TAP",
    }
    assert set(re.findall(action_pattern, observer.read_text())) == expected | policy_actions
    sources = [observer, directory / "NativeInputObservationQueue.kt", directory / "NativeInputObservationRunState.kt"]
    for filename, content in DOUBLES.items():
        path = output / filename
        path.write_text(content)
        sources.append(path)
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
    jar = output / "navigation-host-checks.jar"
    commands = {
        "compile": [java, "-Xmx512m", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp, "-d", str(jar)]
                   + list(map(str, sources)),
        "checks": [java, "-Xmx128m", "-cp", str(jar) + os.pathsep + cp,
                   "com.code2hack.eyeprojector.directrg.ChecksKt"],
    }
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    result = {"scope": "Actual passive observer, queue/run-state and host doubles; no Gradle/SDK/Android/device execution",
              "inputs": [{"path": str(path), "sha256": digest(path)} for path in sources + [helper, Path(__file__)]],
              "dependencies": [{"path": str(path), "sha256": digest(path)} for path in jars],
              "commands": commands}
    for name, command in commands.items():
        with (output / (name + ".log")).open("w") as log:
            try:
                completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=45)
                result[name + "Exit"] = completed.returncode
            except subprocess.TimeoutExpired:
                result[name + "Exit"] = 124
                log.write("\nHOST_CHECK_TIMEOUT\n")
        (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print((output / (name + ".log")).read_text(), end="")
        if result[name + "Exit"] != 0:
            raise SystemExit(result[name + "Exit"])


if __name__ == "__main__":
    main()
