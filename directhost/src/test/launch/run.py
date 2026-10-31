#!/usr/bin/env python3
"""Compile actual Session and launch adapters against host-only external API doubles."""
import os,json,hashlib,subprocess,argparse,pathlib,shutil
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output-dir',type=pathlib.Path,required=True);p.add_argument('--protocol-classpath',type=pathlib.Path);p.add_argument('--source-dir',type=pathlib.Path,help='Exact production host source snapshot for matched before/after checks');p.add_argument('--stop-race-only',action='store_true');p.add_argument('--function-failure-only',action='store_true');p.add_argument('--navigation-only',action='store_true');p.add_argument('--function-intents-only',action='store_true');p.add_argument('--function-review-only',action='store_true');p.add_argument('--ui-targets-only',action='store_true');p.add_argument('--anchor-only',action='store_true');p.add_argument('--registration-only',action='store_true');p.add_argument('--frozen-dependencies',type=pathlib.Path);p.add_argument('--nav-context-only',action='store_true');p.add_argument('--stamped-nav-only',action='store_true');p.add_argument('--paired116-manifest',type=pathlib.Path);p.add_argument('--merged116',action='store_true',help='Run stamped navigation against actual merged working-tree sources, without private overrides');a=p.parse_args()
if a.merged116 and (not a.stamped_nav_only or a.paired116_manifest):p.error('--merged116 requires --stamped-nav-only and excludes private source overrides')
suite=pathlib.Path(__file__).resolve().parent;root=suite.parents[3];out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=True)
cache=pathlib.Path(os.environ.get('GRADLE_USER_HOME',pathlib.Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
deps=['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21','org.jetbrains.kotlin/kotlin-stdlib/2.2.21','org.jetbrains.kotlin/kotlin-script-runtime/2.2.21','org.jetbrains.kotlin/kotlin-reflect','org.jetbrains/annotations/13.0','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm']
jars=[str(j) for dep in deps for j in sorted((cache/dep).rglob('*.jar'))]
cp=os.pathsep.join([str(a.protocol_classpath or root/'protocol/build/tmp/kotlin-classes/debug')]+jars)
frozen=a.frozen_dependencies.resolve() if a.frozen_dependencies else None
if frozen:
    manifest=json.loads((frozen/'manifest.json').read_text())
    for item in manifest['entries']:
        assert hashlib.sha256(pathlib.Path(item['snapshot']).read_bytes()).hexdigest()==item['sha256'], 'Frozen dependency hash mismatch'
    jars=[item['snapshot'] for item in manifest['entries'] if '/compiler-deps/' in item['snapshot']]
    cp=os.pathsep.join([str(a.protocol_classpath or frozen/'protocol')]+jars)
host_classes=frozen/'host' if frozen else root/'directhost/build/tmp/kotlin-classes/debug'
# The focused Gradle gate compiles current camera/managed-display dependencies. Keep
# complete Session/launch/input sources below; use those exact remaining class files.
sdk=pathlib.Path(os.environ.get('ANDROID_HOME',pathlib.Path.home()/'Android/Sdk'))
cp+=os.pathsep+os.pathsep.join(map(str,[host_classes,
    frozen/'host-aidl' if frozen else root/'directhost/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes',frozen/'android.jar' if frozen else sdk/'platforms/android-35/android.jar']))
rg_classes=frozen/'glasses' if frozen else root/'directrg/build/tmp/kotlin-classes/debug'
if a.registration_only:
    cp=os.pathsep.join([str(rg_classes),str(frozen/'glasses-test' if frozen else root/'directrg/build/tmp/kotlin-classes/debugUnitTest'),cp])
java=str(pathlib.Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
actual=a.source_dir.resolve() if a.source_dir else root/'directhost/src/main/kotlin/com/code2hack/eyeprojector/directhost'
sources=list((suite/'doubles').glob('*.kt'))+[root/'directhost/src/test/adapter/doubles/AtomicFile.kt',suite/'Scenarios.kt',suite/'ServiceContextScenarios.kt',suite/'FusionScenarios.kt',suite/'StopRaceScenarios.kt',suite/'FunctionFailureScenarios.kt',suite/'NavigationScenarios.kt',suite/'FunctionIntentSessionScenarios.kt',suite/'FunctionReviewScenarios.kt',suite/'UiTargetSessionScenarios.kt',suite/'NonspatialContextScenarios.kt',suite/'StampedNavigationContextScenarios.kt',suite/'AnchorSessionScenarios.kt']
sources += [root/'directrg/src/test/kotlin/android/util/Log.kt']
sources += [actual/(n+'.kt') for n in ['ShellMain','PanelPower','LiveScale','PhoneTouchDispatcher','HeldInputRelease','OwnedRelease','ProjectedAppLaunch','PlatformProjectionLaunch','ProjectionDisplayState','ProjectionContext','ShizukuHostService','OwnedProjectionRoutes','OwnedHostLifecycle','OwnedShellSessions','BootstrapSessionFiles']]
sources += [actual/'SessionUsability.kt']
sources += sorted((actual/'anchor/execution').glob('*.kt')) + sorted((actual/'anchor/preferences').glob('*.kt')) + sorted((actual/'anchor/target').glob('*.kt'))
sources += [actual/'uiaccess'/n for n in ['UiTargetSnapshot.kt','UiSourceGeometry.kt','UiTargetReader.kt','UiTargetSnapshotProvider.kt']]
sources += [actual/'activation/snapshot'/n for n in ['NativeSnapshotRegion.kt','SnapshotWord.kt']]
sources += sorted((suite.parent/'snapshot/transport/doubles').glob('*.kt'))
sources += [actual/'input/PhoneInputMerger.kt',actual/'input/FunctionWork.kt',actual/'input/FunctionIntentRoute.kt']
sources += [root/'protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol'/n for n in ['Wire.kt', 'ActivationControl.kt', 'ReceiverUsability.kt','PhoneInput.kt','Touch.kt','LayoutScale.kt','FunctionIntent.kt','AnchorMessage.kt','BackHomeGestureRecognizer.kt']]
if a.registration_only:
    import importlib.util,re
    ps=importlib.util.spec_from_file_location('activity_source_blocks',root/'directhost/src/test/hid/run.py')
    parser=importlib.util.module_from_spec(ps);ps.loader.exec_module(parser)
    activity=(root/'directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg/ReceiverActivity.kt').read_text()
    fixture=(root/'directrg/src/test/registration-pipeline/ActivityBoundary.kt').read_text()
    names=['currentRegistrationIntent','anchorMessage','chooseAnchorRegistration','cancelAnchorRegistration','cancelInput','cancelInputState','updatePointerBounds','publishHeadPointer','contextualBack','remoteBack','remoteTap','remoteScroll']
    methods='\n'.join(parser.block(activity,rf'^    (?:private|internal) fun {n}\(') for n in names)
    predicates='\n'.join(re.search(rf'^    private fun {n}\(.*$',activity,re.M).group() for n in ['registrationActive','localInputVisible','projectedInput','functionContext'])
    anchor=parser.block(activity,r'^        anchorRegistration = AnchorRegistrationController ')
    completed=re.search(r'^        completedInput = CompletedReceiverInput\(.*?::completedNavigation\)',activity,re.M|re.S).group()
    generated=out/'RegistrationActivityWiring.kt'
    generated.write_text(fixture.replace('// ACTUAL_METHODS',methods).replace('// ACTUAL_PREDICATES',predicates).replace('// ACTUAL_ANCHOR_INITIALIZER',anchor).replace('// ACTUAL_COMPLETED_INITIALIZER',completed))
    sources += [generated,root/'directrg/src/test/registration-pipeline/ReceiverRig.kt',suite/'AnchorRegistrationPipelineScenarios.kt']
    sources += sorted((actual/'anchor/registration').glob('*.kt'))
paired=None
if a.paired116_manifest:
    if not a.stamped_nav_only:p.error('Paired116 inputs are allocated only for --stamped-nav-only')
    paired=json.loads(a.paired116_manifest.read_text())
    for item in paired['inputs']:
        copied=pathlib.Path(item['snapshot']);data=copied.read_bytes()
        if len(data)!=item['bytes'] or hashlib.sha256(data).hexdigest()!=item['sha256']:p.error('Paired immutable input hash mismatch')
        if item['path'].endswith('/input/PhoneInputMerger.kt'):
            sources=[copied if source==actual/'input/PhoneInputMerger.kt' else source for source in sources]
        else:
            target=root/item['path']
            if target in sources:sources=[copied if source==target else source for source in sources]
            else:sources.append(copied)
jar=out/'session-launch-composition.jar'
cmd=[java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-jvm-target','17','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(jar)]+list(map(str,sources))
cmd.insert(4,'-Xfriend-paths='+str(host_classes)+((','+str(rg_classes)) if a.registration_only else ''))
snapshots=[]
for index,source in enumerate(sources):
    snapshot=out/'compiled-sources'/str(index)/source.name;snapshot.parent.mkdir(parents=True,exist_ok=True);snapshot.write_bytes(source.read_bytes());snapshots.append(snapshot)
cmd[-len(sources):]=list(map(str,snapshots))
run=[java,'-cp',str(jar)+os.pathsep+cp,'com.code2hack.eyeprojector.directhost.'+('AnchorRegistrationPipelineScenariosKt' if a.registration_only else 'AnchorSessionScenariosKt' if a.anchor_only else 'StampedNavigationContextScenariosKt' if a.stamped_nav_only else 'NonspatialContextScenariosKt' if a.nav_context_only else 'UiTargetSessionScenariosKt' if a.ui_targets_only else 'FunctionReviewScenariosKt' if a.function_review_only else 'FunctionIntentSessionScenariosKt' if a.function_intents_only else 'NavigationScenariosKt' if a.navigation_only else 'StopRaceScenariosKt' if a.stop_race_only else 'FunctionFailureScenariosKt' if a.function_failure_only else 'ScenariosKt')]
if a.stamped_nav_only:run.append('merged' if a.merged116 else 'paired' if paired else 'baseline')
record={'compileArgv':cmd,'runArgv':run,'sources':[{'path':str(s),'snapshot':str(copy),'sha256':hashlib.sha256(copy.read_bytes()).hexdigest()}for s,copy in zip(sources,snapshots)],'scope':'Actual Shizuku service/routes/Bridge/Session/launch/release with external API doubles; no device or Binder proof'}
record['registrationPipeline']=a.registration_only
if a.registration_only:
    record['scope']='Actual Function grammar and Glasses production Session/owner; full source-extracted Activity handlers/controller; actual Phone Session, Binder-value adapter, reflected provider, FIFO/private store/reader/resolver and codecs. Android Binder/TLS/renderer/accessibility connection/widgets are explicit host boundaries, no device or app-effect proof.'
    record['classpathPrecedence']='New composition jar actual Phone/provider sources and named host API doubles precedes frozen candidate Glasses main/test classes and frozen protocol/Phone/SDK dependencies.'
record['merged116']=a.merged116
record['paired116']=None if paired is None else {'label':'PAIRED117+116 SOURCE JOIN','manifestPath':str(a.paired116_manifest),'manifestSha256':hashlib.sha256(a.paired116_manifest.read_bytes()).hexdigest(),'origin':paired,'productionImport':False,'classpathPrecedence':'New composition jar (117 actual Session/authority plus exact116 source overrides) precedes SDK-compiled117 baseline class directories and protocol classes','standalone117Limitation':'Production Merger remains accepted120 baseline; positive no-point dispatch is this paired test join only; final116 merged-pair recheck required'}
record['dependencyTrees']=[]
for directory in [frozen/'protocol' if frozen else root/'protocol/build/tmp/kotlin-classes/debug',host_classes,
    frozen/'host-aidl' if frozen else root/'directhost/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes']:
    entries=[{'path':str(f.relative_to(directory)),'sha256':hashlib.sha256(f.read_bytes()).hexdigest()}
        for f in sorted(directory.rglob('*.class'))]
    record['dependencyTrees'].append({'path':str(directory),'classes':entries})
api_jar=frozen/'android.jar' if frozen else sdk/'platforms/android-35/android.jar'
record['androidApiJar']={'path':str(api_jar),'sha256':hashlib.sha256(api_jar.read_bytes()).hexdigest()}
record['frozenDependencies']=None if frozen is None else {'path':str(frozen/'manifest.json'),'sha256':hashlib.sha256((frozen/'manifest.json').read_bytes()).hexdigest()}
record['runnerSha256']=hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest()
(out/'inputs.json').write_text(json.dumps(record,indent=2)+'\n')
with (out/'compile.log').open('w') as log:r=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=120)
record['compileExit']=r.returncode
if r.returncode:
    (out/'result.json').write_text(json.dumps(record,indent=2)+'\n');print((out/'compile.log').read_text());raise SystemExit(r.returncode)
try:
    with (out/'scenarios.log').open('w') as log:r=subprocess.run(run,stdout=log,stderr=subprocess.STDOUT,timeout=60)
except subprocess.TimeoutExpired:
    import types
    r=types.SimpleNamespace(returncode=124)
    record['timedOut']=True
record.update({'exit':r.returncode,'logSha256':hashlib.sha256((out/'scenarios.log').read_bytes()).hexdigest()})
(out/'result.json').write_text(json.dumps(record,indent=2)+'\n')
print('\n'.join(line for line in (out/'scenarios.log').read_text().splitlines() if line.startswith('PASS')) if (a.anchor_only or a.registration_only) and not r.returncode else (out/'scenarios.log').read_text());raise SystemExit(r.returncode)
