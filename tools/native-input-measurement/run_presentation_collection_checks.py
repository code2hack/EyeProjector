#!/usr/bin/env python3
"""Bounded cached checks of the actual test-only successful-window recorder; no Android/SDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time


DOUBLES = {
    'Instrumentation.kt': r'''package android.app
import java.util.concurrent.atomic.AtomicReference
open class Instrumentation {
    var beforeFailure: Throwable? = null
    var afterFailure: Throwable? = null
    var completed = 0
    var joined = 0
    fun runOnMainSync(target: () -> Unit) {
        beforeFailure?.let { throw it }
        val escaped = AtomicReference<Throwable?>()
        val worker = Thread({ target(); completed++ }, "collector-modeled-main")
        worker.isDaemon = true
        worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> escaped.set(error) }
        worker.start()
        worker.join(1000)
        if (worker.isAlive) { worker.interrupt(); worker.join(1000) }
        check(!worker.isAlive) { "HOST_WORKER_NOT_JOINED" }
        joined++
        check(escaped.get() == null) { "MAIN_CALLBACK_FAILURE_ESCAPED" }
        afterFailure?.let { throw it }
    }
}
''',
    'SystemClock.kt': r'''package android.os
import com.code2hack.eyeprojector.directrg.CollectionClock
object SystemClock {
    fun elapsedRealtime() = CollectionClock.elapsedMs
    fun uptimeMillis() = CollectionClock.uptimeMs
}
''',
    'JSONObject.kt': r'''package org.json
object JsonFault {
    var onceAtKey: String? = null
    fun reset() { onceAtKey = null }
    fun check(key: String) {
        if (key == onceAtKey) { onceAtKey = null; throw AssertionError("modeled JSON export failure") }
    }
}
class JSONObject {
    companion object { val NULL = Any() }
    val fields = linkedMapOf<String, Any?>()
    constructor()
    constructor(values: Map<*, *>) { values.forEach { (key, value) -> fields[key.toString()] = value ?: NULL } }
    fun put(key: String, value: Any?): JSONObject { JsonFault.check(key); fields[key] = value ?: NULL; return this }
    fun has(key: String) = fields.containsKey(key)
    fun get(key: String): Any = fields[key] ?: error("Missing " + key)
    fun opt(key: String): Any? = fields[key]
    fun optString(key: String): String = fields[key]?.takeUnless { it === NULL }?.toString() ?: ""
    fun obj(key: String) = get(key) as JSONObject
    fun array(key: String) = get(key) as JSONArray
    fun long(key: String) = (get(key) as Number).toLong()
    fun bool(key: String) = get(key) as Boolean
}
class JSONArray {
    val values = mutableListOf<Any?>()
    fun put(value: Any?): JSONArray { values.add(value); return this }
    fun obj(index: Int) = values[index] as JSONObject
}
''',
}

FIXTURE = r'''package com.code2hack.eyeprojector.directrg

import android.app.Instrumentation
import org.json.JSONArray
import org.json.JSONObject
import org.json.JsonFault

/** Distinct virtual device clock origins. The collector's actual source sees this nanoTime provider. */
internal object CollectionClock {
    const val BASE = 7_000_000_000_000L
    var monoNs = BASE
    var elapsedMs = 900_000L
    var uptimeMs = 120_000L
    fun reset() { monoNs = BASE; elapsedMs = 900_000; uptimeMs = 120_000 }
    fun advance(ms: Long) { monoNs += ms * 1_000_000; elapsedMs += ms; uptimeMs += ms }
}
internal object System { fun nanoTime() = CollectionClock.monoNs++ }
internal class ReceiverSessionOwner {
    companion object { @Volatile var windowPresentationObserver: WindowPresentationObserver? = null }
}

private fun currentStatus(): JSONObject = JSONObject().put("state", "STREAMING")
    .put("startedMonoNs", CollectionClock.BASE).put("elapsedMs", CollectionClock.elapsedMs - 900_000)
    .put("packets", 4L).put("changingPackets", 2L).put("rendered", 3L).put("touchesSent", 0L)
    .put("presentationActive", true).put("width", 480).put("height", 640)
    .put("driverStatusRead", JSONObject().put("readNowMonoNs", System.nanoTime()))
private fun snapshot(mode: String = "PROJECTED") = OwnerWindowSnapshot(
    readStartMonoNs = CollectionClock.BASE + 100, readEndMonoNs = CollectionClock.BASE + 200,
    windowGenerationBefore = 3, windowGenerationAfter = 3, currentSessionStartedMonoNs = CollectionClock.BASE,
    currentSessionMatchesOriginal = true, outputMatchesOriginal = true, windowMatchesCallback = true,
    closed = false, mediaState = "STREAMING", displayedGeneration = 3, currentDisplayed = true,
    uiUsable = true, localPanel = mode == "LOCAL_PANEL", inputRevision = 31,
    actualReportedMode = mode, actualReportRevision = 44, reportMatchesOriginalOwner = true,
    reportMatchesCurrentDemand = true, actualUsabilityMode = mode, actualUsabilityRevision = 45,
    actualUsabilityInputRevision = 31, usabilityMatchesOriginalOwner = true,
    sessionGeometryWidth = 480, sessionGeometryHeight = 640, metadataReferencesStable = true)
private fun receipt(start: Long? = CollectionClock.BASE, generation: Long = 3, mode: String = "PROJECTED") =
    SuccessfulWindowReceipt(start, generation, CollectionClock.BASE + 50, snapshot(mode), false, true,
        snapshot(mode), CollectionClock.BASE + 60)
private fun report() = JSONObject().put("passed", true)
private fun finish(recorder: WindowPresentationRecorder, instrumentation: Instrumentation,
                   output: JSONObject = report(), status: () -> JSONObject = ::currentStatus): JSONObject {
    recorder.finish(instrumentation, output, status, CollectionClock.BASE)
    check(output.bool("passed")) { "DIAGNOSTIC_CHANGED_MEASUREMENT_VERDICT" }
    return output.obj("windowPresentationObservation")
}
private fun outcome(detail: JSONObject, key: String) = detail.obj("outcomes").get(key).toString()

fun main() {
    var cases = 0
    fun case(name: String, block: () -> Unit) {
        CollectionClock.reset(); JsonFault.reset(); ReceiverSessionOwner.windowPresentationObserver = null
        block(); cases++; println("PASS actual collector " + name)
    }
    case("all immutable receipt fields and diagnostic aliases survive without mode filtering") {
        val instrumentation = Instrumentation()
        val recorder = WindowPresentationRecorder("run-real", CollectionClock.BASE)
        recorder.install(instrumentation)
        val projected = receipt()
        recorder.presented(projected)
        recorder.presented(receipt(generation = 4, mode = "LOCAL_PANEL"))
        recorder.presented(receipt(start = CollectionClock.BASE + 99, mode = "NONE").copy(
            before = snapshot("NONE").copy(currentSessionMatchesOriginal = false, outputMatchesOriginal = false,
                windowMatchesCallback = false, metadataReferencesStable = false)))
        recorder.presented(receipt(start = null).copy(before = null, after = null, callbackCompleted = false))
        val detail = finish(recorder, instrumentation)
        val rows = detail.array("receipts")
        check(rows.values.size == 4 && detail.long("rowsRecorded") == 4L && detail.long("rowsExported") == 4L)
        check(rows.obj(0).get("runId") == "run-real" && rows.obj(0).long("sequence") == 1L)
        check(rows.obj(0).long("originalSessionStartedMonoNs") == projected.originalSessionStartedMonoNs)
        check(rows.obj(0).long("callbackMonoNs") == projected.callbackMonoNs)
        check(rows.obj(0).long("callbackReadEndMonoNs") == projected.callbackReadEndMonoNs)
        check(rows.obj(0).get("sessionAlias") == rows.obj(1).get("sessionAlias"))
        check(rows.obj(0).get("windowAlias") != rows.obj(1).get("windowAlias"))
        check(rows.obj(2).get("sessionAlias") != rows.obj(1).get("sessionAlias"))
        check(rows.obj(1).obj("after").get("actualReportedMode") == "LOCAL_PANEL")
        check(rows.obj(2).obj("before").get("actualReportedMode") == "NONE")
        check(!rows.obj(2).obj("before").bool("metadataReferencesStable"))
        check(rows.obj(3).get("originalSessionStartedMonoNs") === JSONObject.NULL)
        check(rows.obj(3).get("sessionAlias") === JSONObject.NULL && rows.obj(3).get("before") === JSONObject.NULL)
        check(!rows.obj(3).bool("callbackCompleted"))
        check(rows.obj(0).obj("after").fields.keys.size == 26)
        check(detail.get("cachedFrameCause") == "UNKNOWN" && detail.get("frameIdentity") == "UNKNOWN")
        check(detail.get("opticalPresentation") == "UNKNOWN" && detail.get("nativeHidInput") == "UNKNOWN")
        check(detail.bool("exactObserverDetached") && detail.bool("observerReleased") && detail.bool("captureDisabled"))
        check(instrumentation.joined == 2 && instrumentation.completed == 2)
    }
    case("measurement and real tap dispatch markers retain distinct clock domains") {
        val recorder = WindowPresentationRecorder("markers", CollectionClock.BASE)
        val instrumentation = Instrumentation(); recorder.install(instrumentation)
        recorder.measurementStarted(899_950)
        CollectionClock.advance(200); recorder.tapDispatchStarting(120_200)
        CollectionClock.advance(40); recorder.tapDispatchReturned(120_200)
        val markers = finish(recorder, instrumentation).array("markers")
        check(markers.values.size == 3)
        check(markers.obj(0).long("measurementStartElapsedMs") == 899_950L)
        check(markers.obj(0).long("observedElapsedMs") == 900_000L && markers.obj(0).long("observedUptimeMs") == 120_000L)
        check(markers.obj(1).long("eventDownUptimeMs") == 120_200L && markers.obj(2).long("eventDownUptimeMs") == 120_200L)
        check(markers.obj(2).long("readStartMonoNs") > markers.obj(1).long("readEndMonoNs"))
        check(markers.obj(2).long("observedElapsedMs") == 900_240L)
    }
    case("occupied observer remains installed and absence does not fail measurement") {
        val other = WindowPresentationObserver { error("foreign sink must not be invoked") }
        ReceiverSessionOwner.windowPresentationObserver = other
        val instrumentation = Instrumentation(); val recorder = WindowPresentationRecorder("occupied", CollectionClock.BASE)
        recorder.install(instrumentation)
        val detail = finish(recorder, instrumentation)
        check(ReceiverSessionOwner.windowPresentationObserver === other)
        check(!detail.bool("observerInstalled") && !detail.bool("exactObserverDetached") && detail.bool("observerReleased"))
        check(outcome(detail, "observerInstallAvailability") == "OCCUPIED" && detail.array("receipts").values.isEmpty())
    }
    case("replacement observer is not detached and disabled old observer ignores delayed deliveries") {
        val instrumentation = Instrumentation(); val recorder = WindowPresentationRecorder("replace", CollectionClock.BASE)
        recorder.install(instrumentation); recorder.presented(receipt())
        val other = WindowPresentationObserver { }
        ReceiverSessionOwner.windowPresentationObserver = other
        val first = finish(recorder, instrumentation)
        recorder.presented(receipt()); recorder.tapDispatchReturned(120_000)
        val second = finish(recorder, instrumentation)
        check(ReceiverSessionOwner.windowPresentationObserver === other)
        check(!first.bool("exactObserverDetached") && first.bool("observerReleased"))
        check(second.long("rowsRecorded") == 1L && second.long("rowsExported") == 0L)
        check(second.array("markers").values.isEmpty())
    }
    case("main dispatch failure before callback still disables and exports all other legs") {
        val instrumentation = Instrumentation(); val recorder = WindowPresentationRecorder("detach-fail", CollectionClock.BASE)
        recorder.install(instrumentation); recorder.presented(receipt())
        instrumentation.beforeFailure = AssertionError("main dispatch unavailable")
        val detail = finish(recorder, instrumentation)
        check(ReceiverSessionOwner.windowPresentationObserver === recorder && detail.bool("captureDisabled"))
        check(outcome(detail, "observerDetach") == "FAILED:AssertionError")
        check(outcome(detail, "receiptDrain") == "SUCCESS" && outcome(detail, "finalStatusRead") == "SUCCESS")
        check(detail.long("rowsExported") == 1L && !detail.bool("observerReleased"))
        recorder.presented(receipt()); instrumentation.beforeFailure = null
        check(finish(recorder, instrumentation).long("rowsRecorded") == 1L)
    }
    case("post-completion dispatch failure retains actual detach effect without replacing primary") {
        val instrumentation = Instrumentation(); val recorder = WindowPresentationRecorder("after-fail", CollectionClock.BASE)
        recorder.install(instrumentation); instrumentation.afterFailure = AssertionError("dispatch after effect")
        val original = IllegalStateException("original measurement error")
        val output = report(); var caught: Throwable? = null
        try { try { throw original } finally { recorder.finish(instrumentation, output, ::currentStatus, CollectionClock.BASE) } }
        catch (error: Throwable) { caught = error }
        val detail = output.obj("windowPresentationObservation")
        check(caught === original && output.bool("passed"))
        check(detail.bool("exactObserverDetached") && detail.bool("observerReleased"))
        check(outcome(detail, "observerDetach") == "FAILED:AssertionError")
        check(instrumentation.joined == 2 && instrumentation.completed == 2)
    }
    case("receipt JSON failure does not skip markers status or terminal metadata") {
        val instrumentation = Instrumentation(); val recorder = WindowPresentationRecorder("json-fail", CollectionClock.BASE)
        recorder.install(instrumentation); recorder.presented(receipt()); recorder.measurementStarted(900_000)
        JsonFault.onceAtKey = "before"
        val detail = finish(recorder, instrumentation)
        check(outcome(detail, "receiptDrain") == "FAILED:AssertionError" && outcome(detail, "markerDrain") == "SUCCESS")
        check(outcome(detail, "finalStatusRead") == "SUCCESS" && detail.bool("finalStatusValidated"))
        check(detail.long("rowsRecorded") == 1L && detail.long("rowsExported") == 0L)
        check(detail.array("markers").values.size == 1 && detail.array("failures").values.size == 1)
    }
    case("blocking status result is revalidated by actual freshness predicate at publication") {
        val instrumentation = Instrumentation(); val recorder = WindowPresentationRecorder("stale", CollectionClock.BASE)
        recorder.install(instrumentation)
        val detail = finish(recorder, instrumentation, status = {
            val value = currentStatus(); CollectionClock.advance(3500); value
        })
        check(!detail.bool("finalStatusValidated") && detail.get("finalStatus") === JSONObject.NULL)
        check(detail.get("finalStatusValidationRejection") == "STALE_ACTIVE_SESSION_STATUS")
        check(detail.long("finalStatusReadEndMonoNs") > detail.long("finalStatusReadStartMonoNs"))
    }
    case("fresh final status is recognized against the existing first measurement session") {
        val instrumentation = Instrumentation(); val recorder = WindowPresentationRecorder("same-session", CollectionClock.BASE)
        recorder.install(instrumentation)
        val output = report().put("first", JSONObject().put("startedMonoNs", CollectionClock.BASE))
        val detail = finish(recorder, instrumentation, output)
        check(detail.bool("finalStatusValidated") && detail.bool("finalStatusMatchesMeasurementSession"))
        check(detail.long("expectedSessionStartedMonoNs") == CollectionClock.BASE)
        check(detail.get("finalStatusValidationScope").toString().contains("not later report-file publication"))
    }
    case("fresh successor S2 remains exported but cannot be recognized as measurement S1") {
        val instrumentation = Instrumentation(); val recorder = WindowPresentationRecorder("successor-session", CollectionClock.BASE)
        recorder.install(instrumentation)
        val output = report().put("first", JSONObject().put("startedMonoNs", CollectionClock.BASE))
        CollectionClock.advance(200)
        val successor = CollectionClock.BASE + 100_000_000L
        val detail = finish(recorder, instrumentation, output, status = {
            currentStatus().put("startedMonoNs", successor).put("elapsedMs", 100L)
        })
        check(detail.bool("finalStatusValidated") && !detail.bool("finalStatusMatchesMeasurementSession"))
        check(detail.long("expectedSessionStartedMonoNs") == CollectionClock.BASE)
        check(detail.obj("finalStatus").long("startedMonoNs") == successor)
    }
    case("missing first or actual session values remain null and never match each other") {
        for (missingFirst in listOf(true, false)) for (missingActual in listOf(true, false)) {
            val instrumentation = Instrumentation(); val recorder = WindowPresentationRecorder("missing-session", CollectionClock.BASE)
            recorder.install(instrumentation)
            val output = report()
            if (!missingFirst) output.put("first", JSONObject()) // First status exists but its S is absent.
            val detail = finish(recorder, instrumentation, output, status = {
                currentStatus().also { if (missingActual) it.fields.remove("startedMonoNs") }
            })
            check(detail.get("expectedSessionStartedMonoNs") === JSONObject.NULL)
            check(detail.get("finalStatusMatchesMeasurementSession") === JSONObject.NULL)
            check(detail.bool("finalStatusValidated") == !missingActual)
        }
        val instrumentation = Instrumentation(); val recorder = WindowPresentationRecorder("missing-actual", CollectionClock.BASE)
        recorder.install(instrumentation)
        val output = report().put("first", JSONObject().put("startedMonoNs", CollectionClock.BASE))
        val detail = finish(recorder, instrumentation, output, status = {
            currentStatus().also { it.fields.remove("startedMonoNs") }
        })
        check(detail.long("expectedSessionStartedMonoNs") == CollectionClock.BASE)
        check(!detail.bool("finalStatusValidated") && detail.get("finalStatusMatchesMeasurementSession") === JSONObject.NULL)
    }
    case("status and final report export failures remain diagnostic only") {
        val instrumentation = Instrumentation(); val recorder = WindowPresentationRecorder("status-fail", CollectionClock.BASE)
        recorder.install(instrumentation)
        val detail = finish(recorder, instrumentation, status = { throw AssertionError("status unavailable") })
        check(outcome(detail, "finalStatusRead") == "FAILED:AssertionError")
        check(!detail.bool("finalStatusValidated") && detail.get("finalStatusValidationRejection") == "NO_VALIDATED_STATUS")
        val another = WindowPresentationRecorder("attach-fail", CollectionClock.BASE); another.install(instrumentation)
        val output = report(); JsonFault.onceAtKey = "windowPresentationObservation"
        another.finish(instrumentation, output, ::currentStatus, CollectionClock.BASE)
        check(output.bool("passed") && !output.has("windowPresentationObservation"))
    }
    check(ReceiverSessionOwner.windowPresentationObserver == null)
    println("PASS actual recorder cases=" + cases + "; cached host source with JSON/Android/main-boundary doubles only; no Looper/GPU/device proof")
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    directory = root/'directrg/src/androidTest/kotlin/com/code2hack/eyeprojector/directrg'
    actual = [directory/'WindowPresentationRecorder.kt', directory/'NativeInputObservationRunState.kt',
              directory/'ReceiverStartupDiagnostics.kt',
              root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/WindowPresentationObservation.kt']
    generated = []
    for name, content in {**DOUBLES, 'PresentationCollectionChecks.kt': FIXTURE}.items():
        path = output/name
        path.write_text(content)
        generated.append(path)
    cache = Path.home()/'.gradle/caches/modules-2/files-2.1'
    dependencies = [('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21'),
                    ('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'),('org.jetbrains.kotlin','kotlin-script-runtime','2.2.21'),
                    ('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),
                    ('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),('org.jetbrains','annotations','13.0')]
    jars = []
    for group, artifact, version in dependencies:
        matches = list((cache/group/artifact/version).glob('*/'+artifact+'-'+version+'.jar'))
        if len(matches) != 1:
            parser.error('Missing/ambiguous cached dependency '+artifact+'; no downloads')
        jars.append(matches[0])
    java = str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    cp = os.pathsep.join(map(str, jars))
    jar = output/'presentation-collection-checks.jar'
    compile_command = [java,'-Xmx512m','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                       '-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(jar)]+list(map(str,actual+generated))
    run_command = [java,'-Xmx128m','-cp',str(jar)+os.pathsep+cp,
                   'com.code2hack.eyeprojector.directrg.PresentationCollectionChecksKt']
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    result = {'scope':'Actual recorder, immutable product DTO, existing main failure helper and status predicate '
                      'compiled against narrow JSON/Android/clock/owner-slot doubles; no whole Owner, Looper, GPU or device execution',
              'inputs':[{'path':str(path),'sha256':digest(path)} for path in actual+generated+[Path(__file__)]],
              'dependencies':[{'path':str(path),'sha256':digest(path)} for path in jars],
              'commands':{'compile':compile_command,'checks':run_command}}
    deadline = time.monotonic()+115
    result_path = output/'result.json'
    def save(): result_path.write_text(json.dumps(result,indent=2)+'\n')
    def run(command, filename, limit):
        with (output/filename).open('w') as log:
            try:
                return subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,
                                      timeout=min(limit,max(.1,deadline-time.monotonic()))).returncode, False
            except subprocess.TimeoutExpired:
                log.write('\nHOST_COLLECTION_CHECK_TIMEOUT\n')
                return 124, True
    save()
    result['compileExit'],result['compileTimedOut'] = run(compile_command,'compile.log',45); save()
    if result['compileExit'] == 0:
        result['checksExit'],result['checksTimedOut'] = run(run_command,'checks.log',15); save()
    for name in ['compile.log','checks.log']:
        path = output/name
        if path.exists(): print(path.read_text(),end='')
    result['artifacts'] = [{'path':path.name,'sha256':digest(path)} for path in sorted(output.iterdir())
                           if path.is_file() and path != result_path]
    save()
    raise SystemExit(result['compileExit'] or result.get('checksExit',1))


if __name__ == '__main__':
    main()
