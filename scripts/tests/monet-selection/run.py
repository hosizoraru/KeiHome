#!/usr/bin/env python3
"""Host tests only. Never invokes adb, a device, a launcher or a network tool."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shutil
import struct
import subprocess
import sys
import tempfile
sys.dont_write_bytecode = True
from elf_reader import Elf

HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
CPP=REPO/'app/src/main/cpp'

def run(args, log):
    r=subprocess.run([str(x) for x in args],capture_output=True,text=True)
    log.write_text(r.stdout+r.stderr)
    if r.returncode:
        raise RuntimeError(f'command failed ({r.returncode}); see {log}:\n{r.stdout[-3000:]}{r.stderr[-3000:]}')
    return r.stdout

def ndk_bin():
    requested=os.environ.get('NDK_DIR')
    if requested:
        root=Path(requested)
    else:
        sdk=Path(os.environ.get('ANDROID_SDK_ROOT',str(Path.home()/'Library/Android/sdk')))
        root=sdk/'ndk/30.0.16248370'
    candidates=list((root/'toolchains/llvm/prebuilt').glob('*/bin'))
    if len(candidates)!=1 or not (candidates[0]/'clang++').is_file():
        raise RuntimeError('Set NDK_DIR to an installed Android NDK; no toolchain is downloaded')
    return candidates[0]

def macho_text(path):
    data=path.read_bytes();h=struct.unpack_from('<8I',data)
    assert h[0]==0xfeedfacf
    pos=32
    for _ in range(h[4]):
        cmd,size=struct.unpack_from('<II',data,pos)
        if cmd==0x19:
            count=struct.unpack_from('<I',data,pos+64)[0]
            for j in range(count):
                s=pos+72+80*j
                if data[s:s+16].split(b'\0')[0]==b'__text':
                    length=struct.unpack_from('<Q',data,s+40)[0]
                    offset=struct.unpack_from('<I',data,s+48)[0]
                    return data[offset:offset+length]
        pos+=size
    raise ValueError('Mach-O __text missing')

def apple_assembly(preprocessed):
    result=[]
    for line in preprocessed.splitlines():
        stripped=line.strip()
        if stripped.startswith('.section .note'):break
        if stripped.startswith(('.hidden ','.type ','.size ')):continue
        line=re.sub(r'\bkh_[A-Za-z0-9_]+\b',lambda m:'_'+m.group(0),line)
        line=re.sub(r':lo12:([\\\w]+)',r'\1@PAGEOFF',line)
        line=re.sub(r'(\badrp\s+x\d+,\s*)([\\\w]+)',r'\1\2@PAGE',line)
        result.append(line)
    return '\n'.join(result)+'\n'

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--libapp',type=Path,default=os.environ.get('LIBAPP_FIXTURE'),
                   help='optional external libapp.so; never copied into the repository')
    p.add_argument('--fixture',nargs=2,action='append',default=[],metavar=('LIBAPP','DEBUG_ELF'),
                   help='repeat for independent symbol/Capstone ABI checks on multiple real images')
    p.add_argument('--output-dir',type=Path)
    a=p.parse_args()
    out=(a.output_dir or Path(tempfile.mkdtemp(prefix='keihome-monet-selection-'))).resolve()
    out.mkdir(parents=True,exist_ok=True)
    report={'host_only':True,'device_access':False,'output_dir':str(out),'checks':{}}
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    try:
        tool=ndk_bin();cxx=tool/'clang++';cc=tool/'clang'
        flags=['--target=aarch64-linux-android26','-Wall','-Wextra','-Werror']
        run([cxx,*flags,'-std=c++17','-fno-exceptions','-fno-rtti','-fvisibility=hidden',
             '-mbranch-protection=standard','-c',CPP/'selection_profile.cpp','-o',out/'profile.o'],out/'ndk-profile.log')
        run([cc,*flags,'-c',CPP/'monet_selection_bridge.S','-o',out/'bridge.o'],out/'ndk-bridge.log')
        run([cc,*flags,'-c',HERE/'bridge_test_globals.S','-o',out/'globals.o'],out/'ndk-globals.log')
        run([tool/'ld.lld','--no-relax','--section-start=.text=0x2000000','--section-start=.data=0x2010000',
             '-e','kh_monet_selection_bridge',out/'bridge.o',out/'globals.o','-o',out/'selection_bridge_test.elf'],out/'ndk-link.log')
        report['checks']['ndk_compile']='passed: Werror, C++ and actual assembly'
        elf=Elf((out/'selection_bridge_test.elf').read_bytes())
        sym={n:(v,s) for v,s,n,_,_ in elf.symbols()}
        addr,size=sym['kh_monet_selection_bridge'];code=elf.read(addr,size)
        words=[w for w, in struct.iter_unpack('<I',code)]
        assert all((w&0xfc000000)!=0x94000000 and (w&0xfffffc1f)!=0xd63f0000 for w in words),'call instruction present'
        assert all((w&0xff000000)!=0xd4000000 for w in words),'exception/syscall present'
        assert words.count(0xd61f0200)==1 and words.count(0xd65f03c0)==1
        assert words[-1]==0xd61f0200,'original tail is not BR x16'
        report['checks']['actual_machine_code']={'passed':True,'instructions':len(words),
            'sha256':hashlib.sha256(code).hexdigest(),'BL_BLR_SVC_count':0,'original_tail':'BR x16'}
        if importlib.util.find_spec('capstone'):
            run([sys.executable,HERE/'test_bridge_paths.py',out],out/'bridge-model.log')
            report['checks']['instruction_path_model']=json.loads((out/'bridge-path-tests.json').read_text())
        else:
            report['checks']['instruction_path_model']='SKIPPED: optional capstone unavailable; select PYTHON containing capstone to enable'
        host_cxx=os.environ.get('CXX') or shutil.which('clang++')
        if not host_cxx:raise RuntimeError('host C++ compiler missing; set CXX')
        host_flags=['-std=c++17','-O2','-Wall','-Wextra','-Werror','-I',CPP]
        fixtures=([(a.libapp,None)] if a.libapp else [])+[(Path(lib),Path(debug)) for lib,debug in a.fixture]
        if fixtures:
            run([host_cxx,*host_flags,HERE/'test_profile.cpp',CPP/'selection_profile.cpp',CPP/'monet_image_identity.cpp',
                 '-o',out/'test_profile'],out/'host-profile-build.log')
            report['checks']['profile_fixtures']=[]
            for index,(lib,debug) in enumerate(fixtures):
                fixture=lib.resolve()
                log=out/f'native-profile-tests-{index}.txt'
                result=run([out/'test_profile',fixture],log)
                actual=json.loads(next(line.removeprefix('PROFILE ') for line in result.splitlines() if line.startswith('PROFILE ')))
                entry={'passed':True,'path':str(fixture),'profile':actual,
                    'sha256':hashlib.sha256(fixture.read_bytes()).hexdigest(),
                    'timing':json.loads(next(line.removeprefix('TIMING ') for line in result.splitlines() if line.startswith('TIMING '))),
                    'result':result.splitlines()[-1]}
                if debug and importlib.util.find_spec('capstone'):
                    from audit_fixture import audit
                    evidence=audit(fixture,debug.resolve())
                    for role,value in actual.items():
                        expected=evidence[role] if role.endswith('_pool') else evidence['roles'][role]['rva']
                        assert value==expected,(role,hex(value),hex(expected))
                    audit_path=out/f'abi-audit-{index}.json'
                    audit_path.write_text(json.dumps(evidence,indent=2)+'\n')
                    entry['independent_symbol_capstone_audit']=str(audit_path)
                else:
                    entry['independent_symbol_capstone_audit']='SKIPPED: supply --fixture LIBAPP DEBUG_ELF with a Capstone interpreter'
                report['checks']['profile_fixtures'].append(entry)
        else:
            report['checks']['profile_fixture']='SKIPPED: no external --libapp / LIBAPP_FIXTURE supplied'
        if platform.machine().lower() in ('arm64','aarch64'):
            source=CPP/'monet_selection_bridge.S'
            if sys.platform=='darwin':
                pre=run([cc,'--target=aarch64-linux-android26','-E','-P','-x','assembler-with-cpp',source],out/'preprocessed-bridge.S')
                source=out/'bridge.macho.S';source.write_text(apple_assembly(pre))
            run([host_cxx,'-c',source,'-o',out/'bridge.native.o'],out/'native-bridge-build.log')
            expected=Elf((out/'bridge.o').read_bytes()).section('.text')
            actual=macho_text(out/'bridge.native.o') if sys.platform=='darwin' else Elf((out/'bridge.native.o').read_bytes()).section('.text')
            assert actual==expected,'host bridge instruction bytes differ from Android bridge before relocation'
            report['checks']['host_android_code_identity']='passed: every unrelocated text byte identical'
            run([host_cxx,*host_flags,HERE/'test_native_bridge.cpp',HERE/'native_call.S',out/'bridge.native.o',
                 '-o',out/'test_native_bridge'],out/'host-native-case-build.log')
            run([out/'test_native_bridge'],out/'native-bridge-tests.txt')
            report['checks']['native_bridge']={'passed':True,'result':(out/'native-bridge-tests.txt').read_text().splitlines()[-1],
                'limits':'synthetic tagged heap; old roots PROT_NONE between calls; actual Dart VM/GC not executed'}
        else:
            report['checks']['native_bridge']='SKIPPED: actual assembly execution requires an ARM64 host; no device/emulator launched'
        report['passed']=True
    except Exception as ex:
        report['passed']=False;report['error']=str(ex)
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'passed':report['passed'],'report':str(out/'report.json'),
                      'error':report.get('error')},indent=2))
    return 0 if report['passed'] else 1

if __name__=='__main__':sys.exit(main())
