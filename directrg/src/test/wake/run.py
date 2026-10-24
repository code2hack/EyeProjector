#!/usr/bin/env python3
"""Compile actual wake/journal sources on explicit Android power/launch boundaries; no SDK."""
import argparse,hashlib,importlib.util,json,os,subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output-dir',type=Path,required=True);a=p.parse_args()
out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=False)
root=Path(__file__).resolve().parents[4];suite=Path(__file__).resolve().parent
files=sorted((suite/'doubles').glob('*.kt'))+[suite/'Scenarios.kt',root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ReceiverWake.kt',root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ReceiverActivationStore.kt',root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/ActivationControl.kt',root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/ReceiverUsability.kt',root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/Wire.kt',root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/Touch.kt',root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/PhoneInput.kt']
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
coords=[('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21'),('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'),('org.jetbrains.kotlin','kotlin-script-runtime','2.2.21'),('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),('org.jetbrains','annotations','13.0')]
jars=[]
for g,n,v in coords:
 matches=list((cache/g/n/v).glob('*/'+n+'-'+v+'.jar'));assert len(matches)==1;jars+=matches
sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest()
inputs=[];compiled=[]
for i,f in enumerate(files+[Path(__file__).resolve()]):
 dest=out/'source'/str(i)/f.name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(f.read_bytes())
 inputs.append({'path':str(f),'snapshot':str(dest),'sha256':sha(dest),'compiled':i<len(files)})
 if i<len(files):compiled.append(str(dest))
java='/usr/lib/jvm/java-17-openjdk-amd64/bin/java';cp=os.pathsep.join(map(str,jars));jar=out/'lifetime.jar'
commands={'compile':[java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(jar)]+compiled,'run':[java,'-cp',str(jar)+os.pathsep+cp,'com.code2hack.eyeprojector.directrg.ScenariosKt']}
result={'inputs':inputs,'dependencies':[{'path':str(f),'sha256':sha(f)} for f in jars],'commands':commands,'scope':'Actual ReceiverWake and original scope journal/control models on explicit PowerManager/Activity boundary with manual fixture reconcile ticks; no Android/framework/device wake proof','compileExit':None,'runExit':None}
try:
 for phase in ['compile','run']:
  with (out/(phase+'.log')).open('w') as log:r=subprocess.run(commands[phase],stdout=log,stderr=subprocess.STDOUT,timeout=90)
  result[phase+'Exit']=r.returncode;print((out/(phase+'.log')).read_text(),end='')
  if r.returncode:break
finally:(out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
raise SystemExit(0 if result['compileExit']==result['runExit']==0 else 1)
