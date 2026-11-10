#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the actual leaf and actual ELF clone/setter instructions, without a VM."""
import argparse
import hashlib
import json
from pathlib import Path
import random
import struct
import sys
sys.dont_write_bytecode = True
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE, UC_HOOK_MEM_WRITE
import unicorn.arm64_const as ar
from generate_contracts import Fixture

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--elf', type=Path, required=True)
p.add_argument('--library', type=Path, required=True)
p.add_argument('--layout', type=Path, required=True)
p.add_argument('--report', type=Path, required=True)
a = p.parse_args()
assert __debug__
layout = json.loads(a.layout.read_text())
fixture = Fixture(a.library)
with a.elf.open('rb') as stream:
    elf = ELFFile(stream)
    segments = [(s['p_vaddr'], s['p_memsz'], s.data()) for s in elf.iter_segments() if s['p_type'] == 'PT_LOAD']
    symbols = {s.name: s['st_value'] for s in elf.get_section_by_name('.symtab').iter_symbols()}
    symbol_sizes = {s.name: s['st_size'] for s in elf.get_section_by_name('.symtab').iter_symbols()}
    code = elf.get_section_by_name('.text')
    start, size = symbols['kh_image_recovery_bridge'], symbol_sizes['kh_image_recovery_bridge']
    offset = start-code['sh_addr']
    assert size > 0 and offset >= 0 and offset+size <= code.data_size
    decoded = list(Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN).disasm(code.data()[offset:offset+size], start))
    assert len(decoded)*4 == size
assert all(i.mnemonic not in ('bl', 'blr', 'svc', 'hvc', 'smc') for i in decoded)
assert all(i.op_str == 'x0' for i in decoded if i.mnemonic == 'br')
for i in decoded:
    if i.mnemonic.startswith('b.') or i.mnemonic in ('b', 'cbz', 'cbnz', 'tbz', 'tbnz'):
        assert int(i.op_str.split('#')[-1], 16) > i.address, ('unbounded back edge', i)
asm = {i.address: i for i in decoded}
X = [getattr(ar, f'UC_ARM64_REG_X{i}') for i in range(31)]
Q = [getattr(ar, f'UC_ARM64_REG_Q{i}') for i in range(32)]
H, STACK = 0x7400000000, 0x500000
FP, CSP = STACK+0x8000, STACK+0x100
DSP = FP-layout['frame_size']
NULL, REQUEST, OWNER, RENDER, BACKING, NEW = [H+n for n in (0x81, 0x1001, 0x2001, 0x3001, 0x4001, 0x5001)]
results = []


