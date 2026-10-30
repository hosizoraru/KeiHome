#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Host-only diagnostic validation. Never build/install an APK or contact a device."""
import argparse
import hashlib
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
sys.path.insert(0, str(HERE.parent/'monet-tint'))
from validate import ndk_toolchain
sys.path.insert(0, str(HERE))
from generate_contracts import Fixture, ROLES


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--library', type=Path, action='append', required=True)
    p.add_argument('--output', type=Path)
    a = p.parse_args()
    if not __debug__: p.error('assertions must be enabled')
    output = a.output.resolve() if a.output else Path(tempfile.mkdtemp(prefix='keihome-image-trace-'))
    output.mkdir(parents=True, exist_ok=True)
    ndk, tc = ndk_toolchain()
    host_cxx = os.environ.get('CXX') or shutil.which('clang++')
    if not host_cxx: p.error('host C++17 compiler missing')
    log = output/'commands.log'
    log.write_text('')
    def run(command):
        command = list(map(str, command))
        result = subprocess.run(command, cwd=REPO, text=True, capture_output=True)
        with log.open('a') as stream:
            stream.write('$ '+shlex.join(command)+'\n'+result.stdout+result.stderr+f'\n[exit {result.returncode}]\n')
        if result.returncode:
            raise RuntimeError(f'{shlex.join(command)}\n{result.stdout}{result.stderr}')
        return result.stdout
    fixtures = [path.resolve() for path in a.library]
    generate = [sys.executable, HERE/'generate_contracts.py', '--output', output/'patterns.h', '--evidence', output/'contracts.json']
    for path in fixtures: generate += ['--library', path]
    run(generate)
    print(run([sys.executable, HERE/'test_report.py']), end='')
    # With both reviewed fixtures, regeneration must exactly reproduce the header.
    if len(fixtures) == 2:
        assert (output/'patterns.h').read_bytes() == (CPP/'monet_image_trace_patterns.h').read_bytes()
    flags = ['-std=c++17', '-O2', '-UNDEBUG', '-Wall', '-Wextra', '-Werror', '-I'+str(CPP)]
    run([host_cxx, *flags, HERE/'test_resolver.cpp', CPP/'monet_image_trace_resolver.cpp',
         CPP/'monet_image_identity.cpp', '-o', output/'resolver'])
    records = []
    for index, path in enumerate(fixtures):
        row = json.loads(run([output/'resolver', path]))
        fixture = Fixture(path)
        for role, name in ROLES:
            key = {'RawUpdate': 'raw_update'}.get(role, role.lower())
            assert row[key] == fixture.role(name)[0], (path, key, row[key], fixture.role(name))
        row['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        row['library'] = str(path)
        (output/f'layout-{index}.json').write_text(json.dumps(row, indent=2)+'\n')
        records.append(row)
    cc = tc/'aarch64-linux-android37-clang'
    cxx = tc/'aarch64-linux-android37-clang++'
    native_flags = [*flags, '-fno-exceptions', '-fno-rtti', '-fno-threadsafe-statics',
        '-fvisibility=hidden', '-fvisibility-inlines-hidden', '-mbranch-protection=standard',
        '-DKH_MONET_IMAGE_TRACE=1']
    for source in ['monet_image_trace.cpp', 'monet_image_trace_resolver.cpp', 'monet_hooks.cpp']:
        run([cxx, *native_flags, '-c', CPP/source, '-o', output/(source+'.o')])
    for source, name in [(CPP/'monet_image_trace_bridge.S', 'bridge'), (HERE/'test_symbols.S', 'symbols')]:
        run([cc, '-I'+str(CPP), '-c', source, '-o', output/(name+'.o')])
    run([tc/'ld.lld', '-m', 'aarch64elf', '-e', 'test_original', '-Ttext=0x100000',
         output/'bridge.o', output/'symbols.o', '-o', output/'bridge.elf'])
    for index in range(len(fixtures)):
        print(run([sys.executable, HERE/'test_bridge.py', '--elf', output/'bridge.elf',
             '--layout', output/f'layout-{index}.json', '--report', output/f'bridge-{index}.json']), end='')
    # Compile the ordinary path too: the default hooks object has no trace refs.
    run([cxx, *flags, '-c', CPP/'monet_hooks.cpp', '-o', output/'hooks-off.o'])
    symbols = run([tc/'llvm-nm', '-u', output/'hooks-off.o'])
    assert 'ImageTrace' not in symbols and 'image_trace' not in symbols
    report = {'result': 'host-pass', 'ndk': str(ndk), 'fixtures': records,
              'source_sha256': {str(path.relative_to(REPO)): hashlib.sha256(path.read_bytes()).hexdigest()
                  for path in sorted([*CPP.glob('monet_image_trace*'), CPP/'monet_hooks.cpp', CPP/'CMakeLists.txt',
                                      *HERE.glob('*.py'), *HERE.glob('*.cpp'), *HERE.glob('*.S'), *HERE.glob('*.gradle')])},
              'limits': 'No APK build, hook installation, device action, managed allocation or Dart GC validation.'}
    (output/'validation.json').write_text(json.dumps(report, indent=2)+'\n')
    print(f'Host validation passed: {output}')


if __name__ == '__main__': main()
