#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Execute the actual leaf machine code with authored roots, never a Dart VM."""
import argparse
import json
from pathlib import Path
import random
import struct
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE, UC_HOOK_MEM_WRITE
import unicorn.arm64_const as ar

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--elf', type=Path, required=True)
p.add_argument('--layout', type=Path, required=True)
p.add_argument('--report', type=Path, required=True)
a = p.parse_args()
assert __debug__, 'assertions must be enabled'
layout = json.loads(a.layout.read_text())
with a.elf.open('rb') as stream:
    elf = ELFFile(stream)
    segments = [(s['p_vaddr'], s['p_memsz'], s.data()) for s in elf.iter_segments() if s['p_type'] == 'PT_LOAD']
    symbols = {s.name: s['st_value'] for s in elf.get_section_by_name('.symtab').iter_symbols()}
    section = elf.get_section_by_name('.text')
    decoded = list(Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN).disasm(section.data(), section['sh_addr']))
assert all(i.mnemonic not in ('bl', 'blr', 'svc', 'hvc', 'smc') for i in decoded)
assert all(i.op_str == 'x0' for i in decoded if i.mnemonic == 'br')

X = [getattr(ar, f'UC_ARM64_REG_X{i}') for i in range(31)]
Q = [getattr(ar, f'UC_ARM64_REG_Q{i}') for i in range(32)]
H, STACK, GDT = 0x7400000000, 0x500000, 0x600000
DSP, CSP = STACK+0x8000, STACK+0x100
NULL, FIRST, SECOND, STATE, BACKING = H+0x81, H+0x1001, H+0x2001, H+0x3001, H+0x4001
STATE_CID, RAW_CALLER, DISPOSE_PC = 5432, 0x40007c, 0x400300
CAP, STRIDE = 8192, 80
results = []