def run(name, repair=False, stock=False, **o):
    u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    pages = set()
    def mapping(addr, size):
        for page in range(addr & ~4095, (addr+size+4095)&~4095, 4096):
            if page not in pages: u.mem_map(page, 4096); pages.add(page)
    for addr, size, data in segments:
        mapping(addr, size); u.mem_write(addr, data)
    mapping(H, 0x10000); mapping(STACK, 0x10000)
    def w32(addr, n): u.mem_write(addr, struct.pack('<I', n & 0xffffffff))
    def w64(addr, n): u.mem_write(addr, struct.pack('<Q', n & 0xffffffffffffffff))
    def r32(addr): return struct.unpack('<I', u.mem_read(addr, 4))[0]
    def r64(addr): return struct.unpack('<Q', u.mem_read(addr, 8))[0]
    cfg, counts = symbols['kh_image_recovery_config'], symbols['kh_image_recovery_counts']
    u.mem_write(cfg, struct.pack('<QIIiIIIII', layout['update_return'], layout['image_cid'],
        layout['render_cid'], layout['receiver_spill'], layout['render_image'], layout['disposed'],
        layout['backing'], layout['frame_size'], o.get('enabled', 1)))
    original = layout['clone'] if stock else symbols.get('test_original', 0x600000)
    if not stock:
        # The shipping SO has no authored test tail. Stop at a mapped synthetic
        # tail boundary, exactly as at test_original in the standalone harness.
        mapping(original, 4)
        u.mem_write(original, b'\xc0\x03\x5f\xd6')
    w64(symbols['kh_image_recovery_original'], original)
    w32(counts, o.get('count', 0)); w32(counts+4, o.get('uncounted', 0))
    for ptr, prefix, disposed in [(REQUEST, 'request', NULL+0x20), (OWNER, 'owner', NULL+0x30)]:
        w32(ptr-1, (o.get(prefix+'_cid', layout['image_cid'])<<12)|0x31c)
        w32(ptr+layout['disposed'], o.get(prefix+'_flag', disposed))
        w32(ptr+layout['backing'], o.get(prefix+'_backing', BACKING))
        w64(ptr+15, 96); w64(ptr+23, 96)
    w32(RENDER-1, (o.get('render_cid', layout['render_cid'])<<12)|0xe1c)
    w32(RENDER+layout['render_image'], o.get('owner', OWNER))
    u.mem_write(DSP-128, b'\xa5'*128)
    w64(FP+layout['receiver_spill'], o.get('render', RENDER))
    # Deliberately tempting decoys: neither x3 nor a newly made bridge frame owns
    # the receiver. A wrong-frame implementation must fail the negative tests.
    w64(DSP-16+layout['receiver_spill'], RENDER)
    rng = random.Random(8319)
    initial = [rng.getrandbits(64) for _ in X]
    initial[1] = o.get('request', REQUEST)
    initial[3] = RENDER
    initial[15], initial[22], initial[28] = DSP, NULL, (0xa5<<32)|(H>>32)
    initial[29], initial[30] = o.get('fp', FP), o.get('caller', layout['update_return'])
    for reg, val in zip(X, initial): u.reg_write(reg, val)
    vectors = [rng.getrandbits(128) for _ in Q]
    for reg, val in zip(Q, vectors): u.reg_write(reg, val)
    nzcv = o.get('nzcv', 0xa0000000)
    for reg, val in [(ar.UC_ARM64_REG_SP, CSP), (ar.UC_ARM64_REG_NZCV, nzcv),
                     (ar.UC_ARM64_REG_FPCR, 0x400000), (ar.UC_ARM64_REG_FPSR, 0x10)]: u.reg_write(reg, val)
    before_heap = bytes(u.mem_read(H, 0x10000))
    before_frame = bytes(u.mem_read(DSP, 256))
    before_config = bytes(u.mem_read(cfg, 40))
    if o.get('unmap_heap'): u.mem_unmap(H, 0x10000)
    if o.get('poison_spill'): w64(FP+layout['receiver_spill'], H+0x90000001)
    writes, steps = [], []
    def on_write(uc, access, addr, size, value, data): writes.append((addr, size))
    u.hook_add(UC_HOOK_MEM_WRITE, on_write)
    def on_code(uc, addr, size, data):
        steps.append(addr)
        if addr == original: uc.emu_stop(); return
        if o.get('exclusive_fail') and asm[addr].mnemonic == 'stlxr':
            # Replace the result of this single attempt, as with a lost monitor.
            uc.reg_write(ar.UC_ARM64_REG_W11, 1)
            uc.reg_write(ar.UC_ARM64_REG_PC, addr+4)
    hook = u.hook_add(UC_HOOK_CODE, on_code)
    if stock:
        # Map only the actual reviewed bodies; allocator and set bookkeeping are
        # explicit mock boundaries below. No proprietary code is stored in repo.
        for symbol in ['Image.clone', 'RenderImage.image=']:
            addr, size = fixture.role(symbol)
            mapping(addr, size); u.mem_write(addr, fixture.read(addr, size))
    u.emu_start(symbols['kh_image_recovery_bridge'], 0, count=500)
    assert u.reg_read(ar.UC_ARM64_REG_PC) == original, (name, 'tail not reached')
    assert len(steps) < 150
    expected = list(initial)
    expected[0], expected[1] = original, OWNER if repair else initial[1]
    assert [u.reg_read(x) for x in X] == expected, (name, 'GPR corruption')
    assert [u.reg_read(q) for q in Q] == vectors, (name, 'SIMD corruption')
    for reg, val in [(ar.UC_ARM64_REG_SP, CSP), (ar.UC_ARM64_REG_NZCV, nzcv),
                     (ar.UC_ARM64_REG_FPCR, 0x400000), (ar.UC_ARM64_REG_FPSR, 0x10)]:
        assert u.reg_read(reg) == val, (name, reg)
    assert bytes(u.mem_read(cfg, 40)) == before_config
    if not o.get('poison_spill'): assert bytes(u.mem_read(DSP, 256)) == before_frame
    if not o.get('unmap_heap'): assert bytes(u.mem_read(H, 0x10000)) == before_heap
    assert all(DSP-48 <= addr and addr+size <= DSP or counts <= addr and addr+size <= counts+8
               for addr, size in writes), (name, 'write outside scratch/counters', writes)
    count = o.get('count', 0)
    counted = repair and count < 65535 and not o.get('exclusive_fail')
    assert r32(counts) == count+int(counted), name
    assert r32(counts+4) == int(bool(o.get('uncounted') or repair and count < 65535 and o.get('exclusive_fail'))), name

    outcome = 'redirected' if repair else 'unchanged'
    if stock:
        u.hook_del(hook)
        clone, setter = layout['clone'], layout['render_set']
        # All targets are decoded from actual calls. Calls to allocator/handle
        # registration are stubbed, while frame construction and field reads/
        # writes, flag branches and setter's ownership branch execute verbatim.
        def target(pc):
            w = struct.unpack('<I', fixture.read(pc, 4))[0]
            return pc+(((w&0x3ffffff)^0x2000000)-0x2000000)*4
        calls = {target(clone+0x34): 'allocate', target(clone+0x6c): 'register',
                 target(clone+0x80): 'throw', layout['dispose']: 'dispose'}
        for addr in calls: mapping(addr, 4)
        mapping(layout['update_return'], 4)
        actions = []
        def stock_code(uc, addr, size, data):
            if addr == clone+0x80:
                actions.append('throw'); uc.emu_stop(); return
            kind = calls.get(addr)
            if kind == 'allocate':
                assert uc.reg_read(X[29]) == DSP-16
                assert r64(DSP-16) == FP, 'bridge frame leaked across allocator'
                assert r64(uc.reg_read(X[29])-24) == BACKING
                w32(NEW-1, (layout['image_cid']<<12)|0x31c)
                uc.reg_write(X[0], NEW)
            elif kind == 'register':
                assert uc.reg_read(X[2]) == NEW
            elif kind == 'dispose':
                # The real setter must dispose the new clone, never the owner.
                assert uc.reg_read(X[1]) == NEW
                actions.append('dispose-new'); uc.emu_stop(); return
            if kind:
                actions.append(kind); uc.reg_write(ar.UC_ARM64_REG_PC, uc.reg_read(X[30])); return
            if addr == layout['update_return']:
                assert uc.reg_read(X[0]) == NEW
                assert r32(NEW+7) == BACKING & 0xffffffff
                assert r32(NEW+31) == (NULL+0x30) & 0xffffffff
                assert uc.reg_read(X[29]) == FP and uc.reg_read(X[15]) == DSP
                # Resume the original caller's next operation: setter(x3, clone).
                uc.reg_write(X[1], RENDER); uc.reg_write(X[2], NEW)
                uc.reg_write(ar.UC_ARM64_REG_PC, setter)
        u.hook_add(UC_HOOK_CODE, stock_code)
        u.emu_start(clone, 0, count=500)
        if repair:
            assert actions == ['allocate', 'register', 'dispose-new'], (name, actions)
            assert r32(RENDER+layout['render_image']) == OWNER & 0xffffffff
            assert r32(OWNER+layout['disposed']) == (NULL+0x30) & 0xffffffff
            assert r32(REQUEST+layout['disposed']) == (NULL+0x20) & 0xffffffff
            outcome = 'stock clone allocates; stock setter disposes new clone and retains owner'
        else: assert actions == ['throw'], (name, actions)
    results.append({'case': name, 'outcome': outcome, 'leaf_instructions': len(steps)})


