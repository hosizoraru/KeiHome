#!/usr/bin/env python3
"""Execute the actual linked AArch64 bridges in Unicorn; no Android/ADB."""
# SPDX-License-Identifier: Apache-2.0
# Authored synthetic fixtures only; no launcher bytes are loaded here.
import argparse
import json
import pathlib
import random
import shlex
import struct
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--elf", type=pathlib.Path, required=True, help="ELF linked from the repository bridge and test_symbols.S")
parser.add_argument("--report", type=pathlib.Path, required=True, help="JSON result path outside source fixtures")
parser.add_argument('--layout', type=pathlib.Path, help='Live resolver JSON for dynamic CIDs and slots')
args = parser.parse_args()
layout=json.loads(args.layout.read_text()) if args.layout else {}
FILTER_SLOT=layout.get('filter_pool',0xa7b18); CONTROLLER_SLOT=layout.get('controller_slot',0x2910)
CONTROLLER_CID=layout.get('controller_cid',0xfa4); RX_INT_CID=layout.get('rx_int_cid',0xef2); RX_BOOL_CID=layout.get('rx_bool_cid',0xef6)
if not __debug__:
    parser.error("Emulation assertions require Python without -O/PYTHONOPTIMIZE")
try:
    from elftools.elf.elffile import ELFFile
    from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE, UC_HOOK_MEM_WRITE, UC_HOOK_MEM_READ
    import unicorn.arm64_const as ar
    from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
except (ImportError, OSError) as error:
    requirements = pathlib.Path(__file__).with_name("requirements-emulation.txt")
    print(f"SKIP: optional ARM64 emulation dependencies unavailable: {error}", file=sys.stderr)
    print(f"Install with: {shlex.join([sys.executable, '-m', 'pip', 'install', '-r', str(requirements)])}", file=sys.stderr)
    sys.exit(77)

with args.elf.open('rb') as f:
    e=ELFFile(f)
    segments=[(s['p_vaddr'],s['p_memsz'],s.data()) for s in e.iter_segments() if s['p_type']=='PT_LOAD']
    syms={s.name:s['st_value'] for s in e.get_section_by_name('.symtab').iter_symbols()}
    textsec=e.get_section_by_name('.text'); textbytes=textsec.data(); textaddr=textsec['sh_addr']

H=0x7400000000; PP=H+0x100000; THR=0x500000; TABLE=0x510000
STACK=0x700000; DSP=STACK+0x8000; FP=DSP+0x20; CSP=STACK+0x100; GDT=0x530000
POLICY=0x600000; POLICY2=POLICY+0x100
NULL=H+0x81; SENTINEL=H+0x91; FILTER=H+0x1001
CONTROLLER=H+0x2001; RX=H+0x3001; MINT=H+0x4001
TINT_LR=0x800100; RENDER_LR=0x800200; LAYER_LR=0x800300; OTHER_LR=0x800400
ENABLED_LR=0x800500; SETTINGS_LR=0x800600
X=[getattr(ar,f'UC_ARM64_REG_X{i}') for i in range(31)]
Q=[getattr(ar,f'UC_ARM64_REG_Q{i}') for i in range(32)]

