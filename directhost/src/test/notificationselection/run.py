#!/usr/bin/env python3
"""Bounded cached JVM checks of actual notification selection, canonical policy and both launch queues."""
import argparse, hashlib, json, os, subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--output-dir',type=Path,required=True)
p.add_argument('--source-root',type=Path,help='Exact production source snapshot; fixtures remain current and are separately retained')
p.add_argument('--correction65-only',action='store_true',help='Run only modeled local-order and retired-registration correction cases')
a=p.parse_args(); out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=False)
root=a.source_root.resolve() if a.source_root else Path(__file__).resolve().parents[4]; suite=Path(__file__).resolve().parents[1]/'activation'
host=root/'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
protocol=root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
glass=root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
files=list((host/'activation').glob('*.kt'))
files += [p for p in (host/'notifications').glob('*.kt') if p.name != 'NotificationAccessPanel.kt']
files += [host/'apps'/n for n in ['ChosenApps.kt','ChosenAppStore.kt']]
files += [Path(__file__).resolve().parent/'Scenarios.kt']
files += [root/'directhost/src/test/kotlin/com/code2hack/eyeprojector/directhost'/n for n in
          ['notifications/NotificationEventsTest.kt','notifications/NotificationIngressRuntimeTest.kt',
           'apps/ChosenAppStoreTest.kt','scale/AppScaleCoordinatorTest.kt']]
files += [host/n for n in ['SessionController.kt','SessionAttemptStore.kt','ProjectionContext.kt','ProjectionDisplayState.kt','SessionUsability.kt']]
files += [protocol/n for n in ['SessionControl.kt','SessionState.kt','ActivationControl.kt','ReceiverUsability.kt','Wire.kt','PhoneInput.kt','Touch.kt','LayoutScale.kt']]
files += [glass/n for n in ['ReceiverActivationStore.kt','ReceiverTaskRegistry.kt','PairedSessionClient.kt']]
files += [host/n for n in ['PhoneSessionController.kt','HostBootstrap.kt','HostForgetCompletion.kt','BootstrapSessionFiles.kt','PanelPower.kt','input/PhoneHidRoute.kt','LaunchOperationDiagnostics.kt',
                          'scale/AppScaleCoordinator.kt','scale/AppScalePreferences.kt','scale/AppScaleStore.kt']]
files += [suite.parent/'adapter/doubles'/n for n in ['AtomicFile.kt','Context.kt','Json.kt','Os.kt','Annotation.kt','PackageManager.kt','Service.kt','Shizuku.kt','Log.kt']]
files += [suite/'composed/Transport.kt',suite/'composed/Widgets.kt']+sorted((suite/'producer').glob('*.kt'))
files += [host/'hid'/(n+'.kt') for n in ['PhoneHidBindings','PhoneHidController','PhoneHidRuntime','PhoneHidKeyService']]
files += [suite.parent/'hid/doubles'/n for n in ['Accessibility.kt','AccessibilityEvent.kt','InputManager.kt','KeyEvent.kt','Settings.kt','PackageManager.kt']]
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
coordinates=[('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21'),('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'),('org.jetbrains.kotlin','kotlin-script-runtime','2.2.21'),('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),('org.jetbrains','annotations','13.0'),('junit','junit','4.13.2'),('org.hamcrest','hamcrest-core','1.3')]
jars=[]
for group,name,version in coordinates:
    paths=sorted((cache/group/name/version).glob(f'*/{name}-{version}.jar'))
    if len(paths)!=1:p.error('Missing cached compiler input '+name)
    jars+=paths
sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest()
inputs=[]; compiled=[]
for i,f in enumerate(files):
    dest=out/'source'/str(i)/f.name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(f.read_bytes())
    inputs.append({'path':str(f),'snapshot':str(dest),'sha256':sha(dest)});compiled.append(str(dest))
java=str(Path(os.environ.get('JAVA_HOME','/usr/lib/jvm/java-17-openjdk-amd64'))/'bin/java')
cp=os.pathsep.join(map(str,jars)); jar=out/'activation.jar'
commands={'compile':[java,'-Xmx2g','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(jar)]+compiled,
          'run':[java,'-cp',str(jar)+os.pathsep+cp,'com.code2hack.eyeprojector.directhost.notifications.selectiontest.ScenariosKt']}
if a.correction65_only: commands['run'].append('--correction65-only')
result={'inputs':inputs,'dependencies':[{'path':str(f),'sha256':sha(f)} for f in jars],'commands':commands,'compileExit':None,'runExit':None,'scope':'Actual facade+HostBootstrap+AppScaleCoordinator+notification selection+canonical policy/store adapters. Android/Shizuku/Binder/JSON/TLS boundaries doubled; explicit delivery/settlement adversarial cases use ControlledPort; no native/device/physical proof', 'runner':{'path':str(Path(__file__).resolve()),'sha256':sha(Path(__file__).resolve())}}
try:
    for phase,limit in [('compile',90),('run',60)]:
        with (out/(phase+'.log')).open('w') as log:r=subprocess.run(commands[phase],stdout=log,stderr=subprocess.STDOUT,timeout=limit)
        result[phase+'Exit']=r.returncode;print((out/(phase+'.log')).read_text(),end='')
        if r.returncode:break
except subprocess.TimeoutExpired as error:
    result['timeoutSeconds']=error.timeout
    raise
finally:
    (out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
raise SystemExit(0 if result['compileExit']==result['runExit']==0 else 1)