def run(name, kind='dispose', **opts):
    u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    pages = set()
    def mapping(addr, size):
        for page in range(addr & ~4095, (addr+size+4095)&~4095, 4096):
            if page not in pages:
                u.mem_map(page, 4096); pages.add(page)
    for addr, size, data in segments:
        mapping(addr, size); u.mem_write(addr, data)
    for addr, size in [(H, 0x10000), (STACK, 0x10000), (GDT, 0x100000)]:
        mapping(addr, size)
    def w32(addr, n): u.mem_write(addr, struct.pack('<I', n & 0xffffffff))
    def w64(addr, n): u.mem_write(addr, struct.pack('<Q', n & 0xffffffffffffffff))
    def r32(addr): return struct.unpack('<I', u.mem_read(addr, 4))[0]
    def r64(addr): return struct.unpack('<Q', u.mem_read(addr, 8))[0]
    control, records = symbols['kh_image_trace_control'], symbols['kh_image_trace_records']
    for which in ['dispose', 'clone', 'build']:
        w64(symbols[f'kh_image_trace_{which}_original'], symbols['test_original'])
    w32(symbols['kh_image_trace_image_cid'], layout['image_cid'])
    w32(symbols['kh_image_trace_dispose_selector'], layout['dispose_selector'])
    w64(symbols['kh_image_trace_raw_update_return'], RAW_CALLER)
    w32(control, opts.get('enabled', 1)); w32(control+4, opts.get('busy', 0))
    initial_count = opts.get('count', 0)
    w32(control+8, initial_count)
    for image, d in [(FIRST, opts.get('d1', 0)), (SECOND, opts.get('d2', 0))]:
        w32(image-1, (opts.get('cid', layout['image_cid']) << 12) | 0x31c)
        w32(image+7, BACKING)
        w32(image+0x1f, NULL+{0: 0x30, 1: 0x20, 3: 0x40}[d])
    w32(STATE-1, (STATE_CID << 12) | 0x21c)
    first, second = opts.get('first', FIRST), opts.get('second', SECOND)
    w32(STATE+0x13, first); w32(STATE+0x17, second)
    w64(GDT+(STATE_CID+layout['dispose_selector'])*8, DISPOSE_PC)
    randomizer = random.Random(71)
    expected_x = [randomizer.getrandbits(64) for _ in X]
    expected_x[1] = opts.get('input', STATE if kind == 'build' else FIRST)
    expected_x[15], expected_x[21] = DSP, GDT
    expected_x[22], expected_x[26], expected_x[28] = NULL, 0x77770000, H >> 32
    expected_x[30] = opts.get('caller', RAW_CALLER)
    for reg, val in zip(X, expected_x): u.reg_write(reg, val)
    expected_q = [randomizer.getrandbits(128) for _ in Q]
    for reg, val in zip(Q, expected_q): u.reg_write(reg, val)
    nzcv = opts.get('nzcv', 0xa0000000)
    u.reg_write(ar.UC_ARM64_REG_SP, CSP); u.reg_write(ar.UC_ARM64_REG_NZCV, nzcv)
    u.reg_write(ar.UC_ARM64_REG_FPCR, 0x400000); u.reg_write(ar.UC_ARM64_REG_FPSR, 0x10)
    u.mem_write(DSP-128, b'\xa5'*128)
    before_heap = bytes(u.mem_read(H, 0x10000))
    before_gdt = bytes(u.mem_read(GDT, 0x100000))
    if opts.get('retired'):
        u.mem_unmap(H, 0x10000); u.mem_unmap(GDT, 0x100000)
    instructions, writes, injected = [], [], False
    asm = {i.address: i for i in decoded}
    def on_code(uc, pc, size, _):
        nonlocal injected
        instructions.append(pc)
        assert len(instructions) <= 250, name
        if pc == symbols['test_original']:
            uc.emu_stop(); return
        ins = asm[pc]
        if opts.get('exclusive_failure') and ins.mnemonic == 'stxr' and not injected:
            # Explicit failed-exclusive path model; this is not a contention/ARM
            # memory-model proof. Skip its store and supply architectural status.
            uc.reg_write(ar.UC_ARM64_REG_W9, 1); uc.reg_write(ar.UC_ARM64_REG_PC, pc+4)
            injected = True
        if opts.get('disarm_race') and ins.mnemonic == 'ldar' and ins.op_str == 'w9, [x14]' and r32(control+4):
            w32(control, 0); injected = True
    def on_write(uc, access, addr, size, value, _):
        writes.append((addr, size))
        assert (DSP-80 <= addr and addr+size <= DSP) or \
               (control <= addr and addr+size <= control+16) or \
               (records+initial_count*STRIDE <= addr and addr+size <= records+(initial_count+1)*STRIDE), (name, hex(addr), size)
        if addr == control+8:
            assert value == initial_count+1
            candidate = struct.unpack('<10Q', bytes(uc.mem_read(records+initial_count*STRIDE, STRIDE)))
            assert candidate[0] == {'dispose': 1, 'clone': 2, 'build': 3}[kind]
            assert candidate[1] == expected_x[26] and candidate[2] == expected_x[30]
    u.hook_add(UC_HOOK_CODE, on_code); u.hook_add(UC_HOOK_MEM_WRITE, on_write)
    u.emu_start(symbols[f'kh_image_trace_{kind}_bridge'], 0, count=251)
    assert u.reg_read(ar.UC_ARM64_REG_PC) == symbols['test_original'], name
    for i, (reg, val) in enumerate(zip(X, expected_x)):
        assert u.reg_read(reg) == (symbols['test_original'] if i == 0 else val), (name, f'x{i}')
    assert [u.reg_read(reg) for reg in Q] == expected_q
    assert u.reg_read(ar.UC_ARM64_REG_NZCV) == nzcv
    assert u.reg_read(ar.UC_ARM64_REG_SP) == CSP
    assert u.reg_read(ar.UC_ARM64_REG_FPCR) == 0x400000 and u.reg_read(ar.UC_ARM64_REG_FPSR) == 0x10
    assert bytes(u.mem_read(DSP-128, 48)) == b'\xa5'*48
    if not opts.get('retired'):
        assert bytes(u.mem_read(H, 0x10000)) == before_heap
        assert bytes(u.mem_read(GDT, 0x100000)) == before_gdt
    recorded = opts.get('recorded', True)
    assert r32(control+8) == initial_count+int(recorded), name
    assert r32(control+4) == opts.get('busy', 0), name
    assert r32(control+12) == int(bool(opts.get('busy') or opts.get('exclusive_failure'))), name
    if opts.get('disarm_race') or opts.get('exclusive_failure'): assert injected
    if recorded:
        values = struct.unpack('<10Q', bytes(u.mem_read(records+initial_count*STRIDE, STRIDE)))
        if kind == 'build':
            assert values[3] == expected_x[1]
            assert values[4] == (0 if first == NULL else first) and values[5] == (0 if second == NULL else second)
            assert values[6] == (2 if first == NULL else opts.get('expected_d1', opts.get('d1', 0)))
            assert values[7] == (2 if second == NULL else opts.get('expected_d2', opts.get('d2', 0)))
            assert values[8] == DISPOSE_PC
        else:
            assert values[3] == 0 and values[5] == 0 and values[7] == 2 and values[8] == 0
            assert values[4] == (0 if expected_x[1] == NULL else expected_x[1])
            assert values[6] == opts.get('expected_d1', opts.get('d1', 0))
        if values[6] in (0, 1): assert values[9] == BACKING
        else: assert values[9] == 0
    if not recorded and initial_count < CAP:
        # Filtered clone attempts may write the unpublished next slot. Readers
        # cannot see it until count is released; disabled/full/busy never do.
        if kind != 'clone' or opts.get('enabled', 1) == 0 or opts.get('busy'):
            assert not any(records <= addr < records+CAP*STRIDE for addr, _ in writes)
    results.append({'case': name, 'instructions': len(instructions), 'recorded': recorded})


