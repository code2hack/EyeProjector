#!/usr/bin/env python3
"""Finite cached actual-source original-target and fixture-finalization checks; no SDK/device.

The Activity's exact new capture/target declarations (or exact preserved mutable
Disconnect for the semantic parent counterexample) are compiled in a field-only
Activity scaffold. Actual Owner/Session/Client/Store and retained-session fixture
run against repository Android/GL/codec/TLS doubles. Actual test recorder and
Instrumentation dispatch/finally methods are extracted, not reimplemented.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time

METHODS = [
    'teardown332OriginalOwnerSurvivesActivityReplacement',
    'teardown332EmptyTriggerUsesCapturedHOnlyAndExistingAck',
    'teardown332NonemptyOriginalScopeAndNullableH',
    'teardown332MissingOrMismatchedBindingRemainsUnknown',
    'teardown332SameActivityNewerActivationIsNeverAdopted',
    'teardown332ClosedOwnerInlineCallbackIsNotCancellationProof',
    'teardown332PersistenceFailureReturnsPendingWithoutRetry',
    'teardown332CallbackFailureCannotEscapeReconciliation',
    'teardown332ActualFixtureFinalizationAndLifecycle',
    'teardown332MissingTargetKeepsIndependentFinish',
    'teardown332ExpiredCeilingNeverResetsOrWaits',
    'teardown332PendingCompletionUsesOnlyRemainingCeiling',
    'teardown332MainBeforeAndAfterSideEffectFailures',
    'teardown332DestroyThrowPreservesPrimaryAndOtherLegs',
    'teardown332StatusSuccessorMissingAndOldTerminalTypedSeparately',
    'teardown332StatusAndExportFailureRemainDiagnostic',
    'teardown332QueuedCaptureCannotRepublishAfterRelease',
    'teardown332QueuedRequestCannotCancelAfterRelease',
    'teardown332OrdinaryOperationDoesNotInstallRecorder',
    'teardown332MissingActivityNeverClaimsFinishReturn',
]

JSON_DOUBLE = r'''package org.json
object JsonFault {
    var onceAtKey: String? = null
    fun reset() { onceAtKey = null }
    fun check(key: String) { if (key == onceAtKey) { onceAtKey = null; throw AssertionError("modeled JSON failure") } }
}
class JSONObject {
    companion object { val NULL = Any() }
    val fields = linkedMapOf<String, Any?>()
    constructor()
    constructor(values: Map<*, *>) { values.forEach { (key, value) -> fields[key.toString()] = value ?: NULL } }
    fun put(key: String, value: Any?): JSONObject { JsonFault.check(key); fields[key] = value ?: NULL; return this }
    fun put(key: String, value: Int) = put(key, value as Any)
    fun put(key: String, value: Long) = put(key, value as Any)
    fun put(key: String, value: Double) = put(key, value as Any)
    fun put(key: String, value: Boolean) = put(key, value as Any)
    fun has(key: String) = fields.containsKey(key)
    fun get(key: String): Any = fields[key] ?: error("Missing " + key)
    fun opt(key: String): Any? = fields[key]
    fun optString(key: String, fallback: String = "") = fields[key]?.takeUnless { it === NULL }?.toString() ?: fallback
    fun optLong(key: String, fallback: Long = 0) = (fields[key] as? Number)?.toLong() ?: fallback
    fun optBoolean(key: String, fallback: Boolean = false) = fields[key] as? Boolean ?: fallback
    fun getString(key: String) = get(key) as String
    fun getLong(key: String) = (get(key) as Number).toLong()
    fun getBoolean(key: String) = get(key) as Boolean
    fun getJSONObject(key: String) = get(key) as JSONObject
    fun obj(key: String) = get(key) as JSONObject
    fun array(key: String) = get(key) as JSONArray
    fun long(key: String) = (get(key) as Number).toLong()
    fun bool(key: String) = get(key) as Boolean
    override fun toString() = fields.toString()
}
class JSONArray {
    val values = mutableListOf<Any?>()
    fun put(value: Any?): JSONArray { values.add(value); return this }
    fun length() = values.size
    fun obj(index: Int) = values[index] as JSONObject
}
'''

ANDROID_DOUBLE = r'''package android.app
import java.util.concurrent.atomic.AtomicReference
open class Activity {
    companion object { const val RESULT_OK=0; const val RESULT_CANCELED=1 }
    var finishCount = 0
    var destroyCount = 0
    var isFinishing = false
    var isDestroyed = false
    var finishAction: (() -> Unit)? = null
    var destroyFailure: Throwable? = null
    fun finish() { finishCount++; isFinishing = true; BoundaryTrace.steps.add("activity-finish"); finishAction?.invoke() }
    fun destroy() { destroyCount++; destroyFailure?.let { throw it }; isDestroyed = true }
}
object BoundaryTrace { val steps = java.util.concurrent.CopyOnWriteArrayList<String>() }
open class Instrumentation {
    var mainCalls = 0
    var queueAtCall: Int? = null
    val queued = mutableListOf<() -> Unit>()
    fun drainQueued() { val work=queued.toList();queued.clear();work.forEach { it() } }
    var beforeFailure: Throwable? = null
    var afterFailure: Throwable? = null
    var completed = 0
    var joined = 0
    open fun callActivityOnDestroy(activity: Activity) { activity.destroy() }
    fun finish(code: Int, result: Any) { BoundaryTrace.steps.add("instrumentation-finish") }
    fun runOnMainSync(target: () -> Unit) {
        mainCalls++
        if (mainCalls == queueAtCall) { queued.add(target); throw AssertionError("queued main dispatch then threw") }
        beforeFailure?.let { throw it }
        val escaped = AtomicReference<Throwable?>()
        val worker = Thread({ target(); completed++ }, "teardown-modeled-main")
        worker.isDaemon = true
        worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> escaped.set(error) }
        worker.start(); worker.join(2000)
        if (worker.isAlive) { worker.interrupt(); worker.join(1000) }
        check(!worker.isAlive) { "HOST_MODELED_MAIN_NOT_JOINED" }; joined++
        escaped.get()?.let { throw it }
        afterFailure?.let { throw it }
    }
}
'''

ACTIVITY_SCAFFOLD = r'''package com.code2hack.eyeprojector.directrg
import android.app.Activity

/** Field-only receiver context. Every target declaration/dispatch below is actual source. */
internal class ReceiverActivity : Activity() {
    internal data class FixtureBinder(val owner: ReceiverSessionOwner, val feature: ReceiverActivationStore)
    private var owner: ReceiverSessionOwner? = null
    private var serviceBinder: FixtureBinder? = null
    private lateinit var launchScope: ReceiverLaunchScope
    private var visibleActivation: String? = null
    private var session: ProjectionSession? = null
    private var connectBeforeBinding = false
    private var disconnectBeforeBinding = false
    var inputCancellations = 0
    private fun cancelInput() { inputCancellations++ }
    fun bind(owner: ReceiverSessionOwner?, feature: ReceiverActivationStore?, scope: ReceiverLaunchScope?,
             activation: String?, session: ProjectionSession?, binderOwner: ReceiverSessionOwner? = owner) {
        this.owner = owner
        serviceBinder = if (feature != null && binderOwner != null) FixtureBinder(binderOwner, feature) else null
        if (scope != null) launchScope = scope
        visibleActivation = activation; this.session = session
    }
@TARGET_DECLARATIONS@
@CAPTURE@
@LEGACY_DISCONNECT@
}
'''

DRIVER_SCAFFOLD = r'''package com.code2hack.eyeprojector.directrg
import android.app.Activity
import android.app.Instrumentation
import android.os.SystemClock
import org.json.JSONObject

@PHASE@

/** Real test method bodies with explicit Android/status boundaries. */
internal class FixtureBundle { fun putString(key:String,value:String) {} }
internal class AudioManager {
    companion object { const val STREAM_MUSIC=3 }
    fun abandonAudioFocusRequest(value:Any) { android.app.BoundaryTrace.steps.add("audio-focus") }
    fun setStreamVolume(stream:Int,volume:Int,flags:Int) { android.app.BoundaryTrace.steps.add("volume-restore") }
    fun getStreamVolume(stream:Int)=1
}
internal class FixtureFile {
    val name="owned-probe.json"
    var written: String?=null
    fun writeText(value:String) { written=value;android.app.BoundaryTrace.steps.add("report-write") }
    fun length()=(written?.length?:0).toLong()
}
internal class WindowPresentationRecorder {
    fun finish(instrumentation:Any,report:JSONObject,status:()->JSONObject,minimumStart:Long) { android.app.BoundaryTrace.steps.add("window-recorder-finish") }
}
internal typealias Bundle=FixtureBundle
internal class TeardownDriver : Instrumentation() {
    @Volatile private var fixtureTeardownRecorder: FixtureTeardownRecorder? = null
    private var setupReport: JSONObject? = null
    private var fixtureSetupPhase: FixtureSetupPhase? = null
    private var baselineActivity: Activity? = null
    private fun publishFixtureSetup(phase:FixtureSetupPhase,stage:String,quiet:Boolean) {}
    private var freshAfterNs = 0L
    private var runStartedNs: Long? = 0L
    var statusSource: () -> JSONObject = { error("Missing status boundary") }
    private fun status(): JSONObject = statusSource()
@CAPTURE_METHOD@
@DESTROY_METHOD@
@FINISH_METHOD@
    fun capture(activity: Activity?, first: JSONObject, deadlineElapsedMs: Long?, operation: String = "static-content") {
        val phase = deadlineElapsedMs?.let { FixtureSetupPhase("case", 0L, it - 180_000L) }
        captureFixtureTeardown(activity, first, phase, operation)
    }
    fun finish(activity: Activity?, report: JSONObject) { finishFixtureTeardown(activity, report) }
    fun released() = fixtureTeardownRecorder == null
    fun closeUsingActualFinally(activity:Activity?,report:JSONObject,primary:Throwable?=null) {
        var measurementStarted:Long?=SystemClock.elapsedRealtime()
        var measurementEnded:Long?=null
        val windowPresentationRecorder:WindowPresentationRecorder?=WindowPresentationRecorder()
        val audioManager=AudioManager();val testFocus:Any?=Any();val priorVolume:Int?=1
        val ownsRecord=true;val file:FixtureFile?=FixtureFile()
        try { if(primary!=null)throw primary }
        finally @ACTUAL_FINALLY@
    }
}
'''

TESTS = r'''
    // Appended ONLY to a private copy of actual RetainedReceiverSessionTest.
    private fun start332(f: Fixture) = f.owner.session!!.observationStartedMonoNs
    private fun activity332(f: Fixture, h: String? = "H2", trigger: String = "current-trigger") = ReceiverActivity().apply {
        bind(f.owner, f.feature, ReceiverLaunchScope(if (trigger.isEmpty()) "wake-scope" else "current-scope", trigger, 8), h, f.owner.session)
    }
    private fun target332(activity: ReceiverActivity, s: Long): () -> Unit = @TARGET_ACQUIRE@
    private fun reconcile332(f: Fixture) {
        val client = f.owner.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(f.owner)
        client.javaClass.getDeclaredMethod("reconcile").apply { isAccessible = true }.invoke(client)
        f.pump()
    }
    private fun first332(s: Long): org.json.JSONObject = org.json.JSONObject().put("state", "STREAMING")
        .put("startedMonoNs", s).put("elapsedMs", 0L)
    private fun report332() = org.json.JSONObject().put("passed", true)
    private fun status332(s: Long, state: String = "STOPPED", elapsed: Long = 0) = org.json.JSONObject()
        .put("state", state).put("startedMonoNs", s).put("elapsedMs", elapsed)
    private fun detail332(report: org.json.JSONObject): org.json.JSONObject = report.obj("fixtureTeardown")
    private fun values332(value: Any?): List<Any?> = when (value) {
        is org.json.JSONObject -> value.fields.values.flatMap(::values332)
        is org.json.JSONArray -> value.values.flatMap(::values332)
        else -> listOf(value)
    }
    private fun find332(report: org.json.JSONObject, key: String): Any? {
        fun visit(value: Any?): Any? = when(value) {
            is org.json.JSONObject -> if(value.has(key)) value.opt(key) else value.fields.values.firstNotNullOfOrNull(::visit)
            is org.json.JSONArray -> value.values.firstNotNullOfOrNull(::visit)
            else -> null
        }
        return visit(report)
    }
    private fun expired332() = android.os.SystemClock.elapsedRealtime() - 1L

    @Test fun teardown332OriginalOwnerSurvivesActivityReplacement() {
        Fixture(withFeature = true).use { original -> Fixture(withFeature = true).use { later ->
            val activity = activity332(original); val cancel = target332(activity, start332(original))
            activity.bind(later.owner, later.feature, ReceiverLaunchScope("current-scope", "current-trigger", 8), "H2", later.owner.session)
            cancel(); original.pump(); later.pump()
            assertTrue("ORIGINAL_OWNER_RECEIVES_SCOPED_TEARDOWN", "H2" in original.feature!!.state.canceled)
            assertFalse("SUCCESSOR_OWNER_MUST_NOT_RECEIVE_TEARDOWN", "H2" in later.feature!!.state.canceled)
            assertNotNull(later.owner.session)
        } }
    }

    @Test fun teardown332EmptyTriggerUsesCapturedHOnlyAndExistingAck() {
        Fixture(withFeature = true).use { f ->
            val target = activity332(f, trigger = "").captureFixtureTeardownTarget(start332(f))!!
            var callbacks = 0; target.disconnect { callbacks++ }
            assertTrue("H2" in f.feature!!.state.pendingCancel)
            assertFalse(f.feature.state.triggers.containsKey(""))
            assertEquals(0, callbacks)
            f.feature.accept(ActivationReply(cancelAcknowledged = "H2")); reconcile332(f)
            assertEquals("Existing H-only completion must not await nonexistent empty trigger", 1, callbacks)
            val observation = target.observe()
            assertFalse(observation.originalTriggerCaptured)
            assertTrue(observation.originalActivationCaptured)
            assertEquals(false, observation.originalActivationPendingCancel)
            assertEquals(true, observation.originalActivationCancellationRecorded)
        }
    }
    @Test fun teardown332NonemptyOriginalScopeAndNullableH() {
        Fixture(withFeature = true).use { f ->
            val target = activity332(f, h = null).captureFixtureTeardownTarget(start332(f))!!
            var done = 0; target.disconnect { done++ }
            assertTrue(f.feature!!.state.triggers.getValue("current-trigger").canceled)
            assertEquals(0, done)
            f.feature.accept(ActivationReply(trigger = TriggerReceipt("current-trigger", "H2", TriggerDisposition.CANCELED)))
            reconcile332(f); assertEquals(1, done)
            assertTrue(target.observe().originalTriggerCaptured)
            assertFalse(target.observe().originalActivationCaptured)
            assertNull(target.observe().originalActivationPendingCancel)
        }
    }
    @Test fun teardown332MissingOrMismatchedBindingRemainsUnknown() {
        Fixture(withFeature = true).use { f -> Fixture(withFeature = true).use { other ->
            val s = start332(f)
            assertNull(ReceiverActivity().captureFixtureTeardownTarget(s))
            assertNull(activity332(f).captureFixtureTeardownTarget(s + 1))
            assertNull(activity332(f, h = null, trigger = "").captureFixtureTeardownTarget(s))
            assertNull(activity332(f, h = "wrong-H").captureFixtureTeardownTarget(s))
            assertNull(ReceiverActivity().apply { bind(f.owner, f.feature, null, "H2", f.owner.session) }.captureFixtureTeardownTarget(s))
            assertNull(ReceiverActivity().apply { bind(f.owner, f.feature, ReceiverLaunchScope("current-scope", "current-trigger",8), "H2", f.owner.session, other.owner) }.captureFixtureTeardownTarget(s))
            assertNull(ReceiverActivity().apply { bind(f.owner, f.feature, ReceiverLaunchScope("current-scope", "missing-trigger",8), "H2", f.owner.session) }.captureFixtureTeardownTarget(s))
            assertNull(ReceiverActivity().apply { bind(f.owner, f.feature, ReceiverLaunchScope("old-scope", "old-trigger",7), "H2", f.owner.session) }.captureFixtureTeardownTarget(s))
            assertNull(ReceiverActivity().apply { bind(f.owner, f.feature, ReceiverLaunchScope("old-scope", "old-trigger",7), null, f.owner.session) }.captureFixtureTeardownTarget(s))
            assertTrue(f.feature!!.state.canceled.isEmpty())
        } }
    }
    @Test fun teardown332SameActivityNewerActivationIsNeverAdopted() {
        Fixture(withFeature = true).use { f ->
            val activity = activity332(f); val target = activity.captureFixtureTeardownTarget(start332(f))!!
            f.feature!!.accept(ActivationReply(ActivationDirective("H3", true, 2, "successor-owner")))
            activity.bind(f.owner, f.feature, ReceiverLaunchScope("new-scope", "new-trigger",9), "H3", f.owner.session)
            target.disconnect { }; f.pump()
            assertTrue("H2" in f.feature.state.canceled); assertFalse("H3" in f.feature.state.canceled)
            assertEquals("H3", f.feature.directive!!.activationId)
            assertEquals(false, target.observe().currentActivationMatchesOriginal)
        }
    }
    @Test fun teardown332ClosedOwnerInlineCallbackIsNotCancellationProof() {
        Fixture(withFeature = true).use { f ->
            val target = activity332(f).captureFixtureTeardownTarget(start332(f))!!
            f.owner.close(); var done = 0; target.disconnect { done++ }
            assertEquals(1, done)
            assertEquals(false, target.observe().originalActivationCancellationRecorded)
            assertFalse(target.observe().ownerSessionPresent)
        }
    }
    @Test fun teardown332PersistenceFailureReturnsPendingWithoutRetry() {
        Fixture(withFeature = true).use { f ->
            val target = activity332(f).captureFixtureTeardownTarget(start332(f))!!
            val file = f.feature!!.javaClass.getDeclaredField("file").apply { isAccessible = true }.get(f.feature) as java.io.File
            val parent = file.parentFile; val backup = java.io.File(parent.parentFile, parent.name + "-saved")
            assertTrue(parent.renameTo(backup)); parent.writeText("not a directory")
            try {
                var done = 0; target.disconnect { done++ }
                assertEquals(0, done); assertEquals("CANCEL_PENDING", f.owner.snapshot().state)
                val pending = f.owner.javaClass.getDeclaredField("pendingCancellations").apply { isAccessible = true }.get(f.owner) as Map<*, *>
                assertEquals(1, pending.size)
            } finally { assertTrue(parent.delete()); assertTrue(backup.renameTo(parent)) }
        }
    }
    @Test fun teardown332CallbackFailureCannotEscapeReconciliation() {
        Fixture(withFeature = true).use { f ->
            val target = activity332(f, trigger = "").captureFixtureTeardownTarget(start332(f))!!
            var other = 0; target.disconnect { throw AssertionError("diagnostic callback") }; target.disconnect { other++ }
            f.feature!!.accept(ActivationReply(cancelAcknowledged = "H2")); reconcile332(f)
            assertEquals(1, other)
        }
    }

@HELPER_TESTS@
'''

RUNNER = r'''package com.code2hack.eyeprojector.directrg
import org.junit.runner.JUnitCore
import org.junit.runner.Request
fun main(args: Array<String>) {
    var failed = false
    args.forEach { name ->
        val started = System.nanoTime(); val result = JUnitCore().run(Request.method(RetainedReceiverSessionTest::class.java, name))
        println("CASE " + name + " run=" + result.runCount + " failed=" + result.failureCount + " elapsedMs=" + (System.nanoTime()-started)/1_000_000)
        result.failures.forEach { println(it.trace) }; failed = failed || !result.wasSuccessful()
    }
    val live = Thread.getAllStackTraces().keys.filter { it.isAlive &&
        (it.name == "eyeprojector-video-output" || it.name.startsWith("eyeprojector-decoder") || it.name.startsWith("pool-") || it.name.startsWith("teardown-") && it.name != "main") }
    println("OWNED_THREAD_CLOSURE live=" + live.map { it.name }.sorted())
    if (failed || live.isNotEmpty()) kotlin.system.exitProcess(1)
}
'''


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def block(source, pattern):
    """Exact Kotlin declaration through its balanced braces, skipping strings/comments."""
    match = re.search(pattern, source, re.M)
    if not match:
        raise ValueError('Missing actual declaration '+pattern)
    start = match.start(); opening = source.index('{', match.end())
    level = 1; pos = opening + 1; quote = None; line_comment = False; block_comment = False
    while pos < len(source):
        char = source[pos]; pair = source[pos:pos+2]
        if line_comment:
            if char == '\n': line_comment = False
        elif block_comment:
            if pair == '*/': block_comment = False; pos += 1
        elif quote:
            if char == '\\': pos += 1
            elif char == quote: quote = None
        elif pair == '//': line_comment = True; pos += 1
        elif pair == '/*': block_comment = True; pos += 1
        elif char in ('"', "'"): quote = char
        elif char == '{': level += 1
        elif char == '}':
            level -= 1
            if level == 0: return source[start:pos+1]
        pos += 1
    raise ValueError('Unterminated actual source declaration '+pattern)


def proc_record(pid):
    try:
        p = Path('/proc')/str(pid); stat = (p/'stat').read_text(); fields = stat[stat.rfind(')')+2:].split()
        return {'pid':pid,'ppid':int(fields[1]),'processGroup':int(fields[2]),'session':int(fields[3]),
                'startTicks':int(fields[19]),'state':fields[0],
                'cwd':os.readlink(p/'cwd'),
                'command':(p/'cmdline').read_bytes().replace(b'\0',b' ').decode(errors='replace')}
    except (FileNotFoundError,ProcessLookupError,PermissionError):
        return None


def owned(command, path, limit, deadline):
    started = time.monotonic(); allowance = min(limit,deadline-started)
    if allowance <= 0: return {'exit':124,'timedOut':True,'notStarted':'overall120s exhausted'}
    samples=[]; identities={}
    with path.open('w') as log:
        child=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,cwd=path.parent,start_new_session=True)
        first=proc_record(child.pid)
        if first: identities[child.pid]=first
        timed_out=False
        while child.poll() is None:
            table={int(p.name):row for p in Path('/proc').iterdir() if p.name.isdigit() and (row:=proc_record(int(p.name)))}
            found={child.pid}; previous=None
            while found != previous:
                previous=set(found); found|={pid for pid,row in table.items() if row['ppid'] in found}
            for pid in found:
                if pid in table: identities.setdefault(pid,table[pid])
            live=[row for pid,birth in identities.items() if (row:=table.get(pid)) and row['startTicks']==birth['startTicks']]
            signature=[(r['pid'],r['state'],r['ppid']) for r in live]
            if not samples or signature!=samples[-1]['signature']:
                samples.append({'elapsedSeconds':round(time.monotonic()-started,4),'signature':signature,'live':live})
            if time.monotonic()-started >= allowance:
                timed_out=True; os.killpg(child.pid,signal.SIGTERM)
                try: child.wait(timeout=2)
                except subprocess.TimeoutExpired: os.killpg(child.pid,signal.SIGKILL);child.wait(timeout=2)
                break
            time.sleep(.05)
        child.wait()
        terminal=[]
        for pid,birth in identities.items():
            row=proc_record(pid)
            if row and row['startTicks']==birth['startTicks']:terminal.append(row)
        original_group=[]
        for p in Path('/proc').iterdir():
            if p.name.isdigit() and (row:=proc_record(int(p.name))) and row['processGroup']==child.pid:original_group.append(row)
    return {'pid':child.pid,'birth':first,'processGroup':child.pid,'exit':child.returncode,'reaped':True,
            'timedOut':timed_out,'elapsedSeconds':round(time.monotonic()-started,4),
            'sampledIdentities':list(identities.values()),'samples':samples,'liveAtTerminal':terminal,
            'originalGroupAtTerminal':original_group,'scope':'Sampled identities/points only; no continuous absence claim'}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir',type=Path,required=True)
    parser.add_argument('--parent-activity',type=Path)
    parser.add_argument('--repo-root',type=Path,help='Explicit repository for executing a preserved runner snapshot')
    parser.add_argument('--source-mode',choices=['current','parent'],default='current')
    args=parser.parse_args()
    if args.source_mode=='parent' and args.parent_activity is None:parser.error('parent mode needs preserved before Activity')
    root=args.repo_root.resolve() if args.repo_root else Path(__file__).resolve().parents[2];output=args.output_dir.resolve();output.mkdir(parents=True,exist_ok=False)
    deadline=time.monotonic()+116
    glass=root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg';androidtest=root/'directrg/src/androidTest/kotlin/com/code2hack/eyeprojector/directrg';tests=root/'directrg/src/test';protocol=root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
    activity=glass/'ReceiverActivity.kt';driver=androidtest/'ProjectionInstrumentation.kt';helper=androidtest/'FixtureTeardownObservation.kt'
    result={'scope':'Actual extracted Activity target and Instrumentation methods, actual test helper, actual Owner/Session/Client/Store with repository Android/GL/codec/TLS doubles; no Android/device/native/H proof',
            'sourceMode':args.source_mode,'overallBudgetSeconds':120,'inputs':[],'dependencies':[],'commands':{},'processes':{},'cases':[]}
    try:
        source=activity.read_text();instrumentation=driver.read_text()
        contract={}
        if args.parent_activity:
            before=args.parent_activity.read_text()
            for name in ['disconnect','onDestroy','connect','onPause','finishProjection','exitToLauncher','cancelInput']:
                pattern=r'^    (?:private |internal |override )?fun '+name+r'\('
                contract['unchangedProductBody:'+name]=block(source,pattern)==block(before,pattern)
            # Full Activity differs only by its allocated target declarations/factory insertion.
            start=source.index('    /** Test-only holder: original references stay private')
            end=source.index('    /** Main-thread diagnostic reads only;',start)
            contract['onlyAllocatedActivityInsertion']=source[:start]+source[end:]==before
        capture_line='                    captureFixtureTeardown(activity, report.getJSONObject("first"), fixtureSetup, operation)'
        contract['exactSingleCaptureOutsideMeasurement']=instrumentation.count(capture_line)==1 and instrumentation.index(capture_line)<instrumentation.index('                    val start = SystemClock.elapsedRealtime()',instrumentation.index(capture_line))
        contract['noNewFixtureTimeout']=all(token not in helper.read_text() for token in ['Thread.sleep(', '360_000', '15_000', '+ 180_000']) if args.source_mode=='current' else True
        result['sourceContract']=contract
        if not all(contract.values()):raise ValueError('Actual source boundary contract failed: '+str(contract))
        declarations=source[source.index('    internal interface FixtureTeardownTarget'):source.index('    /** Main-thread, fixture-local capture')]
        factory=block(source,r'^    internal fun captureFixtureTeardownTarget\(')
        legacy_source=args.parent_activity.read_text() if args.source_mode=='parent' else source
        legacy=block(legacy_source,r'^    internal fun disconnect\(')
        actual_activity=ACTIVITY_SCAFFOLD.replace('@TARGET_DECLARATIONS@',declarations).replace('@CAPTURE@',factory if args.source_mode=='current' else '').replace('@LEGACY_DISCONNECT@',legacy)
        generated_activity=output/'ReceiverActivity.kt';generated_activity.write_text(actual_activity)
        selected=METHODS if args.source_mode=='current' else METHODS[:1]
        alltests=TESTS
        acquire='checkNotNull(activity.captureFixtureTeardownTarget(s)).let { target -> { target.disconnect { } } }' if args.source_mode=='current' else '{ activity.disconnect() }'
        alltests=alltests.replace('@TARGET_ACQUIRE@',acquire)
        if args.source_mode=='parent':
            # Keep exact same desired first method, compiling only the real old mutable disconnect path.
            start=alltests.index('    @Test fun teardown332OriginalOwnerSurvivesActivityReplacement')
            end=alltests.index('\n    @Test fun teardown332EmptyTrigger',start)
            prefix=alltests[:alltests.index('    private fun reconcile332')]
            alltests=prefix+alltests[start:end]
        else:
            alltests=alltests.replace('@HELPER_TESTS@',HELPER_TESTS)
            phase=instrumentation[instrumentation.index('internal class FixtureSetupPhase('):instrumentation.index('/** Explicit bounded')]
            methods={name:block(instrumentation,pattern) for name,pattern in [
                ('CAPTURE_METHOD',r'^    private fun captureFixtureTeardown\('),
                ('DESTROY_METHOD',r'^    override fun callActivityOnDestroy\('),
                ('FINISH_METHOD',r'^    private fun finishFixtureTeardown\(')]}
            # Exact existing producer finally, including independent baseline/audio/report/exit legs.
            closing_at=instrumentation.index('        finally {\n            fixtureSetupPhase?.let')
            actual_finally=block(instrumentation[closing_at:],r'^        finally')
            actual_finally=actual_finally[actual_finally.index('{'):]
            generated_driver=DRIVER_SCAFFOLD.replace('@PHASE@',phase).replace('@ACTUAL_FINALLY@',actual_finally)
            for name,body in methods.items():generated_driver=generated_driver.replace('@'+name+'@',body)
            (output/'TeardownDriver.kt').write_text(generated_driver)
        original_test=tests/'kotlin/com/code2hack/eyeprojector/directrg/RetainedReceiverSessionTest.kt';fixture=original_test.read_text();closing=fixture.rfind('}')
        generated_test=output/'RetainedReceiverSessionTest.kt';generated_test.write_text(fixture[:closing]+alltests+fixture[closing:])
        generated_runner=output/'Teardown332ChecksMain.kt';generated_runner.write_text(RUNNER)
        json_double=output/'JSONObject.kt';json_double.write_text(JSON_DOUBLE);android_double=output/'Activity.kt';android_double.write_text(ANDROID_DOUBLE)
        names=['ProjectionSession','ReceiverSessionOwner','WindowPresentationObservation','ReceiverActivationStore','PairedSessionClient','RetainedVideoOutput','SurfaceDecoder','SessionAttachment','StatePublisher','VideoReadiness','VideoClock','HeldTouchTransport','OrderedControlDispatcher','LatestHeadPointer','CompletedReceiverInput','SessionPresentation','HeadPointerModel','HeadOrientation','HeldPointerDispatcher','PadGestureRecognizer']
        sources=[glass/(name+'.kt') for name in names]
        sources += [protocol/name for name in ['SessionControl.kt','SessionState.kt','ActivationControl.kt','ReceiverUsability.kt','Wire.kt','PhoneInput.kt','Touch.kt','LayoutScale.kt']]
        sources += sorted((tests/'kotlin/android').rglob('*.kt'))+sorted((tests/'activation/doubles').glob('*.kt'))
        sources += [generated_activity,generated_test,generated_runner,json_double,android_double]
        if args.source_mode=='current':sources += [helper,androidtest/'NativeInputObservationRunState.kt',androidtest/'ReceiverStartupDiagnostics.kt',output/'TeardownDriver.kt']
        snapshots=[]
        for number,path in enumerate(sources):
            dest=output/'source'/str(number)/path.name;dest.parent.mkdir(parents=True);dest.write_bytes(path.read_bytes());snapshots.append(dest)
            result['inputs'].append({'path':str(path),'snapshot':str(dest),'sha256':digest(path)})
        for number,path in enumerate([Path(__file__),activity,driver,original_test]+([args.parent_activity] if args.parent_activity else [])):
            dest=output/'control-inputs'/str(number)/path.name;dest.parent.mkdir(parents=True);dest.write_bytes(path.read_bytes());result['inputs'].append({'path':str(path.resolve()),'snapshot':str(dest),'sha256':digest(path)})
        coordinates=[('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21'),('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'),('org.jetbrains.kotlin','kotlin-script-runtime','2.2.21'),('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),('org.jetbrains','annotations','13.0'),('junit','junit','4.13.2'),('org.hamcrest','hamcrest-core','1.3')]
        jars=[];cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
        for group,name,version in coordinates:
            matches=list((cache/group/name/version).glob('*/'+name+'-'+version+'.jar'))
            if len(matches)!=1:raise ValueError('Missing/ambiguous cached dependency '+name+'; no download')
            jars.append(matches[0])
        result['dependencies']=[{'path':str(p),'sha256':digest(p)} for p in jars];java=str(Path(os.environ.get('JAVA_HOME','/usr/lib/jvm/java-17-openjdk-amd64'))/'bin/java');cp=os.pathsep.join(map(str,jars));jar=output/'fixture-teardown-checks.jar'
        compile_command=[java,'-Xmx768m','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(jar)]+list(map(str,snapshots))
        run_command=[java,'-Xmx256m','-cp',str(jar)+os.pathsep+cp,'com.code2hack.eyeprojector.directrg.Teardown332ChecksMainKt']+selected
        result['commands']={'compile':compile_command,'run':run_command};result['selectedMethods']=selected
        result['processes']['compile']=owned(compile_command,output/'compile.log',85,deadline)
        if result['processes']['compile']['exit']==0:result['processes']['run']=owned(run_command,output/'run.log',25,deadline)
        log=(output/'run.log').read_text() if (output/'run.log').exists() else ''
        result['cases']=[{'method':m[0],'runCount':int(m[1]),'failureCount':int(m[2]),'elapsedMs':int(m[3])} for m in re.findall(r'^CASE (\S+) run=(\d+) failed=(\d+) elapsedMs=(\d+)$',log,re.M)]
        result['ownedThreadClosure']=re.findall(r'^OWNED_THREAD_CLOSURE live=(.*)$',log,re.M)
        run=result['processes'].get('run',{})
        if args.source_mode=='parent':result['expectationMet']=result['processes']['compile']['exit']==0 and run.get('exit')==1 and not run.get('timedOut') and len(result['cases'])==1 and result['cases'][0]['failureCount']==1 and 'ORIGINAL_OWNER_RECEIVES_SCOPED_TEARDOWN' in log and result['ownedThreadClosure']==['[]']
        else:result['expectationMet']=result['processes']['compile']['exit']==0 and run.get('exit')==0 and not run.get('timedOut') and len(result['cases'])==len(selected) and all(c['failureCount']==0 and c['runCount']==1 for c in result['cases']) and result['ownedThreadClosure']==['[]']
        result['expectedOutcome']='Actual old mutable-disconnect semanticRED' if args.source_mode=='parent' else 'Actual immutabletarget/test-finalization GREEN'
        result['limitations']=['Extracted methods run in explicit field/Android scaffolds, not complete Activity or Android Looper','Actual Owner/client/store execute with repository transport/codec/GL doubles; no physical or H/backend release claim','Observed process identities are sampled points, not continuous absence']
    except Exception as error:
        result['error']={'type':type(error).__name__,'message':str(error)};result['expectationMet']=False
    finally:
        (output/'result.json').write_text(json.dumps(result,indent=2)+'\n')
        for name in ['compile.log','run.log']:
            if (output/name).exists():print((output/name).read_text(),end='')
        print(json.dumps({'result':str(output/'result.json'),'expectationMet':result.get('expectationMet',False)}))
    return 0 if result.get('expectationMet') else 1


# Actual helper assertions are intentionally below runner infrastructure for readable source binding.
HELPER_TESTS = r'''
    @Test fun teardown332ActualFixtureFinalizationAndLifecycle() {
        Fixture(withFeature=true).use { f ->
            val activity=activity332(f,trigger="");val s=start332(f);val driver=TeardownDriver();val output=report332()
            driver.capture(activity,first332(s),android.os.SystemClock.elapsedRealtime()+1200)
            driver.statusSource={status332(s)};activity.finishAction={driver.callActivityOnDestroy(activity)}
            val acknowledger=Thread({
                val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1)
                while("H2" !in f.feature!!.state.pendingCancel && System.nanoTime()<until)Thread.yield()
                f.feature!!.accept(ActivationReply(cancelAcknowledged="H2"));reconcile332(f)
            },"teardown-ack-worker")
            android.app.BoundaryTrace.steps.clear();acknowledger.start()
            driver.closeUsingActualFinally(activity,output);acknowledger.join(2000);assertFalse(acknowledger.isAlive)
            val d=detail332(output)
            assertTrue(output.bool("passed"));assertEquals(1,activity.finishCount);assertEquals(1,activity.destroyCount)
            assertEquals(1L,d.long("completionCallbackCount"));assertEquals(1L,d.long("destroyReturnCount"))
            assertTrue(d.bool("targetReferenceReleased") && d.bool("activityReferenceReleased") && driver.released())
            assertEquals("UNKNOWN",d.get("productRelease"));assertEquals("UNOBSERVED",d.get("callbackQuiescence"))
            assertEquals(true,d.get("statusMatchesOriginalSession"))
            val steps=android.app.BoundaryTrace.steps.toList()
            assertEquals(listOf("window-recorder-finish","activity-finish","audio-focus","volume-restore","report-write","instrumentation-finish"),steps)
            assertFalse(values332(d).contains("H2"));assertFalse(values332(d).contains("current-scope"))
        }
    }
    @Test fun teardown332MissingTargetKeepsIndependentFinish() {
        val activity=ReceiverActivity();val driver=TeardownDriver();val output=report332();val s=System.nanoTime()
        driver.capture(activity,first332(s),android.os.SystemClock.elapsedRealtime()+1000)
        activity.finishAction={driver.callActivityOnDestroy(activity)};driver.statusSource={status332(s)}
        driver.closeUsingActualFinally(activity,output)
        val d=detail332(output);assertEquals(1,activity.finishCount);assertTrue(output.bool("passed"))
        assertFalse(d.bool("originalTargetCaptured"));assertFalse(d.bool("waitCalled"))
        assertSame(org.json.JSONObject.NULL,d.get("statusMatchesOriginalSession"))
        assertEquals("UNKNOWN_NO_ORIGINAL_TARGET",d.obj("outcomes").get("disconnectRequest"))
        assertTrue(driver.released())
    }
    @Test fun teardown332ExpiredCeilingNeverResetsOrWaits() {
        Fixture(withFeature=true).use { f ->
            val activity=activity332(f);val s=start332(f);val driver=TeardownDriver();val output=report332();val ceiling=expired332()
            driver.capture(activity,first332(s),ceiling);f.owner.close()
            activity.finishAction={driver.callActivityOnDestroy(activity)};driver.statusSource={status332(s)}
            driver.finish(activity,output);val d=detail332(output)
            assertEquals(ceiling,d.long("originalObservationDeadlineElapsedMs"));assertEquals(0L,d.long("remainingOriginalCeilingMs"))
            assertFalse(d.bool("waitCalled"));assertEquals(1L,d.long("completionCallbackCount"))
            assertEquals(1L,d.long("destroyReturnCount"));assertEquals(false,d.obj("before").get("originalActivationCancellationRecorded"))
            assertEquals("UNKNOWN",d.get("originalHSettlement"))
        }
    }
    @Test fun teardown332PendingCompletionUsesOnlyRemainingCeiling() {
        Fixture(withFeature=true).use { f ->
            val activity=activity332(f,trigger="");val s=start332(f);val driver=TeardownDriver();val output=report332();val ceiling=android.os.SystemClock.elapsedRealtime()+35
            driver.capture(activity,first332(s),ceiling);activity.finishAction={driver.callActivityOnDestroy(activity)};driver.statusSource={status332(s)}
            val start=System.nanoTime();driver.finish(activity,output);val elapsed=(System.nanoTime()-start)/1_000_000
            val d=detail332(output);assertTrue(elapsed<500)
            assertEquals(ceiling,d.long("originalObservationDeadlineElapsedMs"))
            assertTrue(d.long("remainingOriginalCeilingMs")<=35);assertTrue(d.bool("waitCalled"));assertEquals(false,d.get("waitReturned"))
            assertEquals(0L,d.long("completionCallbackCount"));assertEquals(1L,d.long("destroyReturnCount"))
            // An acknowledgment delivered after recorder release must not revise the published facts.
            f.feature!!.accept(ActivationReply(cancelAcknowledged="H2"));reconcile332(f)
            assertEquals(0L,d.long("completionCallbackCount"));assertTrue(d.bool("targetReferenceReleased"))
        }
    }
    @Test fun teardown332MainBeforeAndAfterSideEffectFailures() {
        for(after in listOf(false,true))Fixture(withFeature=true).use { f ->
            val activity=activity332(f,trigger="");val s=start332(f);val driver=TeardownDriver();val output=report332()
            driver.capture(activity,first332(s),expired332());activity.finishAction={driver.callActivityOnDestroy(activity)};driver.statusSource={status332(s)}
            if(after)driver.afterFailure=AssertionError("dispatch after effect") else driver.beforeFailure=AssertionError("dispatch before effect")
            driver.closeUsingActualFinally(activity,output);val d=detail332(output)
            assertTrue(output.bool("passed"));assertTrue(driver.released());assertTrue(d.bool("targetReferenceReleased"))
            assertEquals(if(after)1 else 0,activity.finishCount)
            if(after)assertNotSame(org.json.JSONObject.NULL,d.get("finishReturnedMonoNs")) else assertSame(org.json.JSONObject.NULL,d.get("finishReturnedMonoNs"))
            assertEquals(if(after)true else false,"H2" in f.feature!!.state.canceled)
            assertTrue(d.obj("outcomes").get("disconnectRequest").toString().startsWith("FAILED:"))
            assertTrue(android.app.BoundaryTrace.steps.contains("report-write"))
            driver.beforeFailure=null;driver.afterFailure=null
        }
    }
    @Test fun teardown332DestroyThrowPreservesPrimaryAndOtherLegs() {
        Fixture(withFeature=true).use { f ->
            val activity=activity332(f);val s=start332(f);val driver=TeardownDriver();val output=report332();val product=AssertionError("destroy primary")
            driver.capture(activity,first332(s),expired332());f.owner.close();driver.statusSource={status332(s)}
            activity.destroyFailure=product;activity.finishAction={driver.callActivityOnDestroy(activity)}
            val primary=IllegalStateException("measurement primary");var caught:Throwable?=null
            android.app.BoundaryTrace.steps.clear()
            try { driver.closeUsingActualFinally(activity,output,primary) } catch(error:Throwable){caught=error}
            assertSame(primary,caught);assertEquals(1,activity.destroyCount)
            val d=detail332(output);assertEquals(1L,d.long("destroyEntryCount"));assertEquals(1L,d.long("destroyFailureCount"));assertEquals(0L,d.long("destroyReturnCount"))
            assertSame(org.json.JSONObject.NULL,d.get("destroyReturnedMonoNs"));assertTrue(d.get("finishFailureClass").toString().contains("AssertionError"))
            assertEquals(listOf("window-recorder-finish","activity-finish","audio-focus","volume-restore","report-write","instrumentation-finish"),android.app.BoundaryTrace.steps.toList())
        }
    }
    @Test fun teardown332StatusSuccessorMissingAndOldTerminalTypedSeparately() {
        for(kind in listOf("successor","missing","stale-active","future","historical","old-terminal"))Fixture(withFeature=true).use { f ->
            val activity=activity332(f);val s=start332(f);val driver=TeardownDriver();val output=report332()
            driver.capture(activity,first332(s),expired332());f.owner.close();activity.finishAction={driver.callActivityOnDestroy(activity)}
            driver.statusSource={ when(kind) {
                "successor" -> status332(s+1)
                "missing" -> org.json.JSONObject().put("state","STOPPED")
                "future" -> status332(System.nanoTime()+1_000_000_000L)
                "historical" -> status332(-1)
                "stale-active" -> status332(1,"STREAMING",0)
                else -> status332(s,"STOPPED",0)
            } }
            driver.finish(activity,output);val d=detail332(output)
            when(kind){
                "successor" -> {assertTrue(d.bool("statusValidatedAtObservation"));assertEquals(false,d.get("statusMatchesOriginalSession"))}
                "old-terminal" -> {assertTrue(d.bool("statusValidatedAtObservation"));assertEquals(true,d.get("statusMatchesOriginalSession"));assertTrue(d.get("statusScope").toString().contains("does not prove a new"))}
                else -> {assertFalse(d.bool("statusValidatedAtObservation"));assertSame(org.json.JSONObject.NULL,d.get("statusMatchesOriginalSession"))}
            }
            assertEquals("UNKNOWN",d.get("productRelease"))
        }
    }
    @Test fun teardown332StatusAndExportFailureRemainDiagnostic() {
        for(fault in listOf("status","owner-json","attach-json"))Fixture(withFeature=true).use { f ->
            org.json.JsonFault.reset();val activity=activity332(f);val s=start332(f);val driver=TeardownDriver();val output=report332()
            driver.capture(activity,first332(s),expired332());f.owner.close();activity.finishAction={driver.callActivityOnDestroy(activity)}
            driver.statusSource={if(fault=="status")throw AssertionError("status read failure") else status332(s)}
            if(fault=="owner-json")org.json.JsonFault.onceAtKey="ownerState"
            if(fault=="attach-json")org.json.JsonFault.onceAtKey="fixtureTeardown"
            val primary=IllegalStateException("measurement primary");var caught:Throwable?=null
            try{driver.closeUsingActualFinally(activity,output,primary)}catch(error:Throwable){caught=error}
            assertSame(primary,caught);assertEquals(1,activity.finishCount);assertTrue(driver.released());assertTrue(output.bool("passed"))
            if(fault!="attach-json"){
                val d=detail332(output);assertTrue(d.bool("targetReferenceReleased"));assertTrue(d.bool("activityReferenceReleased"))
                if(fault=="status")assertFalse(d.bool("statusValidatedAtObservation"))
                if(fault=="owner-json")assertTrue(d.obj("outcomes").get("ownerObservationExport").toString().startsWith("FAILED:"))
            }else assertFalse(output.has("fixtureTeardown"))
            org.json.JsonFault.reset()
        }
    }
    @Test fun teardown332QueuedCaptureCannotRepublishAfterRelease() {
        Fixture(withFeature=true).use { f ->
            val activity=activity332(f);val s=start332(f);val instrumentation=android.app.Instrumentation();instrumentation.queueAtCall=1
            val recorder=FixtureTeardownRecorder.capture(instrumentation,activity,first332(s),expired332())
            recorder.release();instrumentation.queueAtCall=null;instrumentation.drainQueued()
            val output=report332();recorder.complete(instrumentation,output,{status332(s)},0L)
            val d=detail332(output);assertFalse(d.bool("originalTargetCaptured"));assertTrue(d.bool("targetReferenceReleased"));assertTrue(d.bool("activityReferenceReleased"))
            assertTrue(f.feature!!.state.canceled.isEmpty())
        }
    }
    @Test fun teardown332QueuedRequestCannotCancelAfterRelease() {
        Fixture(withFeature=true).use { f ->
            val activity=activity332(f);val s=start332(f);val instrumentation=android.app.Instrumentation()
            val recorder=FixtureTeardownRecorder.capture(instrumentation,activity,first332(s),expired332())
            instrumentation.queueAtCall=instrumentation.mainCalls+2
            recorder.requestDisconnect(instrumentation);recorder.release();instrumentation.queueAtCall=null;instrumentation.drainQueued()
            assertTrue("Late queued request must not start active teardown after release",f.feature!!.state.canceled.isEmpty())
            val output=report332();recorder.complete(instrumentation,output,{status332(s)},0L);assertTrue(detail332(output).bool("targetReferenceReleased"))
        }
    }
    @Test fun teardown332OrdinaryOperationDoesNotInstallRecorder() {
        Fixture(withFeature=true).use { f ->
            val activity=activity332(f);val driver=TeardownDriver();val output=report332()
            driver.capture(activity,first332(start332(f)),android.os.SystemClock.elapsedRealtime()+1000,"stream")
            assertTrue(driver.released());driver.closeUsingActualFinally(activity,output)
            assertEquals(1,activity.finishCount);assertFalse(output.has("fixtureTeardown"));assertTrue(f.feature!!.state.canceled.isEmpty())
        }
    }
    @Test fun teardown332MissingActivityNeverClaimsFinishReturn() {
        val driver=TeardownDriver();val output=report332();val s=System.nanoTime()
        driver.capture(null,first332(s),expired332());driver.statusSource={status332(s)}
        driver.closeUsingActualFinally(null,output)
        val d=detail332(output);assertFalse(d.bool("ownedActivityCaptured"));assertFalse(d.bool("originalTargetCaptured"))
        assertSame(org.json.JSONObject.NULL,d.get("finishRequestedMonoNs"));assertSame(org.json.JSONObject.NULL,d.get("finishReturnedMonoNs"))
        assertTrue(driver.released());assertTrue(output.bool("passed"))
    }

'''

if __name__=='__main__':raise SystemExit(main())
