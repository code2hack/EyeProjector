#!/usr/bin/env python3
"""Kotlin word/JNI signature preflight only; never invoke SDK, CMake, NDK or native code."""
import argparse,hashlib,json,os,re,subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output-dir',type=Path,required=True);a=p.parse_args()
out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=False)
root=Path(__file__).resolve().parents[4]
base=root/'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/activation/snapshot'
files=[base/'SnapshotWord.kt',base/'NativeSnapshotAccess.kt',root/'directhost/src/test/launch/doubles/Keep.kt',root/'directhost/src/test/kotlin/com/code2hack/eyeprojector/directhost/activation/snapshot/SnapshotWordTest.kt']
provenance=[root/'directhost/src/main/cpp/snapshot_cell.c',root/'directhost/src/main/cpp/CMakeLists.txt',base/'NativeSnapshotRegion.kt',root/'directhost/build.gradle.kts',Path(__file__).resolve()]
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
coords=[('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21'),('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'),('org.jetbrains.kotlin','kotlin-script-runtime','2.2.21'),('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),('org.jetbrains','annotations','13.0'),('junit','junit','4.13.2'),('org.hamcrest','hamcrest-core','1.3')]
jars=[]
for g,n,v in coords:
 matches=list((cache/g/n/v).glob('*/'+n+'-'+v+'.jar'));assert len(matches)==1;jars+=matches
sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest()
inputs=[];compiled=[]
for i,f in enumerate(files+provenance):
 dest=out/'source'/str(i)/f.name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(f.read_bytes())
 inputs.append({'path':str(f),'snapshot':str(dest),'sha256':sha(dest),'compiled':i<len(files)})
 if i<len(files):compiled.append(str(dest))
jdk=Path('/usr/lib/jvm/java-17-openjdk-amd64/bin');cp=os.pathsep.join(map(str,jars));jar=out/'snapshot-preflight.jar'
name='com.code2hack.eyeprojector.directhost.activation.snapshot.'
commands={'compile':[str(jdk/'java'),'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(jar)]+compiled,
 'junit':[str(jdk/'java'),'-cp',str(jar)+os.pathsep+cp,'org.junit.runner.JUnitCore',name+'SnapshotWordTest'],
 'signatures':[str(jdk/'javap'),'-private','-s','-classpath',str(jar),name+'NativeSnapshotAccess']}
result={'inputs':inputs,'dependencies':[{'path':str(f),'sha256':sha(f)} for f in jars],'commands':commands,'scope':'Kotlin unsigned cell layout and actual compiled JNI registration signatures only. Native access class is disassembled, never initialized. C/NDK/SharedMemory/ABI/RO runtime/packaging unexecuted.','compileExit':None,'junitExit':None,'signaturesExit':None}
try:
 for phase in ['compile','junit','signatures']:
  with (out/(phase+'.log')).open('w') as log:r=subprocess.run(commands[phase],stdout=log,stderr=subprocess.STDOUT,timeout=90)
  result[phase+'Exit']=r.returncode;print((out/(phase+'.log')).read_text(),end='')
  if r.returncode:break
 if result['signaturesExit']==0:
  text=(out/'signatures.log').read_text()
  for method,signature in [('initializeCell','(Ljava/nio/ByteBuffer;I)J'),('bindCell','(Ljava/nio/ByteBuffer;I)J'),('acquireCell','(J)J'),('releaseCell','(JJ)V')]:
   assert re.search(r'public static final native .* '+method+r'\([^;]*;\s*descriptor: '+re.escape(signature),text),method
  result['jniSignaturesMatch']=True
finally:(out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
raise SystemExit(0 if result['compileExit']==result['junitExit']==result['signaturesExit']==0 and result.get('jniSignaturesMatch') else 1)
