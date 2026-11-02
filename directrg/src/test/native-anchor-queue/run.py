#!/usr/bin/env python3
"""Actual raw native socket read loop, main queue and recognizer; controlled Android I/O/clock."""
import argparse,hashlib,json,os,subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output-dir',type=Path,required=True);p.add_argument('--dependency-result',type=Path,required=True);p.add_argument('--git-ref');a=p.parse_args()
root=Path(__file__).resolve().parents[4];suite=Path(__file__).resolve().parent;out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=False)
sha=lambda b:hashlib.sha256(b).hexdigest();records=[];inputs=[]
def snap(path,production=False):
 b=subprocess.check_output(['git','show',a.git_ref+':'+str(path.relative_to(root))],cwd=root) if production and a.git_ref else path.read_bytes()
 q=out/'source'/path.relative_to(root);q.parent.mkdir(parents=True,exist_ok=True);q.write_bytes(b);records.append({'path':str(path),'snapshot':str(q),'sha256':sha(b)});inputs.append(q);return q
proto=root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
for n in 'Wire Touch PhoneInput ReceiverUsability CameraFeed ActivationControl AnchorIntent AnchorMessage ReceiverControlIntent LayoutScale SessionState'.split():snap(proto/(n+'.kt'),True)
main=root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
for n in 'NativePadShellContact NativeFunctionInput FunctionGestureRecognizer InputRetirement ReceiverContactInput AnchorGesture'.split():snap(main/(n+'.kt'),True)
for n in ['Android','Activity','Annotation']:snap(root/'directrg/src/test/functionadapter/doubles'/(n+'.kt'))
snap(suite/'Log.kt')
for n in ['Clock','Socket','Boundary']:snap(suite/(n+'.kt'))
scenario=snap(suite/'Scenarios.kt')
old= a.git_ref and 'captureRawOriginal' not in (out/'source'/main.relative_to(root)/'NativeFunctionInput.kt').read_text()
adapter=None
if old:
 before=scenario.read_text();scenario.write_text(before.replace(',\n      functionCapture={native.captureRawOriginal()}',''))
 adapter='Only omit unavailable new constructor capture callback for historical API; all reader/revision/recognizer bodies remain exact Git.'
 records[-1]['compiledSha256']=sha(scenario.read_bytes())
deps=json.loads(a.dependency_result.read_text())['dependencies']
for d in deps:assert sha(Path(d['path']).read_bytes())==d['sha256']
cp=os.pathsep.join(d['path'] for d in deps);jar=out/'native.jar'
compile=['/usr/bin/java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-jvm-target','17','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(jar)]+list(map(str,inputs))
run=['/usr/bin/java','-cp',str(jar)+os.pathsep+cp,'com.code2hack.eyeprojector.directrg.ScenariosKt']
r={'scope':__doc__+' No device proof. Ordinary pad types are unused boundaries; real input lines traverse actual reader thread/Handler callback/Function recognizer.', 'productionRef':a.git_ref or 'worktree','sources':records,'dependencies':deps,'historicalApiAdapter':adapter,'compileCommand':compile,'testCommand':run,'runnerSha256':sha(Path(__file__).read_bytes())}
(out/'runner.py').write_bytes(Path(__file__).read_bytes())
for phase,cmd in [('compile',compile),('test',run)]:
 with (out/(phase+'.log')).open('w') as f:z=subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT,timeout=120 if phase=='compile' else 30)
 r[phase+'Exit']=z.returncode;print((out/(phase+'.log')).read_text())
 if z.returncode:break
if jar.exists():r['jarSha256']=sha(jar.read_bytes())
r['logs']={q.name:sha(q.read_bytes()) for q in out.glob('*.log')};(out/'result.json').write_text(json.dumps(r,indent=2)+'\n')
raise SystemExit(r['compileExit'] or r.get('testExit',1))
