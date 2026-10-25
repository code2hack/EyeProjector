#!/usr/bin/env python3
"""After a reserved build: retain every ABI's exact ELF, lowering, dependencies and APK offsets."""
import argparse,hashlib,json,shutil,struct,subprocess,zipfile
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--source-root',type=Path,required=True);p.add_argument('--ndk',type=Path,required=True)
p.add_argument('--output-dir',type=Path,required=True);p.add_argument('--source-set-sha256',required=True)
a=p.parse_args();root=a.source_root.resolve();out=a.output_dir.resolve();out.mkdir(parents=True,exist_ok=False)
abis=['armeabi-v7a','arm64-v8a','x86','x86_64'];tool=a.ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest()
result={'sourceSetSha256':a.source_set_sha256,'sourceRoot':str(root),'ndkMetadataSha256':sha(a.ndk/'source.properties'),'abis':{},'apk':{},'scope':'Artifact retention and static inspection only; no installed-process/RO mapping/physical proof. Every acquire_cell lowering/helper path requires explicit review; no PASS inferred from absence of libatomic.'}
try:
 for abi in abis:
  candidates=[]
  for directory in [root/'directhost/build/intermediates/cxx',root/'directhost/.cxx']:
   candidates += [f for f in directory.rglob('libeyeprojector_snapshot.so') if abi in f.parts]
  unique={sha(f) for f in candidates};assert len(unique)==1,(abi,'Cannot bind a unique exact unstripped build artifact',list(map(str,candidates)))
  source=sorted(candidates)[0];folder=out/abi;folder.mkdir()
  elf=folder/source.name;shutil.copyfile(source,elf)
  records={'source':str(source),'sha256':sha(elf),'candidates':[{'path':str(f),'sha256':sha(f)} for f in sorted(candidates)],'commands':[]}
  commands=[('readelf',['llvm-readelf','-h','-l','-d','-s','-r','-W']),('objdump',['llvm-objdump','-d','--no-show-raw-insn']),('nm',['llvm-nm','-a'])]
  for label,argv in commands:
   command=[str(tool/argv[0])]+argv[1:]+[str(elf)]
   with (folder/(label+'.log')).open('w') as log:r=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=30)
   records['commands'].append({'argv':command,'exit':r.returncode,'logSha256':sha(folder/(label+'.log'))});assert r.returncode==0
  result['abis'][abi]=records
 apk=root/'directhost/build/outputs/apk/debug/directhost-debug.apk'
 with zipfile.ZipFile(apk) as archive,apk.open('rb') as raw:
  entries=[]
  for entry in archive.infolist():
   if entry.filename.endswith('/libeyeprojector_snapshot.so'):
    raw.seek(entry.header_offset);header=raw.read(30)
    name,extra=struct.unpack_from('<HH',header,26)
    offset=entry.header_offset+30+name+extra
    entries.append({'entry':entry.filename,'compression':entry.compress_type,'dataOffset':offset,'aligned16KiB':offset%16384==0,'sha256':hashlib.sha256(archive.read(entry)).hexdigest()})
  assert sorted(e['entry'].split('/')[1] for e in entries)==sorted(abis)
  result['apk']={'path':str(apk),'sha256':sha(apk),'entries':entries}
finally:(out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'retainedAbis':list(result['abis']),'apk':result['apk'],'loweringReview':'REQUIRED; not a source-host verdict'},indent=2))