run('same-backing valid live owner', repair=True)
for n in range(16): run(f'NZCV {n:x}', repair=True, nzcv=n<<28)
run('last counter slot', repair=True, count=65534)
run('saturated counter still recovers', repair=True, count=65535)
run('failed exclusive still recovers and marks loss', repair=True, exclusive_fail=True)
run('sticky undercount preserved', repair=True, uncounted=1)
run('disabled never dereferences roots', enabled=0, unmap_heap=True)
run('unrelated caller never dereferences roots', caller=0x777777, unmap_heap=True)
run('new-mount caller never dereferences roots', caller=layout['raw_create']+0x3c, unmap_heap=True)
run('adjacent update instruction rejected', caller=layout['update_return']-4, unmap_heap=True)
run('FP relation rejected before root access', fp=FP+8, unmap_heap=True)
run('FP alignment rejected before root access', fp=FP+1, unmap_heap=True)
for label, value in [('null', NULL), ('Smi', 2), ('tag misalignment', REQUEST+2), ('foreign heap', REQUEST+(1<<32))]:
    run('request '+label, request=value)
run('request wrong CID', request_cid=12)
run('live request leaves poisoned owner alone', request_flag=NULL+0x30, poison_spill=True)
run('unknown disposed boolean', request_flag=NULL+0x40)
for label, value in [('null', NULL), ('Smi', 8), ('tag misalignment', RENDER+2), ('foreign heap', RENDER+(1<<32))]:
    run('renderer '+label+' with live x3 decoy', render=value)
run('renderer wrong CID', render_cid=layout['render_cid']+1)
for label, value in [('null', NULL), ('Smi', 4), ('tag misalignment', OWNER+2), ('same disposed wrapper', REQUEST)]:
    run('owner '+label, owner=value)
run('owner wrong CID', owner_cid=layout['image_cid']+1)
run('owner disposed', owner_flag=NULL+0x20)
run('owner unknown boolean', owner_flag=NULL+0x40)
run('different backing despite equal dimensions', owner_backing=BACKING+0x80)
run('identical null backing is not an image', request_backing=NULL, owner_backing=NULL)
run('identical Smi backing is not an image', request_backing=6, owner_backing=6)
run('misaligned backing is rejected', request_backing=BACKING+2, owner_backing=BACKING+2)
run('real clone and setter same-backing lifetime', repair=True, stock=True)
run('real clone still throws for missing owner', owner=NULL, stock=True)
run('real clone still throws for different backing', owner_backing=BACKING+0x80, stock=True)
run('real clone still throws on new mount', caller=layout['raw_create']+0x3c, stock=True)
a.report.write_text(json.dumps({'result': 'host-pass', 'cases': results,
    'elf': str(a.elf.resolve()), 'elf_sha256': hashlib.sha256(a.elf.read_bytes()).hexdigest(),
    'limits': 'Synthetic rooted heap; real clone/setter bodies with mocked allocation/handle registration. No Dart VM, GC, detour backend or device.'}, indent=2)+'\n')
print(f'{len(results)} ARM64 recovery cases passed')
