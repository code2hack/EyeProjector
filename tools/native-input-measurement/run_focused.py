#!/usr/bin/env python3
"""Compile actual recorder queue and extracted optional-hook/ownership blocks using cached Kotlin only."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    spec = importlib.util.spec_from_file_location('existing_adapter_runner', root/'directrg/src/test/adapter/run.py')
    adapter = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(adapter)
    actual = root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ReceiverActivity.kt'
    helper = root/'directrg/src/androidTest/kotlin/com/code2hack/eyeprojector/directrg/NativeInputObservation.kt'
    body = adapter.activity_lambda(actual.read_text(), r'private inline fun observeNativeInput\(read: \(NativeInputObserver\) -> Unit\)\s*', 'optional observer')
    install = adapter.activity_lambda(helper.read_text(), r'instrumentation.runOnMainSync\s*(?=\{\s*check\(ReceiverActivity.nativeInputObserver)', 'exact install ownership')
    detach = adapter.activity_lambda(helper.read_text(), r'instrumentation.runOnMainSync\s*(?=\{\s*if \(ReceiverActivity.nativeInputObserver === recorder\))', 'exact detach ownership')
    release = adapter.activity_lambda(helper.read_text(), r'instrumentation.runOnMainSync\s*(?=\{\s*application.unregisterActivityLifecycleCallbacks)', 'independent lifecycle release')
    drain = adapter.activity_lambda(helper.read_text(), r'fun drain\(\)\s*', 'per-row writer accounting')
    generated = output/'NativeInputObservationHookChecks.kt'
    generated.write_text('''package com.code2hack.eyeprojector.directrg
private interface NativeInputObserver
private class FakeSink : NativeInputObserver
private object ReceiverActivity { var nativeInputObserver: NativeInputObserver? = null }
private class FakeApplication {
    var registrations = 0
    var failRegistrationAfterEffect = false
    fun registerActivityLifecycleCallbacks(value: Any) { registrations++; if (failRegistrationAfterEffect) error("registration after effect") }
    fun unregisterActivityLifecycleCallbacks(value: Any) { registrations-- }
}
private class Hook {
    var nativeInputObserver: NativeInputObserver? = null
    private inline fun observeNativeInput(read: (NativeInputObserver) -> Unit) ''' + body + '''
    fun route(read: (NativeInputObserver) -> Unit, original: () -> Int): Int {
        observeNativeInput(read)
        return original()
    }
}
private class Ownership {
    val application = FakeApplication()
    val lifecycle = Any()
    val recorder = FakeSink()
    var installed = false
    var lifecycleRegistrationAttempted = false
    var sinkInstallAttempted = false
    var lifecycleReleased = false
    var sinkReleased = false
    var detached = false
    var currentActivity: Any? = Any()
    val boundaries = mutableListOf<String>()
    fun boundary(value: String) { boundaries.add(value) }
    fun install() ''' + install + '''
    fun detach() ''' + detach + '''
    fun releaseLifecycle() ''' + release + '''
}
private class JSONObject(fields: Map<String, Any?>) {
    companion object { val NULL = Any() }
    fun put(key: String, value: Any?): JSONObject = this
    override fun toString() = "primitive-row"
}
private class FakeWriter {
    var writes = 0
    var failOnWrite = 2
    fun write(value: String) { writes++; if (writes == failOnWrite) error("writer failed") }
    fun newLine() {}
    fun flush() {}
}
private class Drain {
    private val recorder = object { val queue = NativeInputObservationQueue() }
    val state = NativeInputObservationRunState(1000)
    val case = "host-check"
    var writer: FakeWriter? = FakeWriter()
    fun drain() ''' + drain + '''
    fun checkPartial() {
        repeat(3) { recorder.queue.capture("key", 1, 1, 2) { emptyMap() } }
        var failure: Throwable? = null
        try { drain() } catch (error: Throwable) { failure = error }
        check(failure != null && state.rowsWritten == 1L && state.exportFailures == 1L)
        writer!!.failOnWrite = -1
        drain()
        check(state.rowsWritten == 2L && recorder.queue.recorded == 3L)
    }
}
fun main() {
    nativeInputObservationQueueChecks()
    val hook = Hook()
    var metadataReads = 0
    var originalCalls = 0
    check(hook.route({ metadataReads++; error("disabled must not read metadata or clocks") }, { ++originalCalls }) == 1)
    check(metadataReads == 0 && originalCalls == 1)
    hook.nativeInputObserver = FakeSink()
    check(hook.route({ metadataReads++; error("observer failed") }, { ++originalCalls }) == 2)
    check(metadataReads == 1 && originalCalls == 2)
    val first = Ownership(); first.install()
    val owned = ReceiverActivity.nativeInputObserver
    val second = Ownership()
    var rejected = false
    try { second.install() } catch (_: IllegalStateException) { rejected = true }
    check(rejected && ReceiverActivity.nativeInputObserver === owned && !second.installed)
    first.detach(); first.releaseLifecycle()
    check(first.detached && ReceiverActivity.nativeInputObserver == null && first.application.registrations == 0)
    check(first.boundaries == listOf("installed"))
    val lost = Ownership(); lost.install()
    val replacement = FakeSink(); ReceiverActivity.nativeInputObserver = replacement
    lost.detach(); lost.releaseLifecycle()
    check(!lost.detached && ReceiverActivity.nativeInputObserver === replacement)
    check(lost.application.registrations == 0)
    ReceiverActivity.nativeInputObserver = null
    val partialRegistration = Ownership()
    partialRegistration.application.failRegistrationAfterEffect = true
    try { partialRegistration.install() } catch (_: IllegalStateException) {}
    check(partialRegistration.lifecycleRegistrationAttempted && !partialRegistration.installed)
    partialRegistration.releaseLifecycle()
    check(partialRegistration.application.registrations == 0)
    Drain().checkPartial()
    println("PASS actual queue snapshot/clock/failure/close/writer behavior; extracted disabled hook/failure containment and exact sink ownership")
}
''')
    cache = Path.home()/'.gradle/caches/modules-2/files-2.1'
    dependencies = [('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21'),
        ('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'),('org.jetbrains.kotlin','kotlin-script-runtime','2.2.21'),
        ('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),
        ('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),('org.jetbrains','annotations','13.0')]
    jars = []
    for group, artifact, version in dependencies:
        matches = list((cache/group/artifact/version).glob('*/'+artifact+'-'+version+'.jar'))
        if len(matches) != 1:
            parser.error('Missing/ambiguous cached dependency '+artifact+'; no downloads performed')
        jars.append(matches[0])
    java = str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    directory = root/'directrg/src/androidTest/kotlin/com/code2hack/eyeprojector/directrg'
    sources = [directory/'NativeInputObservationQueue.kt', directory/'NativeInputObservationRunState.kt',
               directory/'NativeInputObservationQueueChecks.kt', generated]
    cp = os.pathsep.join(map(str, jars))
    jar = output/'native-observation-checks.jar'
    compile_command = [java,'-Xmx512m','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                       '-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(jar)]+list(map(str,sources))
    run_command = [java,'-Xmx128m','-cp',str(jar)+os.pathsep+cp,
                   'com.code2hack.eyeprojector.directrg.NativeInputObservationHookChecksKt']
    digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    result = {'scope':'Actual queue and extracted source hook/ownership blocks, host doubles only; no SDK/Activity/device execution',
              'inputs':[{'path':str(p),'sha256':digest(p)} for p in [actual,helper]+sources+[Path(__file__)]],
              'dependencies':[{'path':str(p),'sha256':digest(p)} for p in jars],
              'commands':{'compile':compile_command,'checks':run_command}}
    with (output/'compile.log').open('w') as log:
        result['compileExit'] = subprocess.run(compile_command,stdout=log,stderr=subprocess.STDOUT,timeout=45).returncode
    if result['compileExit'] == 0:
        with (output/'checks.log').open('w') as log:
            result['checksExit'] = subprocess.run(run_command,stdout=log,stderr=subprocess.STDOUT,timeout=15).returncode
    (output/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    for name in ['compile.log','checks.log']:
        if (output/name).exists(): print((output/name).read_text(),end='')
    raise SystemExit(result['compileExit'] or result.get('checksExit',1))


if __name__ == '__main__':
    main()
