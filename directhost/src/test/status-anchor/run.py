#!/usr/bin/env python3
"""Actual paired STATUS producer/codec and facade command body -> real Anchor owner, feature FIFO and engine."""
import argparse,hashlib,importlib.util,json,os,subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output-dir',type=Path,required=True);p.add_argument('--dependency-result',type=Path,required=True);p.add_argument('--git-ref');p.add_argument('--retry',action='store_true');a=p.parse_args()
root=Path(__file__).resolve().parents[4];suite=Path(__file__).resolve().parent;out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=False)
sha=lambda b:hashlib.sha256(b).hexdigest();records=[];files=[]
def source(path,prod=True,compile=True):
 b=subprocess.check_output(['git','show',a.git_ref+':'+str(path.relative_to(root))],cwd=root) if a.git_ref and prod else path.read_bytes()
 q=out/'sources'/path.relative_to(root);q.parent.mkdir(parents=True,exist_ok=True);q.write_bytes(b);records.append({'path':str(path),'snapshot':str(q),'sha256':sha(b)})
 if compile:files.append(q)
 return q
host=root/'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost';proto=root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol';rg=root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
for n in 'Wire Touch PhoneInput ReceiverUsability CameraFeed ActivationControl AnchorIntent AnchorMessage ReceiverControlIntent LayoutScale SessionState SessionControl HidNavigationGestureRecognizer OriginalContact'.split():source(proto/(n+'.kt'))
for n in 'ProjectionContext PhoneTouchDispatcher SessionController PhoneInputTrace PhoneInputTraceConfig'.split():source(host/(n+'.kt'))
for n in 'AnchorControlAdmission AnchorContactOwner AnchorSessionInput'.split():source(host/'anchor/execution'/(n+'.kt'))
for n in 'AppAnchor AppAnchorStore'.split():source(host/'anchor/preferences'/(n+'.kt'))
for n in 'AnchorTargetDescriptor AnchorTargetResolver'.split():source(host/'anchor/target'/(n+'.kt'))
for n in 'UiSourceGeometry UiTargetSnapshot'.split():source(host/'uiaccess'/(n+'.kt'))
for n in 'InputWork PhoneHidRoute'.split():source(host/'input'/(n+'.kt'))
for n in 'PhoneActivationEngine PhoneActivationOwner ActivationStore PhoneHidDisposition HidWakeRequest LaunchCommitPermit RemoteControlReceipt'.split():source(host/'activation'/(n+'.kt'))
for n in 'PairedSessionClient ReceiverActivationStore'.split():source(rg/(n+'.kt'))
for q in ['directhost/src/test/uiaccess/doubles/ProjectionDependencies.kt','directhost/src/test/uiaccess/doubles/SnapshotDescriptor.kt','directhost/src/test/anchor-hold/doubles/Json.kt','directhost/src/test/anchor-current/LogBoundary.kt','directhost/src/test/activation/doubles/Transport.kt','directhost/src/test/activation/doubles/Log.kt']:source(root/q,False)
parser=source(root/'directhost/src/test/hid/run.py',False,False);spec=importlib.util.spec_from_file_location('blocks',parser);mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
original=source(host/'PhoneSessionController.kt',True,False);command=mod.block(original.read_text(),r'^    private fun command\(')
decision=mod.block(original.read_text(),r'^    private fun remoteControlConsumed\(')
boundary=source(suite/'Boundary.kt',False,False);generated=out/'FacadeCommand.kt';generated.write_text(boundary.read_text().replace('// ACTUAL_COMMAND',command).replace('// ACTUAL_CONTROL_DECISION',decision));files.append(generated)
source(suite/('RetryScenarios.kt' if a.retry else 'Scenarios.kt'),False);source(Path(__file__).resolve(),False,False)
deps=json.loads(a.dependency_result.read_text())['dependencies']
for d in deps:assert sha(Path(d['path']).read_bytes())==d['sha256']
cp=os.pathsep.join(d['path'] for d in deps);jar=out/'status-anchor.jar'
compile=['/usr/bin/java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-jvm-target','17','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(jar)]+list(map(str,files))
run=['/usr/bin/java','-cp',str(jar)+os.pathsep+cp,'com.code2hack.eyeprojector.directhost.'+('RetryScenariosKt' if a.retry else 'ScenariosKt')]
r={'scope':__doc__+' Platform MotionTarget, facade Bootstrap/drain dependencies, log/TLS are explicit boundaries; no complete Android Session or physical cause proof.', 'productionRef':a.git_ref or 'worktree','sources':records,'dependencies':deps,'cuts':[{'source':str(original),'body':command,'sha256':sha(command.encode())},{'source':str(original),'body':decision,'sha256':sha(decision.encode())}],'generated':{'path':str(generated),'sha256':sha(generated.read_bytes())},'compileCommand':compile,'testCommand':run}
for phase,cmd in [('compile',compile),('test',run)]:
 with (out/(phase+'.log')).open('w') as f:z=subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT,timeout=120 if phase=='compile' else 30)
 r[phase+'Exit']=z.returncode;print((out/(phase+'.log')).read_text())
 if z.returncode:break
if jar.exists():r['jarSha256']=sha(jar.read_bytes())
r['logs']={q.name:sha(q.read_bytes()) for q in out.glob('*.log')};(out/'result.json').write_text(json.dumps(r,indent=2)+'\n')
raise SystemExit(r['compileExit'] or r.get('testExit',1))
