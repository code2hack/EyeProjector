#!/usr/bin/env python3
"""Compile actual Session and launch adapters against host-only external API doubles."""
import os,json,hashlib,subprocess,argparse,pathlib,shutil
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output-dir',type=pathlib.Path,required=True);p.add_argument('--protocol-classpath',type=pathlib.Path);p.add_argument('--source-dir',type=pathlib.Path,help='Exact production host source snapshot for matched before/after checks');p.add_argument('--stop-race-only',action='store_true');p.add_argument('--function-failure-only',action='store_true');a=p.parse_args()
suite=pathlib.Path(__file__).resolve().parent;root=suite.parents[3];out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=True)
cache=pathlib.Path(os.environ.get('GRADLE_USER_HOME',pathlib.Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
deps=['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21','org.jetbrains.kotlin/kotlin-stdlib/2.2.21','org.jetbrains.kotlin/kotlin-script-runtime/2.2.21','org.jetbrains.kotlin/kotlin-reflect','org.jetbrains/annotations/13.0','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm']
jars=[str(j) for dep in deps for j in sorted((cache/dep).rglob('*.jar'))]
cp=os.pathsep.join([str(a.protocol_classpath or root/'protocol/build/tmp/kotlin-classes/debug')]+jars)
java=str(pathlib.Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
actual=a.source_dir.resolve() if a.source_dir else root/'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
sources=list((suite/'doubles').glob('*.kt'))+[root/'directhost/src/test/adapter/doubles/AtomicFile.kt',suite/'Scenarios.kt',suite/'ServiceContextScenarios.kt',suite/'FusionScenarios.kt',suite/'StopRaceScenarios.kt',suite/'FunctionFailureScenarios.kt']
sources += [actual/(n+'.kt') for n in ['ShellMain','PanelPower','LiveScale','PhoneTouchDispatcher','HeldInputRelease','OwnedRelease','ProjectedAppLaunch','PlatformProjectionLaunch','ProjectionDisplayState','ProjectionContext','ShizukuHostService','OwnedProjectionRoutes','OwnedHostLifecycle','OwnedShellSessions','BootstrapSessionFiles']]
sources += [actual/'input/PhoneInputMerger.kt',actual/'input/FunctionWork.kt']
sources += [root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'/n for n in ['Wire.kt', 'ActivationControl.kt', 'ReceiverUsability.kt','PhoneInput.kt','Touch.kt','LayoutScale.kt']]
jar=out/'session-launch-composition.jar'
cmd=[java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(jar)]+list(map(str,sources))
snapshots=[]
for index,source in enumerate(sources):
    snapshot=out/'compiled-sources'/str(index)/source.name;snapshot.parent.mkdir(parents=True,exist_ok=True);snapshot.write_bytes(source.read_bytes());snapshots.append(snapshot)
cmd[-len(sources):]=list(map(str,snapshots))
run=[java,'-cp',str(jar)+os.pathsep+cp,'com.code2hack.eyeprojector.directhost.'+('StopRaceScenariosKt' if a.stop_race_only else 'FunctionFailureScenariosKt' if a.function_failure_only else 'ScenariosKt')]
record={'compileArgv':cmd,'runArgv':run,'sources':[{'path':str(s),'snapshot':str(copy),'sha256':hashlib.sha256(copy.read_bytes()).hexdigest()}for s,copy in zip(sources,snapshots)],'scope':'Actual Shizuku service/routes/Bridge/Session/launch/release with external API doubles; no device or Binder proof'}
(out/'inputs.json').write_text(json.dumps(record,indent=2)+'\n')
with (out/'compile.log').open('w') as log:r=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=120)
record['compileExit']=r.returncode
if r.returncode:
    (out/'result.json').write_text(json.dumps(record,indent=2)+'\n');print((out/'compile.log').read_text());raise SystemExit(r.returncode)
with (out/'scenarios.log').open('w') as log:r=subprocess.run(run,stdout=log,stderr=subprocess.STDOUT,timeout=60)
record.update({'exit':r.returncode,'logSha256':hashlib.sha256((out/'scenarios.log').read_bytes()).hexdigest()})
(out/'result.json').write_text(json.dumps(record,indent=2)+'\n')
print((out/'scenarios.log').read_text());raise SystemExit(r.returncode)
