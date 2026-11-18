#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Independent debug-symbol + Capstone audit of the unchanged bridge's ABI."""
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
from generate_contracts import Fixture


def audit(library, debug):
    fixture = Fixture(library, debug)
    cs = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    cs.detail = True
    code = {role: list(cs.disasm(fixture.elf.read(address, size), address))
            for role, (address, size) in fixture.roles.items()}

    def instruction(role, offset, mnemonic, operands):
        i = code[role][offset//4]
        assert (i.mnemonic, i.op_str) == (mnemonic, operands), (role, hex(offset), str(i))

    def pool_pair(role, offset):
        a, l = code[role][offset//4:offset//4+2]
        assert a.mnemonic == 'add' and cs.reg_name(a.operands[1].reg) == 'x27'
        assert l.mnemonic == 'ldr' and l.operands[1].mem.base == a.operands[0].reg
        assert l.operands[0].reg == a.operands[0].reg
        return (a.operands[2].imm << a.operands[2].shift.value) + l.operands[1].mem.disp

    instruction('Has', 12, 'stur', 'x1, [x29, #-8]')
    instruction('Has', 36, 'stur', 'w0, [x1, #0xf]')
    instruction('Has', 80, 'ldur', 'w3, [x2, #0xf]')
    instruction('Has', 84, 'add', 'x3, x3, x28, lsl #32')
    instruction('Has', 108, 'ldur', 'x2, [x29, #-8]')
    instruction('Has', 112, 'mov', 'x3, x0')
    has = fixture.roles['Has'][0]
    callers = fixture.incoming[has]
    assert len(callers) == 2 and all(link for _, link in callers)
    for caller, previous in [('Should', ('ldur', 'x1, [x29, #-8]')),
                             ('Update', ('mov', 'x1, x2'))]:
        sites = [i for i, op in enumerate(code[caller]) if op.mnemonic == 'bl' and op.operands[0].imm == has]
        assert len(sites) == 1
        index = sites[0]
        assert (code[caller][index-1].mnemonic, code[caller][index-1].op_str) == previous
        if caller == 'Update':
            assert (code[caller][index-3].mnemonic, code[caller][index-3].op_str) == ('ldur', 'w2, [x1, #0xc3]')
    instruction('PackageGetter', 80, 'ldur', 'w3, [x2, #0xc3]')
    instruction('WidgetPackageGetter', 36, 'ldur', 'w2, [x1, #0xc3]')
    instruction('IconBuild', 68, 'bl', f'#{fixture.roles["PackageGetter"][0]:#x}')
    instruction('IconBuild', 76, 'stur', 'w0, [x2, #0x17]')
    cache_path = 'CacheQuery' in code
    if not cache_path:
        instruction('IconQueryCallback', 136, 'ldur', 'w1, [x0, #0x17]')
        instruction('IconQueryCallback', 144, 'bl', f'#{fixture.roles["Should"][0]:#x}')
    else:
        assert 'IconQueryCallback' not in code and 'PreviewUpdate' in code
        # Cache passes its live String unchanged. Preview first reconstructs
        # the model, then its package String, before the very same predicate.
        instruction('CacheQuery', 12, 'mov', 'x0, x1')
        instruction('CacheQuery', 16, 'stur', 'x1, [x29, #-8]')
        instruction('CacheQuery', 28, 'bl', f'#{fixture.roles["Should"][0]:#x}')
        instruction('PreviewUpdate', 0xc4, 'ldur', 'w1, [x0, #0x1f]')
        instruction('PreviewUpdate', 0xcc, 'ldur', 'w2, [x1, #0xc3]')
        instruction('PreviewUpdate', 0xd0, 'add', 'x2, x2, x28, lsl #32')
        instruction('PreviewUpdate', 0xd4, 'mov', 'x1, x2')
        instruction('PreviewUpdate', 0xd8, 'bl', f'#{fixture.roles["Should"][0]:#x}')
    # FolderBuild changed its stack allocation use. Follow the actual last
    # store to the argument's stack slot; do not assume the old stack offset.
    folder = code['FolderBuild']
    fallback = fixture.roles['FolderFallback'][0]
    sites = [i for i, op in enumerate(folder) if op.mnemonic == 'bl' and op.operands[0].imm == fallback]
    assert len(sites) == 1
    index = sites[0]
    reload = folder[index-1]
    assert reload.mnemonic == 'ldur' and cs.reg_name(reload.operands[0].reg) == 'x3'
    slot = reload.operands[1].mem
    stores = [i for i, op in enumerate(folder[:index-1]) if op.mnemonic == 'stur'
              and op.operands[1].mem.base == slot.base and op.operands[1].mem.disp == slot.disp]
    store_index = stores[-1]
    source = folder[store_index].operands[0].reg
    writes = [i for i, op in enumerate(folder[:store_index]) if source in op.regs_access()[1]]
    add = folder[writes[-1]]
    field = folder[writes[-1]-1]
    assert add.mnemonic == 'add' and cs.reg_name(add.operands[2].reg) == 'x28' and add.operands[2].shift.value == 32
    assert field.mnemonic == 'ldur' and field.operands[1].mem.disp == 11
    fallback_code = code['FolderFallback']
    should = fixture.roles['Should'][0]
    sites = [i for i, op in enumerate(fallback_code)
             if op.mnemonic == 'bl' and op.operands[0].imm == should]
    assert len(sites) == 1 and sites[0] >= 2
    argument, saved = fallback_code[sites[0]-2:sites[0]]
    assert (argument.mnemonic, argument.op_str) == ('mov', 'x1, x3')
    assert saved.mnemonic == 'stur' and saved.operands[1].mem.disp == -8
    instruction('ResourceProbe', 16, 'ldur', 'w2, [x0, #0x17]')
    instruction('ResourceProbe', 28, 'ldur', 'w1, [x2, #0xf]')
    instruction('ResourceProbe', 36, 'bl', f'#{fixture.roles["Resource"][0]:#x}')
    instruction('ClosureAllocateFast', 52, 'stur', 'w1, [x0, #0x13]')
    instruction('ClosureAllocateFast', 56, 'stur', 'w2, [x0, #0x17]')
    instruction('ClosureAllocateFast', 64, 'ldur', 'x4, [x1, #7]')
    instruction('ClosureAllocateFast', 68, 'stur', 'x4, [x0, #0x1f]')
    for role, offset, mnemonic, operands in [
        ('OneByteEquals', 20, 'ldur', 'x16, [x1, #-1]'),
        ('OneByteEquals', 24, 'ubfx', 'x16, x16, #0xc, #0x14'),
        ('OneByteEquals', 28, 'cmp', 'x16, #0x5e'),
        ('OneByteEquals', 36, 'ldur', 'x2, [x0, #7]'),
        ('OneByteEquals', 52, 'asr', 'x2, x2, #1'),
        ('OneByteEquals', 64, 'add', 'x0, x0, #0xf'),
        ('OneByteEquals', 96, 'add', 'x0, x22, #0x30'),
        ('TwoByteEquals', 28, 'cmp', 'x16, #0x5f'),
        ('OneByteAllocateFast', 88, 'mov', 'x16, #0xe05c'),
        ('OneByteAllocateFast', 92, 'movk', 'x16, #5, lsl #16'),
        ('OneByteAllocateFast', 104, 'stur', 'xzr, [x0, #7]'),
        ('OneByteAllocateFast', 108, 'stur', 'w6, [x0, #7]'),
    ]:
        instruction(role, offset, mnemonic, operands)
    # No path branches before AllocateContext overwrites x16. On returning to
    # Has, the sentinel load overwrites it again before any local use.
    for role, count in [('Has', 5), ('ContextAllocateFast', 5)]:
        for op in code[role][:count]:
            reads, _ = op.regs_access()
            assert all(cs.reg_name(r) not in ('x16', 'w16') for r in reads)
            assert not op.mnemonic.startswith(('b', 'cb', 'tb'))
    instruction('ContextAllocateFast', 20, 'ldr', 'x16, [x26, #0x68]')
    instruction('Has', 48, 'ldr', 'x16, [x27, #0x40]')
    factory = pool_pair('Has', 92)
    init_allocations = [i for i, op in enumerate(code['Init'])
                        if op.mnemonic == 'bl' and op.operands[0].imm == fixture.roles['ClosureAllocateFast'][0]]
    assert len(init_allocations) == 1
    callback = pool_pair('Init', (init_allocations[0]-2)*4)
    initializer = pool_pair('Has', 60)
    typ = pool_pair('Should', 44)
    assert pool_pair('Update', 44) == pool_pair('Init', 76) == typ
    assert len({callback, initializer, factory, typ}) == 4
    for role in ('Has', 'Should', 'Update', 'Resource'):
        incoming = fixture.incoming[fixture.roles[role][0]]
        count = {'Has': 2, 'Should': 3 if cache_path else 2, 'Update': 2,
                 'Resource': 1 if cache_path else 2}[role]
        assert len(incoming) == count and all(link for _, link in incoming)
        observed = {op.address for body in code.values() for op in body
                    if op.mnemonic == 'bl' and op.operands[0].imm == fixture.roles[role][0]}
        assert observed == {pc for pc, _ in incoming}
    return {
        'roles': {role: {'rva': address, 'size': size} for role, (address, size) in fixture.roles.items()},
        'direct_callers': {role: [pc for pc, link in fixture.incoming[fixture.roles[role][0]] if link]
                           for role in ('Has', 'Should', 'Update', 'Resource')},
        'factory_pool': factory, 'callback_pool': callback, 'type_pool': typ,
        'x1': 'live package String; Should reloads argument; Update loads ShortcutInfoModel package field',
        'string': {'one_byte_cid': 94, 'two_byte_cid': 95, 'header': -1, 'cid_bits': [12, 20],
                   'tagged_length': 7, 'data': 15, 'false_from_x22': 48},
        'x16': 'no read before AllocateContext +0x14 overwrite; Has +0x30 overwrites before its first read',
        'function_entry': 'ClosureAllocateFast +0x40 loads [Function+7] into closure entry +0x1f',
        'limits': 'static real-image ABI audit; actual Dart VM/GC and launcher rendering are not executed',
    }
