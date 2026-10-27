#!/usr/bin/env python3
"""Cached host checks for the existing driver's diagnostic freshness, without SDK/device access."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--parent-driver', type=Path, help='Preserved pre-addendum driver for the exact old check expressions')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output_dir.resolve(); output.mkdir(parents=True, exist_ok=False)
    source = root/'directrg/src/androidTest/kotlin/com/code2hack/eyeprojector/directrg'
    driver = root/'directrg/src/androidTest/kotlin/com/code2hack/eyeprojector/directrg/ProjectionInstrumentation.kt'
    activity = root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ReceiverActivity.kt'
    spec = importlib.util.spec_from_file_location('existing_adapter', root/'directrg/src/test/adapter/run.py')
    adapter = importlib.util.module_from_spec(spec); spec.loader.exec_module(adapter)
    snapshot_body = adapter.activity_lambda(activity.read_text(), r'internal fun startupDiagnosticSnapshot\(\): Map<String, Any\?>\s*', 'passive startup snapshot')
    callback_body = adapter.activity_lambda(driver.read_text(), r'runOnMainSync\s*(?=\{\s*try\s*\{\s*start = System.nanoTime\(\))', 'main-thread snapshot exception containment')
    snapshot_checks = output/'PassiveStartupSnapshotChecks.kt'
    snapshot_checks.write_text('''package com.code2hack.eyeprojector.directrg
private enum class Phase { AVAILABLE, STREAMING }
private enum class Goal { PROJECTED }
private enum class Mode { NONE, PROJECTED }
private data class Peer(val phase: Phase, val detail: String)
private data class Memory(val state: String, val requested: Boolean, val session: Any?, val peer: Peer?, val activationId: String?)
private class Owner(var value: Memory) {
    var reads = 0; var failRead = false
    fun snapshot(): Memory { reads++; if (failRead) error("passive read failed"); return value }
}
private data class Directive(val desired: Boolean, val goal: Goal)
private data class Report(val mode: Mode, val detail: String)
private class Feature {
    var directiveReads = 0; var reportReads = 0
    val directive get() = Directive(true, Goal.PROJECTED).also { directiveReads++ }
    val presentation get() = Report(Mode.NONE, "DISPLAY_DETACHED").also { reportReads++ }
}
private class Binder(val value: Feature) { var reads = 0; val feature get() = value.also { reads++ } }
private class Surface { val isValid = true }
private class Holder { val surface = Surface() }
private class Screen { val holder = Holder() }
private class Presentation { var panelVisible = false; var live = true }
private class Fixture {
    var owner: Owner? = null
    var serviceBinder: Binder? = null
    var bound = false; var bindingValid = true; var resumed = true
    var isFinishing = false; var isDestroyed = false
    fun hasWindowFocus() = true
    lateinit var screen: Screen
    var connectRequested = false; var connectBeforeBinding = false; var disconnectBeforeBinding = false
    var visibleActivation: String? = "visible-old"
    val presentation = Presentation()
    internal fun startupDiagnosticSnapshot(): Map<String, Any?> '''+snapshot_body+'''
}
internal fun passiveStartupSnapshotChecks() {
    val empty = Fixture(); val absent = empty.startupDiagnosticSnapshot()
    check(absent["ownerState"] == null && absent["sessionPresent"] == null && absent["surfaceValid"] == null)
    check(absent["pairedAvailable"] == null && absent["pairedAvailabilityScope"] == "UNEXPORTED_NO_MEMORY_GETTER")
    val fixture = Fixture()
    val owner = Owner(Memory("CONNECTING", true, null, Peer(Phase.AVAILABLE, "READY"), "owner-new"))
    val feature = Feature(); val binder = Binder(feature)
    fixture.owner = owner; fixture.serviceBinder = binder; fixture.screen = Screen(); fixture.bound = true
    val result = fixture.startupDiagnosticSnapshot()
    check(owner.reads == 1 && binder.reads == 1 && feature.directiveReads == 1 && feature.reportReads == 1)
    check(result["visibleActivation"] == "visible-old" && result["currentActivation"] == "owner-new")
    check(result["ownerState"] == "CONNECTING" && result["ownerRequested"] == true && result["sessionPresent"] == false)
    check(result["activationGoal"] == "PROJECTED" && result["activationDesired"] == true)
    check(result["reportedPresentationMode"] == "NONE" && result["reportedPresentationDetail"] == "DISPLAY_DETACHED")
    check(result["panelSelected"] == false && result["presentationLive"] == true) // Never infer actual mode from selection.
    check(result["surfaceValid"] == true && result["pairedAvailable"] == null)
    check(result.values.all { it == null || it is String || it is Boolean })
    check(fixture.presentation.live && !fixture.presentation.panelVisible && fixture.visibleActivation == "visible-old")
    owner.value = owner.value.copy(state="STREAMING", session=Any())
    check(fixture.startupDiagnosticSnapshot()["sessionPresent"] == true && owner.reads == 2)
    val activity = Fixture()
    activity.owner = owner.apply { failRead = true }
    var values: Map<String, Any?>? = null
    var start: Long? = null
    var end: Long? = null
    var readFailure: Throwable? = null
    var escaped = false
    try '''+callback_body+''' catch (_: Throwable) { escaped = true }
    check(!escaped && readFailure is IllegalStateException && values == null && start != null && end != null)
}
''')
    generated = output/'ReceiverStartupChecksMain.kt'
    generated.write_text('''package com.code2hack.eyeprojector.directrg
fun main() {
    receiverStartupDiagnosticChecks()
    passiveStartupSnapshotChecks()
    println("PASS current-run/current-boot status bounds, preserved3s/terminal semantics, overflow and exact private error fields")
}
''')
    sources = [source/'ReceiverStartupDiagnostics.kt', source/'ReceiverStartupDiagnosticChecks.kt', generated, snapshot_checks]
    if args.parent_driver:
        parent = args.parent_driver.resolve()
        checks = re.findall(r'^        check\(.*$', parent.read_text(), re.M)
        checks = [line for line in checks if 'freshAfterNs' in line or 'Stale session telemetry' in line]
        if len(checks) != 2:
            parser.error('Parent must contain exactly the existing two status check expressions')
        old = output/'ParentFutureStatusCounterexample.kt'
        old.write_text('''package com.code2hack.eyeprojector.directrg
object ParentFutureStatusCounterexample {
    private object System { fun nanoTime() = 14_346_830_000_000L }
    private class Status(val state: String) {
        fun optLong(key: String, default: Long): Long = if (key == "startedMonoNs") 23_461_317_476_307L else 1_352_926L
        fun optString(key: String) = state
    }
    private fun accepts(it: Status): Boolean {
        val freshAfterNs = 14_300_000_000_000L
        return try {
'''+'\n'.join(checks)+'''
            true
        } catch (_: IllegalStateException) { false }
    }
    @JvmStatic fun main(args: Array<String>) {
        val stopped = accepts(Status("STOPPED")); val streaming = accepts(Status("STREAMING"))
        println("PARENT exact-check counterexample: futureStoppedAccepted=$stopped futureStreamingAccepted=$streaming")
        check(!stopped && !streaming) { "Prior-boot future timestamps must not be fresh current-run status" }
    }
}
''')
        sources.append(old)
    cache = Path.home()/'.gradle/caches/modules-2/files-2.1'
    deps = [('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21'),
            ('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'),('org.jetbrains.kotlin','kotlin-script-runtime','2.2.21'),
            ('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),
            ('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),('org.jetbrains','annotations','13.0')]
    jars=[]
    for group,artifact,version in deps:
        matches=list((cache/group/artifact/version).glob('*/'+artifact+'-'+version+'.jar'))
        if len(matches)!=1:parser.error('Missing/ambiguous cached dependency '+artifact)
        jars.append(matches[0])
    java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    cp=os.pathsep.join(map(str,jars));jar=output/'startup-checks.jar'
    compile_cmd=[java,'-Xmx512m','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect',
                 '-jvm-target','17','-classpath',cp,'-d',str(jar)]+list(map(str,sources))
    run_cmd=[java,'-Xmx128m','-cp',str(jar)+os.pathsep+cp,'com.code2hack.eyeprojector.directrg.ReceiverStartupChecksMainKt']
    sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
    inputs=sources+[driver,activity,Path(__file__)]+([args.parent_driver] if args.parent_driver else [])
    result={'scope':'Cached host predicate/error cases and exact-source passive getter with host doubles; optional parent expressions; no Android/startup pass',
            'inputs':[{'path':str(p),'sha256':sha(p)} for p in inputs],
            'dependencies':[{'path':str(p),'sha256':sha(p)} for p in jars],
            'commands':{'compile':compile_cmd,'current':run_cmd}}
    with (output/'compile.log').open('w') as log:
        result['compileExit']=subprocess.run(compile_cmd,stdout=log,stderr=subprocess.STDOUT,timeout=45).returncode
    if result['compileExit']==0:
        if args.parent_driver:
            parent_cmd=run_cmd[:-1]+['com.code2hack.eyeprojector.directrg.ParentFutureStatusCounterexample']
            result['commands']['parentCounterexample']=parent_cmd
            with (output/'parent-red.log').open('w') as log:
                result['parentDesiredBehaviorExit']=subprocess.run(parent_cmd,stdout=log,stderr=subprocess.STDOUT,timeout=15).returncode
        with (output/'current-green.log').open('w') as log:
            result['currentExit']=subprocess.run(run_cmd,stdout=log,stderr=subprocess.STDOUT,timeout=15).returncode
    (output/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    for name in ['compile.log','parent-red.log','current-green.log']:
        if (output/name).exists():print((output/name).read_text(),end='')
    raise SystemExit(result['compileExit'] or result.get('currentExit',1))


if __name__=='__main__':main()
