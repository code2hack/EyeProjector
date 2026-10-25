#!/usr/bin/env python3
"""Compile exact Activity binding/Exit methods on explicit framework/service boundaries; no SDK."""
import argparse,hashlib,importlib.util,json,os,subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output-dir',type=Path,required=True);a=p.parse_args()
out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=False)
root=Path(__file__).resolve().parents[4];suite=Path(__file__).resolve().parent
parser=root/'directhost/src/test/hid/run.py'
spec=importlib.util.spec_from_file_location('extract',parser);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
activity=root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ReceiverActivity.kt';s=activity.read_text()
markers=[r'^        override fun onServiceConnected\(',r'^    private fun finishProjection\(',r'^    internal fun exitToLauncher\(',r'^    private fun cancelOriginalOnExit\(']
blocks=[m.block(s,x) for x in markers]
service=root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ReceiverSessionService.kt';ss=service.read_text()
start=ss.index('        internal fun cancelIntent(');end=ss.index('        const val DISCONNECT',start);cancel=ss[start:end]
header='''package com.code2hack.eyeprojector.directrg
import android.app.Activity
import android.content.*
import android.os.IBinder
internal class Part {fun stop()=Unit}
internal class OwnerBoundary {
 val launches=mutableListOf<ReceiverLaunchScope>()
 val canceled=mutableListOf<Pair<ReceiverLaunchScope?,String?>>()
 fun launch(scope:ReceiverLaunchScope){launches.add(scope)}
 fun disconnect(complete:(()->Unit)?=null,scope:ReceiverLaunchScope?=null,activationId:String?=null){canceled.add(scope to activationId);complete?.invoke()}
}
internal object ReceiverWake {const val ACTIVATION="eyeprojector.activation";fun readIntent(intent:Intent):com.code2hack.eyeprojector.protocol.ReceiverDemand?=null}
internal class ReceiverSessionService {
 class LocalBinder(val owner:OwnerBoundary):IBinder
 companion object {
  private const val TRIGGER="eyeprojector.trigger"
  private const val SCOPE="eyeprojector.scope"
  private const val TASK="eyeprojector.task"
  const val EXIT="com.code2hack.eyeprojector.directrg.EXIT"
'''
header+=cancel+'\n }\n}\n'
header+='''internal class ActivityLifecycleSource:Activity(),ServiceConnection {
 private var owner:OwnerBoundary?=null
 private var serviceBinder:ReceiverSessionService.LocalBinder?=null
 private val launchScope=ReceiverLaunchScope("original-scope","original-T",7)
 private var visibleActivation:String?="original-H"
 private var pendingExit:Pair<ReceiverLaunchScope,String?>?=null
 private var pendingForget:(()->Unit)?=null
 private var bindingValid=true
 private var disconnectBeforeBinding=false
 private var connectBeforeBinding=false
 private var resumed=true
 private val hidDevices=Part();private val functionInput=Part();private val longTouch=Part();private val padContact=Part();private val pointer=Part()
 var wakeValid=true
 var attachments=0;var registrations=0
 private fun validWake(local:ReceiverSessionService.LocalBinder,intent:Intent)=wakeValid
 private fun applyReceiverDemand(demand:com.code2hack.eyeprojector.protocol.ReceiverDemand)=Unit
 private fun attachPresentation(){attachments++}
 private fun registerTask(scope:ReceiverLaunchScope,activation:String?){registrations++}
 private fun cancelInput()=Unit
 fun finishForTest()=finishProjection()
 fun scopeForTest()=launchScope
'''
generated=out/'ActivityLifecycleSource.kt';generated.write_text(header+'\n'.join(blocks)+'\n}\n')
files=sorted((suite/'doubles').glob('*.kt'))+[suite/'Scenarios.kt',generated,root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ReceiverActivationStore.kt',root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/ActivationControl.kt',root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/ReceiverUsability.kt',root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/Wire.kt',root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/Touch.kt',root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/PhoneInput.kt']
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
coords=[('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21'),('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'),('org.jetbrains.kotlin','kotlin-script-runtime','2.2.21'),('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),('org.jetbrains','annotations','13.0')]
jars=[]
for g,n,v in coords:
 matches=list((cache/g/n/v).glob('*/'+n+'-'+v+'.jar'));assert len(matches)==1;jars+=matches
sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest()
inputs=[];compiled=[]
for i,f in enumerate(files+[activity,service,parser,Path(__file__).resolve()]):
 dest=out/'source'/str(i)/f.name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(f.read_bytes())
 inputs.append({'path':str(f),'snapshot':str(dest),'sha256':sha(dest),'compiled':i<len(files)})
 if i<len(files):compiled.append(str(dest))
java='/usr/lib/jvm/java-17-openjdk-amd64/bin/java';cp=os.pathsep.join(map(str,jars));jar=out/'lifetime.jar'
commands={'compile':[java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(jar)]+compiled,'run':[java,'-cp',str(jar)+os.pathsep+cp,'com.code2hack.eyeprojector.directrg.ScenariosKt']}
result={'inputs':inputs,'dependencies':[{'path':str(f),'sha256':sha(f)} for f in jars],'commands':commands,'scope':'Exact extracted Activity onServiceConnected/finish/Exit/cancel helper and Service cancelIntent; actual Receiver journal/model; explicit framework/owner/service doubles, no full Activity/Android/device proof','compileExit':None,'runExit':None}
try:
 for phase in ['compile','run']:
  with (out/(phase+'.log')).open('w') as log:r=subprocess.run(commands[phase],stdout=log,stderr=subprocess.STDOUT,timeout=90)
  result[phase+'Exit']=r.returncode;print((out/(phase+'.log')).read_text(),end='')
  if r.returncode:break
finally:(out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
raise SystemExit(0 if result['compileExit']==result['runExit']==0 else 1)
