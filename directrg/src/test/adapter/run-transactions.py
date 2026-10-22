#!/usr/bin/env python3
"""Compile actual admission/client/controller/store source and preserve before/after transaction regressions."""
import argparse, hashlib, json, os, shutil, subprocess, sys
from datetime import datetime, timezone
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--output-dir',type=Path,required=True)
p.add_argument('--revision',help='Read exact sources using git show, e.g. frozen85; no checkout/worktree mutation')
p.add_argument('--expect-baseline-failure',action='store_true')
a=p.parse_args()
suite=Path(__file__).resolve().parent
repo=suite.parents[3]
out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=True)
inputs=out/'inputs';inputs.mkdir(exist_ok=True)
paths=[
 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/PairedSessionClient.kt',
 'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/SessionAttachment.kt',
 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/SessionController.kt',
 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/SessionAttemptStore.kt',
 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/SessionState.kt',
 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/SessionControl.kt',
]
manifest=[];sources=[]
for rel in paths:
 data=subprocess.check_output(['git','show',a.revision+':'+rel],cwd=repo) if a.revision else (repo/rel).read_bytes()
 snapshot=inputs/Path(rel).name;snapshot.write_bytes(data);sources.append(snapshot)
 manifest.append(dict(repositoryPath=rel,snapshot=str(snapshot),sha256=hashlib.sha256(data).hexdigest()))
for original in (suite/'TransactionScenarios.kt',Path(__file__).resolve()):
 snapshot=inputs/original.name;shutil.copy2(original,snapshot)
 manifest.append(dict(original=str(original),snapshot=str(snapshot),sha256=hashlib.sha256(snapshot.read_bytes()).hexdigest()))
sources.append(inputs/'TransactionScenarios.kt')
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
deps=['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21','org.jetbrains.kotlin/kotlin-stdlib/2.2.21','org.jetbrains.kotlin/kotlin-script-runtime/2.2.21','org.jetbrains.kotlin/kotlin-reflect','org.jetbrains/annotations/13.0','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm']
jars=[str(j) for dep in deps for j in sorted((cache/dep).rglob('*.jar'))]
protocol=repo/'protocol/build/tmp/kotlin-classes/debug'
assert protocol.is_dir() and jars,'Existing Gradle provisioned Kotlin/protocol classes required'
java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
cp=os.pathsep.join([str(protocol)]+jars)
jar=out/'transactions.jar'
command=[java,'-cp',os.pathsep.join(jars),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(jar)]+list(map(str,sources))
with (out/'compile.log').open('w') as f: compile=subprocess.run(command,stdout=f,stderr=subprocess.STDOUT,timeout=120)
if compile.returncode:
 print((out/'compile.log').read_text());sys.exit(compile.returncode)
env=os.environ.copy();env['CASE_ROOT']=str(out/'cases');Path(env['CASE_ROOT']).mkdir(exist_ok=True)
with (out/'scenarios.log').open('w') as f:
 run=subprocess.run([java,'-cp',str(jar)+os.pathsep+cp,'com.code2hack.eyeprojector.directrg.TransactionScenariosKt'],env=env,stdout=f,stderr=subprocess.STDOUT,timeout=60)
text=(out/'scenarios.log').read_text();print(text)
required=['FAIL R8-01 initial STATUS Disconnect','FAIL R8-03 queued Stop lost before Phone process restore','FAIL R8-03 closed Receiver UNKNOWN restore','FAIL R8-04 terminal atomic store IOException','FAIL R8-01 CANCEL overtakes initial STATUS admission','FAIL R8-01 failed first STATUS then cancellation admission']
unchanged=a.revision is not None or all(hashlib.sha256((repo/e['repositoryPath']).read_bytes()).hexdigest()==e['sha256'] for e in manifest if 'repositoryPath'in e)
result=dict(created=datetime.now(timezone.utc).isoformat(),revision=a.revision,compileExit=compile.returncode,scenariosExit=run.returncode,expectedBaselineFailure=a.expect_baseline_failure,requiredNegativeCases=required,inputsUnchanged=unchanged,sources=manifest,logSha256=hashlib.sha256(text.encode()).hexdigest(),protocolClasspathClasses=[dict(path=str(f.relative_to(protocol)),sha256=hashlib.sha256(f.read_bytes()).hexdigest()) for f in sorted(protocol.rglob('*')) if f.is_file()],scope='Actual production client/controller/atomic store/attachment and control codec source; modeled bounded transport/UI/backend work scheduling, host filesystem, no device proof')
(out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
if not unchanged:sys.exit('Live source changed during private harness; retain run and repeat fixed exact head')
if a.expect_baseline_failure:sys.exit(0 if run.returncode!=0 and all(s in text for s in required) and 'PASS positive fresh C and repeated cancellation'in text else 1)
sys.exit(run.returncode)
