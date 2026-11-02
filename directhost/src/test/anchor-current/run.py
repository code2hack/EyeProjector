#!/usr/bin/env python3
"""Focused current-source Anchor, Phone ingress and chooser regressions; no Android/SDK invocation."""
import argparse, hashlib, json, os, shutil, subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--output-dir',type=Path,required=True)
p.add_argument('--dependency-result',type=Path,required=True)
a=p.parse_args();root=Path(__file__).resolve().parents[4];out=a.output_dir.resolve()
if out.exists():p.error('Refusing to overwrite a frozen attempt')
out.mkdir(parents=True)
host=root/'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
rg=root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
proto=root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
sources=[];tests=[]
def add(folder,names):sources.extend(folder/(n+'.kt') for n in names.split())
def suite(module,package,names):
 folder=root/module/'src/test/kotlin'/package.replace('.','/')
 for n in names.split():sources.append(folder/(n+'.kt'));tests.append(package+'.'+n)
add(proto,'Wire Touch PhoneInput ReceiverUsability CameraFeed ActivationControl AnchorIntent AnchorMessage HidNavigationGestureRecognizer ReceiverControlIntent LayoutScale OriginalContact SessionState')
add(host,'ProjectionContext PhoneTouchDispatcher PhoneInputTrace PhoneInputTraceConfig')
add(host/'input','PhoneInputMerger InputWork PhoneHidRoute PhoneHidOriginal')
add(host/'hid','PhoneHidController PhoneHidBindings')
add(host/'activation','PhoneHidActivation PhoneHidDisposition PhoneActivationOwner HidWakeRequest LaunchCommitPermit')
add(host/'anchor/execution','AnchorControlAdmission AnchorContactOwner AnchorAppCoordinator AnchorSessionInput AnchorExecutionCodec AnchorRegistrationDiagnostic')
add(host/'anchor/registration','AnchorRegistrationTarget AnchorRegistrationCoordinator')
add(host/'anchor/preferences','AppAnchor AppAnchorStore')
sources.extend(sorted((host/'anchor/target').glob('*.kt')))
add(host/'uiaccess','UiTargetSnapshot UiSourceGeometry UiTargetReader UiTargetSnapshotProvider')
add(rg,'InputRetirement AnchorHeldIndicator FunctionGestureRecognizer AnchorRegistrationController')
add(root/'directhost/src/test/uiaccess/doubles','ProjectionDependencies SnapshotDescriptor')
add(root/'directhost/src/test/anchor-hold/doubles','Json')
add(Path(__file__).resolve().parent,'LogBoundary RawControlBoundary')
suite('protocol','com.code2hack.eyeprojector.protocol','AnchorIntentTest AnchorMessageTest PhoneInputTest TouchTest WireTest HidNavigationGestureRecognizerTest')
suite('directhost','com.code2hack.eyeprojector.directhost','PhoneInputMergerTest PhoneTouchDispatcherTest')
suite('directhost','com.code2hack.eyeprojector.directhost.hid','PhoneHidFunctionCompositionTest PhoneHidNavigationCompositionTest')
suite('directhost','com.code2hack.eyeprojector.directhost.input','InputWorkTest PhoneRegistrationReleaseTest')
suite('directhost','com.code2hack.eyeprojector.directhost.anchor.execution','AnchorExecutionTest')
suite('directhost','com.code2hack.eyeprojector.directhost.anchor.registration','AnchorRegistrationTest')
suite('directhost','com.code2hack.eyeprojector.directhost.anchor.target','AnchorTargetResolverTest')
suite('directrg','com.code2hack.eyeprojector.directrg','FunctionGestureRecognizerTest AnchorRegistrationControllerTest RegistrationReleaseTest')
sha=lambda b:hashlib.sha256(b).hexdigest()
deps=json.loads(a.dependency_result.read_text())['dependencies'];jars=[]
for d in deps:
 q=Path(d['path']);assert sha(q.read_bytes())==d['sha256'],q;jars.append(q)
cp=os.pathsep.join(map(str,jars));java=shutil.which('java');copies=[];records=[]
for i,s in enumerate(sources):
 q=out/'sources'/str(i)/s.name;q.parent.mkdir(parents=True);q.write_bytes(s.read_bytes());copies.append(q)
 records.append({'path':str(s),'snapshot':str(q),'sha256':sha(q.read_bytes())})
aidl=root/'directhost/src/main/aidl/com/code2hack/eyeprojector/directhost/anchor/execution/IAnchorRawControl.aidl'
(out/'IAnchorRawControl.aidl').write_bytes(aidl.read_bytes())
records.append({'path':str(aidl),'snapshot':str(out/'IAnchorRawControl.aidl'),'sha256':sha(aidl.read_bytes())})
(out/'runner.py').write_bytes(Path(__file__).read_bytes());jar=out/'current.jar'
compile=[java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-jvm-target','17','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(jar)]+list(map(str,copies))
run=[java,'-cp',str(jar)+os.pathsep+cp,'org.junit.runner.JUnitCore']+tests
record={'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'scope':__doc__+' Actual full production sources; unused Android projection return types and JSON telemetry are declared test boundaries. Current worktree tests and production are byte-frozen below, no cached product classes.', 'sources':records,'dependencies':deps,'runnerSha256':sha(Path(__file__).read_bytes()),'compileCommand':compile,'testCommand':run}
with (out/'compile.log').open('w') as f:r=subprocess.run(compile,stdout=f,stderr=subprocess.STDOUT,timeout=120)
record['compileExit']=r.returncode
if r.returncode==0:
 with (out/'junit.log').open('w') as f:r=subprocess.run(run,stdout=f,stderr=subprocess.STDOUT,timeout=60)
 record['testExit']=r.returncode;record['artifactSha256']=sha(jar.read_bytes());print((out/'junit.log').read_text())
else:print((out/'compile.log').read_text())
record['logs']={q.name:sha(q.read_bytes()) for q in out.glob('*.log')}
(out/'result.json').write_text(json.dumps(record,indent=2)+'\n')
raise SystemExit(record['compileExit'] or record.get('testExit',1))
