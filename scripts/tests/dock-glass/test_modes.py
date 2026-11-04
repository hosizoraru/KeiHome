#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Unicorn mode POST bridge + real saved native computeMode instruction tests.

--elf accepts the unstripped linked Release library (required for acceptance)
or an AArch64 mode-only ET_REL object for interim development. No build/device
commands run here. Native computeMode executes its original bytes, frame and
RETs; external calls are controlled boundaries. Moving GC is simulated using
the fixture's actual serialized caller stack map, not claimed as VM GC.
"""
import argparse, hashlib, io, json, struct, sys, time
from pathlib import Path
sys.dont_write_bytecode = True
from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE, UC_HOOK_MEM_WRITE
from unicorn import arm64_const as ar
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts/tests/icon-highlight'))
from elf_fixture import Image

X = [getattr(ar, f'UC_ARM64_REG_X{i}') for i in range(31)]
LIB, BRIDGE, HEAP, THREAD, TABLE, POOL, STACK, CTRL = (
    0x10000000, 0x13000000, 0x140000000, 0x50000000,
    0x50010000, 0x60000000, 0x70000000, 0x72000000)
NULL, SENTINEL, RX, DEVICE = HEAP+1, HEAP+0x101, HEAP+0x401, HEAP+0x801
FP, DSP, CSP = STACK+0x18000, STACK+0x17fa0, STACK+0xc000

def unsigned(b, p, leb=False):
    v=0; shift=0
    while True:
        c=b[p]; p+=1
        if leb:
            v|=(c&127)<<shift
            if c<128: return v,p
        else:
            if c>=128: return v|((c-128)<<shift),p
            v|=c<<shift
        shift+=7; assert shift<64

def payload(path):
    raw=path.read_bytes(); e=ELFFile(io.BytesIO(raw))
    assert e['e_machine']=='EM_AARCH64'
    syms={s.name:s['st_value'] for s in e.get_section_by_name('.symtab').iter_symbols()}
    names=('kh_dock_mode_post','kh_dock_mode_replay','kh_dock_mode_config','kh_dock_mode_end')
    assert all(n in syms for n in names), 'Rebuild payload with post-sync mode symbols'
    first,last=syms[names[0]],syms[names[-1]]
    if e['e_type']=='ET_DYN':
        seg=next(s for s in e.iter_segments() if s['p_type']=='PT_LOAD' and s['p_vaddr']<=first and last<=s['p_vaddr']+s['p_filesz'])
        at=seg['p_offset']+first-seg['p_vaddr']; blob=bytearray(raw[at:at+last-first])
        for sec in e.iter_sections():
            if isinstance(sec,RelocationSection):
                assert not any(first<=r['r_offset']<last for r in sec.iter_relocations())
    else:
        assert e['e_type']=='ET_REL' and first==0
        blob=bytearray(e.get_section_by_name('.text').data()[first:last])
        rel=e.get_section_by_name('.rela.text')
        if rel:
            st=e.get_section(rel['sh_link'])
            for r in rel.iter_relocations():
                assert r['r_info_type']==274, 'Only local ADR relocation is expected'
                at=r['r_offset']; delta=st.get_symbol(r['r_info_sym'])['st_value']+r['r_addend']-at
                w=struct.unpack_from('<I',blob,at)[0]
                w=(w&~0x60ffffe0)|((delta&3)<<29)|(((delta>>2)&0x7ffff)<<5)
                struct.pack_into('<I',blob,at,w)
    offsets={n.removeprefix('kh_dock_mode_'):syms[n]-first for n in names}
    assert offsets['end']-offsets['config']==48
    return blob,offsets,{'elf':str(path.resolve()),'sha256':hashlib.sha256(raw).hexdigest(),'kind':e['e_type']}

def fixture(number):
    path=ROOT/f'.artifacts/inputs/launchers/{number}/libapp.so'; im=Image(path)
    md=Cs(CS_ARCH_ARM64,CS_MODE_ARM); md.detail=True
    def named(n):
        [(a,z)]=im.syms[n]; return a,z,list(md.disasm(im.read(a,z),a))
    sync,sz,si=named('_HotseatWidgetState._syncRemoveAnimationState')
    rets=[]
    for k,i in enumerate(si):
        if i.mnemonic=='ret':
            assert si[k-3].op_str=='x0, x22' and si[k-2].op_str=='x15, x29'
            assert si[k-1].op_str=='x29, x30, [x15], #0x10'; rets.append(i.address)
    assert len(rets)==3
    refs=[]
    for lo,hi,ex in im.ranges:
        if not ex: continue
        for j,(w,) in enumerate(struct.iter_unpack('<I',im.read(lo,(hi-lo)//4*4))):
            if w&0xfc000000==0x94000000:
                delta=((w&0x3ffffff)^0x2000000)-0x2000000
                if lo+j*4+delta*4==sync: refs.append(lo+j*4)
    assert len(refs)==1; post=refs[0]+4
    replay=im.read(post,4); assert next(md.disasm(replay,post)).op_str=='x2, [x29, #-0x18]'
    scan=max(0x880000,post-0x3000); pro=im.read(scan,post-scan).rfind(bytes.fromhex('fd79bfa9fd030faa'))
    assert pro>=0; outer=scan+pro
    oi=list(md.disasm(im.read(outer,sync-outer),outer))
    logger=next(i.address for i in oi if i.mnemonic=='bl' and any(a==int(i.op_str[1:],0) for a,z in im.syms['_LoggerImpl.logWithLevel']))
    assert next(i for i in oi if i.address==logger+4).op_str=='x0, [x29, #-8]'
    compute,cz,ci=named('BlurCapabilityCheckerImpl.computeMode')
    assert len([i for i in ci if i.mnemonic=='ret'])==4
    style=ci[4].operands[1].mem.disp
    device=next(i for i in ci if i.address==compute+0x44).operands[1].mem.disp
    [(version_field)]=[i.operands[1].mem.disp for k,i in enumerate(ci[:-1]) if i.mnemonic=='ldur' and i.op_str.startswith('x1, [x0, #') and ci[k+1].mnemonic=='cmp' and ci[k+1].op_str=='x1, #4']
    # Decode actual saved caller stack-map entries, independently of reports.
    elf=ELFFile(io.BytesIO(im.data)); dyn={s.name:s['st_value'] for s in elf.get_section_by_name('.dynsym').iter_symbols()}
    data=dyn['_kDartIsolateSnapshotData']; end=data+4+struct.unpack_from('<Q',im.data,data+4)[0]
    p=im.data.index(b'\0',data+52)+1; header=[]
    for _ in range(5): v,p=unsigned(im.data,p); header.append(v)
    ro=((end+63)&~63)+header[4]+16; globaloff,total,first,pad=struct.unpack_from('<IIII',im.data,ro)
    ibase=dyn['_kDartIsolateSnapshotInstructions']
    [(index,sm)]=[(k,sm) for k in range(total) for pc,sm in [struct.unpack_from('<II',im.data,ro+16+k*8)] if ibase+pc==outer]
    def packed(at):
        h=struct.unpack_from('<I',im.data,at)[0]; return h&3,im.data[at+4:at+4+(h>>2)]
    flags,maps=packed(ro+sm); gf,glob=packed(ro+globaloff); assert flags==2 and gf==1
    p=0; pc=outer; stackmap=None
    while p<len(maps):
        delta,p=unsigned(maps,p,True); pc+=delta; go,p=unsigned(maps,p,True)
        if pc==post:
            spills,q=unsigned(glob,go,True); other,q=unsigned(glob,q,True)
            bitmap=glob[q:q+(spills+other+7)//8]
            stackmap={'spills':spills,'other':other,'bitmap':bitmap.hex(),'object_bits':[k for k in range(spills+other) if bitmap[k//8]>>(k%8)&1]}
    assert stackmap=={'spills':12,'other':0,'bitmap':'0500','object_bits':[0,2]}
    rxa,_,_=named('RxObjectMixin.value'); blur,_,_=named('BlurUtilities.isBlurSupported'); basic,_,_=named('BlurCapabilityCheckerImpl._isBasicBlurSupported')
    logs={a for n in ('_LoggerImpl.logWithTag',) for a,z in im.syms[n]}
    enum_pools=[]
    for k,i in enumerate(ci):
        if i.mnemonic=='ret':
            aw,lw=struct.unpack('<II',im.read(ci[k-4].address,8))
            enum_pools.append(((aw>>10)&4095)<<(12 if aw&(1<<22) else 0))
            enum_pools[-1]+=((lw>>10)&4095)*8
    return dict(number=number,im=im,md=md,post=post,replay=replay,outer=outer,logger=logger,compute=compute,cz=cz,ci=ci,style=style,device=device,version_field=version_field,rxa=rxa,blur=blur,basic=basic,logs=logs,enum_pools=enum_pools,stackmap=stackmap,sync_rets=rets)

def run(f,blob,off,mode=0,guard='ready',bypass='flag',gc=False,bad_index=None,phase_smi=False):
    u=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
    for a,z in ((LIB,0x2000000),(BRIDGE,0x10000),(HEAP,0x40000),(THREAD,0x20000),(POOL,0x100000),(STACK,0x20000),(CTRL,0x1000)): u.mem_map(a,z)
    def put(a,v,n=8): u.mem_write(a,int(v&((1<<(8*n))-1)).to_bytes(n,'little'))
    def get(a,n=8): return int.from_bytes(u.mem_read(a,n),'little')
    post=LIB+f['post']; resume=post+4; compute=LIB+f['compute']; join=LIB+f['logger']+4
    u.mem_write(LIB+f['outer'],f['im'].read(f['outer'],f['logger']+8-f['outer']))
    u.mem_write(compute,f['im'].read(f['compute'],f['cz']))
    code=bytearray(blob); code[off['replay']:off['replay']+4]=f['replay']
    struct.pack_into('<6Q',code,off['config'],post,resume,compute,CTRL,CTRL+8,f['style'])
    u.mem_write(BRIDGE,bytes(code)); delta=BRIDGE-post; assert delta%4==0 and abs(delta)<0x8000000
    put(post,0x14000000|((delta//4)&0x3ffffff),4)
    for k,reg in enumerate(X): u.reg_write(reg,0x80800000+k*0x100)
    original_x1=u.reg_read(X[1])
    for k,v in {0:2 if phase_smi else NULL,15:DSP,21:0x80700000,22:NULL,26:THREAD,27:POOL,28:HEAP>>32,29:FP,30:post+(0x40 if guard=='wrong_lr' else 0)}.items(): u.reg_write(X[k],v)
    u.reg_write(ar.UC_ARM64_REG_SP,CSP)
    put(THREAD+0x78,TABLE); put(POOL+0x40,SENTINEL); put(CTRL,0 if guard=='off' else 1,4); put(CTRL+8,99,4)
    ready={'null':NULL,'smi':2,'late':SENTINEL}.get(guard,RX); put(TABLE+f['style'],ready)
    put(TABLE+f['device'],DEVICE); put(DEVICE+f['version_field'],4)
    for i in f['ci']:
        if i.mnemonic=='ldr' and i.op_str.startswith('x0, [x0, #'):
            a=TABLE+i.operands[1].mem.disp
            if a not in (TABLE+f['style'],TABLE+f['device']): put(a,HEAP+0xc01)
    parent,context,items=HEAP+0x10001,HEAP+0x11001,HEAP+0x12001
    put(FP-8,parent); put(FP-0x18,context); put(context+0x23,NULL+(0x30 if bypass=='flag' else 0x20),4)
    put(context+0xf,6,4); put(context+0x13,items&0xffffffff,4); put(items+0xb,6,4)
    rxi=list(f['md'].disasm(f['im'].read(f['rxa'],f['im'].syms['RxObjectMixin.value'][0][1]),f['rxa']))
    observer=next(i.operands[1].mem.disp for i in rxi if i.mnemonic=='ldr' and i.op_str.startswith('x1, [x1, #'))
    put(TABLE+observer,HEAP+0x901)
    for k,p in enumerate(f['enum_pools']):
        obj=HEAP+0x2001+k*0x40; put(POOL+p,obj); put(obj+7,(bad_index if bad_index is not None else k))
    native_calls={i.address+4:i for i in f['ci'] if i.mnemonic=='bl'}
    external_targets={int(i.op_str[1:],0) for i in native_calls.values()}
    state={'steps':0,'compute_entries':0,'post_entries':0,'replays':0,'writes':[],'gc':0,'registered_rx_owners':[],'native_bl_lr_checks':0}
    expected_regs={k:u.reg_read(X[k]) for k in (15,21,22,26,27,28,29,30)}
    def on_write(uc,access,a,size,value,data):
        pc=uc.reg_read(ar.UC_ARM64_REG_PC)
        if BRIDGE<=pc<BRIDGE+off['config']:
            assert a==CTRL+8 and size==4 and 0<=value<=3, 'Bridge must only publish a primitive mode'
            state['writes'].append(value)
    def on_code(uc,a,size,data):
        state['steps']+=1; assert state['steps']<400, 'Bounded two-phase execution; no retry loop'
        if a==join: uc.emu_stop(); return
        if a==BRIDGE: state['post_entries']+=1
        if a==BRIDGE+off['replay']:
            state['replays']+=1
            assert uc.reg_read(X[15])==DSP and uc.reg_read(X[29])==FP
        if a==compute:
            state['compute_entries']+=1
            assert uc.reg_read(X[30])==post and uc.reg_read(X[1])==original_x1
        if a-LIB not in external_targets: return
        native_return=uc.reg_read(X[30]); i=native_calls[native_return-LIB]
        target=int(i.op_str[1:],0); assert a==LIB+target
        state['native_bl_lr_checks']+=1
        if target==f['rxa']:
            cf=uc.reg_read(X[29]); assert get(cf)==FP and get(cf+8)==post
            assert cf==DSP-16 and uc.reg_read(X[15])==DSP-32
            assert uc.reg_read(X[1])==RX and get(TABLE+observer)==HEAP+0x901
            state['registered_rx_owners'].append(get(TABLE+observer))
            if gc:
                # Dart's stack walker visits bit0 at FP-8, then descends.
                for bit in f['stackmap']['object_bits']:
                    slot=FP-8*(bit+1); old=get(slot); new=old+0x10000
                    u.mem_write(new-1,bytes(u.mem_read(old-1,0x100))); put(slot,new)
                    u.mem_write(old-1,b'\xdd'*0x100)
                state['gc']+=1
            result=2 if mode==0 else 0
            if guard=='toggle_off': put(CTRL,0,4)
        elif target==f['blur']: result=NULL+(0x20 if mode<2 else 0x30)
        elif target==f['basic']: result=NULL+(0x20 if mode==2 else 0x30)
        elif target in f['logs']: result=NULL
        else: raise AssertionError(f'Uncontrolled native call {hex(target)}')
        # The original native BL executed and supplied this actual x30. Only
        # the external boundary is controlled; return to that native PC.
        uc.reg_write(X[0],result); uc.reg_write(ar.UC_ARM64_REG_PC,native_return)
    u.hook_add(UC_HOOK_CODE,on_code); u.hook_add(UC_HOOK_MEM_WRITE,on_write)
    start=time.perf_counter(); u.emu_start(post,join+4,count=500); elapsed=time.perf_counter()-start
    sampled=guard in ('ready','toggle_off') and not phase_smi
    assert state['compute_entries']==int(sampled) and state['post_entries']==1+int(sampled)
    assert state['replays']==1 and u.reg_read(ar.UC_ARM64_REG_PC)==join
    expected_context=context+(0x10000 if gc and sampled else 0)
    assert u.reg_read(X[2])==expected_context and get(FP-0x18)==expected_context
    assert get(FP-8)==parent+(0x10000 if gc and sampled else 0)
    assert u.reg_read(ar.UC_ARM64_REG_SP)==CSP
    for k,v in expected_regs.items(): assert u.reg_read(X[k])==v,(f['number'],k)
    good=sampled and bad_index is None
    assert get(CTRL+8,4)==(mode if good else 99) and state['writes']==([mode] if good else []), (f['number'],mode,guard,bypass,gc,get(CTRL+8,4),state)
    assert len(state['registered_rx_owners'])==int(sampled) and state['gc']==int(gc and sampled)
    assert bytes(u.mem_read(BRIDGE+off['config'],48))==bytes(code[off['config']:off['config']+48]), 'No retained managed pointer in config'
    assert state['native_bl_lr_checks']>=3 if sampled else state['native_bl_lr_checks']==0
    return dict(fixture=f['number'],mode=mode,guard=guard,bypass=bypass,moving_gc=gc,bad_index=bad_index,phase_smi=phase_smi,steps=state['steps'],compute_entries=state['compute_entries'],native_bl_lr_checks=state['native_bl_lr_checks'],root_count=len(f['stackmap']['object_bits']),seconds=elapsed)

def main():
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('--elf',type=Path,required=True); p.add_argument('--report',type=Path); p.add_argument('--fixture',choices=('all','7722','8593','8602','9209','9226'),default='all'); args=p.parse_args()
    if not __debug__: p.error('Assertions must be enabled')
    blob,off,artifact=payload(args.elf); cases=[]
    for number in (('7722','8593','8602','9209','9226') if args.fixture=='all' else (args.fixture,)):
        f=fixture(number)
        for mode in range(4):
            for bypass in ('flag','count'):
                for moving in (False,True): cases.append(run(f,blob,off,mode=mode,bypass=bypass,gc=moving))
        for guard in ('off','null','smi','late','wrong_lr','toggle_off'): cases.append(run(f,blob,off,guard=guard,gc=True))
        for bad in (4,-1): cases.append(run(f,blob,off,bad_index=bad,gc=True))
        cases.append(run(f,blob,off,phase_smi=True))
        print(number,'25 mode POST/native compute/GC-boundary scenarios passed',flush=True)
    report=dict(passed=True,artifact=artifact,release_payload=artifact['kind']=='ET_DYN',cases=len(cases),max_steps=max(c['steps'] for c in cases),details=cases,limits='Real native computeMode bytes/frame/RETs; external calls and moving GC controlled by host. Does not execute the Dart VM GC, Flutter rendering, or devices.')
    if args.report: args.report.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k!='details'}))
if __name__=='__main__': main()
