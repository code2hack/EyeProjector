#!/usr/bin/env python3
"""Actual native receiver/recognizer and drawn menu on explicit framework rendering doubles."""
import argparse,hashlib,json,os,subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output-dir',type=Path,required=True);p.add_argument('--dependency-manifest',type=Path,required=True);a=p.parse_args()
root=Path(__file__).resolve().parents[4];suite=Path(__file__).resolve().parent;out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=False)
manifest=json.loads(a.dependency_manifest.read_text());sha=lambda x:hashlib.sha256(x.read_bytes()).hexdigest()
jars=[]
for e in manifest['jars']:
 assert sha(Path(e['path']))==e['sha256']
 if e['kind']=='compiler-dependency':jars.append(e['path'])
proto=root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol';main=root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
files=[proto/(n+'.kt') for n in ['Wire','Touch','PhoneInput','ReceiverUsability','ActivationControl','CameraFeed','AnchorIntent','AnchorMessage','ReceiverControlIntent','LayoutScale','SessionState']]
files += [main/(n+'.kt') for n in ['InputRetirement','FunctionGestureRecognizer','NativeFunctionInput','AnchorGesture','AnchorRegistrationController','AnchorRegistrationOverlay']]
files += [root/'directrg/src/test/functionadapter/doubles'/(n+'.kt') for n in ['Android','Activity','Annotation','Clock']]
files += [root/'directrg/src/test/kotlin/android/util/Log.kt']
files += sorted((suite/'doubles').glob('*.kt'))+[suite/'RegistrationMenuRenderingTest.kt']
files += [root/'directrg/src/test/kotlin/com/code2hack/eyeprojector/directrg'/(n+'.kt') for n in ['RegistrationReleaseTest','AnchorRegistrationControllerTest']]
snapshots=[]
for f in files+[Path(__file__).resolve()]:
 target=out/'inputs'/f.relative_to(root);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(f.read_bytes());snapshots.append({'path':str(f),'snapshot':str(target),'sha256':sha(target)})
cp=os.pathsep.join(jars);java='/usr/lib/jvm/java-17-openjdk-amd64/bin/java';jar=out/'menu-renderer.jar'
compile=[java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-jvm-target','17','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(jar)]+[s['snapshot'] for s in snapshots if s['path'].endswith('.kt')]
run=[java,'-cp',str(jar)+os.pathsep+cp,'org.junit.runner.JUnitCore']+['com.code2hack.eyeprojector.directrg.'+n for n in ['RegistrationMenuRenderingTest','RegistrationReleaseTest','AnchorRegistrationControllerTest']]
r={'sources':snapshots,'dependencies':[{'path':j,'sha256':sha(Path(j))} for j in jars],'commands':{'compile':compile,'run':run},'scope':'Actual native BroadcastReceiver/grammar/controller/overlay draw bodies on framework drawing/delivery doubles; no Android compile or hardware acceptance'}
for phase,cmd in [('compile',compile),('test',run)]:
 with (out/(phase+'.log')).open('w') as log:z=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=120 if phase=='compile' else 30)
 r[phase+'Exit']=z.returncode;print((out/(phase+'.log')).read_text())
 if z.returncode:break
if jar.exists():r['artifactSha256']=sha(jar)
(out/'result.json').write_text(json.dumps(r,indent=2)+'\n')
raise SystemExit(r['compileExit']or r.get('testExit',1))
