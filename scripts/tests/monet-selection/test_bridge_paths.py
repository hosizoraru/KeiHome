#!/usr/bin/env python3
"""Host-only instruction-path analysis of the linked AArch64 bridge.

No native ARM/Dart code is executed. Capstone decodes the compiled instructions;
this bounded model evaluates only the audited integer/memory/control subset.
Every unexpected instruction, memory access or preservation failure is fatal.
"""
import hashlib,json,struct
from pathlib import Path
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from capstone.arm64_const import ARM64_OP_REG,ARM64_OP_IMM,ARM64_OP_MEM
import sys
sys.dont_write_bytecode = True
from elf_reader import Elf
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path(__file__).resolve().parent
elf=Elf((ROOT/'selection_bridge_test.elf').read_bytes())
syms={n:(v,s) for v,s,n,_,_ in elf.symbols()}
start,size=syms['kh_monet_selection_bridge']
code=elf.read(start,size)
cs=Cs(CS_ARCH_ARM64,CS_MODE_ARM);cs.detail=True
instructions={i.address:i for i in cs.disasm(code,start)}
assert len(instructions)*4==size
assert not any(i.mnemonic in ('bl','blr','svc','hvc','smc','brk') for i in instructions.values())
assert sum(i.mnemonic=='ret' for i in instructions.values())==1
assert sum(i.mnemonic=='br' for i in instructions.values())==1

