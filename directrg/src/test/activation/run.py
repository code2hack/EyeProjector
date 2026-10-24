#!/usr/bin/env python3
"""Compile actual activation/core/control/Receiver journals on explicit TLS/log boundaries, no SDK/Gradle."""
import argparse, hashlib, json, os, subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--output-dir',type=Path,required=True)
p.add_argument('--composed',action='store_true')
p.add_argument('--tls',action='store_true')
p.add_argument('--client',action='store_true',help='Only canonical PairedSessionClientTest with its actual privacy helper source; modeled transport')
a=p.parse_args(); out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=False)
root=Path(__file__).resolve().parents[4]; suite=Path(__file__).resolve().parent
protocol=root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
glass=root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
tests=root/'directrg/src/test/kotlin'
names=['ProjectionSession','ReceiverSessionOwner','ReceiverActivationStore','PairedSessionClient','RetainedVideoOutput','SurfaceDecoder',
       'SessionAttachment','StatePublisher','VideoReadiness','VideoClock','HeldTouchTransport','OrderedControlDispatcher','LatestHeadPointer',
       'CompletedReceiverInput','SessionPresentation','HeadPointerModel','HeadOrientation','HeldPointerDispatcher','PadGestureRecognizer']
files=[glass/(n+'.kt') for n in names]
files += [protocol/n for n in ['SessionControl.kt','SessionState.kt','ActivationControl.kt','ReceiverUsability.kt','Wire.kt','PhoneInput.kt','Touch.kt','LayoutScale.kt']]
classes=['ReceiverUsabilityTest','RetainedReceiverSessionTest','ProjectionInputTest','HeadPointerExpiryTest','PhoneFusionTest','SessionCloseCompletionTest']
files += [tests/'com/code2hack/eyeprojector/directrg'/(n+'.kt') for n in classes]
classes += ['ReceiverStartupTest']
files += [suite/'ReceiverStartupTest.kt']
files += sorted((tests/'android').rglob('*.kt'))+sorted((tests/'org/json').glob('*.kt'))+sorted((suite/'doubles').glob('*.kt'))
if not a.tls and not a.client:
    import feedback_source
    generated,extraction=feedback_source.generate(root,out)
    (out/'activity-extraction.json').write_text(json.dumps(extraction,indent=2)+'\n')
    files += [generated,suite/'ReceiverActivityFeedbackTest.kt']
    classes += ['ReceiverActivityFeedbackTest']
if a.tls:
    files=[f for f in files if f not in [suite/'ReceiverStartupTest.kt'] and '/src/test/kotlin/com/code2hack/' not in str(f) and f.name not in ['Transport.kt','StartupPeer.kt'] and '/protocol/src/main/' not in str(f)]
    classes=['VideoSilenceTest']
    files += [tests/'com/code2hack/eyeprojector/directrg/VideoSilenceTest.kt']
    files += [f for f in sorted(protocol.glob('*.kt')) if f.name!='PairingPanel.kt']
    files += [root/'directhost/src/test/adapter/doubles/Annotation.kt']
if a.client:
    classes=['PairedSessionClientTest']
    files=[glass/(n+'.kt') for n in ['PairedSessionClient','ReceiverActivationStore']]
    files += [f for f in sorted(protocol.glob('*.kt')) if f.name!='PairingPanel.kt']
    files += [tests/'com/code2hack/eyeprojector/directrg'/(n+'.kt') for n in ['PairedSessionClientTest','ControlOutcomePrivacyTest']]
    files += [tests/'android/util/Log.kt',tests/'android/os/SystemClock.kt',root/'directhost/src/test/adapter/doubles/Annotation.kt']
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
coordinates=[('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21'),('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'),('org.jetbrains.kotlin','kotlin-script-runtime','2.2.21'),('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),('org.jetbrains','annotations','13.0'),('junit','junit','4.13.2'),('org.hamcrest','hamcrest-core','1.3'),('org.bouncycastle','bcprov-jdk18on','1.83'),('org.bouncycastle','bcutil-jdk18on','1.83'),('org.bouncycastle','bcpkix-jdk18on','1.83')]
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
          'run':[java,'-cp',str(jar)+os.pathsep+cp,'org.junit.runner.JUnitCore']+['com.code2hack.eyeprojector.directrg.'+n for n in classes]}
result={'inputs':inputs,'dependencies':[{'path':str(f),'sha256':sha(f)} for f in jars],'commands':commands,'compileExit':None,'runExit':None,'scope':('Actual canonical PairedSessionClientTest and privacy helper with actual client/store/control codec sources; Android log/clock and injected transport boundaries explicit, no TLS/Android/device proof' if a.client else 'Actual Receiver owner/session/decoder/retained renderer/control codecs with explicit Android/GPU/TLS doubles, not physical proof')}
try:
    for phase,limit in [('compile',90),('run',30)]:
        with (out/(phase+'.log')).open('w') as log:r=subprocess.run(commands[phase],stdout=log,stderr=subprocess.STDOUT,timeout=limit,cwd=out)
        result[phase+'Exit']=r.returncode;print((out/(phase+'.log')).read_text(),end='')
        if r.returncode:break
finally:
    (out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
raise SystemExit(0 if result['compileExit']==result['runExit']==0 else 1)