def run(name,kind='tint',success=False,**opt):
    uc=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
    mapped=set()
    def map_region(a,n):
        for p in range(a&~4095,(a+n+4095)&~4095,4096):
            if p not in mapped: uc.mem_map(p,4096);mapped.add(p)
    for a,n,b in segments:map_region(a,n);uc.mem_write(a,b)
    for a,n in [(H,0x200000),(THR,0x2000),(TABLE,0x4000),(GDT,0x1000),(POLICY,0x1000),(STACK,0x10000),(0x800000,0x1000)]:map_region(a,n)
    def w32(a,n):uc.mem_write(a,struct.pack('<I',n&0xffffffff))
    def w64(a,n):uc.mem_write(a,struct.pack('<Q',n&0xffffffffffffffff))
    def r32(a):return struct.unpack('<I',uc.mem_read(a,4))[0]
    for a in [TINT_LR,RENDER_LR,LAYER_LR,OTHER_LR]:w32(a,0xd503201f)
    w64(syms['kh_tint_caller'],TINT_LR)
    w64(syms['kh_render_equals_caller'],RENDER_LR)
    w64(syms['kh_layer_equals_caller'],LAYER_LR)
    w64(syms['kh_monet_enabled_equals_caller'],ENABLED_LR)
    w64(syms['kh_monet_settings_setter_caller'],SETTINGS_LR)
    w64(syms['kh_monet_object_equals_entry'],0x800700)
    w64(GDT+0x1f8,opt.get('gdt_entry',0x800700))
    revision=opt.get('revision',7); notified=opt.get('notified',6)
    w64(syms['kh_monet_selection_revision'],revision)
    w64(syms['kh_monet_selection_notified'],notified)
    w64(syms['kh_monet_policy'],0 if opt.get('null_policy') else POLICY)
    ever_enabled=opt.get('ever_enabled',1)
    w32(syms['kh_monet_tint_ever_enabled'],ever_enabled)
    initial_readers=opt.get('readers',0)
    w32(syms['kh_monet_readers'],initial_readers)
    background=opt.get('background',0xff123456)
    foreground=opt.get('foreground',0xffdfbaff)
    w32(POLICY,opt.get('flags',1)); w32(POLICY+4,foreground)
    w32(POLICY+8,background); w32(POLICY+12,opt.get('count',0))
    w32(POLICY2,1);w32(POLICY2+4,0xffaabbcc);w32(POLICY2+8,background)
    w64(syms['kh_monet_filter_pool_offset'],FILTER_SLOT)
    w64(syms['kh_monet_controller_field_offset'],CONTROLLER_SLOT)
    w32(syms['kh_monet_controller_cid'],CONTROLLER_CID)
    w32(syms['kh_monet_rx_int_cid'],RX_INT_CID)
    w32(syms['kh_monet_rx_bool_cid'],RX_BOOL_CID)
    w64(PP+FILTER_SLOT,FILTER);w64(PP+0x40,SENTINEL)
    w64(THR+0x78,0 if opt.get('null_table') else TABLE)
    w64(TABLE+CONTROLLER_SLOT,opt.get('controller',CONTROLLER))
    w32(CONTROLLER-1,(opt.get('controller_cid',CONTROLLER_CID)<<12)|0x430)
    w32(CONTROLLER+0x1f,opt.get('rx',RX))
    w32(CONTROLLER+0x23,opt.get('rx',RX))
    w32(RX-1,(opt.get('rx_cid',RX_BOOL_CID if kind=='enabled' else RX_INT_CID)<<12)|0x230)
    value=opt.get('selected',background)
    if 'raw_value' in opt:w32(RX+0x13,opt['raw_value'])
    elif opt.get('smi'):w32(RX+0x13,value<<1)
    else:
        w32(RX+0x13,MINT)
        w32(MINT-1,(opt.get('mint_cid',0x3d)<<12)|0x130)
        w64(MINT+7,value)
    w64(FP-0x10,opt.get('parent_filter',FILTER))
    operand=NULL+0x20 if kind=='enabled' else FILTER
    w64(DSP,opt.get('other',operand));w64(DSP+8,opt.get('this',operand))
    w64(FP-8,opt.get('receiver',RX));w64(FP+8,opt.get('settings_caller',SETTINGS_LR))
    rng=random.Random(name)
    for reg in X:uc.reg_write(reg,rng.getrandbits(64))
    for reg in Q:uc.reg_write(reg,rng.getrandbits(128))
    uc.reg_write(X[2],opt.get('argb',0xff000000));uc.reg_write(X[3],opt.get('mode',5))
    uc.reg_write(X[15],DSP);uc.reg_write(X[22],NULL);uc.reg_write(X[26],THR)
    uc.reg_write(X[21],GDT)
    uc.reg_write(X[27],PP);uc.reg_write(X[28],0xc00000074);uc.reg_write(X[29],opt.get('frame',FP))
    lr=opt.get('caller',TINT_LR if kind=='tint' else ENABLED_LR if kind=='enabled' else RENDER_LR)
    uc.reg_write(X[30],lr);uc.reg_write(ar.UC_ARM64_REG_SP,CSP)
    uc.reg_write(ar.UC_ARM64_REG_NZCV,0x60000000)
    before=[uc.reg_read(r) for r in X];vectors=[uc.reg_read(r) for r in Q]
    writes=[]; policy_reads=[]; stop=[]; swapped=False; current_dsp=DSP
    def on_write(u,access,a,n,v,user):
        assert current_dsp-64<=a and a+n<=current_dsp or a==syms['kh_monet_readers'] and n==4 or a==syms['kh_monet_selection_notified'] and n==8,(name,'unexpected write',hex(a),n)
        writes.append((a,n,v))
    def on_read(u,access,a,n,v,user):
        nonlocal swapped
        if opt.get('no_live_roots_read'):
            assert not any(base <= a < base+size for base,size in
                           [(H,0x200000),(THR,0x2000),(TABLE,0x4000),(GDT,0x1000)]), (name,'unrelated caller read live roots')
        if a==syms['kh_monet_policy'] or POLICY<=a<POLICY+16:
            assert r32(syms['kh_monet_readers'])>initial_readers,(name,'unprotected policy read',hex(a))
            policy_reads.append(a)
        if opt.get('swap_policy') and a==POLICY and not swapped:
            w64(syms['kh_monet_policy'],POLICY2);swapped=True
    stops={syms['tint_original_stop'],syms['equals_original_stop'],syms['enabled_original_stop'],lr}
    steps=[]
    def on_code(u,a,n,user):
        steps.append(a)
        if a in stops:stop.append(a);u.emu_stop()
    uc.hook_add(UC_HOOK_MEM_WRITE,on_write);uc.hook_add(UC_HOOK_MEM_READ,on_read)
    uc.hook_add(UC_HOOK_CODE,on_code)
    entry={'tint':'kh_monet_tint_bridge','equals':'kh_monet_filter_equals_bridge','enabled':'kh_monet_enabled_equals_bridge'}[kind]
    invocations=opt.get('repeat',1)
    total_writes=total_policy_reads=0
    for invocation in range(invocations):
        if invocation:
            assert kind=='equals' and opt.get('withdraw') in ('disabled','null')
            # Reuse this VM and its untouched history flag. Later setter frames
            # represent distinct owners whose old filters can survive withdrawal.
            if opt['withdraw']=='null':w64(syms['kh_monet_policy'],0)
            else:w32(POLICY,0)
            current_dsp=DSP+invocation*0x100
            before[15]=current_dsp;before[29]=current_dsp+0x20
            w64(current_dsp,FILTER);w64(current_dsp+8,FILTER)
            w64(before[29]-8,H+0x5001+invocation*0x100)
            for r,value in zip(X,before):uc.reg_write(r,value)
            writes.clear();policy_reads.clear();stop.clear();steps.clear()
        uc.emu_start(syms[entry],0,count=20000)
        assert len(stop)==1,(name,invocation,'no terminal transfer')
        if 'max_instructions' in opt:assert len(steps)<=opt['max_instructions'],(name,'hot-path instruction budget',len(steps))
        expected_stop=lr if kind!='tint' and success else syms[kind+'_original_stop']
        assert stop[0]==expected_stop,(name,invocation,'wrong transfer',hex(stop[0]),hex(expected_stop))
        allowed={2,16} if kind=='tint' and success else {0} if kind!='tint' and success else {16}
        for i,r in enumerate(X):
            if i not in allowed:assert uc.reg_read(r)==before[i],(name,'clobber x'+str(i))
        assert [uc.reg_read(r) for r in Q]==vectors,(name,'SIMD clobber')
        assert uc.reg_read(ar.UC_ARM64_REG_NZCV)==0x60000000,(name,'flags clobber')
        assert uc.reg_read(ar.UC_ARM64_REG_SP)==CSP,(name,'native SP changed')
        assert r32(syms['kh_monet_readers'])==initial_readers,(name,'reader leak')
        assert r32(syms['kh_monet_tint_ever_enabled'])==ever_enabled,(name,'tint history changed')
        if kind=='tint':assert uc.reg_read(X[2])==(foreground if success else before[2]),(name,'ARGB')
        elif success:assert uc.reg_read(X[0])==NULL+0x30,(name,'not Dart false')
        if opt.get('no_policy_read'):assert not policy_reads,(name,'unexpected policy access')
        assert struct.unpack('<Q',uc.mem_read(syms['kh_monet_selection_notified'],8))[0] == (revision if kind=='enabled' and success else notified)
        if kind in ('equals','enabled'):
            assert not policy_reads, (name, 'equality must not consult policy')
            assert not any(a == syms['kh_monet_readers'] for a, _, _ in writes), (name, 'equality must not pin readers')
        total_writes+=len(writes);total_policy_reads+=len(policy_reads)
    return {'name':name,'passed':True,'invocations':invocations,'writes':total_writes,'policy_reads':total_policy_reads}