for kind in ['dispose', 'clone', 'build']:
    run(f'{kind}-disabled-retired-roots', kind, enabled=0, retired=True, recorded=False)
    run(f'{kind}-buffer-full', kind, count=CAP, recorded=False)
    run(f'{kind}-busy-no-spin', kind, busy=1, recorded=False)
    run(f'{kind}-exclusive-failed-no-retry', kind, exclusive_failure=True, recorded=False)
    run(f'{kind}-disarmed-after-admission', kind, disarm_race=True, recorded=False)
    run(f'{kind}-last-slot', kind, count=CAP-1)
run('dispose-live')
run('dispose-already-disposed', d1=1)
run('dispose-null', input=NULL, expected_d1=2)
run('dispose-smi', input=42, expected_d1=3)
run('dispose-wrong-cid', cid=1, expected_d1=3)
run('dispose-invalid-flag', d1=3)
run('clone-rawimage-live', 'clone')
run('clone-rawimage-disposed', 'clone', d1=1)
run('clone-other-live-filtered', 'clone', caller=0x400080, recorded=False)
run('clone-other-disposed-kept', 'clone', caller=0x400080, d1=1)
run('build-two-live-images', 'build')
run('build-two-disposed-images', 'build', d1=1, d2=1)
run('build-first-absent', 'build', first=NULL)
run('build-second-absent', 'build', second=NULL)
run('build-both-absent', 'build', first=NULL, second=NULL)
run('build-wrong-image-class', 'build', cid=1, expected_d1=3, expected_d2=3)
for flags in range(16): run(f'nzcv-{flags:x}', 'build', nzcv=flags << 28)
a.report.write_text(json.dumps({'cases': results, 'count': len(results), 'leaf_instructions': len(decoded),
    'limitation': 'Synthetic roots and explicit race injection; not Dart GC, a hook backend, or launcher acceptance.'}, indent=2)+'\n')
print(f'{len(results)} AArch64 leaf cases; registers, scalar publication, bounds, no heap writes checked')
