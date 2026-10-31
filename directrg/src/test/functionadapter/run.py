#!/usr/bin/env python3
"""Compile exact Activity Function callbacks and actual native/HID adapters on host boundaries."""
import argparse, hashlib, importlib.util, json, os, subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--source-dir',type=Path,required=True);p.add_argument('--output-dir',type=Path,required=True)
a=p.parse_args();root=a.source_dir.resolve();out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=False)
suite=root/'directrg/src/test/functionadapter';actual=root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg'
helper=root/'directrg/src/test/hidadapter/run-held-local.py'
spec=importlib.util.spec_from_file_location('extract',helper);extract=importlib.util.module_from_spec(spec);spec.loader.exec_module(extract)
source=(actual/'ReceiverActivity.kt').read_text()
initializers=[extract.initializer(source,'functionInput','NativeFunctionInput'),extract.initializer(source,'hidInput','HidKeyInput')]
methods=[next(line for line in source.splitlines() if line.startswith('    private fun functionContext('))]
methods += [extract.method(source,n) for n in ['captureFunction','dispatchFunction']]
start=source.index('    private data class FunctionCapture(');end=source.index('\n',start)
capture_type=source[start:end]
fixture=(suite/'ActivityBoundary.kt').read_text().replace('// ACTUAL_CAPTURE_TYPE',capture_type)
fixture=fixture.replace('// ACTUAL_INITIALIZERS','\n'.join(initializers)).replace('// ACTUAL_METHODS','\n'.join(methods))
generated=out/'ActivityFunctionWiring.kt';generated.write_text(fixture)
files=[generated,suite/'Scenarios.kt']+sorted((suite/'doubles').glob('*.kt'))
files += [actual/n for n in ['FunctionGestureRecognizer.kt','NativeFunctionInput.kt','HidKeyInput.kt','ContextualNavigation.kt','HidBindings.kt','HidDecisionTrace.kt','PadGestureRecognizer.kt','HeadPointerModel.kt','HeadOrientation.kt']]
files += [root/'directrg/src/test/hidframework/doubles'/n for n in ['Point.kt','Rect.kt','Log.kt']]
protocol=root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'
files += [protocol/n for n in ['FunctionIntent.kt','Wire.kt','PhoneInput.kt','ReceiverUsability.kt','ActivationControl.kt','CameraFeed.kt','Touch.kt','BackHomeGestureRecognizer.kt']]
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
deps=['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21','org.jetbrains.kotlin/kotlin-stdlib/2.2.21','org.jetbrains.kotlin/kotlin-script-runtime/2.2.21','org.jetbrains.kotlin/kotlin-reflect','org.jetbrains/annotations/13.0','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm']
cp=os.pathsep.join(str(j) for d in deps for j in sorted((cache/d).rglob('*.jar')))
inputs=[];snapshots=[]
for n,f in enumerate(files):
 snapshot=out/'source'/str(n)/f.name;snapshot.parent.mkdir(parents=True,exist_ok=True);snapshot.write_bytes(f.read_bytes());snapshots.append(str(snapshot));inputs.append({'path':str(f),'snapshot':str(snapshot),'sha256':hashlib.sha256(snapshot.read_bytes()).hexdigest()})
java=str(Path(os.environ.get('JAVA_HOME','/usr/lib/jvm/java-17-openjdk-amd64'))/'bin/java');jar=out/'function-adapter.jar'
commands={'compile':[java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(jar)]+snapshots,'run':[java,'-cp',str(jar)+os.pathsep+cp,'com.code2hack.eyeprojector.directrg.ScenariosKt']}
record={'sourceActivitySha256':hashlib.sha256((actual/'ReceiverActivity.kt').read_bytes()).hexdigest(),'initializers':initializers,'methods':methods,'captureType':capture_type,'inputs':inputs,'commands':commands,'scope':'Exact Activity Function initialization/capture/dispatch plus actual native/HID grammar; modeled surrounding Android/UI/Session APIs, no firmware/device proof'}
for phase,limit in [('compile',90),('run',30)]:
 with (out/(phase+'.log')).open('w') as log:r=subprocess.run(commands[phase],stdout=log,stderr=subprocess.STDOUT,timeout=limit)
 record[phase+'Exit']=r.returncode;print((out/(phase+'.log')).read_text(),end='')
 if r.returncode:break
(out/'result.json').write_text(json.dumps(record,indent=2)+'\n')
raise SystemExit(0 if record.get('compileExit')==record.get('runExit')==0 else 1)
