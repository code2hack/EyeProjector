#!/usr/bin/env python3
"""Compile actual Session and launch adapters against host-only external API doubles."""
import os,json,hashlib,subprocess,argparse,pathlib,shutil
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output-dir',type=pathlib.Path,required=True);a=p.parse_args()
suite=pathlib.Path(__file__).resolve().parent;root=suite.parents[3];out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=True)
cache=pathlib.Path(os.environ.get('GRADLE_USER_HOME',pathlib.Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
deps=['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21','org.jetbrains.kotlin/kotlin-stdlib/2.2.21','org.jetbrains.kotlin/kotlin-script-runtime/2.2.21','org.jetbrains.kotlin/kotlin-reflect','org.jetbrains/annotations/13.0','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm']
jars=[str(j) for dep in deps for j in sorted((cache/dep).rglob('*.jar'))]
cp=os.pathsep.join([str(root/'protocol/build/tmp/kotlin-classes/debug')]+jars)
java=str(pathlib.Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
actual=root/'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
sources=list((suite/'doubles').glob('*.kt'))+[root/'directhost/src/test/adapter/doubles/AtomicFile.kt',suite/'Scenarios.kt',suite/'ServiceContextScenarios.kt']
sources += [actual/(n+'.kt') for n in ['ShellMain','PanelPower','LiveScale','PhoneTouchDispatcher','HeldInputRelease','OwnedRelease','ProjectedAppLaunch','PlatformProjectionLaunch','ProjectionDisplayState','ProjectionContext','ShizukuHostService','OwnedProjectionRoutes','OwnedHostLifecycle','OwnedShellSessions','BootstrapSessionFiles']]
jar=out/'session-launch-composition.jar'
cmd=[java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(jar)]+list(map(str,sources))
with (out/'compile.log').open('w') as log:r=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=120)
if r.returncode:print((out/'compile.log').read_text());raise SystemExit(r.returncode)
with (out/'scenarios.log').open('w') as log:r=subprocess.run([java,'-cp',str(jar)+os.pathsep+cp,'com.code2hack.eyeprojector.directhost.ScenariosKt'],stdout=log,stderr=subprocess.STDOUT,timeout=60)
(out/'result.json').write_text(json.dumps({'exit':r.returncode,'sources':[{'path':str(s),'sha256':hashlib.sha256(s.read_bytes()).hexdigest()}for s in sources],'logSha256':hashlib.sha256((out/'scenarios.log').read_bytes()).hexdigest(),'scope':'Actual Shizuku service/routes/Bridge/Session/launch/release with external API doubles; no device or Binder proof'},indent=2)+'\n')
print((out/'scenarios.log').read_text());raise SystemExit(r.returncode)