cases=[
 ('tint-match',dict(success=True)),
 ('tint-count-zero-expiry',dict(success=True,count=0)),
 ('tint-count-nonzero',dict(success=True,count=512)),
 ('tint-signed-mint',dict(success=True,selected=0xff123456-(1<<32))),
 ('tint-positive-smi',dict(success=True,smi=True,selected=123,background=123)),
 ('tint-negative-smi',dict(success=True,smi=True,selected=-123,background=(1<<32)-123)),
 ('tint-zero-smi',dict(success=True,smi=True,selected=0,background=0)),
 ('tint-policy-swap-held-snapshot',dict(success=True,swap_policy=True)),
 ('tint-existing-reader',dict(success=True,readers=7)),
 ('tint-other-caller',dict(caller=OTHER_LR,no_policy_read=True)),
 ('tint-other-filter',dict(parent_filter=FILTER+0x100,no_policy_read=True)),
 ('tint-wrong-mode',dict(mode=3,no_policy_read=True)),
 ('tint-nonblack-input',dict(argb=0xffffffff,no_policy_read=True)),
 ('tint-null-policy',dict(null_policy=True)),
 ('tint-disabled',dict(flags=0)),
 ('tint-reserved-flags',dict(flags=3)),
 ('tint-reader-saturation',dict(readers=0xffffffff,no_policy_read=True)),
 ('tint-null-table',dict(null_table=True)),
 ('tint-null-controller',dict(controller=NULL)),
 ('tint-sentinel-controller',dict(controller=SENTINEL)),
 ('tint-smi-controller',dict(controller=4)),
 ('tint-controller-cid',dict(controller_cid=0xfa5)),
 ('tint-null-rx',dict(rx=NULL)),
 ('tint-sentinel-rx',dict(rx=SENTINEL)),
 ('tint-smi-rx',dict(rx=4)),
 ('tint-rx-cid',dict(rx_cid=0xef3)),
 ('tint-null-value',dict(raw_value=NULL)),
 ('tint-sentinel-value',dict(raw_value=SENTINEL)),
 ('tint-nonint-value',dict(mint_cid=0x3e)),
 ('tint-background-mismatch',dict(selected=0xff123457)),
 ('tint-large-int-alias',dict(selected=0x1ff123456)),
 ('tint-negative-int-alias',dict(selected=0xff123456-(2<<32))),
 ('equals-render',dict(kind='equals',success=True)),
 ('equals-layer',dict(kind='equals',success=True,caller=LAYER_LR)),
 ('equals-other-caller',dict(kind='equals',caller=OTHER_LR,no_policy_read=True)),
 ('equals-other-operand',dict(kind='equals',other=FILTER+0x20,no_policy_read=True)),
 ('equals-this-operand',dict(kind='equals',this=FILTER+0x20,no_policy_read=True)),
 ('equals-null-policy',dict(kind='equals',success=True,null_policy=True)),
 ('equals-disabled',dict(kind='equals',success=True,flags=0)),
 ('equals-reserved-flags',dict(kind='equals',success=True,flags=2)),
 ('equals-reader-saturation',dict(kind='equals',success=True,readers=0xffffffff,no_policy_read=True)),
 ('equals-manual-background-restores-stock',dict(kind='equals',success=True,selected=0xff123457)),
 ('equals-expired-denies-retain-colors',dict(kind='equals',success=True,count=0)),
]
assert len(cases) == 43
cases += [
 ('enabled-policy-change',dict(kind='enabled',success=True)),
 ('enabled-withdrawal',dict(kind='enabled',success=True,null_policy=True)),
 ('enabled-already-notified',dict(kind='enabled',notified=7)),
 ('enabled-never-rewind',dict(kind='enabled',notified=8)),
 ('enabled-no-selection-change',dict(kind='enabled',revision=0)),
 ('enabled-unrelated-equality',dict(kind='enabled',caller=OTHER_LR,frame=0,no_live_roots_read=True,max_instructions=16)),
 ('enabled-unrelated-rx-setter',dict(kind='enabled',settings_caller=OTHER_LR)),
 ('enabled-unrelated-rx',dict(kind='enabled',receiver=RX+0x100)),
 ('enabled-off',dict(kind='enabled',this=NULL+0x30,other=NULL+0x30)),
 ('enabled-real-enable-transition',dict(kind='enabled',this=NULL+0x30)),
 ('enabled-real-disable-transition',dict(kind='enabled',other=NULL+0x30)),
 ('enabled-missing-controller',dict(kind='enabled',controller=NULL)),
 ('enabled-wrong-controller-class',dict(kind='enabled',controller_cid=0xfa5)),
 ('enabled-wrong-rx-class',dict(kind='enabled',rx_cid=0xef2)),
 ('enabled-uninitialized-rx',dict(kind='enabled',rx=SENTINEL)),
 ('enabled-missing-static-table',dict(kind='enabled',null_table=True)),
 ('enabled-mismatched-gdt',dict(kind='enabled',gdt_entry=0x800704)),
 ('enabled-mismatched-frame',dict(kind='enabled',frame=FP+8)),
]
for caller_name,caller in [('render',RENDER_LR),('layer',LAYER_LR)]:
    cases += [
        (f'equals-{caller_name}-never-enabled-null',dict(kind='equals',caller=caller,ever_enabled=0,null_policy=True,no_live_roots_read=True)),
        (f'equals-{caller_name}-never-enabled-disabled',dict(kind='equals',caller=caller,ever_enabled=0,flags=0,no_live_roots_read=True)),
        (f'equals-{caller_name}-enabled-then-disabled-all-instances',dict(kind='equals',caller=caller,success=True,repeat=4,withdraw='disabled')),
        (f'equals-{caller_name}-enabled-then-null-all-instances',dict(kind='equals',caller=caller,success=True,repeat=4,withdraw='null')),
    ]
results=[run(n,**o) for n,o in cases]
instructions=list(Cs(CS_ARCH_ARM64,CS_MODE_ARM).disasm(textbytes,textaddr))
assert not any(i.mnemonic in ('bl','blr','svc','hvc','smc') for i in instructions)
report={'unicorn_cases':len(results),'all_passed':True,'no_calls_or_syscalls':True,
        'scoped_equality':'Stock until first enabled publication; process-lifetime invalidation thereafter for every instance, including withdrawal; no policy reads or reader-counter writes',
        'registers_checked':'All X except intended result and dead x16 tail scratch; all Q; x15, native SP, x28, FP, LR, NZCV',
        'memory_writes_allowed':'64-byte temporary Dart stack; atomic reader counter; primitive selection notification generation',
        'limits':'Instruction emulation, not VM/Flutter/device validation or an ARM weak-memory concurrency proof',
        'cases':results}
args.report.parent.mkdir(parents=True, exist_ok=True)
args.report.write_text(json.dumps(report,indent=2)+'\n')
print(f'bridges: {len(results)} AArch64 cases passed; registers/vectors/flags restored; policy pinned; no heap writes/calls')
