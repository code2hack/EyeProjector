#!/usr/bin/env python3
"""Check actual recorder code and extracted callbacks across a modeled asynchronous main boundary."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess


def ownership_source(adapter, text, class_name, *, parent=False):
    """Keep the source callback bodies AND their actual dispatch choice in the fixture."""
    dispatch = r'instrumentation\.runOnMainSync' if parent else r'onMain'
    patterns = {
        'install': dispatch + r'\s*(?=\{\s*check\(ReceiverActivity.nativeInputObserver)',
        'detach': dispatch + r'\s*(?=\{\s*if \(ReceiverActivity.nativeInputObserver === recorder\))',
        'releaseLifecycle': dispatch + r'\s*(?=\{\s*application.unregisterActivityLifecycleCallbacks)',
    }
    methods = []
    for name, pattern in patterns.items():
        block = adapter.activity_lambda(text, pattern, class_name + ' ' + name)
        spelling = 'instrumentation.runOnMainSync' if parent else 'onMain'
        methods.append('    override fun ' + name + '() { ' + spelling + ' ' + block + ' }')
    if not parent:
        block = adapter.activity_lambda(text, r'fun onMain\(operation: \(\) -> Unit\)\s*', 'actual onMain adapter')
        methods.insert(0, '    private fun onMain(operation: () -> Unit) ' + block)
    return 'private class ' + class_name + ' : Ownership() {\n' + '\n'.join(methods) + '\n}\n'


FIXTURE = r'''package com.code2hack.eyeprojector.directrg

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private interface NativeInputObserver
private class FakeSink : NativeInputObserver
private object ReceiverActivity { @Volatile var nativeInputObserver: NativeInputObserver? = null }

private class MissingAndroidCompletion : RuntimeException("HOST_FIXTURE_DEAD_WAITER_ESCAPE")
private data class DispatchReceipt(val callerId: Long, val workerId: Long, val completed: Boolean,
    val escaped: Throwable?, val joined: Boolean, val order: List<String>)

/** Model target() BEFORE completion/notify. The host-only bound never changes production dispatch. */
private class AsyncInstrumentation {
    val receipts = mutableListOf<DispatchReceipt>()
    var failAfterCompletion: Throwable? = null
    fun runOnMainSync(target: () -> Unit) {
        val caller = Thread.currentThread().id
        val completed = CountDownLatch(1)
        val escaped = AtomicReference<Throwable?>()
        val order = CopyOnWriteArrayList<String>()
        val worker = Thread({
            order.add("target-start")
            target() // Deliberately no catch: an uncontained operation skips the statements below.
            order.add("target-return")
            order.add("completion-notify")
            completed.countDown()
        }, "modeled-android-main-" + receipts.size)
        worker.isDaemon = true // A failed fixture must never strand the host JVM; joining is still asserted.
        worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
            escaped.set(error) // Observation only: NEVER relay this failure as if Android transported it.
            order.add("uncaught-worker-throw")
        }
        worker.start()
        try {
            completed.await(1, TimeUnit.SECONDS)
        } finally {
            worker.join(2000)
            if (worker.isAlive) { worker.interrupt(); worker.join(2000) }
            val normal = completed.count == 0L
            order.add(if (normal) "dispatcher-return" else "host-dead-waiter-escape")
            receipts.add(DispatchReceipt(caller, worker.id, normal, escaped.get(), !worker.isAlive, order.toList()))
            check(!worker.isAlive) { "HOST_FIXTURE_WORKER_NOT_JOINED" }
        }
        if (completed.count != 0L) throw MissingAndroidCompletion()
        failAfterCompletion?.let { throw it }
    }
    fun cleanBoundary(): Boolean = receipts.all { row ->
        row.joined && row.callerId != row.workerId && row.completed && row.escaped == null &&
            row.order.indexOf("target-return") < row.order.indexOf("completion-notify") &&
            row.order.indexOf("completion-notify") < row.order.indexOf("dispatcher-return")
    }
}

private class FakeApplication {
    var registrations = 0
    var failRegistrationAfterEffect = false
    val registrationFailure = IllegalStateException("registration after effect")
    var unregisterFailure: Throwable? = null
    fun registerActivityLifecycleCallbacks(value: Any) {
        registrations++
        if (failRegistrationAfterEffect) throw registrationFailure
    }
    fun unregisterActivityLifecycleCallbacks(value: Any) {
        unregisterFailure?.let { throw it }
        registrations--
    }
}

private class Hook {
    var nativeInputObserver: NativeInputObserver? = null
    private inline fun observeNativeInput(read: (NativeInputObserver) -> Unit) @HOOK@
    fun route(read: (NativeInputObserver) -> Unit, original: () -> Int): Int {
        observeNativeInput(read)
        return original()
    }
}

private abstract class Ownership {
    val instrumentation = AsyncInstrumentation()
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
    var listenerReleased = false
    var inventoryQuit = false
    var inventoryJoined = false
    var queueClosed = false
    var traceDrained = false
    var traceFlushed = false
    var writerClosed = false
    var streamClosed = false
    var flushFailure: Throwable? = null
    fun boundary(value: String) { boundaries.add(value) }
    abstract fun install()
    abstract fun detach()
    abstract fun releaseLifecycle()
    fun cleanup(state: NativeInputObservationRunState) {
        // Use the actual run state's per-leg failure recorder with the actual extracted UI bodies.
        state.attempt("observerDetach") { detach() }
        state.attempt("lifecycleUnregister") { if (lifecycleRegistrationAttempted) releaseLifecycle() }
        state.attempt("inputListenerUnregister") { listenerReleased = true }
        state.attempt("inventoryQuit") { inventoryQuit = true }
        state.attempt("inventoryJoin") { inventoryJoined = true }
        state.attempt("queueClose") { queueClosed = true }
        state.attempt("traceDrain") { traceDrained = true }
        state.attempt("traceFlush") { traceFlushed = true; flushFailure?.let { throw it } }
        state.attempt("traceClose") { writerClosed = true }
        state.attempt("traceStreamClose") { streamClosed = true }
    }
    fun laterCleanupRan() = listenerReleased && inventoryQuit && inventoryJoined && queueClosed &&
        traceDrained && traceFlushed && writerClosed && streamClosed
}

@CURRENT_OWNERSHIP@
@PARENT_OWNERSHIP@

private fun occupiedCase(make: () -> Ownership, label: String) {
    val other = FakeSink()
    ReceiverActivity.nativeInputObserver = other
    val participant = make()
    val state = NativeInputObservationRunState(1000)
    var callerFailure: Throwable? = null
    try { participant.install() }
    catch (error: Throwable) { callerFailure = error; state.failed(error) }
    finally { participant.cleanup(state) }
    val desired = callerFailure is IllegalStateException &&
        callerFailure?.message == "Another observation owns the sink" && state.primary === callerFailure &&
        !participant.installed && !participant.lifecycleRegistrationAttempted &&
        ReceiverActivity.nativeInputObserver === other && participant.laterCleanupRan() &&
        participant.instrumentation.cleanBoundary() && !state.captureComplete
    println("ASYNC_CASE label=" + label + " kind=occupied desiredOutcome=" + desired +
        " callerFailure=" + callerFailure?.javaClass?.simpleName +
        " escaped=" + participant.instrumentation.receipts.count { it.escaped != null } +
        " completed=" + participant.instrumentation.receipts.map { it.completed } +
        " joined=" + participant.instrumentation.receipts.all { it.joined } +
        " laterCleanup=" + participant.laterCleanupRan())
    ReceiverActivity.nativeInputObserver = null
    check(desired) { "DESIRED_OCCUPIED_FAILURE_TRANSPORT" }
}

private fun releaseCase(make: () -> Ownership, label: String) {
    ReceiverActivity.nativeInputObserver = null
    val participant = make()
    val state = NativeInputObservationRunState(1000)
    val original = IllegalStateException("original measurement failure")
    val releaseFailure = AssertionError("lifecycle unregister failure") // Throwable, not merely Exception.
    val writerFailure = IllegalArgumentException("later writer flush failure")
    try {
        participant.install()
        state.failed(original)
        participant.application.unregisterFailure = releaseFailure
        participant.flushFailure = writerFailure
    } finally { participant.cleanup(state) }
    val desired = state.primary === original &&
        original.suppressed.toList() == listOf(releaseFailure, writerFailure) &&
        state.outcomes["lifecycleUnregister"] == "FAILED:AssertionError" &&
        state.outcomes["traceFlush"] == "FAILED:IllegalArgumentException" &&
        state.outcomes["traceClose"] == "SUCCESS" && state.outcomes["traceStreamClose"] == "SUCCESS" &&
        !participant.lifecycleReleased && participant.application.registrations == 1 &&
        participant.detached && ReceiverActivity.nativeInputObserver == null &&
        participant.laterCleanupRan() && participant.instrumentation.cleanBoundary() && !state.captureComplete
    println("ASYNC_CASE label=" + label + " kind=release desiredOutcome=" + desired +
        " primaryPreserved=" + (state.primary === original) +
        " releaseIdentitySuppressed=" + original.suppressed.any { it === releaseFailure } +
        " escaped=" + participant.instrumentation.receipts.count { it.escaped != null } +
        " completed=" + participant.instrumentation.receipts.map { it.completed } +
        " joined=" + participant.instrumentation.receipts.all { it.joined } +
        " laterCleanup=" + participant.laterCleanupRan())
    check(desired) { "DESIRED_RELEASE_FAILURE_TRANSPORT" }
}

private fun sideEffectingRegistrationCase() {
    ReceiverActivity.nativeInputObserver = null
    val participant = CurrentOwnership()
    val state = NativeInputObservationRunState(1000)
    participant.application.failRegistrationAfterEffect = true
    var callerFailure: Throwable? = null
    try { participant.install() }
    catch (error: Throwable) { callerFailure = error; state.failed(error) }
    finally { participant.cleanup(state) }
    check(callerFailure === participant.application.registrationFailure && state.primary === callerFailure)
    check(participant.lifecycleRegistrationAttempted && !participant.installed && !participant.sinkInstallAttempted)
    check(participant.lifecycleReleased && participant.application.registrations == 0)
    check(ReceiverActivity.nativeInputObserver == null && participant.laterCleanupRan())
    check(participant.instrumentation.cleanBoundary() && !state.captureComplete)
    println("PASS async side-effecting registration failure retains identity and releases lifecycle/listener/thread/writer legs")
}

private fun helperTransportControls() {
    val instrumentation = AsyncInstrumentation()
    val caller = Thread.currentThread().id
    var operationThread = caller
    nativeInputObservationOnMain({ task -> instrumentation.runOnMainSync(task) }) {
        operationThread = Thread.currentThread().id
    }
    check(operationThread != caller && instrumentation.cleanBoundary())
    val schedulingFailure = IllegalStateException("dispatch failed before callback")
    var ran = false
    var caught: Throwable? = null
    try { nativeInputObservationOnMain({ _ -> throw schedulingFailure }) { ran = true } }
    catch (error: Throwable) { caught = error }
    check(caught === schedulingFailure && !ran)
    val original = AssertionError("operation failure before dispatcher failure")
    val dispatcherFailure = IllegalArgumentException("dispatch failed after callback completion")
    instrumentation.failAfterCompletion = dispatcherFailure
    caught = null
    try { nativeInputObservationOnMain({ task -> instrumentation.runOnMainSync(task) }) { throw original } }
    catch (error: Throwable) { caught = error }
    check(caught === original && original.suppressed.toList() == listOf(dispatcherFailure))
    check(instrumentation.cleanBoundary())
    println("PASS actual helper async completion/caller identity, scheduling failure and original-plus-dispatch failure suppression")
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
    fun drain() @DRAIN@
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

private object SystemClock {
    fun elapsedRealtimeNanos() = 123_000_000L
    fun uptimeMillis() = 123L
}
private class NativeInputRecorder {
    data class Device(val name: String, val observedElapsedNs: Long)
    val queue = NativeInputObservationQueue()
    val inventory = AtomicReference<Map<Int, Device>>(emptyMap())
}
private class FakeInputDevice(val name: String)
private class FakeInputManager {
    var inputDeviceIds: IntArray = intArrayOf(3, 4, 5)
    fun getInputDevice(id: Int): FakeInputDevice? = if (id == 4) null else FakeInputDevice("device-" + id)
}
private class Inventory {
    val recorder = NativeInputRecorder()
    val manager = FakeInputManager()
    fun inventory() @INVENTORY@
    fun checkIntArray() {
        inventory()
        check(recorder.queue.failureCount == 0L)
        check(recorder.inventory.get().keys == setOf(3, 5))
        check(recorder.inventory.get()[3]?.name == "device-3")
        val rows = mutableListOf<NativeInputObservationQueue.Row>()
        check(recorder.queue.drain(rows::add) == 1L)
        check((rows.single().fields["devices"] as List<*>).size == 2)
        manager.inputDeviceIds = intArrayOf()
        inventory()
        check(recorder.inventory.get().isEmpty() && recorder.queue.failureCount == 0L)
        println("PASS extracted current inventory against IntArray with missing-device and empty-inventory controls; no Android API execution")
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
    ReceiverActivity.nativeInputObserver = null
    val first = CurrentOwnership()
    first.install()
    check(first.installed && first.instrumentation.cleanBoundary())
    first.detach(); first.releaseLifecycle()
    check(first.detached && ReceiverActivity.nativeInputObserver == null && first.application.registrations == 0)
    check(first.boundaries == listOf("installed"))
    val lost = CurrentOwnership()
    lost.install()
    val replacement = FakeSink(); ReceiverActivity.nativeInputObserver = replacement
    lost.detach(); lost.releaseLifecycle()
    check(!lost.detached && ReceiverActivity.nativeInputObserver === replacement && lost.sinkReleased)
    check(lost.application.registrations == 0 && lost.instrumentation.cleanBoundary())
    ReceiverActivity.nativeInputObserver = null
    occupiedCase(::CurrentOwnership, "current")
    releaseCase(::CurrentOwnership, "current")
    sideEffectingRegistrationCase()
    helperTransportControls()
    Drain().checkPartial()
    Inventory().checkIntArray()
    println("PASS actual queue/run-state/helper and source-extracted hook/adapter/ownership/drain/inventory controls; asynchronous host model only, not Android Looper or device qualification")
}

@PARENT_ENTRY@
'''

PARENT_ENTRY = r'''object ParentOwnershipCounterexample {
    @JvmStatic fun main(args: Array<String>) {
        when (args.single()) {
            "occupied" -> occupiedCase(::ParentOwnership, "parent")
            "release" -> releaseCase(::ParentOwnership, "parent")
            else -> error("Unknown parent comparison")
        }
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--parent-helper', type=Path,
                        help='Preserved pre-correction recorder with original raw runOnMainSync callbacks')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    adapter_path = root/'directrg/src/test/adapter/run.py'
    spec = importlib.util.spec_from_file_location('existing_adapter_runner', adapter_path)
    adapter = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(adapter)
    actual = root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ReceiverActivity.kt'
    helper = root/'directrg/src/androidTest/kotlin/com/code2hack/eyeprojector/directrg/NativeInputObservation.kt'
    helper_text = helper.read_text()
    if len(re.findall(r'\bonMain\s*\{', helper_text)) != 4:
        parser.error('Expected exactly four protected diagnostic onMain call sites')
    if len(re.findall(r'\binstrumentation\.runOnMainSync\b', helper_text)) != 1:
        parser.error('Expected the sole raw main dispatch inside the extracted onMain adapter')
    body = adapter.activity_lambda(actual.read_text(),
        r'private inline fun observeNativeInput\(read: \(NativeInputObserver\) -> Unit\)\s*', 'optional observer')
    drain = adapter.activity_lambda(helper_text, r'fun drain\(\)\s*', 'per-row writer accounting')
    inventory = adapter.activity_lambda(helper_text, r'fun inventory\(\)\s*', 'actual IntArray inventory')
    current = ownership_source(adapter, helper_text, 'CurrentOwnership')
    parent_source = ''
    parent_path = args.parent_helper.resolve() if args.parent_helper else None
    if parent_path:
        parent_source = ownership_source(adapter, parent_path.read_text(), 'ParentOwnership', parent=True)
    replacements = {'@HOOK@': body, '@DRAIN@': drain, '@INVENTORY@': inventory,
                    '@CURRENT_OWNERSHIP@': current, '@PARENT_OWNERSHIP@': parent_source,
                    '@PARENT_ENTRY@': PARENT_ENTRY if parent_path else ''}
    generated = output/'NativeInputObservationHookChecks.kt'
    content = FIXTURE
    for token, replacement in replacements.items():
        content = content.replace(token, replacement)
    generated.write_text(content)
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
    inputs = [actual, helper, adapter_path]+sources+[Path(__file__)]+([parent_path] if parent_path else [])
    result = {
        'scope': 'Actual queue/run-state/transport helper plus source-extracted adapter/ownership/drain/inventory; '
                 'separate-worker SyncRunnable ordering model and host doubles only; no Android/SDK/device execution',
        'inputs': [{'path':str(p),'sha256':digest(p)} for p in inputs],
        'dependencies': [{'path':str(p),'sha256':digest(p)} for p in jars],
        'commands': {'compile':compile_command,'checks':run_command},
        'parentComparisons': {},
    }
    result_path = output/'result.json'
    def save():
        result_path.write_text(json.dumps(result, indent=2)+'\n')
    def run_logged(command, filename, seconds):
        with (output/filename).open('w') as log:
            try:
                process = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=seconds)
                return process.returncode, False
            except subprocess.TimeoutExpired:
                log.write('\nHOST_CHECK_TIMEOUT\n')
                return 124, True
    save()
    result['compileExit'], result['compileTimedOut'] = run_logged(compile_command, 'compile.log', 45)
    save()
    if result['compileExit'] == 0:
        if parent_path:
            for case, assertion in [('occupied', 'DESIRED_OCCUPIED_FAILURE_TRANSPORT'),
                                    ('release', 'DESIRED_RELEASE_FAILURE_TRANSPORT')]:
                command = run_command[:-1]+['com.code2hack.eyeprojector.directrg.ParentOwnershipCounterexample', case]
                result['commands']['parent-'+case] = command
                filename = 'parent-'+case+'-red.log'
                exit_code, timed_out = run_logged(command, filename, 15)
                log = (output/filename).read_text()
                marker = 'ASYNC_CASE label=parent kind='+case+' desiredOutcome=false'
                result['parentComparisons'][case] = {
                    'exitCode':exit_code, 'timedOut':timed_out,
                    'expectedRed': exit_code != 0 and not timed_out and marker in log and assertion in log,
                    'scope':'Same desired async ownership/failure/cleanup assertions against extracted parent raw dispatch',
                }
                save()
        result['checksExit'], result['checksTimedOut'] = run_logged(run_command, 'checks.log', 15)
        save()
    for name in ['compile.log','parent-occupied-red.log','parent-release-red.log','checks.log']:
        if (output/name).exists():
            print((output/name).read_text(), end='')
    comparisons_ok = not parent_path or (len(result['parentComparisons']) == 2 and
        all(row['expectedRed'] for row in result['parentComparisons'].values()))
    raise SystemExit(result['compileExit'] or result.get('checksExit',1) or (0 if comparisons_ok else 1))


if __name__ == '__main__':
    main()
