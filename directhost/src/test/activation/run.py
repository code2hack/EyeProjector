#!/usr/bin/env python3
"""Compile actual activation/core/control/Receiver journals on explicit TLS/log boundaries, no SDK/Gradle."""
import argparse, hashlib, json, os, subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--output-dir',type=Path,required=True)
p.add_argument('--composed',action='store_true')
p.add_argument('--producer',action='store_true')
p.add_argument('--transport',action='store_true',help='Producer plus actual Session/merger composition; Android/native/Binder boundaries explicit')
p.add_argument('--source-root',type=Path,help='Exact production source snapshot; fixtures remain current and are separately retained')
a=p.parse_args(); out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=False)
root=a.source_root.resolve() if a.source_root else Path(__file__).resolve().parents[4]; suite=Path(__file__).resolve().parent
host=root/'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
protocol=root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
glass=root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
files=list((host/'activation').glob('*.kt'))
files += [host/n for n in ['SessionController.kt','SessionAttemptStore.kt','ProjectionContext.kt','ProjectionDisplayState.kt','SessionUsability.kt']]
files += [protocol/n for n in ['SessionControl.kt','SessionState.kt','ActivationControl.kt','ReceiverUsability.kt','Wire.kt','PhoneInput.kt','Touch.kt','LayoutScale.kt']]
files += [glass/n for n in ['ReceiverActivationStore.kt','ReceiverTaskRegistry.kt','PairedSessionClient.kt']]
if a.producer:
    files += [host/n for n in ['PhoneSessionController.kt','HostBootstrap.kt','HostForgetCompletion.kt','BootstrapSessionFiles.kt','PanelPower.kt','input/PhoneHidRoute.kt','LaunchOperationDiagnostics.kt',
                              'scale/AppScaleCoordinator.kt','scale/AppScalePreferences.kt','scale/AppScaleStore.kt']]
    files += [suite.parent/'adapter/doubles'/n for n in ['AtomicFile.kt','Context.kt','Json.kt','Os.kt','Annotation.kt','PackageManager.kt','Service.kt','Shizuku.kt','Log.kt']]
    files += [suite/'composed/Transport.kt',suite/'composed/Widgets.kt']+sorted((suite/'producer').glob('*.kt'))
    files += [host/'hid'/(n+'.kt') for n in ['PhoneHidBindings','PhoneHidController','PhoneHidRuntime','PhoneHidKeyService']]
    files += [suite.parent/'hid/doubles'/n for n in ['Accessibility.kt','AccessibilityEvent.kt','InputManager.kt','KeyEvent.kt','Settings.kt','PackageManager.kt']]
    if a.transport:
        files += [host/n for n in ['ShellMain.kt','PhoneTouchDispatcher.kt','HeldInputRelease.kt','OwnedRelease.kt','LiveScale.kt','ProjectedAppLaunch.kt','PlatformProjectionLaunch.kt','input/PhoneInputMerger.kt','input/FunctionWork.kt']]
        files += [suite.parent/'panel/doubles'/n for n in ['Sockets.kt']]
        files += [suite.parent/'panel/PanelFixtures.kt',suite/'producer/transport/Platform.kt',suite/'producer/transport/TransportScenarios.kt']
elif a.composed:
    files += [host/n for n in ['PhoneSessionController.kt','PanelPower.kt','input/PhoneHidRoute.kt','input/PhoneInputMerger.kt',
                              'hid/PhoneHidBindings.kt','hid/PhoneHidController.kt','hid/PhoneHidRuntime.kt','hid/PhoneHidKeyService.kt',
                              'scale/AppScaleCoordinator.kt','scale/AppScalePreferences.kt','scale/AppScaleStore.kt',
                              'PhoneTouchDispatcher.kt','widget/PhoneWidgetPanel.kt','widget/PhoneWidgetActionTurn.kt','widget/PhoneWidgetModel.kt','widget/PhoneWidgetSessionAdapter.kt']]
    excluded={'PhoneSessions.kt','PhoneHidRoute.kt','Protocol.kt'}
    files += [f for f in sorted((suite.parent/'hid/doubles').glob('*.kt')) if f.name not in excluded]
    files += [root/'directhost/src/test/kotlin/com/code2hack/eyeprojector/directhost'/n for n in ['hid/PhoneHidFunctionCompositionTest.kt','widget/PhoneWidgetTest.kt']]
    files += sorted((suite/'composed').glob('*.kt'))+[suite/'doubles/Log.kt']
else:
    files += sorted((suite/'doubles').glob('*.kt'))+[suite/'SharedScenarios.kt']
if not a.producer and not a.composed: files += [host/'input/PhoneHidRoute.kt']
files += [host/'activation/snapshot'/n for n in ['NativeSnapshotRegion.kt','SnapshotWord.kt','PhoneHidSnapshotLease.kt']]
files += sorted((suite.parent/'snapshot/transport/doubles').glob('*.kt'))
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
          'run':[java,('-Deyeprojector.transport=true' if a.transport else '-Deyeprojector.transport=false'),'-cp',str(jar)+os.pathsep+cp,('com.code2hack.eyeprojector.directhost.activation.producer.ScenariosKt' if a.producer else 'com.code2hack.eyeprojector.directhost.activation.ComposedScenariosKt' if a.composed else 'com.code2hack.eyeprojector.directhost.activation.SharedScenariosKt')]}
result={'inputs':inputs,'dependencies':[{'path':str(f),'sha256':sha(f)} for f in jars],'commands':commands,'compileExit':None,'runExit':None,'scope':('Actual Bootstrap/facade/Runtime/KeyService/controller/native Kotlin writer+reader/Session/merger/core/control with explicit Android/JNI/SharedMemory/Binder/TLS/platform boundaries; no native/runtime/physical proof' if a.transport else 'Actual HostBootstrap producer and facade/core/feature/control with explicit Android/Shizuku/Binder/persistence boundaries; no native/runtime/physical proof' if a.producer else 'Actual facade/runtime/HID/merger/core/feature/store/control code with explicit Android/Bootstrap/Binder/TLS doubles; not real Android/backend resources or physical proof' if a.composed else 'Actual core/feature engine/stores/control codecs/paired client; TLS/log boundaries explicit, no facade/Android/backend/physical proof')}
try:
    for phase,limit in [('compile',90),('run',60 if a.producer else 30)]:
        with (out/(phase+'.log')).open('w') as log:r=subprocess.run(commands[phase],stdout=log,stderr=subprocess.STDOUT,timeout=limit)
        result[phase+'Exit']=r.returncode;print((out/(phase+'.log')).read_text(),end='')
        if r.returncode:break
except subprocess.TimeoutExpired as error:
    result['timeoutSeconds']=error.timeout
    raise
finally:
    (out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
raise SystemExit(0 if result['compileExit']==result['runExit']==0 else 1)
