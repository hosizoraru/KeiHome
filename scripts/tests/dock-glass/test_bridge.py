#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Execute the linked production Dock gate/render bridge in Unicorn.

Usage: python test_bridge.py --elf <unstripped Release SO> [--report result.json]
Requires unicorn, pyelftools and capstone. Does not compile a fixture or call
Gradle, connect a device, install, or activate anything. The test copies the
actual parent payload bytes (including sibling configs) as the installer does.
It executes only gate/render; original native targets are terminal sentinels.
Authored roots/tags isolate the 456-byte render ABI from profile-resolution QA.
"""
import argparse
import hashlib
import io
import json
import math
import re
import struct
import sys
from pathlib import Path
from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE, UC_HOOK_MEM_WRITE, UC_HOOK_MEM_READ
from unicorn import arm64_const as ar
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--elf', '--library', type=Path, required=True, help='Unstripped linked production arm64 Release/RelWithDebInfo SO')
parser.add_argument('--report', type=Path, help='Optional JSON result file')
args = parser.parse_args()
if not __debug__:
    parser.error('Assertions must be enabled; do not use python -O')
artifact = args.elf.read_bytes()
artifact_sha256 = hashlib.sha256(artifact).hexdigest()
with io.BytesIO(artifact) as stream:
    elf = ELFFile(stream)
    assert elf['e_machine'] == 'EM_AARCH64' and elf['e_type'] == 'ET_DYN', 'Expected linked AArch64 SO'
    table = elf.get_section_by_name('.symtab')
    assert table is not None, 'Use the unstripped CMake output; hidden bridge symbols are required'
    syms = {symbol.name: symbol['st_value'] for symbol in table.iter_symbols()}
    required = ('template', 'gate', 'parent_clip', 'parent_cid', 'background', 'config', 'end', 'payload_end', 'refresh_begin', 'refresh_event', 'refresh_value', 'refresh_config', 'refresh_end', 'mode_post', 'mode_replay', 'mode_config')
    missing = [name for name in required if 'kh_dock_' + name not in syms]
    assert not missing, f'Missing production payload symbols: {missing}'
    origin, payload_end = (syms['kh_dock_template'], syms['kh_dock_payload_end'])
    assert 0 < payload_end - origin <= 8192, 'Payload copy range must fit the bounded mapping contract'
    assert all((origin <= syms['kh_dock_' + name] <= payload_end for name in required)), 'Payload entry outside copied range; check wrapper section after GNU-stack'
    for segment in elf.iter_segments():
        if segment['p_type'] == 'PT_LOAD' and segment['p_vaddr'] <= origin and (payload_end <= segment['p_vaddr'] + segment['p_filesz']):
            offset = segment['p_offset'] + origin - segment['p_vaddr']
            blob = artifact[offset:offset + payload_end - origin]
            break
    else:
        raise AssertionError('Production payload is not wholly inside one file-backed PT_LOAD')
    for section in elf.iter_sections():
        if isinstance(section, RelocationSection) and section['sh_type'] in ('SHT_REL', 'SHT_RELA'):
            assert not any((origin <= item['r_offset'] < payload_end for item in section.iter_relocations())), 'Copied payload contains a dynamic relocation'
entry = {name: syms['kh_dock_' + name] - origin for name in required}
assert entry['refresh_begin'] - entry['config'] == 456, 'Render config ABI must be 456 bytes'
assert entry['refresh_end'] - entry['refresh_config'] == 104, 'Refresh config ABI'
assert entry['payload_end'] - entry['mode_config'] == 72, 'Mode config ABI'
CODE, THREAD, FIELDS, POOL = 0x10000000, 0x20000000, 0x21000000, 0x22000000
POLICY, STACK, END = 0x23000000, 0x24008000, 0x25000000
HEAP = 1 << 32
NULL, TOP = HEAP + 0x100001, HEAP + 0x110000
ORIGINAL, ENTER, SKIP = END + 0x100, END + 0x200, END + 0x300
X = [getattr(ar, f'UC_ARM64_REG_X{i}') for i in range(31)]
Q = [getattr(ar, f'UC_ARM64_REG_Q{i}') for i in range(32)]
# Authored headers: deliberately distinct so every configured tag is exercised.
# These validate machine-code field writes, not the parent launcher resolver.
DOUBLE, POS, STACKTAG, SIZED, BG = 0x3e15c, 0x123431c, 0x123521c, 0x123621c, 0x206211c
TAGS = (0x206931c, 0x206821c, 0x206a21c, 0x206721c)
IGNORE_POINTER = 0x1eb321c
VALUE_KEY = 0x202811c
OWNED_KEY_VALUE = 0x969088
MD = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
decoded = list(MD.disasm(blob[:entry['config']], CODE))
assert sum(instruction.size for instruction in decoded) == entry['config']
for i in decoded:
    assert i.mnemonic not in ('bl', 'blr', 'svc', 'hvc', 'smc'), (i.mnemonic, i.op_str)
    assert not re.search(r'\b(?:sp|x15|w15)\b', i.op_str), (i.mnemonic, i.op_str)

def setup(count=1, elderly=False, C=400.0, H=80.0, w=56.0, native=True,
          enabled=1, pressure=None, change=None, gate=False, mode=0,
          switch_mode=False, width_flag=False, layout_field=0xe7,
          corner_field=0x9f, ink_width=None, q8=0, corner_address=True,
          heap_high=1, array_cid=89, nzcv=0xa0000000,
          icon_top=8.0, no_word=False, cell_height=80.0,
          suppress_field=0, suppress=False, enum_index=None,
          ignore_tag=IGNORE_POINTER,corner_style=0,curve_enabled=1,native_card_radius=0.0,
          expected_horizontal=None):
    """Execute copied production bytes; only authored roots/config are replaced.

    Return True for the constructed graph, False for an untouched native tail,
    and None for a gate decision. Every terminal path checks preserved state.
    """
    global HEAP, NULL, TOP, active_case
    HEAP = heap_high << 32
    NULL = HEAP + 1048577
    TOP = HEAP + 1114112
    active_case = dict(count=count, elderly=elderly, C=C, H=H, cell_width=w, ink_width=ink_width, material_mode=mode,corner_style=corner_style,curve_enabled=curve_enabled, width_flag=width_flag, layout_field=layout_field, corner_q8=q8, heap_high=heap_high, icon_top=icon_top, no_word=no_word, cell_height=cell_height)
    ink_width = w if ink_width is None else ink_width
    native_enum_index = (2 if elderly else 0) if enum_index is None else enum_index
    no_word_allowed = ((not suppress and 0 <= native_enum_index <= 1)
                       if suppress_field else not elderly)
    effective_no_word = no_word and no_word_allowed
    active_case.update(suppress_field=suppress_field, suppress=suppress,
                       enum_index=native_enum_index, effective_no_word=effective_no_word)
    u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    for at, size in [(CODE, 8192), (THREAD, 4096), (FIELDS, 4096), (POOL, 4096), (POLICY, 4096), (STACK - 32768, 65536), (END, 4096), (HEAP + 1048576, 131072)]:
        u.mem_map(at, size)

    def put(at, v):
        u.mem_write(at, struct.pack('<Q', v & (1 << 64) - 1))

    def word(at, v):
        u.mem_write(at, struct.pack('<I', v & 4294967295))

    def number(at, v):
        u.mem_write(at, struct.pack('<d', v))

    def get(at):
        return struct.unpack('<Q', u.mem_read(at, 8))[0]

    def getw(at):
        return struct.unpack('<I', u.mem_read(at, 4))[0]

    def num(at):
        return struct.unpack('<d', u.mem_read(at, 8))[0]
    u.mem_write(CODE, blob)
    cfg = [
        POLICY, ENTER, SKIP, ORIGINAL,                 # 0..24: native addresses
        0x100, 0x13, 0x13, 0x108,                    # 32..56: grid/device
        POLICY + 8, *TAGS,                            # 64..96: mode and tags
        0x100, POS, STACKTAG, SIZED, BG,               # 104..136: PP and headers
        0x108, 0x110, 0x118, DOUBLE,                  # 144..168: Stack PP, Double
        0x128, 0x6b, 0x120,                           # 176..192: max-count chain
        layout_field, corner_field,                  # 200..208: per-build layout
        0x6b, 0x17,                                  # 216..224: painted metrics
        POLICY + 16 if corner_address else 0,         # 232: native dp Q8 address
        0x1f, 0x33, 0xf,                             # 240..256: painted top sources
        suppress_field,                              # 264: optional effective guard
        ignore_tag,                                  # 272: native input wrapper
        0x130, END,                                  # 280..288: stock clip, parent continuation
    ]
    cfg.extend([POLICY+24,POLICY+32,VALUE_KEY,END,END,END,END,(-48)&((1<<64)-1),
                struct.unpack('<Q',struct.pack('<d',20.0))[0],
                struct.unpack('<Q',struct.pack('<d',30.0))[0],(-8)&((1<<64)-1),(-40)&((1<<64)-1),POLICY+40,POLICY+64,END,(-56)&((1<<64)-1),END])
    unlock_tag = 0x21eb31c
    cfg.extend([unlock_tag, 0x140, 0x148])
    assert len(cfg) * 8 == 456
    owned_material=(corner_style&7)==1 and mode==0
    arena = 400 if owned_material else (384 if mode == 0 else 368)
    word(POLICY + 8, mode)
    word(POLICY + 16, q8)
    word(POLICY + 24, corner_style)
    word(POLICY + 32, curve_enabled)
    number(POLICY+40,native_card_radius)
    u.mem_write(CODE + entry['config'], struct.pack('<57Q', *cfg))
    word(POLICY, enabled)
    objects = {name: HEAP + 1052673 + 256 * i for i, name in enumerate(('outer', 'inner', 'box', 'grid', 'rx', 'config', 'device', 'grow', 'array', 'args', 'mode_controller', 'mode_rx', 'elderly_enum', 'normal_enum', 'metrics', 'no_word_rx', 'suppress_rx'))}
    for i, at in enumerate(objects.values()):
        put(at - 1, 100 + i << 12 | 796)
    o = objects
    put(STACK - 8, o['outer'])
    put(STACK - 32, o['inner'])
    put(STACK - 40, o['grow'])
    word(o['outer'] + 15, count * 2)
    word(o['outer'] + 27, NULL + (32 if native else 48))
    word(o['inner'] + 19, o['box'])
    number(o['box'] + 15, C)
    number(o['box'] + 31, H)
    put(THREAD + 120, FIELDS)
    put(FIELDS + 256, o['grid'])
    put(FIELDS + 264, o['device'])
    word(o['grid'] + 19, o['rx'])
    word(o['rx'] + 19, o['config'])
    word(o['device'] + 231, 0)
    word(o['device'] + 235, 0)
    word(o['device'] + layout_field, NULL + (32 if width_flag else 48))
    if corner_field in (159, 163):
        word(o['device'] + corner_field, 4294967281)
    number(o['config'] + 43, float('nan') if width_flag else w)
    number(o['config'] + 119, w - 20 if width_flag else float('nan'))
    number(o['config'] + 0x33, float('nan') if width_flag else cell_height)
    word(o['config'] + 107, o['metrics'])
    number(o['metrics'] + 23, ink_width)
    number(o['metrics'] + 0x1f, icon_top)
    put(FIELDS + 296, o['mode_controller'])
    word(o['mode_controller'] + 107, o['mode_rx'])
    word(o['mode_controller'] + 0xf, o['no_word_rx'])
    word(o['no_word_rx'] + 0x13, NULL + (0x20 if no_word else 0x30))
    word(o['mode_rx'] + 19, o['elderly_enum'] if elderly else o['normal_enum'])
    put(o['elderly_enum'] + 7, native_enum_index)
    put(o['normal_enum'] + 7, native_enum_index)
    word(o['mode_controller'] + (suppress_field or 0x73), o['suppress_rx'])
    word(o['suppress_rx'] + 0x13, NULL + (0x20 if suppress else 0x30))
    put(POOL + 288, o['elderly_enum'])
    word(o['grow'] + 7, o['args'])
    word(o['grow'] + 15, o['array'])
    put(o['array'] - 1, array_cid << 12 | 604)
    word(o['array'] + 7, NULL + 2304)
    for p in (256, 264, 272, 280, 312):
        put(POOL + p, NULL + 1280 + p)
    put(POOL + 64, NULL + 1024)
    put(POOL + 0x140, NULL + 0x1900)
    put(POOL + 0x148, NULL + 0x1a00)
    put(THREAD + 96, TOP)
    put(THREAD + 104, TOP + (4096 if pressure is None else pressure))
    u.mem_write(TOP, b'\xa5' * 4096)
    for i in range(31):
        u.reg_write(X[i], 1717960704 + 256 * i)
    for i in range(32):
        u.reg_write(Q[i], 338770000845734292515960266532868587520 + i)
    for i, v in {1: NULL + (32 if native else 48), 15: STACK - 112, 22: NULL, 26: THREAD, 27: POOL, 28: HEAP >> 32, 29: STACK, 30: END}.items():
        u.reg_write(X[i], v)
    if not gate:
        u.reg_write(X[1], 18369561651120553711)
    u.reg_write(ar.UC_ARM64_REG_NZCV, nzcv)
    u.reg_write(ar.UC_ARM64_REG_SP, STACK + 4096)
    if change:
        change(u, o, put, word, number, cfg)
    beforex = [u.reg_read(r) for r in X]
    beforeq = [u.reg_read(r) for r in Q]
    writes = []
    dest = []
    values = []

    def record_write(uc, access, addr, size, value, data):
        writes.append((addr, size))
        values.append((addr, size, value))
        if switch_mode and addr == THREAD + 96:
            word(POLICY + 8, 3 if mode == 0 else 0)
    u.hook_add(UC_HOOK_MEM_WRITE, record_write)

    def record_read(uc, access, addr, size, value, data):
        assert not addr <= CODE + entry['config'] + 208 < addr + size, 'premature corner config read'
        assert not addr <= o['device'] + corner_field < addr + size, 'premature native corner read'
        if not no_word_allowed:
            no_word_field = get(CODE + entry['config'] + 256)
            assert not addr <= o['mode_controller'] + no_word_field < addr + size, 'guarded mode must bypass no-word Rx'
        if not suppress_field:
            assert not addr <= o['mode_controller'] + 0x73 < addr + size, 'legacy helper must not read suppression Rx'
        if not suppress_field or suppress:
            enum = o['elderly_enum'] if elderly else o['normal_enum']
            assert not addr <= enum + 7 < addr + size, 'enum index read before native guard permits it'
        if effective_no_word:
            top_field = get(CODE + entry['config'] + 240)
            assert not addr <= o['metrics'] + top_field < addr + size, 'no-word must use native cell height'
        elif not width_flag:
            height_field = get(CODE + entry['config'] + 248)
            assert not addr <= o['config'] + height_field < addr + size, 'word mode must use metrics top'
    u.hook_add(UC_HOOK_MEM_READ, record_read)

    def hook(uc, addr, size, data):
        if addr in (END, ORIGINAL, ENTER, SKIP):
            dest.append(addr)
            uc.emu_stop()
    u.hook_add(UC_HOOK_CODE, hook)
    u.emu_start(CODE + entry['gate' if gate else 'background'], END + 4096, count=5000)
    assert dest, 'no terminal branch'
    assert u.reg_read(ar.UC_ARM64_REG_SP) == STACK + 4096, 'native SP clobber'
    keep = [1, 15, *range(18, 31)]
    if gate:
        keep = [i for i in range(31) if i not in (16, 17)]
    assert all((u.reg_read(X[i]) == beforex[i] for i in keep)), 'register clobber'
    assert all((u.reg_read(Q[i]) == beforeq[i] for i in range(8, 16))), 'v8-v15 clobber'
    assert all((addr == THREAD + 96 and size == 8 or (TOP <= addr and addr + size <= TOP + arena) for addr, size in writes)), writes
    if gate:
        assert not writes
        assert u.reg_read(ar.UC_ARM64_REG_NZCV) == nzcv
        assert dest[0] == (ENTER if enabled == 1 or native else SKIP)
        return
    if dest[0] == ORIGINAL:
        # The original native target is a sentinel: no Dart/VM code is stubbed
        # into the tested payload, and no allocation is accepted on fallback.
        assert not writes and get(THREAD + 96) == TOP
        assert bytes(u.mem_read(TOP, 336)) == b'\xa5' * 336
        return False
    assert dest[0] == END and get(THREAD + 96) == TOP + arena
    root = u.reg_read(X[0])
    ref = lambda at: HEAP + getw(at)
    assert get(root - 1) == SIZED and ref(root + 7) == NULL
    assert num(ref(root + 15) + 7) == C and num(ref(root + 19) + 7) == H
    stack = ref(root + 11)
    array = ref(stack + 11)
    pos = ref(array + 15)
    unlock = ref(pos + 11)
    assert get(unlock - 1) == unlock_tag
    assert ref(unlock + 7) == NULL and ref(unlock + 0x13) == NULL
    assert ref(unlock + 0xf) == NULL + 0x1900
    assert get(unlock + 0x17) == 3 and get(unlock + 0x1f) == (1 << 64) - 1
    assert ref(unlock + 0x27) == NULL + 0x30
    assert ref(unlock + 0x2b) == NULL + 0x1a00
    assert sum(get(TOP + off) == unlock_tag for off in range(0, arena, 16)) == 1
    ignore = ref(unlock + 0xb)
    mat = ref(ignore + 0xb)
    assert get(ignore - 1) == ignore_tag
    assert ref(ignore + 7) == NULL
    assert ref(ignore + 0xf) == NULL + 0x20, 'IgnorePointer must ignore input'
    for off in (0x13, 0x17, 0x1b):
        assert ref(ignore + off) == NULL, 'unwritten wrapper slots must be initialized'
    assert sum(get(TOP + off) == ignore_tag for off in range(0, arena, 16)) == 1
    assert get(stack - 1) == STACKTAG and ref(stack + 7) == NULL and (ref(stack + 19) == NULL)
    assert get(array - 1) == array_cid << 12 | 540 and ref(array + 7) == o['args']
    assert getw(array + 11) == (4 if native else 2)
    if native:
        bg = ref(array + 19)
        assert get(bg - 1) == BG and ref(bg + 7) == NULL
        assert bg not in (ignore, mat), 'native background must stay outside IgnorePointer'
    else:
        assert ref(array + 19) == NULL
    assert get(pos - 1) == POS and get(mat - 1) == TAGS[mode]
    for off in (7, 15, 35, 39, 43):
        assert ref(pos + off) == NULL
    if owned_material:
        key=ref(mat+7)
        assert get(key-1)==VALUE_KEY and ref(key+7)==NULL
        assert getw(key+0xb)==OWNED_KEY_VALUE
    else:
        assert ref(mat+7)==NULL
    for off in (39,43) if mode==0 else ():
        assert ref(mat+off)==NULL
    assert ref(mat+19)==NULL+1536 and num(mat+23)==1
    if mode==0:
        assert get(mat+31)==0
    else:
        assert (mat+31,8,0) not in values
    left = num(ref(pos + 19) + 7)
    right = C - num(ref(pos + 27) + 7)
    if expected_horizontal is not None:
        assert math.isclose(left, expected_horizontal[0], abs_tol=1e-10), active_case
        assert math.isclose(right, expected_horizontal[1], abs_tol=1e-10), active_case
    # Top and bottom have separate Double identities even when numerically equal.
    assert ref(pos + 23) != ref(pos + 31)
    native_icon_top = (max(0, ((w - 20 if width_flag else cell_height) - ink_width) / 2)
                       if effective_no_word else icon_top)
    padding_code=(corner_style>>3)&63
    padding=padding_code-1 if padding_code else 18
    native_radius=q8/256 if corner_address and 0<q8<=65536 else 0
    actual_left,actual_right=left,right
    expected_top = max(-12, native_icon_top - padding)
    expected_bottom = min(H + 12, native_icon_top + ink_width + padding)
    assert num(ref(pos + 23) + 7) == expected_top
    assert num(ref(pos + 31) + 7) == H - expected_bottom, 'Positioned bottom must be an inset'
    M = 4 if elderly else 5
    centers = [C / 2 + (i - (count - 1) / 2) * w if count < M else (i + 0.5) * (C / M) for i in range(count)]
    first = centers[0] - ink_width / 2
    last = centers[-1] + ink_width / 2
    clearance = 0 if count == M == 5 else 8
    assert first >= clearance and last <= C - clearance, 'invalid painted envelope admitted'
    edge = 0 if count == 5 else 8
    if (corner_style&7)==3 and count!=5 and native_radius>0:
        left = min(max(0,native_radius-(expected_bottom-expected_top)/2),first,C-last)
        right = C-left
    else:
        left,right = max(edge,first-18),min(C-edge,last+18)
    requested = (((corner_style >> 24) & 63) ^ 32) - 32
    delta = max(-max(0,min(first-left,right-last)), min(requested,max(0,min(left,C-right))))
    left,right = left-delta,right+delta
    assert math.isclose(actual_left,left,abs_tol=1e-10) and math.isclose(actual_right,right,abs_tol=1e-10)
    for center in centers:
        assert actual_left <= center - ink_width / 2 + 1e-10
        assert actual_right >= center + ink_width / 2 - 1e-10
    expected_radius = min(max(28, native_radius - max(8,min(left, C - right))), (right - left) / 2, (expected_bottom - expected_top) / 2)
    if (corner_style&7)==1:
        target=native_card_radius if math.isfinite(native_card_radius) and 0<native_card_radius<=256 else 20
        expected_radius=min(target,(right-left)/2,(expected_bottom-expected_top)/2)
    elif (corner_style&7)==2:
        expected_radius=min(max(0,ink_width*16/60+(expected_bottom-expected_top-ink_width)/2),
                            (right-left)/2,(expected_bottom-expected_top)/2)
    elif (corner_style&7)==3 and native_radius>0:
        expected_radius=min(max(0,native_radius-min(left,C-right)),(right-left)/2,(expected_bottom-expected_top)/2)
    elif (corner_style&7)==4:
        expected_radius=min((corner_style>>9)&127,(right-left)/2,(expected_bottom-expected_top)/2)
    if count==5:expected_radius=min(expected_radius,32)
    actual_radius=num(mat+11)
    assert math.isclose(actual_radius,expected_radius,abs_tol=1e-10), 'corner style/Q8 geometry'
    for off, pooloff in ((15, 264), (23, 272), (27, 280)):
        assert ref(stack + off) == NULL + 1280 + pooloff
    for off in (23, 27):
        assert ref(root + off) == NULL and ref(array + off) == NULL
    if native:
        assert ref(bg + 11) == NULL
    assert all((get(TOP + off) == DOUBLE for off in (0, 16, 32, 48, 64, 80)))
    touched = {p for addr, size in writes for p in range(addr, addr + size) if TOP <= p < TOP + arena}
    assert len(touched) == arena
    assert bytes(u.mem_read(TOP + arena, 16)) == b'\xa5' * 16, 'arena overrun'
    return True
# New native Recents material/shape branch, including capability fallback and
# exact-end pressure. All original renderer cases continue to cover Rounded.
recents_cases=0
for style in (0,1):
    for mode in range(4):
        for curve in (0,1,2):
            for count in range(1,6):
                for q8 in (0,20*256,30*256,60*256,256*256):
                    assert setup(count=count,mode=mode,corner_style=style,
                                 curve_enabled=curve,q8=q8)
                    recents_cases+=1
for native_card in (0,20,30,40.2,46,60,256,-1,float('nan'),float('inf')):
    for count in range(1,6):
        assert setup(count=count,corner_style=1,native_card_radius=native_card)
        recents_cases+=1
for mode in range(4):
    arena=400 if mode==0 else 368
    for pressure in (0,arena-1,arena):
        assert not setup(mode=mode,corner_style=1,pressure=pressure)
        recents_cases+=1
    assert setup(mode=mode,corner_style=1,pressure=arena+1)
    recents_cases+=1
for style in (0,1,2,3,4):
 for padding in (0,6,18,32):
  for custom_radius in ((0,16,64) if style==4 else (0,)):
   options=style | ((padding+1)<<3) | (custom_radius<<9)
   try:
    result=setup(count=4,C=420,w=80,H=88,ink_width=64,icon_top=4,
                 corner_style=options,q8=60*256)
   except Exception as error:
    raise RuntimeError((style,padding,custom_radius)) from error
   assert result is True,(style,padding,custom_radius,result)
   recents_cases+=1
for count in (1,5):
 for style in (2,3,4):
  for padding in (0,18,32):
   options=style|((padding+1)<<3)|(12<<9 if style==4 else 0)
   assert setup(count=count,C=420,w=80,H=88,ink_width=64,icon_top=4,
                corner_style=options,q8=60*256)
   recents_cases+=1
for style in (5,0xffffffff):
    assert not setup(corner_style=style)
    recents_cases+=1
horizontal_cases=0
for C,count,elderly in ((340,4,False),(360,4,False),(360.5,3,False),
                         (336,5,False),(560,4,True)):
    for style in range(5):
        for delta in (-16,-8,0,1,8,24):
            options = style | ((delta & 63) << 24) | (12 << 9 if style == 4 else 0)
            assert setup(count=count,elderly=elderly,C=C,w=80,H=100,ink_width=56,
                         corner_style=options,q8=64*256), (C,count,elderly,style,delta)
            horizontal_cases+=1
active_case = {}


def test_failure(kind, value, traceback):
    print('FAIL scenario: ' + repr(active_case), file=sys.stderr)
    sys.__excepthook__(kind, value, traceback)
sys.excepthook = test_failure
cases = recents_cases + horizontal_cases
# Literal ink bounds are independent of the production header/assembly formula.
# The first case is the exact new boundary: five 80dp icons touch C=400 at 0dp.
# setup executes linked ARM64 and checks every reference, allocation and inset.
five_item_cases = 0
five_item_fixtures = (
    # C, cell, ink, first painted left, last painted right, visible
    (400, 100, 80, 0, 400, True),
    (400, 100, 79.5, 0.25, 399.75, True),
    (400, 100, 72, 4, 396, True),
    (400, 100, 65, 7.5, 392.5, True),
    (400, 100, 64.5, 7.75, 392.25, True),
    (400, 100, 64, 8, 392, True),
    (400, 100, 56, 12, 388, True),
    (300, 40, 40, 10, 290, True),
    (280, 40, 40, 8, 272, True),
    (279, 40, 40, 7.9, 271.1, True),
    (200, 100, 40, 0, 200, True),
    (200, 40, 40, 0, 200, True),
    (199, 40, 40, -0.1, 199.1, False),
    (400, 100, 80.5, -0.25, 400.25, False),
    (400, 100, 100, -10, 410, False),
)
for C, cell, ink, first, last, visible in five_item_fixtures:
    assert visible == (first >= 0 and last <= C)
    for mode in range(4):
        for native in (False, True):
            for width_flag in (False, True):
                for elderly in (False, True):
                    expected = visible and not elderly
                    assert setup(count=5, elderly=elderly, C=C, H=160,
                                 w=cell, ink_width=ink, icon_top=20, mode=mode,
                                 native=native, width_flag=width_flag,
                                 expected_horizontal=(0, C)) == expected, active_case
                    five_item_cases += 1
# Packed one-to-four and full elderly four retain the exact 8dp boundary.
for elderly in (False, True):
    for count in range(1, 5):
        C = 224 if elderly and count == 4 else count * 40 + 16
        assert setup(count=count, elderly=elderly, C=C, w=40,
                     expected_horizontal=(8, C - 8)), active_case
        assert not setup(count=count, elderly=elderly, C=C - 0.5, w=40), active_case
        assert not setup(count=count, elderly=elderly, C=count * 40, w=40), active_case
        five_item_cases += 3
# At zero horizontal clearance, malformed roots/metrics must still fall back
# without publishing allocations. Positive overflow is never clipped to fit.
for field in ('C', 'H', 'w', 'ink_width'):
    for bad in (0., -1., float('nan'), float('inf'), -float('inf')):
        fixture = dict(count=5, w=100, ink_width=80, H=160, icon_top=20)
        fixture[field] = bad
        assert not setup(**fixture), active_case
        five_item_cases += 1
for bad in (-1., float('nan'), float('inf'), -float('inf'), 92.001):
    assert not setup(count=5, w=100, ink_width=80, H=160, icon_top=bad), active_case
    five_item_cases += 1
for bad in (0., -1., 101., float('nan'), float('inf'), -float('inf')):
    assert not setup(count=5, C=200, w=100, ink_width=bad, H=160), active_case
    five_item_cases += 1
for count in (0, -1, 6, (1 << 30) - 1):
    assert not setup(count=count, w=100, ink_width=80, H=160), active_case
    five_item_cases += 1
for style in range(5):
    for mode in range(4):
        for no_word in (False, True):
            options = style | (12 << 9 if style == 4 else 0)
            assert setup(count=5, w=100, ink_width=80, H=160, icon_top=20,
                         mode=mode, no_word=no_word, corner_style=options,
                         q8=60 * 256, native_card_radius=44,
                         expected_horizontal=(0, 400)), active_case
            five_item_cases += 1
cases += five_item_cases
# Disabled gate delegates to the original bit; enabled gate forces insertion.
for enabled in (0, 1, 2):
    for native in (False, True):
        setup(enabled=enabled, native=native, gate=True)
        cases += 1
for elderly in (False, True):
    for native in (False, True):
        for count in range(0, 7):
            ok = setup(count=count, elderly=elderly, native=native)
            assert ok == (1 <= count <= (4 if elderly else 5))
            cases += 1
for p in (0, 1, 383, 384):
    assert not setup(pressure=p)
    cases += 1
assert setup(pressure=385)
cases += 1
for enabled in (0, 2):
    assert not setup(enabled=enabled)
    cases += 1
for C, H, w in ((36, 100, 20), (96, 80, 40), (401.5, 80.5, 55.25), (400, 17, 56)):
    assert setup(C=C, H=H, w=w) == (H + 12 >= 8 + w)
    cases += 1
for field in ('C', 'H', 'w'):
    for bad in (float('nan'), float('inf'), -float('inf'), -1.0, 0.0):
        assert not setup(**{field: bad})
        cases += 1
for C, H, w, count in ((16, 80, 1, 1), (35, 80, 20, 1), (199, 80, 40, 5), (400, 80, 100, 5)):
    assert not setup(C=C, H=H, w=w, count=count)
    cases += 1
for name, field in (('grid', None), ('device', None), ('grid', 19), ('rx', 19), ('inner', 19), ('grow', 15), ('mode_controller', None), ('mode_controller', 107), ('mode_rx', 19), ('config', 107)):
    for bad in (NULL, NULL + 1024, 0):

        def change(u, o, put, word, number, cfg, name=name, field=field, bad=bad):
            if field is None:
                put(FIELDS + {'grid': 256, 'device': 264, 'mode_controller': 296}[name], bad)
            else:
                word(o[name] + field, bad)
        assert not setup(change=change)
        cases += 1
for mode in (4, 4294967295):
    assert not setup(mode=mode)
    cases += 1
# Malformed count/Bools must never reach allocation or manufacture a native BG.
for count in (-1, -2, (1 << 30) - 1):
    assert not setup(count=count)
    cases += 1
assert not setup(change=lambda u, o, put, word, number, cfg:
                 word(o['outer'] + 0xf, 3))
cases += 1
for bad in (NULL, 0, NULL + 0x40):
    assert not setup(change=lambda u, o, put, word, number, cfg:
                     word(o['outer'] + 0x1b, bad))
    cases += 1
    assert not setup(change=lambda u, o, put, word, number, cfg:
                     word(o['device'] + 0xe7, bad))
    cases += 1
assert not setup(change=lambda u, o, put, word, number, cfg: put(CODE + entry['config'] + 64, 0))
cases += 1
for mode in range(4):
    for native in (False, True):
        for elderly in (False, True):
            for count in range(1, 6):
                assert setup(mode=mode, native=native, elderly=elderly, count=count) == (not elderly or count <= 4)
                cases += 1
        assert setup(mode=mode, native=native, switch_mode=True)
        cases += 1
    arena = 384 if mode == 0 else 368
    for available in (arena - 1, arena):
        assert not setup(mode=mode, pressure=available)
        cases += 1
    assert setup(mode=mode, pressure=arena + 1)
    cases += 1
    assert not setup(mode=mode, change=lambda u, o, put, word, number, cfg: put(CODE + entry['config'] + 72 + 8 * mode, 0))
    cases += 1
for mode in range(4):
    for width_flag in (False, True):
        for elderly in (False, True):
            for native in (False, True):
                for count in range(1, 6):
                    assert setup(mode=mode, width_flag=width_flag, elderly=elderly, native=native, count=count) == (not elderly or count <= 4)
                    cases += 1
for bad in (NULL, NULL + 1024, 0):
    assert not setup(change=lambda u, o, put, word, number, cfg: put(POOL + 288, bad))
    cases += 1

def relocate_mode_chain(u, o, put, word, number, cfg):
    """Poison old fields so fixture-address shortcuts cannot pass."""
    for offset, value in ((40, 35), (48, 27), (176, 568), (184, 123), (192, 384)):
        put(CODE + entry['config'] + offset, value)
    word(o['grid'] + 35, o['rx'])
    word(o['grid'] + 19, NULL)
    word(o['rx'] + 27, o['config'])
    word(o['rx'] + 19, NULL)
    put(FIELDS + 568, o['mode_controller'])
    put(FIELDS + 296, NULL)
    word(o['mode_controller'] + 123, o['mode_rx'])
    word(o['mode_controller'] + 107, NULL)
    enum_value = struct.unpack('<I', u.mem_read(o['mode_rx'] + 19, 4))[0]
    word(o['mode_rx'] + 27, enum_value)
    word(o['mode_rx'] + 19, NULL)
    no_word_value = struct.unpack('<I', u.mem_read(o['no_word_rx'] + 0x13, 4))[0]
    word(o['no_word_rx'] + 0x1b, no_word_value)
    word(o['no_word_rx'] + 0x13, NULL)
    suppress_value = struct.unpack('<I', u.mem_read(o['suppress_rx'] + 0x13, 4))[0]
    word(o['suppress_rx'] + 0x1b, suppress_value)
    word(o['suppress_rx'] + 0x13, NULL)
    put(POOL + 384, o['elderly_enum'])
    put(POOL + 288, NULL)
for width_flag in (False, True):
    for elderly in (False, True):
        for count in (4, 5):
            assert setup(width_flag=width_flag, elderly=elderly, count=count, change=relocate_mode_chain) == (not elderly or count <= 4)
            cases += 1
for layout_field, corner_field in ((231, 159), (235, 163), (199, 4194304)):
    for mode in range(4):
        for width_flag in (False, True):
            for elderly in (False, True):
                for count in (4, 5):
                    assert setup(layout_field=layout_field, corner_field=corner_field, mode=mode, width_flag=width_flag, elderly=elderly, count=count) == (not elderly or count <= 4)
                    cases += 1
for width_flag in (False, True):
    for elderly in (False, True):
        assert setup(layout_field=235, corner_field=163, width_flag=width_flag, elderly=elderly, count=4, change=relocate_mode_chain)
        cases += 1
for mode in range(4):
    for width_flag in (False, True):
        for elderly in (False, True):
            for count in (1, 2, 3, 4, 5, 4, 3, 2, 1):
                assert setup(mode=mode, width_flag=width_flag, elderly=elderly, count=count, w=100, ink_width=56) == (not elderly or count <= 4)
                cases += 1
# Cells wider than C/maxCount remain valid when their painted icons fit.
for bad in (0.0, -1.0, 101.0, float('nan'), float('inf'), -float('inf')):
    assert not setup(count=5, w=100, ink_width=bad)
    cases += 1
assert setup(count=5, w=100, ink_width=64)
cases += 1
assert setup(count=5, w=100, ink_width=65)
cases += 1
assert not setup(count=5, w=100, ink_width=81)
cases += 1
# Q8 floor, fractional values, adaptation, dimension caps and invalid fallback.
for q8 in (0, 1, 8 * 256, 20 * 256, 28 * 256, 36 * 256, 40 * 256, 40 * 256 + 128, 200 * 256, 65536, 65537, 4294967295):
    for count in (1, 2, 5):
        for mode in range(4):
            assert setup(q8=q8, count=count, mode=mode, w=100, ink_width=56)
            cases += 1
for count in (1, 5):
    assert setup(q8=40 * 256, count=count, w=100, ink_width=56, corner_address=False)
    cases += 1
for C, H, w, ink in ((36, 100, 20, 20), (400, 17, 100, 56), (400, 36, 100, 56)):
    assert setup(C=C, H=H, w=w, ink_width=ink, q8=40 * 256) == (H + 12 >= 8 + ink)
    cases += 1
for bits in range(16):
    for enabled in (0, 1, 2):
        for native in (False, True):
            setup(gate=True, nzcv=bits << 28, enabled=enabled, native=native)
            cases += 1
for high in (1, 116):
    for cid in (89, 90, 4660):
        assert setup(heap_high=high, array_cid=cid, count=5, w=100, ink_width=56)
        cases += 1

def relocate_metrics(u, o, put, word, number, cfg):
    """Painted metrics also come from the parent config, not fixed offsets."""
    put(CODE + entry['config'] + 216, 123)
    put(CODE + entry['config'] + 224, 39)
    word(o['config'] + 123, o['metrics'])
    word(o['config'] + 107, NULL)
    number(o['metrics'] + 39, 56)
    number(o['metrics'] + 23, float('nan'))
assert setup(count=5, w=100, ink_width=56, change=relocate_metrics)
cases += 1

# Vertical painted bounds use native mode semantics, independent of material,
# layout flag, count threshold, and native background insertion gate.
for mode in range(4):
    for width_flag in (False, True):
        for elderly in (False, True):
            for no_word in (False, True):
                for count in (1, 4, 5):
                    assert setup(mode=mode, width_flag=width_flag, elderly=elderly,
                                 no_word=no_word, count=count, C=400, H=160,
                                 w=100, ink_width=56, icon_top=20,
                                 cell_height=120, q8=256 * 256) == (not elderly or count <= 4)
                    cases += 1
for native in (False, True):
    for top in (0, 4, 8, 20, 76, 76.001, 80, 120, 128, 149, 150, 152, 200):
        assert setup(H=120, w=100, ink_width=56, icon_top=top,
                     native=native) == (top <= 76)
        cases += 1
for bad in (-1., float('nan'), float('inf'), -float('inf')):
    assert not setup(icon_top=bad)
    cases += 1
    for width_flag in (False, True):
        assert setup(no_word=True, width_flag=width_flag, icon_top=bad)
        cases += 1
    assert not setup(elderly=True, no_word=True, icon_top=bad)
    cases += 1
for bad in (-1., float('nan'), float('inf'), -float('inf')):
    assert not setup(no_word=True, cell_height=bad)
    cases += 1
    assert setup(cell_height=bad)
    cases += 1
    assert setup(elderly=True, no_word=True, cell_height=bad)
    cases += 1
for cell_height in (0., 1., 20., 56., 80., 160.):
    assert setup(no_word=True, H=200, cell_height=cell_height)
    cases += 1
for width in (19.,):
    assert not setup(no_word=True, width_flag=True, w=width, ink_width=12)
    cases += 1
    assert setup(width_flag=True, w=width, ink_width=12)
    cases += 1
for width in (20., 21.):
    assert setup(no_word=True, width_flag=True, w=width, ink_width=12)
    cases += 1
# Small but positive native bounds are valid with the explicit painted envelope.
assert setup(H=16, w=100, ink_width=8, icon_top=4)
cases += 1
for bad in (NULL, 0, NULL + 0x400):
    def bad_no_word_rx(u, o, put, word, number, cfg):
        word(o['mode_controller'] + 0xf, bad)
    assert not setup(change=bad_no_word_rx)
    cases += 1
    assert setup(elderly=True, no_word=True, change=bad_no_word_rx)
    cases += 1
for bad in (NULL, 0, NULL + 0x400, NULL + 0x40):
    def bad_no_word_value(u, o, put, word, number, cfg):
        word(o['no_word_rx'] + 0x13, bad)
    assert not setup(change=bad_no_word_value)
    cases += 1
    assert setup(elderly=True, no_word=True, change=bad_no_word_value)
    cases += 1


def relocate_vertical(u, o, put, word, number, cfg):
    """Move all three vertical fields; poison the source-fixture locations."""
    top = struct.unpack('<d', u.mem_read(o['metrics'] + 0x1f, 8))[0]
    height = struct.unpack('<d', u.mem_read(o['config'] + 0x33, 8))[0]
    rx = struct.unpack('<I', u.mem_read(o['mode_controller'] + 0xf, 4))[0]
    for offset, value in ((240, 0x37), (248, 0x43), (256, 0x23)):
        put(CODE + entry['config'] + offset, value)
    number(o['metrics'] + 0x37, top)
    number(o['metrics'] + 0x1f, float('nan'))
    number(o['config'] + 0x43, height)
    number(o['config'] + 0x33, float('nan'))
    word(o['mode_controller'] + 0x23, rx)
    word(o['mode_controller'] + 0xf, NULL)


def relocate_vertical_and_modes(u, o, put, word, number, cfg):
    relocate_vertical(u, o, put, word, number, cfg)
    relocate_mode_chain(u, o, put, word, number, cfg)


for width_flag in (False, True):
    for elderly in (False, True):
        for no_word in (False, True):
            assert setup(width_flag=width_flag, elderly=elderly, no_word=no_word,
                         H=160, w=100, ink_width=56, icon_top=20,
                         change=relocate_vertical_and_modes)
            cases += 1

# Extended effective helper: suppression Bool first, then mode enum raw index,
# then no-word Bool. The exact elderly identity still controls ONLY max count.
for mode in range(4):
    for width_flag in (False, True):
        for elderly in (False, True):
            for suppress in (False, True):
                for index in (0, 1, 2, 3):
                    for no_word in (False, True):
                        for count in (1, 4):
                            assert setup(mode=mode, width_flag=width_flag,
                                         elderly=elderly, suppress_field=0x73,
                                         suppress=suppress, enum_index=index,
                                         no_word=no_word, count=count, H=160,
                                         w=100, ink_width=56, icon_top=20,
                                         cell_height=120)
                            cases += 1
# Legacy path ignores both suppression and enum ordinal; exact identity rules.
for elderly in (False, True):
    for suppress in (False, True):
        for index in (0, 1, 2, 3):
            assert setup(elderly=elderly, suppress=suppress, enum_index=index,
                         no_word=True, H=160, w=100, ink_width=56, icon_top=20)
            cases += 1


def poison_no_word(u, o, put, word, number, cfg):
    word(o['mode_controller'] + 0xf, NULL)
    number(o['config'] + 0x33, float('nan'))


# Suppression short-circuits both raw enum index and no-word reads. Index >1
# short-circuits no-word reads too; both select finite metrics top normally.
for index in (-1, 0, 1, 2, 3):
    assert setup(suppress_field=0x73, suppress=True, enum_index=index,
                 no_word=True, icon_top=20, change=poison_no_word)
    cases += 1
for index in (2, 3):
    assert setup(suppress_field=0x73, enum_index=index, no_word=True,
                 icon_top=20, change=poison_no_word)
    cases += 1
assert not setup(suppress_field=0x73, enum_index=-1)
cases += 1
for bad in (NULL, 0, NULL + 0x400):
    def bad_suppression_rx(u, o, put, word, number, cfg):
        word(o['mode_controller'] + 0x73, bad)
    assert not setup(suppress_field=0x73, change=bad_suppression_rx)
    cases += 1
    assert setup(no_word=True, change=bad_suppression_rx)
    cases += 1
for bad in (NULL, 0, NULL + 0x400, NULL + 0x40):
    def bad_suppression_value(u, o, put, word, number, cfg):
        word(o['suppress_rx'] + 0x13, bad)
    assert not setup(suppress_field=0x73, change=bad_suppression_value)
    cases += 1
    assert setup(no_word=True, change=bad_suppression_value)
    cases += 1


def relocate_extended(u, o, put, word, number, cfg):
    rx = struct.unpack('<I', u.mem_read(o['mode_controller'] + 0x73, 4))[0]
    put(CODE + entry['config'] + 264, 0x83)
    word(o['mode_controller'] + 0x83, rx)
    word(o['mode_controller'] + 0x73, NULL)
    relocate_vertical_and_modes(u, o, put, word, number, cfg)


for suppress in (False, True):
    for index in (0, 1, 2, 3):
        for width_flag in (False, True):
            for no_word in (False, True):
                assert setup(suppress_field=0x73, suppress=suppress,
                             enum_index=index, width_flag=width_flag,
                             no_word=no_word, H=160, w=100, ink_width=56,
                             icon_top=20, change=relocate_extended)
                cases += 1
assert setup(suppress_field=0x83, no_word=True, H=160, icon_top=20)
cases += 1
# All five authenticated fixture headers are supplied through the config; the
# wrapper is unique, input-ignoring, and never owns the native background.
for ignore_tag in (0x1e1021c, 0x1eb321c, 0x1eb321c, 0x1efa21c, 0x1efc21c):
    for mode in range(4):
        for native in (False, True):
            assert setup(ignore_tag=ignore_tag, mode=mode, native=native,
                         count=5, w=100, ink_width=56, H=160, icon_top=20)
            cases += 1
assert not setup(ignore_tag=0)
cases += 1
# Execute the extra parent store through its authenticated native continuation.
# Every register other than the proven-dead x1, all SIMD state, flags and stack
# remain unchanged. Native continuation restores x1 and initializes children.
def parent_clip_case(enabled, nzcv, heap_high, first_tag=SIZED, empty=False):
    u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    heap = heap_high << 32
    for at, size in ((CODE,8192),(POLICY,4096),(POOL,4096),(END,4096),(STACK-4096,8192),(heap+0x100000,4096)):
        u.mem_map(at,size)
    u.mem_write(CODE,blob)
    words=[0xd503201f]*10
    bit,used,cid=0,0,SIZED>>12
    while bit<20:
        if not cid&(1<<bit):
            bit+=1
            continue
        first_bit=bit
        while bit<20 and cid&(1<<bit): bit+=1
        words[used]=0x52000021|(((32-first_bit)&31)<<16)|((bit-first_bit-1)<<10)
        used+=1
    u.mem_write(CODE+entry['parent_cid'],struct.pack('<10I',*words))
    def put(at,v): u.mem_write(at,struct.pack('<Q',v))
    obj=heap+0x100001
    original=heap+0x100401
    none=heap+0x100501
    child=heap+0x100601
    array,first=heap+0x100701,heap+0x100801
    u.mem_write(child+0xb,struct.pack('<I',0 if empty else 2))
    u.mem_write(child+0xf,struct.pack('<I',array&0xffffffff))
    u.mem_write(array+0xf,struct.pack('<I',first&0xffffffff))
    u.mem_write(first-1,struct.pack('<I',first_tag))
    put(CODE+entry['config'],POLICY)
    put(CODE+entry['config']+160,0x118)
    put(CODE+entry['config']+280,0x130)
    put(CODE+entry['config']+288,END)
    put(POOL+0x118,none);put(POOL+0x130,original)
    u.mem_write(POLICY,struct.pack('<I',enabled))
    put(STACK-0x28,child)
    # Original LDUR x1,[fp,-28], STUR w1,[x0,+b], RET.
    u.mem_write(END,bytes.fromhex('a1835df801b000b8c0035fd6'))
    for i,r in enumerate(X):u.reg_write(r,0x44440000+i*256)
    for i,r in enumerate(Q):u.reg_write(r,0x11112222333344445555666677770000+i)
    for i,v in {0:obj,1:original,15:STACK-128,27:POOL,28:heap_high,29:STACK,30:END+256}.items():u.reg_write(X[i],v)
    u.reg_write(ar.UC_ARM64_REG_SP,STACK+1024)
    u.reg_write(ar.UC_ARM64_REG_NZCV,nzcv)
    before=[u.reg_read(r) for r in X];qbefore=[u.reg_read(r) for r in Q]
    stack_before=bytes(u.mem_read(STACK-4096,8192))
    u.emu_start(CODE+entry['parent_clip'],END+256,count=100)
    assert u.reg_read(ar.UC_ARM64_REG_PC)==END+256
    for i,r in enumerate(X):assert u.reg_read(r)==(child if i==1 else before[i]),i
    assert [u.reg_read(r) for r in Q]==qbefore
    assert u.reg_read(ar.UC_ARM64_REG_NZCV)==nzcv
    assert u.reg_read(ar.UC_ARM64_REG_SP)==STACK+1024
    assert bytes(u.mem_read(STACK-4096,8192))==stack_before
    expected=none if enabled==1 and not empty and first_tag==SIZED else original
    assert struct.unpack('<I',u.mem_read(obj+0x1b,4))[0]==(expected&0xffffffff)
    assert struct.unpack('<I',u.mem_read(obj+0xb,4))[0]==(child&0xffffffff)
for enabled in (0,1,2,0xffffffff):
    for flags in range(16):
        for heap_high in (1,7):
            for first_tag,empty in ((SIZED,False),(BG,False),(SIZED,True)):
                parent_clip_case(enabled,flags<<28,heap_high,first_tag,empty)
                cases+=1
for bit in range(20):
    for heap_high in (1,7):
        parent_clip_case(1,0xa0000000,heap_high,SIZED^(1<<(bit+12)))
        cases+=1

report = dict(passed=True, cases=cases, elf=str(args.elf.resolve()), sha256=artifact_sha256, payload_bytes=len(blob), render_config_bytes=456, arena_bytes={'rounded_glass':384,'recents_glass':400,'other':368}, coverage=['production linked bytes copied to a different address', 'enabled-only parent Clip.none; stock off; original child load/store; all registers/flags', 'native gate outcomes and all NZCV patterns', 'counts, native ink envelopes, cells wider than slots', 'independent device layout and elderly enum', 'four material headers/sizes/raw fields', 'Q8 corner adaptation and radius floor/caps', 'painted vertical bounds, no-word cell height, elderly bypass and branch read guards', 'legacy and extended no-word guards, suppression ordering and enum-index short-circuit', 'compressed references and dynamic offsets', 'one native UnlockAnimWidget scopes one IgnorePointer and leaves native background outside', 'native background inclusion/order and fallback target', 'register/stack preservation and zero corner-list reads', 'full arena initialization and exact-end pressure'], limits='Authored Dart roots and configured tags; does not validate profile resolution, VM GC, Flutter rendering, mode/refresh execution or device behavior')
report['five_item_clearance_cases'] = five_item_cases
report['horizontal_padding_cases'] = horizontal_cases
report['coverage'].append('full-capacity five: inclusive zero-clearance ink, old 8dp boundary, negative overflow, unchanged one-to-four and elderly rejection')
report['coverage'].append('signed horizontal padding across compact and wide layouts, three through five icons and all corner styles')
if args.report:
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
