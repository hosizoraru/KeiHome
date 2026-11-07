#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Host resolver, ARM64, worker and complete native builds. Never contacts devices."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
CPP = REPO/'app/src/main/cpp'
spec = importlib.util.spec_from_file_location('tint_validation', HERE.parent/'monet-tint/validate.py')
tint = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tint)
from generate_contracts import Fixture, ROLES


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--library', type=Path, action='append', required=True)
    p.add_argument('--output', type=Path)
    p.add_argument('--verify-generated', action='store_true',
                   help='Require the supplied complete fixture matrix to reproduce shipping contracts')
    a = p.parse_args()
    if not __debug__: p.error('assertions must be enabled')
    output = a.output.resolve() if a.output else Path(tempfile.mkdtemp(prefix='keihome-image-recovery-'))
    output.mkdir(parents=True, exist_ok=True)
    ndk, tc = tint.ndk_toolchain()
    cxx = os.environ.get('CXX') or shutil.which('clang++')
    cmake = shutil.which('cmake')
    if not cmake:
        found = sorted((ndk.parent.parent/'cmake').glob('*/bin/cmake'))
        cmake = str(found[-1]) if found else None
    if not cxx or not cmake: p.error('host C++17 compiler and cmake required')
    log = output/'commands.log'; log.write_text('')
    def run(command):
        command = list(map(str, command))
        result = subprocess.run(command, cwd=REPO, text=True, capture_output=True)
        with log.open('a') as stream:
            stream.write('$ '+shlex.join(command)+'\n'+result.stdout+result.stderr+f'\n[exit {result.returncode}]\n')
        if result.returncode: raise RuntimeError(f'{shlex.join(command)}\n{result.stdout}{result.stderr}')
        return result.stdout
    fixtures = [path.resolve() for path in a.library]
    command = [sys.executable, HERE/'generate_contracts.py', '--output', output/'patterns.h', '--evidence', output/'contracts.json']
    for path in fixtures: command += ['--library', path]
    run(command)
    if a.verify_generated:
        assert (output/'patterns.h').read_bytes() == (CPP/'monet_image_recovery_patterns.h').read_bytes()
    flags = ['-std=c++17', '-O2', '-UNDEBUG', '-Wall', '-Wextra', '-Werror', '-I'+str(CPP)]
    run([cxx, *flags, HERE/'test_resolver.cpp', CPP/'monet_image_recovery_resolver.cpp',
         CPP/'monet_image_identity.cpp', '-o', output/'resolver'])
    rows = []
    for index, path in enumerate(fixtures):
        row = json.loads(run([output/'resolver', path]))
        f = Fixture(path)
        for role, name in ROLES:
            key = {'RawUpdate': 'raw_update', 'RawCreate': 'raw_create', 'RenderSet': 'render_set',
                   'RenderDispose': 'render_dispose', 'GuardedClone': 'guarded_clone'}.get(role, role.lower())
            assert row[key] == f.role(name)[0], (path, key)
        row['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        row['library'] = str(path)
        rows.append(row)
        (output/f'layout-{index}.json').write_text(json.dumps(row, indent=2)+'\n')
        print(f'{path}: {row["positives"]} positive / {row["rejects"]} rejected resolver cases', flush=True)
    for mode in ['off', 'on']:
        defines = ['-DKH_MONET_IMAGE_TRACE=1'] if mode == 'on' else []
        exe = output/f'worker-{mode}'
        run([cxx, *flags, *defines, '-I'+str(HERE/'stubs'), HERE/'test_worker.cpp',
             CPP/'monet_image_recovery.cpp', '-o', exe])
        for case in ['success', 'reject', 'hook-fail']: run([exe, case])
    cc = tc/'aarch64-linux-android37-clang'
    for source, name in [(CPP/'monet_image_recovery_bridge.S', 'bridge'), (HERE/'test_symbols.S', 'symbols')]:
        run([cc, '-I'+str(CPP), '-c', source, '-o', output/(name+'.o')])
    run([tc/'ld.lld', '-m', 'aarch64elf', '-e', 'test_original', '-Ttext=0x100000',
         output/'bridge.o', output/'symbols.o', '-o', output/'bridge.elf'])
    for index, path in enumerate(fixtures):
        print(run([sys.executable, HERE/'test_bridge.py', '--elf', output/'bridge.elf',
             '--library', path, '--layout', output/f'layout-{index}.json', '--report', output/f'bridge-{index}.json']), end='', flush=True)
    native = {}
    for mode in ['OFF', 'ON']:
        directory = output/f'native-{mode.lower()}'
        run([cmake, '-S', CPP, '-B', directory, '-DCMAKE_TOOLCHAIN_FILE='+str(ndk/'build/cmake/android.toolchain.cmake'),
             '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-37', '-DCMAKE_BUILD_TYPE=Release',
             '-DKEIHOME_MONET_IMAGE_TRACE='+mode])
        run([cmake, '--build', directory, '--parallel', '4'])
        library = directory/'libkeihome_hyos_lsp.so'
        symbols = run([tc/'llvm-nm', '-C', library])
        assert 'ReportMonetImageRecovery' in symbols
        assert ('kh_image_recovery_bridge' in symbols) == (mode == 'OFF')
        assert ('kh_image_trace_clone_bridge' in symbols) == (mode == 'ON')
        native[mode] = {'library': str(library), 'sha256': hashlib.sha256(library.read_bytes()).hexdigest()}
        print(f'Complete Android native Release build, trace {mode}: passed', flush=True)
    (output/'validation.json').write_text(json.dumps({'result': 'host-pass', 'ndk': str(ndk),
        'fixtures': rows, 'native_builds': native,
        'source_sha256': {str(path.relative_to(REPO)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted([*CPP.glob('monet_image_recovery*'), CPP/'monet_hooks.cpp', CPP/'monet_policy_server.cpp',
                                CPP/'CMakeLists.txt', *HERE.rglob('*.py'), *HERE.rglob('*.cpp'), *HERE.rglob('*.h'), *HERE.glob('*.S')])},
        'limits': 'No APK build/deployment, device actions, actual hook backend, Dart GC or runtime acceptance.'}, indent=2)+'\n')
    print(f'Host validation passed: {output}', flush=True)


if __name__ == '__main__': main()
