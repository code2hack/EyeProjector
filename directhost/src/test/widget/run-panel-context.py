#!/usr/bin/env python3
"""Verify widget expected-context overload in actual HostBootstrap with existing modeled Binder fixture."""
import argparse, hashlib, json, os, subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--output-dir',type=Path,required=True)
a=p.parse_args(); output=a.output_dir.resolve(); output.mkdir(parents=True,exist_ok=True)
suite=Path(__file__).resolve().parent; root=suite.parents[3]
actual=root/'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
adapter=suite.parent/'adapter/doubles'
cache=Path(os.environ.get('GRADLE_USER_HOME',Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
deps=['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21','org.jetbrains.kotlin/kotlin-stdlib/2.2.21','org.jetbrains.kotlin/kotlin-script-runtime/2.2.21','org.jetbrains.kotlin/kotlin-reflect','org.jetbrains/annotations/13.0','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm']
cp=os.pathsep.join([str(root/'protocol/build/tmp/kotlin-classes/debug')]+[str(j) for d in deps for j in sorted((cache/d).rglob('*.jar'))])
java=str(Path(os.environ['JAVA_HOME'])/'bin/java')
sources=[adapter/n for n in ['Context.kt','AtomicFile.kt','Json.kt','Os.kt','Annotation.kt','PackageManager.kt','Service.kt','Shizuku.kt']]
sources += [actual/n for n in ['HostBootstrap.kt','HostForgetCompletion.kt','BootstrapSessionFiles.kt','PanelPower.kt','ProjectionContext.kt','ProjectionDisplayState.kt']]
sources += [root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'/n for n in ['Wire.kt', 'PhoneInput.kt','LayoutScale.kt','Touch.kt']]
sources += [actual/'input/PhoneHidRoute.kt', suite/'PanelContextScenarios.kt']
archive=output/'compiled-sources';archive.mkdir(exist_ok=True)
for i,s in enumerate(sources):(archive/f'{i:02d}-{s.name}').write_bytes(s.read_bytes())
inputs=[{'path':str(s),'sha256':hashlib.sha256(s.read_bytes()).hexdigest()} for s in sources]
(output/'compiler-inputs.json').write_text(json.dumps(inputs,indent=2)+'\n')
jar=output/'widget-panel-context.jar'
with (output/'compile.log').open('w') as log:
 compiled=subprocess.run([java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(jar)]+list(map(str,sources)),stdout=log,stderr=subprocess.STDOUT,timeout=120)
if compiled.returncode: print((output/'compile.log').read_text());raise SystemExit(compiled.returncode)
with (output/'scenarios.log').open('w') as log:
 run=subprocess.run([java,'-cp',str(jar)+os.pathsep+cp,'com.code2hack.eyeprojector.directhost.PanelContextScenariosKt'],stdout=log,stderr=subprocess.STDOUT,timeout=60)
(output/'result.json').write_text(json.dumps({'compileExit':compiled.returncode,'scenariosExit':run.returncode,'inputs':inputs,'scope':'Actual HostBootstrap expected-context panel facade; modeled Binder/Shizuku/Android, no physical proof'},indent=2)+'\n')
print((output/'scenarios.log').read_text());raise SystemExit(run.returncode)
