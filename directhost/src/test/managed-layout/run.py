#!/usr/bin/env python3
"""Targeted actual-source mapping/retirement composition; no SDK or Android device invocation."""
import argparse, hashlib, importlib.util, json, os, re, subprocess, sys
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output-dir', type=Path, required=True)
p.add_argument('--dependency-result', type=Path, required=True)
p.add_argument('--git-ref', help='Frozen historical production source; test/scenario source stays current')
a = p.parse_args()
suite = Path(__file__).resolve().parent
repo = suite.parents[3]
out = a.output_dir.resolve()
out.mkdir(parents=True, exist_ok=False)
sha = lambda b: hashlib.sha256(b).hexdigest()
manifest = {'productionRef': a.git_ref or 'worktree', 'scope':
    'Actual full LiveScale/PhoneInputMerger/PhoneTouchDispatcher and protocol/models; '
    'exact extracted Session managed-Layout arm and ManagedProjectionDisplay mapping methods. '
    'Actual ManagedProjectionInput geometry/touch/tap/scroll methods; display callback/discovery, '
    'original-display unique-ID lookup, native injection, clock and logging are controlled host boundaries; '
    'not complete Session startup, Android Binder or physical E2E.', 'sources': [], 'cuts': []}

def read(path):
    data = subprocess.check_output(['git', 'show', a.git_ref + ':' + path], cwd=repo) if a.git_ref else (repo/path).read_bytes()
    dest = out/'source'/path; dest.parent.mkdir(parents=True, exist_ok=True); dest.write_bytes(data)
    manifest['sources'].append({'path': path, 'snapshot': str(dest), 'sha256': sha(data)})
    return data.decode(), dest

parser_path = repo/'directhost/src/test/hid/run.py'
parser_copy = out/'parser.py'; parser_copy.write_bytes(parser_path.read_bytes())
spec = importlib.util.spec_from_file_location('source_blocks', parser_copy)
parser = importlib.util.module_from_spec(spec); spec.loader.exec_module(parser)
manifest['parserSha256'] = sha(parser_copy.read_bytes())

host = 'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost/'
protocol = 'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol/'
names = ['Wire','Touch','PhoneInput','ReceiverUsability','ActivationControl','SessionState',
    'LayoutScale','CameraFeed','ReceiverControlSource','OriginalContact','PhoneHidGestureRecognizer','CoordinateAnchors','CoordinateMode']
sources = [read(protocol+n+'.kt')[1] for n in names]
names = ['LiveScale','PhoneTouchDispatcher','input/PhoneInputMerger','input/InputWork','ProjectionCoordinates']
sources += [read(host+n+'.kt')[1] for n in names]

def cut(text, marker, origin):
    value = parser.block(text, marker)
    manifest['cuts'].append({'origin':origin,'marker':marker,'sha256':sha(value.encode()),'text':value})
    return value

display_path = host+'ManagedProjectionDisplay.kt'
display, _ = read(display_path)
blocks = [cut(display, r'^    internal data class '+n+r'\(', display_path) for n in ['SourceObservation','InputMapping']]
blocks += [cut(display, r'^    internal sealed interface Read', display_path)]
blocks += [cut(display, r'^    fun '+n+r'\(', display_path) for n in
    ['resize','inputGeometry','withInputMapping','layoutGeometry','inputDestination','withInputDestination']]
destination = re.search(r'^    internal class InputDestination[\s\S]*?\)\n', display, re.M).group()
blocks.append(destination); manifest['cuts'].append({'origin':display_path,'text':destination,'sha256':sha(destination.encode())})
# sourceObservation is a one-line actual declaration.
line = next(l for l in display.splitlines() if l.startswith('    fun sourceObservation('))
blocks.append(line); manifest['cuts'].append({'origin':display_path,'text':line,'sha256':sha(line.encode())})
shell_path = host+'ShellMain.kt'; shell,_ = read(shell_path)
layout = cut(shell, r'^                        is ManagedProjectionDisplay.Read.Layout ->', shell_path)
body = layout[layout.index('{')+1:layout.rfind('}')]
boundary = (suite/'Boundary.kt').read_text().replace('// ACTUAL_DISPLAY_METHODS','\n'.join(blocks)).replace('// ACTUAL_SESSION_LAYOUT_BODY',body)
generated = out/'LayoutBoundary.kt'; generated.write_text(boundary); sources.append(generated)
context_path=host+'ProjectionContext.kt'; context,_=read(context_path)
context_value=cut(context,r'^data class ProjectionContext\(',context_path)
context_file=out/'ProjectionContext.kt';context_file.write_text('package com.code2hack.eyeprojector.directhost\n'+context_value+'\n');sources.append(context_file)
adapter_path=host+'ManagedProjectionInput.kt';adapter,_=read(adapter_path)
contact=adapter[adapter.index('    private class Contact('):adapter.index('    // InputManagerGlobal')]
manifest['cuts'].append({'origin':adapter_path,'text':contact,'sha256':sha(contact.encode())})
adapter_blocks=[contact]+[cut(adapter,r'^    (?:@Synchronized )?override fun '+n+r'\(',adapter_path)
    for n in ['geometry','touch','tap','scroll']]
