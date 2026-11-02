#!/usr/bin/env python3
"""Compile frozen actual reader/selection/store with framework doubles, never Gradle/SDK/device."""
import argparse, hashlib, json, os, shutil, subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--output-dir',type=Path,required=True)
p.add_argument('--dependency-manifest',type=Path)
p.add_argument('--production-ref')
p.add_argument('--registration-only',action='store_true')
a=p.parse_args(); suite=Path(__file__).resolve().parent; root=suite.parents[3]; out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=False)
def sha(b):return hashlib.sha256(b).hexdigest()
if a.dependency_manifest:
    manifest=json.loads(a.dependency_manifest.read_text()); jars=[]
    for e in manifest['jars']:
        assert sha(Path(e['path']).read_bytes())==e['sha256']
        if e['kind']=='compiler-dependency':jars.append(e['path'])
else:
    cache=Path(os.environ.get('GRADLE_USER_HOME',Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
    deps=['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21','org.jetbrains.kotlin/kotlin-stdlib/2.2.21','org.jetbrains.kotlin/kotlin-script-runtime/2.2.21','org.jetbrains.kotlin/kotlin-reflect/1.6.10','org.jetbrains/annotations/13.0','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0']
    jars=[str(j) for d in deps for j in sorted((cache/d).rglob('*.jar'))]
assert jars
actual=root/'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost';protocol=root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
sources=sorted((actual/'uiaccess').glob('*.kt'))+[actual/'ProjectionContext.kt',actual/'hid/PhoneHidKeyService.kt']
sources += [protocol/(n+'.kt') for n in ['Wire','Touch','PhoneInput','ReceiverUsability','CameraFeed','ActivationControl','AnchorIntent','AnchorMessage','OriginalContact']]
sources += [actual/(s+'.kt') for s in ['anchor/target/AnchorTargetDescriptor','anchor/target/AnchorTargetResolver','anchor/preferences/AppAnchor','anchor/preferences/AppAnchorStore','anchor/registration/AnchorRegistrationTarget','anchor/registration/AnchorRegistrationCoordinator']]
sources += sorted((suite/'doubles').glob('*.kt'))+[suite/'RegistrationScenarios.kt',suite/'Scenarios.kt']
inputs=[]; frozen=[]
for path in sources+[Path(__file__).resolve(),suite/'fixtures/doubao-voice306.json']:
    rel=path.relative_to(root);data=subprocess.check_output(['git','show',a.production_ref+':'+str(rel)],cwd=root) if a.production_ref and '/src/main/' in str(path) else path.read_bytes()
    target=out/'inputs'/rel;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
    inputs.append({'path':str(path),'snapshot':str(target),'sha256':sha(data)})
    if path in sources:frozen.append(str(target))
raw=json.loads((suite/'fixtures/doubao-voice306.json').read_text())['nodes']
def kt(v):
    if v is None:return 'null'
    if isinstance(v,bool):return str(v).lower()
    if isinstance(v,str):return json.dumps(v)
    if isinstance(v,list):return 'listOf('+','.join(kt(x) for x in v)+')'
    return str(v)
data='package com.code2hack.eyeprojector.directhost.uiaccess\ninternal fun capturedVoiceNodes() = listOf(\n'+',\n'.join('RawVoiceNode('+','.join(kt(n.get(k)) for k in ['ordinal','parentOrdinal','childIndex','childCount','resourceId','className','bounds','actionIds','clickable','longClickable','visible','enabled'])+')' for n in raw)+'\n)\n'
data=data.replace('RawVoiceNode(0,null,null,','RawVoiceNode(0,null,0,')
generated=out/'FixtureData.kt';generated.write_text(data);frozen.append(str(generated));inputs.append({'path':'generated raw fixture','snapshot':str(generated),'sha256':sha(data.encode())})
cp=os.pathsep.join(jars);java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java'); jar=out/'uiaccess-checks.jar'
cmd=[java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-jvm-target','17','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(jar)]+frozen
with (out/'compile.log').open('w') as f:c=subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT,timeout=120)
r=None;commands=[]
if c.returncode==0:
    for main in (['RegistrationFixtureChecks'] if a.registration_only else ['ScenariosKt','RegistrationFixtureChecks']):
        cmd2=[java,'-cp',str(jar)+os.pathsep+cp,'com.code2hack.eyeprojector.directhost.uiaccess.'+main];commands.append(cmd2)
        with (out/(main+'.log')).open('w') as f:r=subprocess.run(cmd2,stdout=f,stderr=subprocess.STDOUT,timeout=60)
        if r.returncode:break
result={'compileExit':c.returncode,'scenariosExit':r.returncode if r else None,'productionRef':a.production_ref,'scope':'Actual Android adapter, reader, integer mapping, hierarchy selection, fresh resolution and store; framework handles modeled; no physical proof','compilerCommand':cmd,'scenarioCommands':commands,'sources':inputs,'dependencies':[{'path':j,'sha256':sha(Path(j).read_bytes())} for j in jars]}
if jar.exists():result['jarSha256']=sha(jar.read_bytes())
(out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:result[k] for k in ['compileExit','scenariosExit','productionRef']}))
print((out/'compile.log').read_text())
for log in out.glob('*.log'):
    if log.name!='compile.log':print(log.read_text())
raise SystemExit(c.returncode or (r.returncode if r else 1))