class Model:
 def __init__(self, names=(b'com.example.bad',), package=b'com.example.bad', flags=1,count=None,
              cid=94,length=None,ptr=None,ready=1,pool_bad=None,reader_count=0,fail_cas=False):
  self.mem={};self.reg={f'x{i}':0x11110000+i*0x100 for i in range(31)}
  self.reg.update(sp=0xb000000,x15=0x9001000,x22=0x6000001,x27=0x8000000,x30=0xc0de0000)
  self.arg=0x5000001;self.policy=0xa000000
  self.reg['x1']=self.arg if ptr is None else ptr
  self.nzcv=0xb0000000;self.initial=dict(self.reg);self.flags0=self.nzcv
  self.events=[];self.steps=0;self.fail_cas=fail_cas;self.write_events=[]
  self.bounds=[(0x9000000,0x9002000,'scratch'),(0x5000000,0x5000200,'argument'),
               (0x6000000,0x6000200,'null'),(0x7000000,0x7000200,'function'),
               (0x8000000,0x8041000,'pool'),(self.policy,self.policy+16+512*260,'policy')]
  for p in elf.loads:
   if p[0]==1 and p[1]&2:
    self.bounds.append((p[3],p[3]+p[5],'globals'))
    for k,b in enumerate(elf.data[p[2]:p[2]+p[5]]):self.mem[p[3]+k]=b
  self.setsym('kh_monet_policy',self.policy,8)
  self.setsym('kh_monet_readers',reader_count,4)
  self.setsym('kh_selection_profile_ready',ready,4)
  self.put(self.policy,flags,4);self.put(self.policy+12,len(names) if count is None else count,4)
  for j,name in enumerate(names):
   self.put(self.policy+16+j*260,len(name),4)
   self.bytes(self.policy+20+j*260,name)
  self.put(self.arg-1,cid<<12,8)
  self.put(self.arg+7,2*len(package) if length is None else length,8)
  self.bytes(self.arg+15,package)
  self.put(0x80404f8,0x7000001,8);self.put(0x80404e8,0x7000101,8)
  self.put(0x8000040,0x6000101,8)
  self.put(0x7000008,0x19e8678,8);self.put(0x7000108,0xebf8dc,8)
  if pool_bad=='factory':self.put(0x7000008,0x19e8680,8)
  if pool_bad=='callback':self.put(0x7000108,0xebf8e0,8)
  if pool_bad=='null':self.put(0x80404f8,self.reg['x22'],8)
  if pool_bad=='smi':self.put(0x80404f8,2,8)
  if pool_bad=='sentinel':self.put(0x80404f8,0x6000101,8)
  self.initial_memory=dict(self.mem)
 def setsym(self,n,v,size):self.put(syms[n][0],v,size)
 def bytes(self,a,b):
  for i,x in enumerate(b):self.mem[a+i]=x
 def put(self,a,v,size):self.bytes(a,int(v & ((1<<(size*8))-1)).to_bytes(size,'little'))
 def region(self,a,n):
  for lo,hi,name in self.bounds:
   if lo<=a and a+n<=hi:return name
  raise AssertionError(f'unbounded read/write {a:x}+{n}')
 def read(self,a,n):
  region=self.region(a,n)
  if region=='policy':
   count=int.from_bytes(bytes(self.mem.get(syms['kh_monet_readers'][0]+i,0) for i in range(4)),'little')
   assert count>0,'policy read without reader pin'
  self.events.append(('read',region,a,n))
  return int.from_bytes(bytes(self.mem.get(a+i,0) for i in range(n)),'little')
 def write(self,a,v,n):
  region=self.region(a,n)
  assert region=='scratch' or (a==syms['kh_monet_readers'][0] and n==4),f'non-scratch/non-counter write: {a:x}'
  self.write_events.append((a,v,n));self.put(a,v,n)
 def rn(self,ins,op):return ins.reg_name(op.reg)
 def getreg(self,n):
  if n in ('wzr','xzr'):return 0
  if n.startswith('w'):return self.reg['x'+n[1:]]&0xffffffff
  return self.reg[n]
 def setreg(self,n,v):
  if n in ('wzr','xzr'):return
  if n.startswith('w'):self.reg['x'+n[1:]]=v&0xffffffff
  else:self.reg[n]=v&0xffffffffffffffff
 def val(self,ins,op):
  if op.type==ARM64_OP_IMM:return op.imm
  if op.type==ARM64_OP_REG:return self.getreg(self.rn(ins,op))
  raise AssertionError('operand')
 def addr(self,ins,op):
  assert op.type==ARM64_OP_MEM
  return (self.getreg(ins.reg_name(op.mem.base))+(self.getreg(ins.reg_name(op.mem.index)) if op.mem.index else 0)+op.mem.disp)&0xffffffffffffffff
 def width(self,ins,op):return 4 if self.rn(ins,op).startswith('w') else 8
 def compare(self,a,b,bits,addition=False):
  mask=(1<<bits)-1;sign=1<<(bits-1);a&=mask;b&=mask
  raw=a+b if addition else a-b;r=raw&mask
  n=bool(r&sign);z=r==0;c=raw>mask if addition else a>=b
  v=bool((~(a^b)&(a^r)&sign) if addition else ((a^b)&(a^r)&sign))
  self.nzcv=(int(n)<<31)|(int(z)<<30)|(int(c)<<29)|(int(v)<<28)
 def cond(self,name):
  n,z,c,v=[bool(self.nzcv&(1<<i)) for i in (31,30,29,28)]
  return {'eq':z,'ne':not z,'hi':c and not z,'lo':not c,'hs':c,'ls':not c or z}[name]
 def run(self):
  pc=start
  for _ in range(3000000):
   i=instructions[pc];m=i.mnemonic;o=i.operands;self.steps+=1;nextpc=pc+4
   if m in ('bti','nop','clrex'):pass
   elif m=='dmb':self.events.append(('barrier',i.op_str))
   elif m=='mrs':self.setreg(self.rn(i,o[0]),self.nzcv)
   elif m=='msr':self.nzcv=self.val(i,o[1])&0xf0000000
   elif m in ('mov','adr','adrp'):self.setreg(self.rn(i,o[0]),self.val(i,o[1]))
   elif m in ('add','sub','subs'):
    a=self.val(i,o[1]);b=self.val(i,o[2]);r=a+b if m=='add' else a-b
    self.setreg(self.rn(i,o[0]),r)
    if m=='subs':self.compare(a,b,self.width(i,o[0])*8)
   elif m in ('cmp','cmn'):self.compare(self.val(i,o[0]),self.val(i,o[1]),self.width(i,o[0])*8,m=='cmn')
   elif m=='ubfx':self.setreg(self.rn(i,o[0]),(self.val(i,o[1])>>o[2].imm)&((1<<o[3].imm)-1))
   elif m=='lsr':self.setreg(self.rn(i,o[0]),self.val(i,o[1])>>o[2].imm)
   elif m in ('ldr','ldur','ldrb','ldar','ldaxr'):
    n=1 if m=='ldrb' else self.width(i,o[0]);a=self.addr(i,o[1]);v=self.read(a,n)
    if m=='ldar' and a==syms['kh_monet_policy'][0]:
     assert any(e[0]=='barrier' and e[1]=='ish' for e in self.events),'no DMB before policy LDAR'
    self.setreg(self.rn(i,o[0]),v)
   elif m=='ldp':
    a=self.addr(i,o[2]);n=self.width(i,o[0]);self.setreg(self.rn(i,o[0]),self.read(a,n));self.setreg(self.rn(i,o[1]),self.read(a+n,n))
   elif m=='str':self.write(self.addr(i,o[1]),self.val(i,o[0]),self.width(i,o[0]))
   elif m=='stp':
    a=self.addr(i,o[2]);n=self.width(i,o[0]);self.write(a,self.val(i,o[0]),n);self.write(a+n,self.val(i,o[1]),n)
   elif m=='stlxr':
    if self.fail_cas:self.fail_cas=False;self.setreg(self.rn(i,o[0]),1)
    else:self.write(self.addr(i,o[2]),self.val(i,o[1]),self.width(i,o[1]));self.setreg(self.rn(i,o[0]),0)
   elif m in ('cbz','cbnz'):
    if (self.val(i,o[0])==0)==(m=='cbz'):nextpc=o[1].imm
   elif m in ('tbz','tbnz'):
    if bool(self.val(i,o[0])&(1<<o[1].imm))==(m=='tbnz'):nextpc=o[2].imm
   elif m=='b':nextpc=o[0].imm
   elif m.startswith('b.'):
    if self.cond(m[2:]):nextpc=o[0].imm
   elif m in ('br','ret'):
    path='original' if m=='br' else 'false'
    if path=='original':assert self.val(i,o[0])==0x400000
    else:assert self.reg['x0']==self.reg['x22']+0x30
    for n,v in self.initial.items():
     if n==('x16' if path=='original' else 'x0'):continue
     assert self.reg[n]==v,(path,'changed register',n,hex(self.reg[n]),hex(v))
    assert self.nzcv==self.flags0,('NZCV',self.nzcv,self.flags0)
    counter=self.read(syms['kh_monet_readers'][0],4)
    old=int.from_bytes(bytes(self.initial_memory[syms['kh_monet_readers'][0]+k] for k in range(4)),'little')
    assert counter==old,('reader leak',counter,old)
    return path
   else:raise AssertionError((hex(pc),m,i.op_str))
   pc=nextpc
  raise AssertionError('instruction step budget exceeded')

