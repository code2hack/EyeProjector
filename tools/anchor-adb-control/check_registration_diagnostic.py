#!/usr/bin/env python3
"""Cached JVM checks of the exact diagnostic FIFO lease; no SDK/device invocation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--output-dir', required=True)
out = Path(p.parse_args().output_dir)
out.mkdir(parents=True, exist_ok=False)
repo = Path(__file__).resolve().parents[2]
source = repo / 'directhost/src/androidTest/kotlin/com/code2hack/eyeprojector/directhost/AnchorRegistrationDiagnostic.kt'
text = source.read_text()
lease = text[text.index('internal class RegistrationDiagnosticLease'):text.index('/** No Activity,')]
(out / 'Lease.kt').write_text('package checks\nimport java.util.concurrent.CountDownLatch\n' + lease)
(out / 'Checks.kt').write_text(r'''package checks
private class Lane {
    val work = ArrayDeque<() -> Unit>()
    var inside = false
    fun enqueue(action: () -> Unit) { work.addLast(action) }
    fun drain() { while (work.isNotEmpty()) { inside=true; try { work.removeFirst()() } finally { inside=false } } }
}
private class Fixture {
    val lane=Lane(); var calls=0; var observed=0; var failures=0
    val request=Any(); var received:Any?=null
    val original:(Any)->Unit={ calls++; received=it }
    var handler:((Any)->Unit)?=original
    fun lease(observe:(Any)->Unit={ observed++ }, originalFailure:(Throwable)->Unit={ failures++ }) =
        RegistrationDiagnosticLease(lane::enqueue,
            { check(lane.inside); handler }, { check(lane.inside); handler=it }, observe, {}, originalFailure)
}
fun main() {
    run { val f=Fixture(); val x=f.lease(); x.install(); x.close(); f.lane.drain()
        check(f.handler===f.original && x.installed=="CANCELED_BEFORE_INSTALL" && x.restored=="NO_OWN_HANDLER_INSTALLED")
        println("PASS pending installation cannot run after closure") }
    run { val f=Fixture(); f.handler=null; val x=f.lease(); x.install(); f.lane.drain(); x.close(); f.lane.drain()
        check(f.handler==null && x.installed=="NO_EXISTING_HANDLER")
        println("PASS missing handler stays missing") }
    run { val f=Fixture(); val x=f.lease(); x.install(); f.lane.drain(); f.handler!!(f.request)
        check(f.calls==1 && f.received===f.request && f.observed==1); x.close(); f.lane.drain()
        check(f.handler===f.original && x.restored=="RESTORED_EXACT_PRIOR_HANDLER")
        println("PASS original request and handler once then exact FIFO restore") }
    run { val f=Fixture(); val x=f.lease(observe={ error("observer failure") }); x.install(); f.lane.drain(); f.handler!!(f.request)
        check(f.calls==1 && f.received===f.request)
        println("PASS observation failure does not block original") }
    run { val f=Fixture(); val originalError=IllegalStateException("original"); f.handler={ throw originalError }
        val x=f.lease(originalFailure={ error("diagnostic failure") }); x.install(); f.lane.drain()
        try { f.handler!!(f.request); error("missing original throw") } catch(t:Throwable) { check(t===originalError) }
        println("PASS same original exception survives diagnostic exception") }
    run { val f=Fixture(); val x=f.lease(); x.install(); f.lane.drain(); val old=f.handler!!
        val successor:(Any)->Unit={}; f.handler=successor; x.close(); old(f.request); f.lane.drain()
        check(f.handler===successor && f.calls==1 && f.observed==0 && x.restored=="OWN_HANDLER_REPLACED_NOT_OVERWRITTEN")
        println("PASS close disables observation and preserves successor handler") }
}
''')
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
jars = []
for group, name, version in [
    ('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'),
    ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'),
    ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'),
    ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
    ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
    ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
    ('org.jetbrains', 'annotations', '13.0'),
]:
    found = list((cache / group / name / version).glob('*/' + name + '-' + version + '.jar'))
    assert len(found) == 1
    jars += found
cp = os.pathsep.join(map(str, jars))
java = '/usr/lib/jvm/java-17-openjdk-amd64/bin/java'
commands = {
    'compile': [java, '-Xmx512m', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-d', str(out/'checks.jar'),
                str(out/'Lease.kt'), str(out/'Checks.kt')],
    'checks': [java, '-cp', str(out/'checks.jar')+os.pathsep+cp, 'checks.ChecksKt'],
}
def ref(path):
    return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
result = {'scope': 'Exact extracted FIFO lease with recording queue/property boundaries; no Android/Main method/runtime proof.',
          'source': ref(source), 'inputs': [ref(out/'Lease.kt'), ref(out/'Checks.kt')],
          'dependencies': [ref(x) for x in jars], 'commands': commands}
for name, argv in commands.items():
    with (out/(name+'.log')).open('w') as log:
        result[name+'Exit'] = subprocess.run(argv, stdout=log, stderr=subprocess.STDOUT, timeout=45).returncode
    (out/'result.json').write_text(json.dumps(result, indent=2)+'\n')
    if result[name+'Exit']:
        print((out/(name+'.log')).read_text())
        raise SystemExit(result[name+'Exit'])
print((out/'checks.log').read_text())