adapter_blocks.append(cut(adapter,r'^    private fun sourceCoordinates\(',adapter_path))
adapter_boundary='''package com.code2hack.eyeprojector.directhost
import com.code2hack.eyeprojector.protocol.*
private object SystemClock { fun uptimeMillis() = System.nanoTime()/1_000_000 }
private object InputDevice { const val SOURCE_TOUCHSCREEN=4098;const val SOURCE_MOUSE=8194 }
private object MotionEvent { const val ACTION_DOWN=0;const val ACTION_UP=1;const val ACTION_MOVE=2;const val ACTION_CANCEL=3;const val ACTION_SCROLL=8;const val TOOL_TYPE_FINGER=1;const val TOOL_TYPE_MOUSE=3 }
internal class ManagedProjectionInput(private val owner:ManagedProjectionDisplay):PhoneTouchDispatcher.MotionTarget {
    data class Submission(val display:Int,val point:Pair<Float,Float>,val action:Int,val down:Long,val time:Long)
    val submitted=mutableListOf<Submission>()
    var onSubmission:(Submission)->Unit={}
    private fun motion(displayId:Int,point:Pair<Float,Float>,action:Int,downTime:Long,eventTime:Long,
        source:Int,toolType:Int,pressure:Float,verticalScroll:Float=0f):Boolean {
        val value=Submission(displayId,point,action,downTime,eventTime);submitted.add(value);onSubmission(value);return true
    }
// ACTUAL_ADAPTER
}
'''
adapter_file=out/'ManagedProjectionInput.kt';adapter_file.write_text(adapter_boundary.replace('// ACTUAL_ADAPTER','\n'.join(adapter_blocks)));sources.append(adapter_file)
for name in ['Boundary.kt','Scenarios.kt','run.py']:
    data=(suite/name).read_bytes();(out/name).write_bytes(data)
    manifest.setdefault('harness',[]).append({'name':name,'sha256':sha(data)})
sources.append(out/'Scenarios.kt')
manifest['generated']=[{'path':str(f),'sha256':sha(f.read_bytes())} for f in [generated,context_file,adapter_file]]
deps=json.loads(a.dependency_result.read_text())['dependencies']
for d in deps:
    if sha(Path(d['path']).read_bytes())!=d['sha256']: raise RuntimeError('Changed frozen dependency '+d['path'])
manifest['dependencies']=deps
cp=os.pathsep.join(d['path'] for d in deps)
java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else '/usr/bin/java'
jar=out/'managed-layout.jar'
compile_cmd=[java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-jvm-target','17','-no-stdlib','-no-reflect',
    '-classpath',cp,'-d',str(jar)]+[str(f) for f in sources]
run_cmd=[java,'-cp',str(jar)+os.pathsep+cp,'com.code2hack.eyeprojector.directhost.ScenariosKt']
manifest.update(compileCommand=compile_cmd,testCommand=run_cmd,compileExit=None,testExit=None)
exit_code=1
try:
    with (out/'compile.log').open('w') as log: result=subprocess.run(compile_cmd,stdout=log,stderr=subprocess.STDOUT,timeout=120)
    manifest['compileExit']=result.returncode;exit_code=result.returncode
    if result.returncode==0:
        with (out/'scenarios.log').open('w') as log: result=subprocess.run(run_cmd,stdout=log,stderr=subprocess.STDOUT,timeout=30)
        manifest['testExit']=result.returncode;exit_code=result.returncode
        print((out/'scenarios.log').read_text())
    else: print((out/'compile.log').read_text())
except subprocess.TimeoutExpired as error:
    manifest['timeoutSeconds']=error.timeout;exit_code=124
finally:
    if jar.exists():manifest['jarSha256']=sha(jar.read_bytes())
    manifest['logs']=[{'name':n,'sha256':sha((out/n).read_bytes())} for n in ['compile.log','scenarios.log'] if (out/n).exists()]
    (out/'result.json').write_text(json.dumps(manifest,indent=2)+'\n')
sys.exit(exit_code)