cases=[
 ('exact deny','false',{}),('enabled extra flag bits','false',{'flags':3}),
 ('disabled policy','original',{'flags':0}),('empty table','original',{'names':()}),
 ('different package','original',{'package':b'com.example.good'}),
 ('prefix is not equal','original',{'package':b'com.example.bad.extra'}),
 ('shorter is not equal','original',{'package':b'com.example.ba'}),
 ('case sensitive','original',{'package':b'com.example.Bad'}),
 ('CID95','original',{'cid':95}),('non String CID','original',{'cid':60}),
 ('Smi argument','original',{'ptr':2}),('null argument','original',{'ptr':0x6000001}),
 ('empty String','original',{'package':b''}),('odd tagged length','original',{'length':3}),
 ('high word length','original',{'length':(1<<32)+30}),('negative length','original',{'length':-2}),
 ('256 length','original',{'package':b'a'*256}),('255 exact length','false',{'names':(b'a'*255,), 'package':b'a'*255}),
 ('non ASCII','original',{'names':(b'bad\xff',),'package':b'bad\xff'}),
 ('embedded NUL','original',{'names':(b'bad\x00',),'package':b'bad\x00'}),
 ('oversize count','original',{'count':513}),('not ready','original',{'ready':0}),
 ('publishing state','original',{'ready':2}),('factory mismatch','original',{'pool_bad':'factory'}),
 ('callback mismatch','original',{'pool_bad':'callback'}),('factory null','original',{'pool_bad':'null'}),
 ('factory Smi','original',{'pool_bad':'smi'}),('factory sentinel','original',{'pool_bad':'sentinel'}),
 ('saturated readers','original',{'reader_count':0xffffffff}),
 ('concurrent reader preserved','false',{'reader_count':5}),('CAS retry','false',{'fail_cas':True}),
 ('last of 512','false',{'names':tuple(f'com.p{i}'.encode() for i in range(512)), 'package':b'com.p511'}),
 ('zero row after match','original',{'names':(b'com.example.bad',b'')}),
]
results=[]
for name,path,kw in cases:
 m=Model(**kw)
 actual=m.run();assert actual==path,(name,actual,path)
 results.append({'case':name,'path':actual,'instructions':m.steps})
# Memory/read ordering-specific corruptions use the compiled code as well.
m=Model();m.setsym('kh_monet_policy',0,8);assert m.run()=='original';results.append({'case':'null policy','path':'original'})
m=Model();m.put(m.policy+16,256,4);assert m.run()=='original';results.append({'case':'oversize entry length','path':'original'})
result={'native_execution':False,'method':'bounded decoded instruction-path model',
        'compiled_instructions':len(instructions),'text_sha256':hashlib.sha256(code).hexdigest(),
        'forbidden_calls_or_syscalls':0,'cases':results,'passed':len(results)}
(ROOT/'bridge-path-tests.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:v for k,v in result.items() if k!='cases'}))
